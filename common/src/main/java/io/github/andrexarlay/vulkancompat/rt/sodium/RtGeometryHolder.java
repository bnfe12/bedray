package io.github.andrexarlay.vulkancompat.rt.sodium;

/**
 * Duck interface implemented by the mixin on Sodium's {@code ChunkBuildOutput} (H2). Carries the decoded geometry
 * from the Sodium worker thread (H1) to the render thread (H3). Lives outside the mixin package on purpose.
 */
public interface RtGeometryHolder {
    /** The geometry decoded for this build output, or null (feature off, decoding failed, or already released). */
    DecodedSection vkb$getGeometry();

    void vkb$setGeometry(DecodedSection geometry);
}
