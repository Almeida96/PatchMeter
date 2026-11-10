"""Compare PatchMeter with independent readings of real JaCoCo and Node LCOV exports."""
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

from cli_smoke import launcher, run


def added_file(path: str, content: str) -> str:
    lines = content.splitlines()
    return f"diff --git a/{path} b/{path}\nnew file mode 100644\n--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{len(lines)} @@\n" + "".join("+" + line + "\n" for line in lines)


def verify(command, diff, coverage, format_name, expected, *extra):
    result = json.loads(run(command, "analyze", "--diff", diff, "--coverage", coverage, "--coverage-format", format_name,
                            "--minimum", "0", *extra).stdout)
    evidence = result["fileResults"][0]["lines"]
    actual = {item["line"]: item["status"] == "COVERED" for item in evidence if item["status"] in {"COVERED", "UNCOVERED"}}
    assert actual == expected, (format_name, actual, expected)
    assert result["coveredLines"] == sum(expected.values())
    assert result["uncoveredLines"] == len(expected) - sum(expected.values())
    assert result["missingFileLines"] == 0
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-dir", type=Path, required=True)
    parser.add_argument("--jacoco-report", type=Path, default=Path("build/reports/jacoco/test/jacocoTestReport.xml"))
    args = parser.parse_args()
    command = launcher(args.install_dir)
    root = Path(__file__).resolve().parents[1]
    jacoco = args.jacoco_report.resolve()
    xml = ET.parse(jacoco).getroot()
    source = xml.find("./package[@name='io/github/almeida96/patchmeter']/sourcefile[@name='Main.java']")
    assert source is not None, "Run jacocoTestReport first"
    observations = {int(line.attrib["nr"]): int(line.attrib["ci"]) > 0 for line in source.findall("line")}
    assert observations and any(observations.values())
    with tempfile.TemporaryDirectory(prefix="patchmeter-producers-") as temp:
        directory = Path(temp)
        diff = directory / "java.diff"
        path = "src/main/java/io/github/almeida96/patchmeter/Main.java"
        diff.write_text(added_file(path, (root / path).read_text(encoding="utf-8")), encoding="utf-8")
        java_result = verify(command, diff, jacoco, "jacoco", observations, "--source-root", "src/main/java")
        print(f"Real JaCoCo export: {len(observations)} instruction-bearing lines matched; {java_result['notReportedLines']} absent lines remain NOT_REPORTED.")

        script = directory / "pricing.mjs"
        script.write_text("export function price(amount, member) {\n  if (member) {\n    return amount * 0.9;\n  }\n  return amount;\n}\n", encoding="utf-8")
        test = directory / "pricing.test.mjs"
        test.write_text("import { test } from 'node:test';\nimport assert from 'node:assert/strict';\nimport { price } from './pricing.mjs';\ntest('regular price', () => assert.equal(price(100, false), 100));\n", encoding="utf-8")
        lcov = directory / "node.info"
        process = subprocess.run(["node", "--test", "--experimental-test-coverage", "--test-reporter=lcov",
                                  f"--test-reporter-destination={lcov}", str(test)], cwd=directory, capture_output=True, text=True, timeout=60)
        assert process.returncode == 0, process.stderr
        current = None
        observed = {}
        source_paths = []
        for line in lcov.read_text(encoding="utf-8").splitlines():
            if line.startswith("SF:"):
                source_path = Path(line[3:])
                source_paths.append(source_path)
                current = (directory / source_path).resolve()
            elif current == script.resolve() and line.startswith("DA:"):
                number, count, *_ = line[3:].split(",")
                observed[int(number)] = int(count) > 0
        assert observed and any(observed.values()) and not all(observed.values()), "Fixture must include executed and missed lines"
        diff.write_text(added_file("pricing.mjs", script.read_text(encoding="utf-8")), encoding="utf-8")
        absolute_paths = {path.is_absolute() for path in source_paths}
        assert len(absolute_paths) == 1, "Node exported a mix of relative and absolute source paths"
        mapping = ("--strip-prefix", str(directory)) if True in absolute_paths else ()
        verify(command, diff, lcov, "lcov", observed, *mapping)
        print(f"Real Node LCOV export: {len(observed)} reported lines matched, including unexecuted discount branch.")


if __name__ == "__main__":
    main()
