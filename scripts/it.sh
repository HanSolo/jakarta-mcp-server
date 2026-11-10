#!/usr/bin/env bash
#
# Runs the integration tests (module mcp-it) against one or more application servers, after making sure that Testcontainers can
# talk to Docker, and says plainly whether the tests really ran.
#
# Why this exists: the integration tests are SKIPPED, not failed, when Testcontainers finds no usable Docker, and Maven still prints
# BUILD SUCCESS. This script reads the Failsafe reports and fails when a test class was skipped or did not run.
#
# Usage:
#   scripts/it.sh                                 all three servers: payara, wildfly, liberty
#   scripts/it.sh --servers payara                one server (or a comma separated list)
#   scripts/it.sh --no-pull                       do not pull the container images first
#   scripts/it.sh --conformance                   after the integration tests, run the MCP conformance suite against each server
#   scripts/it.sh --only-conformance              the conformance suite only (no integration tests)
#   scripts/it.sh --conformance-tasks             the conformance suite only, without expected-failures.yml (the Tasks extension is on in every conformance run),
#                                                 without expected-failures.yml, so every tasks-* scenario shows its real result
#   scripts/it.sh -- -Dmcp.image=payara/micro:7   everything after -- goes to Maven (for every server)
#
# The conformance suite (npx @modelcontextprotocol/conformance@alpha, needs Node 22+) runs against the mcp-example-conformance WAR on the
# same server images, with mcp-example-conformance/expected-failures.yml. A listed scenario that passes counts as a failure here too.
#
# Run it from a checkout of the version you want to test (git checkout v0.4.0); the script never changes your checkout.
# Logs, Failsafe reports and a summary go to it-logs/<timestamp>/. Needs JDK 21+, Maven, and a running Docker.
#
# It works on macOS (bash 3.2) and Linux.

set -u
set -o pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT" || exit 2

# These mirror the defaults in mcp-it/src/test/java/eu/hansolo/mcp/it/ServerFixture.java and KeycloakSupport.java.
KEYCLOAK_IMAGE="quay.io/keycloak/keycloak:26.4"
DEFAULT_API_VERSION="1.44"
DOCKER_JAVA_PROPERTIES="${HOME}/.docker-java.properties"

SERVERS="payara,wildfly,liberty"
PULL=1
RUN_IT=1
RUN_CONFORMANCE=0
CONFORMANCE_TASKS=0
MVN_EXTRA=()

usage() {
    cat <<'EOF'
Usage:
  scripts/it.sh                                 all three servers: payara, wildfly, liberty
  scripts/it.sh --servers payara                one server (or a comma separated list)
  scripts/it.sh --no-pull                       do not pull the container images first
  scripts/it.sh --conformance                   after the integration tests, run the MCP conformance suite against each server
  scripts/it.sh --only-conformance              the conformance suite only (no integration tests)
  scripts/it.sh --conformance-tasks             the conformance suite only, without expected-failures.yml (the Tasks extension is on in every conformance run) and
                                                without expected-failures.yml, so every tasks-* scenario shows its real result
  scripts/it.sh -- -Dmcp.image=payara/micro:7   everything after -- goes to Maven (for every server)

Run it from a checkout of the version you want to test. Logs and a summary go to it-logs/<timestamp>/.
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

say() {
    echo "[it] $*"
}

image_for() {
    case "$1" in
        payara)  echo "payara/micro:latest" ;;
        wildfly) echo "quay.io/wildfly/wildfly:latest-jdk21" ;;
        liberty) echo "icr.io/appcafe/open-liberty:full-java21-openj9-ubi-minimal" ;;
    esac
}

# True if "major.minor" $1 is at least $2.
version_ge() {
    local a_major=${1%%.*} a_minor=${1#*.} b_major=${2%%.*} b_minor=${2#*.}
    [ "$a_major" -gt "$b_major" ] || { [ "$a_major" -eq "$b_major" ] && [ "$a_minor" -ge "$b_minor" ]; }
}

is_version() {
    case "$1" in
        ''|*[!0-9.]*|.*|*.|*.*.*) return 1 ;;
        *.*) return 0 ;;
        *)   return 1 ;;
    esac
}

image_id() {
    docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}{{.Id}}{{end}}' "$1" 2>/dev/null || echo "not pulled"
}

# ---- arguments -------------------------------------------------------------------------------------------------------

