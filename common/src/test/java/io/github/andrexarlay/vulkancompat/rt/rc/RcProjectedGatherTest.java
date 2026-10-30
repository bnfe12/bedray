package io.github.andrexarlay.vulkancompat.rt.rc;

import org.junit.jupiter.api.Test;
import java.util.List;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class RcProjectedGatherTest {
    @Test void clippedVectorAreasTileTheHemisphereToPiEvenAfterShiftingEveryCorner() {
        int polygons = 0;
        for (int level = 0; level < LEVELS; level++) {
            for (Vec3 surface : List.of(ZERO, new Vec3(0.7, -0.5, 1.1), new Vec3(0.999, 0.999, 0.999))) {
                for (Vec3 normal : List.of(new Vec3(0, 0, 1), new Vec3(1, 0, 0), new Vec3(0.3, 0.8, -0.2))) {
                    double sum = 0;
                    for (Cell cell : cells(level)) {
                        double area = projectedArea(apparentCorners(cell, ZERO, surface, 4), normal);
                        assertTrue(Double.isFinite(area) && area >= 0 && area <= Math.PI);
                        sum += area; polygons++;
                    }
                    assertEquals(Math.PI, sum, 2e-14, "constant radiance is an irradiance integral, including the horizon clip");
                }
            }
        }
        assertEquals(18_414, polygons);
    }

    @Test void individualShiftedCellsAgreeWithIndependentDenseHemisphereIntegration() {
        // Uniform z/azimuth samples integrate by point classification only; they use no polygon area formula.
        int comparisons = 0;
        double maximumError = 0;
        for (int level = 0; level < 3; level++) {
            for (Vec3 surface : List.of(new Vec3(0.7, -0.5, 1.1), new Vec3(0.999, 0.999, 0.999))) {
                Vec3 normal = new Vec3(0.3, 0.8, -0.2).normalized();
                var polygons = cells(level).stream().map(cell -> apparentCorners(cell, ZERO, surface, 4)).toList();
                double[] numerical = denseHemisphere(polygons, normal, 512, 2048);
                double total = 0;
                for (int i = 0; i < polygons.size(); i++) {
                    double exact = projectedArea(polygons.get(i), normal);
                    double error = Math.abs(exact - numerical[i]);
                    maximumError = Math.max(maximumError, error);
                    assertEquals(exact, numerical[i], 0.002, "independent shifted-cell projected integral " + level + "/" + i);
                    total += numerical[i]; comparisons++;
                }
                assertEquals(Math.PI, total, 5e-13);
            }
        }
        assertEquals(252, comparisons);
        System.out.println("RC projected gather: 252 cell integrals, 6291456 hemisphere samples, maximum absolute error=" + maximumError);
    }

    @Test void invalidEndpointSphereAndNonconvexShiftNeverProduceFabricatedWeights() {
        Cell face = new Cell(0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> apparentCorners(face, ZERO, new Vec3(4, 0, 0), 4));
        assertThrows(IllegalArgumentException.class, () -> apparentCorners(face, ZERO, new Vec3(3.5, 0, 0), 4),
            "inside the sphere alone is insufficient outside its coarse corner mesh");
        assertThrows(IllegalArgumentException.class, () -> projectedArea(List.of(ONE, ONE, ONE), ONE));
        assertThrows(IllegalArgumentException.class, () -> projectedArea(face.corners(), ZERO));
    }

    private static double[] denseHemisphere(List<List<Vec3>> polygons, Vec3 normal, int rows, int columns) {
        Vec3 tangent = normal.cross(Math.abs(normal.z()) < 0.9 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0)).normalized();
        Vec3 bitangent = normal.cross(tangent);
        Vec3[][] edges = new Vec3[polygons.size()][4];
        for (int cell = 0; cell < polygons.size(); cell++) for (int edge = 0; edge < 4; edge++)
            edges[cell][edge] = polygons.get(cell).get(edge).cross(polygons.get(cell).get((edge + 1) % 4));
        double[] result = new double[polygons.size()];
        double[] correction = new double[polygons.size()];
        double solidAngle = 2 * Math.PI / ((long) rows * columns);
        for (int row = 0; row < rows; row++) {
            double z = (row + 0.5) / rows, radial = Math.sqrt(1 - z * z);
            for (int column = 0; column < columns; column++) {
                double phi = 2 * Math.PI * (column + 0.5) / columns;
                Vec3 direction = normal.scale(z).add(tangent.scale(radial * Math.cos(phi))).add(bitangent.scale(radial * Math.sin(phi)));
                boolean found = false;
                for (int cell = 0; cell < edges.length; cell++) {
                    boolean inside = true;
                    for (Vec3 edge : edges[cell]) if (edge.dot(direction) < 0) { inside = false; break; }
                    if (inside) {
                        double increment = solidAngle * z - correction[cell];
                        double sum = result[cell] + increment;
                        correction[cell] = (sum - result[cell]) - increment;
                        result[cell] = sum; found = true; break;
                    }
                }
                if (!found) fail("closed shifted corner mesh must cover every hemisphere direction");
            }
        }
        return result;
    }
}
