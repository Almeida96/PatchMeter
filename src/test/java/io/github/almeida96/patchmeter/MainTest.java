package io.github.almeida96.patchmeter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path temp;
    record Run(int code, String out, String err) {}
    Run run(String... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = Main.run(args, InputStream.nullInputStream(), new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
    @Test void demoProducesReproducibleReportsAndRefusesOverwrite() throws Exception {
        var dir = temp.resolve("demo"); assertEquals(0, run("demo", "--dir", dir.toString()).code());
        byte[] before = Files.readAllBytes(dir.resolve("report.html"));
        assertEquals("FAIL", Json.read(Files.readString(dir.resolve("report.json"))).path("status").asText());
        assertEquals(1, run("demo", "--dir", dir.toString()).code()); assertArrayEquals(before, Files.readAllBytes(dir.resolve("report.html")));
    }
    @Test void installedInputsProduceAllGateOutcomes() throws Exception {
        var dir = temp.resolve("demo"); run("demo", "--dir", dir.toString());
        var args = new ArrayList<>(List.of("analyze", "--diff", dir.resolve("change.diff").toString(), "--coverage", dir.resolve("jacoco.xml").toString(), "--coverage-format", "jacoco", "--source-root", "src/main/java", "--include", "**/*.java"));
        assertEquals(2, run(args.toArray(String[]::new)).code());
        args.addAll(List.of("--minimum", "50")); assertEquals(0, run(args.toArray(String[]::new)).code());
        args.set(args.indexOf("src/main/java"), "wrong-root"); assertEquals(3, run(args.toArray(String[]::new)).code());
    }
    @Test void invalidOptionsCannotCreateOutputs() {
        Path output = temp.resolve("output.json");
        assertEquals(1, run("analyze", "--unknown", "1", "--out", output.toString()).code());
        assertEquals(1, run("analyze", "--minimum", "1", "--minimum", "2").code());
        assertFalse(Files.exists(output));
        assertEquals(1, run("mcp", "--out", output.toString()).code());
    }
    @Test void malformedCoverageFailsWithoutWritingAReport() throws Exception {
        Path diff = temp.resolve("change.diff"), coverage = temp.resolve("bad.xml"), out = temp.resolve("out.json");
        Files.writeString(diff, ""); Files.writeString(coverage, "<report>");
        assertEquals(1, run("analyze", "--diff", diff.toString(), "--coverage", coverage.toString(), "--coverage-format", "jacoco", "--out", out.toString()).code());
        assertFalse(Files.exists(out));
    }
}
