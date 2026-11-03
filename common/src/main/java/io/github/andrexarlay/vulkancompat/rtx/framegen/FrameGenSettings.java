package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * User settings of frame generation, persisted in the {@code [frame_generation]} table of {@code config/bedray.toml}
 * (written by {@code VulkanRtxConfig.save}, read by {@code VulkanRtxConfig.applyKeyValue}; UTF-8 without BOM).
 * <pre>
 * [frame_generation]
 * framegen_mode = "OFF"        # OFF, X2, X3, X4 (X3/X4 = DLSS Multi Frame Generation, only when the driver reports it)
 * reflex_mode = "ON"           # OFF, ON, BOOST
 * framegen_frame_limit = 0     # presented frames per second cap through Reflex (0 = off)
 * </pre>
 * The JVM property {@code -Dvkrtx.framegen=off|2x|3x|4x} (also {@code dlss}/{@code on} = 2x) overrides {@code framegen_mode} for
 * the whole session (autotests). Loading Streamline needs a restart: the interposer has to be the Vulkan library before the
 * first instance exists ({@link StreamlineBootstrap}); the mode itself, Reflex and the limit change at runtime.
 */
public final class FrameGenSettings {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FrameGenSettings");
    public static final String PROPERTY = "vkrtx.framegen";

    /** Requested multiplier: {@link #frames()} real + generated frames per rendered frame. */
    public enum Mode {
        OFF(1), X2(2), X3(3), X4(4);

        private final int multiplier;

        Mode(int multiplier) {
            this.multiplier = multiplier;
        }

        public int multiplier() {
            return multiplier;
        }

        /** {@code numFramesToGenerate} of sl::DLSSGOptions. */
        public int generated() {
            return multiplier - 1;
        }

        public String label() {
            return this == OFF ? "Off" : multiplier + "x";
        }
    }

    /** sl::ReflexMode. */
    public enum Reflex {
        OFF, ON, BOOST;

        public int slValue() {
            return ordinal();
        }
    }

    /** Which implementation generates frames. AUTO = NVIDIA DLSS-G when the NVIDIA driver and Streamline files are present, otherwise AMD FSR 3. */
    public enum Provider {
        AUTO, NVIDIA, AMD;

        public String label() {
            return switch (this) {
                case AUTO -> "Auto";
                case NVIDIA -> "NVIDIA DLSS-G";
                case AMD -> "AMD FSR 3";
            };
        }
    }

    public static final String PROVIDER_PROPERTY = "vkrtx.framegen.provider";
    private static volatile Provider provider = Provider.AUTO;
    private static final Provider PROPERTY_PROVIDER = parseProvider(System.getProperty(PROVIDER_PROPERTY));

