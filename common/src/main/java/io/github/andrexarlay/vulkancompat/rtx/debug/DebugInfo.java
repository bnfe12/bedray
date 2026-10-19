package io.github.andrexarlay.vulkancompat.rtx.debug;

import io.github.andrexarlay.vulkancompat.rt.sodium.RtCaptureControl;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;
import io.github.andrexarlay.vulkancompat.rtx.debug.PerfMonitor.Segment;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Data hub of the F3 screen and the periodic {@code [VKB-PERF]} log line. The F3 text itself is built by {@link F3Sections}
 * (one cached list per section, rebuilt only while its entry is on screen); this class keeps what the sections read from other
 * threads of control (the world phase noted every frame by MixinGameRenderer), the extra {@link DebugLineProvider}s of other
 * modules (shown in the "diag" section) and the key=value perf text. Render thread only.
 * <p>
 * Periodic log: {@code -Dvkrtx.perf.logSec=<n>} (default 30, 0 = off) writes one {@code [VKB-PERF]} key=value line every n
 * seconds, so a user's latest.log carries the same numbers as the F3 screen. The text is built only when it is logged or asked
 * for ({@link #lastPerfLine()}), never on every PerfMonitor window.
 */
public final class DebugInfo {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-PERF");
    private static final long LOG_INTERVAL_NS = Math.max(0L, Long.getLong("vkrtx.perf.logSec", 30L)) * 1_000_000_000L;
    private static final long PERF_TEXT_TTL_NS = PerfMonitor.WINDOW_NS;

    // world phase of the last frame (primitives, written every frame by MixinGameRenderer)
    private static boolean worldPhase;
    private static int renderW;
    private static int renderH;
    private static int outW;
    private static int outH;

    private static long lastLogNs;
    private static long perfTextNs;
    private static String perfText = "";

    private static final java.util.concurrent.CopyOnWriteArrayList<DebugLineProvider> PROVIDERS =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    static {
        register(io.github.andrexarlay.vulkancompat.rt.world.RtModesDebug.provider());
    }

    private DebugInfo() {}

    /**
     * Adds (or replaces, same id) a provider of extra F3 lines; they appear in the "diag" section ({@code bedray_6_diag}, off by
     * default). See {@link DebugLineProvider}.
     */
    public static void register(DebugLineProvider provider) {
        PROVIDERS.removeIf(p -> p.id().equals(provider.id()));
        PROVIDERS.add(provider);
    }

    /** Small factory for lambda providers. */
    public static DebugLineProvider provider(String id, java.util.function.Consumer<List<String>> body) {
        return new DebugLineProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void appendLines(List<String> out) {
                body.accept(out);
            }
        };
    }

    /** After beginWorldPhase: whether the world went into our reduced/isolated target, and its size vs the window. */
    public static void noteWorldPhase(boolean inWorldPhase, int worldW, int worldH, int winW, int winH) {
        worldPhase = inWorldPhase;
        renderW = worldW;
        renderH = worldH;
        outW = winW;
        outH = winH;
    }

    static boolean worldPhase() {
        return worldPhase;
    }

    static int renderW() {
        return renderW;
    }

    static int renderH() {
        return renderH;
    }

    static int outW() {
        return outW;
    }

    static int outH() {
        return outH;
    }

    /** Appends the lines of all registered providers (diag section). */
    static void providerLines(List<String> out) {
        out.add(F3Text.HEAD + "Bedray diagnostics" + F3Text.R);
        for (DebugLineProvider p : PROVIDERS) {
            p.appendLines(out);
        }
    }

    /** All F3 lines of all sections, rebuilt now (autotest scenarios; the overlay uses the per-section caches). */
    public static List<String> lines() {
        return F3Sections.allLinesFresh();
    }

    /** The {@code [VKB-PERF]} key=value text of the last 500 ms or so (for autotest scenarios). */
    public static String lastPerfLine() {
        long now = System.nanoTime();
        if (perfText.isEmpty() || now - perfTextNs >= PERF_TEXT_TTL_NS) {
            perfText = perfKeyValues();
            perfTextNs = now;
        }
        return perfText;
    }

    /** From PerfMonitor at the end of each window: only the periodic log is due here, no text is built otherwise. */
    static void onPerfPublished() {
        PerfHistory.sample();
        if (LOG_INTERVAL_NS > 0) {
            long now = System.nanoTime();
            if (now - lastLogNs >= LOG_INTERVAL_NS) {
                lastLogNs = now;
                LOGGER.info("[VKB-PERF] {}", lastPerfLine());
            }
        }
    }

    /** One line of key=value pairs with the same numbers (log and autotest). */
    private static String perfKeyValues() {
        UpscalerRequest.Request req = UpscalerRequest.current();
        UpscalerBackend active = TemporalUpscaler.active();
        StringBuilder b = new StringBuilder(256);
        b.append("fps=").append(fmt(PerfMonitor.fps()))
            .append(" frame_ms=").append(fmt(PerfMonitor.frameIntervalMs()))
            .append(" bound=").append(F3Sections.limitedBy() != null ? "LIMIT" + F3Sections.limitedBy() : PerfMonitor.boundVerdict())
            .append(" gpu_busy_pct=").append(fmt(PerfMonitor.gpuBusyPercent()))
            .append(" upscaler_request=").append(req)
            .append(" upscaler_active=").append(active)
            .append(" render=").append(worldPhase ? renderW + "x" + renderH : "native")
            .append(" output=").append(outW).append('x').append(outH)
            .append(" rt=").append(RtGeometryGate.enabled() ? "on" : "off:" + RtGeometryGate.blockReason())
            .append(" rt_sections=").append(SectionGeometryRegistry.INSTANCE.sectionCount())
            .append(" rt_rebuild_requests=").append(RtCaptureControl.rebuildRequests());
        for (Segment s : Segment.values()) {
            String n = s.name().toLowerCase(Locale.ROOT);
            b.append(" gpu_").append(n).append('=').append(PerfMonitor.gpuValid(s) ? fmt(PerfMonitor.gpuMs(s)) : "-");
        }
        for (Segment s : Segment.values()) {
            String n = s.name().toLowerCase(Locale.ROOT);
            b.append(" cpu_").append(n).append('=').append(PerfMonitor.cpuValid(s) ? fmt(PerfMonitor.cpuMs(s)) : "-");
        }
        return b.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
