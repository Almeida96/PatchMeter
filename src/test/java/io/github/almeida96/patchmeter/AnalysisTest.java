package io.github.almeida96.patchmeter;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {
    static Analysis.Result demo(String minimum) throws Exception {
        byte[] patch = AnalysisTest.class.getResourceAsStream("/change.diff").readAllBytes();
        byte[] coverage = AnalysisTest.class.getResourceAsStream("/jacoco.xml").readAllBytes();
        return Analysis.analyze(Patch.parse(patch), Coverage.parse(coverage, "jacoco", "src/main/java", ""), "src/main/java", "", "**/*.java", new BigDecimal(minimum));
    }
    @Test void countsOnlyExplicitObservationsAndKeepsUnknownLinesSeparate() throws Exception {
        var result = demo("80"); var report = Analysis.summary(result);
        assertEquals("FAIL", result.status()); assertEquals(2, report.path("coveredLines").asInt());
        assertEquals(1, report.path("uncoveredLines").asInt()); assertEquals(1, report.path("notReportedLines").asInt());
        assertEquals(1, report.path("excludedFiles").asInt()); assertEquals(2, report.path("linesWithMissedBranches").asInt());
        assertEquals("PASS", demo("66.6666").status()); assertEquals("FAIL", demo("66.6667").status());
    }
    @Test void missingCoverageFilesAreInconclusiveEvenWithZeroThreshold() throws Exception {
        var r = demo("0");
        var patch = new Patch.Data(r.diffSha256(), List.of(new Patch.File("other.java", List.of(new Patch.Line(1, 5)), false, false)));
        var data = new Coverage.Data("lcov", "hash", Map.of());
        var missing = Analysis.analyze(patch, data, "", "", "**", BigDecimal.ZERO);
        assertEquals("INCONCLUSIVE", missing.status()); assertEquals(1, Analysis.summary(missing).path("missingFileLines").asInt());
    }
    @Test void binaryChangesAreInconclusiveButDeletedFilesHaveNoNewLines() throws Exception {
        var data = new Coverage.Data("lcov", "hash", Map.of());
        assertEquals("INCONCLUSIVE", Analysis.analyze(new Patch.Data("hash", List.of(new Patch.File("a.bin", List.of(), true, false))), data, "", "", "**", BigDecimal.ZERO).status());
        assertEquals("NO_CHANGED_LINES", Analysis.analyze(new Patch.Data("hash", List.of(new Patch.File("a.java", List.of(), false, true))), data, "", "", "**", BigDecimal.ZERO).status());
    }
    @Test void paginationAndBranchFilterPreserveOriginalEvidence() throws Exception {
        var result = demo("80");
        var first = Analysis.lines(result, "src/main/java/shop/Pricing.java", "ALL", 0, 2);
        assertEquals(4, first.path("total").asInt()); assertEquals(2, first.path("nextOffset").asInt());
        var last = Analysis.lines(result, "src/main/java/shop/Pricing.java", "ALL", 2, 2);
        assertTrue(last.path("nextOffset").isNull()); assertEquals(7, last.path("lines").get(0).path("line").asInt());
        assertEquals(2, Analysis.lines(result, "src/main/java/shop/Pricing.java", "MISSED_BRANCHES", 0, 25).path("total").asInt());
        assertThrows(IllegalArgumentException.class, () -> Analysis.files(result, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Analysis.lines(result, "missing", "ALL", 0, 1));
    }
    @Test void absentLineRecordsCannotProduceAPassingEmptyDenominator() throws Exception {
        var patch = new Patch.Data("hash", List.of(new Patch.File("a.java", List.of(new Patch.Line(10, 1)), false, false)));
        var data = new Coverage.Data("lcov", "hash", Map.of("a.java", Map.of()));
        var result = Analysis.analyze(patch, data, "", "", "**", BigDecimal.ZERO);
        assertEquals("INCONCLUSIVE", result.status()); assertTrue(Analysis.summary(result).get("observedLinePercent").isNull());
    }
    @Test void globsMatchRootAndNestedJavaPathsAndThresholdsAreBounded() throws Exception {
        var patch = new Patch.Data("hash", List.of(new Patch.File("a.java", List.of(), false, false), new Patch.File("src/a.java", List.of(), false, false), new Patch.File("other.kt", List.of(), false, false)));
        var data = new Coverage.Data("lcov", "hash", Map.of());
        var result = Analysis.analyze(patch, data, "", "", "**/*.java", BigDecimal.TEN);
        assertEquals(2, Analysis.summary(result).path("selectedFiles").asInt());
        assertThrows(java.io.IOException.class, () -> Analysis.analyze(patch, data, "", "", "**", new BigDecimal("101")));
        assertThrows(java.io.IOException.class, () -> Analysis.analyze(patch, data, "", "", "../**", BigDecimal.TEN));
    }
    @Test void reportDetailBudgetDoesNotLoseTotalsOrMcpEvidence() throws Exception {
        var lines = new ArrayList<Patch.Line>(); var coverage = new TreeMap<Integer, Coverage.Line>();
        for (int i = 1; i <= 2100; i++) { lines.add(new Patch.Line(i, i + 4)); coverage.put(i, new Coverage.Line(0, null, null, "lcov:" + i)); }
        var result = Analysis.analyze(new Patch.Data("h", List.of(new Patch.File("a", lines, false, false))), new Coverage.Data("lcov", "h", Map.of("a", coverage)), "", "", "**", BigDecimal.TEN);
        var full = Analysis.full(result);
        assertEquals(2100, full.path("uncoveredLines").asInt()); assertEquals(2000, full.path("fileResults").get(0).path("lines").size());
        assertTrue(full.path("fileResults").get(0).path("linesTruncated").asBoolean());
        assertEquals(2100, Analysis.lines(result, "a", "ALL", 2000, 100).path("lines").get(99).path("line").asInt());
    }
    @Test void htmlAndMarkdownTreatPathsAsData() throws Exception {
        var result = Analysis.analyze(new Patch.Data("h", List.of(new Patch.File("<img onerror=x>.java", List.of(new Patch.Line(1, 1)), false, false))), new Coverage.Data("lcov", "h", Map.of()), "", "", "**", BigDecimal.TEN);
        String html = Reports.render(result, "html");
        assertFalse(html.contains("<img onerror")); assertTrue(html.contains("&lt;img onerror=x&gt;")); assertTrue(html.contains("default-src 'none'"));
        assertFalse(Reports.render(result, "md").contains("<img onerror"));
    }
    @Test void markdownFileLabelsCannotCreateLinksImagesOrFormatting() throws Exception {
        var files = List.of(
                new Patch.File("src/![pixel](https:example.test).java", List.of(), false, false),
                new Patch.File("src/[open](javascript:alert(1)).java", List.of(), false, false),
                new Patch.File("src/a&b|c_*~.java", List.of(), false, false));
        var result = Analysis.analyze(new Patch.Data("h", files), new Coverage.Data("lcov", "h", Map.of()), "", "", "**", BigDecimal.TEN);
        String markdown = Reports.render(result, "md");
        String table = markdown.substring(markdown.indexOf("| File"), markdown.indexOf("## Evidence"));
        assertTrue(table.contains("src\\/\\!\\[pixel\\]\\(https\\:example\\.test\\)\\.java"));
        assertTrue(table.contains("\\[open\\]\\(javascript\\:alert\\(1\\)\\)"));
        assertTrue(table.contains("a&amp;b\\|c\\_\\*\\~\\.java"));
        assertFalse(table.contains("![pixel](")); assertFalse(table.contains("[open]("));
    }
}
