package io.github.almeida96.patchmeter;

import com.fasterxml.jackson.databind.JsonNode;

public final class Reports {
    private Reports() {}
    public static String render(Analysis.Result result, String format) {
        var data = Analysis.full(result);
        if (format.equals("json")) return Json.pretty(data);
        if (format.equals("md")) {
            var md = new StringBuilder("# Patch coverage review\n\n**Status: " + result.status() + "**\n\n");
            md.append("Observed changed-line coverage: ").append(data.get("observedLinePercent").isNull() ? "not available" : data.get("observedLinePercent").asText() + "%")
                    .append(". Required: ").append(result.minimumPercent()).append("%.\n\n");
            md.append("| File | Covered | Uncovered | Not reported | Missing file |\n|---|---:|---:|---:|---:|\n");
            for (var f : result.files()) if (f.selected()) md.append("| ").append(markdown(f.path())).append(" | ").append(f.covered()).append(" | ").append(f.uncovered()).append(" | ").append(f.notReported()).append(" | ").append(f.missing()).append(" |\n");
            md.append("\n"); data.get("limitations").forEach(l -> md.append("- ").append(l.asText()).append("\n"));
            md.append("\n## Evidence\n\nLine details are capped at 2,000; use MCP pagination for every observation.\n\n````json\n")
                    .append(Json.pretty(data).replace("`", "\\u0060").replace("<", "\\u003c")).append("````\n");
            return md.toString();
        }
        if (!format.equals("html")) throw new IllegalArgumentException("Unknown report format");
        var body = new StringBuilder("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\"><title>PatchMeter — coverage review</title><style>");
        body.append("""
            *{box-sizing:border-box}body{margin:0;background:#f2f4f8;color:#172336;font:16px/1.5 system-ui,sans-serif}main{max-width:1180px;margin:auto;padding:36px 24px}
            header{background:#152b46;color:#fff;padding:28px;border-radius:12px;border-top:5px solid #50d4b4}.brand{color:#73e2c9;font:700 12px monospace;letter-spacing:.15em}h1{font-size:38px;margin:10px 0}h2{font-size:22px;margin-top:28px}.badge{background:#ffdc8b;color:#362b11;padding:5px 12px;border-radius:5px;font:700 13px monospace}
            .metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin:22px 0}.metric{background:white;border:1px solid #dce3eb;border-radius:8px;padding:18px}.metric span{color:#526479;font-size:13px}.metric strong{display:block;font-size:30px}table{width:100%;border-collapse:collapse;background:white}th,td{text-align:left;padding:13px;border-bottom:1px solid #e0e6ef;overflow-wrap:anywhere}th{font-size:12px;background:#e8edf5;text-transform:uppercase}td:first-child{max-width:440px}code,pre{font-family:ui-monospace,monospace;font-size:12px}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#152b46;color:#f2f4f8;padding:20px;border-radius:8px}details{margin-top:24px}summary{cursor:pointer;font-weight:600}.note{padding:18px;background:#fff8e6;border-left:4px solid #d5a334;margin:20px 0}.table{overflow:auto}footer{margin-top:30px;color:#526479;font-size:12px}li{margin-bottom:8px}@media(max-width:650px){.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}main{padding:16px}h1{font-size:29px}}
            </style></head><body><main><header><div class="brand">PATCHMETER / CHANGED-LINE EVIDENCE</div><h1>What did the tests exercise?</h1>
            """);
        body.append("<span class=\"badge\">").append(result.status()).append("</span><p>Review added lines against recorded coverage before merging a change.</p></header><section class=\"metrics\">");
        metric(body, "Observed coverage", data.get("observedLinePercent").isNull() ? "—" : data.get("observedLinePercent").asText() + "%");
        metric(body, "Covered lines", data.path("coveredLines").asText()); metric(body, "Uncovered lines", data.path("uncoveredLines").asText());
        metric(body, "Not reported / missing", Integer.toString(data.path("notReportedLines").asInt() + data.path("missingFileLines").asInt()));
        body.append("</section><div class=\"note\">Threshold: <strong>").append(result.minimumPercent()).append("%</strong> of added lines with explicit coverage records. Lines absent from the report are displayed separately. Scope: <code>").append(escape(result.include())).append("</code>.</div>");
        body.append("<h2>Changed files</h2><div class=\"table\"><table><thead><tr><th>File</th><th>Covered</th><th>Uncovered</th><th>Not reported</th><th>Missing file</th></tr></thead><tbody>");
        for (var f : result.files()) if (f.selected()) body.append("<tr><td><code>").append(escape(f.path())).append("</code>")
                .append(f.binary() ? " (binary)" : f.deleted() ? " (deleted)" : "").append("</td><td>").append(f.covered()).append("</td><td>")
                .append(f.uncovered()).append("</td><td>").append(f.notReported()).append("</td><td>").append(f.missing()).append("</td></tr>");
        body.append("</tbody></table></div><h2>Lines to investigate</h2><div class=\"table\"><table><thead><tr><th>Source location</th><th>Observation</th><th>Evidence</th></tr></thead><tbody>");
        int shown = 0, totalGaps = 0;
        for (var f : result.files()) for (var line : f.lines()) if (!line.status().equals("COVERED") || line.branchesMissed() != null && line.branchesMissed() > 0) {
            totalGaps++; if (shown++ >= 200) continue;
            body.append("<tr><td><code>").append(escape(f.path())).append(':').append(line.line()).append("</code></td><td>")
                    .append(line.status()).append(line.branchesMissed() != null && line.branchesMissed() > 0 ? " / missed branches: " + line.branchesMissed() : "")
                    .append("</td><td>diff:").append(line.diffLine()).append("<br>").append(escape(line.coverageEvidence() == null ? "No coverage record" : line.coverageEvidence())).append("</td></tr>");
        }
        body.append("</tbody></table></div><p>Showing ").append(Math.min(totalGaps, 200)).append(" of ").append(totalGaps).append(" observations needing review. MCP pages expose all lines.</p><h2>Interpretation</h2><ul>");
        data.get("limitations").forEach(l -> body.append("<li>").append(escape(l.asText())).append("</li>"));
        body.append("</ul><details><summary>Structured evidence and source hashes (2,000-line detail budget)</summary><pre>").append(escape(Json.pretty(data)))
                .append("</pre></details><footer>PatchMeter 0.1.0 · Local report · No source code, scripts or external assets embedded.</footer></main></body></html>");
        return body.toString();
    }
    private static void metric(StringBuilder out, String label, String value) { out.append("<div class=\"metric\"><span>").append(label).append("</span><strong>").append(escape(value)).append("</strong></div>"); }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String markdown(String value) {
        var escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') escaped.append("&amp;");
            else if (c == '<') escaped.append("&lt;");
            else if (c == '>') escaped.append("&gt;");
            else {
                // CommonMark escapes keep filenames from becoming images, links,
                // emphasis, table delimiters or autolinks in a rendered report.
                if (c >= '!' && c <= '/' || c >= ':' && c <= '@'
                        || c >= '[' && c <= '`' || c >= '{' && c <= '~') escaped.append('\\');
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
