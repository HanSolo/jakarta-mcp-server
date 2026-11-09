#!/usr/bin/env bash
# Runs what a Maven Central release needs, without signing or uploading: sources jar, Javadoc jar (a Javadoc error fails the build) and the
# unit tests, for the modules that get published. Run it before tagging; CI runs the same command.
#
#   scripts/release-check.sh            build the publishable modules with the release profile
#   scripts/release-check.sh --updates  also list newer versions of plugins and dependencies (needs network access to Maven Central)
set -euo pipefail

cd "$(dirname "$0")/.."
command -v mvn >/dev/null 2>&1 || { echo "mvn not found: install Maven 3.9 or newer" >&2; exit 1; }

# -Dgpg.skip: the signing key is only needed for the real upload
mvn -B -ntp -Prelease -Dgpg.skip=true clean verify -pl .,mcp-jakarta,mcp-jakarta-tasks-jdbc

for module in mcp-jakarta mcp-jakarta-tasks-jdbc; do
    for kind in "" -sources -javadoc; do
        jar=$(ls "$module"/target/"$module"-*"$kind".jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)
        if [ -n "$kind" ]; then jar=$(ls "$module"/target/"$module"-*"$kind".jar 2>/dev/null | head -1 || true); fi
        if [ -z "$jar" ]; then echo "MISSING: $module$kind jar" >&2; exit 1; fi
    done
done
echo "release check passed: jar, sources and javadoc for mcp-jakarta and mcp-jakarta-tasks-jdbc"

if [ "${1:-}" = "--updates" ]; then
    mvn -B -ntp versions:display-plugin-updates versions:display-dependency-updates versions:display-property-updates
fi
