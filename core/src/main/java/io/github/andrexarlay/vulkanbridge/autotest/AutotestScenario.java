package io.github.andrexarlay.vulkanbridge.autotest;

import io.github.andrexarlay.vulkanbridge.api.Experimental;

/**
 * Extension point of the autotest harness: a scenario contributed by another mod (for example the RTX mod's
 * {@code settings} and {@code sun}). Register it with {@link AutotestScenarios#register(AutotestScenario)} from the
 * mod constructor; it is selected with {@code -Dvkbridge.autotest=<name>}.
 * <p>
 * Lifecycle: the harness waits for the title screen (and, when {@link #needsWorld()}, creates the fixed test world,
 * teleports to the fixed pose and waits for the terrain), logs the environment line, then calls {@link #run}. From
 * that moment the scenario drives the flow through the {@link AutotestContext} (shots, waits) and MUST end with
 * {@link AutotestContext#finish()} or {@link AutotestContext#fail}; otherwise the harness watchdog reports a stall.
 */
@Experimental
public interface AutotestScenario {
    /** Scenario name, lower case {@code [a-z0-9_]+}; must not equal a built-in one (menu, world, inventory, soak, world_rotate). */
    String name();

    /** True (default): {@link #run} is called inside the loaded world. False: on the title screen. */
    default boolean needsWorld() {
        return true;
    }

    /** Called once when the start condition holds. Exceptions are reported as {@code result=FAIL}. */
    void run(AutotestContext ctx) throws Exception;

    /** Called on every rendered frame while the scenario is active and no shot or wait is pending. */
    default void onFrame(AutotestContext ctx) throws Exception {
    }
}
