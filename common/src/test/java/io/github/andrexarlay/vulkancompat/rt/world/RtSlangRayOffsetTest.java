package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every secondary ray leaves its surface exactly one {@code rayBias} away, along the face it left. A bounce biased
 * along its own direction stays inside the triangle at grazing angles and self-intersects; a shadow ray whose origin
 * is shifted <em>and</em> whose {@code tMin} is the same bias starts its tests twice as far away, so light arrives
 * through carpets, glass and slabs.
 * <p>
 * Ways this silently breaks: the bounce offset moves back onto {@code bsdf.direction} (black fireflies along water and
 * ice), the bias is applied to the shading normal instead of the geometric face (a normal-mapped tilt can push the
 * origin behind the triangle), a wrapper adds {@code tMin} on top of an already-offset origin, or an offset is dropped
 * entirely and every ray hits the surface it came from.
 */
class RtSlangRayOffsetTest {

    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String TRACER = SHADERS + "/pathtrace/path_tracer.slang";
    private static final String VISIBILITY = SHADERS + "/surface/visibility.slang";

    @Test
    void theBounceLeavesAlongTheGeometricFace() {
        String source = read(TRACER);
        String bounce = statementBlock(source, "float3 origin = hit.position");
        assertTrue(bounce.contains("float3 origin = hit.position\n"),
            "the origin is the hit point plus one offset, nothing else");
        assertTrue(source.contains("float3 bounceNormal = safeNormalize(surface.hit.geometricNormal, normal);"),
            "a normal-mapped shading normal can tilt past the face; the offset uses the geometric one, like the "
                + "shadow ray does");
        assertTrue(bounce.contains("bounceNormal * (dot(bsdf.direction, bounceNormal) >= 0.0 ? options.rayBias"
                + " : -options.rayBias)"),
            "one bias, on the hemisphere the outgoing ray actually left through");
        assertTrue(!bounce.contains("bsdf.direction * options.rayBias"),
            "biasing along the ray keeps the bounce inside the triangle at grazing angles");
        assertTrue(source.contains("ray = makeRay(origin, bsdf.direction, 0.0, options.maxDistance);"),
            "tMin stays zero: the origin already carries the only offset");
    }

    @Test
    void aShadowRayIsOffsetExactlyOnce() {
        String source = read(VISIBILITY);
        for (String header : new String[] {"SurfaceVisibility traceShadowVisibility<T : ISceneTracer>(",
            "SurfaceVisibility traceShadowVisibilityMedium<T : ISceneTracer>("}) {
            String wrapper = block(source, header);
            assertTrue(wrapper.contains("float3 origin = surfacePosition + normal * bias;"),
                header + ": the receiver's surface is left behind by exactly one bias");
            assertTrue(wrapper.contains("Ray ray = makeRay(origin, direction, 0.0, tMax);"),
                header + ": a tMin of the same bias would start the test at twice the offset and let light through "
                    + "thin partitions");
            assertTrue(!wrapper.contains("makeRay(origin, direction, bias,"),
                header + ": the origin shift and a bias tMin together are a double offset");
        }
    }

    /** The three lines that build the bounce origin, up to the ray that consumes it. */
    private static String statementBlock(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
        int end = source.indexOf("makeRay(origin", at);
        assertTrue(end > at, "no ray consumes the origin of " + header);
        return source.substring(at, end);
    }

    private static String block(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block: " + header);
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
