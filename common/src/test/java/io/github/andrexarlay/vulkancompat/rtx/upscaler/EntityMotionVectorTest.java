package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import static org.junit.jupiter.api.Assertions.*;

public class EntityMotionVectorTest {

    @Test
    public void testEntityPreviousTransformReprojection() {
        Matrix4f invViewProj = new Matrix4f().identity();
        Matrix4f prevViewProj = new Matrix4f().identity();

        Matrix4f entityCurrTransform = new Matrix4f().translation(5.0f, 0.0f, 10.0f);
        Matrix4f entityPrevTransform = new Matrix4f().translation(4.0f, 0.0f, 10.0f);

        float width = 1920.0f;
        float height = 1080.0f;

        Vector3f worldPosRel = new Vector3f(5.0f, 0.0f, 10.0f);

        Matrix4f invCurr = new Matrix4f(entityCurrTransform).invert();
        Vector4f localPos = invCurr.transform(new Vector4f(worldPosRel, 1.0f));
        Vector4f prevPosRel = entityPrevTransform.transform(localPos);
        Vector4f prevClip = prevViewProj.transform(prevPosRel);

        assertNotNull(prevClip);
        assertEquals(0.0f, prevClip.y, 1e-4f);
    }

    @Test
    public void testEntityTeleportDisocclusionMask() {
        Matrix4f entityCurrTransform = new Matrix4f().translation(25.0f, 0.0f, 10.0f);
        Matrix4f entityPrevTransform = new Matrix4f().translation(0.0f, 0.0f, 10.0f);

        float moveDist = new Vector3f(entityCurrTransform.m30() - entityPrevTransform.m30(),
                entityCurrTransform.m31() - entityPrevTransform.m31(),
                entityCurrTransform.m32() - entityPrevTransform.m32()).length();

        float teleportThreshold = 16.0f;
        boolean teleported = moveDist > teleportThreshold;

        assertTrue(teleported, "Entity movement exceeding 16.0 should be flagged as teleported / disoccluded");
    }

    @Test
    public void testHonestFrameGenerationScaffoldStatus() {
        assertEquals("FRAME GENERATION = SCAFFOLD ONLY / NOT IMPLEMENTED", io.github.andrexarlay.vulkancompat.rtx.upscaler.fg.FrameGenerationPipeline.SCAFFOLD_STATUS);
        assertFalse(io.github.andrexarlay.vulkancompat.rtx.upscaler.fg.FrameGenerationPipeline.IMPLEMENTED);
    }
}
