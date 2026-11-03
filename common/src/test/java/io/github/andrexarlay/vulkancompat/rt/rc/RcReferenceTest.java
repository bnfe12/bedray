package io.github.andrexarlay.vulkancompat.rt.rc;

import org.junit.jupiter.api.Test;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometry.*;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcTransport.*;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometryTest.vectorEquals;
import static org.junit.jupiter.api.Assertions.*;

class RcReferenceTest {
    @Test void constantVacuumFieldSurvivesAllFourResolveLevelsWithoutBranchEnergyGain() {
        Grid grid = new Grid(8, new Vec3(-3, 7, 19));
        Vec3 radiance = new Vec3(2, 3, 5);
        Segment tail = interval(radiance, ONE, 0.37);
        int[] traces = {0}, reads = {0};
        for (int level = LEVELS - 2; level >= 0; level--) {
            Segment coarse = tail;
            Vec3 parent = grid.probe(level, 0, 0, 0);
            for (Cell cell : cells(level)) {
                Segment result = RcReference.reconstruct(grid, parent, cell, connection -> {
                    traces[0]++; return finiteMiss();
                }, (c, q, child) -> { reads[0]++; return coarse; });
                assertTrue(result.valid()); assertFalse(result.terminalKnown());
                vectorEquals(radiance, result.radiance(), 2e-14);
                vectorEquals(ONE, result.transmission(), 0);
                assertEquals(0.37, result.confidence());
                tail = result;
            }
        }
        assertEquals(510 * 32, traces[0]); assertEquals(traces[0], reads[0]);
    }

    @Test void oneMissingRequiredTailInvalidatesAndOnlyExactOpaqueBridgesBypassTheTail() {
        Grid grid = new Grid(8, ZERO);
        Vec3 parent = grid.probe(0, 0, 0, 0);
        Cell cell = new Cell(0, 0, 0, 0);
        int[] reads = {0};
        Segment missing = RcReference.reconstruct(grid, parent, cell, bridge -> finiteMiss(), (c, q, child) -> {
            return ++reads[0] == 7 ? invalid() : interval(ONE, ONE, 1);
        });
        assertFalse(missing.valid());
        Segment opaque = RcReference.reconstruct(grid, parent, cell, bridge -> interval(ONE, ZERO, 0.21), (c, q, child) -> {
            fail("opaque bridge must not read unresolved tail"); return invalid();
        });
        assertTrue(opaque.valid()); assertTrue(opaque.terminalKnown()); assertEquals(0.21, opaque.confidence());
        vectorEquals(ONE, opaque.radiance(), 0); vectorEquals(ZERO, opaque.transmission(), 0);
        assertFalse(RcReference.reconstruct(grid, parent, cell,
            bridge -> interval(ZERO, new Vec3(Double.MIN_VALUE, 0, 0), 1), (c, q, child) -> invalid()).valid());
    }

    @Test void surfaceGatherIntegratesConstantRadianceToPiAndLeavesAlbedoToTheLightingOwner() {
        Grid grid = new Grid(8, ZERO);
        Vec3 surface = new Vec3(0.2, -0.4, 0.1), normal = new Vec3(0.3, 0.8, -0.2).normalized();
        Vec3 radiance = new Vec3(2, 3, 5);
        var result = RcReference.gather(grid, surface, normal, bridge -> finiteMiss(),
            (c, q, cell) -> interval(radiance, ONE, 0.43));
        assertTrue(result.valid()); assertFalse(result.terminalKnown()); assertEquals(0.43, result.confidence());
        vectorEquals(radiance.scale(Math.PI), result.value(), 2e-14);
        Vec3 albedo = new Vec3(0.2, 0.7, 0.9);
        vectorEquals(albedo.multiply(radiance), result.value().multiply(albedo).scale(1 / Math.PI), 5e-15);
        var dark = RcReference.gather(grid, surface, normal, bridge -> finiteMiss(), (c, q, cell) -> finiteMiss());
        assertTrue(dark.valid()); assertFalse(dark.terminalKnown()); vectorEquals(ZERO, dark.value(), 0);
    }

    @Test void gatherKeepsCompletenessAndOpaqueFrontRulesWithoutClampingOutsideTheGrid() {
        Grid grid = new Grid(8, ZERO);
        Vec3 surface = new Vec3(0.2, 0.3, -0.4);
        assertFalse(RcReference.gather(grid, surface, ONE, bridge -> finiteMiss(), (c, q, cell) -> invalid()).valid());
        var opaque = RcReference.gather(grid, surface, ONE, bridge -> interval(ONE, ZERO, 0.6), (c, q, cell) -> {
            fail("opaque near field needs no tail"); return invalid();
        });
        assertTrue(opaque.valid()); assertTrue(opaque.terminalKnown());
        vectorEquals(ONE.scale(Math.PI), opaque.value(), 3e-15);
        assertFalse(RcReference.gather(grid, new Vec3(100, 0, 0), ONE, bridge -> finiteMiss(), (c, q, cell) -> finiteMiss()).valid());
        assertFalse(RcReference.gather(grid, surface, ZERO, bridge -> finiteMiss(), (c, q, cell) -> finiteMiss()).valid());
    }
}
