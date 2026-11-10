package io.github.almeida96.patchmeter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

public final class Analysis {
    public record Evidence(int line, int diffLine, String status, Long hits,
            Long branchesCovered, Long branchesMissed, String coverageEvidence) {}
    public record File(String path, boolean selected, boolean binary, boolean deleted, boolean coveragePresent,
            int changedLines, int covered, int uncovered, int notReported, int missing, List<Evidence> lines) {}
    public record Result(String diffSha256, String coverageSha256, String coverageFormat,
            String sourceRoot, String stripPrefix, String include, BigDecimal minimumPercent,
            String status, List<File> files) {}
    private Analysis() {}
    public static Result analyze(Patch.Data patch, Coverage.Data coverage, String root, String strip,
            String include, BigDecimal minimum) throws IOException {
        if (minimum == null || minimum.signum() < 0 || minimum.compareTo(BigDecimal.valueOf(100)) > 0 || minimum.scale() > 4)
            throw new IOException("Minimum coverage must be 0..100 with at most four decimal places");
        Pattern scope = glob(include);
        var files = new ArrayList<File>(); int covered = 0, uncovered = 0, missing = 0, selectedLines = 0;
        boolean binary = false;
        for (Patch.File file : patch.files()) {
            boolean selected = scope.matcher(file.path()).matches();
            Map<Integer, Coverage.Line> source = coverage.files().get(file.path());
            var evidence = new ArrayList<Evidence>(); int c = 0, u = 0, n = 0, m = 0;
            if (selected && !file.deleted()) for (Patch.Line line : file.added()) {
                Coverage.Line observation = source == null ? null : source.get(line.number());
                String status = source == null ? "MISSING_FILE" : observation == null ? "NOT_REPORTED" : observation.hits() > 0 ? "COVERED" : "UNCOVERED";
                switch (status) { case "COVERED" -> c++; case "UNCOVERED" -> u++; case "NOT_REPORTED" -> n++; default -> m++; }
                evidence.add(new Evidence(line.number(), line.diffLine(), status, observation == null ? null : observation.hits(),
                        observation == null ? null : observation.branchesCovered(), observation == null ? null : observation.branchesMissed(),
                        observation == null ? null : observation.evidence()));
            }
            if (selected) { covered += c; uncovered += u; missing += m; selectedLines += file.added().size(); binary |= file.binary() && !file.deleted(); }
            files.add(new File(file.path(), selected, file.binary(), file.deleted(), source != null, file.added().size(), c, u, n, m, List.copyOf(evidence)));
        }
        String status;
        if (missing > 0 || binary || (selectedLines > 0 && covered + uncovered == 0)) status = "INCONCLUSIVE";
        else if (selectedLines == 0) status = "NO_CHANGED_LINES";
        else status = BigDecimal.valueOf(covered).multiply(BigDecimal.valueOf(100))
                    .compareTo(minimum.multiply(BigDecimal.valueOf(covered + uncovered))) >= 0 ? "PASS" : "FAIL";
        return new Result(patch.sha256(), coverage.sha256(), coverage.format(), root, strip, include, minimum, status, List.copyOf(files));
    }
    private static Pattern glob(String pattern) throws IOException {
        if (pattern.isBlank() || pattern.length() > 256 || pattern.contains("\\") || pattern.contains("..")) throw new IOException("Invalid include glob");
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                i++; if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') { i++; out.append("(?:.*/)?"); } else out.append(".*");
            } else if (c == '*') out.append("[^/]*");
            else if (c == '?') out.append("[^/]");
            else out.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(out.append('$').toString());
    }
    public static ObjectNode summary(Result result) {
        var json = base(result).put("kind", "summary");
        int covered = 0, uncovered = 0, notReported = 0, missing = 0, changed = 0, selected = 0, partial = 0;
        for (File f : result.files()) if (f.selected()) {
            selected++; covered += f.covered(); uncovered += f.uncovered(); notReported += f.notReported(); missing += f.missing(); changed += f.changedLines();
            partial += (int) f.lines().stream().filter(l -> l.branchesMissed() != null && l.branchesMissed() > 0).count();
        }
        json.put("files", result.files().size()).put("selectedFiles", selected).put("excludedFiles", result.files().size() - selected)
                .put("changedLines", changed).put("coveredLines", covered).put("uncoveredLines", uncovered)
                .put("notReportedLines", notReported).put("missingFileLines", missing).put("linesWithMissedBranches", partial);
        if (covered + uncovered == 0) json.putNull("observedLinePercent");
        else json.put("observedLinePercent", BigDecimal.valueOf(covered * 100L).divide(BigDecimal.valueOf(covered + uncovered), 4, java.math.RoundingMode.HALF_UP));
        json.putArray("limitations")
                .add("Coverage must be generated from the same candidate revision as the diff. Hashes identify input bytes, not revision alignment.")
                .add("The percentage uses added lines with explicit coverage records. NOT_REPORTED lines may be non-executable, excluded or missing debug data; they are never counted as covered.")
                .add("Line execution does not prove assertions, branch completeness or correctness. Branch observations are diagnostic and do not determine the line threshold.")
                .add("Deleted lines and files outside the explicit include glob are not assessed. Missing selected files and selected binary changes are inconclusive.");
        return json;
    }
    public static ObjectNode files(Result result, int offset, int limit) {
        page(offset, limit); var json = base(result).put("kind", "files").put("total", result.files().size()).put("offset", offset).put("limit", limit);
        var rows = json.putArray("files");
        result.files().stream().skip(offset).limit(limit).forEach(f -> rows.addObject().put("path", f.path()).put("selected", f.selected())
                .put("binary", f.binary()).put("deleted", f.deleted()).put("coveragePresent", f.coveragePresent()).put("changedLines", f.changedLines())
                .put("covered", f.covered()).put("uncovered", f.uncovered()).put("notReported", f.notReported()).put("missing", f.missing()));
        next(json, offset, limit, result.files().size()); return json;
    }
    public static ObjectNode lines(Result result, String path, String status, int offset, int limit) {
        page(offset, limit);
        if (!Set.of("ALL", "COVERED", "UNCOVERED", "NOT_REPORTED", "MISSING_FILE", "MISSED_BRANCHES").contains(status)) throw new IllegalArgumentException("Unknown line filter");
        File file = result.files().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow(() -> new IllegalArgumentException("File is absent from the diff"));
        List<Evidence> selected = file.lines().stream().filter(l -> status.equals("ALL") || l.status().equals(status)
                || status.equals("MISSED_BRANCHES") && l.branchesMissed() != null && l.branchesMissed() > 0).toList();
        var json = base(result).put("kind", "lines").put("path", path).put("selected", file.selected()).put("filter", status)
                .put("total", selected.size()).put("offset", offset).put("limit", limit);
        json.set("lines", Json.MAPPER.valueToTree(selected.stream().skip(offset).limit(limit).toList()));
        next(json, offset, limit, selected.size()); return json;
    }
    public static ObjectNode full(Result result) {
        var json = summary(result); json.put("kind", "analysis");
        var files = json.putArray("fileResults"); int remaining = 2000;
        for (File file : result.files()) {
            ObjectNode item = Json.MAPPER.valueToTree(new File(file.path(), file.selected(), file.binary(), file.deleted(), file.coveragePresent(),
                    file.changedLines(), file.covered(), file.uncovered(), file.notReported(), file.missing(), List.of()));
            int displayed = Math.min(remaining, file.lines().size());
            item.set("lines", Json.MAPPER.valueToTree(file.lines().subList(0, displayed)));
            item.put("displayedLines", displayed).put("linesTruncated", displayed < file.lines().size());
            remaining -= displayed; files.add(item);
        }
        json.put("lineDetailBudget", 2000);
        return json;
    }
    private static ObjectNode base(Result result) {
        var json = Json.MAPPER.createObjectNode().put("schemaVersion", 1).put("toolVersion", "0.1.0").put("status", result.status());
        json.putObject("inputs").put("diffSha256", result.diffSha256()).put("coverageSha256", result.coverageSha256()).put("coverageFormat", result.coverageFormat());
        json.putObject("policy").put("minimumPercent", result.minimumPercent()).put("include", result.include())
                .put("sourceRoot", result.sourceRoot()).put("stripPrefix", result.stripPrefix());
        return json;
    }
    private static void page(int offset, int limit) { if (offset < 0 || offset > 1000000 || limit < 1 || limit > 100) throw new IllegalArgumentException("Pagination requires offset 0..1000000 and limit 1..100"); }
    private static void next(ObjectNode json, int offset, int limit, int total) { if (offset + limit < total) json.put("nextOffset", offset + limit); else json.putNull("nextOffset"); }
}
