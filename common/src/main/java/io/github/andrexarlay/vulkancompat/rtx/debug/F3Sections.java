package io.github.andrexarlay.vulkancompat.rtx.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.sodium.SectionGeometryRegistry;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rt.world.RtWorld;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.debug.PerfMonitor.Segment;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.RtActivation;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerLibraryLoader;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial.SpatialUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessNative;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.andrexarlay.vulkancompat.rtx.debug.F3Text.*;

/**
 * The text of the Bedray F3 screen, split into sections. Every section is its own debug-screen entry (see {@link F3Entry}), so the
 * vanilla F3+F6 options screen is the fold/unfold control: each section can be shown always, only in the F3 overlay, or never.
 * <p>
 * Cost model: a section is rebuilt lazily by {@link #lines} when its entry is asked for lines and the cached list is older than the
 * section interval (500 ms = {@link PerfMonitor#WINDOW_NS}, rarely changing data 2 s). Between rebuilds the entry hands the same
 * immutable list to the overlay and allocates nothing. With F3 hidden (or a section switched off) nothing is called at all.
 * The cost of every rebuild is measured with {@code System.nanoTime} and logged as {@code [VKB-F3] build_us=..}.
 * Render thread only.
 */
public final class F3Sections {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-F3");
    private static final long FAST_NS = PerfMonitor.WINDOW_NS;
    private static final long SLOW_NS = 2_000_000_000L;
    private static final long STATS_LOG_NS = 30_000_000_000L;

    /** One entry of the F3 screen. The id path is what the F3+F6 screen shows, the numeric part orders the groups. */
    public enum Section {
        OVERVIEW("bedray_1_overview", true, FAST_NS),
        LOAD("bedray_2_load", true, FAST_NS),
        QUALITY("bedray_3_quality", true, FAST_NS),
        LIBS("bedray_4_libs", true, SLOW_NS),
        TIMING("bedray_5_timing", false, FAST_NS),
        DIAG("bedray_6_diag", false, 1_000_000_000L);

        private final Identifier id;
        private final boolean defaultOn;
        private final long intervalNs;

        Section(String path, boolean defaultOn, long intervalNs) {
            this.id = Identifier.fromNamespaceAndPath("bedray", path);
            this.defaultOn = defaultOn;
            this.intervalNs = intervalNs;
        }

        public Identifier id() {
            return id;
        }

        /** Whether the section is part of the overlay the first time (a choice saved by the player in debug-profile.json wins). */
        public boolean defaultOn() {
            return defaultOn;
        }
    }

    public static final Section[] ALL = Section.values();

    @SuppressWarnings("unchecked")
    private static final List<String>[] LINES = (List<String>[]) new List[ALL.length];
    private static final long[] BUILT_NS = new long[ALL.length];
    private static final long[] BUILD_COUNT = new long[ALL.length];
    private static final long[] BUILD_SUM_NS = new long[ALL.length];
    private static final long[] BUILD_MAX_NS = new long[ALL.length];
    private static final long[] BUILD_LAST_NS = new long[ALL.length];
    private static final boolean[] FAILURE_LOGGED = new boolean[ALL.length];
    private static long totalBuilds;
    private static long lastStatsLogNs;

    private static final StringBuilder SB = new StringBuilder(256);

    // state kept between rebuilds of one section
    private static long lastBlasTotal = -1;
    private static long lastBlasNs;
    private static double blasPerSec;
    private static String deviceText;
    private static String xessVersion;
    private static Object xessVersionOwner;
    private static FrameGenRuntime.Availability fgAvailability = FrameGenRuntime.Availability.NO_BINARIES;
    private static long fgAvailabilityNs;

    private F3Sections() {}

    // ------------------------------------------------------------------------------------------------------------ access

    /** Cached lines of {@code s}, rebuilt first when older than the section interval. The hot path of the overlay. */
    public static List<String> lines(Section s) {
        int i = s.ordinal();
        List<String> l = LINES[i];
        long now = System.nanoTime();
        if (l == null || now - BUILT_NS[i] >= s.intervalNs) {
            l = rebuild(s, now);
        }
        return l;
    }

