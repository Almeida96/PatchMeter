# PatchMeter

Find untested lines in a change before asking an agent to write more tests.

PatchMeter joins a Git diff with a JaCoCo XML or LCOV report. It identifies added lines with observed coverage, explicit misses, or missing evidence, then exposes the result as JSON, Markdown, an offline HTML report, and three read-only MCP tools. It runs locally on Java 21 without a model account or service.

An overall coverage percentage can hide a new untested branch in an otherwise well-tested module. PatchMeter narrows the review to the candidate change and keeps the source line, diff location, input hashes and coverage evidence together. An agent can page through the uncovered lines, propose focused tests, and compare the next report after those tests run.

## Try it

Running a packaged release requires Java 21 or later. Building from source requires a JDK 21 installation and downloads the pinned Gradle distribution and Maven dependencies.

```sh
./gradlew test installDist
build/install/patchmeter/bin/patchmeter demo --dir demo-output
```

On Windows, use `gradlew.bat` and `build\install\patchmeter\bin\patchmeter.bat`. Open `demo-output/report.html` in a browser or read `demo-output/report.json`. The output directory must not already exist.

The demo uses **synthetic data**, with an intentional missed discount calculation:

| Added Java line | Observation | Branch diagnostics |
| --- | --- | --- |
| 5 | Covered | One missed branch |
| 6 | Covered | No recorded branches |
| 7 | Uncovered | Two missed branches |
| 8 | Not reported | Unknown |

Two of three observed lines are covered: **66.6667%**, below the default 80% threshold. The fourth line is a comment in this fixture; PatchMeter only knows that it has no coverage record. It does not infer executability from text.

## Analyze your change

Generate coverage from the candidate revision you intend to review. For a Java project that already applies the Gradle JaCoCo plugin:

```sh
./gradlew test jacocoTestReport
git diff --no-ext-diff --no-textconv --src-prefix=a/ --dst-prefix=b/ --unified=0 origin/main...HEAD > change.diff
patchmeter analyze --diff change.diff --coverage build/reports/jacoco/test/jacocoTestReport.xml --coverage-format jacoco --source-root src/main/java --include '**/*.java' --minimum 80 --format html --out patch-report.html
```

`patchmeter` above is the installed launcher; add its `bin` directory to your `PATH` or use its full path. Enable XML output in your project's `jacocoTestReport` task. For Kotlin sources, use the matching source root and include pattern, such as `src/main/kotlin` and `**/*.kt`. Analyze modules separately when they contain the same package/source filename.

The three-dot diff compares the merge base with `HEAD`. Working-tree edits are not included in that command. Use a diff whose new side matches the code that produced your coverage. On Windows, write the patch as UTF-8; older PowerShell redirection may emit UTF-16.

For LCOV:

```sh
patchmeter analyze --diff change.diff --coverage coverage/lcov.info --coverage-format lcov --strip-prefix /home/runner/work/project/project --include 'src/**/*.ts' --format json
```

Use `--strip-prefix` only when it matches the prefix in every coverage source path. Omit it for repository-relative paths. `--source-root` prepends a repository-relative directory after stripping. Path mapping is explicit; files with matching basenames are never guessed to be the same file.

## Read the outcome

| Outcome | Exit | Meaning |
| --- | --- | --- |
| `PASS` | 0 | Observed added-line coverage meets the selected threshold. |
| `NO_CHANGED_LINES` | 0 | The selected scope contains no added lines or relevant binary changes. Check the scope before treating this as meaningful evidence. |
| `FAIL` | 2 | Observed added-line coverage is below the threshold. |
| `INCONCLUSIVE` | 3 | A selected changed file lacks coverage, a selected binary change cannot be assessed, or no added line has an explicit coverage observation. |
| Invalid input | 1 | Input parsing, path mapping, options or output creation failed. |

