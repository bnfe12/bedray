package io.github.andrexarlay.vulkancompat.rt.entity;

/**
 * Honest runtime feature lifecycle status.
 * READY is forbidden unless all prerequisite production resources exist and are verified.
 */
public enum RuntimeFeatureStatus {
    MISSING,
    EMPTY,
    CAPTURED,
    UPLOAD_PENDING,
    BLAS_PENDING,
    TLAS_PENDING,
    SHADER_LAYOUT_MISSING,
    READY,
    ACTIVE,
    FAILED
}
