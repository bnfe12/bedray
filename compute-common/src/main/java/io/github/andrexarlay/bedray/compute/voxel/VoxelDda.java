package io.github.andrexarlay.bedray.compute.voxel;

import java.util.Objects;

/** Allocation-free traversal until a hit is produced, using grid-local unit cells. */
public final class VoxelDda {
    private VoxelDda() {}

    /**
     * Traces a finite half-open ray segment [minT,maxT). A stationary coordinate on the outer
     * maximum face is outside. Negative directions own the cell immediately below an exact
     * boundary. All axes tied at an edge/corner advance together; normals prefer X, then Y, Z.
     * If the segment starts in a solid cell without crossing the grid entry, the normal is zero.
     */
    public static VoxelHit trace(VoxelGrid grid, double ox, double oy, double oz,
                                 double dx, double dy, double dz, double minT, double maxT) {
        Objects.requireNonNull(grid, "grid");
        if (!Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || !Double.isFinite(minT) || !Double.isFinite(maxT) || minT > maxT
                || (dx == 0 && dy == 0 && dz == 0)) {
            throw new IllegalArgumentException("Ray must have finite values, ordered bounds and a nonzero direction");
        }
        if (minT == maxT) return null;

        double enter = Double.NEGATIVE_INFINITY;
        double exit = maxT;
        int entryAxis = -1;
        for (int axis = 0; axis < 3; axis++) {
            double origin = axis == 0 ? ox : axis == 1 ? oy : oz;
            double direction = axis == 0 ? dx : axis == 1 ? dy : dz;
            int size = axis == 0 ? grid.sizeX() : axis == 1 ? grid.sizeY() : grid.sizeZ();
            if (direction == 0) {
                if (origin < 0 || origin >= size) return null;
                continue;
            }
            double a = -origin / direction;
            double b = (size - origin) / direction;
            double near = Math.min(a, b);
            double far = Math.max(a, b);
            if (near > enter) {
                enter = near;
                entryAxis = axis;
            }
            exit = Math.min(exit, far);
        }
        double t = Math.max(minT, enter);
        if (!(t < exit)) return null;

        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int sy = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        int x = initialCell(ox, dx, t, grid.sizeX());
        int y = initialCell(oy, dy, t, grid.sizeY());
        int z = initialCell(oz, dz, t, grid.sizeZ());
        double tx = boundaryT(ox, dx, x, sx);
        double ty = boundaryT(oy, dy, y, sy);
        double tz = boundaryT(oz, dz, z, sz);
        double dtx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double dty = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double dtz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        boolean startedInside = enter < minT;
        int nx = !startedInside && entryAxis == 0 ? -sx : 0;
        int ny = !startedInside && entryAxis == 1 ? -sy : 0;
        int nz = !startedInside && entryAxis == 2 ? -sz : 0;
        long remaining = (long) grid.sizeX() + grid.sizeY() + grid.sizeZ();
        while (remaining-- > 0 && x >= 0 && x < grid.sizeX()
                && y >= 0 && y < grid.sizeY() && z >= 0 && z < grid.sizeZ()) {
            double next = Math.min(tx, Math.min(ty, tz));
            // A cell touched only at a corner/edge does not occupy a ray interval.
            if (t < Math.min(next, exit)) {
                int material = grid.material(x, y, z);
                if (material != 0) return new VoxelHit(x, y, z, material, t, nx, ny, nz, startedInside);
            }
            if (!(next < exit)) return null;
            nx = ny = nz = 0;
            if (tx == next) {
                x += sx;
                tx += dtx;
                nx = -sx;
            }
            if (ty == next) {
                y += sy;
                ty += dty;
                if (nx == 0) ny = -sy;
            }
            if (tz == next) {
                z += sz;
                tz += dtz;
                if (nx == 0 && ny == 0) nz = -sz;
            }
            t = Math.max(t, next);
            startedInside = false;
        }
        return null;
    }

    private static int initialCell(double origin, double direction, double t, int size) {
        double coordinate = Math.fma(direction, t, origin);
        double cell = Math.floor(coordinate);
        if (direction < 0 && coordinate == cell) cell--;
        // Slab entry may round a few ulps beyond the exterior face; the intersection already
        // established a positive ray interval inside the grid, so clamp only this initial cell.
        return (int) Math.clamp(cell, 0, size - 1);
    }

    private static double boundaryT(double origin, double direction, int cell, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        return ((step > 0 ? cell + 1.0 : cell) - origin) / direction;
    }
}
