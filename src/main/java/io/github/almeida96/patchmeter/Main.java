package io.github.almeida96.patchmeter;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
    public static void main(String[] args) { System.exit(run(args, System.in, System.out, System.err)); }
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                out.println("""
                    PatchMeter 0.1.0 — changed-line coverage evidence
                    patchmeter analyze --diff FILE --coverage FILE --coverage-format jacoco|lcov
                      [--source-root src/main/java] [--strip-prefix /build/project]
                      [--include '**/*.java'] [--minimum 80] [--format json|md|html] [--out NEW_FILE]
                    patchmeter mcp --diff FILE --coverage FILE --coverage-format jacoco|lcov [same analysis options]
                    patchmeter demo --dir NEW_DIRECTORY
                    Analyze exits: 0 PASS/NO_CHANGED_LINES, 2 FAIL, 3 INCONCLUSIVE, 1 invalid input.
                    """); return 0;
            }
            String command = args[0];
            if (!Set.of("analyze", "mcp", "demo").contains(command)) throw new IOException("Unknown command");
            var options = new LinkedHashMap<String, String>();
            Set<String> allowed = command.equals("demo") ? Set.of("dir") : command.equals("mcp")
                    ? Set.of("diff", "coverage", "coverage-format", "source-root", "strip-prefix", "include", "minimum")
                    : Set.of("diff", "coverage", "coverage-format", "source-root", "strip-prefix", "include", "minimum", "format", "out");
            for (int i = 1; i < args.length; i += 2) {
                if (!args[i].startsWith("--") || i + 1 >= args.length || !allowed.contains(args[i].substring(2)) || args[i + 1].isBlank()
                        || options.putIfAbsent(args[i].substring(2), args[i + 1]) != null) throw new IOException("Invalid, repeated or missing option");
            }
            if (command.equals("demo")) {
                Path dir = Path.of(required(options, "dir")); Files.createDirectory(dir);
                for (String file : List.of("change.diff", "jacoco.xml", "lcov.info")) {
                    try (var resource = Main.class.getResourceAsStream("/" + file)) {
                        if (resource == null) throw new IOException("Missing demo resource");
                        Files.copy(resource, dir.resolve(file));
                    }
                }
                var result = load(dir.resolve("change.diff"), dir.resolve("jacoco.xml"), "jacoco", "src/main/java", "", "**/*.java", new BigDecimal("80"));
                Files.writeString(dir.resolve("report.html"), Reports.render(result, "html"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                Files.writeString(dir.resolve("report.json"), Reports.render(result, "json"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                out.println(Json.pretty(Map.of("status", "completed", "fixture", "synthetic-pricing-change", "gate", result.status(), "report", dir.resolve("report.html").toString()))); return 0;
            }
            String format = options.getOrDefault("format", "json");
            if (!Set.of("json", "md", "html").contains(format)) throw new IOException("Output format must be json, md or html");
            Path destination = options.containsKey("out") ? Path.of(options.get("out")) : null;
            if (destination != null && Files.exists(destination)) throw new IOException("Output file already exists");
            Analysis.Result result = load(Path.of(required(options, "diff")), Path.of(required(options, "coverage")), required(options, "coverage-format"),
                    options.getOrDefault("source-root", ""), options.getOrDefault("strip-prefix", ""), options.getOrDefault("include", "**"), new BigDecimal(options.getOrDefault("minimum", "80")));
            if (command.equals("mcp")) { new McpServer(result).serve(in, out); return 0; }
            String report = Reports.render(result, format);
            if (destination == null) out.print(report); else Files.writeString(destination, report, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return switch (result.status()) { case "FAIL" -> 2; case "INCONCLUSIVE" -> 3; default -> 0; };
        } catch (IOException | IllegalArgumentException e) { err.println("patchmeter: " + e.getMessage()); return 1; }
    }
    static Analysis.Result load(Path diff, Path coverage, String format, String root, String strip, String include, BigDecimal minimum) throws IOException {
        return Analysis.analyze(Patch.parse(Input.read(diff)), Coverage.parse(Input.read(coverage), format, root, strip), root, strip, include, minimum);
    }
    private static String required(Map<String, String> options, String key) throws IOException {
        if (!options.containsKey(key)) throw new IOException("Missing --" + key); return options.get(key);
    }
}
