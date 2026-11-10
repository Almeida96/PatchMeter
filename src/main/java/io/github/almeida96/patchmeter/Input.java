package io.github.almeida96.patchmeter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Input limits apply before parsing; paths are labels and are never opened. */
public final class Input {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FILES = 5000;
    public static final int MAX_LINES = 250000;
    private Input() {}
    public static byte[] read(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new IOException("Input exceeds 16 MiB");
            return data;
        }
    }
    public static String text(byte[] data) throws IOException {
        if (data.length > MAX_BYTES) throw new IOException("Input exceeds 16 MiB");
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }
    public static String path(String raw) throws IOException {
        String value = raw.replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        if (value.isBlank() || value.length() > 2048 || value.startsWith("/") || value.matches("^[A-Za-z]:.*")
                || value.chars().anyMatch(Character::isISOControl)) throw new IOException("Expected a relative source path");
        for (String part : value.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IOException("Source path contains an empty or traversal segment");
        return value;
    }
    public static int number(String raw, boolean positive) throws IOException {
        try {
            if (!raw.matches("[0-9]{1,9}")) throw new NumberFormatException();
            int value = Integer.parseInt(raw);
            if (positive && value == 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new IOException("Invalid bounded nonnegative integer"); }
    }
}
