package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rtx.debug.GameplayCapture;
import java.nio.file.Files;

/** Exercises the same explicit FPS/export actions a tester uses. Never opens a browser or submits an issue. */
public final class FriendTestScenario implements AutotestScenario {
    @Override public String name() { return "friend_test"; }

    @Override public void run(AutotestContext ctx) {
        ScenarioTime.waitSeconds(ctx, 10, () -> {
            GameplayCapture.start();
            ScenarioTime.waitUntil(ctx, () -> GameplayCapture.lastCapture() != null, 100,
                () -> saved(ctx), () -> ctx.fail("Tester FPS capture did not finish", null));
        });
    }

    private void saved(AutotestContext ctx) {
        try {
            var capture = GameplayCapture.lastCapture();
            var manifest = com.google.gson.JsonParser.parseString(Files.readString(capture.resolve("manifest.json"))).getAsJsonObject();
            var summary = manifest.getAsJsonObject("summary");
            ctx.log("scenario", name(), "event", "capture_saved", "complete", manifest.get("capture_complete").getAsString(),
                "frames", summary.get("measured_frames").getAsString());
            if (!manifest.get("capture_complete").getAsBoolean() || !summary.has("average_fps")) {
                ctx.fail("Tester FPS measurement is incomplete", null);
                return;
            }
            ScenarioTime.waitSeconds(ctx, 3, () -> ctx.shot("01_tester_world.png", ctx::finish));
        } catch (Exception failure) { ctx.fail("Tester FPS/export verification failed", failure); }
    }
}
