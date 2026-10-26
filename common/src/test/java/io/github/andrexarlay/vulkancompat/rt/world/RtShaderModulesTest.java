package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source check for the rt_common.glsl umbrella (the former 2748-line file, split into include modules).
 * The resolved expansion of the umbrella must contain every module exactly once, in umbrella order, and
 * nothing else: rt_rays.comp / rt_path.comp / rt_atrous.comp / rt_volumetric.comp still include only
 * rt_common.glsl, and the include-once resolver must not shadow a module (a repeated include is silently
 * skipped by the resolver, so a wrong umbrella fails here instead of at shaderc with a missing symbol).
 */
public class RtShaderModulesTest {
    private static final String[] MODULES = {
        "rt_common_core.glsl", "rt_tonemap.glsl", "rt_sky.glsl", "rt_common_light.glsl",
        "rt_common_sampling.glsl", "rt_common_surface.glsl", "rt_common_pixel_shadow.glsl",
        "rt_common_emitters.glsl", "rt_common_temporal.glsl", "rt_common_water.glsl", "rt_common_shade.glsl"};

    @Test
    public void umbrellaExpandsToModulesInOrder() {
        String umbrella = RtShaderCompiler.loadSource("/shaders/rt_common.glsl");
        String expanded = RtShaderCompiler.resolveIncludes(umbrella, "/shaders/rt_common.glsl");
        // the resolver leaves each include line's terminator unconsumed: "\n", or "\r\n" in a CRLF checkout
        String[] lines = umbrella.split("\n", -1);
        int nonIncludeBytes = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("#include")) {
                nonIncludeBytes += lines[i].endsWith("\r") ? 2 : 1;
                continue;
            }
            nonIncludeBytes += lines[i].length() + (i < lines.length - 1 ? 1 : 0);
        }
        int sum = nonIncludeBytes;
        int pos = -1;
        for (String m : MODULES) {
            String content = RtShaderCompiler.resolveIncludes(
                RtShaderCompiler.loadSource("/shaders/" + m), "/shaders/" + m);
            int at = expanded.indexOf(content, pos + 1);
            assertTrue(at >= 0, m + " is missing from the umbrella expansion or out of order");
            pos = at;
            sum += content.length();
        }
        assertEquals(sum, expanded.length(), "expansion must contain the modules exactly once and nothing else");
    }
}
