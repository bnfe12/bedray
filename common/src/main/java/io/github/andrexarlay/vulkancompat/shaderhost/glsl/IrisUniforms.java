package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal Iris uniform model of step A. Plain uniforms become members of one push-constant block with FIXED offsets (so every
 * stage and pass shares one push range); camera matrices/positions use a fixed-offset std140 frame UBO.
 * Samplers use stable descriptor bindings. Anything outside these tables makes the program unsupported, never a silent 0.
 * <p>
 * Semantics follow Iris: viewWidth/viewHeight = size of the target being shaded, aspectRatio = w/h, frameTimeCounter = seconds
 * (wraps at 3600), frameCounter = frames (wraps at 720720), frameTime = seconds of the last frame, near = 0.05, far = render
 * distance in blocks. The typed frame path derives near/far from the current projection instead of hardcoded planes.
 */
public final class IrisUniforms {
    public record Member(String glslType, int offset) {}

    /** name -> type/offset inside the push block. */
    public static final Map<String, Member> PUSH;
    public static final int PUSH_BYTES = 32;

    /** sampler name -> binding. Aliases share a binding. */
    public static final Map<String, Integer> SAMPLERS;
    public static final int BINDING_COLORTEX0 = 0;
    public static final int BINDING_DEPTHTEX0 = 1;
    public static final int SAMPLER_BINDINGS = 2;
    public static final int BINDING_FRAME = 2;
    public static final int FRAME_BYTES = 416;
    public static final Map<String, Member> FRAME;

    static {
        Map<String, Member> p = new LinkedHashMap<>();
        p.put("viewWidth", new Member("float", 0));
        p.put("viewHeight", new Member("float", 4));
        p.put("aspectRatio", new Member("float", 8));
        p.put("frameTimeCounter", new Member("float", 12));
        p.put("frameCounter", new Member("int", 16));
        p.put("near", new Member("float", 20));
        p.put("far", new Member("float", 24));
        p.put("frameTime", new Member("float", 28));
        PUSH = java.util.Collections.unmodifiableMap(p);

        Map<String, Member> frame = new LinkedHashMap<>();
        frame.put("gbufferProjection", new Member("mat4", 0));
        frame.put("gbufferProjectionInverse", new Member("mat4", 64));
        frame.put("gbufferModelView", new Member("mat4", 128));
        frame.put("gbufferModelViewInverse", new Member("mat4", 192));
        frame.put("gbufferPreviousProjection", new Member("mat4", 256));
        frame.put("gbufferPreviousModelView", new Member("mat4", 320));
        frame.put("cameraPosition", new Member("vec3", 384));
        frame.put("previousCameraPosition", new Member("vec3", 400));
        FRAME = java.util.Collections.unmodifiableMap(frame);

        Map<String, Integer> s = new LinkedHashMap<>();
        s.put("colortex0", BINDING_COLORTEX0);
        s.put("gcolor", BINDING_COLORTEX0);
        s.put("depthtex0", BINDING_DEPTHTEX0);
        s.put("gdepthtex", BINDING_DEPTHTEX0);
        SAMPLERS = java.util.Collections.unmodifiableMap(s);
    }

    /** Fixed matrices of a fullscreen pass (Iris draws composite/final with an orthographic 0..1 projection). */
    public static final java.util.Set<String> CONSTANT_MATRICES = java.util.Set.of("modelViewMatrix", "projectionMatrix",
        "modelViewMatrixInverse", "projectionMatrixInverse", "textureMatrix");

    private IrisUniforms() {}
}
