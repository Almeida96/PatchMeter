package io.github.almeida96.patchmeter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Parses ordinary Git unified diffs without invoking git or retaining source text. */
public final class Patch {
    public record Line(int number, int diffLine) {}
    public record File(String path, List<Line> added, boolean binary, boolean deleted) {}
    public record Data(String sha256, List<File> files) {}
    private static final Pattern HUNK = Pattern.compile("^@@ -([0-9]+)(?:,([0-9]+))? \\+([0-9]+)(?:,([0-9]+))? @@(?:.*)$");
    private Patch() {}

    public static Data parse(byte[] bytes) throws IOException {
        String[] lines = Input.text(bytes).split("\\r?\\n", -1);
        var files = new ArrayList<File>(); var paths = new HashSet<String>();
        State state = null;
        int oldLeft = 0, newLeft = 0, next = 0, total = 0;
        boolean newlineMarkerAllowed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.equals("\\ No newline at end of file")) {
                if (!newlineMarkerAllowed) throw new IOException("Unexpected no-newline marker");
                newlineMarkerAllowed = false;
                continue;
            }
            if (oldLeft > 0 || newLeft > 0) {
                if (line.isEmpty()) throw new IOException("Truncated diff hunk at line " + (i + 1));
                char marker = line.charAt(0);
                if (marker == ' ' || marker == '-') { if (--oldLeft < 0) throw new IOException("Invalid old hunk count"); }
                if (marker == ' ' || marker == '+') {
                    if (--newLeft < 0) throw new IOException("Invalid new hunk count");
                    if (marker == '+') {
                        state.added.add(new Line(next, i + 1));
                        if (++total > 50000) throw new IOException("Diff exceeds 50,000 added lines");
                    }
                    next++;
                }
                if (marker != ' ' && marker != '-' && marker != '+') throw new IOException("Unexpected diff hunk content");
                newlineMarkerAllowed = true;
                continue;
            }
            newlineMarkerAllowed = false;
            if (line.startsWith("diff --cc ") || line.startsWith("diff --combined ") || line.startsWith("@@@"))
                throw new IOException("Combined merge diffs are unsupported; compare two revisions");
            if (line.startsWith("diff --git ")) {
                if (state != null) state.finish(files, paths);
                state = new State(line.substring(11));
            } else if (line.startsWith("--- ")) {
                if (state == null || state.oldHeader || state.hunks || state.binary) throw new IOException("Unexpected old-file header");
                state.oldPath = headerPath(line.substring(4), "a/"); state.oldHeader = true;
            } else if (line.startsWith("+++ ")) {
                if (state == null || !state.oldHeader || state.headers) throw new IOException("Unexpected new-file header");
                state.newPath = headerPath(line.substring(4), "b/"); state.headers = true;
                if (state.oldPath == null && state.newPath == null) throw new IOException("Both diff paths are /dev/null");
                if (state.deleted && state.newPath != null) throw new IOException("Conflicting deleted-file metadata");
                if (state.created && state.oldPath != null) throw new IOException("Conflicting new-file metadata");
                state.deleted = state.newPath == null;
            } else if (line.startsWith("@@")) {
                if (state == null || !state.headers || state.binary) throw new IOException("Hunk has no supported new-file header");
                var m = HUNK.matcher(line);
                if (!m.matches()) throw new IOException("Malformed unified hunk header");
                int oldStart = Input.number(m.group(1), false); next = Input.number(m.group(3), false);
                oldLeft = m.group(2) == null ? 1 : Input.number(m.group(2), false);
                newLeft = m.group(4) == null ? 1 : Input.number(m.group(4), false);
                if (state.deleted && newLeft != 0 || state.oldPath == null && oldLeft != 0)
                    throw new IOException("Hunk conflicts with created/deleted file headers");
                if (oldLeft + newLeft == 0 || oldLeft > 0 && oldStart == 0 || newLeft > 0 && next == 0
                        || (long) oldStart + oldLeft > 999999999L || (long) next + newLeft > 999999999L)
                    throw new IOException("Invalid hunk range");
                int oldBegin = oldStart + (oldLeft == 0 ? 1 : 0), newBegin = next + (newLeft == 0 ? 1 : 0);
                if (oldBegin < state.oldEnd || newBegin < state.newEnd
                        || oldBegin - state.oldEnd != newBegin - state.newEnd)
                    throw new IOException("Overlapping or inconsistent diff hunk ranges");
                state.oldEnd = oldBegin + oldLeft; state.newEnd = newBegin + newLeft; state.hunks = true;
            } else if (state != null && state.binaryPayload) {
                // Git's encoded binary payload is not interpreted as source lines.
                if (!line.isEmpty() && !line.matches("(?:literal|delta) [0-9]+|[A-Za-z][!-~]+"))
                    throw new IOException("Invalid Git binary patch payload");
            } else if (line.equals("GIT binary patch") || line.startsWith("Binary files ")) {
                if (state == null || state.headers || state.hunks || state.binary) throw new IOException("Unexpected binary marker");
                state.binary = true; state.binaryPayload = line.equals("GIT binary patch");
            } else if (line.startsWith("deleted file mode ")) {
                if (state == null || state.headers || state.hunks || state.deleted || state.created
                        || !line.matches("deleted file mode [0-7]{6}")) throw new IOException("Unexpected deleted-file metadata");
                state.deleted = true;
            } else if (line.startsWith("new file mode ") || line.startsWith("old mode ") || line.startsWith("new mode ")) {
                if (state == null || state.oldHeader || state.hunks
                        || !line.matches("(?:new file mode |old mode |new mode )[0-7]{6}")) throw new IOException("Unexpected file-mode metadata");
                if (line.startsWith("new file mode ")) {
                    if (state.created || state.deleted) throw new IOException("Conflicting new-file metadata");
                    state.created = true;
                } else if (line.startsWith("old mode ")) {
                    if (state.oldMode) throw new IOException("Repeated old mode"); state.oldMode = true;
                } else {
                    if (state.newMode) throw new IOException("Repeated new mode"); state.newMode = true;
                }
            } else if (line.startsWith("rename from ") || line.startsWith("copy from ")) {
                if (state == null || state.headers || state.hunks || state.renameOld != null) throw new IOException("Unexpected rename/copy metadata");
                state.renameOld = sourcePath(decode(line.substring(line.indexOf(" from ") + 6)));
            } else if (line.startsWith("rename to ") || line.startsWith("copy to ")) {
                if (state == null || state.headers || state.hunks || state.renameNew != null) throw new IOException("Unexpected rename/copy metadata");
                state.renameNew = sourcePath(decode(line.substring(line.indexOf(" to ") + 4)));
            } else if (!line.isEmpty()) {
                if (state == null || state.headers || state.hunks || !line.matches(
                        "(?:dis)?similarity index [0-9]{1,3}%|index [0-9a-f]+\\.\\.[0-9a-f]+(?: [0-7]{6})?"))
                    throw new IOException("Unexpected diff content at line " + (i + 1));
            }
        }
        if (oldLeft != 0 || newLeft != 0) throw new IOException("Truncated diff hunk");
        if (state != null) state.finish(files, paths);
        return new Data(Json.hash(bytes), List.copyOf(files));
    }

    private static final class State {
        final String gitHeader;
        final List<Line> added = new ArrayList<>();
        String oldPath, newPath, renameOld, renameNew;
        boolean oldHeader, headers, hunks, binary, binaryPayload, deleted, created, oldMode, newMode;
        int oldEnd = 1, newEnd = 1;
        State(String gitHeader) throws IOException {
            // Two 2,048-character paths, at most three UTF-8 octets per UTF-16
            // character, with four characters per octal escape plus prefixes/quotes.
            if (gitHeader.length() > 2 * (2048 * 12 + 4) + 1) throw new IOException("Oversized Git file header");
            this.gitHeader = gitHeader;
        }
        void finish(List<File> files, Set<String> paths) throws IOException {
            if (oldHeader != headers || headers && !hunks) throw new IOException("Incomplete diff file headers or missing hunks");
            if ((renameOld == null) != (renameNew == null)) throw new IOException("Incomplete rename/copy metadata");
            if (oldMode != newMode) throw new IOException("Incomplete file-mode metadata");
            if (!headers && !binary && !created && !deleted && renameOld == null && !oldMode)
                throw new IOException("Diff file has no change content");
            String old = headers ? oldPath == null ? newPath : oldPath : renameOld;
            String current = headers ? newPath == null ? oldPath : newPath : renameNew;
            List<String> names = gitNames(gitHeader, old, current);
            if (renameOld != null && (!renameOld.equals(names.get(0)) || !renameNew.equals(names.get(1))))
                throw new IOException("Conflicting rename/copy paths");
            if (!names.get(0).equals(names.get(1)) && renameOld == null)
                throw new IOException("Changed file paths require rename/copy metadata");
            String path = deleted ? names.get(0) : names.get(1);
            if (!paths.add(path)) throw new IOException("Repeated diff file path");
            if (files.size() >= Input.MAX_FILES) throw new IOException("Diff exceeds 5,000 files");
            files.add(new File(path, List.copyOf(added), binary, deleted));
        }
    }

    private static String headerPath(String raw, String prefix) throws IOException {
        // Git adds a delimiter tab to unquoted names containing spaces.
        if (raw.endsWith("\t")) raw = raw.substring(0, raw.length() - 1);
        String value = decode(raw);
        if (value.equals("/dev/null")) return null;
        if (!value.startsWith(prefix)) throw new IOException("Use Git's default a/ and b/ prefixes");
        return sourcePath(value.substring(2));
    }
    private static String sourcePath(String value) throws IOException {
        // In Git output a decoded backslash is a filename character, not a separator.
        if (value.contains("\\") || value.startsWith("./")) throw new IOException("Backslashes and dot prefixes in Git source paths are unsupported");
        return Input.path(value);
    }
    private static List<String> gitNames(String value, String old, String current) throws IOException {
        int boundary = -1;
        if (value.startsWith("\"")) {
            boolean escaped = false;
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' && !escaped) { boundary = i + 1; break; }
                escaped = c == '\\' && !escaped;
            }
        } else if (old != null) {
            // File headers/rename metadata resolve unquoted names containing " b/".
            boundary = old.length() + 2;
        } else {
            // With no text or rename headers, Git must name the same path twice.
            boundary = (value.length() - 1) / 2;
        }
        if (boundary < 1 || boundary >= value.length() - 1 || value.charAt(boundary) != ' ')
            throw new IOException("Invalid Git file path separator");
        String left = headerPath(value.substring(0, boundary), "a/"), right = headerPath(value.substring(boundary + 1), "b/");
        if (left == null || right == null || (old != null ? !old.equals(left) || !current.equals(right) : !left.equals(right)))
            throw new IOException("Conflicting Git file paths; use default a/ and b/ prefixes");
        return List.of(left, right);
    }
    private static String decode(String value) throws IOException {
        if (!value.startsWith("\"")) return value;
        if (value.length() < 2 || value.charAt(value.length() - 1) != '"') throw new IOException("Invalid quoted path");
        var bytes = new ByteArrayOutputStream();
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '"') throw new IOException("Unescaped quote in path");
            if (c != '\\') {
                int cp = value.codePointAt(i);
                bytes.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp) - 1; continue;
            }
            if (++i >= value.length() - 1) throw new IOException("Invalid path escape");
            c = value.charAt(i);
            if (c >= '0' && c <= '7') {
                if (i + 2 >= value.length() - 1 || !value.substring(i, i + 3).matches("[0-7]{3}")) throw new IOException("Invalid octal escape");
                int octet = Integer.parseInt(value.substring(i, i + 3), 8);
                if (octet > 255) throw new IOException("Invalid UTF-8 octet");
                bytes.write(octet); i += 2;
            } else if (c == '\\' || c == '"') bytes.write(c);
            else throw new IOException("Control-character escapes in source paths are unsupported");
        }
        return Input.text(bytes.toByteArray());
    }
}
