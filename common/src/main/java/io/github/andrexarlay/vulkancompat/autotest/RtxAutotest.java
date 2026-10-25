package io.github.andrexarlay.vulkancompat.autotest;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenarios;

/**
 * Registers the scenarios of the RTX mod in the autotest harness of Bedray Core (API 0.1.1,
 * {@code AutotestScenarios.register}, see Bedray Core API docs). Select with
 * {@code -Dvkbridge.autotest=settings}, {@code sun}, {@code upscaler_switch}, {@code perf}, {@code rt_upscaler}, {@code rt_modes}, {@code rt_light}, {@code rt_particles} or {@code iris}.
 */
public final class RtxAutotest {
    private RtxAutotest() {}

    public static void registerAll() {
        AutotestScenarios.register(new SettingsScenario());
        AutotestScenarios.register(new SettingsUiScenario());
        AutotestScenarios.register(new SettingsPreviewScenario());
        AutotestScenarios.register(new SunScenario());
        AutotestScenarios.register(new UpscalerSwitchScenario());
        AutotestScenarios.register(new RtModesScenario());
        AutotestScenarios.register(new PerfScenario());
        AutotestScenarios.register(new RtUpscalerScenario());
        AutotestScenarios.register(new LightScenario());
        AutotestScenarios.register(new LightingScenario());
        AutotestScenarios.register(new ShadowScenario());
        AutotestScenarios.register(new LookScenario());
        AutotestScenarios.register(new OverlayScenario());
        AutotestScenarios.register(new MaterialsScenario());
        AutotestScenarios.register(new FrameGenScenario());
        AutotestScenarios.register(new F3Scenario());
        AutotestScenarios.register(new AtmosphereScenario());
        AutotestScenarios.register(new ParticlesScenario());
        AutotestScenarios.register(new IrisScenario());
    }
}
