"""Exercise the packaged launchers and all four analysis exit outcomes."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from html.parser import HTMLParser
from pathlib import Path


def launcher(install: Path) -> list[str]:
    path = install.resolve() / "bin" / ("patchmeter.bat" if os.name == "nt" else "patchmeter")
    if not path.is_file():
        raise AssertionError(f"Build installDist first: {path}")
    return [str(path)]


def run(command: list[str], *args: str, expected: int = 0) -> subprocess.CompletedProcess:
    result = subprocess.run([*command, *map(str, args)], capture_output=True, text=True, encoding="utf-8", timeout=45)
    if result.returncode != expected:
        raise AssertionError(f"Expected exit {expected}, got {result.returncode}: {result.stderr}\n{result.stdout}")
    return result


class OfflineReport(HTMLParser):
    def __init__(self):
        super().__init__()
        self.csp = False
        self.tables = 0

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        assert tag not in {"script", "iframe", "object", "embed"}
        assert not any(key.startswith("on") for key in attributes)
        if tag == "meta" and attributes.get("http-equiv", "").lower() == "content-security-policy":
            self.csp = "default-src 'none'" in attributes.get("content", "")
        if tag == "table":
            self.tables += 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--install-dir", type=Path, required=True)
    command = launcher(parser.parse_args().install_dir)
    with tempfile.TemporaryDirectory(prefix="patchmeter-cli-") as temp:
        directory = Path(temp) / "demo"
        run(command, "demo", "--dir", directory)
        inputs = [directory / name for name in ("change.diff", "jacoco.xml", "lcov.info")]
        before = [hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs]
        common = ["analyze", "--diff", str(inputs[0]), "--include", "**/*.java"]
        jacoco = [*common, "--coverage", str(inputs[1]), "--coverage-format", "jacoco", "--source-root", "src/main/java"]
        lcov = [*common, "--coverage", str(inputs[2]), "--coverage-format", "lcov"]
        report = json.loads(run(command, *jacoco, expected=2).stdout)
        alternate = json.loads(run(command, *lcov, expected=2).stdout)
        for key, value in {"status": "FAIL", "coveredLines": 2, "uncoveredLines": 1, "notReportedLines": 1, "observedLinePercent": 66.6667}.items():
            assert report[key] == alternate[key] == value, (key, report[key], alternate[key])
        assert json.loads(run(command, *jacoco, "--minimum", "66.6666").stdout)["status"] == "PASS"
        assert json.loads(run(command, *jacoco[:-2], expected=3).stdout)["status"] == "INCONCLUSIVE"
        no_scope = ["**/*.does-not-exist" if value == "**/*.java" else value for value in jacoco]
        assert json.loads(run(command, *no_scope).stdout)["status"] == "NO_CHANGED_LINES"
        for format_name in ("html", "md", "json"):
            target = directory / f"explicit.{format_name}"
            run(command, *jacoco, "--format", format_name, "--out", target, expected=2)
            original = target.read_bytes()
            run(command, *jacoco, "--format", format_name, "--out", target, expected=1)
            assert target.read_bytes() == original
        html = OfflineReport()
        html.feed((directory / "explicit.html").read_text(encoding="utf-8"))
        assert html.csp and html.tables >= 1
        run(command, "analyze", "--unknown", "value", expected=1)
        run(command, "demo", "--dir", directory, expected=1)
        assert before == [hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs]
    print("CLI checks passed: both formats, four outcomes, report structure, no overwrite, immutable inputs.")


if __name__ == "__main__":
    main()