while [ $# -gt 0 ]; do
    case "$1" in
        --servers)  [ $# -ge 2 ] || die "--servers needs a value"; SERVERS="$2"; shift 2 ;;
        --no-pull)  PULL=0; shift ;;
        --conformance)      RUN_CONFORMANCE=1; shift ;;
        --only-conformance) RUN_CONFORMANCE=1; RUN_IT=0; shift ;;
        --conformance-tasks) RUN_CONFORMANCE=1; RUN_IT=0; CONFORMANCE_TASKS=1; shift ;;
        -h|--help)  usage; exit 0 ;;
        --)         shift; MVN_EXTRA=("$@"); break ;;
        *)          usage >&2; die "unknown option: $1" ;;
    esac
done

SERVER_LIST=$(echo "$SERVERS" | tr ',' ' ')
[ -n "$(echo "$SERVER_LIST" | tr -d ' ')" ] || die "no server given"
for server in $SERVER_LIST; do
    case "$server" in
        payara|wildfly|liberty) ;;
        *) die "unknown server '$server': use payara, wildfly or liberty" ;;
    esac
done

# ---- preflight -------------------------------------------------------------------------------------------------------

say "checking the build environment"

command -v java >/dev/null 2>&1 || die "java not found: install JDK 21 or newer"
JAVA_VERSION_LINE=$(java -version 2>&1 | grep -m1 ' version "' || true)   # JAVA_TOOL_OPTIONS makes java print a "Picked up" line first
JAVA_MAJOR=$(printf '%s' "$JAVA_VERSION_LINE" | sed -E 's/.*version "([0-9]+).*/\1/')
case "$JAVA_MAJOR" in ''|*[!0-9]*) JAVA_MAJOR=0 ;; esac
[ "$JAVA_MAJOR" -ge 21 ] || die "JDK 21 or newer is needed, found: ${JAVA_VERSION_LINE:-no version line in the output of java -version}"

command -v mvn >/dev/null 2>&1 || die "mvn not found: install Maven 3.9 or newer"

command -v docker >/dev/null 2>&1 || die "docker not found: install Docker Desktop (or another Docker engine) and start it"
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: 'docker info' fails: the Docker engine is not running or not reachable." >&2
    echo "       Start Docker Desktop and wait until it says 'running', then try again." >&2
    echo "       Active context: $(docker context show 2>/dev/null || echo unknown). 'docker context ls' lists the others." >&2
    exit 2
fi

# Testcontainers looks for /var/run/docker.sock (or DOCKER_HOST). The docker CLI also follows 'docker context', so it can work while
# Testcontainers finds nothing: Docker Desktop with the default socket switched off, Colima, Rancher Desktop.
if [ -z "${DOCKER_HOST:-}" ] && [ ! -S /var/run/docker.sock ]; then
    CONTEXT_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)
    case "$CONTEXT_HOST" in
        unix://*)
            if [ -S "${CONTEXT_HOST#unix://}" ]; then
                export DOCKER_HOST="$CONTEXT_HOST"
                say "no /var/run/docker.sock: using DOCKER_HOST=$DOCKER_HOST (from the active docker context) for this run"
            fi
            ;;
    esac
fi

# The Docker client inside Testcontainers 2.0.1 talks API 1.32. Newer engines (observed with Docker Desktop 29) refuse that, and
# Testcontainers then reports Docker as unavailable although 'docker info' works.
SERVER_API=$(docker version --format '{{.Server.APIVersion}}' 2>/dev/null || true)
MIN_API=$(docker version --format '{{.Server.MinAPIVersion}}' 2>/dev/null || true)
if is_version "$SERVER_API" && is_version "$MIN_API"; then
    if ! version_ge "1.32" "$MIN_API"; then
        WANTED_API="$DEFAULT_API_VERSION"
        version_ge "$WANTED_API" "$MIN_API" || WANTED_API="$MIN_API"
        if [ -f "$DOCKER_JAVA_PROPERTIES" ] && grep -q '^api\.version=' "$DOCKER_JAVA_PROPERTIES"; then
            CONFIGURED_API=$(grep '^api\.version=' "$DOCKER_JAVA_PROPERTIES" | tail -1 | cut -d= -f2 | tr -d '[:space:]')
            if is_version "$CONFIGURED_API" && version_ge "$CONFIGURED_API" "$MIN_API" && version_ge "$SERVER_API" "$CONFIGURED_API"; then
                say "docker engine API $SERVER_API (minimum $MIN_API); $DOCKER_JAVA_PROPERTIES asks for $CONFIGURED_API: fine"
            else
                die "$DOCKER_JAVA_PROPERTIES sets api.version=$CONFIGURED_API, but this engine accepts $MIN_API to $SERVER_API. Edit that line (1.44 works for Docker Desktop 29)."
            fi
        elif version_ge "$SERVER_API" "$WANTED_API"; then
            echo "api.version=$WANTED_API" >> "$DOCKER_JAVA_PROPERTIES"
            say "docker engine API $SERVER_API refuses the API 1.32 that Testcontainers uses (minimum $MIN_API): added api.version=$WANTED_API to $DOCKER_JAVA_PROPERTIES (once per machine)"
        else
            die "the docker engine speaks API $MIN_API to $SERVER_API, and Testcontainers 2.0.1 cannot use that range. Set testcontainers.version in pom.xml to a newer release."
        fi
    else
        say "docker engine API $SERVER_API (minimum $MIN_API): no client setting needed"
    fi
