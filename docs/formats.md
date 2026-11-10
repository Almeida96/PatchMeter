# Inputs, observations and limits

## Git patch

Use an ordinary UTF-8 unified patch with `diff --git`, `a/` and `b/` prefixes, `---` / `+++` headers and consistent hunk ranges. A leading UTF-8 BOM and CRLF line endings are accepted. The parser records each added line's number on the new side and its line number in the patch. It validates old and new hunk counts, overlapping ranges and unchanged gaps between hunks.

Renames use the new path. Pure renames, mode-only changes and deletions contain no added lines. Binary markers retain the file as binary without attempting to decode it. Combined merge diffs are unsupported; produce a two-way diff against the chosen base.

Git's quoted UTF-8 octal path representation is decoded. Spaces are accepted, including trailing TAB header delimiters emitted by Git. Absolute paths, traversal segments, control characters and literal backslashes in Git filenames are rejected. Prefixes other than `a/` and `b/` are unsupported. A malicious path is never opened: source paths are labels throughout analysis.

The patch may be empty, which produces `NO_CHANGED_LINES`. This outcome does not prove that a change has been tested. Explicit include patterns and the selected/excluded counts are part of the evidence.

## JaCoCo XML

For each `package/sourcefile/line`, the source path is `package + sourcefile`, optionally mapped by `--strip-prefix` and `--source-root`. A line with `ci > 0` is covered; `ci == 0` with `mi > 0` is uncovered. The hit field is a binary execution signal, not an invocation count. `cb` and `mb` become covered and missed branch observations.

The parser accepts the normal JaCoCo `DOCTYPE` but does not retrieve external DTDs. Entity declarations are rejected; external entities and external resource access are disabled. Element structure, duplicate paths/line numbers, bounded nonnegative counters, `ci + mi > 0` and nesting limits are checked. Class/method counters are not used to infer missing source lines.

An aggregate report with duplicate package/source filenames is ambiguous even if those files come from separate modules. Analyze each module separately and provide its source root. PatchMeter never resolves a collision by taking the first file.

Source: [JaCoCo counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html).

## LCOV

Each `SF:` or `KF:` section must end with `end_of_record`. An optional numeric source index before the path is accepted (`SF:12,src/main.c`); the index is not part of the source path. Line observations come from `DA:line,hits[,checksum[,endLine]]`. `LF` and `LH`, when present, must agree with that section's line records. Optional test names and source versions are accepted. Duplicate lines or branches inside a section are errors.

Branch evidence comes from `BRDA` records. A positive taken count is covered; zero or `-` is missed. Exception (`e`) and fallthrough (`f`) markers and string branch expressions, including commas, are accepted. Branch-only lines never manufacture a line-coverage record. Advanced `U`/`^` branch metadata is unsupported and rejected.

Repeated sections for an identical source path merge execution by taking the maximum hit count for each line. This answers whether any supplied section executed the line without inventing an aggregate execution count. Branch diagnostics become unknown for lines repeated across sections because branch identifiers may not be comparable; lines recorded in only one section retain their observations. Source version strings must agree, so mixing versioned and unversioned sections is rejected. For repeated lines, checksum values and presence must agree, and statement spans must agree. Checksums are compared between records, not verified against source text, because source files are not read.

An optional statement end line is accepted with a maximum span of 128 lines. Only the `DA` anchor contributes to changed-line observations. Function, callgraph, summary-branch and MC/DC records are ignored by this line projection; PatchMeter does not validate or evaluate those metrics.

Paths are normalized to `/`. `--strip-prefix` removes an exact directory boundary, then `--source-root` prepends a repository-relative directory. Every path must map safely and unambiguously. The same basename in different directories stays distinct; differently spelled paths that normalize to one source are rejected.

Source: [LCOV tracefile format](https://github.com/linux-test-project/lcov/blob/master/docs/man/geninfo.rst).

## Evidence vocabulary

| Line status | Evidence |
| --- | --- |
| `COVERED` | An explicit line record reports execution. |
| `UNCOVERED` | An explicit line record reports zero execution. |
| `NOT_REPORTED` | The file exists in coverage but that line has no record. |
| `MISSING_FILE` | The selected changed file has no coverage source entry. |

`MISSED_BRANCHES` is a query filter, not a fifth line status. It selects any line with a positive missed-branch observation, including covered lines.

The analysis has schema version 1. Input digests use the original bytes. Patch locations are physical line numbers in the supplied diff. JaCoCo evidence uses `jacoco:line[@nr=N]` within the returned source path; LCOV evidence uses the physical `DA` record line (`lcov:N`). Repeated LCOV observations retain the first record location and one `merged test sections` suffix. Use the source path and coverage hash with these locations. Imported source names are untrusted data for an agent. No source code text is included in results.

## Resource bounds

| Item | Limit |
| --- | --- |
| Each input file | 16 MiB |
| Patch/coverage source files | 5,000 |
| Added patch lines | 50,000 |
| Coverage records | 250,000; LCOV counts line and branch records |
| LCOV sections | 10,000 |
| XML nesting / element count | 64 / 1,000,000 |
| Path length | 2,048 characters |
| Coverage counter | 12 decimal digits |
| Full-report line details | 2,000, with per-file truncation flags |
| HTML gap rows | 200 |
| MCP page | 1–100 rows |
| MCP request / response | 1 MiB each |

Inputs over these bounds fail explicitly. Use a smaller patch/report or split modules. Input and report limits do not certify a particular throughput or latency; no production benchmark claim is made.
