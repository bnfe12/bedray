package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** CPU coordinate identities paired with the exact shader coordinate expressions; no GPU execution is claimed. */
class RtSlangVolumetricOriginTest {
    private static final Path LIGHT = Path.of("src/main/resources/shaders/slang/light");

    @Test void sampleVisibilityHeightAndHistoryUseTheSameCameraRelativeOrigin() throws IOException {
        String march = Files.readString(LIGHT.resolve("volumetric.slang"));
        String entry = Files.readString(LIGHT.resolve("volumetric_march_entry.slang"));
        assertTrue(march.contains("T traversal, float3 origin, float3 dir, float tEnd"));
        assertTrue(march.contains("float3 p = origin + dir * t;"));
        assertTrue(march.contains("fogHeightProfile(worldHeightOffset + p.y)"));
        assertTrue(march.contains("makeRay(p, L, rayBias, VOLUMETRIC_SHADOW_RANGE)"));
        assertTrue(entry.contains("marchLightShaft(traversal, frame.camera.origin.xyz, dir, tEnd"));
        assertTrue(entry.contains("float3 rayEnd = frame.camera.origin.xyz + dir * tEnd;"));
        assertTrue(march.contains("float4(rayEnd + worldDelta, 1.0)"));
        String host = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangVolumetricPass.java"));
        assertTrue(host.contains("putFloat(60, (float) p.cameraY)"), "height offset is raw camera height; bob belongs to the ray point");
    }

    @Test void coordinateInputDoesNotChangeScatteringOrIntegrationWeights() throws Exception {
        String source = Files.readString(LIGHT.resolve("volumetric.slang"));
        String body = block(source, "float3 marchLightShaft<")
            .replace("float3 p = origin + dir * t;", "float3 p = dir * t;");
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(body.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8)));
        assertEquals("365fb9582f155c0cf3341c09782abd883c57ca6bdbdc5c4eeb69e970f20db63b", fingerprint,
            "only the coordinate origin may change; dt, density, transmittance, phase and normalization remain pinned");
    }

    @Test void xyzBobAndCameraRebaseReprojectTheSameWorldEndpointThroughPreviousBob() {
        Vector3f origin = new Vector3f(0.2f, -0.1f, 0.35f);
        Vector3f direction = new Vector3f(0.3f, 0.4f, -0.8f).normalize();
        Vector3f currentAnchor = new Vector3f(100f, 70f, -50f);
        Vector3f previousAnchor = new Vector3f(99.5f, 69f, -48f);
        Vector3f previousBob = new Vector3f(-0.15f, 0.08f, 0.02f);
        Vector3f endpoint = new Vector3f(direction).mul(12f).add(origin);
        Vector3f delta = new Vector3f(currentAnchor).sub(previousAnchor);
        Matrix4f projectionRotation = new Matrix4f().perspective((float)Math.toRadians(70), 16f / 9f, 0.1f, 100f).rotateY(0.12f);
        Matrix4f previousViewProjection = new Matrix4f(projectionRotation).translate(new Vector3f(previousBob).negate());

        Vector4f shaderClip = previousViewProjection.transform(new Vector4f(new Vector3f(endpoint).add(delta), 1));
        Vector3f worldEndpoint = new Vector3f(currentAnchor).add(endpoint);
        Vector3f previousEye = new Vector3f(previousAnchor).add(previousBob);
        Vector4f worldReference = projectionRotation.transform(new Vector4f(worldEndpoint.sub(previousEye), 1));
        assertVectorEquals(worldReference, shaderClip, 1e-5f);
        Vector4f wrongNoBob = previousViewProjection.transform(new Vector4f(new Vector3f(direction).mul(12).add(delta), 1));
        assertTrue(shaderClip.distance(wrongNoBob) > 0.01f, "omitting current bob moves the reprojected endpoint");
    }

    @Test void heightIncludesVerticalBobExactlyOnce() {
        Vector3f origin = new Vector3f(0.1f, 0.25f, -0.2f);
        Vector3f direction = new Vector3f(0.3f, 0.4f, -0.8f).normalize();
        float distance = 12f, rawCameraHeight = 80f;
        Vector3f point = new Vector3f(direction).mul(distance).add(origin);
        assertEquals(rawCameraHeight + origin.y + direction.y * distance, rawCameraHeight + point.y, 1e-5f);
        assertEquals(origin.y, (rawCameraHeight + point.y) - (rawCameraHeight + direction.y * distance), 1e-5f);
    }

    @Test void zeroOriginPreservesEverySamplePosition() {
        Vector3f direction = new Vector3f(0.3f, 0.4f, -0.8f).normalize();
        for (float distance : new float[]{0.25f, 1f, 12f, 64f}) {
            Vector3f oldPoint = new Vector3f(direction).mul(distance);
            Vector3f newPoint = new Vector3f(direction).mul(distance).add(0, 0, 0);
            assertEquals(oldPoint, newPoint);
        }
    }

    private static void assertVectorEquals(Vector4f expected, Vector4f actual, float epsilon) {
        assertEquals(expected.x, actual.x, epsilon); assertEquals(expected.y, actual.y, epsilon);
        assertEquals(expected.z, actual.z, epsilon); assertEquals(expected.w, actual.w, epsilon);
    }
    private static String block(String source, String header) {
        int start = source.indexOf(header), begin = source.indexOf('{', start), depth = 0;
        assertTrue(start >= 0, header);
        for (int i = begin; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            else if (source.charAt(i) == '}' && --depth == 0) return source.substring(begin, i + 1);
        }
        throw new AssertionError("unclosed block " + header);
    }
}
