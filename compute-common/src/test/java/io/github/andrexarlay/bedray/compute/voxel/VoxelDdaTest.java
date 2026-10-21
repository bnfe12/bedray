package io.github.andrexarlay.bedray.compute.voxel;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VoxelDdaTest {
    private static VoxelGrid single() { return VoxelGrid.builder(4, 4, 4).set(1, 1, 1, 9).build(); }
    private static VoxelHit trace(VoxelGrid grid, double ox, double oy, double oz, double dx, double dy, double dz) {
        return VoxelDda.trace(grid, ox, oy, oz, dx, dy, dz, 0, 100);
    }
    private static void normal(VoxelHit hit, int x, int y, int z) {
        assertNotNull(hit);
        assertEquals(x, hit.normalX());
        assertEquals(y, hit.normalY());
        assertEquals(z, hit.normalZ());
    }

    @Test void positiveAxisHitKeepsOriginalRayParameter() {
        VoxelHit hit = trace(single(), -1, 1.5, 1.5, 2, 0, 0);
        normal(hit, -1, 0, 0);
        assertEquals(1, hit.t());
        assertEquals(1, hit.x()); assertEquals(1, hit.y()); assertEquals(1, hit.z());
        assertEquals(9, hit.material());
        assertFalse(hit.startedInside());
    }

    @Test void negativeAxisHit() {
        VoxelHit hit = trace(single(), 5, 1.5, 1.5, -1, 0, 0);
        normal(hit, 1, 0, 0);
        assertEquals(3, hit.t());
    }

    @Test void allSixFaceNormals() {
        normal(trace(single(), 1.5, -1, 1.5, 0, 1, 0), 0, -1, 0);
        normal(trace(single(), 1.5, 5, 1.5, 0, -1, 0), 0, 1, 0);
        normal(trace(single(), 1.5, 1.5, -1, 0, 0, 1), 0, 0, -1);
        normal(trace(single(), 1.5, 1.5, 5, 0, 0, -1), 0, 0, 1);
    }

    @Test void startsInsideSolidWithNoFabricatedSurfaceNormal() {
        VoxelHit hit = trace(single(), 1.5, 1.5, 1.5, 1, 0, 0);
        normal(hit, 0, 0, 0);
        assertTrue(hit.startedInside());
        assertEquals(0, hit.t());
    }

    @Test void exactNegativeBoundaryOwnsLowerCell() {
        VoxelHit hit = trace(single(), 2, 1.5, 1.5, -1, 0, 0);
        assertNotNull(hit);
        assertEquals(1, hit.x());
        assertEquals(0, hit.t());
        assertTrue(hit.startedInside());
        assertNull(trace(single(), 2, 1.5, 1.5, 1, 0, 0));
    }

    @Test void exactExteriorMaximumFaceCanEnterButNotLeave() {
        VoxelGrid grid = VoxelGrid.builder(4, 4, 4).set(3, 1, 1, 2).build();
        VoxelHit hit = trace(grid, 4, 1.5, 1.5, -1, 0, 0);
        normal(hit, 1, 0, 0);
        assertFalse(hit.startedInside());
        assertEquals(0, hit.t());
        assertNull(trace(grid, 4, 1.5, 1.5, 1, 0, 0));
    }

    @Test void exactExteriorMinimumFaceCanEnterButNotLeave() {
        VoxelGrid grid = VoxelGrid.builder(4, 4, 4).set(0, 1, 1, 2).build();
        VoxelHit hit = trace(grid, 0, 1.5, 1.5, 1, 0, 0);
        normal(hit, -1, 0, 0);
        assertFalse(hit.startedInside());
        assertNull(trace(grid, 0, 1.5, 1.5, -1, 0, 0));
    }

    @Test void parallelSlabsRejectOutsideWithoutNaNs() {
        assertNull(trace(single(), -1, 4, 1.5, 1, 0, 0));
        assertNull(trace(single(), -1, -0.1, 1.5, 1, 0, 0));
        assertNull(trace(single(), -1, 1.5, 4, 1, 0, -0.0));
        assertNull(trace(single(), -1, 1.5, -0.1, 1, 0, 0));
    }

    @Test void diagonalCornerAdvancesAllAxesAndPrefersXNormal() {
        VoxelHit hit = trace(single(), -1, -1, -1, 1, 1, 1);
        normal(hit, -1, 0, 0);
        assertEquals(2, hit.t());
        assertEquals(1, hit.x()); assertEquals(1, hit.y()); assertEquals(1, hit.z());
    }

    @Test void cellsTouchedOnlyAtCornerAreNotHits() {
        VoxelGrid grid = VoxelGrid.builder(4, 4, 4).set(1, 0, 1, 2).set(0, 1, 1, 3).build();
        assertNull(trace(grid, -1, -1, 1.5, 1, 1, 0));
    }

    @Test void negativeDiagonalCornerAdvancesAllAxes() {
        VoxelHit hit = trace(single(), 4, 4, 4, -1, -1, -1);
        normal(hit, 1, 0, 0);
        assertEquals(2, hit.t());
    }

    @Test void segmentMaximumIsExclusiveAndMinimumIsInclusive() {
        assertNull(VoxelDda.trace(single(), -1, 1.5, 1.5, 1, 0, 0, 0, 2));
        VoxelHit hit = VoxelDda.trace(single(), -1, 1.5, 1.5, 1, 0, 0, 2, 3);
        assertNotNull(hit);
        assertEquals(2, hit.t());
        assertTrue(hit.startedInside());
        assertNull(VoxelDda.trace(single(), -1, 1.5, 1.5, 1, 0, 0, 3, 4));
        assertNull(VoxelDda.trace(single(), -1, 1.5, 1.5, 1, 0, 0, 2, 2));
    }

    @Test void gridTangentHasNoPositiveInterval() {
        assertNull(trace(single(), -1, 1, 1.5, 1, -1, 0));
    }

    @Test void emptyGridAndRayPointingAwayMiss() {
        assertNull(trace(VoxelGrid.builder(4, 4, 4).build(), -1, 1.5, 1.5, 1, 0, 0));
        assertNull(trace(single(), -1, 1.5, 1.5, -1, 0, 0));
    }

    @Test void tinyNonzeroDirectionDoesNotProduceNaN() {
        VoxelHit hit = trace(single(), 1.5, 1.5, 1.5, Double.MIN_VALUE, 0, 0);
        assertNotNull(hit);
        assertTrue(Double.isFinite(hit.t()));
    }

    @Test void localRebasingRetainsFarWorldPrecision() {
        double worldOrigin = 30_000_000;
        VoxelHit hit = trace(single(), (worldOrigin - 0.25) - worldOrigin, 1.5, 1.5, 1, 0, 0);
        assertNotNull(hit);
        assertEquals(1.25, hit.t());
    }

    @Test void invalidRaysFailClosed() {
        VoxelGrid grid = single();
        assertThrows(IllegalArgumentException.class, () -> trace(grid, Double.NaN, 1, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> trace(grid, 0, 1, 1, Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> trace(grid, 0, 1, 1, 0, -0.0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelDda.trace(grid, 0, 1, 1, 1, 0, 0, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> VoxelDda.trace(grid, 0, 1, 1, 1, 0, 0, 0, Double.POSITIVE_INFINITY));
    }

    @Test void seededRaysMatchIndependentOccupiedBoxOracle() {
        Random random = new Random(0xBEDA7L);
        VoxelGridBuilder builder = VoxelGrid.builder(4, 4, 4);
        for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
            if (random.nextInt(3) == 0) builder.set(x, y, z, 1 + x + 4 * (y + 4 * z));
        }
        VoxelGrid grid = builder.build();
        for (int sample = 0; sample < 500; sample++) {
            double[] origin = {random.nextDouble() * 8 - 2, random.nextDouble() * 8 - 2, random.nextDouble() * 8 - 2};
            double[] direction = {random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1};
            VoxelHit actual = trace(grid, origin[0], origin[1], origin[2], direction[0], direction[1], direction[2]);
            double bestT = 100;
            int expectedMaterial = 0;
            for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                int material = grid.material(x, y, z);
                if (material == 0) continue;
                int[] lower = {x, y, z};
                double near = 0;
                double far = 100;
                for (int axis = 0; axis < 3; axis++) {
                    double a = (lower[axis] - origin[axis]) / direction[axis];
                    double b = (lower[axis] + 1 - origin[axis]) / direction[axis];
                    near = Math.max(near, Math.min(a, b));
                    far = Math.min(far, Math.max(a, b));
                }
                if (near < far && near < bestT) {
                    bestT = near;
                    expectedMaterial = material;
                }
            }
            if (expectedMaterial == 0) {
                assertNull(actual, "Ray " + sample);
            } else {
                assertNotNull(actual, "Ray " + sample);
                assertEquals(expectedMaterial, actual.material(), "Ray " + sample);
                assertEquals(bestT, actual.t(), 1e-10, "Ray " + sample);
            }
        }
    }
}
