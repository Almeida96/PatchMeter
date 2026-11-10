# Implementation

```text
UTF-8 Git patch ── Patch parser ──┐
                               ├── immutable Analysis.Result ── JSON / Markdown / HTML
JaCoCo or LCOV ── Coverage ──────┘                           └── stdio MCP queries
```

`Input` caps byte reads and validates source labels. `Patch` interprets hunks once; `Coverage` reads XML with SAX or LCOV line by line into per-file maps. `Analysis` joins added line numbers to coverage records using exact mapped paths. It computes the gate once before any output or MCP request.

Storage is bounded by the input limits. Lookup is by file and line number; analysis does not compare every changed line with every report line. The stdio server is synchronous and serves an immutable snapshot. Pagination filters a selected file's accepted line observations; it does not trigger parsing or disk I/O.

The command layer owns the two input paths and optional output file. MCP arguments contain only identifiers and filters inside the snapshot. There is no subprocess, Git, test-runner, provider API, database or network access in the application. Python verification scripts run the installed application and coverage producers during development; those scripts are not MCP tools.

XML external resource loading is disabled. JSON duplicate fields and trailing tokens are rejected. HTML escapes imported paths and evidence and uses a restrictive content security policy without scripts or external assets. Output files are created with `CREATE_NEW` to prevent accidental overwrite.

The engine can establish consistency within supplied evidence, not whether it describes the correct commit or whether executed code was asserted. That distinction is present in summaries, reports and setup documentation.
