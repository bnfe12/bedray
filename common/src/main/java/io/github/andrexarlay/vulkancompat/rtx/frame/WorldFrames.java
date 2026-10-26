package io.github.andrexarlay.vulkancompat.rtx.frame;

/** Host render-thread timeline. Retains only CPU camera history, never previous borrowed GPU handles. */
public final class WorldFrames {
    private static Object world;
    private static long epoch = 1, frame;
    private static Metadata last;
    private static WorldFrame.Camera previous;
    private static WorldFrame current;

    private record Metadata(WorldFrame.Stamp stamp, WorldFrame.Renderer renderer, Object device,
                            int width, int height, int format, WorldFrame.Origin origin, WorldFrame.Camera camera) {}

    private WorldFrames() {}

    public static void beginFrame(Object identity) {
        if (world != identity) { epoch++; last = null; }
        world = identity;
        frame++;
        current = null;
        previous = null;
        if (identity == null) last = null;
    }

    public static WorldFrame.Stamp stamp() {
        if (world == null || frame == 0) throw new IllegalStateException("No current world frame");
        return new WorldFrame.Stamp(epoch, frame);
    }

    public static WorldFrame.Stamp stampOrNull() {
        return world == null || frame == 0 ? null : new WorldFrame.Stamp(epoch, frame);
    }

    public static boolean isCurrent(WorldFrame.Stamp stamp) {
        return world != null && stamp.worldEpoch() == epoch && stamp.frameNumber() == frame;
    }

    public static void publish(WorldFrame value) {
        if (!isCurrent(value.stamp())) throw new IllegalArgumentException("Stale world frame");
        if (current != null) throw new IllegalStateException("World frame was already published");
        var color = value.color();
        if (last != null && last.stamp().worldEpoch() == epoch && last.stamp().frameNumber() == frame - 1
            && last.renderer() == value.renderer() && last.device() == value.deviceIdentity()
            && last.width() == color.width() && last.height() == color.height() && last.format() == color.format()
            && last.origin() == color.origin() && last.camera() != null && value.camera() != null) {
            var a = last.camera(); var b = value.camera();
            double dx = a.x()-b.x(), dy = a.y()-b.y(), dz = a.z()-b.z();
            // A teleport/camera cut starts a new history. Matrices for normal motion/FOV changes remain valid.
            if (dx*dx + dy*dy + dz*dz <= 64.0*64.0) previous = a;
        }
        current = value;
        last = new Metadata(value.stamp(), value.renderer(), value.deviceIdentity(), color.width(), color.height(),
            color.format(), color.origin(), value.camera());
    }

    public static WorldFrame.Camera previousCamera(WorldFrame value) {
        if (current != value || !isCurrent(value.stamp())) throw new IllegalArgumentException("Unpublished/stale frame");
        return previous;
    }

    public static void invalidateDevice(Object device) {
        if (last != null && last.device() == device) last = null;
        if (current != null && current.deviceIdentity() == device) { current = null; previous = null; }
    }
}
