# Release checklist (1.0.0, and the template for later releases)

Run it from a clean checkout of the commit you intend to tag. Do not start before the Central count has reset (check the Usage Center of the Central Portal): a release that fails halfway costs another slot.

## A. Before the tag

- [ ] `git status` clean, on `main`, up to date with the remote.
- [ ] Decisions recorded in the README: protocol policy (done), whether `Apps` / `McpAppsExtension` are provisional, which hosts were tested (see the host table).
- [ ] README "Status: read this first" rewritten for 1.0.0: replace the dated 0.x run reports with what was run on the release commit, and move "Not yet run" / "Unverified" items to a "Known limits" list or remove them once tested.
- [ ] CHANGELOG: rename "Unreleased" to "1.0.0 (YYYY-MM-DD)", check that every entry under it really is new since 0.9.0, and check the 0.9.0 date.
- [ ] `scripts/release-check.sh` passes (jar, sources and Javadoc for `mcp-jakarta`, `mcp-jakarta-tasks-jdbc`, `mcp-jakarta-bridge-jms`).
- [ ] `scripts/it.sh --servers payara,wildfly,liberty` passes on this commit. Keep the summary and logs.
- [ ] The MCP conformance run passes (CI job "MCP conformance suite"; the tool is still `@alpha`, the `tasks-status-notifications` scenario is skipped).
- [ ] CI is green on the commit (unit tests on all JDKs, release build, integration tests, conformance).
- [ ] An outsider has followed only the README to a running tool.
- [ ] Dependencies and plugin versions checked (`scripts/release-check.sh --updates`); the test-only `slf4j-simple` is not an alpha.

## B. Version

Set the release version in all modules (the README "Releasing to Maven Central" has the command):

```bash
mvn versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false
```

- [ ] Every `pom.xml` says `1.0.0`: parent, the three published modules, and the examples and test modules (they inherit it; check any that name a version).
- [ ] The dependency snippet in the README says `1.0.0`.
- [ ] Commit ("Release 1.0.0") and tag `v1.0.0`.

## C. Publish

- [ ] `mvn -Prelease clean deploy -pl .,mcp-jakarta,mcp-jakarta-tasks-jdbc,mcp-jakarta-bridge-jms` (README step 3; the `-pl` matters).
- [ ] Open the Central Portal, check the validated bundle, press Publish. A release cannot be deleted afterwards.
- [ ] Wait until the artifacts show up on Maven Central (search.maven.org or the repository index).

## D. After

- [ ] Push the commit and the tag.
- [ ] Create the GitHub release `v1.0.0` with the CHANGELOG text (and the missing `v0.7.0` release if it is still absent).
- [ ] Set the next version: `mvn versions:set -DnewVersion=1.0.1-SNAPSHOT -DgenerateBackupPoms=false` (or `1.1.0-SNAPSHOT` if the next work is the backlog), add an empty "Unreleased" section to the CHANGELOG, commit.
- [ ] A test application builds against the released `1.0.0` from Central, not from `~/.m2`.
- [ ] Tell the people who tested it.

## If something goes wrong

- Validation fails in the portal: fix and upload a new bundle; nothing was published.
- A bug shows up after publishing: fix it in a patch release (1.0.1). Do not move the tag.
- Count the artifacts before the next release: each release uploads four (parent plus three modules), which may count as several against the monthly limit.