else
    say "could not read the docker engine API version (server '$SERVER_API', minimum '$MIN_API'); continuing"
fi

if [ "$RUN_CONFORMANCE" -eq 1 ]; then
    command -v npx >/dev/null 2>&1 || die "npx not found: the conformance suite needs Node 22 or newer"
    NODE_MAJOR=$(node -v 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/')
    case "$NODE_MAJOR" in ''|*[!0-9]*) NODE_MAJOR=0 ;; esac
    [ "$NODE_MAJOR" -ge 22 ] || die "Node 22 or newer is needed for the conformance suite, found: $(node -v 2>/dev/null || echo none)"
fi

if [ "$PULL" -eq 1 ]; then
    IMAGES="$KEYCLOAK_IMAGE"
    for server in $SERVER_LIST; do
        IMAGES="$IMAGES $(image_for "$server")"
    done
    for image in $IMAGES; do
        say "pulling $image"
        docker pull -q "$image" >/dev/null || say "WARNING: could not pull $image; Testcontainers will try again and the tests fail if it cannot get it either"
    done
fi

# ---- run -------------------------------------------------------------------------------------------------------------

LOGDIR="$ROOT/it-logs/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOGDIR" || die "cannot create $LOGDIR"
SUMMARY="$LOGDIR/summary.txt"
OVERALL=0

{
    echo "Integration test run $(date '+%Y-%m-%d %H:%M:%S')"
    echo "source:   $(git describe --tags --always --dirty 2>/dev/null || echo 'not a git checkout')"
    echo "java:     $JAVA_VERSION_LINE"
    echo "maven:    $(mvn -v 2>/dev/null | grep -m1 '^Apache Maven' || echo unknown)"
    echo "docker:   $(docker version --format 'engine {{.Server.Version}}, API {{.Server.APIVersion}} (minimum {{.Server.MinAPIVersion}})' 2>/dev/null || echo unknown)"
    echo "host:     DOCKER_HOST=${DOCKER_HOST:-unset}, api.version in properties: $(grep '^api\.version=' "$DOCKER_JAVA_PROPERTIES" 2>/dev/null | tail -1 || true)"
    echo "keycloak: $KEYCLOAK_IMAGE -> $(image_id "$KEYCLOAK_IMAGE")"
    for server in $SERVER_LIST; do
        echo "$server: $(image_for "$server") -> $(image_id "$(image_for "$server")")"
    done
    if [ ${#MVN_EXTRA[@]} -gt 0 ]; then
        echo "extra maven arguments: ${MVN_EXTRA[*]}"
    fi
    echo
} | tee "$SUMMARY"

# Reads the Failsafe reports of the run that just finished and prints one line per test class. Sets OVERALL=1 on any problem.
evaluate() {
    local server=$1 mvn_status=$2 reports="$ROOT/mcp-it/target/failsafe-reports" found=0 problem=0
    local file name line run failures errors skipped verdict

    for file in "$reports"/*.txt; do
        [ -f "$file" ] || continue
        line=$(grep -h '^Tests run:' "$file" | tail -1)
        [ -n "$line" ] || continue
        found=1
        name=$(basename "$file" .txt)
        name=${name##*.}
        run=$(printf '%s' "$line" | sed -E 's/^Tests run: ([0-9]+),.*/\1/')
        failures=$(printf '%s' "$line" | sed -E 's/.*Failures: ([0-9]+),.*/\1/')
        errors=$(printf '%s' "$line" | sed -E 's/.*Errors: ([0-9]+),.*/\1/')
        skipped=$(printf '%s' "$line" | sed -E 's/.*Skipped: ([0-9]+).*/\1/')

        if [ "$((failures + errors))" -gt 0 ]; then
            verdict="FAILED"
            problem=1
        elif [ "$run" -eq 0 ] || [ "$skipped" -ge "$run" ]; then
            if [ "$server" = "liberty" ] && [ "$name" = "McpTicketsKeycloakIT" ]; then
                verdict="skipped by design (needs a data source on Open Liberty, see README)"
            else
                verdict="SKIPPED: the tests did not run (Docker not usable for Testcontainers?)"
                problem=1
            fi
        elif [ "$skipped" -gt 0 ]; then
            verdict="PASSED, but $skipped skipped"
            problem=1
        else
            verdict="passed"
        fi
        printf '  %-8s %-24s run %3s, failures %s, errors %s, skipped %s  %s\n' "$server" "$name" "$run" "$failures" "$errors" "$skipped" "$verdict"
    done

    if [ "$found" -eq 0 ]; then
        printf '  %-8s %s\n' "$server" "NO TEST REPORTS: the build stopped before the integration tests (maven exit $mvn_status)"
        problem=1
    elif [ "$mvn_status" -ne 0 ] && [ "$problem" -eq 0 ]; then
        printf '  %-8s %s\n' "$server" "MAVEN FAILED (exit $mvn_status) although the reports above look fine: see $server.log"
        problem=1
    fi
    if grep -q '^\[mcp-it\]' "$LOGDIR/$server.log" 2>/dev/null; then
        grep -h '^\[mcp-it\]' "$LOGDIR/$server.log" | sort -u | head -3 | sed 's/^/           /'
    fi
    [ "$problem" -eq 0 ] || OVERALL=1
}

# Where each server expects the WAR and which port it listens on (mirrors ServerFixture.java).
war_path_for() {
    case "$1" in
        payara)  echo "/opt/payara/deployments/ROOT.war" ;;
        wildfly) echo "/opt/jboss/wildfly/standalone/deployments/ROOT.war" ;;
        liberty) echo "/opt/ol/wlp/usr/servers/defaultServer/apps/ROOT.war" ;;
    esac
}

