package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessQuality;

import java.util.List;

/**
 * Quality modes per upscaler technology and their resolution scaling ratios (linear, per axis:
 * render size = output size / ratio).
 * <p>
 * Sources of the ratios (project rules, nothing invented):
 * <ul>
 * <li>XeSS 1.3+: {@code local reference} table "Resolution scaling
 *     (fixed)" (lines ~866-880): AA 1.0, Ultra Quality Plus 1.3, Ultra Quality 1.5, Quality 1.7, Balanced 2.0,
 *     Performance 2.3, Ultra Performance 3.0. At runtime the real input size comes from
 *     {@code xessGetOptimalInputResolution}; this table is only for display and tests.</li>
 * <li>FSR 3.1 / 2: Native AA 1.0, Quality 1.5, Balanced 1.7, Performance 2.0, Ultra Performance 3.0: enum
 *     {@code FfxApiUpscaleQualityMode} in {@code ffx-api/include/ffx_api/ffx_upscale.h} l.31-38 and the "Scaling modes" table of
 *     {@code docs/techniques/super-resolution-upscaler.md} (FidelityFX SDK v1.1.4, FSR 3.1.4). There is no "Ultra Quality"
 *     in FSR 2/3.1 (it was an FSR 1.0 mode), so FSR does not offer it. At runtime the render size comes from
 *     {@code ffxQueryDescUpscaleGetRenderResolutionFromQualityMode} ({@code FsrRuntime}).</li>
 * <li>DLSS: no ratios here. The render size of each mode (Quality, Balanced, Performance, Ultra Performance, DLAA) is
 *     queried from NGX with {@code NGX_DLSS_GET_OPTIMAL_SETTINGS} ({@code vkb_dlss_query_optimal_settings}) after init,
 *     as the DLSS Programming Guide requires; {@link #ratio} and {@link #scale} refuse DLSS instead of inventing numbers.</li>
 * <li>FSR 1 (built-in spatial): Ultra Quality 1.3, Quality 1.5, Balanced 1.7, Performance 2.0 per dimension: AMD GPUOpen page
 *     https://gpuopen.com/fidelityfx-superresolution/ ("FSR 1 quality modes" table, read 2026-10-12). Not in the local SDK.</li>
 * <li>NIS (built-in spatial): NVIDIA publishes no mode table (NIS SDK v1.0.3 README and Streamline ProgrammingGuideNIS.md list
 *     none), so only {@link UpscalerMode#CUSTOM} is offered; NIS accepts 50%..100% ({@code NVScalerUpdateConfig}).</li>
 * <li>{@link UpscalerMode#CUSTOM}: any technology; the ratio is the user's percent, not a table value.</li>
 * </ul>
 */
public final class UpscalerModes {
    /** How trustworthy a ratio is. */
    public enum Source { SDK_DOC, RESEARCH_NOTE, UNVERIFIED, RUNTIME_QUERY, USER }

    /** Range of the custom render scale slider in percent. NIS raises the minimum to 50 (NisConstants). */
    public static final int CUSTOM_MIN_PERCENT = 25;
    public static final int CUSTOM_MAX_PERCENT = 100;

    private UpscalerModes() {}

    /** Modes offered in the UI for the technology, best quality first. {@code AUTO} is a STUB and never listed. */
    public static List<UpscalerMode> modesFor(UpscalerBackend backend) {
        return switch (backend) {
            case BILINEAR -> List.of();
            case DLSS -> List.of(UpscalerMode.DLAA, UpscalerMode.QUALITY, UpscalerMode.BALANCED,
                UpscalerMode.PERFORMANCE, UpscalerMode.ULTRA_PERFORMANCE, UpscalerMode.CUSTOM);
            case FSR -> List.of(UpscalerMode.NATIVE_AA, UpscalerMode.QUALITY,
                UpscalerMode.BALANCED, UpscalerMode.PERFORMANCE, UpscalerMode.ULTRA_PERFORMANCE, UpscalerMode.CUSTOM);
            case XESS -> List.of(UpscalerMode.ULTRA_QUALITY_PLUS, UpscalerMode.ULTRA_QUALITY, UpscalerMode.QUALITY,
                UpscalerMode.BALANCED, UpscalerMode.PERFORMANCE, UpscalerMode.ULTRA_PERFORMANCE, UpscalerMode.CUSTOM);
            case FSR1 -> List.of(UpscalerMode.ULTRA_QUALITY, UpscalerMode.QUALITY, UpscalerMode.BALANCED,
                UpscalerMode.PERFORMANCE, UpscalerMode.CUSTOM);
            case NIS -> List.of(UpscalerMode.CUSTOM);
        };
    }

