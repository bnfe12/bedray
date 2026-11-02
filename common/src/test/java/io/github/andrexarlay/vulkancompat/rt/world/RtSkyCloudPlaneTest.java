package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** Analytic plane reference and live shader contract; no GPU/image claim. */
class RtSkyCloudPlaneTest {
    private static final Path SOURCE = Path.of("src/main/resources/shaders/slang/light/sky_clouds.slang");

    private static float distance(float origin, float direction, float far) {
        if (!Float.isFinite(origin) || !Float.isFinite(direction) || !Float.isFinite(far) || direction == 0) return Float.NaN;
        float t = -origin / direction;
        return Float.isFinite(t) && t >= 0 && t < far ? t : Float.NaN;
    }

    @Test void planeBehindTheRayCannotTurnIntoACloudAtTheCamera() {
        assertTrue(Float.isNaN(distance(10, 1, 100)));
        assertTrue(Float.isNaN(distance(-10, -1, 100)));
        assertEquals(10, distance(10, -1, 100));
        assertEquals(10, distance(-10, 1, 100));
        assertEquals(0, distance(0, 1, 100), 0.0); // both IEEE zero signs are the same plane crossing
        assertEquals(0, distance(0, -1, 100), 0.0);
    }

    @Test void parallelNearParallelAndFarEndpointHaveDistinctGeometricMeaning() {
        for (float origin : new float[]{-10, 0, 10}) {
            assertTrue(Float.isNaN(distance(origin, 0f, 100)));
            assertTrue(Float.isNaN(distance(origin, -0f, 100)));
        }
        assertEquals(100, distance(-0.0001f, 0.000001f, 200), 1e-4);
        assertTrue(Float.isNaN(distance(-100, 1, 100)));
        assertTrue(distance(-Math.nextDown(100f), 1, 100) < 100);
        assertTrue(Float.isNaN(distance(Float.NaN, 1, 100)));
        assertTrue(Float.isNaN(distance(1, Float.POSITIVE_INFINITY, 100)));
        assertTrue(Float.isNaN(distance(1, Float.MIN_VALUE, 100)));
        assertEquals(0.378, 0.7 * 0.6 * (1 - distance(-10, 1, 100) / 100), 1e-7);
    }

    @Test void liveFastPathRejectsItsIntervalBeforeCollapsingItAndKeepsFancyDdaAndShading() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("distance = -originY / directionY;"));
        assertTrue(source.contains("!isfinite(originY) || !isfinite(directionY) || !isfinite(fadeEnd) || directionY == 0.0"));
        assertTrue(source.contains("return isfinite(distance) && distance >= 0.0 && distance < fadeEnd;"));
        int plane = source.indexOf("if (!skyFastCloudDistance(o.y, d.y, fadeEnd, t0)) return float4(0.0);");
        assertTrue(plane >= 0 && plane < source.indexOf("t1 = t0;"));
        assertTrue(source.contains("} else if (abs(d.y) < 1.0e-5)"), "the FANCY slab keeps its own parallel policy");
        String tail = source.substring(source.indexOf("// 2D DDA"))
            .replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "").replaceAll("\\s+", "");
        assertEquals("43b056eadf0fd7eeddc4837f96e3d2ca802c18220393c80def64740fa1573792",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tail.getBytes(StandardCharsets.UTF_8))),
            "the original cloud-cell walk, texel shading and fade equations remain unchanged");
    }
}
