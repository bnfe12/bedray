package io.github.andrexarlay.vulkancompat.rtx.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rtx.debug.benchmark.FrameCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** A bounded capture of the player's current workload. It changes no rendering settings or resolution. */
public final class GameplayCapture {
    private static CompletableFuture<FrameBenchmark.Result> pending;
    private static long started;
    private static boolean timeoutRequested;
    private static java.nio.file.Path lastCapture;

    private GameplayCapture() {}

    public static boolean canStart() {
        var mc = Minecraft.getInstance();
        return mc != null && mc.level != null && !FrameBenchmark.busy();
    }

    public static java.nio.file.Path lastCapture() { return lastCapture; }

    /** Render thread, from the explicit settings action. Pending settings are guarded by that screen. */
    public static void start() {
        if (!canStart()) { TestReportSharing.notifyUser("Enter a world and wait for any current FPS capture to finish."); return; }
        var mc = Minecraft.getInstance();
        var output = mc.gameDirectory.toPath().resolve("bedray/profiles/capture-" + UUID.randomUUID());
        var device = RenderSystem.getDevice().getDeviceInfo();
        var config = new FrameCapture.Config(5_000_000_000L, 30_000_000_000L, 30_000, 5_000_000_000L,
            FrameCapture.WORLD | FrameCapture.SCREEN_CLOSED | FrameCapture.FOCUSED);
        pending = FrameBenchmark.start(output, config, Map.of(
            "scenario", "gameplay_profile", "fps_acceptance", "not_native_acceptance",
            "gpu", device.name(), "driver", device.driverInfo(),
            "render_distance", String.valueOf(mc.options.renderDistance().get()),
            "vsync", String.valueOf(mc.options.enableVsync().get()),
            "frame_limit", String.valueOf(mc.options.framerateLimit().get()),
            "denoiser_selected_at_start", RtFrameRenderer.denoiserSelected().name(),
            "denoiser_state_at_start", RtFrameRenderer.denoiserRuntimeState(),
            "denoiser_resolution_at_start", RtFrameRenderer.denoiserResolutionReason()));
        started = System.nanoTime();
        timeoutRequested = false;
        pending.whenComplete((saved, failure) -> mc.execute(() -> {
            if (failure == null) {
                lastCapture = saved.directory();
                TestReportSharing.export();
            }
            Component title = Component.translatableWithFallback("bedray.profile.done", "Bedray performance capture");
            String detail = failure != null ? "write_failed: " + failure
                : (saved.complete() ? "saved: " : "incomplete: " + saved.reason() + " ") + saved.directory();
            SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                title, Component.literal(detail));
        }));
        LoggerFactory.getLogger("VKB-BENCH").info("[VKB-BENCH] gameplay_profile armed directory={} warmup_s=5 duration_s=30 settings=unchanged",
            output);
        mc.gui.setScreen(null);
        TestReportSharing.notifyUser("FPS test started: 5 seconds warmup, then 30 seconds. Keep playing with the game focused; a tester ZIP will be saved.");
    }

    /** Prevent a capture waiting forever after the world unloads or RT stops producing frames. */
    static void tick(long now) {
        if (pending != null && !pending.isDone() && !timeoutRequested && now - started >= 90_000_000_000L) {
            timeoutRequested = true;
            FrameBenchmark.abort("gameplay_profile_timeout_waiting_for_world_or_capture");
        }
    }
}
