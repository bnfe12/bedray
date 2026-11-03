package io.github.andrexarlay.vulkancompat.rt.denoise;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RrCameraMatricesTest {
    @Test void bobbedViewMatchesLinearDepthAndKeepsTheOriginalClipTransform() {
        var base = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .05f, 512, true);
        var bob = new Matrix4f().rotateX(.08f).rotateZ(-.03f).translate(.03f, -.05f, .02f);
        var rotation = new Matrix4f().rotationY(.4f);
        var full = new Matrix4f(base).mul(bob);
        var view = RrCameraMatrices.modelView(base, full, rotation);
        assertNotNull(view);
        assertTrue(view.equals(new Matrix4f(bob).mul(rotation), 1.0e-5f));
        assertTrue(new Matrix4f(base).mul(view).equals(new Matrix4f(full).mul(rotation), 1.0e-5f));
        // A surface ten units forward in the bobbed camera must have view Z -10.
        var surface = new Matrix4f(view).invert().transformPosition(new Vector3f(0, 0, -10));
        assertEquals(-10, view.transformPosition(new Vector3f(surface)).z, 1.0e-4f);
        assertTrue(Math.abs(rotation.transformPosition(new Vector3f(surface)).z + 10) > .001f);
    }

    @Test void singularOrNonfiniteBaseCannotProduceAFalseValidRrView() {
        assertNull(RrCameraMatrices.modelView(new Matrix4f().zero(), new Matrix4f(), new Matrix4f()));
        assertNull(RrCameraMatrices.modelView(new Matrix4f().m00(Float.NaN), new Matrix4f(), new Matrix4f()));
        assertNull(RrCameraMatrices.modelView(new Matrix4f(), new Matrix4f(), null));
        assertEquals(new Matrix4f(), RrCameraMatrices.modelView(new Matrix4f(), new Matrix4f(), new Matrix4f()));
    }
}
