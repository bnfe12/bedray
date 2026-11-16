package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.sodium.BedrayConfigScreen;
import net.minecraft.client.gui.components.EditBox;

import java.lang.reflect.Field;

/**
 * {@code hdr_settings}: opens the Bedray settings screen from the title screen, types "HDR" into its search box (the same
 * path a player takes) and shoots the result, so the three HDR rows (output, paper white, peak) are seen in the real UI.
 * The search field is private; the scenario reaches it with reflection, nothing in the screen is changed for the test.
 */
public final class HdrSettingsScenario implements AutotestScenario {
    @Override
    public String name() {
        return "hdr_settings";
    }

    @Override
    public boolean needsWorld() {
        return false;
    }

    @Override
    public void run(AutotestContext ctx) {
        var mc = ctx.minecraft();
        mc.gui.setScreen(new BedrayConfigScreen(mc.gui.screen()));
        ctx.waitFrames(15, () -> {
            if (!(mc.gui.screen() instanceof BedrayConfigScreen screen)) {
                ctx.fail("expected BedrayConfigScreen, got " + (mc.gui.screen() == null ? "null" : mc.gui.screen().getClass().getName()), null);
                return;
            }
            try {
                Field f = BedrayConfigScreen.class.getDeclaredField("search");
                f.setAccessible(true);
                ((EditBox) f.get(screen)).setValue("HDR");
            } catch (ReflectiveOperationException e) {
                ctx.fail("cannot reach the search box: " + e, e);
                return;
            }
            ctx.waitFrames(12, () -> ctx.shot("01_hdr_settings.png", () -> {
                var w = mc.getWindow();
                screen.mouseScrolled(w.getGuiScaledWidth() * 0.5, w.getGuiScaledHeight() * 0.55, 0.0, -4.0);
                ctx.waitFrames(10, () -> ctx.shot("02_hdr_settings_scrolled.png", ctx::finish));
            }));
        });
    }
}
