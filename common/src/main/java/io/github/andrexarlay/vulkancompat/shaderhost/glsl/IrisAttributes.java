package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.util.Map;

/** Iris gbuffers vertex attribute -> (location, glsl type). Matches IrisGeometrySource.STRIDE_FLOATS layout. */
public final class IrisAttributes {
    public record Attr(int location, String type) {}

    public static final Map<String, Attr> LOCATIONS = Map.of(
        "vaPosition", new Attr(0, "vec3"),
        "vaColor", new Attr(1, "vec4"),
        "vaUV0", new Attr(2, "vec2"),
        "vaUV1", new Attr(3, "vec2"),
        "vaNormal", new Attr(4, "vec3"),
        "at_midBlock", new Attr(5, "vec3"),
        "mc_Entity", new Attr(6, "vec4"),
        "mc_midTexCoord", new Attr(7, "vec4"),
        "at_tangent", new Attr(8, "vec4")
    );

    private IrisAttributes() {}
}