package io.github.andrexarlay.vulkancompat.rt.entity;

import org.joml.Matrix4f;

/**
 * World transform of one entity submit, as the capture records it at {@code EntityRenderDispatcher.submit} HEAD.
 * <p>
 * javap 26.3 of {@code EntityRenderDispatcher.submit}: {@code pushPose} at offset 46 and {@code translate(x + off, y + off,
 * z + off)} at 57 run AFTER the HEAD injection, before {@code EntityRenderer.submit} at 68. The capture used to store
 * {@code poseStack.last().pose()} itself: the same mutable base matrix for every entity, without the entity's own
 * translation. The frame-to-frame matcher (2-block nearest-origin gate, RtEntityGeometryManager) then degenerated to "first
 * unused entity of the same type and vertex count", so identical drops or a herd of one mob type could swap their previous
 * vertices and get garbage motion under DLSS-RR (2026-11-08 item audit). The render offset (under 0.2 blocks) is left out: it
 * does not matter at the 2-block gate.
 */
public final class EntitySubmitTransform {

    private EntitySubmitTransform() {
    }

    /** A copy of {@code base} translated to the entity position; {@code base} is not modified. */
    public static Matrix4f of(Matrix4f base, double x, double y, double z) {
        Matrix4f transform = base != null ? new Matrix4f(base) : new Matrix4f();
        return transform.translate((float) x, (float) y, (float) z);
    }
}
