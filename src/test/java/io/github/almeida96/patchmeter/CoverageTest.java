package io.github.almeida96.patchmeter;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoverageTest {
    private static Coverage.Data parse(String text, String format) throws IOException {
        return Coverage.parse(text.getBytes(StandardCharsets.UTF_8), format, "", "");
    }
    private static String xml(String body) {
        return "<report name=\"test\"><package name=\"app\"><sourcefile name=\"Main.java\">" + body + "</sourcefile></package></report>";
    }
    private static String line(int number, int covered, int missed) {
        return "<line nr=\"" + number + "\" ci=\"" + covered + "\" mi=\"" + missed + "\" cb=\"1\" mb=\"1\"/>";
    }
    private static String section(String body) { return "SF:src/a.js\n" + body + "end_of_record\n"; }

    @Test void readsStandardJacocoDoctypeWithoutFetchingItAndKeepsPartialBranches() throws Exception {
        String report = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"http://127.0.0.1:1/report.dtd\">"
                + xml(line(3, 2, 1) + line(4, 0, 2));
        var data = Coverage.parse(report.getBytes(StandardCharsets.UTF_8), "jacoco", "src/main/java", "");
        var rows = data.files().get("src/main/java/app/Main.java");
        assertEquals(1, rows.get(3).hits()); assertEquals(0, rows.get(4).hits());
        assertEquals(1, rows.get(3).branchesCovered()); assertEquals(1, rows.get(3).branchesMissed());
        assertEquals("jacoco:line[@nr=3]", rows.get(3).evidence());
    }

    @Test void readsNestedJacocoGroupsDefaultPackagesAndClassCounters() throws Exception {
        String report = "<report name=\"r\"><sessioninfo id=\"s\" start=\"0\" dump=\"1\"/><group name=\"outer\"><group name=\"inner\">"
                + "<package name=\"\"><class name=\"Main\"><method name=\"m\" desc=\"()V\" line=\"1\">"
                + "<counter type=\"INSTRUCTION\" missed=\"0\" covered=\"1\"/></method></class>"
                + "<sourcefile name=\"Main.java\">" + line(1, 1, 0) + "</sourcefile></package></group></group></report>";
        assertEquals(1, parse(report, "jacoco").files().get("Main.java").get(1).hits());
    }

    @Test void refusesJacocoEntitiesDuplicateLinesAndModulePathCollisions() {
        for (String report : List.of(
                "<!DOCTYPE report [<!ENTITY secret SYSTEM \"file:///not-to-read\">]>" + xml(line(1, 1, 0)),
                xml(line(1, 1, 0) + line(1, 0, 1)),
                "<report name=\"r\"><group name=\"a\"><package name=\"p\"><sourcefile name=\"A.java\"/></package></group>"
                        + "<group name=\"b\"><package name=\"p\"><sourcefile name=\"A.java\"/></package></group></report>"))
            assertThrows(IOException.class, () -> parse(report, "jacoco"));
    }

    @Test void refusesMalformedJacocoShapePathsAndCounters() {
        for (String report : List.of(xml(line(0, 1, 0)), xml(line(1, 0, 0)), xml(line(1, -1, 2)),
                xml(line(1, 1, 0)).replace("app", "../app"), xml(line(1, 1, 0)).replace("Main.java", "x/Main.java"),
                "<report><package name=\"p\">" + line(1, 1, 0) + "</package></report>",
                "<report><unknown/></report>", xml(line(1, 1, 0)) + "<report/>", xml("text")))
            assertThrows(IOException.class, () -> parse(report, "jacoco"), report);
    }

    @Test void readsIstanbulStyleLcovAndLeavesAbsentBranchesUnknown() throws Exception {
        var data = parse("TN:unit\n" + section("FN:1,main\nFNDA:2,main\nFNF:1\nFNH:1\nDA:1,2\nDA:2,0\nBRDA:1,0,0,2\nBRDA:1,0,1,-\nBRF:2\nBRH:1\nLF:2\nLH:1\n"), "lcov");
        var rows = data.files().get("src/a.js");
        assertEquals(2, rows.get(1).hits()); assertEquals(1, rows.get(1).branchesCovered());
        assertEquals(1, rows.get(1).branchesMissed()); assertNull(rows.get(2).branchesCovered());
        assertEquals("lcov:7", rows.get(1).evidence());
    }

    @Test void mergesRepeatedTestSectionsWithoutInflatingHitsOrProvenance() throws Exception {
        String first = section("VER:rev1\nDA:1,0,checksum\nBRDA:1,0,0,-\n");
        String next = section("VER:rev1\nDA:1,3,checksum\nBRDA:1,0,0,3\n");
        var line = parse(first + next.repeat(100), "lcov").files().get("src/a.js").get(1);
        assertEquals(3, line.hits()); assertNull(line.branchesCovered()); assertNull(line.branchesMissed());
        assertEquals("lcov:3; merged test sections", line.evidence());
    }

    @Test void rejectsConflictingVersionsChecksumsAndStatementSpans() {
        for (String text : List.of(
                section("VER:one\nDA:1,0\n") + section("VER:two\nDA:1,1\n"),
                section("VER:one\nDA:1,0\n") + section("DA:1,1\n"),
                section("DA:1,0,first\n") + section("DA:1,1,second\n"),
                section("DA:1,0,first\n") + section("DA:1,1\n"),
                section("DA:1,0,,2\n") + section("DA:1,1,,3\n"),
                section("VER:\nVER:\nDA:1,1\n")))
            assertThrows(IOException.class, () -> parse(text, "lcov"), text);
    }

    @Test void mapsExplicitWindowsPrefixesAndRejectsNormalizedPathCollisions() throws Exception {
        String input = "SF:C:\\work\\src\\a.js\nDA:1,1\nend_of_record\n";
        var data = Coverage.parse(input.getBytes(StandardCharsets.UTF_8), "lcov", "module", "C:/work");
        assertTrue(data.files().containsKey("module/src/a.js"));
        assertThrows(IOException.class, () -> parse(section("DA:1,0\n") + section("DA:1,1\n").replace("SF:src/", "SF:./src/"), "lcov"));
        assertThrows(IOException.class, () -> Coverage.parse(input.getBytes(StandardCharsets.UTF_8), "lcov", "", "C:/different"));
        assertThrows(IOException.class, () -> parse(input, "lcov"));
    }

    @Test void parsesIndexedSourcesSpansAndStringBranchesWithoutInferringInteriorCoverage() throws Exception {
        String input = "SF:12,src/a.js\nDA:3,1,,5\nBRDA:3,e0,enabled(a,b),1\nBRDA:3,e0,!enabled(a,b),0\n"
                + "BRDA:4,0,other,1\nend_of_record\n";
        var rows = parse(input, "lcov").files().get("src/a.js");
        assertEquals(1, rows.size()); assertNull(rows.get(4)); assertNull(rows.get(5));
        assertEquals(1, rows.get(3).branchesCovered()); assertEquals(1, rows.get(3).branchesMissed());
    }

    @Test void rejectsLcovSummaryMismatchesDuplicateRecordsAndMalformedCounts() {
        for (String body : List.of("DA:1,1\nLF:2\nLH:1\n", "DA:1,0\nLF:1\nLH:1\n", "DA:1,1\nLF:1\nLF:1\n",
                "DA:1,0\nDA:1,1\n", "DA:1,-1\n", "DA:0,1\n", "DA:1,1000000000000\n",
                "DA:1,1\nBRDA:1,0,0,1\nBRDA:1,0,0,0\n", "DA:1,1\nBRDA:1,0,0,-2\n", "DA:1,1,,130\n"))
            assertThrows(IOException.class, () -> parse(section(body), "lcov"), body);
    }

    @Test void rejectsTruncatedAndMisplacedLcovRecords() {
        for (String text : List.of("SF:src/a.js\nDA:1,1\n", "TN:unit\n", "DA:1,1\n", "end_of_record\n",
                "SF:src/a.js\nSF:src/b.js\nend_of_record\n", section("TN:bad\nDA:1,1\n"), section("UNKNOWN:ignored\n")))
            assertThrows(IOException.class, () -> parse(text, "lcov"), text);
    }

    @Test void preservesEmptySourceRecordsButRejectsEmptyReportsAndUnsupportedFormats() throws Exception {
        assertTrue(parse(section("LF:0\nLH:0\n"), "lcov").files().get("src/a.js").isEmpty());
        assertThrows(IOException.class, () -> parse("", "lcov"));
        assertThrows(IOException.class, () -> parse("<report/>", "jacoco"));
        assertThrows(IOException.class, () -> parse("{}", "json"));
        var files = parse(section("DA:1,1\n"), "lcov").files();
        assertThrows(UnsupportedOperationException.class, () -> files.clear());
        assertThrows(UnsupportedOperationException.class, () -> files.get("src/a.js").clear());
    }
}
