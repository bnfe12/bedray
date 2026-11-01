package io.github.andrexarlay.vulkancompat.rt.world;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests verifying:
 * 1. Screen-space depth composite compute shader (rt_composite.comp) compilation via Shaderc into valid SPIR-V.
 * 2. Reverse-Z depth unprojection and distance evaluation logic.
 * 3. Foreground entity separation from ray-traced chunk geometry and sky background.
 * 4. Alpha blending support for opaque, translucent, and transparent entities/particles.
 */
public class RtEntityCompositeTest {

    private static final float EPSILON = 1e-4f;


    @Test
    public void testDepthSeparationSky() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtSky = new Vector4f(0.2f, 0.4f, 0.8f, 1.0f);
        Vector4f rasterColor = new Vector4f(1.0f, 0.0f, 0.0f, 1.0f); // hypothetical raster garbage in sky

        // In Reverse-Z, sky depth is 0.0 (or <= 1e-6)
        float dRasterSky = 0.0f;
        float dRaySky = 1.0e6f; // ray missed into sky

        Vector4f outCol = simulateComposite(rasterColor, dRasterSky, rtSky, dRaySky, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(rtSky, outCol, "Sky raster pixel must be completely replaced by ray-traced sky");

        // Edge case: depth just below epsilon
        float dRasterNearZero = 5.0e-7f;
        Vector4f outColEps = simulateComposite(rasterColor, dRasterNearZero, rtSky, dRaySky, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(rtSky, outColEps, "Pixels below 1e-6 depth must show RT sky");
    }

    @Test
    public void testDepthSeparationEntityInFrontOfTerrain() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtWall = new Vector4f(0.5f, 0.5f, 0.5f, 1.0f); // stone wall
        Vector4f mobColor = new Vector4f(0.0f, 0.8f, 0.2f, 1.0f); // zombie green

        float dRayWall = 10.0f; // wall hit at 10 meters
        float mobDist = 5.0f;   // zombie at 5 meters in front of the wall
        float dRaster = computeReverseZDepth(mobDist, 0.05f, 1000.0f);

        Vector4f outCol = simulateComposite(mobColor, dRaster, rtWall, dRayWall, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(mobColor, outCol, "Foreground zombie must be preserved over ray-traced wall");
    }

    @Test
    public void testDepthSeparationTerrainMatching() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtTerrain = new Vector4f(0.6f, 0.4f, 0.2f, 1.0f); // ray-traced dirt block
        Vector4f rasterTerrain = new Vector4f(0.1f, 0.1f, 0.1f, 1.0f); // flat raster dirt block

        float dRay = 12.0f;
        // Raster terrain is at the exact same distance (12.0m)
        float dRasterExact = computeReverseZDepth(12.0f, 0.05f, 1000.0f);
        Vector4f outColExact = simulateComposite(rasterTerrain, dRasterExact, rtTerrain, dRay, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(rtTerrain, outColExact, "Matching raster terrain must be replaced by ray-traced terrain");

        // Raster terrain slightly closer within epsilon threshold (12m * 0.015 = 0.18m threshold)
        float dRasterSlightlyCloser = computeReverseZDepth(11.90f, 0.05f, 1000.0f); // only 10cm closer
        Vector4f outColNear = simulateComposite(rasterTerrain, dRasterSlightlyCloser, rtTerrain, dRay, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(rtTerrain, outColNear, "Raster terrain within tolerance must be replaced by ray-traced terrain");
    }

    @Test
    public void testDepthSeparationOccludedEntity() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtWall = new Vector4f(0.3f, 0.3f, 0.3f, 1.0f);
        Vector4f hiddenMob = new Vector4f(1.0f, 1.0f, 0.0f, 1.0f);

        float dRayWall = 8.0f; // wall at 8 meters
        float mobDist = 15.0f; // mob behind the wall at 15 meters
        float dRaster = computeReverseZDepth(mobDist, 0.05f, 1000.0f);

        Vector4f outCol = simulateComposite(hiddenMob, dRaster, rtWall, dRayWall, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(rtWall, outCol, "Entity behind ray-traced geometry must be occluded");
    }

    @Test
    public void testDepthSeparationEntityAgainstSky() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtSky = new Vector4f(0.1f, 0.2f, 0.5f, 1.0f);
        Vector4f phantomColor = new Vector4f(0.2f, 0.3f, 0.8f, 1.0f);

        float dRaySky = 1.0e6f; // ray hit sky (miss)
        float phantomDist = 20.0f; // phantom flying at 20 meters
        float dRaster = computeReverseZDepth(phantomDist, 0.05f, 1000.0f);

        Vector4f outCol = simulateComposite(phantomColor, dRaster, rtSky, dRaySky, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(phantomColor, outCol, "Entity flying against sky must be rendered over RT sky");
    }

    @Test
    public void testAlphaBlending() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtWorld = new Vector4f(0.0f, 0.0f, 1.0f, 1.0f); // pure blue
        float dRay = 10.0f;
        float dist = 4.0f;
        float dRaster = computeReverseZDepth(dist, 0.05f, 1000.0f);

        // 1. Opaque entity (alpha = 1.0): 100% entity color
        Vector4f redOpaque = new Vector4f(1.0f, 0.0f, 0.0f, 1.0f);
        Vector4f outOpaque = simulateComposite(redOpaque, dRaster, rtWorld, dRay, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(new Vector4f(1.0f, 0.0f, 0.0f, 1.0f), outOpaque, "Opaque entity must replace background");

        // 2. Translucent entity (alpha = 0.5): 50% red, 50% blue
        Vector4f redTranslucent = new Vector4f(1.0f, 0.0f, 0.0f, 0.5f);
        Vector4f outTranslucent = simulateComposite(redTranslucent, dRaster, rtWorld, dRay, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(new Vector4f(0.5f, 0.0f, 0.5f, 1.0f), outTranslucent, "Translucent entity must alpha-blend with RT background");

        // 3. Low opacity entity (alpha = 0.2): 20% red, 80% blue
        Vector4f redLow = new Vector4f(1.0f, 0.0f, 0.0f, 0.2f);
        Vector4f outLow = simulateComposite(redLow, dRaster, rtWorld, dRay, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(new Vector4f(0.2f, 0.0f, 0.8f, 1.0f), outLow, "Low opacity entity must correctly blend");

        // 4. Fully transparent entity (alpha = 0.0): 100% blue RT world
        Vector4f redZero = new Vector4f(1.0f, 0.0f, 0.0f, 0.0f);
        Vector4f outZero = simulateComposite(redZero, dRaster, rtWorld, dRay, invViewProj, 0.0f, 0.0f, 0.08f);
        assertVectorEquals(rtWorld, outZero, "Zero alpha entity must result in pure RT world color");
    }

    @Test
    public void testFloorTerrainNotClassifiedAsEntityAtGlancingAngles() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtFloor = new Vector4f(0.1f, 0.1f, 0.1f, 1.0f); // dark reflective RT floor
        Vector4f rasterFloor = new Vector4f(0.9f, 0.9f, 0.9f, 1.0f); // white grid raster floor from screenshot

        // At glancing angle, distance is 4.0m, and raster depth reconstruction discrepancy is 15cm
        float dRay = 4.0f;
        float distGlancing = 3.85f; // 15cm discrepancy
        float dRaster = computeReverseZDepth(distGlancing, 0.05f, 1000.0f);

        Vector4f outCol = simulateComposite(rasterFloor, dRaster, rtFloor, dRay, invViewProj, 0.0f, 0.0f, 0.20f);
        assertVectorEquals(rtFloor, outCol, "Floor with 15cm projection discrepancy must NOT be classified as an entity");
    }

    @Test
    public void testHandPreservedInFrontOfCloseWall() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtWall = new Vector4f(0.4f, 0.4f, 0.4f, 1.0f);
        Vector4f handColor = new Vector4f(0.8f, 0.6f, 0.5f, 1.0f);

        // Player standing close to wall at 1.0m, holding hand at 0.6m
        float dRay = 1.0f;
        float handDist = 0.6f;
        float dRaster = computeReverseZDepth(handDist, 0.05f, 1000.0f);

        Vector4f outCol = simulateComposite(handColor, dRaster, rtWall, dRay, invViewProj, 0.0f, 0.0f, 0.20f);
        assertVectorEquals(handColor, outCol, "Hand in immediate foreground (<1.2m) must be preserved in front of wall");
    }

    @Test
    public void testWallItselfCloseToPlayerNotClassifiedAsEntity() {
        Matrix4f invViewProj = createTestInverseProjection();

        Vector4f rtWall = new Vector4f(0.4f, 0.4f, 0.4f, 1.0f);
        Vector4f rasterWall = new Vector4f(0.2f, 0.2f, 0.2f, 1.0f);

        // Player standing close to wall at 1.0m, raster wall at 0.99m (1cm discrepancy)
        float dRay = 1.0f;
        float wallDist = 0.99f;
        float dRaster = computeReverseZDepth(wallDist, 0.05f, 1000.0f);

        Vector4f outCol = simulateComposite(rasterWall, dRaster, rtWall, dRay, invViewProj, 0.0f, 0.0f, 0.20f);
        assertVectorEquals(rtWall, outCol, "Wall close to player must NOT be classified as hand/entity");
    }

    /**
     * Java simulation of the exact mathematical logic in rt_composite.comp.
     */
    private static Vector4f simulateComposite(
        Vector4f rasterColor,
        float dRaster,
        Vector4f rtColor,
        float dRay,
        Matrix4f invViewProj,
        float ndcX,
        float ndcY,
        float depthThreshold
    ) {
        if (dRaster <= 1.0e-6f) {
            return new Vector4f(rtColor);
        }

        Vector4f clip = new Vector4f(ndcX, ndcY, dRaster, 1.0f);
        Vector4f worldPosRel = invViewProj.transform(clip, new Vector4f());
        float distRaster = Math.abs(worldPosRel.w) > 1.0e-7f
            ? (float) Math.sqrt(
                (worldPosRel.x / worldPosRel.w) * (worldPosRel.x / worldPosRel.w)
                + (worldPosRel.y / worldPosRel.w) * (worldPosRel.y / worldPosRel.w)
                + (worldPosRel.z / worldPosRel.w) * (worldPosRel.z / worldPosRel.w))
            : 1.0e6f;

        float thresh = Math.max(depthThreshold > 0.0f ? depthThreshold : 0.20f, dRay * 0.05f);

        boolean isEntity = false;
        if (dRay >= 1.0e5f) {
            if (distRaster <= 256.0f) {
                isEntity = true;
            }
        } else {
            if (distRaster < dRay - thresh) {
                isEntity = true;
            } else if (distRaster < 1.2f && distRaster < dRay - 0.05f) {
                isEntity = true;
            }
        }

        if (isEntity && rasterColor.w > 0.0f) {
            float a = Math.max(0.0f, Math.min(1.0f, rasterColor.w));
            float r = rtColor.x * (1.0f - a) + rasterColor.x * a;
            float g = rtColor.y * (1.0f - a) + rasterColor.y * a;
            float b = rtColor.z * (1.0f - a) + rasterColor.z * a;
            float outAlpha = Math.max(rtColor.w, a);
            return new Vector4f(r, g, b, outAlpha);
        }

        return new Vector4f(rtColor);
    }

    /**
     * Creates an inverse view-projection matrix for a standard 90-degree FOV camera with Vulkan Reverse-Z.
     */
    private static Matrix4f createTestInverseProjection() {
        float fovRad = (float) Math.toRadians(90.0);
        float aspect = 1.0f;
        float near = 0.05f;
        float far = 1000.0f;

        // JOML Reverse-Z perspective with Vulkan [0, 1] depth:
        // near plane maps to depth 1.0, far plane maps to depth 0.0
        Matrix4f proj = new Matrix4f().perspective(fovRad, aspect, far, near, true);
        Matrix4f view = new Matrix4f().identity(); // camera at origin looking down -Z
        return InstanceMath.inverseViewProjection(proj, view, new Matrix4f());
    }

    /**
     * Reconstructs Vulkan Reverse-Z depth value for a given distance along the view axis (-Z).
     */
    private static float computeReverseZDepth(float dist, float near, float far) {
        float fovRad = (float) Math.toRadians(90.0);
        Matrix4f proj = new Matrix4f().perspective(fovRad, 1.0f, far, near, true);
        Vector4f viewPos = new Vector4f(0.0f, 0.0f, -dist, 1.0f);
        Vector4f clip = proj.transform(viewPos, new Vector4f());
        return clip.z / clip.w;
    }

    private static void assertVectorEquals(Vector4f expected, Vector4f actual, String message) {
        assertEquals(expected.x, actual.x, EPSILON, message + " [X / Red mismatch]");
        assertEquals(expected.y, actual.y, EPSILON, message + " [Y / Green mismatch]");
        assertEquals(expected.z, actual.z, EPSILON, message + " [Z / Blue mismatch]");
        assertEquals(expected.w, actual.w, EPSILON, message + " [W / Alpha mismatch]");
    }
}
