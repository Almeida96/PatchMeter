package io.github.almeida96.patchmeter;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Projects source line and branch observations; it does not execute or instrument code. */
public final class Coverage {
    public record Line(long hits, Long branchesCovered, Long branchesMissed, String evidence) {}
    public record Data(String format, String sha256, Map<String, Map<Integer, Line>> files) {}
    private Coverage() {}
    public static Data parse(byte[] bytes, String format, String sourceRoot, String stripPrefix) throws IOException {
        String text = Input.text(bytes);
        if (!sourceRoot.isEmpty()) sourceRoot = Input.path(sourceRoot);
        final String root = sourceRoot;
        var paths = new Paths(root, stripPrefix.replace('\\', '/'));
        return switch (format) {
            case "jacoco" -> jacoco(text, Json.hash(bytes), paths);
            case "lcov" -> lcov(text, Json.hash(bytes), paths);
            default -> throw new IOException("Coverage format must be jacoco or lcov");
        };
    }
    private record Paths(String root, String strip) {
        String map(String value) throws IOException {
            value = value.replace('\\', '/');
            if (!strip.isEmpty()) {
                String prefix = strip.endsWith("/") ? strip : strip + "/";
                if (!value.startsWith(prefix)) throw new IOException("Coverage path does not match --strip-prefix");
                value = value.substring(prefix.length());
            }
            return Input.path(root.isEmpty() ? value : root + "/" + value);
        }
    }
    private static Data jacoco(String text, String hash, Paths paths) throws IOException {
        if (text.contains("<!ENTITY")) throw new IOException("XML entity declarations are unsupported");
        var files = new TreeMap<String, Map<Integer, Line>>();
        try {
            var factory = SAXParserFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            var reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            var handler = new DefaultHandler() {
                final Deque<String> stack = new ArrayDeque<>();
                String packageName, file;
                int records, elements;
                @Override public void startElement(String uri, String local, String name, Attributes a) throws SAXException {
                    try {
                        String parent = stack.peek();
                        if (++elements > 1000000 || stack.size() >= 64) throw new IOException("XML structure exceeds limits");
                        if (stack.isEmpty() && !name.equals("report")) throw new IOException("Expected JaCoCo report root");
                        boolean allowed = switch (name) {
                            case "report" -> parent == null;
                            case "sessioninfo", "group" -> "report".equals(parent) || name.equals("group") && "group".equals(parent);
                            case "package" -> "report".equals(parent) || "group".equals(parent);
                            case "class", "sourcefile" -> "package".equals(parent);
                            case "method" -> "class".equals(parent);
                            case "line" -> "sourcefile".equals(parent);
                            case "counter" -> parent != null && Set.of("report", "group", "package", "class", "method", "sourcefile").contains(parent);
                            default -> false;
                        };
                        if (!allowed) throw new IOException("Unexpected JaCoCo element " + name);
                        if (name.equals("package")) {
                            packageName = attr(a, "name", false);
                            if (packageName.contains("\\") || packageName.startsWith("./")) throw new IOException("Invalid JaCoCo package path");
                            if (!packageName.isEmpty()) Input.path(packageName);
                        } else if (name.equals("sourcefile")) {
                            if (!"package".equals(parent)) throw new IOException("Unexpected JaCoCo sourcefile");
                            String source = attr(a, "name", true);
                            if (source.contains("/") || source.contains("\\")) throw new IOException("JaCoCo sourcefile name must be a filename");
                            file = paths.map(packageName.isEmpty() ? source : packageName + "/" + source);
                            if (files.size() >= Input.MAX_FILES || files.putIfAbsent(file, new TreeMap<>()) != null)
                                throw new IOException("Duplicate coverage path or more than 5,000 files; split modules explicitly");
                        } else if (name.equals("line")) {
                            if (!"sourcefile".equals(parent) || file == null) throw new IOException("Unexpected JaCoCo line");
                            int nr = Input.number(attr(a, "nr", true), true);
                            long ci = counter(attr(a, "ci", true)), mi = counter(attr(a, "mi", true));
                            long cb = counter(attr(a, "cb", true)), mb = counter(attr(a, "mb", true));
                            if (ci + mi == 0) throw new IOException("JaCoCo line has no instructions");
                            if (++records > Input.MAX_LINES || files.get(file).putIfAbsent(nr,
                                    new Line(ci > 0 ? 1 : 0, cb, mb, "jacoco:line[@nr=" + nr + "]")) != null)
                                throw new IOException("Duplicate coverage line or more than 250,000 lines");
                        }
                        stack.push(name);
                    } catch (IOException e) { throw new SAXException(e.getMessage(), e); }
                }
                @Override public void endElement(String uri, String local, String name) {
                    stack.pop(); if (name.equals("sourcefile")) file = null;
                }
                @Override public void characters(char[] ch, int start, int length) throws SAXException {
                    for (int i = start; i < start + length; i++) if (!Character.isWhitespace(ch[i]))
                        throw new SAXException("Unexpected text in JaCoCo XML");
                }
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            };
            reader.setContentHandler(handler); reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(text)));
        } catch (Exception e) { throw new IOException("Invalid JaCoCo XML: " + e.getMessage(), e); }
        return data("jacoco", hash, files);
    }
    private static String attr(Attributes a, String key, boolean nonempty) throws IOException {
        String value = a.getValue(key);
        if (value == null || value.length() > 2048 || (nonempty && value.isEmpty())) throw new IOException("Missing or oversized XML attribute " + key);
        return value;
    }
    private static long counter(String value) throws IOException {
        try { if (!value.matches("[0-9]{1,12}")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new IOException("Coverage counters must be nonnegative integers up to 12 digits"); }
    }
    private record Identity(String checksum, int statementEnd) {}
    private record Branch(String block, String expression) {}
    private static Data lcov(String text, String hash, Paths paths) throws IOException {
        var files = new TreeMap<String, Map<Integer, Line>>();
        var versions = new HashMap<String, String>();
        var sourceNames = new HashMap<String, String>();
        var identities = new HashMap<String, Map<Integer, Identity>>();
        String file = null, version = ""; int records = 0, sections = 0;
        boolean versionSeen = false, pendingTest = false;
        var section = new TreeMap<Integer, Line>();
        var sectionIdentities = new HashMap<Integer, Identity>();
        var branches = new HashMap<Integer, Map<Branch, Boolean>>();
        Long expectedFound = null, expectedHit = null;
        String[] rows = text.split("\\r?\\n", -1);
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i];
            if (row.isEmpty() || row.startsWith("#")) continue;
            if (row.startsWith("TN:")) {
                if (file != null || pendingTest) throw new IOException("LCOV TN must precede one file section");
                pendingTest = true; continue;
            }
            if (row.startsWith("SF:") || row.startsWith("KF:")) {
                if (file != null) throw new IOException("LCOV section lacks end_of_record");
                String source = row.substring(3);
                int comma = source.indexOf(',');
                if (comma > 0 && source.substring(0, comma).matches("[0-9]+")) {
                    Input.number(source.substring(0, comma), false); source = source.substring(comma + 1);
                }
                file = paths.map(source);
                String previousName = sourceNames.putIfAbsent(file, source);
                if (previousName != null && !previousName.equals(source)) throw new IOException("Distinct LCOV paths normalize to the same source file");
                section = new TreeMap<>(); branches = new HashMap<>(); sectionIdentities = new HashMap<>();
                version = ""; versionSeen = false; pendingTest = false; expectedFound = null; expectedHit = null;
                if (++sections > 10000) throw new IOException("LCOV exceeds 10,000 sections");
                continue;
            }
            if (file == null) throw new IOException("LCOV data outside a file section");
            if (row.equals("end_of_record")) {
                long hit = section.values().stream().filter(l -> l.hits() > 0).count();
                if ((expectedFound != null && expectedFound != section.size()) || (expectedHit != null && expectedHit != hit))
                    throw new IOException("LCOV LF/LH does not match DA records");
                String previousVersion = versions.putIfAbsent(file, version);
                if (previousVersion != null && !previousVersion.equals(version)) throw new IOException("Conflicting LCOV source versions");
                Map<Integer, Line> target = files.computeIfAbsent(file, key -> new TreeMap<>());
                Map<Integer, Identity> targetIdentities = identities.computeIfAbsent(file, key -> new HashMap<>());
                if (files.size() > Input.MAX_FILES) throw new IOException("Coverage exceeds 5,000 files");
                for (var e : section.entrySet()) {
                    Identity identity = sectionIdentities.get(e.getKey());
                    Identity previousIdentity = targetIdentities.putIfAbsent(e.getKey(), identity);
                    if (previousIdentity != null && !previousIdentity.equals(identity))
                        throw new IOException("Conflicting LCOV line checksums or statement spans");
                    var b = branches.get(e.getKey());
                    long covered = b == null ? 0 : b.values().stream().filter(Boolean::booleanValue).count();
                    Line value = new Line(e.getValue().hits(), b == null ? null : covered, b == null ? null : b.size() - covered, e.getValue().evidence());
                    Line previous = target.putIfAbsent(e.getKey(), value);
                    // Multiple test sections can merge line execution. Branch identity across test sections is not inferred.
                    if (previous != null) target.put(e.getKey(), new Line(Math.max(previous.hits(), value.hits()), null, null,
                            previous.evidence().contains("; merged test sections") ? previous.evidence() : previous.evidence() + "; merged test sections"));
                }
                file = null; continue;
            }
            if (row.startsWith("DA:")) {
                String[] fields = row.substring(3).split(",", -1);
                if (fields.length < 2 || fields.length > 4) throw new IOException("Malformed LCOV DA");
                int nr = Input.number(fields[0], true); long hits = counter(fields[1]);
                String checksum = fields.length >= 3 ? fields[2] : "";
                if (checksum.length() > 128 || checksum.chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid LCOV checksum");
                int end = fields.length == 4 ? Input.number(fields[3], true) : nr;
                if (end < nr || end - nr > 127) throw new IOException("Invalid LCOV statement span");
                sectionIdentities.put(nr, new Identity(checksum, end));
                if (++records > Input.MAX_LINES || section.putIfAbsent(nr, new Line(hits, null, null, "lcov:" + (i + 1))) != null)
                    throw new IOException("Duplicate DA within section or more than 250,000 records");
            } else if (row.startsWith("BRDA:")) {
                String value = row.substring(5);
                int first = value.indexOf(','), second = value.indexOf(',', first + 1), last = value.lastIndexOf(',');
                if (first < 1 || second <= first || last <= second) throw new IOException("Malformed LCOV BRDA");
                int nr = Input.number(value.substring(0, first), true);
                String block = value.substring(first + 1, second), expression = value.substring(second + 1, last);
                // Exception/fallthrough markers describe the same block identity. More
                // elaborate exclusion/location records require producer-side normalization.
                if (block.startsWith("e") || block.startsWith("f")) block = block.substring(1);
                block = Integer.toString(Input.number(block, false));
                if (expression.isEmpty() || expression.length() > 2048 || expression.chars().anyMatch(Character::isISOControl))
                    throw new IOException("Invalid LCOV branch expression");
                String count = value.substring(last + 1);
                boolean taken = !count.equals("-") && counter(count) > 0;
                if (++records > Input.MAX_LINES || branches.computeIfAbsent(nr, key -> new HashMap<>()).putIfAbsent(new Branch(block, expression), taken) != null)
                    throw new IOException("Duplicate branch or record limit exceeded");
            } else if (row.startsWith("LF:")) { if (expectedFound != null) throw new IOException("Duplicate LF"); expectedFound = counter(row.substring(3)); }
            else if (row.startsWith("LH:")) { if (expectedHit != null) throw new IOException("Duplicate LH"); expectedHit = counter(row.substring(3)); }
            else if (row.startsWith("VER:")) {
                if (versionSeen) throw new IOException("Duplicate VER");
                versionSeen = true; version = row.substring(4);
                if (version.length() > 2048 || version.chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid LCOV source version");
            }
            else if (!row.matches("^(FN|FNDA|FNF|FNH|FNL|FNA|FNC|FNM|FNCF|FNCH|BRF|BRH|MCDC|MCF|MCH|MCDCF|MCDCH):.*"))
                throw new IOException("Unsupported LCOV record at line " + (i + 1));
        }
        if (file != null || pendingTest) throw new IOException("Incomplete LCOV file section");
        return data("lcov", hash, files);
    }
    private static Data data(String format, String hash, Map<String, Map<Integer, Line>> files) throws IOException {
        if (files.isEmpty()) throw new IOException("Coverage contains no source files");
        var stable = new TreeMap<String, Map<Integer, Line>>();
        files.forEach((key, value) -> stable.put(key, Collections.unmodifiableMap(new TreeMap<>(value))));
        return new Data(format, hash, Collections.unmodifiableMap(stable));
    }
}
