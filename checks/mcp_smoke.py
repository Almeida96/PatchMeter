"""Check the installed stdio endpoint using the official MCP Python client."""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.metadata
import json
import os
import shutil
import subprocess
import tempfile
from datetime import timedelta
from pathlib import Path

import jsonschema
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.shared.exceptions import McpError


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


async def invalid(session: ClientSession, name: str, arguments: dict) -> None:
    try:
        await session.call_tool(name, arguments)
    except McpError as failure:
        require(
            failure.error.code == -32602, f"Unexpected protocol error: {failure.error}"
        )
    else:
        raise AssertionError(f"Invalid call unexpectedly succeeded: {name}")


async def check(command: list[str], directory: Path, hashes: dict[str, str]) -> None:
    parameters = StdioServerParameters(
        command=command[0],
        args=[
            *command[1:],
            "mcp",
            "--diff",
            str(directory / "change.diff"),
            "--coverage",
            str(directory / "jacoco.xml"),
            "--coverage-format",
            "jacoco",
            "--source-root",
            "src/main/java",
            "--include",
            "**/*.java",
        ],
    )
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(
            read, write, read_timeout_seconds=timedelta(seconds=20)
        ) as session:
            initialized = await session.initialize()
            require(
                initialized.protocolVersion == "2025-11-25",
                "Unexpected protocol version",
            )
            require(
                initialized.serverInfo.name == "patchmeter", "Unexpected server name"
            )
            require(
                initialized.capabilities.tools is not None, "Missing tools capability"
            )
            tools = (await session.list_tools()).tools
            definitions = {tool.name: tool for tool in tools}
            require(
                set(definitions)
                == {"summarize_patch", "list_changed_files", "inspect_changed_lines"},
                "Unexpected tool inventory",
            )
            for tool in tools:
                require(tool.annotations is not None, "Missing annotations")
                require(
                    tool.annotations.readOnlyHint is True
                    and tool.annotations.idempotentHint is True,
                    "Tools must describe read-only idempotent behavior",
                )
                require(
                    tool.annotations.destructiveHint is False
                    and tool.annotations.openWorldHint is False,
                    "Unexpected side-effect annotation",
                )
                require(
                    tool.inputSchema.get("additionalProperties") is False,
                    "Undeclared arguments are allowed",
                )
                jsonschema.Draft202012Validator.check_schema(tool.inputSchema)
                require(tool.outputSchema is not None, "Missing output schema")
                jsonschema.Draft202012Validator.check_schema(tool.outputSchema)

            async def call(name: str, arguments: dict) -> dict:
                result = await session.call_tool(name, arguments)
                require(not result.isError, f"Tool failed: {result}")
                require(
                    isinstance(result.structuredContent, dict),
                    "Missing structured result",
                )
                require(
                    len(result.content) == 1 and result.content[0].type == "text",
                    "Missing JSON text fallback",
                )
                require(
                    json.loads(result.content[0].text) == result.structuredContent,
                    "Structured and text evidence disagree",
                )
                jsonschema.validate(
                    result.structuredContent, definitions[name].outputSchema
                )
                return result.structuredContent

            summary = await call("summarize_patch", {})
            require(
                summary["status"] == "FAIL",
                "Synthetic uncovered change should fail the 80% policy",
            )
            require(
                summary["coveredLines"] == 2
                and summary["uncoveredLines"] == 1
                and summary["notReportedLines"] == 1
                and summary["changedLines"] == 4,
                "Changed-line evidence counts differ",
            )
            require(
                summary["selectedFiles"] == 1 and summary["excludedFiles"] == 1,
                "Include pattern did not exclude the README",
            )
            require(
                summary["inputs"]["diffSha256"] == hashes["change.diff"]
                and summary["inputs"]["coverageSha256"] == hashes["jacoco.xml"],
                "Input hashes differ",
            )

            first = await call("list_changed_files", {"limit": 1})
            require(
                first["total"] == 2
                and first["nextOffset"] == 1
                and len(first["files"]) == 1,
                "First file page differs",
            )
            last = await call("list_changed_files", {"offset": 1, "limit": 1})
            require(
                last["nextOffset"] is None and len(last["files"]) == 1,
                "Final file page differs",
            )
            files = first["files"] + last["files"]
            require(
                {row["path"] for row in files}
                == {"src/main/java/shop/Pricing.java", "README.md"},
                "File pagination lost or repeated an entry",
            )
            target = next(row["path"] for row in files if row["selected"])
            all_lines = []
            offset = 0
            while True:
                page = await call(
                    "inspect_changed_lines",
                    {"path": target, "offset": offset, "limit": 2},
                )
                all_lines.extend(page["lines"])
                if page["nextOffset"] is None:
                    break
                require(
                    offset < page["nextOffset"] < page["total"],
                    "Line pagination did not advance within its declared total",
                )
                offset = page["nextOffset"]
            require(
                [row["line"] for row in all_lines] == [5, 6, 7, 8],
                "Line pagination differs",
            )
            patch_lines = (
                (directory / "change.diff").read_text(encoding="utf-8").splitlines()
            )
            for row in all_lines:
                require(
                    patch_lines[row["diffLine"] - 1].startswith("+"),
                    "Diff location is not an added line",
                )
            uncovered = await call(
                "inspect_changed_lines", {"path": target, "status": "UNCOVERED"}
            )
            require(
                uncovered["total"] == 1 and uncovered["lines"][0]["line"] == 7,
                "Uncovered-line filter differs",
            )
            require(
                uncovered["lines"][0]["hits"] == 0
                and uncovered["lines"][0]["coverageEvidence"],
                "Uncovered line lacks source evidence",
            )
            missed = await call(
                "inspect_changed_lines", {"path": target, "status": "MISSED_BRANCHES"}
            )
            require(
                {row["line"] for row in missed["lines"]} == {5, 7},
                "Branch diagnostics differ",
            )
            absent = await call(
                "inspect_changed_lines", {"path": target, "status": "NOT_REPORTED"}
            )
            require(
                absent["total"] == 1 and absent["lines"][0]["hits"] is None,
                "Unreported lines must not be classified as covered",
            )
            excluded = await call("inspect_changed_lines", {"path": "README.md"})
            require(
                excluded["selected"] is False and excluded["total"] == 0,
                "Excluded file gained evidence",
            )
            require(
                await call("summarize_patch", {}) == summary,
                "Repeated read changed the analysis",
            )

            for name, arguments in [
                ("summarize_patch", {"diff": "another.diff"}),
                ("list_changed_files", {"limit": 1.5}),
                ("list_changed_files", {"limit": False}),
                ("list_changed_files", {"limit": 101}),
                ("list_changed_files", {"offset": -1}),
                ("inspect_changed_lines", {}),
                ("inspect_changed_lines", {"path": target, "status": "unknown"}),
                ("inspect_changed_lines", {"path": "unlisted.java"}),
                ("write_report", {}),
            ]:
                await invalid(session, name, arguments)
            await session.send_ping()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-dir", type=Path, required=True)
    installation = parser.parse_args().install_dir.resolve()
    java_home = os.environ.get("JAVA_HOME")
    java = (
        str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
        if java_home
        else shutil.which("java")
    )
    require(java is not None, "Java 21 must be available")
    jars = sorted((installation / "lib").glob("*.jar"))
    require(bool(jars), "Build installDist before running this check")
    command = [
        java,
        "-cp",
        os.pathsep.join(map(str, jars)),
        "io.github.almeida96.patchmeter.Main",
    ]
    with tempfile.TemporaryDirectory(prefix="patchmeter-mcp-") as temporary:
        directory = Path(temporary) / "demo"
        result = subprocess.run(
            [*command, "demo", "--dir", str(directory)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
            timeout=60,
        )
        require(result.returncode == 0, f"Demo failed: {result.stderr}")
        inputs = ("change.diff", "jacoco.xml", "lcov.info")
        hashes = {
            name: hashlib.sha256((directory / name).read_bytes()).hexdigest()
            for name in inputs
        }
        before_files = {path.name for path in directory.iterdir()}
        asyncio.run(check(command, directory, hashes))
        require(
            {
                name: hashlib.sha256((directory / name).read_bytes()).hexdigest()
                for name in inputs
            }
            == hashes,
            "MCP changed an input file",
        )
        require(
            {path.name for path in directory.iterdir()} == before_files,
            "MCP created an unexpected file",
        )
    print(
        f"Official MCP SDK {importlib.metadata.version('mcp')}: three tools, evidence, pagination, filters, invalid calls and read-only input checks passed."
    )


if __name__ == "__main__":
    main()
