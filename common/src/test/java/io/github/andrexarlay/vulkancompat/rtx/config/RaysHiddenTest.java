package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ray Tracing (Fast) is hidden in the public release: a saved rt_mode=rays becomes path unless experimental_rays is set. */
class RaysHiddenTest {
    @Test
    void onlyOffAndPathArePublic() {
        assertTrue(RtMode.OFF.isPublic());
        assertTrue(RtMode.PATH_TRACED.isPublic());
        assertFalse(RtMode.RAY_TRACED.isPublic());
        assertFalse(RtMode.AO.isPublic());
        assertFalse(RtMode.DIRECT.isPublic());
    }

    @Test
    void savedRaysModeMovesToPathWithoutTheFlag() {
        RtMode old = VulkanRtxConfig.rtMode;
        boolean oldFlag = VulkanRtxConfig.experimentalRays;
        boolean oldHw = VulkanRtxConfig.enableHardwareRayTracing;
        try {
            VulkanRtxConfig.experimentalRays = false;
            VulkanRtxConfig.applyKeyValue("rt_mode", "rays");
            assertEquals(RtMode.RAY_TRACED, VulkanRtxConfig.rtMode);   // parsing alone does not migrate
            assertTrue(VulkanRtxConfig.migrateHiddenRtMode());
            assertEquals(RtMode.PATH_TRACED, VulkanRtxConfig.rtMode);
            assertFalse(VulkanRtxConfig.migrateHiddenRtMode());   // already public

            VulkanRtxConfig.experimentalRays = true;
            VulkanRtxConfig.applyKeyValue("rt_mode", "rays");
            assertFalse(VulkanRtxConfig.migrateHiddenRtMode());
            assertEquals(RtMode.RAY_TRACED, VulkanRtxConfig.rtMode);

            VulkanRtxConfig.applyKeyValue("experimental_rays", "false");
            assertFalse(VulkanRtxConfig.experimentalRays);
        } finally {
            VulkanRtxConfig.rtMode = old;
            VulkanRtxConfig.experimentalRays = oldFlag;
            VulkanRtxConfig.enableHardwareRayTracing = oldHw;
        }
    }

    /**
     * A file that predates rt_mode names no mode at all: {@code enable_hardware_ray_tracing} said "trace" and the renderer
     * of that release happened to be the ray one. The ray pass no longer exists, so such a file has to open the renderer
     * that does rather than the frame refusal of SlangPathUnavailableTest.
     */
    @Test
    void legacyOnOffKeyOpensTheRendererThatExists() throws Exception {
        RtMode oldMode = VulkanRtxConfig.rtMode;
        boolean oldHw = VulkanRtxConfig.enableHardwareRayTracing;
        java.io.File cfg = java.io.File.createTempFile("bedray_legacy_rt", ".toml");
        try {
            java.nio.file.Files.writeString(cfg.toPath(), "enable_hardware_ray_tracing = true\n");
            VulkanRtxConfig.setConfigFileForTesting(cfg);
            VulkanRtxConfig.load();
            assertEquals(RtMode.PATH_TRACED, VulkanRtxConfig.effectiveRtMode(), "the on/off key opens the path renderer");
            assertTrue(VulkanRtxConfig.enableHardwareRayTracing, "and it does turn tracing on");

            java.nio.file.Files.writeString(cfg.toPath(), "enable_hardware_ray_tracing = false\n");
            VulkanRtxConfig.load();
            assertEquals(RtMode.OFF, VulkanRtxConfig.effectiveRtMode(), "false still means off");
        } finally {
            VulkanRtxConfig.setConfigFileForTesting(new java.io.File("config/bedray.toml"));
            cfg.delete();
            VulkanRtxConfig.rtMode = oldMode;
            VulkanRtxConfig.enableHardwareRayTracing = oldHw;
        }
    }

    /**
     * A scan, not a call: {@code PerfScenario.run()} needs a running game, and a chooser is the kind of line that silently
     * walks back to the deleted renderer whenever somebody adds a mode. The last two assertions are the whole rule - the
     * files that decide which renderer a trace request gets must not name the mode the Slang renderer refuses
     * ({@code slang_ray}).
     */
    @Test
    void theTraceChoosersNeverNameTheRefusedMode() throws Exception {
        String config = read("src/main/java/io/github/andrexarlay/vulkancompat/rtx/config/VulkanRtxConfig.java");
        String perf = read("src/main/java/io/github/andrexarlay/vulkancompat/autotest/PerfScenario.java");
        assertTrue(config.contains("return RtMode.PATH_TRACED;\n        }\n        return rtMode;"),
            "effectiveRtMode's boolean-only fallback names the path renderer");
        assertTrue(config.contains("setRtMode(Boolean.parseBoolean(val) ? RtMode.PATH_TRACED : RtMode.OFF);"),
            "the legacy on/off toml key names the path renderer");
        assertTrue(perf.contains("VulkanRtxConfig.rtMode = WITH_RT ? RtMode.PATH_TRACED : RtMode.OFF;"),
            "the perf autotest's perfRt flag names the path renderer");
        assertFalse(config.contains("RtMode.RAY_TRACED"),
            "VulkanRtxConfig must not hand out the mode the renderer refuses");
        assertFalse(perf.contains("RtMode.RAY_TRACED"),
            "PerfScenario must not hand out the mode the renderer refuses");
    }

    private static String read(String relativeToCommon) throws java.io.IOException {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relativeToCommon)).replace("\r\n", "\n");
    }
}
