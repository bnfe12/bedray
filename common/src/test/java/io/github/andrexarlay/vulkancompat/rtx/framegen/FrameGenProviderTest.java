package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenProvider.Active;
import io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3.FfxFgNative;
import io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3.Fsr3Queues;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** L2: provider choice, queue planning and ffx-api struct layouts of the FSR 3 frame generation (no GPU, no DLL). */
public class FrameGenProviderTest {
    @Test
    public void autoPicksNvidiaOnlyWithDriverAndStreamline() {
        assertEquals(Active.NVIDIA, FrameGenProvider.decide(FrameGenSettings.Provider.AUTO, false, true, true));
        assertEquals(Active.AMD, FrameGenProvider.decide(FrameGenSettings.Provider.AUTO, false, true, false));
        assertEquals(Active.AMD, FrameGenProvider.decide(FrameGenSettings.Provider.AUTO, false, false, true));
        assertEquals(Active.AMD, FrameGenProvider.decide(FrameGenSettings.Provider.AMD, false, true, true));
        assertEquals(Active.NVIDIA, FrameGenProvider.decide(FrameGenSettings.Provider.NVIDIA, false, false, false));
        assertEquals(Active.NONE, FrameGenProvider.decide(FrameGenSettings.Provider.AMD, true, true, true));
    }

    @Test
    public void parsesProvider() {
        assertEquals(FrameGenSettings.Provider.AMD, FrameGenSettings.parseProvider("fsr3"));
        assertEquals(FrameGenSettings.Provider.NVIDIA, FrameGenSettings.parseProvider("\"NVIDIA\""));
        assertEquals(FrameGenSettings.Provider.AUTO, FrameGenSettings.parseProvider("auto"));
        assertNull(FrameGenSettings.parseProvider("xyz"));
        assertNull(FrameGenSettings.parseProvider(null));
    }

    @Test
    public void queuePlanAddsTwoQueuesOnlyWhenTheFamilyHasThem() {
        Int2IntMap current = new Int2IntArrayMap();
        current.put(0, 1);
        current.put(2, 1);
        Int2IntMap planned = Fsr3Queues.plan(current, 0, 16, 2);
        assertEquals(3, planned.get(0));
        assertEquals(1, planned.get(2));
        assertEquals(1, current.get(0));
        assertNull(Fsr3Queues.plan(current, 0, 2, 2));
        assertNull(Fsr3Queues.plan(current, -1, 16, 2));
    }

    @Test
    public void ffxLayoutsMatchTheHeaders() {
        assertDoesNotThrow(FfxFgNative::verifyLayouts);
    }
}
