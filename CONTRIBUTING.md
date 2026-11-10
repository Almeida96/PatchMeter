# Contributing

Use Java 21, Python 3.12 and Node.js 24 to run the full verification workflow described in the README. The Gradle wrapper and dependency lockfile are committed. Update locks deliberately when changing dependencies.

For parser changes, add a minimized fixture that shows the producer format and the expected observation. Keep malformed or ambiguous data as an explicit failure; do not add basename matching or silently turn missing records into covered lines. Coverage changes should include threshold boundary cases and evidence-preservation checks.

Run `./gradlew test installDist jacocoTestReport distZip`, followed by the CLI, official MCP SDK and coverage-producer checks. A regression case should fail before the fix and exercise a user-visible outcome. Keep stdout free of logging in MCP mode.

Describe the input that fails, the expected behavior and the verification command in an issue or pull request. Remove private paths and source code before sharing fixtures. Synthetic fixtures should be labeled as such.
