package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three kinds of escape see three skies. The eye and a mirror see the visible dome with the discs; a glossy reflection or
 * a transmission sees the visible dome without them (its vertex's NEE drew the sun); a diffuse bounce receives the sky
 * light. After the sky light split, rippled water - a GGX lobe, not a delta one - reflected the dim sky light and the
 * ocean of the autotest rt_look went black (2026-11-01 03:23).
 */
class RtSlangGlossyEscapeTest {
    private static final Path TRACER = Path.of("src/main/resources/shaders/slang/pathtrace/path_tracer.slang");
    private static final Path SKY = Path.of("src/main/resources/shaders/slang/light/sky.slang");

    @Test
    void aGlossyEscapeSeesTheVisibleDome() throws IOException {
        String tracer = Files.readString(TRACER).replace("\r\n", "\n");
        assertTrue(tracer.contains("float3 sky = bodiesVisible ? environment.radiance(escape)\n"
            + "                : glossyEscape ? environment.viewRadiance(escape) : environment.skyRadiance(escape);"));
        assertTrue(tracer.contains("        glossyEscape = bsdf.lobe != BSDF_LOBE_DIFFUSE;"),
            "decided by the lobe the vertex drew");
        String sky = Files.readString(SKY).replace("\r\n", "\n");
        assertTrue(sky.contains("    float3 viewRadiance(float3 direction) {\n        return dome.radiance(direction);\n    }"),
            "the sun-and-moon sky answers the visible dome without its discs");
    }
}