port_for() {
    case "$1" in
        liberty) echo 9080 ;;
        *)       echo 8080 ;;
    esac
}

CONFORMANCE_WAR="$ROOT/mcp-example-conformance/target/mcp-conformance.war"
CONFORMANCE_BUILT=0

# Runs the conformance suite against one server and prints one result line. Sets OVERALL=1 on any problem.
conformance() {
    local server=$1 image port container mapped url code status log="$LOGDIR/conformance-$server.log" ready=0 i
    image=$(image_for "$server")
    port=$(port_for "$server")

    if [ "$CONFORMANCE_BUILT" -eq 0 ]; then
        say "building the conformance WAR" >&2
        if ! mvn -B -ntp -q -DskipTests package -pl mcp-example-conformance -am > "$LOGDIR/conformance-build.log" 2>&1 || [ ! -f "$CONFORMANCE_WAR" ]; then
            printf '  %-8s %-24s %s\n' "$server" "conformance" "BUILD FAILED: see conformance-build.log"
            OVERALL=1
            return
        fi
        CONFORMANCE_BUILT=1
    fi

    TASKS_ENV=()
    TASKS_ENV=(-e MCP_TASKS_ENABLED=true)
    container=$(docker create -p "127.0.0.1::$port" ${TASKS_ENV[@]+"${TASKS_ENV[@]}"} "$image" 2>/dev/null) || {
        printf '  %-8s %-24s %s\n' "$server" "conformance" "FAILED: could not create a container from $image"
        OVERALL=1
        return
    }
    docker cp "$CONFORMANCE_WAR" "$container:$(war_path_for "$server")" >/dev/null 2>&1
    if [ "$server" = "liberty" ]; then
        docker cp "$ROOT/mcp-it/src/test/resources/liberty/server.xml" "$container:/opt/ol/wlp/usr/servers/defaultServer/server.xml" >/dev/null 2>&1
    fi
    docker start "$container" >/dev/null 2>&1

    mapped=$(docker port "$container" "$port/tcp" 2>/dev/null | head -1 | sed -E 's/.*:([0-9]+)$/\1/')
    url="http://127.0.0.1:$mapped/mcp"
    for i in $(seq 1 90); do
        code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' "$url" -d '{}' 2>/dev/null || true)
        if [ -n "$code" ] && [ "$code" != "000" ] && [ "$code" != "404" ]; then
            ready=1
            break
        fi
        sleep 2
    done

    if [ "$ready" -eq 0 ]; then
        docker logs "$container" > "$LOGDIR/conformance-$server-container.log" 2>&1
        printf '  %-8s %-24s %s\n' "$server" "conformance" "FAILED: the application did not answer at $url (container log: conformance-$server-container.log)"
        OVERALL=1
    else
        say "conformance suite against $server at $url (log: conformance-$server.log)" >&2
        rm -rf "$LOGDIR/conformance-$server-results"
        EXPECTED=()
        # A file without a single entry is not passed: the suite may not accept an empty list.
        grep -Eq '^[[:space:]]*-[[:space:]]' "$ROOT/mcp-example-conformance/expected-failures.yml" && EXPECTED=(--expected-failures "$ROOT/mcp-example-conformance/expected-failures.yml")
        [ "$CONFORMANCE_TASKS" -eq 1 ] && EXPECTED=()
        npx --yes @modelcontextprotocol/conformance@alpha server --url "$url" --requirements 2026-07-28 ${EXPECTED[@]+"${EXPECTED[@]}"} -o "$LOGDIR/conformance-$server-results" 2>&1 | tee "$log" >&2
        status=${PIPESTATUS[0]}
        if [ "$CONFORMANCE_TASKS" -eq 1 ]; then
            # No expected-failures file: the exit code says nothing, so report what the suite itself counted.
            printf '  %-8s %-24s %s\n' "$server" "conformance (tasks on)" "INFORMATION ONLY, exit $status; $(grep -E '^Total:' "$log" | tail -1); failing scenarios: $(grep -E '^✗ ' "$log" | sed -E 's/^✗ ([^:]+):.*/\1/' | tr '\n' ' ')"
        elif [ "$status" -eq 0 ] && grep -Eq '^Total: [0-9]+ passed, 0 failed' "$log"; then
            printf '  %-8s %-24s %s\n' "$server" "conformance" "passed ($(grep -E '^Total:' "$log" | tail -1))"
        elif [ "$status" -eq 0 ]; then
            # The suite exits 0 when only unscored ("pending" or extension) scenarios fail. That is still a failure we want to see.
            printf '  %-8s %-24s %s\n' "$server" "conformance" "FAILED CHECKS IN UNSCORED SCENARIOS (exit 0, but $(grep -E '^Total:' "$log" | tail -1)); failing: $(grep -E '^✗ ' "$log" | sed -E 's/^✗ ([^:]+):.*/\1/' | tr '\n' ' ')"
            OVERALL=1
        else
            printf '  %-8s %-24s %s\n' "$server" "conformance" "FAILED (exit $status): a scenario failed that is not listed, or a listed one passes now; see conformance-$server.log"
            OVERALL=1
        fi
    fi
    docker rm -f "$container" >/dev/null 2>&1
}

