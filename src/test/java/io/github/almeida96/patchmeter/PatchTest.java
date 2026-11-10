package io.github.almeida96.patchmeter;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchTest {
    @TempDir Path temp;
    private static Patch.Data parse(String value) throws IOException { return Patch.parse(value.getBytes(StandardCharsets.UTF_8)); }
    private static String headers() { return "diff --git a/a.java b/a.java\n--- a/a.java\n+++ b/a.java\n"; }

    @Test void mapsReplacementAndContextToCandidateAndPhysicalDiffLines() throws Exception {
        var result = parse(headers() + "@@ -3,3 +3,4 @@ method\n context\n-old\n+new\n+extra\n tail\n");
        assertEquals(List.of(new Patch.Line(4, 7), new Patch.Line(5, 8)), result.files().getFirst().added());
        assertEquals(Json.hash((headers() + "@@ -3,3 +3,4 @@ method\n context\n-old\n+new\n+extra\n tail\n").getBytes(StandardCharsets.UTF_8)), result.sha256());
    }

    @Test void supportsZeroContextInsertionsAndDeletionOnlyHunks() throws Exception {
        var file = parse(headers() + "@@ -1,0 +2 @@\n+insert\n@@ -3 +3,0 @@\n-remove\n@@ -5,0 +6 @@\n+last\n").files().getFirst();
        assertEquals(List.of(2, 6), file.added().stream().map(Patch.Line::number).toList());
    }

    @Test void preservesDeletedAndCreatedFiles() throws Exception {
        String deleted = "diff --git a/gone.txt b/gone.txt\ndeleted file mode 100644\n--- a/gone.txt\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-a\n-b\n";
        String created = "diff --git a/new.txt b/new.txt\nnew file mode 100644\n--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1,2 @@\n+a\n+b\n";
        var result = parse(deleted + created);
        assertTrue(result.files().getFirst().deleted());
        assertTrue(result.files().getFirst().added().isEmpty());
        assertEquals(List.of(1, 2), result.files().get(1).added().stream().map(Patch.Line::number).toList());
    }

    @Test void acceptsNoFinalNewlineMarkersAndSourceThatLooksLikeMetadata() throws Exception {
        var result = parse(headers() + "@@ -1 +1,2 @@\n-before\n\\ No newline at end of file\n+diff --git is source\n+@@@ is also source\n\\ No newline at end of file\n");
        assertEquals(List.of(1, 2), result.files().getFirst().added().stream().map(Patch.Line::number).toList());
    }

    @Test void usesRenamedCandidatePathsWithMixedQuoting() throws Exception {
        var result = parse("diff --git a/old name.java \"b/caf\\303\\251.java\"\n"
                + "similarity index 60%\nrename from old name.java\nrename to \"caf\\303\\251.java\"\n"
                + "--- a/old name.java\t\n+++ \"b/caf\\303\\251.java\"\n@@ -1 +1 @@\n-old\n+new\n");
        assertEquals("caf\u00e9.java", result.files().getFirst().path());
        var reverse = parse("diff --git \"a/caf\\303\\251.java\" b/new name.java\n"
                + "similarity index 100%\nrename from \"caf\\303\\251.java\"\nrename to new name.java\n");
        assertEquals("new name.java", reverse.files().getFirst().path());
        assertTrue(reverse.files().getFirst().added().isEmpty());
    }

    @Test void readsActualGitOutputForSpacesSeparatorTextAndUnicode() throws Exception {
        git("init", "-q"); git("config", "core.autocrlf", "false");
        var names = List.of("a name.txt", "foo b/bar.txt", "caf\u00e9.txt", "rocket\ud83d\ude80.txt");
        for (String name : names) {
            Path file = temp.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, "before\n");
        }
        git("add", ".");
        for (String name : names) Files.writeString(temp.resolve(name), "after\n");
        for (String quote : List.of("true", "false")) {
            byte[] actual = git("-c", "core.quotePath=" + quote, "diff", "--no-ext-diff", "--no-color");
            var result = Patch.parse(actual);
            assertEquals(names.stream().sorted().toList(), result.files().stream().map(Patch.File::path).sorted().toList());
            assertTrue(result.files().stream().allMatch(f -> f.added().size() == 1 && f.added().getFirst().number() == 1));
        }
    }

    @Test void distinguishesBinaryAndModeOnlyChanges() throws Exception {
        var result = parse("diff --git a/img.png b/img.png\nindex aaaa..bbbb 100644\nBinary files a/img.png and b/img.png differ\n"
                + "diff --git a/run.sh b/run.sh\nold mode 100644\nnew mode 100755\n");
        assertTrue(result.files().getFirst().binary());
        assertFalse(result.files().get(1).binary());
        assertTrue(result.files().get(1).added().isEmpty());
    }

    @Test void readsRealGitSeparatedHunksAfterInsertionsAndDeletions() throws Exception {
        git("init", "-q"); git("config", "core.autocrlf", "false");
        var old = new java.util.ArrayList<String>();
        for (int i = 1; i <= 100; i++) old.add("line " + i);
        Files.writeString(temp.resolve("a.txt"), String.join("\n", old) + "\n"); git("add", ".");
        var changed = new java.util.ArrayList<>(old);
        changed.add(1, "first addition"); changed.remove(40); changed.set(80, "replacement"); changed.add("last addition");
        Files.writeString(temp.resolve("a.txt"), String.join("\n", changed) + "\n");
        for (String context : List.of("--unified=0", "--unified=3")) {
            var file = Patch.parse(git("diff", "--no-ext-diff", "--no-color", context)).files().getFirst();
            assertEquals(List.of(2, 81, 101), file.added().stream().map(Patch.Line::number).toList());
        }
    }

    @Test void rejectsTruncatedOverfullAndOverlappingHunks() {
        for (String body : List.of(
                "@@ -1 +1,2 @@\n-old\n+only-one\n",
                "@@ -1 +1 @@\n-old\n+new\n+extra\n",
                "@@ -1 +1 @@\n-a\n+b\n@@ -1 +2 @@\n-c\n+d\n",
                "@@ -1 +1 @@\n-a\n+b\n@@ -2 +1 @@\n-c\n+d\n",
                "@@ -1 +1 @@\n-a\n+b\n@@ -5 +7 @@\n-c\n+d\n",
                "@@ -1,0 +1,0 @@\n")) assertThrows(IOException.class, () -> parse(headers() + body), body);
    }

    @Test void rejectsConflictingPathsAndIncompleteFileHeaders() {
        for (String value : List.of(
                headers().replace("+++ b/a.java", "+++ b/other.java") + "@@ -1 +1 @@\n-a\n+b\n",
                "diff --git a/a.java b/a.java\n+++ b/a.java\n@@ -1 +1 @@\n-a\n+b\n",
                headers(),
                "diff --git a/a.java b/a.java\nindex aaaa..bbbb 100644\n",
                "diff --git a/a.java b/a.java\nold mode 100644\n",
                "diff --git a/a.java b/a.java\n--- a/a.java\n",
                "diff --git a/old.java b/new.java\nrename from old.java\n"))
            assertThrows(IOException.class, () -> parse(value), value);
    }

    @Test void rejectsTraversalAliasesCombinedDiffsAndRepeatedFiles() {
        String ordinary = headers() + "@@ -1 +1 @@\n-a\n+b\n";
        for (String value : List.of(ordinary + ordinary, ordinary.replace("a.java", "../escape.java"),
                ordinary.replace("a.java", "./a.java"), "diff --cc a.java\n@@@ -1 -1 +1 @@@\n",
                "diff --git \"a/foo\\\\bar\" \"b/foo\\\\bar\"\nold mode 100644\nnew mode 100755\n"))
            assertThrows(IOException.class, () -> parse(value), value);
    }

    @Test void rejectsMalformedUtf8AndAllowsAnEmptyDiff() throws Exception {
        assertThrows(IOException.class, () -> Patch.parse(new byte[] {(byte) 0xc3, (byte) 0x28}));
        assertTrue(parse("").files().isEmpty());
        assertThrows(IOException.class, () -> parse("\\ No newline at end of file\n"));
    }

    private byte[] git(String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", temp.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), String.join(" ", command));
        return output;
    }
}