Coverage is `covered / (covered + uncovered)` for **added lines with explicit records**. `NOT_REPORTED` lines are listed separately and excluded from the denominator; a report with some such lines can still pass. Missing entire changed files take precedence over pass/fail. Deleted lines and excluded files are outside the calculation. The threshold uses exact decimal arithmetic; displayed percentages are rounded to four places.

The default include pattern is `**`. Select source files explicitly to avoid asking for coverage of documentation or configuration. Patterns support `*`, `**` and `?`; they are case-sensitive and use `/` separators. There is one include pattern per analysis.

## Use it through MCP

```sh
patchmeter mcp --diff change.diff --coverage jacoco.xml --coverage-format jacoco --source-root src/main/java --include '**/*.java'
```

| Tool | Use |
| --- | --- |
| `summarize_patch` | Read the threshold result, scope, counts and input hashes. |
| `list_changed_files` | Locate affected files with missing or uncovered lines. |
| `inspect_changed_lines` | Page through evidence, optionally filtering uncovered lines or missed branches. |

Example request to an agent with this server configured:

> Review this patch's coverage evidence. List the uncovered added lines and missed branches, separate missing evidence from actual misses, and propose the smallest useful test cases. Do not claim that passing coverage proves correctness.

The server loads one immutable analysis at startup. Tools cannot open arbitrary files, run commands, change the policy, or write to the repository. After regenerating coverage, restart the server. See [MCP setup and protocol](docs/mcp.md) for client configuration, pagination and errors.

## Reports and CI

Choose `--format json`, `md` or `html`. `--out` creates a new file and refuses to overwrite an existing one. Without it, the report goes to stdout; diagnostics go to stderr. Reports include full counts and up to 2,000 line details, with explicit truncation flags. The HTML view shows the first 200 gap rows. MCP pagination can inspect all accepted changed lines.

In CI, keep the analysis exit code and upload the report even when the threshold fails:

```yaml
- name: Check changed-line coverage
  run: patchmeter analyze --diff change.diff --coverage jacoco.xml --coverage-format jacoco --source-root src/main/java --include '**/*.java' --format html --out patch-report.html
# Follow with your CI system's artifact upload step configured to run on failure too.
```

The launcher and generated inputs must already exist in that job. PatchMeter does not install instrumentation, execute tests, fetch Git refs or publish review comments.

## Boundaries

- Coverage must correspond to the same candidate revision as the diff. SHA-256 hashes identify supplied bytes; they do not establish source or build provenance.
- Line execution says nothing about assertion quality or correctness. Branch observations are diagnostic and do not affect the line threshold.
- JaCoCo records use instruction execution as the line signal. Kotlin compiler-generated code and source mappings can affect interpretation.
- LCOV repeated test sections merge line execution conservatively. Their hit value is the maximum observed count, not a sum; branch counts become unknown for lines repeated across sections. Statement spans count only their anchor line.
- Ordinary Git unified diffs are supported, including renamed files, quoted paths and deletions. Combined merge diffs, custom path prefixes, unsafe filenames and ambiguous path mappings are rejected. Selected binary changes yield an inconclusive result unless the file is deleted.
- Inputs are limited to 16 MiB each, 5,000 files, 50,000 added lines and 250,000 coverage records. This is a bounded local review tool, not a repository-wide coverage database.

See [input formats and limits](docs/formats.md) and [implementation structure](docs/architecture.md).

## Development

```sh
./gradlew test installDist jacocoTestReport distZip
python checks/cli_smoke.py --install-dir build/install/patchmeter
python -m pip install -r checks/requirements.txt
python checks/mcp_smoke.py --install-dir build/install/patchmeter
python checks/coverage_integration.py --install-dir build/install/patchmeter
```

The last check needs Node.js 24 and compares independent readings of actual JaCoCo and Node LCOV exports with PatchMeter's output. Tests cover malformed diffs, Unicode Git paths, XML external-entity rejection, path collisions, coverage merging, threshold boundaries, protocol framing and pagination. CI runs the package checks on Linux and Windows.

[Contributing](CONTRIBUTING.md) · [Changes](CHANGELOG.md) · [MIT license](LICENSE)
