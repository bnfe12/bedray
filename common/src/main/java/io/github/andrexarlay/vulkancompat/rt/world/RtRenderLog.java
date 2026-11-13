package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * The operator-facing log of the RT path: the once-per-second status / BLAS / rt_pass window, the pass and shading lines
 * that print on change, and the sky-display packet line. It owns the window accumulators and the last-logged dedup keys;
 * the live frame state it prints is borrowed from the renderer through {@link Context} so the wording stays in one place.
 */
final class RtRenderLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    // GPU spans of the RT command buffer, logged by RtGpuTimer (-Dvkrtx.rt.gpuTimers=false disables it)
    private static final int SPAN_WORLD = 0;
    private static final int SPAN_RAYS = 1;
    private static final int SPAN_DENOISE = 2;
    private static final int SPAN_ATMO = 3;

    /** The live frame state the status lines print; the renderer is the single source of each value. */
    interface Context {
        RtWorld world();
        RtEmitters emitters();
        RtFrameParams params();
        int lastRadiusXz();
        int lastRadiusY();
        RtGpuTimer gpuTimer();
        long passFrames();
        double lastHitPct();
        double lastRejectedPct();
        RtStatsMetering.Breakdown lastRejectSplit();
        double lastHistPct();
        double lastLeafPct();
        RtTraceTelemetry lastTraceTelemetry();
        RtFrameRenderer.StatsMetadata lastStatsMetadata();
        long lastStatsTrustedPixels();
    }

    private final Context context;
    private long windowStartNs = 0L;
    private int builtInWindow = 0;
    private double buildMsInWindow = 0.0;
    private int lastLoggedTotal = -1;
    private int lastLoggedInstances = -1;
    private long lastStatusNs;
    private double passMsInWindow = 0.0;
    private int passFramesInWindow = 0;
    private String lastPassLog = "";
    private String lastSkyDisplayLog = "";
    private long lastShadingLogNs = 0L;
    private int lastAtlasW = -1;

    RtRenderLog(Context context) { this.context = context; }

    void addTracedFrame(double ms) {
        passFramesInWindow++;
        passMsInWindow += ms;
    }

    void resetPassLog() { lastPassLog = ""; }

    void resetAfterShutdown() {
        lastPassLog = "";
        lastSkyDisplayLog = "";
    }

    /** Logs {@code [VKB-RT] mode=.. passes=..} on the first frame and whenever it changes. */
    void logPass(String passes) {
        String line = RtConfig.mode().logName() + "|" + passes;
        if (!line.equals(lastPassLog)) {
            lastPassLog = line;
            LOGGER.info("[VKB-RT] mode={} passes={}", RtConfig.mode().logName(), passes);
        }
    }

    /** Logs the shading setup on the first frame, when the atlas size changes, and every 10 s (the sun moves). */
    void logShading(int atlasW, int atlasH, boolean hasSky, SkyRenderState sky) {
        RtFrameParams params = context.params();
        long now = System.nanoTime();
        if (lastAtlasW == atlasW && now - lastShadingLogNs < 10_000_000_000L) {
            return;
        }
        lastAtlasW = atlasW;
        lastShadingLogNs = now;
        float[] sun = new float[3];
        RtSky.sunDirection(sky.sunAngle, RtConfig.SUN_FLIP_X, sun);
        float[] moon = new float[3];
        RtSky.sunDirection(sky.moonAngle, RtConfig.SUN_FLIP_X, moon);
        long clock = Minecraft.getInstance().level.getOverworldClockTime();
        LOGGER.info(String.format(Locale.ROOT,
            "[VKB-RT] shading mode=%s sun_dir=(%.3f,%.3f,%.3f) atlas=%dx%d day=%.2f sky=%s sky_color=(%.2f,%.2f,%.2f) sun_flip_x=%s ao=%d"
                + " sun_angle_raw=%.4f moon_dir=(%.3f,%.3f,%.3f) clock_ticks=%d tonemap=%s key_dir=(%.3f,%.3f,%.3f)",
            RtConfig.effectiveModeName(), sun[0], sun[1], sun[2], atlasW, atlasH,
            params.key.day, hasSky, params.skyColor[0], params.skyColor[1], params.skyColor[2], RtConfig.SUN_FLIP_X,
            params.aoSamples, sky.sunAngle, moon[0], moon[1], moon[2], clock, params.tonemap,
            params.key.dir[0], params.key.dir[1], params.key.dir[2]));
    }

    /** Logs the private display-sky packet on the first frame and whenever its layout or loaded mask changes. */
    void logSkyDisplay(RtPass pass, RtSkyTextures skyTextures) {
        String skyDisplayState = pass.mode().logName() + ":" + pass.supportsDisplaySky() + ":"
            + skyTextures.wordCount() + ":" + skyTextures.loadedMask();
        if (!skyDisplayState.equals(lastSkyDisplayLog)) {
            lastSkyDisplayLog = skyDisplayState;
            LOGGER.info("[VKB-SKY] display={} profile={} packet_version=1 words={} loaded_mask=0x{} reason={}",
                pass.supportsDisplaySky() ? "textured" : "gradient", pass.mode().logName(), skyTextures.wordCount(),
                Integer.toHexString(skyTextures.loadedMask()), pass.supportsDisplaySky() ? "ready" : "native_stats_slot_unavailable");
        }
    }

    /** Milliseconds for the status line, or "n/a" while the GPU timer has no readable sample. */
    private static String msOrNa(double v) {
        return v < 0.0 ? "n/a" : String.format(Locale.ROOT, "%.1f", v);
    }

    void logWindow(RtWorld.FrameStats s, RenderTarget target) {
        RtWorld world = context.world();
        RtEmitters emitters = context.emitters();
        RtFrameParams params = context.params();
        int lastRadiusXz = context.lastRadiusXz();
        int lastRadiusY = context.lastRadiusY();
        RtGpuTimer gpuTimer = context.gpuTimer();
        long passFrames = context.passFrames();
        double lastHitPct = context.lastHitPct();
        double lastRejectedPct = context.lastRejectedPct();
        RtStatsMetering.Breakdown lastRejectSplit = context.lastRejectSplit();
        double lastHistPct = context.lastHistPct();
        double lastLeafPct = context.lastLeafPct();
        RtTraceTelemetry lastTraceTelemetry = context.lastTraceTelemetry();
        RtFrameRenderer.StatsMetadata lastStatsMetadata = context.lastStatsMetadata();
        long lastStatsTrustedPixels = context.lastStatsTrustedPixels();
        builtInWindow += s.builtThisFrame();
        buildMsInWindow += s.buildMs();
        long now = System.nanoTime();
        if (windowStartNs == 0L) {
            windowStartNs = now;
        }
        if (now - windowStartNs < 1_000_000_000L) {
            return;
        }
        boolean changed = builtInWindow > 0 || s.totalSections() != lastLoggedTotal || s.instances() != lastLoggedInstances;
        if (now - lastStatusNs >= 5_000_000_000L) {
            lastStatusNs = now;
            // F3-style status line (one line, every 5 s)
            LOGGER.info(String.format(Locale.ROOT,
                "[VKB-RT] status mode=%s lights=%d sun=(%.2f,%.2f,%.2f)x%.2f day=%.2f key=%s radius=%d/%d sections=%d scene_vram_mb=%d sun_size_deg=%.2f",
                RtConfig.mode().logName(), emitters != null ? emitters.count() : 0, params.key.dir[0], params.key.dir[1],
                params.key.dir[2], params.key.color[0], params.key.day, params.key.isMoon ? "moon" : "sun", lastRadiusXz, lastRadiusY,
                s.totalSections(), s.vramBytes() >> 20,
                io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.sunSizeTenths / 10.0));
        }
        if (changed) {
            LOGGER.info(String.format(Locale.ROOT,
                "[VKB-RT] blas built=%d total=%d build_ms=%.2f tlas instances=%d tlas_ms=%.2f vram_mb=%d pending=%d identical_skipped=%d veg_sections=%d veg_quads=%d",
                builtInWindow, s.totalSections(), buildMsInWindow, s.instances(), s.tlasMs(), s.vramBytes() >> 20,
                s.pending(), world != null ? world.identicalSkipped() : 0L,
                world != null ? world.vegetationSections() : 0L, world != null ? world.vegetationQuads() : 0L));
        }
        if (passFramesInWindow > 0) {
            int userSpp = RtSettings.spp();
            int userBounces = RtSettings.bounces();
            double budget = RtSettings.adaptiveBudgetMs();
            RtTraceTelemetry submitted = lastTraceTelemetry;
            RtFrameRenderer.StatsMetadata metered = lastStatsMetadata;
            LOGGER.info(String.format(Locale.ROOT,
                "[VKB-RT] rt_pass ok frames=%d trace=%s output=%s trace_pixels=%d output_pixels=%d primary_samples=%d stats_trace=%s stats_sequence=%d stats_frame=%d stats_metered_pixels=%d stats_trusted_pixels=%d hit_pct=%s reject_pct=%s invalid_pct=%s trunc_pct=%s vis_overflow_pct=%s hist_pct=%s leaf_pct=%s ms=%.2f record_wall_ms=%.2f spp=%d bounces=%d work_ms=%s pass_ms=%s budget_ms=%s adaptive=%d/%d displayed=%s mode=%s",
                passFrames, submitted == null ? "n/a" : submitted.traceExtent(), submitted == null ? "n/a" : submitted.outputExtent(),
                submitted == null ? 0L : submitted.tracePixels(), submitted == null ? 0L : submitted.outputPixels(),
                submitted == null ? 0L : submitted.primarySamples(),
                metered == null ? "n/a" : metered.width() + "x" + metered.height(),
                metered == null ? -1L : metered.sequence(), metered == null ? -1 : metered.frameIndex(),
                metered == null ? 0L : RtStatsMetering.meteredPixels(metered.width(), metered.height(), metered.frameIndex()),
                lastStatsTrustedPixels,
                lastHitPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastHitPct),
                lastRejectedPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastRejectedPct),
                lastRejectSplit == null ? "n/a" : String.format(Locale.ROOT, "%.1f", lastRejectSplit.invalidPct()),
                lastRejectSplit == null ? "n/a" : String.format(Locale.ROOT, "%.1f", lastRejectSplit.truncatedPct()),
                lastRejectSplit == null ? "n/a" : String.format(Locale.ROOT, "%.1f", lastRejectSplit.overflowPct()),
                lastHistPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastHistPct),
                lastLeafPct < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", lastLeafPct),
                passMsInWindow / passFramesInWindow,
                passMsInWindow / passFramesInWindow, // ms is retained for existing log consumers; both are recording wall time.
                submitted == null ? 0 : submitted.spp(), submitted == null ? 0 : submitted.bounces(),
                // work_ms: the rays and the denoiser, i.e. what the governor compares with the budget; pass_ms: the whole RT
                // command buffer (chunk build, atmosphere, the copy), i.e. what the frame actually pays for ray tracing
                msOrNa(gpuTimer == null ? -1.0 : gpuTimer.recentMs(SPAN_RAYS, SPAN_DENOISE)),
                msOrNa(gpuTimer == null ? -1.0 : gpuTimer.recentMs(SPAN_WORLD, SPAN_ATMO)),
                budget <= 0.0 ? "off" : String.format(Locale.ROOT, "%.1f", budget),
                RtAdaptiveQuality.level(), RtAdaptiveQuality.levels(userSpp, userBounces),
                RtConfig.displayed(), RtConfig.effectiveModeName()));
        } else if (s.instances() == 0 && s.pending() > 0) {
            LOGGER.info("[VKB-RT] rt_pass waiting instances=0 pending={}", s.pending());
        }
        passMsInWindow = 0.0;
        passFramesInWindow = 0;
        lastLoggedTotal = s.totalSections();
        lastLoggedInstances = s.instances();
        builtInWindow = 0;
        buildMsInWindow = 0.0;
        windowStartNs = now;
    }
}