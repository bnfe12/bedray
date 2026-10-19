package io.github.andrexarlay.vulkanbridge.autotest;

import io.github.andrexarlay.vulkanbridge.api.Experimental;
import net.minecraft.client.Minecraft;

/** What the harness offers to an {@link AutotestScenario}. All methods must be called on the render thread. */
@Experimental
public interface AutotestContext {
    Minecraft minecraft();

    /** Rendered frames since the harness was armed. */
    long frames();

    /** Logs {@code [VKB-AUTOTEST] key=value ...} from alternating key, value arguments (odd count: the last one is dropped). */
    void log(String... keyValuePairs);

    /** Screenshot of the main render target to {@code <gameDir>/autotest/<scenario>/<fileName>}; {@code then} runs after the file exists. */
    void shot(String fileName, Runnable then);

    /** Waits {@code n} rendered frames, then runs {@code then}. */
    void waitFrames(int n, Runnable then);

    /** Ends the scenario successfully ({@code result=PASS}) and stops the game. */
    void finish();

    /** Ends the scenario with {@code result=FAIL error=<reason>} and stops the game. */
    void fail(String reason, Throwable cause);
}
