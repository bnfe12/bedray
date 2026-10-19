package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Logical paths inside a pack: always relative, '/'-separated, without '.' or '..' segments. Every entry name and every include
 * goes through here, so nothing read from a pack can name a file outside it (zip-slip / path traversal).
 */
public final class PackPaths {
    private PackPaths() {}

    /**
     * Normalizes an archive/directory entry name. Backslashes are treated as separators (zips written on Windows). Rejected:
     * empty, too long/deep, control characters, ':' (drive letters, NTFS streams), absolute paths and ANY '..' segment
     * (a legitimate pack never stores one in an entry name).
     */
    public static String normalizeEntry(String raw, ScanLimits limits) throws PackScanException {
        if (raw == null || raw.isEmpty()) {
            throw new PackScanException("empty entry name");
        }
        if (raw.length() > limits.maxPathLength()) {
            throw new PackScanException("entry name longer than " + limits.maxPathLength() + " chars: " + printable(raw));
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f || c == ':') {
                throw new PackScanException("forbidden character in entry name: " + printable(raw));
            }
        }
        String s = raw.replace('\\', '/');
        if (s.startsWith("/")) {
            throw new PackScanException("absolute entry name: " + printable(raw));
        }
        StringBuilder out = new StringBuilder(s.length());
        int depth = 0;
        for (String seg : s.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                throw new PackScanException("'..' in entry name: " + printable(raw));
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(seg);
            depth++;
        }
        if (out.length() == 0) {
            throw new PackScanException("entry name without a file name: " + printable(raw));
        }
        if (depth > limits.maxDepth()) {
            throw new PackScanException("entry deeper than " + limits.maxDepth() + ": " + printable(raw));
        }
        return out.toString();
    }

    /**
     * Resolves an {@code #include} (or any pack-relative reference) the way Iris does: a leading '/' is relative to
     * {@code shadersRoot}, otherwise relative to the directory of {@code currentFile}. '..' may climb, but never above
     * {@code shadersRoot}. Both inputs are normalized pack paths; {@code shadersRoot} is "" or ends with '/'.
     */
    public static String resolveWithin(String shadersRoot, String currentFile, String reference) throws PackScanException {
        if (reference == null || reference.isBlank()) {
            throw new PackScanException("empty reference in " + currentFile);
        }
        if (!currentFile.startsWith(shadersRoot)) {
            throw new PackScanException(currentFile + " is not under " + shadersRoot);
        }
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c < 0x20 || c == ':' || c == '\\') {
                throw new PackScanException("forbidden character in reference '" + printable(reference) + "' in " + currentFile);
            }
        }
        Deque<String> stack = new ArrayDeque<>();
        if (!reference.startsWith("/")) {
            String rel = currentFile.substring(shadersRoot.length());
            int slash = rel.lastIndexOf('/');
            if (slash > 0) {
                for (String seg : rel.substring(0, slash).split("/")) {
                    stack.addLast(seg);
                }
            }
        }
        for (String seg : reference.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (stack.isEmpty()) {
                    throw new PackScanException("reference '" + reference + "' in " + currentFile + " escapes the shaders root");
                }
                stack.removeLast();
            } else {
                stack.addLast(seg);
            }
        }
        if (stack.isEmpty()) {
            throw new PackScanException("reference '" + reference + "' in " + currentFile + " names no file");
        }
        return shadersRoot + String.join("/", stack);
    }

    static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && i < 160; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
        return sb.toString();
    }
}
