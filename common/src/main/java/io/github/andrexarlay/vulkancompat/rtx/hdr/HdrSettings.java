package io.github.andrexarlay.vulkancompat.rtx.hdr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

/**
 * User settings of HDR output, persisted in the {@code [hdr]} table of {@code config/bedray.toml}
 * (written by {@code VulkanRtxConfig.save}, read by {@code VulkanRtxConfig.applyKeyValue}; UTF-8 without BOM).
 * <pre>
 * [hdr]
 * hdr_output = "OFF"      # OFF or HDR10 (restart needed: the engine swapchain is created once at startup)
 * hdr_paper_white = 200   # nits of SDR white, 80..500 (live)
 * hdr_peak = 1000         # nits the picture may reach, 400..4000 (live)
 * </pre>
 * {@code -Dbedray.hdr=off|hdr10} overrides {@code hdr_output} for the whole session (autotests).
 */
public final class HdrSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/HDR");
    public static final String PROPERTY = "bedray.hdr";

    public enum Output { OFF, HDR10 }

    public static final int PAPER_WHITE_MIN = 80;
    public static final int PAPER_WHITE_MAX = 500;
    public static final int PEAK_MIN = 400;
    public static final int PEAK_MAX = 4000;

    private static volatile Output output = Output.OFF;
    private static volatile int paperWhite = 200;
    private static volatile int peak = 1000;
    private static final Output PROPERTY_OUTPUT = parseOutput(System.getProperty(PROPERTY));

    private HdrSettings() {}

    /** off|false|0 -> OFF; hdr10|on|true|1 -> HDR10; blank -> null; unknown -> null (logged). */
    public static Output parseOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace("\"", "")) {
            case "off", "false", "0", "sdr" -> Output.OFF;
            case "hdr10", "on", "true", "1", "hdr" -> Output.HDR10;
            default -> {
                LOGGER.warn("[VKB-HDR] unknown hdr_output '{}' (off, hdr10)", raw);
                yield null;
            }
        };
    }

    /** Requested output (session property wins over the toml value). Changing it needs a restart. */
    public static Output output() {
        return PROPERTY_OUTPUT != null ? PROPERTY_OUTPUT : output;
    }

    public static boolean outputForcedByProperty() {
        return PROPERTY_OUTPUT != null;
    }

    public static void setOutput(Output o) {
        output = o == null ? Output.OFF : o;
    }

    /** Output the toml asked for, ignoring the session property (what the settings screen edits). */
    public static Output savedOutput() {
        return output;
    }

    public static int paperWhiteNits() {
        return paperWhite;
    }

    public static void setPaperWhiteNits(int nits) {
        paperWhite = Math.max(PAPER_WHITE_MIN, Math.min(PAPER_WHITE_MAX, nits));
    }

    public static int peakNits() {
        return Math.max(peak, paperWhite);
    }

    public static void setPeakNits(int nits) {
        peak = Math.max(PEAK_MIN, Math.min(PEAK_MAX, nits));
    }

    /** True when the saved/forced setting asks for an HDR10 swapchain (read when the surface is created). */
    public static boolean wantsHdr10() {
        // The config class loads bedray.toml in its static initialiser; make sure that happened before the first read.
        io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig.ensureLoaded();
        return output() == Output.HDR10;
    }

    public static void applyKeyValue(String key, String val) {
        try {
            switch (key) {
                case "hdr_output" -> {
                    Output o = parseOutput(val);
                    if (o != null) {
                        output = o;
                    }
                }
                case "hdr_paper_white" -> setPaperWhiteNits(Integer.parseInt(val.trim()));
                case "hdr_peak" -> setPeakNits(Integer.parseInt(val.trim()));
                default -> {
                }
            }
        } catch (NumberFormatException e) {
            LOGGER.warn("[VKB-HDR] invalid {} = '{}' in bedray.toml, keeping the previous value", key, val);
        }
    }

    public static void write(Writer w) throws IOException {
        w.write("\n[hdr]\n");
        w.write("# HDR10 output (PQ, BT.2020) needs Windows HDR switched on for the monitor, no frame generation, and a game restart.\n");
        w.write("hdr_output = \"" + output.name() + "\"\n");
        w.write("# Nits of SDR white and the interface (80..500) and of the brightest highlight (400..4000); both change live.\n");
        w.write("hdr_paper_white = " + paperWhite + "\n");
        w.write("hdr_peak = " + peak + "\n");
    }
}
