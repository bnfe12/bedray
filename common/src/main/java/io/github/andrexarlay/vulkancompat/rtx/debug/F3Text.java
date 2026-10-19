package io.github.andrexarlay.vulkancompat.rtx.debug;

/**
 * Colour codes and allocation-free number formatting for the Bedray F3 text. Minecraft draws the debug overlay lines with
 * {@code GuiGraphicsExtractor.text(font, String, ..)}, so legacy formatting codes (section sign + one character) work inside the
 * strings (vineflower of {@code DebugScreenOverlay.extractLines}: plate 0x90505050, text 0xFFE0E0E0). The section sign is written as
 * an escape so the file encoding cannot change it.
 * <p>
 * Colour contract (owner request): green = works / normal, yellow = warning / fallback, red = error / unavailable, gray = off / not
 * used, aqua = reference values (fps, ms), real fps white, generated fps aqua, frame time by threshold (&lt;= 8 ms green,
 * &lt;= 16 ms yellow, above red).
 */
final class F3Text {
    static final String OK = "§a";
    static final String WARN = "§e";
    static final String ERR = "§c";
    static final String OFF = "§7";
    static final String VAL = "§b";
    static final String REAL = "§f";
    static final String GEN = "§b";
    static final String GOLD = "§6";
    static final String PURPLE = "§d";
    static final String HEAD = "§6§n";
    static final String R = "§r";

    static final double FRAME_GREEN_MS = 8.0;
    static final double FRAME_YELLOW_MS = 16.0;

    private static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L};

    private F3Text() {}

    /** Colour of a frame time by the owner's thresholds. */
    static String frameColor(double ms) {
        if (!(ms > 0.0)) {
            return OFF;
        }
        return ms <= FRAME_GREEN_MS ? OK : ms <= FRAME_YELLOW_MS ? WARN : ERR;
    }

    /** Colour of a state enum name used by the runtimes: READY is fine, FAILED is an error, anything else is in transition. */
    static String stateColor(String stateName) {
        return "READY".equals(stateName) ? OK : "FAILED".equals(stateName) ? ERR : "IDLE".equals(stateName) ? OFF : WARN;
    }

    /** Percentage of accepted history: high is good. */
    static String historyColor(double pct) {
        return pct >= 80.0 ? OK : pct >= 50.0 ? WARN : ERR;
    }

    /** Appends {@code v} with {@code decimals} (0..6) digits after the point; NaN and infinity print as a dash. */
    static StringBuilder fixed(StringBuilder b, double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return b.append('-');
        }
        if (v < 0.0) {
            b.append('-');
            v = -v;
        }
        long scale = POW10[decimals];
        long scaled = Math.round(v * scale);
        b.append(scaled / scale);
        if (decimals > 0) {
            b.append('.');
            long frac = scaled % scale;
            for (int d = decimals - 1; d >= 0; d--) {
                b.append((char) ('0' + (frac / POW10[d]) % 10));
            }
        }
        return b;
    }

    /** Milliseconds: two decimals below 10, one decimal above. */
    static StringBuilder ms(StringBuilder b, double ms) {
        return fixed(b, ms, ms < 10.0 ? 2 : 1);
    }

    /** {@code value} in millions with two decimals and an M suffix. */
    static StringBuilder mega(StringBuilder b, double value) {
        return fixed(b, value / 1.0e6, 2).append('M');
    }

    /** Appends {@code text} cut to {@code max} characters (an ellipsis dot marks the cut) with line breaks flattened. */
    static StringBuilder cut(StringBuilder b, String text, int max) {
        if (text == null) {
            return b.append('?');
        }
        int n = Math.min(text.length(), max);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            b.append(c == '\n' || c == '\r' ? ' ' : c);
        }
        return text.length() > max ? b.append('.') : b;
    }

    /** {@code color + text + reset}. */
    static StringBuilder colored(StringBuilder b, String color, String text) {
        return b.append(color).append(text).append(R);
    }
}
