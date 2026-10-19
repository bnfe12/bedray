package io.github.andrexarlay.vulkancompat.autotest;

/** F3 lines carry legacy formatting codes (section sign + one character); the log gets the plain text. */
final class DebugText {
    private DebugText() {}

    static String plain(String line) {
        StringBuilder b = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length()) {
                i++;
                continue;
            }
            b.append(c);
        }
        return b.toString();
    }
}