FIRST=1
for server in $SERVER_LIST; do
  if [ "$RUN_IT" -eq 1 ]; then
    say "=== $server: mvn clean verify -Dmcp.server=$server  (log: it-logs/$(basename "$LOGDIR")/$server.log) ==="
    args=(-B -ntp clean verify "-Dmcp.server=$server")
    if [ "$FIRST" -eq 0 ]; then
        # the unit tests do not depend on the server; run them once
        args+=(-Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false)
    fi
    FIRST=0

    # reports of an earlier run would otherwise be read as this run's when Maven stops before the integration tests (a unit test failed, say)
    rm -rf "$ROOT/mcp-it/target/failsafe-reports"
    mvn "${args[@]}" ${MVN_EXTRA[@]+"${MVN_EXTRA[@]}"} 2>&1 | tee "$LOGDIR/$server.log"
    mvn_status=${PIPESTATUS[0]}

    rm -rf "$LOGDIR/$server-reports"
    if [ -d "$ROOT/mcp-it/target/failsafe-reports" ]; then
        cp -R "$ROOT/mcp-it/target/failsafe-reports" "$LOGDIR/$server-reports"
    fi
    # not piped: evaluate sets OVERALL, which a subshell would lose
    evaluate "$server" "$mvn_status" > "$LOGDIR/$server-evaluation.txt"
    tee -a "$SUMMARY" < "$LOGDIR/$server-evaluation.txt"
  fi
  if [ "$RUN_CONFORMANCE" -eq 1 ]; then
    conformance "$server" > "$LOGDIR/$server-conformance-line.txt"
    tee -a "$SUMMARY" < "$LOGDIR/$server-conformance-line.txt"
  fi
done

{
    echo
    if [ "$OVERALL" -eq 0 ]; then
        echo "RESULT: everything that was asked for ran and passed."
    else
        echo "RESULT: PROBLEMS FOUND. A green 'BUILD SUCCESS' above does not mean the tests ran; this summary does."
    fi
    echo "Logs and Failsafe reports: $LOGDIR"
} | tee -a "$SUMMARY"

exit "$OVERALL"
