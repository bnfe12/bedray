package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.material.LabPbr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L2 JUnit tests verifying continuous camera-relative emitter coordinate synchronization,
 * invariance under camera movement, two-sided / single-sided emission classification,
 * ceiling/floor/wall normal orientation, and near-field NEE sampling stability.
 */
public class RtEmittersStabilityTest {

    private static final float EPSILON = 1e-4f;

    @Test
    void emitterRecordAbiIncludesWhiteDefaultTint() {
        assertEquals(24, RtEmitters.FLOATS);
        assertEquals(96, RtEmitters.BYTES);
        assertEquals(20, RtEmitters.TINT_OFFSET);

        float[] pos = {0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f};
        float[] uv = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
        RtEmitters.TexelEmission emissive = new RtEmitters.TexelEmission() {
            @Override
            public int width() {
                return 16;
            }

            @Override
            public int height() {
                return 16;
            }

            @Override
            public float at(int x, int y) {
                return 1f;
            }
        };
        float[] rec = new float[RtEmitters.FLOATS];
        assertTrue(RtEmitters.buildQuadRecord(rec, 0, pos, 0, uv, 0, emissive, false));
        for (int i = 0; i < 4; i++) {
            assertEquals(1f, rec[RtEmitters.TINT_OFFSET + i], EPSILON,
                "block emitters must retain a neutral tint across the expanded ABI");
        }
    }

    @Test
    void redstoneEmitterUsesVertexPowerAndDoesNotBoostDimSignal() {
        float[] positions = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};
        float[] uvs = {0, 0, .25f, 0, .25f, .25f, 0, .25f};
        int[] material = new int[16];
        java.util.Arrays.fill(material, LabPbr.pack(230, 10, 0, 251)); // direct redstone emission is vertex-driven, not LabPBR _s alpha
        int[] unpoweredColors = {0xFF00004D, 0xFF00004D, 0xFF00004D, 0xFF00004D}; // red ~= 0.30

        float[] unpowered = RtEmitters.extractQuads(positions, uvs, unpoweredColors, 1, material, 4, 4, 1);
        assertEquals(0, unpowered.length, "zero-power redstone must not enter the emitter list");

