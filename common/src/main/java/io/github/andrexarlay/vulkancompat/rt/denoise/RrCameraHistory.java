package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rtx.CameraMatrixGuard;
import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;

/** Camera history advances only after a successful reconstruction. Invalid frames request a reset, never NaN motion. */
final class RrCameraHistory {
    record Frame(Matrix4f projection, Matrix4f rotation, Matrix4f inverse, Matrix4f previous,
                 double x, double y, double z, Vec3 delta, boolean reset) {}
    private Frame committed;
    private boolean reset = true;

    Frame prepare(Matrix4f projection, Matrix4f rotation, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            reset = true; return null;
        }
        boolean restart = reset || committed == null || Math.hypot(x - committed.x,
            Math.hypot(y - committed.y, z - committed.z)) > 32;
        Matrix4f inverse = new Matrix4f(), previous = new Matrix4f();
        var update = CameraMatrixGuard.update(projection, rotation,
            committed == null ? null : committed.projection, committed == null ? null : committed.rotation,
            restart, inverse, previous);
        if (!update.usable()) { reset = true; return null; }
        var delta = update.resetHistory() ? Vec3.ZERO : new Vec3(x - committed.x, y - committed.y, z - committed.z);
        return new Frame(new Matrix4f(projection), new Matrix4f(rotation), inverse, previous,
            x, y, z, delta, update.resetHistory());
    }

    void commit(Frame frame) {
        if (frame == null) throw new IllegalArgumentException("cannot commit an invalid RR frame");
        committed = frame;
        reset = false;
    }
    void reset() { reset = true; }
    void clear() { committed = null; reset = true; }
}
