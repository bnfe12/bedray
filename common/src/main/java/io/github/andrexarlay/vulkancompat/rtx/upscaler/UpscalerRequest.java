package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRequest;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessQuality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The upscaler the user wants right now (backend plus quality mode, for FSR also the provider version). It starts as the
 * pre-device request ({@link XessConfig#requestedQuality()}, {@link DlssConfig#requestedQuality()},
 * {@link FsrConfig#requestedRequest()}: -Dvkbridge.xess/-Dvkbridge.dlss/-Dvkrtx.fsr or the toml read before the device
 * exists) and is changed at runtime by the settings page or an autotest scenario through {@link #set}. The runtimes compare
 * it with their active context at the head of every frame and reconfigure (see {@link UpscalerReconfigure}).
 * <p>
 * Only the mode of the selected backend is part of the request, so changing the XeSS mode while DLSS is selected is not a
 * reconfiguration. Thread-safe; no Minecraft classes (unit-tested).
 */
public final class UpscalerRequest {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/UpscalerRequest");

    /**
     * Normalised request: the modes of the other technologies are always null. {@code customPercent} is the user render scale
     * (1..100) when the selected mode is {@link UpscalerMode#CUSTOM}, otherwise 0. {@code spatialMode} is the FSR1/NIS mode
     * (null for other backends).
     */
    public record Request(UpscalerBackend backend, XessQuality xess, DlssQuality dlss, FsrRequest fsr, int customPercent,
                          UpscalerMode spatialMode) {
        public Request(UpscalerBackend backend, XessQuality xess, DlssQuality dlss, FsrRequest fsr) {
            this(backend, xess, dlss, fsr, 0, null);
        }

        /** XeSS mode when XeSS is the selected backend, otherwise null. */
        public XessQuality xessQuality() {
            return backend == UpscalerBackend.XESS ? xess : null;
        }

        /** DLSS mode when DLSS is the selected backend, otherwise null. */
        public DlssQuality dlssQuality() {
            return backend == UpscalerBackend.DLSS ? dlss : null;
        }

        /** FSR mode + version when FSR is the selected backend, otherwise null. */
        public FsrRequest fsrRequest() {
            return backend == UpscalerBackend.FSR ? fsr : null;
        }

        /** Custom render percent of {@code tech} when it is the selected backend in CUSTOM mode, otherwise 0. */
        public int customPercentFor(UpscalerBackend tech) {
            return backend == tech ? customPercent : 0;
        }

        @Override
        public String toString() {
            String custom = customPercent > 0 ? "/custom=" + customPercent + "%" : "";
            return switch (backend) {
                case XESS -> "XESS/" + (customPercent > 0 ? "CUSTOM(base=" + xess + ")" : xess) + custom;
                case DLSS -> "DLSS/" + (customPercent > 0 ? "CUSTOM" : dlss) + custom;
                case FSR -> "FSR/" + (customPercent > 0 ? "CUSTOM@" + fsr.version() : fsr) + custom;
                case FSR1, NIS -> backend.name() + "/" + spatialMode + custom;
                default -> backend.name();
            };
        }
    }
    private static Request current;

    private UpscalerRequest() {}

    /** The current request; on first use it is built from the pre-device requests. */
    public static synchronized Request current() {
        if (current == null) {
            current = fromEarly(XessConfig.requestedQuality(), DlssConfig.requestedQuality(), FsrConfig.requestedRequest());
            LOGGER.info("[VKB-AUTOTEST] upscaler_request initial={}", current);
        }
        return current;
    }

    /** XeSS wins over DLSS over FSR (mutually exclusive, same rule as {@link DlssConfig} / {@link FsrConfig}); none is BILINEAR. */
    static Request fromEarly(XessQuality xess, DlssQuality dlss, FsrRequest fsr) {
        if (xess != null) {
            return new Request(UpscalerBackend.XESS, xess, null, null);
        }
        if (dlss != null) {
            return new Request(UpscalerBackend.DLSS, null, dlss, null);
        }
        if (fsr != null) {
            return new Request(UpscalerBackend.FSR, null, null, fsr);
        }
        return new Request(UpscalerBackend.BILINEAR, null, null, null);
    }

    /** Builds a normalised request from UI values; modes of the non-selected technology are ignored. */
    public static Request build(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode) {
        return build(backend, xessMode, dlssMode, null, null);
    }

    /** As {@link #build(UpscalerBackend, UpscalerMode, UpscalerMode)} plus FSR mode and version (required when backend is FSR). */
    public static Request build(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode,
                                UpscalerMode fsrMode, FsrVersion fsrVersion) {
        return build(backend, xessMode, dlssMode, fsrMode, fsrVersion, null, 0);
    }

    /**
     * Full form. {@code spatialMode}: FSR1 / NIS mode. {@code customPercent}: used only when the mode of the selected backend
     * is {@link UpscalerMode#CUSTOM} (clamped to the technology's range, {@link UpscalerModes#clampCustomPercent}).
     * <p>
     * CUSTOM for the temporal backends is mapped to a vendor preset that is only used to open the dynamic range; the render
     * size is the custom one:
     * <ul>
     * <li>XeSS: ULTRA_PERFORMANCE. XeSS SDK 3.0.2 developer guide, "Dynamic Input Resolution" (lines ~913-955): any input in
     *     [pInputResolutionMin; pInputResolutionMax] of {@code xessGetOptimalInputResolution}; Ultra Performance has the widest
     *     range (1.0x-3.0x).</li>
     * <li>DLSS: placeholder QUALITY; {@code DlssRuntime} asks NGX for the optimal settings of the modes and creates the
     *     feature with the mode whose [min, max] render range contains the custom size (no ratio table is assumed).</li>
     * <li>FSR 3.1/2: placeholder QUALITY; {@code FsrRuntime} creates the context with maxRenderSize = the custom size (the ffx
     *     upscaler accepts any render size up to maxRenderSize per dispatch, ffx_upscale.h).</li>
     * </ul>
     */
    public static Request build(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode,
                                UpscalerMode fsrMode, FsrVersion fsrVersion, UpscalerMode spatialMode, int customPercent) {
        Objects.requireNonNull(backend, "backend");
        return switch (backend) {
            case BILINEAR -> new Request(UpscalerBackend.BILINEAR, null, null, null);
            case XESS -> {
                UpscalerMode m = Objects.requireNonNull(xessMode, "xessMode");
                yield m == UpscalerMode.CUSTOM
                    ? new Request(backend, XessQuality.ULTRA_PERFORMANCE, null, null, pct(backend, customPercent), null)
                    : new Request(backend, UpscalerModes.toXessQuality(m), null, null);
            }
            case DLSS -> {
                UpscalerMode m = Objects.requireNonNull(dlssMode, "dlssMode");
                yield m == UpscalerMode.CUSTOM
                    ? new Request(backend, null, DlssQuality.fromMode(UpscalerMode.QUALITY), null, pct(backend, customPercent), null)
                    : new Request(backend, null, DlssQuality.fromMode(m), null);
            }
            case FSR -> {
                UpscalerMode m = Objects.requireNonNull(fsrMode, "fsrMode");
                FsrVersion v = Objects.requireNonNull(fsrVersion, "fsrVersion");
                yield m == UpscalerMode.CUSTOM
                    ? new Request(backend, null, null, new FsrRequest(FsrQuality.QUALITY, v), pct(backend, customPercent), null)
                    : new Request(backend, null, null, new FsrRequest(FsrQuality.fromMode(m), v));
            }
            case FSR1, NIS -> {
                UpscalerMode m = spatialMode == null ? UpscalerModes.defaultMode(backend) : spatialMode;
                if (!UpscalerModes.isOffered(backend, m)) {
                    throw new IllegalArgumentException(backend + " has no mode " + m);
                }
                yield new Request(backend, null, null, null, m == UpscalerMode.CUSTOM ? pct(backend, customPercent) : 0, m);
            }
        };
    }

    private static int pct(UpscalerBackend backend, int customPercent) {
        if (customPercent <= 0) {
            throw new IllegalArgumentException("CUSTOM mode of " + backend + " needs a custom percent > 0, got " + customPercent);
        }
        return UpscalerModes.clampCustomPercent(backend, customPercent);
    }
    /** Sets the wanted upscaler without FSR values (FSR cannot be selected through this overload). */
    public static synchronized boolean set(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode, String reason) {
        return set(backend, xessMode, dlssMode, null, null, reason);
    }

    /**
     * Sets the wanted upscaler. Logs {@code [VKB-AUTOTEST] upscaler_reconfigure from=.. to=.. reason=.. phase=requested}
     * when it differs from the current one; the runtimes apply it at the head of the next frame.
     *
     * @return true when the request changed
     */
    public static synchronized boolean set(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode,
                                           UpscalerMode fsrMode, FsrVersion fsrVersion, String reason) {
        return set(backend, xessMode, dlssMode, fsrMode, fsrVersion, null, 0, reason);
    }

    /** Full form of {@link #set}: plus the FSR1/NIS mode and the custom percent (see {@link #build} with 7 arguments). */
    public static synchronized boolean set(UpscalerBackend backend, UpscalerMode xessMode, UpscalerMode dlssMode,
                                           UpscalerMode fsrMode, FsrVersion fsrVersion, UpscalerMode spatialMode,
                                           int customPercent, String reason) {
        Request before = current();
        Request after = build(backend, xessMode, dlssMode, fsrMode, fsrVersion, spatialMode, customPercent);
        if (before.equals(after)) {
            return false;
        }
        current = after;
        LOGGER.info("[VKB-AUTOTEST] upscaler_reconfigure from={} to={} reason={} phase=requested", before, after, reason);
        return true;
    }

    /** Test hook: forget the request (it is rebuilt from the early requests on next use). */
    public static synchronized void resetForTests() {
        current = null;
    }
}
