package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ray that passes a cutout hole may not jump over the face right behind it. The step past a hole was the scattering
 * bias, 0.01 of a block; glow lichen sits 0.1/16 = 0.00625 off the stone it covers, so a hole in lichen put the ray inside
 * the stone, where no faces exist between solid blocks, and it left the loaded world as sky (blue holes in cave walls,
 * grey ones on a forest floor at night, owner screenshots 2026-11-01).
 */
class RtSlangHoleStepTest {
    private static final Path TRACER = Path.of("src/main/resources/shaders/slang/pathtrace/path_tracer.slang");
    private static final double LICHEN_GAP = 0.1 / 16.0;
    private static final double FAR = 512.0;   // the longest path distance the settings offer

    private static double constant(String source, String name) {
        Matcher m = Pattern.compile("static const float " + name + " = ([0-9.eE+-]+);").matcher(source);
        assertTrue(m.find(), name);
        return Double.parseDouble(m.group(1));
    }

    @Test
    void theStepClearsTheHoleButNotTheFaceBehindIt() throws IOException {
        String tracer = Files.readString(TRACER);
        double min = constant(tracer, "PATH_TRACE_HOLE_STEP_MIN");
        double perBlock = constant(tracer, "PATH_TRACE_HOLE_STEP_PER_BLOCK");
        for (double distance : new double[] {0.5, 16.0, 128.0, FAR}) {
            double step = min + distance * perBlock;
            assertTrue(step < LICHEN_GAP / 4.0, "at " + distance + " blocks the step " + step + " nears the lichen gap");
            double ulp = Math.ulp((float) distance);
            assertTrue(step > 8.0 * ulp, "at " + distance + " blocks the step " + step + " drowns in float precision");
        }
        assertTrue(0.01 > LICHEN_GAP, "the old bias was longer than the gap it jumped");
    }
}
