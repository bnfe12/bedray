package io.github.andrexarlay.vulkancompat.rtx.frame;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReconstructionCameraTest {
    private static Matrix4f projection() {
        return new Matrix4f().perspective(1.2f, 16f / 9, .05f, 512, true);
    }

    @Test void canonicalPairAppliesBobOnce() {
        var p = projection();
        var bob = new Matrix4f().rotateZ(.03f).rotateX(-.07f).translate(.04f, -.08f, .02f);
        var rotation = new Matrix4f().rotationY(.8f);
        var full = new Matrix4f(p).mul(bob);
        var view = new Matrix4f(p).invert().mul(full).mul(rotation);
        var camera = new ReconstructionCamera(p, view, 0, 0, 0, 0, 0, false);
        var expected = new Matrix4f(full).mul(rotation);
        assertTrue(camera.projection().mul(camera.view()).equals(expected, 1e-5f));
        assertFalse(new Matrix4f(full).mul(camera.view()).equals(expected, 1e-3f), "old SL pair applied bob twice");
    }

    @Test void reprojectionMatchesIndependentWorldPointAcrossMotionAndClipYConversion() {
        var p = projection().scale(1, -1, 1);
        var previousView = new Matrix4f().rotateY(.1f).rotateZ(.03f).translate(.02f, -.04f, 0);
        var currentView = new Matrix4f().rotateY(.15f).rotateX(-.05f).translate(-.02f, -.07f, .01f);
        // Anchors remain double precision; only their small difference reaches matrix arithmetic.
        double anchor = 20_000_000.25;
        var previous = new ReconstructionCamera(p, previousView, anchor, 70, -anchor, 0, 0, false);
        var current = new ReconstructionCamera(p, currentView, anchor + .3, 70.1, -anchor - .2, .2f, -.3f, false);
        var worldRelativeToCurrent = new Vector4f(1.7f, -.2f, -12, 1);
        var clipCurrent = new Matrix4f(p).mul(currentView).transform(new Vector4f(worldRelativeToCurrent));
        var actual = current.clipToPrevious(previous).transform(clipCurrent);
        var relativeToPrevious = new Vector4f(worldRelativeToCurrent).add(.3f, .1f, -.2f, 0);
        var expected = new Matrix4f(p).mul(previousView).transform(relativeToPrevious);
        assertTrue(actual.equals(expected, 5e-4f), actual + " != " + expected);
        assertTrue(current.projection().equals(p), "SDK's existing clip-Y projection must survive unchanged");
    }

    @Test void eyeAndAxesIncludeBobTranslationAndRotation() {
        var view = new Matrix4f().rotateY(.4f).rotateZ(.1f).translate(.1f, -.2f, .3f);
        var inverse = new Matrix4f(view).invert();
        var camera = new ReconstructionCamera(projection(), view, 100, 65, -300, 0, 0, false);
        Vector3f offset = inverse.transformPosition(new Vector3f());
        assertTrue(camera.eye().equals(new Vector3d(100.0 + offset.x, 65.0 + offset.y, -300.0 + offset.z), 1e-7));
        assertTrue(view.transformPosition(new Vector3f(offset)).equals(new Vector3f(), 1e-6f));
        assertTrue(view.transformDirection(camera.right()).equals(new Vector3f(1, 0, 0), 1e-6f));
        assertTrue(view.transformDirection(camera.up()).equals(new Vector3f(0, 1, 0), 1e-6f));
        assertTrue(view.transformDirection(camera.forward()).equals(new Vector3f(0, 0, -1), 1e-6f));
    }

    @Test void snapshotCannotBeMutatedByProducerOrConsumer() {
        var p = projection();
        var v = new Matrix4f().rotateY(.3f);
        var camera = new ReconstructionCamera(p, v, 1, 2, 3, .2f, -.3f, true);
        var same = new ReconstructionCamera(p, v, 1, 2, 3, .2f, -.3f, true);
        p.zero(); v.zero(); camera.projection().zero(); camera.view().zero(); camera.eye().zero();
        assertTrue(camera.sameConstants(same));
        assertFalse(camera.sameConstants(new ReconstructionCamera(same.projection(), same.view(), 1, 2, 3, 0, 0, true)));
        assertFalse(camera.sameConstants(new ReconstructionCamera(same.projection(), same.view(), 1, 2, 3, .2f, -.3f, false)));
    }

    @Test void rejectsInvalidCameraBeforeSdkPacking() {
        assertThrows(IllegalArgumentException.class,
            () -> new ReconstructionCamera(new Matrix4f().zero(), new Matrix4f(), 0, 0, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconstructionCamera(projection(), new Matrix4f(), Double.NaN, 0, 0, 0, 0, false));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconstructionCamera(projection(), new Matrix4f(), 0, 0, 0, Float.NaN, 0, false));
    }
}
