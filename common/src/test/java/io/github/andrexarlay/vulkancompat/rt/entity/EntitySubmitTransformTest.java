package io.github.andrexarlay.vulkancompat.rt.entity;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each captured entity carries its own position, and no entity shares the dispatcher's mutable pose matrix. */
class EntitySubmitTransformTest {

    @Test
    void theTransformIsACopyTranslatedToTheEntity() {
        Matrix4f base = new Matrix4f().translate(10f, 0f, 0f);
        Matrix4f before = new Matrix4f(base);
        Matrix4f transform = EntitySubmitTransform.of(base, 3.0, 2.0, -1.0);
        assertNotSame(base, transform, "the dispatcher's pose matrix is mutable and shared; the capture must copy it");
        assertEquals(before, base, "the base pose is untouched");
        Vector3f origin = transform.transformPosition(new Vector3f());
        assertEquals(13f, origin.x, 1e-6f);
        assertEquals(2f, origin.y, 1e-6f);
        assertEquals(-1f, origin.z, 1e-6f);
    }

    @Test
    void twoIdenticalEntitiesNoLongerShareOneOrigin() {
        Matrix4f base = new Matrix4f();
        Vector3f a = EntitySubmitTransform.of(base, 0, 0, 0).transformPosition(new Vector3f());
        Vector3f b = EntitySubmitTransform.of(base, 3, 0, 0).transformPosition(new Vector3f());
        assertTrue(a.distance(b) > 2f, "two drops 3 blocks apart are farther apart than the 2-block matching gate");
    }

    @Test
    void theMixinUsesTheHelper() throws IOException {
        String mixin = Files.readString(Path.of(
            "src/main/java/io/github/andrexarlay/vulkancompat/mixin/rt/MixinEntityRenderDispatcher.java"));
        assertTrue(mixin.contains("EntitySubmitTransform.of("), "the capture records the entity's own transform");
    }
}
