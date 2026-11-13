package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.rt.entity.EntityGeometryFrame;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** L2 tests for the T8c shadow matrices and the per-frame geometry offset/merge helpers (pure, no device). */
class IrisFrameGeometryTest {

    @Test
    void shadowMatricesCenterOnCameraAndAreOrthographic() {
        IrisShadowMatrices.Result r = IrisShadowMatrices.build(new float[] {0f, 1f, 0f}, 120f);
        assertEquals(16, r.modelView().length);
        assertEquals(16, r.projection().length);
        // Orthographic: no perspective divide term.
        assertEquals(0f, r.projection()[11], 1e-6f);
        assertEquals(1f, r.projection()[15], 1e-6f);

        // The camera (origin in camera-relative space) sits 120 units in front of the light, on the view axis.
        Vector4f origin = new Matrix4f().set(r.modelView()).transform(new Vector4f(0f, 0f, 0f, 1f));
        assertEquals(0f, origin.x, 1e-3f);
        assertEquals(0f, origin.y, 1e-3f);
        assertEquals(-120f, origin.z, 1e-3f);

        // ...and projects to the centre of the shadow map.
        Vector4f ndc = new Matrix4f().set(r.projection()).transform(origin);
        assertEquals(0f, ndc.x, 1e-3f);
        assertEquals(0f, ndc.y, 1e-3f);
        assertEquals(0f, ndc.z, 1e-3f);
    }

    @Test
    void shadowMatricesNormalizeSunDirection() {
        IrisShadowMatrices.Result unit = IrisShadowMatrices.build(new float[] {1f, 0f, 0f}, 64f);
        IrisShadowMatrices.Result scaled = IrisShadowMatrices.build(new float[] {5f, 0f, 0f}, 64f);
        assertArrayEquals(unit.modelView(), scaled.modelView(), 1e-4f);
        assertArrayEquals(unit.projection(), scaled.projection(), 1e-4f);
    }

    @Test
    void shadowMatricesHandleVerticalSunAndDegenerateInput() {
        // Near-vertical sun must not produce NaN (gimbal-lock fallback up vector).
        IrisShadowMatrices.Result noon = IrisShadowMatrices.build(new float[] {0f, 1f, 0f}, 100f);
        for (float f : noon.modelView()) {
            assertTrue(Float.isFinite(f), "noon modelView must be finite");
        }
        // Zero direction falls back to +Y, distance clamps to >= 1.
        IrisShadowMatrices.Result zero = IrisShadowMatrices.build(new float[] {0f, 0f, 0f}, 0f);
        for (float f : zero.modelView()) {
            assertTrue(Float.isFinite(f));
        }
        Vector4f o = new Matrix4f().set(zero.modelView()).transform(new Vector4f(0, 0, 0, 1));
        assertEquals(-1f, o.z, 1e-3f);
    }

    @Test
    void offsetTranslatesPositionOnlyAndPreservesMidBlock() {
        float[] v = new float[IrisGeometrySource.STRIDE_FLOATS];
        v[0] = 1f; v[1] = 2f; v[2] = 3f;
        v[14] = 7f; v[15] = 8f; v[16] = 9f; // at_midBlock
        IrisGeometrySource.Mesh m = new IrisGeometrySource.Mesh(v, new int[] {0, 1, 2}, IrisGeometrySource.STRIDE_FLOATS);
        IrisGeometrySource.Mesh o = IrisFrameGeometry.offset(m, 10f, 20f, 30f);
        assertEquals(11f, o.vertices()[0], 1e-6f);
        assertEquals(22f, o.vertices()[1], 1e-6f);
        assertEquals(33f, o.vertices()[2], 1e-6f);
        assertEquals(7f, o.vertices()[14], 1e-6f);
        assertEquals(8f, o.vertices()[15], 1e-6f);
        assertEquals(9f, o.vertices()[16], 1e-6f);
        assertArrayEquals(new int[] {0, 1, 2}, o.indices());
        // Original untouched.
        assertEquals(1f, m.vertices()[0], 1e-6f);
    }

    @Test
    void mergeConcatenatesAndRebasesIndices() {
        int s = IrisGeometrySource.STRIDE_FLOATS;
        float[] a = new float[s];
        a[0] = 1f;
        float[] b = new float[s];
        b[0] = 2f;
        IrisGeometrySource.Mesh ma = new IrisGeometrySource.Mesh(a, new int[] {0, 0, 0}, s);
        IrisGeometrySource.Mesh mb = new IrisGeometrySource.Mesh(b, new int[] {0, 0, 0}, s);
        IrisGeometrySource.Mesh merged = IrisFrameGeometry.merge(List.of(ma, mb));
        assertEquals(2 * s, merged.vertices().length);
        assertEquals(1f, merged.vertices()[0], 1e-6f);
        assertEquals(2f, merged.vertices()[s], 1e-6f);
        assertArrayEquals(new int[] {0, 0, 0, 1, 1, 1}, merged.indices());
        assertEquals(s, merged.strideFloats());
    }

    @Test
    void mergeOfEmptyPartsIsEmpty() {
        IrisGeometrySource.Mesh merged = IrisFrameGeometry.merge(List.of());
        assertEquals(0, merged.vertices().length);
        assertEquals(0, merged.indices().length);
    }

    @Test
    void buildProducesEntityMeshFromCapturedFrame() {
        // One camera-relative entity vertex (interleaved 10 floats) + one triangle.
        float[] verts = new float[] {0.5f, 1f, -0.5f, 0f, 0.2f, 0.3f, 0f, 0f, 1f, 0f};
        int[] idx = new int[] {0, 0, 0};
        EntityGeometryFrame frame = new EntityGeometryFrame(1L, List.of(), 1, 3, 0L, verts, idx);
        IrisFrameGeometry geom = new IrisFrameGeometry();
        Map<String, IrisGeometrySource.Mesh> out = geom.build(0.0, 0.0, 0.0, frame);
        assertTrue(out.containsKey("entities"), "captured entity frame must produce an entities mesh");
        IrisGeometrySource.Mesh entities = out.get("entities");
        assertEquals(IrisGeometrySource.STRIDE_FLOATS, entities.strideFloats());
        assertEquals(IrisGeometrySource.STRIDE_FLOATS, entities.vertices().length);
        assertEquals(0.5f, entities.vertices()[0], 1e-6f);
    }
}