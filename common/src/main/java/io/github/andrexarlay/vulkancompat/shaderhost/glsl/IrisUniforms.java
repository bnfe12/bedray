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
    /** colortex0..15; Iris accepts more, this host refuses higher indices by name. */
    public static final int COLOR_TARGETS = 16;
    /** Draw buffers per pass; the executor also checks the device's maxColorAttachments (Vulkan guarantees only 4). */
    public static final int MAX_DRAW_BUFFERS = 8;
    public static final int SAMPLER_BINDINGS = COLOR_TARGETS + 1;
    public static final int BINDING_FRAME = 2;
    public static final int FRAME_BYTES = 416;
    public static final Map<String, Member> FRAME;
    /** Typed world-state block (Iris world/celestial/weather/fog/eye uniforms), std140, written once per frame. */
    public static final int BINDING_WORLD = 18;
    public static final int WORLD_BYTES = 144;
    public static final Map<String, Member> WORLD;

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

        Map<String, Member> world = new LinkedHashMap<>();
        world.put("worldTime", new Member("int", 0));
        world.put("worldDay", new Member("int", 4));
        world.put("moonPhase", new Member("int", 8));
        world.put("sunAngle", new Member("float", 12));
        world.put("shadowAngle", new Member("float", 16));
        world.put("rainStrength", new Member("float", 20));
        world.put("wetness", new Member("float", 24));
        world.put("isEyeInWater", new Member("int", 28));
        world.put("sunPosition", new Member("vec3", 32));
        world.put("nightVision", new Member("float", 44));
        world.put("moonPosition", new Member("vec3", 48));
        world.put("blindness", new Member("float", 60));
        world.put("shadowLightPosition", new Member("vec3", 64));
        world.put("darknessFactor", new Member("float", 76));
        world.put("upPosition", new Member("vec3", 80));
        world.put("fogStart", new Member("float", 92));
        world.put("skyColor", new Member("vec3", 96));
        world.put("fogEnd", new Member("float", 108));
        world.put("fogColor", new Member("vec3", 112));
        world.put("eyeBrightness", new Member("ivec2", 128));
        world.put("eyeBrightnessSmooth", new Member("ivec2", 136));
        WORLD = java.util.Collections.unmodifiableMap(world);

        Map<String, Integer> s = new LinkedHashMap<>();
        for (int i = 0; i < COLOR_TARGETS; i++) s.put("colortex" + i, colorBinding(i));
        s.put("gcolor", BINDING_COLORTEX0);
        // Legacy OptiFine names, as Iris accepts them.
        s.put("gdepth", colorBinding(1));
        s.put("gnormal", colorBinding(2));
        s.put("composite", colorBinding(3));
        for (int i = 1; i <= 4; i++) s.put("gaux" + i, colorBinding(3 + i));
        s.put("depthtex0", BINDING_DEPTHTEX0);
        s.put("gdepthtex", BINDING_DEPTHTEX0);
        SAMPLERS = java.util.Collections.unmodifiableMap(s);
    }

    /** Fixed matrices of a fullscreen pass (Iris draws composite/final with an orthographic 0..1 projection). */
    public static final java.util.Set<String> CONSTANT_MATRICES = java.util.Set.of("modelViewMatrix", "projectionMatrix",
        "modelViewMatrixInverse", "projectionMatrixInverse", "textureMatrix");

    /**
     * Raster-core UBO catalog: name -> canonical GLSL type, in layout order. Every gbuffers/shadow/deferred/composite/final
     * program shares these FIXED std140 offsets ({@link #UBO_LAYOUT}); a program emits only the members it uses, so any
     * subset is layout-compatible and the executor writes one block per pass. Scalar uniforms (float/int/bool) may be
     * declared with any scalar type by the pack (Iris binds per declaration); the declared type is what gets emitted.
     */
    public static final Map<String, String> UBO;
    public static final IrisUniformBlock.Layout UBO_LAYOUT;
    public static final int UBO_BINDING = 30;

    /** Raster-core sampler name -> binding (set 0). Aliases share a binding; custom pack textures start at 31 (30 is the UBO). */
    public static final Map<String, Integer> GB_SAMPLERS;
    public static final int GB_CUSTOM_BINDING_BASE = 31;

    static {
        Map<String, String> u = new LinkedHashMap<>();
        for (String m : new String[] {"modelViewMatrix", "projectionMatrix", "modelViewMatrixInverse", "projectionMatrixInverse",
            "textureMatrix", "shadowModelView", "shadowProjection", "shadowModelViewInverse", "shadowProjectionInverse"}) {
            u.put(m, "mat4");
        }
        for (String v : new String[] {"cameraPosition", "previousCameraPosition", "sunPosition", "moonPosition",
            "shadowLightPosition", "upPosition"}) {
            u.put(v, "vec3");
        }
        for (String v : new String[] {"entityColor", "fogColor", "skyColor", "blocklightColor", "ambientColor"}) {
            u.put(v, "vec4");
        }
        u.put("eyeBrightness", "ivec2");
        u.put("eyeBrightnessSmooth", "ivec2");
        for (String f : new String[] {"worldTime", "frameTimeCounter", "frameTime", "rainStrength", "wetness", "aspectRatio",
            "viewWidth", "viewHeight", "near", "far", "sunAngle", "moonAngle", "shadowDistance", "shadowMapResolution",
            "centerDepthSmooth", "playerMood", "hideGUI", "blindness", "nightVision", "darknessFactor", "darknessLightFactor",
            "screenBrightness", "cloudHeight", "cloudSpeed", "endFlashIntensity", "sunPathRotation"}) {
            u.put(f, "float");
        }
        u.put("frameCounter", "int");
        u.put("worldDay", "int");
        u.put("isEyeInWater", "int");
        u.put("isRightHanded", "bool");
        for (String m : new String[] {"previousModelViewMatrix", "previousProjectionMatrix"}) {
            u.put(m, "mat4");
        }
        u.put("relativeEyePosition", "vec3");
        for (String i : new String[] {"heldBlockLightValue", "heldBlockLightValue2", "heldItemId", "heldItemId2", "moonPhase"}) {
            u.put(i, "int");
        }
        for (String i : new String[] {"bedrockLevel", "blockEntityId", "entityId", "renderStage"}) {
            u.put(i, "int");
        }
        u.put("atlasSize", "ivec2");
        u.put("cameraPositionFract", "vec3");
        u.put("endFlashPosition", "vec3");
        UBO = java.util.Collections.unmodifiableMap(u);
        UBO_LAYOUT = IrisUniformBlock.layout(UBO);

        Map<String, Integer> g = new LinkedHashMap<>();
        g.put("colortex0", 0);
        g.put("gcolor", 0);
        g.put("depthtex0", 1);
        g.put("gdepthtex", 1);
        for (int i = 1; i <= 15; i++) {
            g.put("colortex" + i, i + 1);
        }
        g.put("gaux1", 2);
        g.put("gaux2", 3);
        g.put("gaux3", 4);
        g.put("gaux4", 5);
        g.put("depthtex1", 17);
        g.put("depthtex2", 18);
        g.put("shadowtex0", 19);
        g.put("shadow", 19);
        g.put("shadowtex1", 20);
        g.put("shadowcolor0", 21);
        g.put("shadowcolor", 21);
        g.put("shadowcolor1", 22);
        g.put("lightmap", 23);
        g.put("normals", 24);
        g.put("specular", 25);
        g.put("texture", 26);
        g.put("gtexture", 26);
        g.put("tex", 26);
        g.put("noisetex", 27);
        g.put("lighttex0", 28);
        g.put("lighttex1", 29);
        GB_SAMPLERS = java.util.Collections.unmodifiableMap(g);
    }

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
