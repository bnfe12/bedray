package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.shaderhost.customrt.MosaicStudioScreen;
import io.github.andrexarlay.vulkancompat.shaderhost.customrt.StudioLauncher;

/**
 * {@code mosaic_studio}: opens Mosaic Studio inside the game exactly as the J key does, keeps it open
 * ({@code -Dvkbridge.autotest.studioFrames}, default 900 frames) and logs whether the Studio process is alive and
 * the game's Studio screen is up, then quits (the Studio process follows when the game's pipe closes).
 */
public final class MosaicStudioScenario implements AutotestScenario {
    @Override
    public String name() {
        return "mosaic_studio";
    }

    @Override
    public void run(AutotestContext ctx) {
        StudioLauncher.Result r = MosaicStudioScreen.open();
        ctx.log("scenario", name(), "phase", "open", "result", r.name());
        int frames = Integer.getInteger("vkbridge.autotest.studioFrames", 900);
        ctx.waitFrames(frames, () -> {
            boolean screen = ctx.minecraft().gui.screen() instanceof MosaicStudioScreen;
            ctx.log("scenario", name(), "phase", "held", "studio_alive", String.valueOf(StudioLauncher.running()),
                "game_screen", String.valueOf(screen));
            if (!StudioLauncher.running()) ctx.fail("mosaic_studio: Studio process is not running", null);
            else ctx.finish();
        });
    }
}
