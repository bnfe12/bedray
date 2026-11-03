package io.github.andrexarlay.vulkancompat.rt.rc;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class RcGeometryTest {
    @Test void all2046AngularCellsHaveUnitCentersPositiveAreasAndExactNestedArea() {
        int count = 0;
        for (int level = 0; level < LEVELS; level++) {
            double area = 0;
            var indices = new HashSet<Integer>();
            for (Cell cell : cells(level)) {
                count++;
                assertTrue(indices.add(cell.index()));
                assertTrue(cell.center().finite());
                assertEquals(1, cell.center().length(), 3e-16);
                assertTrue(cell.area() > 0 && Double.isFinite(cell.area()));
                area += cell.area();
                var corners = cell.corners();
                for (int edge = 0; edge < 4; edge++)
                    assertTrue(corners.get(edge).cross(corners.get((edge + 1) % 4)).dot(cell.center()) > 0);
                if (level + 1 < LEVELS) {
                    assertEquals(cell.area(), cell.children().stream().mapToDouble(Cell::area).sum(), 1e-15);
                    for (Cell child : cell.children()) {
                        assertEquals(cell.face(), child.face());
                        assertEquals(cell.x(), child.x() / 2); assertEquals(cell.y(), child.y() / 2);
                    }
                }
            }
            assertEquals(6 * side(level) * side(level), indices.size());
            assertEquals(4 * Math.PI, area, 2e-13);
        }
        assertEquals(2046, count);
    }

    @Test void cubeFaceSeamsShareTheExactSameCornerAndEdgeCoordinates() {
        for (int level = 0; level < LEVELS; level++) {
            var edges = new java.util.HashMap<String, Integer>();
            for (Cell cell : cells(level)) {
                var corners = cell.corners();
                for (int i = 0; i < 4; i++) {
                    String a = key(corners.get(i)), b = key(corners.get((i + 1) % 4));
                    String edge = a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a;
                    edges.merge(edge, 1, Integer::sum);
                }
            }
            assertEquals(12 * side(level) * side(level), edges.size());
            assertTrue(edges.values().stream().allMatch(count -> count == 2), "closed spherical mesh has two cells per edge");
        }
    }

    @Test void everyFineProbeHasCompleteCoarseInterpolationSupportForEverySupportedGrid() {
        long probes = 0;
        for (int core : new int[] {8, 16, 32}) for (Vec3 anchor : List.of(ZERO, new Vec3(-1234, 77, -91))) {
            Grid grid = new Grid(core, anchor);
            for (int level = 0; level + 1 < LEVELS; level++) {
                int axis = grid.axis(level);
                for (int z = 0; z < axis; z++) for (int y = 0; y < axis; y++) for (int x = 0; x < axis; x++) {
                    Vec3 parent = grid.probe(level, x, y, z);
                    var neighbors = grid.neighbors(level + 1, parent);
                    assertEquals(1, neighbors.stream().mapToDouble(Neighbor::weight).sum(), 2e-15);
                    Vec3 reproduced = ZERO;
                    for (Neighbor neighbor : neighbors) {
                        assertTrue(neighbor.weight() > 0);
                        assertEquals(grid.probe(level + 1, neighbor.x(), neighbor.y(), neighbor.z()), neighbor.position());
                        reproduced = reproduced.add(neighbor.position().scale(neighbor.weight()));
                    }
                    vectorEquals(parent, reproduced, 2e-12);
                    probes++;
                }
            }
        }
        assertTrue(probes > 100_000);
    }

    @Test void exactGridBoundarySkipsOnlyZeroWeightsAndOutsideSupportIsRejected() {
        Grid grid = new Grid(8, new Vec3(-100, 0, 33));
        Vec3 first = grid.first(0), last = grid.probe(0, 9, 9, 9);
        assertEquals(1, grid.neighbors(0, first).size());
        assertEquals(1, grid.neighbors(0, last).size());
        assertThrows(IllegalArgumentException.class, () -> grid.neighbors(0, first.subtract(new Vec3(0.01, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> grid.neighbors(0, last.add(new Vec3(0, 0.01, 0))));
        assertEquals(8, grid.neighbors(0, first.add(new Vec3(0.5, 0.5, 0.5))).size());
        for (int n : new int[] {-1, 0, 4, 9, 24, 64}) assertThrows(IllegalArgumentException.class, () -> new Grid(n, ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Grid(8, new Vec3(Double.NaN, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Cell(5, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Cell(0, 6, 0, 0));
    }

    @Test void bridgeGeometryIsForwardFiniteAndOwnsTheFarEndpointExactlyOnce() {
        Grid grid = new Grid(8, ZERO);
        long count = 0;
        for (int level = 0; level + 1 < LEVELS; level++) {
            int last = grid.axis(level) - 1;
            for (Vec3 parent : List.of(grid.probe(level, 0, 0, 0), grid.probe(level, last, last, last))) {
                for (Neighbor coarse : grid.neighbors(level + 1, parent)) for (Cell child : cells(level + 1)) {
                    Bridge bridge = cascadeBridge(level, parent, coarse.position(), child.center()).orElseThrow();
                    assertTrue(bridge.end().subtract(bridge.start()).dot(child.center()) > 0);
                    assertEquals(1, bridge.direction().length(), 4e-16);
                    vectorEquals(parent.add(child.center().scale(near(level))), bridge.start(), 1e-13);
                    vectorEquals(coarse.position().add(child.center().scale(near(level + 1))), bridge.end(), 1e-13);
                    assertTrue(bridge.contains(0)); assertTrue(bridge.contains(bridge.tMax()));
                    assertFalse(bridge.contains((float) bridge.length()));
                    assertEquals(Math.nextDown((float) bridge.length()), bridge.tMax());
                    count++;
                }
            }
        }
        assertTrue(count > 20_000);
        assertTrue(bridge(ZERO, ZERO, ONE).isEmpty());
        assertTrue(bridge(ZERO, ONE, ONE.scale(-1)).isEmpty());
        assertTrue(bridge(ZERO, new Vec3(Double.NaN, 0, 0), ONE).isEmpty());
        assertTrue(bridge(ZERO, new Vec3(Double.MAX_VALUE, 0, 0), ONE).isEmpty());
    }

    static void vectorEquals(Vec3 expected, Vec3 actual, double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance); assertEquals(expected.y(), actual.y(), tolerance);
        assertEquals(expected.z(), actual.z(), tolerance);
    }
    private static String key(Vec3 v) {
        // Normalize signed zero so face seams name the same geometric vertex.
        return (v.x() == 0 ? 0 : v.x()) + "," + (v.y() == 0 ? 0 : v.y()) + "," + (v.z() == 0 ? 0 : v.z());
    }
}
