package io.github.andrexarlay.bedray.api.v1;

/** Software-renderer work controls; direct lighting and shadows remain enabled independently. */
public record WorldRenderQuality(boolean diffuseBounce, boolean translucentOptics) {}
