package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An emitter's visible face and the light it casts use the same gain (owner comparison with Bedrock RTX, 2026-11-11). */
class RtSlangEmissionConsistencyTest {
    private static float constant(String source, String name) {
        Matcher m = Pattern.compile("static const float " + name + " = ([0-9.]+);").matcher(source);
        assertTrue(m.find(), name + " is declared");
        return Float.parseFloat(m.group(1));
    }

    @Test
    void theFaceIsAsBrightAsItsLight() throws IOException {
        String emitters = Files.readString(Path.of("src/main/resources/shaders/slang/light/block_emitters.slang"));
        String surface = Files.readString(Path.of("src/main/resources/shaders/slang/surface/surface.slang"));
        float light = constant(emitters, "EMISSIVE_GAIN") * constant(emitters, "EMISSIVE_FLUX_GAIN");
        assertEquals(light * constant(surface, "SURFACE_FACE_RATIO"), constant(surface, "SURFACE_EMISSION_GAIN"), 1.0e-3f,
            "the camera sees an emitter face at a fixed share of the radiance its NEE carries");
        assertTrue(surface.contains("options.emissionScale = SURFACE_EMISSION_GAIN;"), "and the default options use it");
    }
}