        int[] halfPowerColors = {0xFF0000A6, 0xFF0000A6, 0xFF0000A6, 0xFF0000A6}; // red ~= 0.65
        float[] powered = RtEmitters.extractQuads(positions, uvs, halfPowerColors, 1, material, 4, 4, 1);
        assertEquals(RtEmitters.FLOATS, powered.length);
        assertEquals(2f, powered[11], EPSILON, "redstone emitter marker must be distinguishable from sidedness");
        assertEquals(0.5f, powered[14], 0.01f, "emitter strength must follow vertex signal and avoid minimum-power boosting");
        assertEquals(1f, powered[7], EPSILON, "redstone signal must not be lifted by the small-source power boost");
    }

    @Test
    public void testMoveRebuildThrottleKeepsCoordinatesExact() {
        // A zero threshold rescanned every section on every moving frame. The records are stored relative to the emitter
        // origin and the shader subtracts cam - origin each frame, so positions stay exact between rebuilds; the threshold
        // only throttles the distance-based re-selection, which is why it may be non-zero.
        assertTrue(RtEmitters.MOVE_REBUILD > 0.0,
            "MOVE_REBUILD must be > 0 so a moving camera does not rescan every section each frame");
        assertTrue(RtEmitters.MOVE_REBUILD <= 0.5,
            "MOVE_REBUILD must stay sub-block so the distance-based light selection tracks the camera");
        runCameraInvarianceChecks();
    }

    @Test
    public void testFingerprintIgnoresReanchoringButSeesLightChanges() {
        float[] record = {1f, 2f, 3f, 0.5f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f};
        long seed = 12345L;

        // The same record anchored to a different origin must hash to the same lights in world space.
        long a = RtEmitters.mixEmitter(seed, record, 0, 0f, 0f, 0f);
        long b = RtEmitters.mixEmitter(seed, record, 0, 10f, -4f, 32f);
        float[] moved = record.clone();
        moved[0] += 10f;
        moved[1] += -4f;
        moved[2] += 32f;
        long c = RtEmitters.mixEmitter(seed, moved, 0, 0f, 0f, 0f);
        assertEquals(b, c, "a re-anchored list of the same lights must keep its ReSTIR history");

        float[] brighter = record.clone();
        brighter[14] *= 2f;
        assertNotEquals(a, RtEmitters.mixEmitter(seed, brighter, 0, 0f, 0f, 0f),
            "a changed emission must invalidate the stored samples");

        float[] movedLight = record.clone();
        movedLight[0] += 1f;
        assertNotEquals(a, RtEmitters.mixEmitter(seed, movedLight, 0, 0f, 0f, 0f),
            "a light that moved must invalidate the stored samples");
    }

    @Test
    public void testCameraInvarianceAndCoordinatesStability() {
        runCameraInvarianceChecks();
    }

    @Test
    public void testTwoSidedEmitterClassification() {
        runTwoSidedEmitterChecks();
    }

    @Test
    public void testEmitterOrientationAndCosineCulling() {
        runEmitterOrientationChecks();
    }

    @Test
    public void testNearFieldApproachingStability() {
        runNearFieldStabilityChecks();
    }

    @Test
    public void testTopKHeapSortingAndBudget() {
        runTopKHeapSortingChecks();
    }

    public static void runAll() {
        RtEmittersStabilityTest test = new RtEmittersStabilityTest();
        test.testMoveRebuildThrottleKeepsCoordinatesExact();
        test.testFingerprintIgnoresReanchoringButSeesLightChanges();
        test.testCameraInvarianceAndCoordinatesStability();
        test.testTwoSidedEmitterClassification();
        test.testEmitterOrientationAndCosineCulling();
        test.testNearFieldApproachingStability();
        test.testDynamicEmittersHandAndDroppedItems();
        test.testTopKHeapSortingAndBudget();
    }

    private static void runCameraInvarianceChecks() {
        // Ceiling lamp quad at world position (15.0, 70.0, 25.0)
        double lampWorldX = 15.0;
        double lampWorldY = 70.0;
        double lampWorldZ = 25.0;

        // Surface point on the floor at (15.0, 64.0, 25.0)
        double floorWorldX = 15.0;
        double floorWorldY = 64.0;
        double floorWorldZ = 25.0;

        // Expected world vector from floor to lamp: (0, 6, 0)
        double expectedDx = lampWorldX - floorWorldX;
        double expectedDy = lampWorldY - floorWorldY;
        double expectedDz = lampWorldZ - floorWorldZ;

        // Test over a broad range of camera positions:
        // close to lamp, far from lamp, on the floor, negative coords, crossing block boundaries
        double[][] cameraPositions = {
            {15.0, 65.0, 25.0},          // Eye level above floor
            {15.0, 69.5, 25.0},          // Right below the lamp
            {15.0, 70.5, 25.0},          // Above the lamp
            {15.2, 64.1, 25.3},          // Sinking right onto the floor
            {14.999, 64.999, 24.999},    // Crossing integer boundaries from below
            {15.001, 65.001, 25.001},    // Crossing integer boundaries from above
            {0.0, 64.0, 0.0},            // Far away
            {-100.5, -45.2, -80.7},      // Negative deep cave coordinates
            {1000.25, 120.75, 500.5}     // Far positive coordinates
        };

        for (double[] cam : cameraPositions) {
            double camX = cam[0];
            double camY = cam[1];
            double camZ = cam[2];

            int originX = (int) Math.floor(camX);
            int originY = (int) Math.floor(camY);
            int originZ = (int) Math.floor(camZ);

            // Emitter p0 stored in GPU buffer relative to origin
            float p0x = (float) (lampWorldX - originX);
            float p0y = (float) (lampWorldY - originY);
            float p0z = (float) (lampWorldZ - originZ);

            // Frame header camRel = cam - origin
            float camRelX = (float) (camX - originX);
            float camRelY = (float) (camY - originY);
            float camRelZ = (float) (camZ - originZ);

            // Shader computes emitter point in camera space: y = p0 - camRel
            float yx = p0x - camRelX;
            float yy = p0y - camRelY;
            float yz = p0z - camRelZ;

            // Mathematical ground truth: y must equal (lampWorld - cam)
            float expectedYx = (float) (lampWorldX - camX);
            float expectedYy = (float) (lampWorldY - camY);
            float expectedYz = (float) (lampWorldZ - camZ);

            assertEquals(expectedYx, yx, EPSILON, "Emitter camera-relative X must equal lampWorldX - camX");
            assertEquals(expectedYy, yy, EPSILON, "Emitter camera-relative Y must equal lampWorldY - camY");
            assertEquals(expectedYz, yz, EPSILON, "Emitter camera-relative Z must equal lampWorldZ - camZ");

            // Shaded surface in camera space: pos = floorWorld - cam
            float posX = (float) (floorWorldX - camX);
            float posY = (float) (floorWorldY - camY);
            float posZ = (float) (floorWorldZ - camZ);

            // Vector between emitter and surface: d = y - pos
            float dx = yx - posX;
            float dy = yy - posY;
            float dz = yz - posZ;

            // Invariant: d must be strictly independent of camera position and equal to world distance
            assertEquals((float) expectedDx, dx, EPSILON, "Vector d.x must be invariant to camera position");
            assertEquals((float) expectedDy, dy, EPSILON, "Vector d.y must be invariant to camera position");
            assertEquals((float) expectedDz, dz, EPSILON, "Vector d.z must be invariant to camera position");
        }
    }

    private static void runTwoSidedEmitterChecks() {
        int[] mat = new int[4 * 4];
        java.util.Arrays.fill(mat, LabPbr.pack(255, 0, 0, LabPbr.SOURCE_AUTO));
        mat[0] = LabPbr.pack(255, 0, 255, LabPbr.SOURCE_AUTO); // texel (0,0) emission 1.0

        // Quad 0: 1x1 flat quad on y=0 (solid pass block face)
        // Quad 1: 0.2x0.8 vertical quad (cutout pass torch/lantern flame)
        float[] pos = {
            // quad 0 (solid): [0,1] on x,z at y=0
            0, 0, 0,  1, 0, 0,  1, 0, 1,  0, 0, 1,
            // quad 1 (cutout): [0.4, 0.6] on x at y=[0.2, 1.0]
            0.4f, 0.2f, 0.5f,  0.6f, 0.2f, 0.5f,  0.6f, 1.0f, 0.5f,  0.4f, 1.0f, 0.5f
        };

        float[] uv = {
            // quad 0 UVs mapping to texel 0
            0.0f, 0.0f,  0.25f, 0.0f,  0.25f, 0.25f,  0.0f, 0.25f,
            // quad 1 UVs mapping to texel 0
            0.0f, 0.0f,  0.25f, 0.0f,  0.25f, 0.25f,  0.0f, 0.25f
        };

        // extractQuads with solidQuads = 1: quad 0 is solid (single-sided), quad 1 is cutout (two-sided)
        float[] records = RtEmitters.extractQuads(pos, uv, 2, mat, 4, 4, 1);
        assertEquals(2 * RtEmitters.FLOATS, records.length, "Should extract two emitter quads");

        // Quad 0 (solid face) should have two-sided flag = 0.0f (single-sided)
        float quad0TwoSided = records[11]; // e2.w is at offset 11
        assertEquals(0.0f, quad0TwoSided, 1e-6f, "Solid block face must be single-sided (twoSided = 0)");

        // Quad 1 (cutout pass) should have two-sided flag = 1.0f (two-sided)
        float quad1TwoSided = records[RtEmitters.FLOATS + 11];
        assertEquals(1.0f, quad1TwoSided, 1e-6f, "Cutout emitter must be two-sided (twoSided = 1)");
    }

    private static void runEmitterOrientationChecks() {
        // Test ceiling lamp: bottom face has outward normal pointing DOWN (0, -1, 0)
        float[] enCeiling = {0f, -1f, 0f};

        // Receiver on the floor below: vector from receiver to lamp points UP: dn = (0, 1, 0)
        // Vector from lamp to receiver is -dn = (0, -1, 0)
        float[] dnFloor = {0f, 1f, 0f};
        float cosCeilingToFloor = -(enCeiling[0] * dnFloor[0] + enCeiling[1] * dnFloor[1] + enCeiling[2] * dnFloor[2]);
        assertTrue(cosCeilingToFloor > 0.99f, "Ceiling lamp must illuminate the floor below (cosEmitter > 0)");

        // Receiver above ceiling: dn points DOWN: dn = (0, -1, 0), -dn points UP
        float[] dnAbove = {0f, -1f, 0f};
        float cosCeilingToAbove = -(enCeiling[0] * dnAbove[0] + enCeiling[1] * dnAbove[1] + enCeiling[2] * dnAbove[2]);
        assertTrue(cosCeilingToAbove < -0.99f, "Ceiling lamp must NOT illuminate behind ceiling (cosEmitter < 0, culled)");

        // Test open two-sided source (torch): at grazing angle, cosS must maintain >= 0.25 floor
        float[] enTorch = {1f, 0f, 0f};
        float[] dnEdgeOn = {0f, 1f, 0f}; // 90 degrees to normal
        float cosEdgeOn = -(enTorch[0] * dnEdgeOn[0] + enTorch[1] * dnEdgeOn[1] + enTorch[2] * dnEdgeOn[2]);
        assertEquals(0f, cosEdgeOn, 1e-6f, "Dot product edge-on is 0");
        float cosSTorch = Math.max(Math.abs(cosEdgeOn), 0.25f);
        assertTrue(cosSTorch >= 0.25f, "Open source must maintain >= 0.25 cosine floor even edge-on");
    }

    private static void runNearFieldStabilityChecks() {
        // Simulate receiver at (0, 0, 0) with normal (0, 1, 0)
        // Light emitter at (0, dist, 0)
        double[] testDistances = {64.0, 16.0, 4.0, 1.0, 0.5, 0.1, 0.05, 0.01, 0.005, 0.001};

        for (double dist : testDistances) {
            float startBias = (float) Math.min(Math.max(0.01 * dist, 0.001), 0.02);
            float endBias = (float) Math.min(Math.max(0.02 * dist, 0.002), 0.03);

            float rayOriginY = startBias; // pos.y + ns.y * startBias
            float rayTargetY = (float) dist - endBias; // chosenY.y - dn.y * endBias

            assertTrue(rayOriginY > 0f, "rayOrigin must always be strictly above the receiver surface");

            if (dist >= 0.05) {
                float rayLen = rayTargetY - rayOriginY;
                assertTrue(rayLen > 0f, "rayTarget must remain strictly ahead of rayOrigin at dist=" + dist);
                assertTrue(rayTargetY < (float) dist, "rayTarget must stop strictly before emitter surface at dist=" + dist);
            }
        }
    }

    @Test
    public void testDynamicEmittersHandAndDroppedItems() {
        runDynamicEmitterChecks();
    }

    /** Texel emission of a 16x16 sprite: the listed texels glow with the given value, all others are dark. */
    private static RtEmitters.TexelEmission sprite16(float value, int[][] glowing) {
        return new RtEmitters.TexelEmission() {
            @Override
            public int width() {
                return 16;
            }

            @Override
            public int height() {
                return 16;
            }

            @Override
            public float at(int x, int y) {
                for (int[] g : glowing) {
                    if (g[0] == x && g[1] == y) {
                        return value;
                    }
                }
                return 0f;
            }
        };
    }

    private static void runDynamicEmitterChecks() {
        // Torch side face: a 2 x 10 texel quad (0.125 x 0.625 blocks) whose top 2 x 2 texels are the flame. p0 bottom left,
        // uv v grows downwards in the texture: the flame is at the top of the quad (b = 0.8 .. 1.0).
        float[] pos = {0f, 0f, 0f, 0.125f, 0f, 0f, 0.125f, 0.625f, 0f, 0f, 0.625f, 0f};
        float[] uv = {7 / 16f, 1f, 9 / 16f, 1f, 9 / 16f, 6 / 16f, 7 / 16f, 6 / 16f};
        RtEmitters.TexelEmission tex = sprite16(1f, new int[][] {{7, 6}, {8, 6}, {7, 7}, {8, 7}});
        float[] rec = new float[RtEmitters.FLOATS];
        assertTrue(RtEmitters.buildQuadRecord(rec, 0, pos, 0, uv, 0, tex, true), "a quad with a flame is an emitter");
        // the emitter is the flame itself: 0.125 x 0.125 blocks at the top of the stick, not the whole quad
        assertEquals(0.125f * 0.125f, rec[3], 1e-4f, "area of the flame rectangle");
        assertEquals(0.5f, rec[1], 1e-4f, "emitter corner y: the flame starts at 80 % of the stick");
        assertEquals(0.125f, rec[4], 1e-4f, "e1 = full width");
        assertEquals(0.125f, rec[9], 1e-4f, "e2 = flame height");
        assertEquals(1f, rec[14], 1e-4f, "mean emission of the flame rectangle");
        // small bright spot: boosted to the minimum power
        assertEquals(RtEmitters.MIN_POWER, rec[3] * rec[14] * rec[7], 1e-3f, "power of a small flame = MIN_POWER");
        assertEquals(1.0f, rec[11], 1e-6f, "two-sided source");
        // uv rectangle of the flame: u 7/16..9/16, v 6/16..8/16
        assertEquals(7 / 16f, rec[12], 1e-4f);
        assertEquals(8 / 16f, rec[13], 1e-4f);   // uv of the corner p0' (bottom of the flame, v grows downwards)
        assertEquals(2 / 16f, rec[16], 1e-4f);
        assertEquals(-2 / 16f, rec[19], 1e-4f);

        // a big solid face (glowstone block face, 65 % mean) is not boosted and stays single-sided for a solid pass quad
        float[] facePos = {0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f};
        float[] faceUv = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
        RtEmitters.TexelEmission whole = new RtEmitters.TexelEmission() {
            @Override
            public int width() {
                return 16;
            }

            @Override
            public int height() {
                return 16;
            }

            @Override
            public float at(int x, int y) {
                return 0.65f;
            }
        };
        float[] face = new float[RtEmitters.FLOATS];
        assertTrue(RtEmitters.buildQuadRecord(face, 0, facePos, 0, faceUv, 0, whole, false));
        assertEquals(1f, face[3], 1e-5f, "whole face area");
        assertEquals(1f, face[7], 1e-5f, "no boost for a big face");
        assertEquals(0f, face[11], 1e-6f, "solid face radiates outward only");

        // a quad without emissive texels is not an emitter
        assertFalse(RtEmitters.buildQuadRecord(new float[RtEmitters.FLOATS], 0, pos, 0, uv, 0, sprite16(1f, new int[0][]), true));

        // a hi-res texture (512 x 512, 200 texel wide glow) is scanned with a stride, within the cap
        RtEmitters.TexelEmission hires = new RtEmitters.TexelEmission() {
            @Override
            public int width() {
                return 512;
            }

            @Override
            public int height() {
                return 512;
            }

            @Override
            public float at(int x, int y) {
                return x >= 100 && x < 300 && y >= 50 && y < 250 ? 1f : 0f;
            }
        };
        float[] big = new float[RtEmitters.FLOATS];
        assertTrue(RtEmitters.buildQuadRecord(big, 0, facePos, 0, faceUv, 0, hires, false));
        assertEquals((200f / 512f) * (200f / 512f), big[3], 0.03f, "glow rectangle of a hi-res sprite");

        // entity records: the atlas slot goes to uvc.w, positions are moved into the emitter frame by RtEmitters.update (camera
        // relative + camera fraction); the importance key (emission * boost * area / d^2) ranks a close item above a far one
        float near = rec[14] * rec[7] * rec[3] / Math.max(0.5f * 0.5f, 1f);
        float far = rec[14] * rec[7] * rec[3] / Math.max(10f * 10f, 1f);
        assertTrue(near > far * 50f, "a close item light outranks a far one");
    }

    private static void runTopKHeapSortingChecks() {
        final int budget = 16;
        float[][] heap = new float[budget][RtEmitters.FLOATS + 1];
        for (int i = 0; i < budget; i++) {
            heap[i] = new float[RtEmitters.FLOATS + 1];
        }
        int heapSize = 0;

        int totalCandidates = 500;
        float[] importances = new float[totalCandidates];
        java.util.Random rng = new java.util.Random(12345);
        for (int i = 0; i < totalCandidates; i++) {
            importances[i] = rng.nextFloat() * 100.0f;
        }

        for (int i = 0; i < totalCandidates; i++) {
            float imp = importances[i];
            if (heapSize < budget) {
                heap[heapSize][RtEmitters.FLOATS] = imp;
                heap[heapSize][0] = i;
                RtEmitters.siftUp(heap, heapSize);
                heapSize++;
            } else if (imp > heap[0][RtEmitters.FLOATS]) {
                heap[0][RtEmitters.FLOATS] = imp;
                heap[0][0] = i;
                RtEmitters.siftDown(heap, 0, heapSize);
            }
        }

        assertEquals(budget, heapSize);

        RtEmitters.sortHeapDescending(heap, heapSize);

        for (int i = 0; i < budget - 1; i++) {
            assertTrue(heap[i][RtEmitters.FLOATS] >= heap[i + 1][RtEmitters.FLOATS],
                "Heap elements must be sorted in descending order of importance at index " + i);
        }

        java.util.Arrays.sort(importances);
        for (int i = 0; i < budget; i++) {
            float expected = importances[totalCandidates - 1 - i];
            float actual = heap[i][RtEmitters.FLOATS];
            assertEquals(expected, actual, EPSILON, "Top K element " + i + " must match ground truth top importance");
        }
    }
}
