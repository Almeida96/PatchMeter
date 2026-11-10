# MCP access

PatchMeter exposes one changed-line coverage analysis through a local stdio MCP server. Generate the diff and coverage report first, then start the process with those two inputs:

```sh
patchmeter mcp --diff change.diff --coverage jacoco.xml --coverage-format jacoco --source-root src/main/java --include '**/*.java' --minimum 80
```

The process loads and validates its inputs at startup. Tool calls read that fixed analysis. They cannot select another input file, change the threshold, run tests, execute Git, write reports or make network requests. Restart the process to review another patch or newly generated coverage.

## Client configuration

For clients that accept an `mcpServers` configuration, use absolute paths:

```json
{
  "mcpServers": {
    "patchmeter": {
      "command": "/absolute/path/to/patchmeter/bin/patchmeter",
      "args": [
        "mcp",
        "--diff", "/absolute/path/to/change.diff",
        "--coverage", "/absolute/path/to/jacoco.xml",
        "--coverage-format", "jacoco",
        "--source-root", "src/main/java",
        "--include", "**/*.java",
        "--minimum", "80"
      ]
    }
  }
}
```

Windows clients that cannot launch batch files can invoke Java directly:

```json
{
  "mcpServers": {
    "patchmeter": {
      "command": "java",
      "args": [
        "-Xmx512m", "-cp", "C:/tools/patchmeter/lib/*",
        "io.github.almeida96.patchmeter.Main", "mcp",
        "--diff", "C:/reports/change.diff",
        "--coverage", "C:/reports/jacoco.xml",
        "--coverage-format", "jacoco",
        "--source-root", "src/main/java",
        "--include", "**/*.java"
      ]
    }
  }
}
```

Java 21 or later must be available. Use the full Java executable path if the client does not inherit your shell's `PATH`. Adapt the enclosing configuration to your client. An LCOV report uses `--coverage-format lcov`; use the documented path mapping options when its source filenames include a build-directory prefix.

## Tools

| Tool | Arguments | Result |
| --- | --- | --- |
| `summarize_patch` | None | Policy, outcome, selected/excluded file counts, changed-line observations, input hashes and interpretation limits. |
| `list_changed_files` | Optional `offset`, `limit` | File paths, selection, coverage presence and gap counts. |
| `inspect_changed_lines` | Required `path`; optional `status`, `offset`, `limit` | Added line numbers, diff locations, observed coverage and branch diagnostics. |

Pages default to 25 rows and allow 1–100. Offsets are 0–1,000,000. Follow `nextOffset` until it is `null`. A path must match an entry returned by `list_changed_files`; paths are labels in the loaded analysis, not files the tool opens.

Line filters are `ALL`, `COVERED`, `UNCOVERED`, `NOT_REPORTED`, `MISSING_FILE` and `MISSED_BRANCHES`. `MISSED_BRANCHES` can include lines whose instructions were executed: a visited line can still have unvisited branches.

Each successful result includes `structuredContent` and an equivalent JSON text content block. The output schema declares an object; consumers should use the documented result fields. The read-only/idempotent annotations describe these implementations, not a general permission system.

## Example investigation

With the bundled synthetic Java fixture:

1. Call `summarize_patch`. The default 80% line policy fails: two observed added lines are covered and one is uncovered. A fourth added line has no coverage record.
2. Call `list_changed_files`. The Java include pattern selects `src/main/java/shop/Pricing.java` and excludes `README.md`.
3. Call `inspect_changed_lines` with that Java path and `status: "UNCOVERED"`. Line 7 has an explicit zero-coverage observation.
4. Use `status: "MISSED_BRANCHES"` to inspect lines 5 and 7. These observations guide test design; they do not prove which assertion or test case is missing.
5. Add or revise tests in the actual project, regenerate coverage for the candidate revision, and restart PatchMeter to review the new evidence.

`NOT_REPORTED` is not a covered line. It may represent a comment, excluded source, missing debug information or incomplete evidence. Coverage must correspond to the same candidate revision as the diff; file hashes identify bytes but do not establish that relationship. A passing line threshold does not prove correctness or branch completeness.

## Protocol and failure behavior

The endpoint supports MCP `2025-11-25` and `2025-06-18` over newline-delimited UTF-8 JSON-RPC. Initialize the connection and send `notifications/initialized` before tool requests. Pings are allowed before initialization completes. Supported methods are `initialize`, `ping`, `tools/list` and `tools/call`; the inventory has three tools and no continuation cursor. It does not provide resources, prompts, sampling, tasks or HTTP transport.

Malformed JSON/UTF-8 and oversized input frames return `-32700`. Invalid requests return `-32600`; unknown methods return `-32601`. Unknown tools, invalid arguments and paths absent from the loaded diff return `-32602`. Input frames are capped at 1 MiB. The reader drains an oversized frame through its newline before processing the next request. Responses exceeding 1 MiB return an explicit `-32603` error rather than truncated JSON; request a smaller page.

Startup errors are written to stderr and terminate the process. Once serving, stdout contains protocol messages only. Imported path names and source evidence remain untrusted data for the consuming agent.

The interoperability check uses the official Python MCP SDK:

```sh
python -m pip install -r checks/requirements.txt
python checks/mcp_smoke.py --install-dir build/install/patchmeter
```

It verifies discovery, result schemas, text/structured consistency, input hashes, pagination, filters, invalid calls and unchanged input files. No model account or live model provider is required.
