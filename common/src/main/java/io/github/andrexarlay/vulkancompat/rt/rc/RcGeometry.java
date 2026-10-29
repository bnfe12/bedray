package io.github.andrexarlay.vulkancompat.rt.rc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Double-precision CPU geometry for the version-2 cubemap cone-transport estimator. */
public final class RcGeometry {
    public static final int LEVELS = 5;
    public static final int DEFAULT_CORE_AXIS = 8;
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 ONE = new Vec3(1, 1, 1);

    private RcGeometry() {}

    public record Vec3(double x, double y, double z) {
        public Vec3 add(Vec3 b) { return new Vec3(x + b.x, y + b.y, z + b.z); }
        public Vec3 subtract(Vec3 b) { return new Vec3(x - b.x, y - b.y, z - b.z); }
        public Vec3 scale(double s) { return new Vec3(x * s, y * s, z * s); }
        public Vec3 multiply(Vec3 b) { return new Vec3(x * b.x, y * b.y, z * b.z); }
        public double dot(Vec3 b) { return x * b.x + y * b.y + z * b.z; }
        public Vec3 cross(Vec3 b) { return new Vec3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x); }
        public double length() { return Math.sqrt(dot(this)); }
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
        public Vec3 normalized() {
            double length = length();
            if (!(length > 0) || !Double.isFinite(length)) throw new IllegalArgumentException("finite nonzero vector required");
            return scale(1 / length);
        }
    }

    public static int side(int level) { checkLevel(level); return 1 << level; }
    public static double spacing(int level) { checkLevel(level); return 1 << level; }
    public static double near(int level) { return 4 * spacing(level); }
    public static double far(int level) { return 8 * spacing(level); }

    /** Corners have the same outward CCW orientation on every face, including face seams. */
    public record Cell(int level, int face, int x, int y) {
        public Cell {
            int d = side(level);
            if (face < 0 || face >= 6 || x < 0 || y < 0 || x >= d || y >= d)
                throw new IllegalArgumentException("cubemap cell outside level");
        }
        public int index() { int d = side(level); return face * d * d + y * d + x; }
        public Vec3 center() {
            int d = side(level);
            return direction(face, 2 * (x + 0.5) / d - 1, 2 * (y + 0.5) / d - 1);
        }
        public List<Vec3> corners() {
            int d = side(level);
            double u0 = 2.0 * x / d - 1, u1 = 2.0 * (x + 1) / d - 1;
            double v0 = 2.0 * y / d - 1, v1 = 2.0 * (y + 1) / d - 1;
            return List.of(direction(face, u0, v0), direction(face, u1, v0),
                direction(face, u1, v1), direction(face, u0, v1));
        }
        public double area() {
            var v = corners();
            return triangleArea(v.get(0), v.get(1), v.get(2)) + triangleArea(v.get(0), v.get(2), v.get(3));
        }
        public List<Cell> children() {
            if (level + 1 >= LEVELS) throw new IllegalArgumentException("terminal angular cell has no children");
            return List.of(new Cell(level + 1, face, 2 * x, 2 * y), new Cell(level + 1, face, 2 * x + 1, 2 * y),
                new Cell(level + 1, face, 2 * x, 2 * y + 1), new Cell(level + 1, face, 2 * x + 1, 2 * y + 1));
        }
    }

    public static List<Cell> cells(int level) {
        int d = side(level);
        List<Cell> result = new ArrayList<>(6 * d * d);
        for (int face = 0; face < 6; face++) for (int y = 0; y < d; y++) for (int x = 0; x < d; x++)
            result.add(new Cell(level, face, x, y));
        return List.copyOf(result);
    }

    private static Vec3 direction(int face, double u, double v) {
        return (switch (face) {
            case 0 -> new Vec3(1, v, -u);
            case 1 -> new Vec3(-1, v, u);
            case 2 -> new Vec3(u, 1, -v);
            case 3 -> new Vec3(u, -1, v);
            case 4 -> new Vec3(u, v, 1);
            case 5 -> new Vec3(-u, v, -1);
            default -> throw new IllegalArgumentException("cubemap face outside mapping");
        }).normalized();
    }

    public static double triangleArea(Vec3 a, Vec3 b, Vec3 c) {
        return 2 * Math.atan2(Math.abs(a.dot(b.cross(c))), 1 + a.dot(b) + b.dot(c) + c.dot(a));
    }

    public record Neighbor(int x, int y, int z, Vec3 position, double weight) {}

    /** A shared anchor includes one initialized probe halo; interpolation never clamps an invalid neighbor. */
    public record Grid(int coreAxis, Vec3 anchor) {
        public Grid {
            if (coreAxis < 8 || coreAxis > 32 || (coreAxis & (coreAxis - 1)) != 0 || anchor == null || !anchor.finite())
                throw new IllegalArgumentException("RC grid requires a finite anchor and power-of-two core axis 8..32");
        }
        public int axis(int level) { checkLevel(level); return Math.max(1, coreAxis >> level) + 2; }
        public Vec3 first(int level) { return anchor.subtract(ONE.scale((axis(level) - 1) * spacing(level) * 0.5)); }
        public Vec3 probe(int level, int x, int y, int z) {
            int a = axis(level);
            if (x < 0 || y < 0 || z < 0 || x >= a || y >= a || z >= a) throw new IllegalArgumentException("probe outside grid");
            return first(level).add(new Vec3(x, y, z).scale(spacing(level)));
        }
        public List<Neighbor> neighbors(int level, Vec3 sample) {
            if (sample == null || !sample.finite()) throw new IllegalArgumentException("finite sample required");
            Vec3 u = sample.subtract(first(level)).scale(1 / spacing(level));
            double bx = Math.floor(u.x), by = Math.floor(u.y), bz = Math.floor(u.z);
            double fx = u.x - bx, fy = u.y - by, fz = u.z - bz;
            int a = axis(level);
            List<Neighbor> result = new ArrayList<>(8);
            for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                double weight = (x == 0 ? 1 - fx : fx) * (y == 0 ? 1 - fy : fy) * (z == 0 ? 1 - fz : fz);
                if (weight == 0) continue;
                double ix = bx + x, iy = by + y, iz = bz + z;
                if (!Double.isFinite(weight) || ix < 0 || iy < 0 || iz < 0 || ix >= a || iy >= a || iz >= a)
                    throw new IllegalArgumentException("positive-weight interpolation neighbor outside grid");
                int nx = (int) ix, ny = (int) iy, nz = (int) iz;
                result.add(new Neighbor(nx, ny, nz, probe(level, nx, ny, nz), weight));
            }
            return List.copyOf(result);
        }
    }

    /** Vulkan-style inclusive tMax is the predecessor of the float endpoint distance: B belongs to the tail. */
    public record Bridge(Vec3 start, Vec3 end, Vec3 direction, double length, float tMax) {
        public boolean contains(float t) { return Float.isFinite(t) && t >= 0 && t <= tMax; }
    }

    public static Optional<Bridge> bridge(Vec3 start, Vec3 end, Vec3 forward) {
        if (start == null || end == null || forward == null || !start.finite() || !end.finite() || !forward.finite())
            return Optional.empty();
        Vec3 delta = end.subtract(start);
        double length = delta.length();
        float tMax = Math.nextDown((float) length);
        if (!(length > 0) || !Double.isFinite(length) || !Float.isFinite(tMax) || !(tMax > 0) || !(delta.dot(forward) > 0))
            return Optional.empty();
        return Optional.of(new Bridge(start, end, delta.scale(1 / length), length, tMax));
    }

    public static Optional<Bridge> cascadeBridge(int level, Vec3 parent, Vec3 coarse, Vec3 childDirection) {
        if (level < 0 || level + 1 >= LEVELS) throw new IllegalArgumentException("bridge needs a coarser level");
        return bridge(parent.add(childDirection.scale(near(level))), coarse.add(childDirection.scale(near(level + 1))), childDirection);
    }

    /** Apparent corner polygon, checked before use; valid gather support lies inside the endpoint mesh. */
    public static List<Vec3> apparentCorners(Cell cell, Vec3 probe, Vec3 surface, double radius) {
        if (!probe.finite() || !surface.finite() || !Double.isFinite(radius) || !(radius > 0)
            || !(probe.subtract(surface).length() < radius)) throw new IllegalArgumentException("surface outside endpoint sphere");
        List<Vec3> corners = cell.corners().stream().map(v -> probe.add(v.scale(radius)).subtract(surface).normalized()).toList();
        Vec3 center = probe.add(cell.center().scale(radius)).subtract(surface).normalized();
        for (int i = 0; i < corners.size(); i++) {
            Vec3 edge = corners.get(i).cross(corners.get((i + 1) % corners.size()));
            if (!(edge.dot(center) > 0) || corners.stream().anyMatch(v -> edge.dot(v) < -1e-14))
                throw new IllegalArgumentException("shifted cell is not a convex outward spherical polygon");
        }
        return corners;
    }

    /** Exact integral of max(0,n dot omega) over a convex great-circle corner polygon. */
    public static double projectedArea(List<Vec3> polygon, Vec3 normal) {
        Vec3 n = normal.normalized();
        List<Vec3> clipped = new ArrayList<>();
        if (polygon.size() < 3) return 0;
        for (Vec3 vertex : polygon) {
            if (vertex == null || !vertex.finite() || Math.abs(vertex.length() - 1) > 1e-9)
                throw new IllegalArgumentException("finite unit polygon vertices required");
        }
        Vec3 previous = polygon.getLast();
        double dp = previous.dot(n);
        for (Vec3 current : polygon) {
            double dc = current.dot(n);
            if ((dp >= 0) != (dc >= 0)) {
                double t = dp / (dp - dc);
                clipped.add(previous.scale(1 - t).add(current.scale(t)).normalized());
            }
            if (dc >= 0) clipped.add(current);
            previous = current; dp = dc;
        }
        Vec3 area = ZERO;
        for (int i = 0; i < clipped.size(); i++) {
            Vec3 a = clipped.get(i), b = clipped.get((i + 1) % clipped.size());
            Vec3 cross = a.cross(b);
            double sine = cross.length();
            if (sine > 1e-15) area = area.add(cross.scale(0.5 * Math.atan2(sine, a.dot(b)) / sine));
        }
        return Math.max(0, n.dot(area));
    }

    private static void checkLevel(int level) {
        if (level < 0 || level >= LEVELS) throw new IllegalArgumentException("RC level outside five-level mapping");
    }
}
