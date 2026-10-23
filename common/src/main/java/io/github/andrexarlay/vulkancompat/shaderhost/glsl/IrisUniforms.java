package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
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
    public static final int COLOR_TARGETS = 8;
    /** Portable bounded post stage; Vulkan guarantees at least four simultaneous color attachments. */
    public static final int MAX_DRAW_BUFFERS = 4;
    public static final int SAMPLER_BINDINGS = COLOR_TARGETS + 1;
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
        for (int i = 0; i < COLOR_TARGETS; i++) s.put("colortex" + i, colorBinding(i));
        s.put("gcolor", BINDING_COLORTEX0);
        s.put("depthtex0", BINDING_DEPTHTEX0);
        s.put("gdepthtex", BINDING_DEPTHTEX0);
        SAMPLERS = java.util.Collections.unmodifiableMap(s);
    }

    /** Fixed matrices of a fullscreen pass (Iris draws composite/final with an orthographic 0..1 projection). */
    public static final java.util.Set<String> CONSTANT_MATRICES = java.util.Set.of("modelViewMatrix", "projectionMatrix",
        "modelViewMatrixInverse", "projectionMatrixInverse", "textureMatrix");

    /** Stable bindings preserve the original color/depth/frame ABI. */
    public static int colorBinding(int index) {
        if (index < 0 || index >= COLOR_TARGETS) throw new IllegalArgumentException("Color target index " + index);
        return index == 0 ? BINDING_COLORTEX0 : index + 2;
    }

    public static int colorIndex(int binding) {
        if (binding == BINDING_COLORTEX0) return 0;
        return binding >= 3 && binding < COLOR_TARGETS + 2 ? binding - 2 : -1;
    }

    /** Descriptor bindings in ascending order, excluding the frame UBO at binding 2. */
    public static List<Integer> samplerBindings() {
        List<Integer> bindings = new ArrayList<>(SAMPLER_BINDINGS);
        bindings.add(BINDING_COLORTEX0);
        bindings.add(BINDING_DEPTHTEX0);
        for (int i = 1; i < COLOR_TARGETS; i++) bindings.add(colorBinding(i));
        return List.copyOf(bindings);
    }

    private IrisUniforms() {}
}