    /** All sections, rebuilt now, as one list (autotest log and the legacy {@code DebugInfo.lines()}). */
    static List<String> allLinesFresh() {
        List<String> all = new ArrayList<>(48);
        for (Section s : ALL) {
            all.addAll(rebuild(s, System.nanoTime()));
        }
        return all;
    }

    /** Rebuilds every section {@code n} times, for the build_us measurement of the autotest. Returns the total ns. */
    public static long benchmarkRebuild(int n) {
        long t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            for (Section s : ALL) {
                rebuild(s, System.nanoTime());
            }
        }
        return System.nanoTime() - t0;
    }

    public static long totalBuilds() {
        return totalBuilds;
    }

    /** One line {@code build_us} / {@code avg_us} / {@code max_us} per section for the log. */
    public static String statsLine() {
        StringBuilder b = new StringBuilder(256);
        long sum = 0;
        long cnt = 0;
        for (Section s : ALL) {
            int i = s.ordinal();
            sum += BUILD_SUM_NS[i];
            cnt += BUILD_COUNT[i];
        }
        b.append("builds=").append(cnt).append(" avg_us=").append(cnt == 0 ? 0 : sum / cnt / 1000);
        for (Section s : ALL) {
            int i = s.ordinal();
            b.append(' ').append(s.name().toLowerCase(Locale.ROOT)).append("_build_us=").append(BUILD_LAST_NS[i] / 1000)
                .append("/avg=").append(BUILD_COUNT[i] == 0 ? 0 : BUILD_SUM_NS[i] / BUILD_COUNT[i] / 1000)
                .append("/max=").append(BUILD_MAX_NS[i] / 1000);
        }
        return b.toString();
    }

    private static List<String> rebuild(Section s, long now) {
        int i = s.ordinal();
        List<String> out = new ArrayList<>(12);
        long t0 = System.nanoTime();
        try {
            switch (s) {
                case OVERVIEW -> overview(out);
                case LOAD -> load(out, now);
                case QUALITY -> quality(out);
                case LIBS -> libs(out, now);
                case TIMING -> timing(out);
                case DIAG -> DebugInfo.providerLines(out);
            }
        } catch (RuntimeException | LinkageError e) {
            // Not swallowed: the first failure of a section is logged with its stack, and the F3 shows a red line from then on.
            if (!FAILURE_LOGGED[i]) {
                FAILURE_LOGGED[i] = true;
                LOGGER.error("[VKB-F3] section {} failed to build", s, e);
            }
            out.clear();
            out.add(ERR + "Bedray F3 " + s.id().getPath() + " failed: " + e.getClass().getSimpleName() + R);
        }
        List<String> built = List.copyOf(out);
        long dt = System.nanoTime() - t0;
        LINES[i] = built;
        BUILT_NS[i] = now;
        BUILD_LAST_NS[i] = dt;
        BUILD_COUNT[i]++;
        BUILD_SUM_NS[i] += dt;
        BUILD_MAX_NS[i] = Math.max(BUILD_MAX_NS[i], dt);
        totalBuilds++;
        if (now - lastStatsLogNs >= STATS_LOG_NS) {
            lastStatsLogNs = now;
            LOGGER.info("[VKB-F3] build_us={} ({})", dt / 1000, statsLine());
        }
        return built;
    }

    private static StringBuilder sb() {
        SB.setLength(0);
        return SB;
    }

    // ------------------------------------------------------------------------------------------------------------ overview

    private static void overview(List<String> out) {
        out.add(HEAD + "Bedray RTX (alpha)" + R);
        out.add(rtLine());
        out.add(upscaleLine());
        out.add(renderLine());
        String denoise = denoiseLine();
        if (denoise != null) {
            out.add(denoise);
        }
        out.add(frameGenLine());
        out.add(frameLine());
        out.add(gpuPhasesLine());
    }

    private static String rtLine() {
        StringBuilder b = sb().append("RT: ");
        RtActivation.Reason reason = RtGeometryGate.blockReason();
        if (RtGeometryGate.enabled()) {
            RtMode sel = VulkanRtxConfig.effectiveRtMode();
            RtMode pass = RtFrameRenderer.lastPassMode();
            boolean ready = RtFrameRenderer.worldReady();
            colored(b, ready ? OK : WARN, sel.logName());
            if (!ready) {
                colored(b, OFF, " starting");
            } else if (pass != null && pass != sel) {
                colored(b, WARN, " pass " + pass.logName());
            }
            if (sel == RtMode.PATH_TRACED) {
                b.append(' ').append(VAL).append(RtSettings.bounces()).append(R).append(" bounce ")
                    .append(VAL).append(RtSettings.spp()).append(R).append(" spp");
            } else if (sel == RtMode.RAY_TRACED) {
                b.append(' ').append(OFF).append('[');
                appendFlag(b, RtSettings.shadows(), "sh");
                appendFlag(b, RtSettings.reflections(), "refl");
                appendFlag(b, RtSettings.ao(), "ao");
                appendFlag(b, RtSettings.emissive(), "em");
                appendFlag(b, RtSettings.gi(), "gi");
                if (b.charAt(b.length() - 1) == ' ') {
                    b.setLength(b.length() - 1);
                }
                b.append(']').append(R);
            }
            if (RtGeometryGate.forced()) {
                colored(b, OFF, " forced");
            }
        } else if (reason == null || reason == RtActivation.Reason.CONFIG_OFF) {
            colored(b, OFF, "off");
        } else if (reason == RtActivation.Reason.FAILED) {
            b.append(ERR).append("FAILED ");
            cut(b, RtGeometryGate.failure(), 40).append(R);
        } else if (reason == RtActivation.Reason.INCOMPATIBLE_TEMPORAL_UPSCALER) {
            colored(b, WARN, "blocked by the upscaler");
        } else {
            b.append(ERR).append("unavailable: ");
            cut(b, reason.text(), 40).append(R);
        }
        return b.toString();
    }

    private static void appendFlag(StringBuilder b, boolean on, String name) {
        if (on) {
            b.append(OK).append(name).append(OFF).append(' ');
        }
    }

    private static String upscaleLine() {
        UpscalerRequest.Request req = UpscalerRequest.current();
        StringBuilder b = sb().append("Upscale: ");
        UpscalerBackend backend = req.backend();
        switch (backend) {
            case DLSS -> runtimeState(b, req.toString(), DlssRuntime.state(), DlssRuntime.lastFailure());
            case XESS -> runtimeState(b, req.toString(), XessRuntime.state(), XessRuntime.lastFailure());
            case FSR -> {
                runtimeState(b, req.toString(), FsrRuntime.state(), null);
                if (FsrRuntime.isActive()) {
                    b.append(OFF).append(' ').append(FsrRuntime.activeProvider()).append(R);
                }
            }
            case FSR1, NIS -> colored(b, SpatialUpscaler.isSelected() ? OK : OFF, req.toString());
            case BILINEAR -> {
                float scale = VulkanRtxConfig.qualityPreset.getScaleFactor();
                if (scale < 1.0f) {
                    b.append(WARN).append("Bilinear ");
                    fixed(b, scale * 100.0, 0).append("%").append(R).append(OFF).append(" (no reconstruction)").append(R);
                } else {
                    colored(b, OFF, "none (native)");
                }
            }
        }
        return b.toString();
    }

    private static void runtimeState(StringBuilder b, String name, Enum<?> state, String failure) {
        String st = state.name();
        b.append(stateColor(st)).append(name);
        if (!"READY".equals(st)) {
            b.append(' ').append(st);
            if ("FAILED".equals(st) && failure != null) {
                b.append(' ');
                cut(b, failure, 36);
            }
        }
        b.append(R);
    }

    private static String renderLine() {
        int rw = DebugInfo.renderW();
        int rh = DebugInfo.renderH();
        int ow = DebugInfo.outW();
        int oh = DebugInfo.outH();
        StringBuilder b = sb().append("Render: ");
        if (ow <= 0 || oh <= 0) {
            return b.append(OFF).append("no world").append(R).toString();
        }
        if (!DebugInfo.worldPhase()) {
            return b.append(VAL).append(ow).append('x').append(oh).append(R).append(OFF).append(" native").append(R).toString();
        }
        b.append(VAL).append(rw).append('x').append(rh).append(R).append(" > ").append(VAL).append(ow).append('x').append(oh).append(R);
        b.append(OFF).append(" (");
        fixed(b, 100.0 * rw / ow, 0).append("% axis)").append(R);
        return b.toString();
    }

    private static String denoiseLine() {
        if (!RtGeometryGate.enabled()) {
            return null;
        }
        RtSettings.PathDenoiser requested = RtFrameRenderer.denoiserRequested();
        DenoiserMode selected = RtFrameRenderer.denoiserSelected();
        String runtime = RtFrameRenderer.denoiserRuntimeState();
        String failure = RtFrameRenderer.denoiserRuntimeFailure();
        String reason = RtFrameRenderer.denoiserResolutionReason();
        boolean wantsOff = requested == RtSettings.PathDenoiser.OFF;
        boolean fallback = requested == RtSettings.PathDenoiser.DLSS_RR && selected.isNrd();
        String color;
        if (failure != null && !failure.isBlank()) {
            color = ERR;
        } else if (selected == DenoiserMode.OFF) {
            color = wantsOff ? OFF : WARN;
        } else if (fallback || !"active".equals(runtime)) {
            color = WARN;
        } else {
            color = OK;
        }
        StringBuilder b = sb().append("Denoise: ").append(color).append(selected.logName()).append(R);
        b.append(OFF).append(" (req ").append(requested.name().toLowerCase(Locale.ROOT)).append(", ").append(runtime).append(')').append(R);
        if (fallback) {
            colored(b, WARN, " fallback");
            if (reason != null && !reason.isBlank() && !"setting".equals(reason)) {
                b.append(' ').append(WARN);
                cut(b, reason.replace(" -> fallback_to_nrd", ""), 60).append(R);
            }
        }
        if (failure != null && !failure.isBlank()) {
            b.append(' ').append(ERR);
            cut(b, failure, 30).append(R);
        } else if (!wantsOff && (selected == DenoiserMode.OFF || fallback) && reason != null && !"setting".equals(reason)) {
            // also for the RR -> NRD fallback: the owner saw "fallback" without the cause (DLL missing / failed earlier / init error)
            b.append(' ').append(WARN);
            cut(b, reason, 30).append(R);
        }
        return b.toString();
    }

    private static FrameGenRuntime.Availability fgAvailability(long now) {
        if (now - fgAvailabilityNs >= SLOW_NS) {
            fgAvailabilityNs = now;
            fgAvailability = FrameGenRuntime.availability(); // looks at two files when Streamline is not up, so not every window
        }
        return fgAvailability;
    }

    private static String frameGenLine() {
        long now = System.nanoTime();
        F3Fps.update(now);
        FrameGenSettings.Mode mode = FrameGenSettings.mode();
        FrameGenRuntime.Availability av = fgAvailability(now);
        StringBuilder b = sb().append("FrameGen: ");
        if (mode == FrameGenSettings.Mode.OFF && av != FrameGenRuntime.Availability.READY) {
            return colored(b, OFF, "off").toString();
        }
        switch (av) {
            case NO_BINARIES -> colored(b, ERR, "no Streamline DLLs");
            case NEEDS_RESTART -> colored(b, WARN, "restart required (" + mode.label() + ")");
            case FAILED -> {
                b.append(ERR).append("failed ");
                cut(b, FrameGenRuntime.failure(), 36).append(R);
            }
            case UNSUPPORTED -> colored(b, ERR, "unsupported by this GPU/driver");
            case READY -> {
                FrameGenRuntime.Snapshot sn = FrameGenRuntime.snapshot();
                FrameGenRuntime.State st = sn.status().state();
                if (F3Fps.generating()) {
                    long real = Math.round(F3Fps.realFps());
                    long shown = Math.round(F3Fps.shownFps());
                    b.append(OK).append(F3Fps.multiplier()).append('x').append(R).append(' ').append(REAL).append(real).append(R)
                        .append(OFF).append(" + ").append(R).append(GEN).append(Math.max(0L, shown - real)).append(R)
                        .append(OFF).append(" = ").append(R).append(VAL).append(shown).append(R).append(" fps");
                } else if (st == FrameGenRuntime.State.ACTIVE) {
                    colored(b, WARN, "reported active, no generated frames in the last second");
                } else if (st == FrameGenRuntime.State.ENGAGING) {
                    colored(b, WARN, "engaging");
                } else if (st == FrameGenRuntime.State.FAILED || st == FrameGenRuntime.State.UNAVAILABLE) {
                    b.append(ERR);
                    cut(b, sn.status().reason(), 36).append(R);
                } else {
                    b.append(OFF).append("standby: ");
                    cut(b, sn.status().reason().replace('_', ' '), 28).append(R);
                }
                b.append(OFF).append(" | Reflex ").append(FrameGenSettings.reflex().name().toLowerCase(Locale.ROOT)).append(R);
                if (sn.latencyUs() >= 0 && st != FrameGenRuntime.State.READY) {
                    b.append(OFF).append(" lat ").append(R).append(VAL);
                    fixed(b, sn.latencyUs() / 1000.0, 1).append(" ms").append(R);
                }
            }
        }
        return b.toString();
    }

    private static String frameLine() {
        double ms = PerfMonitor.frameIntervalMs();
        StringBuilder b = sb().append("Frame: ");
        if (!(ms > 0.0)) {
            return colored(b, OFF, "collecting").toString();
        }
        b.append(frameColor(ms));
        ms(b, ms).append(" ms").append(R).append(OFF).append(" (").append(R).append(VAL);
        fixed(b, PerfMonitor.fps(), 0).append(" fps").append(R).append(OFF).append(')').append(R);
        if (PerfMonitor.gpuValid(Segment.TOTAL)) {
            b.append(" | GPU ").append(VAL);
            ms(b, PerfMonitor.gpuMs(Segment.TOTAL)).append(" ms").append(R);
        }
        Integer cap = limitedBy();
        if (cap != null) {
            b.append(" | ").append(PURPLE).append("limit ").append(cap).append(R);
        } else {
            String v = PerfMonitor.boundVerdict();
            if (v != null) {
                b.append(" | ").append("GPU".equals(v) ? GOLD : "CPU".equals(v) ? VAL : REAL).append(v).append("-bound").append(R);
            }
        }
        return b.toString();
    }

    private static String gpuPhasesLine() {
        String off = PerfMonitor.gpuUnavailableReason();
        StringBuilder b = sb().append("GPU ms: ");
        if (off != null) {
            b.append(WARN).append("n/a (");
            cut(b, off, 32).append(')').append(R);
            return b.toString();
        }
        if (!PerfMonitor.gpuValid(Segment.TOTAL)) {
            return colored(b, OFF, "collecting").toString();
        }
        phase(b, "world", Segment.WORLD);
        phase(b, "rt", Segment.RT);
        phase(b, "post", Segment.POST);
        phase(b, "up", Segment.UPSCALE);
        phase(b, "gui", Segment.GUI);
        if (b.charAt(b.length() - 1) == ' ') {
            b.setLength(b.length() - 1);
        }
        return b.toString();
    }

    private static void phase(StringBuilder b, String name, Segment s) {
        if (!PerfMonitor.gpuValid(s)) {
            return;
        }
        b.append(name).append(' ').append(VAL);
        ms(b, PerfMonitor.gpuMs(s)).append(R).append(' ');
    }

    private static int framerateLimit() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.gameRenderer != null ? mc.gameRenderer.gameRenderState().framerateLimit : 260;
    }

    /** Null, or the cap when the frame rate sits at a frame limiter (then neither CPU nor GPU is the bottleneck). */
    static Integer limitedBy() {
        int limit = framerateLimit();
        return limit < 260 && PerfMonitor.fps() >= 0.9 * limit ? limit : null;
    }

    // ------------------------------------------------------------------------------------------------------------ load

    private static void load(List<String> out, long now) {
        out.add(HEAD + "Bedray load" + R);
        if (!RtGeometryGate.enabled()) {
            out.add(OFF + "RT off: no acceleration structures" + R);
            lastBlasTotal = -1;
            return;
        }
        RtWorld.FrameStats s = RtFrameRenderer.lastStats();
        long blasTotal = RtFrameRenderer.blasBuiltTotal();
        if (lastBlasTotal >= 0 && now > lastBlasNs) {
            blasPerSec = (blasTotal - lastBlasTotal) * 1.0e9 / (now - lastBlasNs);
        }
        lastBlasTotal = blasTotal;
        lastBlasNs = now;
        if (s == null) {
            out.add(WARN + "TLAS/BLAS: waiting for the first RT frame" + R);
            return;
        }
        StringBuilder b = sb().append("TLAS: ").append(VAL).append(s.instances()).append(R).append(" inst ").append(VAL);
        ms(b, s.tlasMs()).append(" ms").append(R).append(" | BLAS ").append(VAL).append(s.totalSections()).append(R)
            .append(OFF).append(" (+");
        fixed(b, blasPerSec, 0).append("/s)").append(R);
        out.add(b.toString());

        b = sb().append("BLAS build: ").append(VAL);
        ms(b, s.buildMs()).append(" ms").append(R).append(" +").append(s.builtThisFrame()).append(" | pending ")
            .append(s.pending() > 0 ? WARN : OFF).append(s.pending()).append(R).append(" | failed ")
            .append(s.failedSections() > 0 ? ERR : OFF).append(s.failedSections()).append(R);
        out.add(b.toString());

        long mb = s.vramBytes() >> 20;
        long budgetMb = RtConfig.vramBudgetBytes() >> 20;
        String memColor = budgetMb <= 0 ? VAL : mb * 10 >= budgetMb * 9 ? ERR : mb * 4 >= budgetMb * 3 ? WARN : VAL;
        SectionGeometryRegistry reg = SectionGeometryRegistry.INSTANCE;
        b = sb().append("RT mem: ").append(memColor).append(mb).append(R).append(" / ").append(budgetMb).append(" MB | geo ")
            .append(VAL).append(reg.sectionCount()).append(R).append(" sec ").append(VAL);
        mega(b, reg.totalQuads()).append(R).append(" quads");
        out.add(b.toString());

        out.add(lightsLine());

        int rw = DebugInfo.renderW();
        int rh = DebugInfo.renderH();
        if (DebugInfo.worldPhase() && rw > 0 && rh > 0) {
            RtMode sel = VulkanRtxConfig.effectiveRtMode();
            int spp = sel == RtMode.PATH_TRACED ? Math.max(1, RtSettings.spp()) : 1;
            b = sb().append("Primary rays: ").append(VAL);
            mega(b, (double) rw * rh * spp).append(R).append("/frame ").append(OFF).append('(').append(rw).append('x').append(rh)
                .append(" px x ").append(spp).append(" spp)").append(R);
            out.add(b.toString());
        }
    }

    /** Emitters of the light list from {@code RtFrameRenderer.lightTelemetry()}: used=..,in_range=..,total=..; parsed, not estimated. */
    private static String lightsLine() {
        String t = RtFrameRenderer.lightTelemetry();
        long used = field(t, "used=");
        long inRange = field(t, "in_range=");
        long total = field(t, "total=");
        StringBuilder b = sb().append("Lights: ");
        if (used < 0 || inRange < 0 || total < 0) {
            return colored(b, OFF, "n/a").toString();
        }
        b.append(used > 0 ? OK : OFF).append(used).append(R).append(" used / ").append(VAL).append(inRange).append(R)
            .append(" in range / ").append(VAL).append(total).append(R).append(" total");
        return b.toString();
    }

    /** Integer after {@code key} in a comma separated key=value text, -1 when absent. */
    static long field(String text, String key) {
        if (text == null) {
            return -1;
        }
        int at = text.indexOf(key);
        if (at < 0) {
            return -1;
        }
        int i = at + key.length();
        long v = 0;
        int digits = 0;
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            v = v * 10 + (text.charAt(i) - '0');
            i++;
            digits++;
        }
        return digits == 0 ? -1 : v;
    }

    // ------------------------------------------------------------------------------------------------------------ quality

    private static void quality(List<String> out) {
        out.add(HEAD + "Bedray quality" + R);
        boolean rt = RtGeometryGate.enabled();
        double hist = RtFrameRenderer.lastHistoryAcceptedPct();
        StringBuilder b = sb().append("History: ");
        DenoiserMode histDen = RtFrameRenderer.denoiserSelected();
        if (!rt) {
            colored(b, OFF, "RT off");
        } else if (RtFrameRenderer.lastPassMode() == RtMode.PATH_TRACED && histDen != DenoiserMode.OFF
            && "active".equals(RtFrameRenderer.denoiserRuntimeState())) {
            // the built-in accumulation is switched off while a denoiser runs (RtPathTracerPass), so its 0% / 1 fr is not a fault
            b.append(OK).append(histDen.logName()).append(R).append(OFF).append(" temporal history inside the denoiser (built-in accumulation off)").append(R);
        } else if (hist < 0) {
            colored(b, OFF, "n/a (no readback yet)");
        } else {
            b.append(historyColor(hist));
            fixed(b, hist, 1).append("% accepted").append(R);
            if (RtFrameRenderer.lastPassMode() == RtMode.PATH_TRACED) {
                b.append(" | accum ").append(VAL).append(RtFrameRenderer.lastAccumulatedFrames()).append(R).append(" fr");
            }
        }
        out.add(b.toString());

        b = sb().append("Jitter: ");
        if (!CameraJitterManager.isActive() || !TemporalUpscaler.isActive()) {
            colored(b, OFF, "off");
        } else {
            b.append(OK).append("on").append(R).append(" phase ").append(CameraJitterManager.getPhaseIndex() + 1).append('/')
                .append(CameraJitterManager.phaseCountFor(TemporalUpscaler.scale())).append(OFF).append(" (");
            appendSigned(b, CameraJitterManager.getCurrentPixelJitterX());
            b.append(", ");
            appendSigned(b, CameraJitterManager.getCurrentPixelJitterY());
            b.append(" px)").append(R);
        }
        out.add(b.toString());

        out.add(sharpLine());

        if (rt && RtFrameRenderer.lastPassMode() == RtMode.PATH_TRACED) {
            b = sb().append("Path: ").append(OFF).append("max dist ").append(R).append(VAL).append(RtSettings.pathMaxDistance()).append(R)
                .append(" blocks | accumulate ").append(RtSettings.accumulate() ? OK : OFF).append(RtSettings.accumulate() ? "on" : "off").append(R);
            out.add(b.toString());
        }
    }

    private static void appendSigned(StringBuilder b, double v) {
        b.append(v < 0 ? '-' : '+');
        fixed(b, Math.abs(v), 2);
    }

    private static String sharpLine() {
        UpscalerRequest.Request req = UpscalerRequest.current();
        StringBuilder b = sb().append("Sharpen: ");
        switch (req.backend()) {
            case FSR -> sharpValue(b, "RCAS ", VulkanRtxConfig.fsrSharpness);
            case FSR1 -> sharpValue(b, "RCAS ", VulkanRtxConfig.fsr1Sharpness);
            case NIS -> sharpValue(b, "NIS ", VulkanRtxConfig.nisSharpness);
            default -> colored(b, OFF, "none (setting of FSR/FSR1/NIS only)");
        }
        return b.toString();
    }

    private static void sharpValue(StringBuilder b, String label, float value) {
        b.append(label).append(VAL);
        fixed(b, value, 2).append(R);
    }

    // ------------------------------------------------------------------------------------------------------------ libs

    private static void libs(List<String> out, long now) {
        out.add(HEAD + "Bedray libraries" + R);
        out.add(deviceLine());

        DlssAvailability.Library dlss = DlssAvailability.library();
        StringBuilder b = sb().append("DLSS ");
        b.append(dlss.usable() ? OK : ERR).append(dlss.usable() ? "found" : "missing").append(R);
        b.append(" | FG ").append(UpscalerLibraryLoader.hasDlssFrameGen() ? OK : OFF)
            .append(UpscalerLibraryLoader.hasDlssFrameGen() ? "found" : "missing").append(R);
        b.append(" | RR ").append(UpscalerLibraryLoader.hasDlssRayReconstruction() ? OK : OFF)
            .append(UpscalerLibraryLoader.hasDlssRayReconstruction() ? "found" : "missing").append(R);
        out.add(b.toString());
        if (!dlss.usable()) {
            b = sb().append(OFF).append("  ");
            cut(b, dlss.reason(), 52).append(R);
            out.add(b.toString());
        }

        b = sb().append("XeSS ");
        boolean xess = UpscalerLibraryLoader.isBackendAvailable(UpscalerBackend.XESS);
        b.append(xess ? OK : OFF).append(xess ? "found" : "missing").append(R);
        String xv = xessVersionText();
        if (xv != null) {
            b.append(' ').append(VAL).append(xv).append(R);
        }
        FsrLibrary.Library fsr = FsrLibrary.library();
        b.append(" | FSR ").append(fsr.usable() ? OK : OFF).append(fsr.usable() ? "found" : "missing").append(R);
        if (fsr.usable() && fsr.fileVersion() != null) {
            b.append(' ').append(VAL);
            cut(b, fsr.fileVersion(), 14).append(R);
        }
        out.add(b.toString());

        b = sb().append("Streamline ");
        if (StreamlineBootstrap.isReady()) {
            b.append(OK).append("loaded").append(R);
            FrameGenRuntime.Snapshot sn = FrameGenRuntime.snapshot();
            if (sn.driver() != null && !"?".equals(sn.driver())) {
                b.append(OFF).append(" (driver >= ").append(sn.driver()).append(')').append(R);
            }
        } else if (StreamlineBootstrap.failure() != null) {
            b.append(ERR).append("failed ");
            cut(b, StreamlineBootstrap.failure(), 36).append(R);
        } else if (StreamlineBootstrap.requested()) {
            colored(b, WARN, "requested, not initialised");
        } else {
            colored(b, OFF, "not requested (-Dvkrtx.framegen)");
        }
        out.add(b.toString());
    }

    private static String deviceLine() {
        if (deviceText == null) {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            deviceText = "GPU: " + info.name() + " | " + info.backendName() + " | drv " + info.driverInfo();
        }
        return deviceText;
    }

    /** XeSS version from {@code xessGetVersion}, read once per loaded library; null while the library is not loaded. */
    private static String xessVersionText() {
        XessNative lib = XessRuntime.nativeLib();
        if (lib == null) {
            return null;
        }
        if (xessVersionOwner != lib) {
            xessVersionOwner = lib;
            try {
                xessVersion = lib.getVersionString();
            } catch (RuntimeException e) {
                LOGGER.warn("[VKB-F3] xessGetVersion failed", e);
                xessVersion = "version ?";
            }
        }
        return xessVersion;
    }

    // ------------------------------------------------------------------------------------------------------------ timing

    private static void timing(List<String> out) {
        out.add(HEAD + "Bedray timing" + R);
        StringBuilder b = sb().append("CPU ms: ");
        if (!PerfMonitor.cpuValid(Segment.TOTAL)) {
            out.add(colored(b, OFF, "collecting").toString());
        } else {
            b.append("render ").append(VAL);
            ms(b, PerfMonitor.cpuMs(Segment.TOTAL)).append(R);
            cpuPhase(b, "world", Segment.WORLD);
            cpuPhase(b, "rt", Segment.RT);
            cpuPhase(b, "up", Segment.UPSCALE);
            cpuPhase(b, "gui", Segment.GUI);
            out.add(b.toString());
        }
        b = sb().append("GPU ms: ");
        if (!PerfMonitor.gpuValid(Segment.TOTAL)) {
            out.add(colored(b, OFF, "collecting").toString());
        } else {
            b.append("pre ").append(VAL);
            ms(b, PerfMonitor.gpuMs(Segment.PRE)).append(R).append(" hooks ").append(VAL);
            ms(b, PerfMonitor.gpuMs(Segment.HOOKS)).append(R).append(" blit ").append(VAL);
            ms(b, PerfMonitor.gpuMs(Segment.BLIT)).append(R);
            out.add(b.toString());
            double busy = PerfMonitor.gpuBusyPercent();
            b = sb().append("GPU busy: ").append(busy >= 90.0 ? GOLD : VAL);
            fixed(b, busy, 0).append('%').append(R).append(" of the frame interval");
            out.add(b.toString());
        }
        b = sb().append("Frame limit: ");
        int limit = framerateLimit();
        out.add(limit >= 260 ? colored(b, OFF, "none").toString() : b.append(PURPLE).append(limit).append(" fps").append(R).toString());
    }

    private static void cpuPhase(StringBuilder b, String name, Segment s) {
        if (PerfMonitor.cpuValid(s)) {
            b.append(' ').append(name).append(' ').append(VAL);
            ms(b, PerfMonitor.cpuMs(s)).append(R);
        }
    }
}
