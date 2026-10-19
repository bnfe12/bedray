package io.github.andrexarlay.vulkancompat.rtx.framegen;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Locale;

/**
 * F3 lines of frame generation (entry id bedray:framegen, registered by MixinDebugScreenEntriesFrameGen): mode and multiplier,
 * rendered/presented fps, Reflex latency, Streamline and driver status. All numbers come from {@link FrameGenRuntime#snapshot()}.
 * Interface and group call as in {@code VkbDebugEntry} (DebugScreenEntry.display(DebugScreenDisplayer, Level, LevelChunk, LevelChunk)).
 */
public final class FrameGenDebugEntry implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("bedray", "framegen");

    @Override
    public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
        FrameGenRuntime.Snapshot s = FrameGenRuntime.snapshot();
        FrameGenSettings.Mode m = FrameGenSettings.mode();
        String lat = s.latencyUs() < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f ms", s.latencyUs() / 1000.0);
        FrameGenRuntime.State state = s.status().state();
        String applied = state == FrameGenRuntime.State.ACTIVE || state == FrameGenRuntime.State.ENGAGING
            ? s.multiplier() + "x" : "off";
        displayer.addToGroup(ID, List.of(
            "[Frame Generation] " + FrameGenRuntime.statusLine(),
            String.format(Locale.ROOT, "FG requested %s  applied %s (max x%d)  Reflex %s  cap %s", m.label(), applied, s.maxMultiplier(),
                FrameGenSettings.reflex(), FrameGenSettings.frameLimitFps() == 0 ? "off" : FrameGenSettings.frameLimitFps() + " fps"),
            String.format(Locale.ROOT, "FG rendered %.0f fps / presented %.0f fps  latency %s  gpu %.2f ms", s.fpsRendered(), s.fpsPresented(), lat,
                Math.max(0, s.gpuFrameUs()) / 1000.0),
            "Streamline runtime " + ("loaded".equals(s.streamline()) ? "initialized" : "not initialized") + "  driver required " + s.driver()
                + "  frames " + s.status().renderedFrames() + "/" + s.status().presentedFrames()));
    }

    @Override
    public boolean isAllowed(boolean reducedDebugInfo) {
        return true;
    }
}