    public static boolean isOffered(UpscalerBackend backend, UpscalerMode mode) {
        return modesFor(backend).contains(mode);
    }

    /** Default mode of the technology (always a member of {@link #modesFor}); BILINEAR has none (null). */
    public static UpscalerMode defaultMode(UpscalerBackend backend) {
        return switch (backend) {
            case BILINEAR -> null;
            case NIS -> UpscalerMode.CUSTOM;
            default -> UpscalerMode.QUALITY;
        };
    }

    /** Lowest custom percent the technology accepts (NIS: 50, NVScalerUpdateConfig; others: {@link #CUSTOM_MIN_PERCENT}). */
    public static int customMinPercent(UpscalerBackend backend) {
        return backend == UpscalerBackend.NIS ? 50 : CUSTOM_MIN_PERCENT;
    }

    /** Clamps a stored custom percent into the range of the technology. */
    public static int clampCustomPercent(UpscalerBackend backend, int percent) {
        return Math.max(customMinPercent(backend), Math.min(CUSTOM_MAX_PERCENT, percent));
    }

    /**
     * Linear ratio (output / render) of the mode.
     *
     * @throws IllegalArgumentException when the technology does not offer the mode, for DLSS (runtime query) and for CUSTOM
     *                                  (the user's percent, see {@link #customRatio})
     */
    public static double ratio(UpscalerBackend backend, UpscalerMode mode) {
        if (!isOffered(backend, mode)) {
            throw new IllegalArgumentException(backend + " has no mode " + mode);
        }
        if (mode == UpscalerMode.CUSTOM) {
            throw new IllegalArgumentException("CUSTOM has no table ratio; it is the user's percent (customRatio)");
        }
        return switch (backend) {
            case DLSS -> throw new IllegalArgumentException("DLSS ratios are queried from NGX at runtime (NGX_DLSS_GET_OPTIMAL_SETTINGS), there is no table");
            case XESS -> switch (mode) {
                case ULTRA_QUALITY_PLUS -> 1.3;
                case ULTRA_QUALITY -> 1.5;
                case QUALITY -> 1.7;
                case BALANCED -> 2.0;
                case PERFORMANCE -> 2.3;
                case ULTRA_PERFORMANCE -> 3.0;
                default -> throw new IllegalStateException(mode.name());
            };
            case FSR -> FsrQuality.fromMode(mode).ratio();
            case FSR1 -> switch (mode) {
                case ULTRA_QUALITY -> 1.3;
                case QUALITY -> 1.5;
                case BALANCED -> 1.7;
                case PERFORMANCE -> 2.0;
                default -> throw new IllegalStateException(mode.name());
            };
            case NIS, BILINEAR -> throw new IllegalStateException("unreachable");
        };
    }

    /** Ratio of a custom percent: 100 / percent. */
    public static double customRatio(int percent) {
        if (percent <= 0 || percent > 100) {
            throw new IllegalArgumentException("custom percent must be 1..100: " + percent);
        }
        return 100.0 / percent;
    }

    /** Render scale (render size / output size) = 1 / ratio. */
    public static double scale(UpscalerBackend backend, UpscalerMode mode) {
        return 1.0 / ratio(backend, mode);
    }

    public static Source source(UpscalerBackend backend, UpscalerMode mode) {
        if (!isOffered(backend, mode)) {
            throw new IllegalArgumentException(backend + " has no mode " + mode);
        }
        if (mode == UpscalerMode.CUSTOM) {
            return Source.USER;
        }
        return switch (backend) {
            case XESS, FSR, FSR1 -> Source.SDK_DOC;
            case DLSS -> Source.RUNTIME_QUERY;
            case NIS, BILINEAR -> throw new IllegalStateException("unreachable");
        };
    }

    /** The XeSS library's name for the mode ({@link XessQuality}); only for modes XeSS offers (not CUSTOM). */
    public static XessQuality toXessQuality(UpscalerMode mode) {
        if (!isOffered(UpscalerBackend.XESS, mode) || mode == UpscalerMode.CUSTOM) {
            throw new IllegalArgumentException("XeSS has no library mode " + mode);
        }
        return XessQuality.parse(mode.name());
    }
}