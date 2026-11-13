package io.github.andrexarlay.vulkancompat.rtx.debug;

import io.github.andrexarlay.vulkancompat.BuildIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Explicit tester actions: export locally, or open GitHub's review form in the user's browser. */
public final class TestReportSharing {
    private static boolean preparing;
    private TestReportSharing() {}

    public static void export() { prepare(false); }
    public static void share() { prepare(true); }

    private static void prepare(boolean openBrowser) {
        if (preparing) { notifyUser("A tester report is already being prepared."); return; }
        if (FrameBenchmark.busy()) {
            notifyUser("FPS capture is still running. Wait for its result before preparing the report.");
            return;
        }
        preparing = true;
        Minecraft mc = Minecraft.getInstance();
        var report = Diagnostics.generateAndCopy();
        var game = mc.gameDirectory.toPath();
        var scrubber = Diagnostics.scrubber(game.toAbsolutePath().normalize());
        Path capture = GameplayCapture.lastCapture();
        String hardware;
        try {
            var device = com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo();
            hardware = device.name() + "; " + device.driverInfo() + "; " + System.getProperty("os.name");
        } catch (RuntimeException failure) { hardware = "GPU information unavailable: " + failure.getClass().getSimpleName(); }
        String cleanHardware = scrubber.scrubIdentity(hardware);
        String build = BuildIdentity.current().display() + "; Minecraft " + net.minecraft.SharedConstants.getCurrentVersion().name()
            + "; " + io.github.andrexarlay.vulkanbridge.platform.Platforms.get().loaderName();
        CompletableFuture.supplyAsync(() -> {
            try {
                Path zip = TestReportBundle.write(game, report.text(), capture, scrubber);
                String performance = capture != null && Files.isRegularFile(capture.resolve("summary.txt"))
                    ? scrubber.scrubIdentity(Files.readString(capture.resolve("summary.txt"))) : "No FPS capture yet; run /bedray test.";
                return new Prepared(zip, TestReportBundle.issueUri(cleanHardware, build, performance, zip.getFileName().toString()));
            } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }).whenComplete((prepared, failure) -> mc.execute(() -> {
            preparing = false;
            if (failure != null) {
                LoggerFactory.getLogger("VKB-DIAG").error("[VKB-DIAG] tester report export failed", failure);
                notifyUser("Tester report export failed. See latest.log; no issue was submitted.");
                return;
            }
            notifyUser("Tester ZIP saved: bedray/reports/" + prepared.zip().getFileName());
            if (openBrowser) {
                try {
                    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                        throw new UnsupportedOperationException("Browser opening is unavailable");
                    Desktop.getDesktop().browse(prepared.issue());
                    com.mojang.blaze3d.Blaze3D.openPath(prepared.zip().getParent());
                    notifyUser("Review the GitHub form, attach the ZIP and submit. The issue has not been posted yet.");
                } catch (Exception openFailure) {
                    LoggerFactory.getLogger("VKB-DIAG").warn("[VKB-DIAG] cannot open GitHub/report folder", openFailure);
                    notifyUser("Open https://github.com/bnfe12/bedray/issues and attach the saved ZIP. Diagnostics are in the clipboard.");
                }
            }
        }));
    }

    private record Prepared(Path zip, java.net.URI issue) {}

    static void notifyUser(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Bedray] " + text));
        LoggerFactory.getLogger("VKB-DIAG").info("[VKB-DIAG] {}", text);
    }
}