    /** off|auto -> AUTO; nvidia|dlss|dlssg|streamline -> NVIDIA; amd|fsr|fsr3 -> AMD; unknown -> null (logged). */
    public static Provider parseProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace("\"", "")) {
            case "auto" -> Provider.AUTO;
            case "nvidia", "dlss", "dlssg", "dlss-g", "streamline" -> Provider.NVIDIA;
            case "amd", "fsr", "fsr3", "fsr 3" -> Provider.AMD;
            default -> {
                LOGGER.warn("[VKB-FG] unknown frame generation provider '{}' (auto, nvidia, amd)", raw);
                yield null;
            }
        };
    }

    /** Requested provider (session property wins over the toml value). */
    public static Provider provider() {
        return PROPERTY_PROVIDER != null ? PROPERTY_PROVIDER : provider;
    }

    public static void setProvider(Provider p) {
        provider = p;
    }

    private static volatile Mode mode = Mode.OFF;
    private static volatile Reflex reflex = Reflex.ON;
    private static volatile int frameLimitFps = 0;
    private static final Mode PROPERTY_MODE = parseMode(System.getProperty(PROPERTY));

    private FrameGenSettings() {}

    /** Parses off|2x|3x|4x|x2..x4|dlss|on|true; null/blank/unknown -> null (unknown is logged). */
    public static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "off", "false", "0", "1x" -> Mode.OFF;
            case "2x", "x2", "dlss", "on", "true" -> Mode.X2;
            case "3x", "x3" -> Mode.X3;
            case "4x", "x4" -> Mode.X4;
            default -> {
                LOGGER.warn("[VKB-FG] unknown frame generation mode '{}' (off, 2x, 3x, 4x)", raw);
                yield null;
            }
        };
    }

    /** True when the session property forces the mode (the settings UI then shows it as fixed). */
    public static boolean modeForcedByProperty() {
        return PROPERTY_MODE != null;
    }

    public static Mode mode() {
        return PROPERTY_MODE != null && !uiOverride ? PROPERTY_MODE : mode;
    }

    /** Set once the user (UI / scenario) changed the mode after startup: then the property no longer wins. */
    private static volatile boolean uiOverride;

    public static void setMode(Mode m) {
        mode = m;
        uiOverride = true;
    }

    public static Reflex reflex() {
        return reflex;
    }

    public static void setReflex(Reflex r) {
        reflex = r;
    }

    public static int frameLimitFps() {
        return frameLimitFps;
    }

    public static void setFrameLimitFps(int fps) {
        frameLimitFps = Math.max(0, Math.min(1000, fps));
    }

    /** Mode wanted at startup (decides whether Streamline is loaded at all): property, otherwise the toml value. */
    public static Mode startupMode() {
        return PROPERTY_MODE != null ? PROPERTY_MODE : mode;
    }

    public static void applyKeyValue(String key, String val) {
        try {
            switch (key) {
                case "framegen_mode" -> {
                    Mode m = parseMode(val);
                    if (m != null) {
                        mode = m;
                    }
                }
                case "framegen_provider" -> {
                    Provider p = parseProvider(val);
                    if (p != null) {
                        provider = p;
                    }
                }
                case "reflex_mode" -> reflex = Reflex.valueOf(val.trim().toUpperCase(Locale.ROOT));
                case "framegen_frame_limit" -> setFrameLimitFps(Integer.parseInt(val.trim()));
                default -> {
                }
            }
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[VKB-FG] invalid {} = '{}' in bedray.toml, keeping the previous value", key, val);
        }
    }

    public static void write(Writer w) throws IOException {
        w.write("\n[frame_generation]\n");
        w.write("# NVIDIA DLSS Frame Generation through Streamline (needs the Streamline binaries, see docs/framegen/FINDINGS.md, an RTX 40/50 GPU,\n");
        w.write("# a temporal upscaler (FSR/DLSS/XeSS) and a game restart to load Streamline; the mode below switches live).\n");
        w.write("# framegen_mode: OFF, X2, X3, X4 (X3/X4 = Multi Frame Generation, RTX 50 only). JVM property -Dvkrtx.framegen=off|2x|3x|4x overrides it.\n");
        w.write("framegen_mode = \"" + mode.name() + "\"\n");
        w.write("# framegen_provider: AUTO, NVIDIA (DLSS-G through Streamline), AMD (FSR 3 frame generation, any GPU, 2x only; not tested on AMD/Intel hardware).\n");
        w.write("# Changing the provider needs a game restart (both replace the swapchain). JVM property -Dvkrtx.framegen.provider overrides it.\n");
        w.write("framegen_provider = \"" + provider.name() + "\"\n");
        w.write("# reflex_mode: OFF, ON, BOOST (NVIDIA Reflex low latency; frame generation needs the Reflex markers in every case)\n");
        w.write("reflex_mode = \"" + reflex.name() + "\"\n");
        w.write("# presented frames per second cap through Reflex, 0 = off\n");
        w.write("framegen_frame_limit = " + frameLimitFps + "\n");
    }

    /** Reads only {@code framegen_provider} from the toml (mixin plugin time). */
    public static Provider peekProviderFromFile(Path toml) {
        try {
            if (!Files.isRegularFile(toml)) {
                return null;
            }
            for (String line : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("framegen_provider")) {
                    int eq = t.indexOf('=');
                    if (eq > 0) {
                        return parseProvider(t.substring(eq + 1).trim().replace("\"", ""));
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[VKB-FG] cannot read {}: {}", LogPaths.show(toml), LogPaths.show(e));
        }
        return null;
    }

    /** Reads only {@code framegen_mode} from the toml before the game classes exist (used by the mixin plugin, see FrameGenEarly). */
    public static Mode peekModeFromFile(Path toml) {
        try {
            if (!Files.isRegularFile(toml)) {
                return null;
            }
            List<String> lines = Files.readAllLines(toml, StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("framegen_mode")) {
                    int eq = t.indexOf('=');
                    if (eq > 0) {
                        return parseMode(t.substring(eq + 1).trim().replace("\"", ""));
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[VKB-FG] cannot read {}: {}", LogPaths.show(toml), LogPaths.show(e));
        }
        return null;
    }
}
