# PatchMeter 0.1.0

Compare added Git lines with JaCoCo XML or LCOV coverage and expose the evidence to an agent through MCP.

The distribution includes launchers, dependencies, documentation and a synthetic demonstration. Java 21 or later is required. Verify the ZIP against the attached SHA256SUMS, extract it, then run `bin/patchmeter demo --dir demo-output` (Windows: `bin\patchmeter.bat`).

This version provides exact line-threshold decisions, missing-evidence states, branch diagnostics, JSON/Markdown/HTML reports and three read-only MCP queries. Input files are loaded once and tools cannot execute commands or open arbitrary files.

Coverage must be produced from the same candidate revision as the diff. A passing line threshold does not establish assertion quality, branch completeness or correctness. NOT_REPORTED lines are disclosed separately and excluded from the observed-line percentage. See the README for path mapping, supported formats and bounded input sizes.

The release workflow checks the installed package on Linux and Windows, official MCP client interoperability, and real JaCoCo and Node LCOV exports before publishing these artifacts.
