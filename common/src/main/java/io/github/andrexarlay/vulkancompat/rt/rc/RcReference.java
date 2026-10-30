package io.github.andrexarlay.vulkancompat.rt.rc;

import java.util.ArrayList;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcGeometry.*;
import static io.github.andrexarlay.vulkancompat.rt.rc.RcTransport.*;

/** Numerical v2 reference, deliberately independent of GPU dispatch, resource layouts and frame history. */
public final class RcReference {
    private RcReference() {}
    @FunctionalInterface public interface Tracer { Segment trace(Bridge connection); }
    @FunctionalInterface public interface Tails { Segment get(int level, Neighbor probe, Cell cell); }

    public static Segment reconstruct(Grid grid, Vec3 parentProbe, Cell parentCell, Tracer tracer, Tails tails) {
        if (parentCell.level() + 1 >= LEVELS) throw new IllegalArgumentException("terminal level is traced directly");
        var samples = new ArrayList<WeightedSample>(32);
        try {
            var neighbors = grid.neighbors(parentCell.level() + 1, parentProbe);
            for (Cell child : parentCell.children()) for (Neighbor neighbor : neighbors) {
                var bridge = cascadeBridge(parentCell.level(), parentProbe, neighbor.position(), child.center());
                if (bridge.isEmpty()) return invalid();
                Segment front = tracer.trace(bridge.get());
                Segment sample = compose(front, front != null && front.valid() && front.opaque()
                    ? invalid() : tails.get(child.level(), neighbor, child));
                samples.add(new WeightedSample(neighbor.weight() * child.area() / parentCell.area(), sample));
            }
        } catch (IllegalArgumentException invalidGeometry) {
            return invalid();
        }
        return average(samples);
    }

    public static Irradiance gather(Grid grid, Vec3 surface, Vec3 normal, Tracer tracer, Tails tails) {
        var samples = new ArrayList<WeightedSample>(48);
        try {
            for (Neighbor neighbor : grid.neighbors(0, surface)) for (Cell cell : cells(0)) {
                var corners = apparentCorners(cell, neighbor.position(), surface, near(0));
                double projected = projectedArea(corners, normal);
                if (projected == 0) continue;
                Vec3 end = neighbor.position().add(cell.center().scale(near(0)));
                var bridge = bridge(surface, end, cell.center());
                if (bridge.isEmpty()) return new Irradiance(ZERO, false, 0, false);
                Segment front = tracer.trace(bridge.get());
                Segment sample = compose(front, front != null && front.valid() && front.opaque()
                    ? invalid() : tails.get(0, neighbor, cell));
                samples.add(new WeightedSample(neighbor.weight() * projected, sample));
            }
        } catch (IllegalArgumentException invalidGeometry) {
            return new Irradiance(ZERO, false, 0, false);
        }
        return integrate(samples);
    }
}
