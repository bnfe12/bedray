package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Kind;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Emits the compute shader of a validated {@link MosaicGraph} as GLSL (what the game compiles) or Slang (the mirror
 * for the AOT build), using the fixed custom-RT set-0 bindings of {@code docs/CUSTOM_RT_SHADERS.md}: TLAS 0, frame UBO
 * 1, scene color 2, output 3, depth 4, then the user bindings (SSBO 5, LUT 6, push constants). Only bindings the
 * emitted code uses are declared.
 * <p>
 * Before emitting, the graph is optimized: only nodes that reach the output (or the previewed node) are kept,
 * pure nodes whose inputs are all constant are folded to literals on the CPU ({@link MosaicEval}), and nodes that would
 * compute the same value (same type, parameters and inputs - ray queries included) are merged. The result carries a
 * line map so compiler errors can be traced back to the node or the user function that caused them.
 */
public final class MosaicCodegen {

    private MosaicCodegen() {}

    /** Emission switches: debug preview of one node, NaN/Inf highlighting, optimization. */
    public record Options(String previewNode, boolean nanCheck, boolean optimize) {
        public static final Options DEFAULT = new Options(null, false, true);
    }

    /** Lines {@code first..last} (1-based, inclusive) belong to a node or to a user function's body. */
    public record Span(int first, int last, String node, String function, int bodyFirst) {}

    /** Optimizer report: how many nodes were dropped, folded and merged, and the per-pixel cost that remains. */
    public record Stats(int nodes, int emitted, int unused, int folded, int merged, MosaicGraph.Cost cost) {}

    public record Generated(String source, List<Span> spans, Stats stats) {
        /** The span that contains a source line, or null. */
        public Span at(int line) {
            for (Span s : spans) if (line >= s.first() && line <= s.last()) return s;
            return null;
        }
    }

    public static String glsl(MosaicGraph g) { return generate(g, Options.DEFAULT, false).source(); }

    public static String slang(MosaicGraph g) { return generate(g, Options.DEFAULT, true).source(); }

    // =================================================================== optimization plan

    /** What to emit, after pruning, folding and merging. */
    static final class Plan {
        final List<Node> emit = new ArrayList<>();
        final Map<String, String> alias = new HashMap<>();      // merged id -> surviving id
        final Map<String, double[]> folded = new HashMap<>();   // id -> constant value
        final Set<String> functions = new LinkedHashSet<>();    // used user functions
        String root;                                            // id whose value is stored ("" = none)
        String rootRef;
        MosaicGraph graph;
        boolean slang;
        int unused, foldedCount, merged;

        String canon(String ref) {
            String[] p = MosaicGraph.split(ref);
            String id = alias.getOrDefault(p[0], p[0]);
            return p[1].isEmpty() ? id : id + "." + p[1];
        }
    }

    static Plan plan(MosaicGraph g, Options o) {
        Plan p = new Plan();
        p.graph = g;
        Node out = g.node(g.outputId());
        String preview = o.previewNode();
        boolean previewing = preview != null && g.node(preview) != null && !preview.equals(g.outputId());
        p.rootRef = previewing ? preview : out.inputs().get("color");
        Set<String> reach = new LinkedHashSet<>();
        upstream(g, MosaicGraph.split(p.rootRef)[0], reach);
        Map<String, String> seen = new HashMap<>();
        for (Node n : g.ordered()) {
            if (n.type().equals("mosaic.output")) continue;
            if (!reach.contains(n.id())) {
                p.unused++;
                continue;
            }
            Kind k = g.kind(n.id());
            if (o.optimize() && MosaicEval.isPure(n.type())) {
                double[] v = MosaicEval.apply(n, port -> {
                    String ref = n.inputs().get(port);
                    if (ref == null) return n.defaultOf(port) != null ? n.defaultOf(port) : MosaicEval.portDefault(n.type(), port);
                    String c = p.canon(ref);
                    return c.contains(".") ? null : p.folded.get(c);
                }, k);
                if (v != null && allFinite(v)) {
                    if (!n.type().equals("mosaic.const")) p.foldedCount++;
                    p.folded.put(n.id(), v);
                }
            }
            String key = key(n, k, p);
            if (o.optimize() && seen.containsKey(key)) {
                p.alias.put(n.id(), seen.get(key));
                p.merged++;
                continue;
            }
            seen.put(key, n.id());
            p.emit.add(n);
            if (MosaicFunction.isFunctionType(n.type())) p.functions.add(MosaicFunction.nameOf(n.type()));
            if (n.type().equals("mosaic.repeat") && n.mask() != null) p.functions.add(n.mask());
        }
        p.rootRef = p.canon(p.rootRef);
        return p;
    }

    private static boolean allFinite(double[] v) {
        for (double d : v) if (!Double.isFinite(d)) return false;
        return true;
    }

    private static String key(Node n, Kind k, Plan p) {
        double[] f = p.folded.get(n.id());
        if (f != null) return "const|" + k + "|" + java.util.Arrays.toString(f);
        StringBuilder sb = new StringBuilder(n.type()).append('|').append(n.index()).append('|').append(n.mask());
        if (n.value() != null) sb.append('|').append(java.util.Arrays.toString(n.value()));
        for (Map.Entry<String, String> e : new TreeMap<>(n.inputs()).entrySet())
            sb.append('|').append(e.getKey()).append('=').append(p.canon(e.getValue()));
        return sb.toString();
    }

    private static void upstream(MosaicGraph g, String id, Set<String> into) {
        if (!into.add(id)) return;
        Node n = g.node(id);
        if (n == null) return;
        for (String ref : n.inputs().values()) upstream(g, MosaicGraph.split(ref)[0], into);
    }

    // =================================================================== shared emission state

    private static final class Out {
        final StringBuilder sb = new StringBuilder();
        final List<Span> spans = new ArrayList<>();
        int line = 1;

        Out a(String s) {
            sb.append(s);
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') line++;
            return this;
        }
    }

    /** Which bindings and helpers the emitted code needs. */
    private record Needs(boolean ray, boolean scene, boolean depth, boolean world, boolean buffer, boolean lut, int maxPush, boolean noise,
                         java.util.Set<Integer> buffers, boolean history, boolean worldInfo, boolean blur, boolean prev, boolean taa, boolean height,
                         boolean accumulate, boolean atrous) {}

    private static final String[] NOISE_HELPERS = {"mosaicNoise(", "mosaicNoiseColor(", "mosaicVoronoi(", "mosaicHash31(", "mosaicHash33(",
        "mosaicChecker(", "mosaicWave(", "mosaicGradient(", "mosaicFbm(", "mosaicGradNoise("};

    /** Helpers that read the world volume, and those of them that also need the depth guide. */
    private static final String[] WORLD_HELPERS = {"mosaicMaterialAt(", "mosaicBlockLightAt(", "mosaicSkyLightAt(",
        "mosaicMaterialPath(", "mosaicCameraMaterial(", "mosaicUnderwater(", "mosaicCell(", "mosaicBlock(", "mosaicAlbedo(",
        "mosaicVoxelTrace("};
    private static final String[] WORLD_VIEW_HELPERS = {"mosaicHitMaterial(", "mosaicHitSkyLight(", "mosaicHitBlockLight(",
        "mosaicMaterialDepth(", "mosaicWaterDepth(", "mosaicLavaDepth("};

    private static boolean mentions(String body, String[] names) {
        for (String n : names) if (body.contains(n)) return true;
        return false;
    }

    private static Needs needs(MosaicGraph g, Plan p) {
        boolean ray = false, scene = false, depth = false, world = false, buffer = false, lut = false, noise = false, history = false, info = false,
            blur = false, prev = false, taa = false, height = false, accumulate = false, atrous = false;
        java.util.Set<Integer> buffers = new java.util.TreeSet<>();
        int push = -1;
        for (Node n : p.emit) {
            if (p.folded.containsKey(n.id())) continue;
            switch (n.type()) {
                case "mosaic.trace", "mosaic.shadow" -> ray = true;
                case "mosaic.sceneColor", "bedray.render" -> scene = true;
                case "mosaic.sceneDepth", "mosaic.position", "mosaic.worldPos", "mosaic.normal" -> depth = true;
                case "mosaic.waterDepth", "mosaic.waterMask", "mosaic.lavaMask", "mosaic.hitMaterial", "mosaic.materialMask",
                     "mosaic.materialDepth", "mosaic.skyLight", "mosaic.blockLight" -> depth = world = true;
                case "mosaic.underwater", "mosaic.cameraMaterial" -> world = true;
                case "mosaic.buffer" -> buffer = true;
                case "mosaic.lut" -> lut = true;
                case "mosaic.push" -> push = Math.max(push, n.index());
                case "mosaic.bufferIn" -> buffers.add(n.index());
                case "mosaic.blur", "mosaic.kawase" -> {
                    buffers.add(n.index());
                    blur = true;
                }
                case "mosaic.history" -> history = true;
                case "mosaic.bufferPrev" -> buffers.add(n.index());
                case "mosaic.accumulate" -> {
                    buffers.add(n.index());
                    if (n.value() != null && n.value().length > 0 && n.value()[0] > 0) buffers.add((int) n.value()[0]);
                    prev = depth = accumulate = true;
                }
                case "mosaic.atrous" -> {
                    buffers.add(n.index());
                    depth = atrous = true;
                }
                case "mosaic.reproject" -> prev = depth = true;
                case "mosaic.taa" -> {
                    prev = depth = history = taa = true;
                    if (n.mask() == null) scene = true;
                    else buffers.add(n.index());
                }
                case "mosaic.rain", "mosaic.thunder", "mosaic.temperature", "mosaic.skyColor", "mosaic.fogColor" -> info = true;
                case "mosaic.noise", "mosaic.voronoi", "mosaic.whiteNoise", "mosaic.checker", "mosaic.wave", "mosaic.gradient" -> noise = true;
                default -> { }
            }
        }
        for (String name : p.functions) {
            MosaicFunction f = g.functions().get(name);
            String body = f.glsl() + "\n" + (f.slang() == null ? "" : f.slang());
            ray |= body.contains("mosaicTrace(") || body.contains("mosaicShadow(");
            scene |= body.contains("mosaicScene(");
            depth |= body.contains("mosaicDepth(") || body.contains("mosaicHitPos(") || body.contains("mosaicWorldPos(")
                || body.contains("mosaicNormal(");
            boolean view = mentions(body, WORLD_VIEW_HELPERS);
            world |= view || mentions(body, WORLD_HELPERS);
            depth |= view;
            noise |= mentions(body, NOISE_HELPERS);
            height |= body.contains("mosaicHeightAt(") || body.contains("mosaicFarShadow(");
        }
        return new Needs(ray, scene, depth, world, buffer, lut, push, noise, buffers, history, info, blur, prev, taa, height, accumulate, atrous);
    }

    /** Header of a stats comment so authors see what the optimizer did. */
    private static String statsLine(Stats s) {
        return "// Mosaic: " + s.nodes() + " nodes -> " + s.emitted() + " emitted (" + s.unused() + " unused, " + s.folded()
            + " folded to constants, " + s.merged() + " merged); per pixel: " + s.cost().rays() + " rays, "
            + s.cost().samples() + " samples\n";
    }

    private static Stats stats(MosaicGraph g, Plan p) {
        int rays = 0, samples = 0, ops = 0;
        for (Node n : p.emit) {
            if (p.folded.containsKey(n.id())) continue;
            switch (n.type()) {
                case "mosaic.trace", "mosaic.shadow" -> rays++;
                case "mosaic.sceneColor", "bedray.render", "mosaic.sceneDepth", "mosaic.lut", "mosaic.position", "mosaic.worldPos" -> samples++;
                case "mosaic.waterDepth", "mosaic.waterMask", "mosaic.lavaMask", "mosaic.materialDepth" -> { samples++; ops += 120; }
                case "mosaic.hitMaterial", "mosaic.materialMask", "mosaic.skyLight", "mosaic.blockLight" -> { samples++; ops += 12; }
                default -> ops += MosaicFunction.isFunctionType(n.type()) ? 16 : g.kind(n.id()).size();
            }
        }
        int total = 0;
        for (Node ignored : g.nodes()) total++;
        return new Stats(total, p.emit.size() - (int) p.emit.stream().filter(n -> p.folded.containsKey(n.id())
            && n.type().equals("mosaic.const")).count() + 1, p.unused, p.foldedCount, p.merged,
            new MosaicGraph.Cost(rays, samples, ops, p.emit.size()));
    }

    // =================================================================== entry point

    public static Generated generate(MosaicGraph g, Options o, boolean slang) {
        Plan p = plan(g, o);
        p.slang = slang;
        Needs nd = needs(g, p);
        Stats st = stats(g, p);
        Out out = new Out();
        if (slang) slangHeader(out, nd, st);
        else glslHeader(out, nd, st);
        for (String name : p.functions) function(out, g.functions().get(name), slang);
        out.a(slang ? "\n[shader(\"compute\")]\n[numthreads(8, 8, 1)]\nvoid main(uint3 tid : SV_DispatchThreadID)\n{\n"
                    : "\nvoid main() {\n");
        if (slang) {
            out.a("    int2 m_texel = int2(tid.xy);\n");
            out.a("    uint m_w, m_h;\n    bedrayOutput.GetDimensions(m_w, m_h);\n");
            out.a("    if (m_texel.x >= int(m_w) || m_texel.y >= int(m_h)) return;\n");
            out.a("    float2 m_uv = (float2(m_texel) + 0.5) / float2(m_w, m_h);\n");
        } else {
            out.a("    ivec2 m_texel = ivec2(gl_GlobalInvocationID.xy);\n");
            out.a("    ivec2 m_size = imageSize(bedrayOutput);\n");
            out.a("    if (m_texel.x >= m_size.x || m_texel.y >= m_size.y) return;\n");
            out.a("    vec2 m_uv = (vec2(m_texel) + 0.5) / vec2(m_size);\n");
        }
        for (Node n : p.emit) {
            int first = out.line;
            node(out, g, p, n, slang);
            out.spans.add(new Span(first, out.line - 1, n.id(), null, 0));
        }
        Kind rk = refKind(g, p.rootRef);
        String result = wrap(ref(p.rootRef), rk, slang);
        if (slang) {
            out.a("    float4 m_out = " + result + ";\n");
            if (o.nanCheck()) out.a("    if (any(isnan(m_out)) || any(isinf(m_out))) m_out = float4(1.0, 0.0, 1.0, 1.0); // NaN/Inf\n");
            out.a("    bedrayOutput[m_texel] = m_out;\n}\n");
        } else {
            out.a("    vec4 m_out = " + result + ";\n");
            if (o.nanCheck()) out.a("    if (any(isnan(m_out)) || any(isinf(m_out))) m_out = vec4(1.0, 0.0, 1.0, 1.0); // NaN/Inf\n");
            out.a("    imageStore(bedrayOutput, m_texel, m_out);\n}\n");
        }
        return new Generated(out.sb.toString(), List.copyOf(out.spans), st);
    }

    private static Kind refKind(MosaicGraph g, String ref) {
        String[] parts = MosaicGraph.split(ref);
        return parts[1].isEmpty() ? g.kind(parts[0]) : MosaicGraph.outputKind(g.node(parts[0]).type(), parts[1]);
    }

    private static String ref(String ref) {
        String[] parts = MosaicGraph.split(ref);
        return parts[1].isEmpty() ? "g_" + parts[0] : "g_" + parts[0] + "_" + parts[1];
    }

    /** Widens a value to the stored vec4: vec4 stays, vec3 gets alpha 1, vec2 is (x, y, 0, 1), float is grey. */
    private static String wrap(String expr, Kind k, boolean slang) {
        String v4 = slang ? "float4" : "vec4", v3 = slang ? "float3" : "vec3";
        return switch (k) {
            case V4 -> expr;
            case V3 -> v4 + "(" + expr + ", 1.0)";
            case V2 -> v4 + "(" + expr + ", 0.0, 1.0)";
            case F -> v4 + "(" + v3 + "(" + expr + "), 1.0)";
            case S -> throw new IllegalStateException("a Slang value cannot be shown as a colour");
        };
    }

    // =================================================================== GLSL

    private static final String FRAME_BLOCK = """
            layout(std140, set = 0, binding = 1) uniform BedrayFrame {
                mat4 projection;         // GL convention (z in [-w, w]), no view bobbing
                mat4 projectionInverse;
                mat4 modelView;          // camera-relative: bobbing * view rotation, no world translation
                mat4 modelViewInverse;
                vec4 cameraPosition;     // xyz: world position of the camera (float); the scene TLAS is relative to it
                vec4 viewSize;           // width, height, 1/width, 1/height
                vec4 timing;             // frameTimeCounter (s, wraps at 3600), frameTime (s), frameCounter, 0
                vec4 depthInfo;          // near, far, depthAvailable (1/0), 0
            } frame;""";

    private static void glslHeader(Out o, Needs nd, Stats st) {
        o.a("#version 460\n");
        o.a("// Generated by Bedray Mosaic from shaders/bedray/mosaic.json - edit the graph, not this file.\n");
        o.a(statsLine(st)).a("\n");
        if (nd.ray()) o.a("#extension GL_EXT_ray_query : require\n\n");
        o.a("layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n\n");
        if (nd.ray()) o.a("layout(set = 0, binding = 0) uniform accelerationStructureEXT bedrayScene;\n\n");
        o.a(FRAME_BLOCK).a("\n\n");
        if (nd.scene()) o.a("layout(set = 0, binding = 2) uniform sampler2D bedraySceneColor;\n");
        o.a("layout(set = 0, binding = 3, rgba16f) uniform writeonly image2D bedrayOutput;\n");
        if (nd.depth()) o.a("layout(set = 0, binding = 4) uniform sampler2D bedrayDepth; // RT primary-ray distance, 1e6 = no hit\n");
        if (nd.buffer()) o.a("\nlayout(std430, set = 0, binding = 5) readonly buffer BedrayUserBuffer { vec4 bedrayParams[]; };\n");
        if (nd.lut()) o.a("\nlayout(set = 0, binding = 6) uniform sampler2D bedrayLut; // user LUT texture\n");
        for (int b : nd.buffers()) o.a("layout(set = 0, binding = " + b + ") uniform sampler2D bedrayBuf" + b + "; // a Buffer pass\n");
        if (nd.history()) o.a("layout(set = 0, binding = 19) uniform sampler2D bedrayHistory; // the previous final frame\n");
        if (nd.prev()) o.a("layout(std140, set = 0, binding = 17) uniform BedrayPrevFrame { mat4 projection; mat4 modelView; vec4 cameraPosition; } prevFrame;"
            + " // the previous frame's camera\n");
        if (nd.worldInfo()) o.a("layout(std140, set = 0, binding = 16) uniform BedrayWorldInfo { vec4 weather; vec4 skyColor; vec4 fogColor; vec4 extra; } worldInfo;"
            + " // weather: rain, thunder, temperature, 0\n");
        if (nd.maxPush() >= 0)
            o.a("\nlayout(push_constant) uniform BedrayPush { vec4 p[" + (nd.maxPush() + 1) + "]; } bedrayPush; // rt.push bytes\n");
        o.a("""

            vec3 mosaicEye() { return (frame.modelViewInverse * vec4(0.0, 0.0, 0.0, 1.0)).xyz; }
            vec3 mosaicViewDir(vec2 uv) {
                vec4 view = frame.projectionInverse * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
                return normalize((frame.modelViewInverse * vec4(normalize(view.xyz / view.w), 0.0)).xyz);
            }
            /** Sun direction from the world's time of day (frame.timing.w, 0 = noon); tilted a little for shadows. */
            vec3 mosaicSunDir() {
                float a = frame.timing.w * 6.2831853;
                return normalize(vec3(-sin(a), cos(a), 0.25));
            }
            /** A simple analytic sky: zenith/horizon gradient that dims at night, plus the sun disc. */
            vec3 mosaicSky(vec3 dir) {
                vec3 sun = mosaicSunDir();
                float day = clamp(sun.y * 2.0 + 0.3, 0.05, 1.0);
                vec3 c = mix(vec3(0.70, 0.80, 0.95), vec3(0.25, 0.45, 0.85), clamp(dir.y, 0.0, 1.0)) * day;
                return c + vec3(1.0, 0.9, 0.7) * pow(max(dot(dir, sun), 0.0), 256.0) * 4.0 * day;
            }
            """);
        if (nd.scene()) o.a("vec4 mosaicScene(vec2 uv) { return texture(bedraySceneColor, uv); }\n");
        if (nd.depth()) o.a("""
            float mosaicDepth(vec2 uv) { return texture(bedrayDepth, uv).r; }
            vec3 mosaicHitPos(vec2 uv) { return mosaicEye() + mosaicViewDir(uv) * min(mosaicDepth(uv), 4096.0); }
            vec3 mosaicWorldPos(vec2 uv) { return frame.cameraPosition.xyz + mosaicHitPos(uv); }
            /** Surface normal rebuilt from the depth of neighbouring pixels (the shorter side wins at edges). */
            vec3 mosaicNormal(vec2 uv) {
                vec2 px = frame.viewSize.zw;
                vec3 p = mosaicHitPos(uv);
                vec3 x1 = mosaicHitPos(uv + vec2(px.x, 0.0)) - p, x0 = p - mosaicHitPos(uv - vec2(px.x, 0.0));
                vec3 y1 = mosaicHitPos(uv + vec2(0.0, px.y)) - p, y0 = p - mosaicHitPos(uv - vec2(0.0, px.y));
                vec3 dx = dot(x0, x0) < dot(x1, x1) ? x0 : x1, dy = dot(y0, y0) < dot(y1, y1) ? y0 : y1;
                vec3 n = normalize(cross(dy, dx));
                return dot(n, mosaicViewDir(uv)) > 0.0 ? -n : n;
            }
            """);
        if (nd.world()) o.a(GLSL_WORLD.replace("%B", String.valueOf(MosaicWorld.BINDING)) + (nd.depth() ? GLSL_WORLD_VIEW : ""));
        if (nd.noise()) o.a(GLSL_NOISE);
        if (nd.blur()) o.a(GLSL_BLUR);
        if (nd.prev()) o.a(GLSL_REPROJECT);
        if (nd.taa()) o.a(GLSL_TAA);
        if (nd.accumulate()) o.a(GLSL_ACCUMULATE);
        if (nd.atrous()) o.a(GLSL_ATROUS);
        if (nd.height()) o.a(MosaicHeight.GLSL);
        if (nd.ray()) o.a("""
            const uint MOSAIC_CULL_MASK = 0x01u; // section geometry
            const vec3 MOSAIC_SKY_DIRECTION = vec3(0.32, 0.90, 0.28); // shadow-node default
            float mosaicTrace(vec3 origin, vec3 dir, float tMax) {
                rayQueryEXT q;
                rayQueryInitializeEXT(q, bedrayScene, gl_RayFlagsOpaqueEXT, MOSAIC_CULL_MASK, origin, 0.001, dir, tMax);
                while (rayQueryProceedEXT(q)) { }
                return rayQueryGetIntersectionTypeEXT(q, true) == gl_RayQueryCommittedIntersectionTriangleEXT
                    ? rayQueryGetIntersectionTEXT(q, true) : -1.0;
            }
            float mosaicShadow(vec3 origin, vec3 dir) {
                rayQueryEXT q;
                rayQueryInitializeEXT(q, bedrayScene, gl_RayFlagsOpaqueEXT | gl_RayFlagsTerminateOnFirstHitEXT,
                    MOSAIC_CULL_MASK, origin, 0.01, dir, 256.0);
                while (rayQueryProceedEXT(q)) { }
                return rayQueryGetIntersectionTypeEXT(q, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
            }
            """);
    }

    /** World volume: header + 16-bit cells (material, block light, sky light) and a voxel DDA along rays. */
    private static final String GLSL_WORLD = """

            // World volume around the camera (see MosaicWorld): material class and light of every block.
            layout(std430, set = 0, binding = %B) readonly buffer BedrayWorld {
                ivec4 originRel;   // xyz: volume origin - floor(camera) in blocks; w: 1 when the data is valid
                vec4 camFrac;      // xyz: camera - floor(camera)
                ivec4 size;        // xyz: volume size in blocks
                uint cells[];      // two 16-bit cells per uint: material | blockLight << 8 | skyLight << 12
            } bedrayWorld;
            uint mosaicCell(ivec3 c) {
                ivec3 p = c - bedrayWorld.originRel.xyz;
                if (bedrayWorld.originRel.w == 0 || any(lessThan(p, ivec3(0))) || any(greaterThanEqual(p, bedrayWorld.size.xyz))) return 0xF000u;
                int i = (p.y * bedrayWorld.size.z + p.z) * bedrayWorld.size.x + p.x;
                return (bedrayWorld.cells[i >> 1] >> uint((i & 1) * 16)) & 0xFFFFu;
            }
            ivec3 mosaicBlock(vec3 rel) { return ivec3(floor(rel + bedrayWorld.camFrac.xyz)); }
            /** Material class of the block at a camera-relative position (0 air, 1 water, 2 lava, ... see the docs). */
            float mosaicMaterialAt(vec3 rel) { return float(mosaicCell(mosaicBlock(rel)) & 0xFFu); }
            float mosaicBlockLightAt(vec3 rel) { return float((mosaicCell(mosaicBlock(rel)) >> 8) & 15u) / 15.0; }
            float mosaicSkyLightAt(vec3 rel) { return float((mosaicCell(mosaicBlock(rel)) >> 12) & 15u) / 15.0; }
            /** Distance a camera-relative ray travels inside blocks of one material before tMax (voxel DDA, <= 160 blocks). */
            float mosaicMaterialPath(vec3 origin, vec3 dir, float tMax, float material) {
                if (bedrayWorld.originRel.w == 0) return 0.0;
                vec3 p = origin + bedrayWorld.camFrac.xyz;
                ivec3 cell = ivec3(floor(p));
                ivec3 stp = ivec3(sign(dir));
                vec3 delta = vec3(abs(dir.x) > 1e-6 ? 1.0 / abs(dir.x) : 1e30, abs(dir.y) > 1e-6 ? 1.0 / abs(dir.y) : 1e30,
                                  abs(dir.z) > 1e-6 ? 1.0 / abs(dir.z) : 1e30);
                vec3 f = p - vec3(cell);
                vec3 nextT = vec3(dir.x > 0.0 ? 1.0 - f.x : f.x, dir.y > 0.0 ? 1.0 - f.y : f.y, dir.z > 0.0 ? 1.0 - f.z : f.z) * delta;
                uint want = uint(material);
                float t = 0.0, inside = 0.0;
                for (int i = 0; i < 160 && t < tMax; i++) {
                    float tEnd = min(min(nextT.x, nextT.y), nextT.z);
                    if ((mosaicCell(cell) & 0xFFu) == want) inside += min(tEnd, tMax) - t;
                    t = tEnd;
                    if (nextT.x <= nextT.y && nextT.x <= nextT.z) { cell.x += stp.x; nextT.x += delta.x; }
                    else if (nextT.y <= nextT.z) { cell.y += stp.y; nextT.y += delta.y; }
                    else { cell.z += stp.z; nextT.z += delta.z; }
                }
                return inside;
            }
            float mosaicCameraMaterial() { return mosaicMaterialAt(mosaicEye()); }
            float mosaicUnderwater() { return mosaicCameraMaterial() == 1.0 ? 1.0 : 0.0; }
            /** Typical color of a material class (voxel rendering and GI bounces). */
            vec3 mosaicAlbedo(float material) {
                const vec3 pal[15] = vec3[15](vec3(0.0), vec3(0.12, 0.30, 0.50), vec3(1.0, 0.45, 0.10), vec3(0.50, 0.50, 0.52),
                    vec3(0.36, 0.50, 0.22), vec3(0.20, 0.42, 0.12), vec3(0.30, 0.55, 0.20), vec3(0.45, 0.32, 0.18),
                    vec3(0.82, 0.76, 0.55), vec3(0.95, 0.97, 1.00), vec3(0.60, 0.75, 0.95), vec3(0.85, 0.90, 0.95),
                    vec3(0.75, 0.75, 0.78), vec3(1.00, 0.85, 0.55), vec3(0.55, 0.50, 0.45));
                return pal[clamp(int(material + 0.5), 0, 14)];
            }
            /** First block of lava or any solid class (>= 2) along a camera-relative ray: distance or -1, its face normal and class. */
            float mosaicVoxelTrace(vec3 origin, vec3 dir, float tMax, out vec3 normal, out float material) {
                normal = vec3(0.0);
                material = 0.0;
                if (bedrayWorld.originRel.w == 0) return -1.0;
                vec3 p = origin + bedrayWorld.camFrac.xyz;
                ivec3 cell = ivec3(floor(p));
                ivec3 stp = ivec3(sign(dir));
                vec3 delta = vec3(abs(dir.x) > 1e-6 ? 1.0 / abs(dir.x) : 1e30, abs(dir.y) > 1e-6 ? 1.0 / abs(dir.y) : 1e30,
                                  abs(dir.z) > 1e-6 ? 1.0 / abs(dir.z) : 1e30);
                vec3 f = p - vec3(cell);
                vec3 nextT = vec3(dir.x > 0.0 ? 1.0 - f.x : f.x, dir.y > 0.0 ? 1.0 - f.y : f.y, dir.z > 0.0 ? 1.0 - f.z : f.z) * delta;
                for (int i = 0; i < 192; i++) {
                    float t;
                    if (nextT.x <= nextT.y && nextT.x <= nextT.z) { t = nextT.x; cell.x += stp.x; nextT.x += delta.x; normal = vec3(-float(stp.x), 0.0, 0.0); }
                    else if (nextT.y <= nextT.z) { t = nextT.y; cell.y += stp.y; nextT.y += delta.y; normal = vec3(0.0, -float(stp.y), 0.0); }
                    else { t = nextT.z; cell.z += stp.z; nextT.z += delta.z; normal = vec3(0.0, 0.0, -float(stp.z)); }
                    if (t > tMax) break;
                    uint c = mosaicCell(cell) & 0xFFu;
                    if (c >= 2u) { material = float(c); return t; }
                }
                return -1.0;
            }
            """;

    /** Buffer blurs: radius in the buffer's own pixels, bilinear taps (the host samples buffers with a linear filter). */
    private static final String GLSL_BLUR = """
            vec4 mosaicBlur(sampler2D s, vec2 uv, float r) {
                vec2 px = max(r, 0.0) / vec2(textureSize(s, 0));
                vec4 c = texture(s, uv) * 4.0;
                c += (texture(s, uv + vec2(px.x, 0.0)) + texture(s, uv - vec2(px.x, 0.0))
                    + texture(s, uv + vec2(0.0, px.y)) + texture(s, uv - vec2(0.0, px.y))) * 2.0;
                c += texture(s, uv + px) + texture(s, uv - px) + texture(s, uv + vec2(px.x, -px.y)) + texture(s, uv + vec2(-px.x, px.y));
                return c * (1.0 / 16.0);
            }
            vec4 mosaicKawase(sampler2D s, vec2 uv, float r) {
                vec2 px = (max(r, 0.0) + 0.5) / vec2(textureSize(s, 0));
                return (texture(s, uv + px) + texture(s, uv - px) + texture(s, uv + vec2(px.x, -px.y))
                    + texture(s, uv + vec2(-px.x, px.y))) * 0.25;
            }
            """;
    /**
     * The pixel's surface (or, for the sky, its direction) seen by last frame's camera: uv there. Positions are camera
     * relative, so the camera's world move between the frames is added before the previous model-view.
     */
    private static final String GLSL_REPROJECT = """
            vec2 mosaicReproject(vec2 uv) {
                float t = mosaicDepth(uv);
                vec4 clip = t >= 1.0e5
                    ? prevFrame.projection * (prevFrame.modelView * vec4(mosaicViewDir(uv), 0.0))
                    : prevFrame.projection * (prevFrame.modelView
                        * vec4(mosaicHitPos(uv) + frame.cameraPosition.xyz - prevFrame.cameraPosition.xyz, 1.0));
                return clip.xy / max(clip.w, 1e-6) * 0.5 + 0.5;
            }
            """;
    private static final String SLANG_REPROJECT = """
            float2 mosaicReproject(float2 uv)
            {
                float t = mosaicDepth(uv);
                float4 clip = t >= 1.0e5
                    ? mul(prevFrame.projection, mul(prevFrame.modelView, float4(mosaicViewDir(uv), 0.0)))
                    : mul(prevFrame.projection, mul(prevFrame.modelView,
                        float4(mosaicHitPos(uv) + frame.cameraPosition.xyz - prevFrame.cameraPosition.xyz, 1.0)));
                return clip.xy / max(clip.w, 1e-6) * 0.5 + 0.5;
            }
            """;
    /**
     * Accumulate: this frame's value blended into last frame's (Buffer history, reprojected) with weight 1/n, n counted in
     * alpha up to {@code frames}. History is dropped off screen, on the sky and where last frame's ray distance there does
     * not match this surface seen from last frame's camera (disocclusion), and when it holds NaN.
     */
    private static final String GLSL_ACCUMULATE = """
            vec4 mosaicAccumulate(sampler2D hist, sampler2D histDepth, vec4 c, vec2 uv, float frames) {
                if (any(isnan(c)) || any(isinf(c))) c = vec4(0.0);
                float t = mosaicDepth(uv);
                vec2 pv = mosaicReproject(uv);
                if (t >= 1.0e5 || pv.x < 0.0 || pv.y < 0.0 || pv.x > 1.0 || pv.y > 1.0) return vec4(c.rgb, 1.0);
                float expect = length(mosaicHitPos(uv) + frame.cameraPosition.xyz - prevFrame.cameraPosition.xyz);
                float was = texture(histDepth, pv).r;
                vec4 h = texture(hist, pv);
                if (any(isnan(h)) || h.a < 0.5 || abs(was - expect) > 0.04 * expect + 0.08) return vec4(c.rgb, 1.0);
                float n = min(h.a + 1.0, max(frames, 1.0));
                return vec4(mix(h.rgb, c.rgb, 1.0 / n), n);
            }
            """;
    private static final String SLANG_ACCUMULATE = """
            float4 mosaicAccumulate(Sampler2D hist, Sampler2D histDepth, float4 c, float2 uv, float frames)
            {
                if (any(isnan(c)) || any(isinf(c))) c = float4(0.0);
                float t = mosaicDepth(uv);
                float2 pv = mosaicReproject(uv);
                if (t >= 1.0e5 || pv.x < 0.0 || pv.y < 0.0 || pv.x > 1.0 || pv.y > 1.0) return float4(c.rgb, 1.0);
                float expect = length(mosaicHitPos(uv) + frame.cameraPosition.xyz - prevFrame.cameraPosition.xyz);
                float was = histDepth.SampleLevel(pv, 0.0).r;
                float4 h = hist.SampleLevel(pv, 0.0);
                if (any(isnan(h)) || h.a < 0.5 || abs(was - expect) > 0.04 * expect + 0.08) return float4(c.rgb, 1.0);
                float n = min(h.a + 1.0, max(frames, 1.0));
                return float4(lerp(h.rgb, c.rgb, 1.0 / n), n);
            }
            """;
    /**
     * Edge-aware a-trous wavelet step (5 x 5 B3 kernel, taps {@code step} buffer pixels apart): neighbours count less the
     * farther they lie from this pixel's surface plane and the more their brightness differs ({@code edges} scales both
     * stops; 0 = plain blur). Chain 1, 2, 4, 8 steps for a wide, sharp-edged denoise. Alpha (Accumulate's count) is kept.
     */
    private static final String GLSL_ATROUS = """
            vec4 mosaicAtrous(sampler2D s, vec2 uv, float stp, float edges) {
                vec2 px = max(stp, 1.0) / vec2(textureSize(s, 0));
                vec4 c0 = texture(s, uv);
                float t0 = mosaicDepth(uv);
                if (t0 >= 1.0e5) return c0;
                vec3 p0 = mosaicHitPos(uv), n0 = mosaicNormal(uv);
                float l0 = dot(c0.rgb, vec3(0.2126, 0.7152, 0.0722));
                float planeTol = (0.04 + 0.01 * t0) / max(edges, 1e-3), lumTol = (0.6 * l0 + 0.05) / max(edges, 1e-3);
                vec3 sum = vec3(0.0);
                float wsum = 0.0;
                for (int y = -2; y <= 2; y++)
                    for (int x = -2; x <= 2; x++) {
                        vec2 q = uv + vec2(float(x), float(y)) * px;
                        if (q.x < 0.0 || q.y < 0.0 || q.x > 1.0 || q.y > 1.0) continue;
                        float k = (x == 0 ? 0.375 : (abs(x) == 1 ? 0.25 : 0.0625)) * (y == 0 ? 0.375 : (abs(y) == 1 ? 0.25 : 0.0625));
                        vec4 c = texture(s, q);
                        if (mosaicDepth(q) >= 1.0e5 || any(isnan(c))) continue;
                        float w = k;
                        if (edges > 0.0) {
                            float dp = abs(dot(mosaicHitPos(q) - p0, n0));
                            float dl = abs(dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)) - l0);
                            w *= exp(-dp / planeTol - dl / lumTol);
                        }
                        sum += c.rgb * w;
                        wsum += w;
                    }
                return vec4(wsum > 0.0 ? sum / wsum : c0.rgb, c0.a);
            }
            """;
    private static final String SLANG_ATROUS = """
            float4 mosaicAtrous(Sampler2D s, float2 uv, float stp, float edges)
            {
                uint sw, sh;
                s.GetDimensions(sw, sh);
                float2 px = max(stp, 1.0) / float2(sw, sh);
                float4 c0 = s.SampleLevel(uv, 0.0);
                float t0 = mosaicDepth(uv);
                if (t0 >= 1.0e5) return c0;
                float3 p0 = mosaicHitPos(uv), n0 = mosaicNormal(uv);
                float l0 = dot(c0.rgb, float3(0.2126, 0.7152, 0.0722));
                float planeTol = (0.04 + 0.01 * t0) / max(edges, 1e-3), lumTol = (0.6 * l0 + 0.05) / max(edges, 1e-3);
                float3 sum = float3(0.0);
                float wsum = 0.0;
                for (int y = -2; y <= 2; y++)
                    for (int x = -2; x <= 2; x++) {
                        float2 q = uv + float2(float(x), float(y)) * px;
                        if (q.x < 0.0 || q.y < 0.0 || q.x > 1.0 || q.y > 1.0) continue;
                        float k = (x == 0 ? 0.375 : (abs(x) == 1 ? 0.25 : 0.0625)) * (y == 0 ? 0.375 : (abs(y) == 1 ? 0.25 : 0.0625));
                        float4 c = s.SampleLevel(q, 0.0);
                        if (mosaicDepth(q) >= 1.0e5 || any(isnan(c))) continue;
                        float w = k;
                        if (edges > 0.0) {
                            float dp = abs(dot(mosaicHitPos(q) - p0, n0));
                            float dl = abs(dot(c.rgb, float3(0.2126, 0.7152, 0.0722)) - l0);
                            w *= exp(-dp / planeTol - dl / lumTol);
                        }
                        sum += c.rgb * w;
                        wsum += w;
                    }
                return float4(wsum > 0.0 ? sum / wsum : c0.rgb, c0.a);
            }
            """;
    /** Temporal AA: reprojected history clamped to the current 3x3 neighbourhood (no ghosting), off-screen history dropped. */
    private static final String GLSL_TAA = """
            vec4 mosaicTaa(sampler2D s, vec2 uv, float blend) {
                vec2 px = 1.0 / vec2(textureSize(s, 0));
                vec4 c = texture(s, uv), lo = c, hi = c;
                for (int y = -1; y <= 1; y++)
                    for (int x = -1; x <= 1; x++) {
                        vec4 v = texture(s, uv + vec2(x, y) * px);
                        lo = min(lo, v);
                        hi = max(hi, v);
                    }
                vec2 prev = mosaicReproject(uv);
                if (prev.x < 0.0 || prev.y < 0.0 || prev.x > 1.0 || prev.y > 1.0) return c;
                vec4 h = clamp(texture(bedrayHistory, prev), lo, hi);
                return mix(c, h, clamp(blend, 0.0, 0.98));
            }
            """;
    private static final String SLANG_TAA = """
            float4 mosaicTaa(Sampler2D s, float2 uv, float blend)
            {
                uint w, h;
                s.GetDimensions(w, h);
                float2 px = 1.0 / float2(w, h);
                float4 c = s.SampleLevel(uv, 0.0), lo = c, hi = c;
                for (int y = -1; y <= 1; y++)
                    for (int x = -1; x <= 1; x++) {
                        float4 v = s.SampleLevel(uv + float2(x, y) * px, 0.0);
                        lo = min(lo, v);
                        hi = max(hi, v);
                    }
                float2 prev = mosaicReproject(uv);
                if (prev.x < 0.0 || prev.y < 0.0 || prev.x > 1.0 || prev.y > 1.0) return c;
                float4 hist = clamp(bedrayHistory.SampleLevel(prev, 0.0), lo, hi);
                return lerp(c, hist, clamp(blend, 0.0, 0.98));
            }
            """;
    private static final String SLANG_BLUR = """
            float4 mosaicBlur(Sampler2D s, float2 uv, float r)
            {
                uint w, h;
                s.GetDimensions(w, h);
                float2 px = max(r, 0.0) / float2(w, h);
                float4 c = s.SampleLevel(uv, 0.0) * 4.0;
                c += (s.SampleLevel(uv + float2(px.x, 0.0), 0.0) + s.SampleLevel(uv - float2(px.x, 0.0), 0.0)
                    + s.SampleLevel(uv + float2(0.0, px.y), 0.0) + s.SampleLevel(uv - float2(0.0, px.y), 0.0)) * 2.0;
                c += s.SampleLevel(uv + px, 0.0) + s.SampleLevel(uv - px, 0.0) + s.SampleLevel(uv + float2(px.x, -px.y), 0.0)
                    + s.SampleLevel(uv + float2(-px.x, px.y), 0.0);
                return c * (1.0 / 16.0);
            }
            float4 mosaicKawase(Sampler2D s, float2 uv, float r)
            {
                uint w, h;
                s.GetDimensions(w, h);
                float2 px = (max(r, 0.0) + 0.5) / float2(w, h);
                return (s.SampleLevel(uv + px, 0.0) + s.SampleLevel(uv - px, 0.0) + s.SampleLevel(uv + float2(px.x, -px.y), 0.0)
                    + s.SampleLevel(uv + float2(-px.x, px.y), 0.0)) * 0.25;
            }
            """;

    /** Per-pixel world reads (need the depth guide as well). Sky: material -1, sky light 1, block light 0. */
    private static final String GLSL_WORLD_VIEW = """
            float mosaicHitMaterial(vec2 uv) {
                float t = mosaicDepth(uv);
                return t >= 1.0e5 ? -1.0 : mosaicMaterialAt(mosaicEye() + mosaicViewDir(uv) * (t + 0.02));
            }
            float mosaicHitSkyLight(vec2 uv) {
                float t = mosaicDepth(uv);
                return t >= 1.0e5 ? 1.0 : mosaicSkyLightAt(mosaicEye() + mosaicViewDir(uv) * max(t - 0.02, 0.0));
            }
            float mosaicHitBlockLight(vec2 uv) {
                float t = mosaicDepth(uv);
                return t >= 1.0e5 ? 0.0 : mosaicBlockLightAt(mosaicEye() + mosaicViewDir(uv) * max(t - 0.02, 0.0));
            }
            float mosaicMaterialDepth(vec2 uv, float material) {
                return mosaicMaterialPath(mosaicEye(), mosaicViewDir(uv), min(mosaicDepth(uv), 96.0) + 0.01, material);
            }
            float mosaicWaterDepth(vec2 uv) { return mosaicMaterialDepth(uv, 1.0); }
            float mosaicLavaDepth(vec2 uv) { return mosaicMaterialDepth(uv, 2.0); }
            """;

    private static final String SLANG_WORLD = """

            // World volume (same layout as the GLSL block): uints 0-3 originRel, 4-7 camFrac, 8-11 size, 12.. cells.
            [[vk::binding(%B, 0)]] StructuredBuffer<uint> bedrayWorld;
            uint mosaicCell(int3 c)
            {
                int3 p = c - int3(asint(bedrayWorld[0]), asint(bedrayWorld[1]), asint(bedrayWorld[2]));
                int3 size = int3(asint(bedrayWorld[8]), asint(bedrayWorld[9]), asint(bedrayWorld[10]));
                if (asint(bedrayWorld[3]) == 0 || any(p < 0) || any(p >= size)) return 0xF000u;
                int i = (p.y * size.z + p.z) * size.x + p.x;
                return (bedrayWorld[12 + (i >> 1)] >> uint((i & 1) * 16)) & 0xFFFFu;
            }
            int3 mosaicBlock(float3 rel)
            {
                return int3(floor(rel + float3(asfloat(bedrayWorld[4]), asfloat(bedrayWorld[5]), asfloat(bedrayWorld[6]))));
            }
            float mosaicMaterialAt(float3 rel) { return float(mosaicCell(mosaicBlock(rel)) & 0xFFu); }
            float mosaicBlockLightAt(float3 rel) { return float((mosaicCell(mosaicBlock(rel)) >> 8) & 15u) / 15.0; }
            float mosaicSkyLightAt(float3 rel) { return float((mosaicCell(mosaicBlock(rel)) >> 12) & 15u) / 15.0; }
            float mosaicMaterialPath(float3 origin, float3 dir, float tMax, float material)
            {
                if (asint(bedrayWorld[3]) == 0) return 0.0;
                float3 p = origin + float3(asfloat(bedrayWorld[4]), asfloat(bedrayWorld[5]), asfloat(bedrayWorld[6]));
                int3 cell = int3(floor(p));
                int3 stp = int3(sign(dir));
                float3 delta = float3(abs(dir.x) > 1e-6 ? 1.0 / abs(dir.x) : 1e30, abs(dir.y) > 1e-6 ? 1.0 / abs(dir.y) : 1e30,
                                      abs(dir.z) > 1e-6 ? 1.0 / abs(dir.z) : 1e30);
                float3 f = p - float3(cell);
                float3 nextT = float3(dir.x > 0.0 ? 1.0 - f.x : f.x, dir.y > 0.0 ? 1.0 - f.y : f.y, dir.z > 0.0 ? 1.0 - f.z : f.z) * delta;
                uint want = uint(material);
                float t = 0.0, inside = 0.0;
                for (int i = 0; i < 160 && t < tMax; i++)
                {
                    float tEnd = min(min(nextT.x, nextT.y), nextT.z);
                    if ((mosaicCell(cell) & 0xFFu) == want) inside += min(tEnd, tMax) - t;
                    t = tEnd;
                    if (nextT.x <= nextT.y && nextT.x <= nextT.z) { cell.x += stp.x; nextT.x += delta.x; }
                    else if (nextT.y <= nextT.z) { cell.y += stp.y; nextT.y += delta.y; }
                    else { cell.z += stp.z; nextT.z += delta.z; }
                }
                return inside;
            }
            float mosaicCameraMaterial() { return mosaicMaterialAt(mosaicEye()); }
            float mosaicUnderwater() { return mosaicCameraMaterial() == 1.0 ? 1.0 : 0.0; }
            float3 mosaicAlbedo(float material)
            {
                static const float3 pal[15] = { float3(0.0, 0.0, 0.0), float3(0.12, 0.30, 0.50), float3(1.0, 0.45, 0.10),
                    float3(0.50, 0.50, 0.52), float3(0.36, 0.50, 0.22), float3(0.20, 0.42, 0.12), float3(0.30, 0.55, 0.20),
                    float3(0.45, 0.32, 0.18), float3(0.82, 0.76, 0.55), float3(0.95, 0.97, 1.00), float3(0.60, 0.75, 0.95),
                    float3(0.85, 0.90, 0.95), float3(0.75, 0.75, 0.78), float3(1.00, 0.85, 0.55), float3(0.55, 0.50, 0.45) };
                return pal[clamp(int(material + 0.5), 0, 14)];
            }
            float mosaicVoxelTrace(float3 origin, float3 dir, float tMax, out float3 normal, out float material)
            {
                normal = float3(0.0, 0.0, 0.0);
                material = 0.0;
                if (asint(bedrayWorld[3]) == 0) return -1.0;
                float3 p = origin + float3(asfloat(bedrayWorld[4]), asfloat(bedrayWorld[5]), asfloat(bedrayWorld[6]));
                int3 cell = int3(floor(p));
                int3 stp = int3(sign(dir));
                float3 delta = float3(abs(dir.x) > 1e-6 ? 1.0 / abs(dir.x) : 1e30, abs(dir.y) > 1e-6 ? 1.0 / abs(dir.y) : 1e30,
                                      abs(dir.z) > 1e-6 ? 1.0 / abs(dir.z) : 1e30);
                float3 f = p - float3(cell);
                float3 nextT = float3(dir.x > 0.0 ? 1.0 - f.x : f.x, dir.y > 0.0 ? 1.0 - f.y : f.y, dir.z > 0.0 ? 1.0 - f.z : f.z) * delta;
                for (int i = 0; i < 192; i++)
                {
                    float t;
                    if (nextT.x <= nextT.y && nextT.x <= nextT.z) { t = nextT.x; cell.x += stp.x; nextT.x += delta.x; normal = float3(-float(stp.x), 0.0, 0.0); }
                    else if (nextT.y <= nextT.z) { t = nextT.y; cell.y += stp.y; nextT.y += delta.y; normal = float3(0.0, -float(stp.y), 0.0); }
                    else { t = nextT.z; cell.z += stp.z; nextT.z += delta.z; normal = float3(0.0, 0.0, -float(stp.z)); }
                    if (t > tMax) break;
                    uint c = mosaicCell(cell) & 0xFFu;
                    if (c >= 2u) { material = float(c); return t; }
                }
                return -1.0;
            }
            """;

    private static final String SLANG_WORLD_VIEW = """
            float mosaicHitMaterial(float2 uv)
            {
                float t = mosaicDepth(uv);
                return t >= 1.0e5 ? -1.0 : mosaicMaterialAt(mosaicEye() + mosaicViewDir(uv) * (t + 0.02));
            }
            float mosaicHitSkyLight(float2 uv)
            {
                float t = mosaicDepth(uv);
                return t >= 1.0e5 ? 1.0 : mosaicSkyLightAt(mosaicEye() + mosaicViewDir(uv) * max(t - 0.02, 0.0));
            }
            float mosaicHitBlockLight(float2 uv)
            {
                float t = mosaicDepth(uv);
                return t >= 1.0e5 ? 0.0 : mosaicBlockLightAt(mosaicEye() + mosaicViewDir(uv) * max(t - 0.02, 0.0));
            }
            float mosaicMaterialDepth(float2 uv, float material)
            {
                return mosaicMaterialPath(mosaicEye(), mosaicViewDir(uv), min(mosaicDepth(uv), 96.0) + 0.01, material);
            }
            float mosaicWaterDepth(float2 uv) { return mosaicMaterialDepth(uv, 1.0); }
            float mosaicLavaDepth(float2 uv) { return mosaicMaterialDepth(uv, 2.0); }
            """;

    /** Procedural texture helpers (Blender-like noise, voronoi, white noise, checker, wave, gradient). */
    private static final String GLSL_NOISE = """

            vec3 mosaicHash33(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx) * p.zyx); }
            float mosaicHash31(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }
            float mosaicGradNoise(vec3 p) {
                vec3 i = floor(p), f = fract(p);
                vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
                float n000 = dot(mosaicHash33(i) * 2.0 - 1.0, f);
                float n100 = dot(mosaicHash33(i + vec3(1, 0, 0)) * 2.0 - 1.0, f - vec3(1, 0, 0));
                float n010 = dot(mosaicHash33(i + vec3(0, 1, 0)) * 2.0 - 1.0, f - vec3(0, 1, 0));
                float n110 = dot(mosaicHash33(i + vec3(1, 1, 0)) * 2.0 - 1.0, f - vec3(1, 1, 0));
                float n001 = dot(mosaicHash33(i + vec3(0, 0, 1)) * 2.0 - 1.0, f - vec3(0, 0, 1));
                float n101 = dot(mosaicHash33(i + vec3(1, 0, 1)) * 2.0 - 1.0, f - vec3(1, 0, 1));
                float n011 = dot(mosaicHash33(i + vec3(0, 1, 1)) * 2.0 - 1.0, f - vec3(0, 1, 1));
                float n111 = dot(mosaicHash33(i + vec3(1, 1, 1)) * 2.0 - 1.0, f - vec3(1, 1, 1));
                return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y), mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
            }
            float mosaicFbm(vec3 p, float detail, float roughness) {
                float sum = 0.0, amp = 1.0, norm = 0.0;
                int octaves = int(clamp(detail, 0.0, 15.0));
                for (int i = 0; i <= octaves; i++) { sum += amp * mosaicGradNoise(p); norm += amp; amp *= clamp(roughness, 0.0, 1.0); p *= 2.0; }
                return sum / max(norm, 1e-5);
            }
            float mosaicNoise(vec3 p, float detail, float roughness, float distortion) {
                if (distortion != 0.0)
                    p += vec3(mosaicGradNoise(p + vec3(17.3)), mosaicGradNoise(p + vec3(-5.1)), mosaicGradNoise(p + vec3(31.7))) * distortion;
                return mosaicFbm(p, detail, roughness) * 0.5 + 0.5;
            }
            vec3 mosaicNoiseColor(vec3 p, float detail, float roughness, float distortion) {
                return vec3(mosaicNoise(p, detail, roughness, distortion), mosaicNoise(p + vec3(43.1), detail, roughness, distortion),
                            mosaicNoise(p + vec3(-91.7), detail, roughness, distortion));
            }
            void mosaicVoronoi(vec3 p, float randomness, out float dist, out vec3 color, out vec3 pos) {
                vec3 cell = floor(p), f = fract(p);
                dist = 1e9; color = vec3(0.0); pos = vec3(0.0);
                for (int z = -1; z <= 1; z++) for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                    vec3 o = vec3(x, y, z), rnd = mosaicHash33(cell + o) * clamp(randomness, 0.0, 1.0);
                    vec3 d = o + rnd - f;
                    float l = dot(d, d);
                    if (l < dist) { dist = l; color = mosaicHash33(cell + o + vec3(7.0)); pos = cell + o + rnd; }
                }
                dist = sqrt(dist);
            }
            float mosaicChecker(vec3 p) {
                vec3 c = floor(p + 1e-4);
                float s = c.x + c.y + c.z;
                return s - 2.0 * floor(s * 0.5) < 0.5 ? 1.0 : 0.0;
            }
            float mosaicWave(vec3 p, float distortion, float detail, float rings) {
                float n = rings > 0.5 ? length(p) * 20.0 : (p.x + p.y + p.z) * 10.0;
                if (distortion != 0.0) n += distortion * mosaicFbm(p, detail, 0.5);
                return 0.5 + 0.5 * sin(n - 1.5707963);
            }
            float mosaicGradient(vec3 p, float mode) {
                if (mode < 0.5) return p.x;
                if (mode < 1.5) return max(p.x, 0.0) * max(p.x, 0.0);
                if (mode < 2.5) { float t = clamp(p.x, 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
                if (mode < 3.5) return (p.x + p.y) * 0.5;
                if (mode < 4.5) return max(1.0 - length(p), 0.0);
                return atan(p.y, p.x) / 6.2831853 + 0.5;
            }
            """;

    private static final String SLANG_NOISE = """

            float3 mosaicHash33(float3 p) { p = frac(p * float3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return frac((p.xxy + p.yxx) * p.zyx); }
            float mosaicHash31(float3 p) { p = frac(p * 0.1031); p += dot(p, p.zyx + 31.32); return frac((p.x + p.y) * p.z); }
            float mosaicGradNoise(float3 p)
            {
                float3 i = floor(p), f = frac(p);
                float3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
                float n000 = dot(mosaicHash33(i) * 2.0 - 1.0, f);
                float n100 = dot(mosaicHash33(i + float3(1, 0, 0)) * 2.0 - 1.0, f - float3(1, 0, 0));
                float n010 = dot(mosaicHash33(i + float3(0, 1, 0)) * 2.0 - 1.0, f - float3(0, 1, 0));
                float n110 = dot(mosaicHash33(i + float3(1, 1, 0)) * 2.0 - 1.0, f - float3(1, 1, 0));
                float n001 = dot(mosaicHash33(i + float3(0, 0, 1)) * 2.0 - 1.0, f - float3(0, 0, 1));
                float n101 = dot(mosaicHash33(i + float3(1, 0, 1)) * 2.0 - 1.0, f - float3(1, 0, 1));
                float n011 = dot(mosaicHash33(i + float3(0, 1, 1)) * 2.0 - 1.0, f - float3(0, 1, 1));
                float n111 = dot(mosaicHash33(i + float3(1, 1, 1)) * 2.0 - 1.0, f - float3(1, 1, 1));
                return lerp(lerp(lerp(n000, n100, u.x), lerp(n010, n110, u.x), u.y), lerp(lerp(n001, n101, u.x), lerp(n011, n111, u.x), u.y), u.z);
            }
            float mosaicFbm(float3 p, float detail, float roughness)
            {
                float sum = 0.0, amp = 1.0, norm = 0.0;
                int octaves = int(clamp(detail, 0.0, 15.0));
                for (int i = 0; i <= octaves; i++) { sum += amp * mosaicGradNoise(p); norm += amp; amp *= clamp(roughness, 0.0, 1.0); p *= 2.0; }
                return sum / max(norm, 1e-5);
            }
            float mosaicNoise(float3 p, float detail, float roughness, float distortion)
            {
                if (distortion != 0.0)
                    p += float3(mosaicGradNoise(p + 17.3), mosaicGradNoise(p - 5.1), mosaicGradNoise(p + 31.7)) * distortion;
                return mosaicFbm(p, detail, roughness) * 0.5 + 0.5;
            }
            float3 mosaicNoiseColor(float3 p, float detail, float roughness, float distortion)
            {
                return float3(mosaicNoise(p, detail, roughness, distortion), mosaicNoise(p + 43.1, detail, roughness, distortion),
                              mosaicNoise(p - 91.7, detail, roughness, distortion));
            }
            void mosaicVoronoi(float3 p, float randomness, out float dist, out float3 color, out float3 pos)
            {
                float3 cell = floor(p), f = frac(p);
                dist = 1e9; color = float3(0.0, 0.0, 0.0); pos = float3(0.0, 0.0, 0.0);
                for (int z = -1; z <= 1; z++) for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++)
                {
                    float3 o = float3(x, y, z), rnd = mosaicHash33(cell + o) * clamp(randomness, 0.0, 1.0);
                    float3 d = o + rnd - f;
                    float l = dot(d, d);
                    if (l < dist) { dist = l; color = mosaicHash33(cell + o + 7.0); pos = cell + o + rnd; }
                }
                dist = sqrt(dist);
            }
            float mosaicChecker(float3 p)
            {
                float3 c = floor(p + 1e-4);
                float s = c.x + c.y + c.z;
                return s - 2.0 * floor(s * 0.5) < 0.5 ? 1.0 : 0.0;
            }
            float mosaicWave(float3 p, float distortion, float detail, float rings)
            {
                float n = rings > 0.5 ? length(p) * 20.0 : (p.x + p.y + p.z) * 10.0;
                if (distortion != 0.0) n += distortion * mosaicFbm(p, detail, 0.5);
                return 0.5 + 0.5 * sin(n - 1.5707963);
            }
            float mosaicGradient(float3 p, float mode)
            {
                if (mode < 0.5) return p.x;
                if (mode < 1.5) return max(p.x, 0.0) * max(p.x, 0.0);
                if (mode < 2.5) { float t = clamp(p.x, 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }
                if (mode < 3.5) return (p.x + p.y) * 0.5;
                if (mode < 4.5) return max(1.0 - length(p), 0.0);
                return atan2(p.y, p.x) / 6.2831853 + 0.5;
            }
            """;

    private static void function(Out o, MosaicFunction f, boolean slang) {
        int first = o.line;
        StringBuilder sig = new StringBuilder();
        sig.append(slang ? f.output().slang() : f.output().glsl()).append(" mosaic_fn_").append(f.name()).append('(');
        for (int i = 0; i < f.params().size(); i++) {
            MosaicFunction.Param prm = f.params().get(i);
            if (i > 0) sig.append(", ");
            sig.append(slang ? prm.kind().slang() : prm.kind().glsl()).append(' ').append(prm.name());
        }
        sig.append(')');
        o.a("\n// node code: ").a(f.label().replace('\n', ' ')).a("\n").a(sig.toString()).a(" {\n");
        int bodyFirst = o.line;
        String body = slang ? (f.slang() != null ? f.slang() : MosaicSlang.fromGlsl(f.glsl())) : f.glsl();
        o.a(body.endsWith("\n") ? body : body + "\n");
        o.a("}\n");
        o.spans.add(new Span(first, o.line - 1, null, f.name(), bodyFirst));
    }

    private static void node(Out o, MosaicGraph g, Plan p, Node n, boolean slang) {
        Kind k = g.kind(n.id());
        String type = slang ? k.slang() : k.glsl();
        double[] f = p.folded.get(n.id());
        if (f != null) {
            o.a("    " + type + " g_" + n.id() + " = " + MosaicEval.literal(f, k, slang) + ";"
                + (n.type().equals("mosaic.const") ? "" : " // folded " + n.type().substring(7)) + "\n");
            return;
        }
        if (n.type().equals("mosaic.trace")) {
            trace(o, p, n, slang);
            return;
        }
        if (n.type().equals("mosaic.colorRamp")) {
            ramp(o, p, n, slang);
            return;
        }
        if (n.type().equals("mosaic.voronoi")) {
            String v3 = slang ? "float3" : "vec3", id = "g_" + n.id();
            o.a("    float " + id + "; " + v3 + " " + id + "_color, " + id + "_position;\n");
            o.a("    mosaicVoronoi(" + vector(p, n, slang) + " * " + in(p, n, "scale") + ", " + in(p, n, "randomness") + ", " + id + ", "
                + id + "_color, " + id + "_position);\n");
            o.a("    " + id + "_position /= " + in(p, n, "scale") + ";\n");
            return;
        }
        if (n.type().equals("mosaic.checker")) {
            String id = "g_" + n.id();
            o.a("    float " + id + "_fac = mosaicChecker(" + vector(p, n, slang) + " * " + in(p, n, "scale") + ");\n");
            o.a("    " + type + " " + id + " = " + (slang ? "lerp(" : "mix(") + in(p, n, "color2") + ", " + in(p, n, "color1") + ", " + id + "_fac);\n");
            return;
        }
        if (n.type().equals("mosaic.whiteNoise") && namedUsed(g, n.id(), "color")) {
            o.a("    " + (slang ? "float3" : "vec3") + " g_" + n.id() + "_color = mosaicHash33(" + vector(p, n, slang) + " + 19.19);\n");
        }
        if (n.type().equals("mosaic.noise") && namedUsed(g, n.id(), "color")) {
            o.a("    " + (slang ? "float3" : "vec3") + " g_" + n.id() + "_color = mosaicNoiseColor(" + vector(p, n, slang) + " * " + in(p, n, "scale")
                + ", " + in(p, n, "detail") + ", " + in(p, n, "roughness") + ", " + in(p, n, "distortion") + ");\n");
        }
        if (n.type().equals("mosaic.shadow")) {
            shadow(o, p, n, slang);
            return;
        }
        if (n.type().equals("mosaic.separate")) {
            String a = as(in(p, n, "a"), inKind(p, n, "a"), Kind.V4, slang), id = "g_" + n.id(), v4 = slang ? "float4" : "vec4";
            o.a("    " + v4 + " " + id + "_v = " + a + ";\n");
            o.a("    float " + id + " = " + id + "_v.x, " + id + "_y = " + id + "_v.y, " + id + "_z = " + id + "_v.z, " + id + "_w = " + id + "_v.w;\n");
            return;
        }
        if (n.type().equals("mosaic.hueSat")) {
            hueSat(o, p, n, slang);
            return;
        }
        if (n.type().equals("mosaic.repeat")) {
            // a loop as a node: acc = f(acc, i) for i = 0 .. count-1 (the function is the loop body)
            String v4 = slang ? "float4" : "vec4", id = "g_" + n.id(), i = "i_" + n.id();
            String init = n.inputs().containsKey("init") ? as(in(p, n, "init"), inKind(p, n, "init"), Kind.V4, slang) : v4 + "(0.0)";
            o.a("    " + v4 + " " + id + " = " + init + ";\n");
            o.a("    for (int " + i + " = 0; " + i + " < " + Math.clamp(n.index(), 1, 64) + "; " + i + "++) " + id
                + " = mosaic_fn_" + n.mask() + "(" + id + ", float(" + i + ")"
                + (g.functions().get(n.mask()) != null && g.functions().get(n.mask()).params().size() == 3
                    ? ", " + (n.inputs().containsKey("uv") ? in(p, n, "uv") : "m_uv") : "") + ");\n");
            return;
        }
        if (n.type().equals("mosaic.mapping")) {
            String v3 = slang ? "float3" : "vec3", id = "g_" + n.id();
            o.a("    " + v3 + " " + id + " = " + in(p, n, "vector") + " * " + in(p, n, "scale") + ";\n");
            o.a("    { " + v3 + " r = " + in(p, n, "rotation") + "; float cx = cos(r.x), sx = sin(r.x), cy = cos(r.y), sy = sin(r.y), cz = cos(r.z), sz = sin(r.z);\n");
            o.a("      " + id + " = " + v3 + "(" + id + ".x, cx * " + id + ".y - sx * " + id + ".z, sx * " + id + ".y + cx * " + id + ".z);\n");
            o.a("      " + id + " = " + v3 + "(cy * " + id + ".x + sy * " + id + ".z, " + id + ".y, -sy * " + id + ".x + cy * " + id + ".z);\n");
            o.a("      " + id + " = " + v3 + "(cz * " + id + ".x - sz * " + id + ".y, sz * " + id + ".x + cz * " + id + ".y, " + id + ".z); }\n");
            o.a("    " + id + " += " + in(p, n, "location") + ";\n");
            return;
        }
        o.a("    " + type + " g_" + n.id() + " = " + expr(g, p, n, k, slang) + ";\n");
    }

    /** A wired input as its variable, an unwired one as its inline value (or the type's default) literal. */
    private static String in(Plan p, Node n, String port) {
        String wired = n.inputs().get(port);
        if (wired != null) return ref(p.canon(wired));
        double[] d = n.defaultOf(port) != null ? n.defaultOf(port) : MosaicEval.portDefault(n.type(), port);
        if (d == null) d = new double[] {0};
        Kind k = MosaicGraph.acceptedKind(n.type(), port, p.graph.functions());
        if (k == null) k = Kind.ofSize(d.length);
        return MosaicEval.literal(d, k, p.slang);
    }

    /** Kind of what feeds a port (wired source, inline value or default). */
    private static Kind inKind(Plan p, Node n, String port) {
        String wired = n.inputs().get(port);
        if (wired != null) return refKind(p.graph, p.canon(wired));
        Kind k = MosaicGraph.acceptedKind(n.type(), port, p.graph.functions());
        if (k != null) return k;
        double[] d = n.defaultOf(port) != null ? n.defaultOf(port) : MosaicEval.portDefault(n.type(), port);
        return Kind.ofSize(d == null ? 1 : d.length);
    }

    /** Widens a value to a wider kind: a float broadcasts (vec3(x)), a vec3 gets alpha 1, a vec2 gets 0 and alpha 1. */
    private static String as(String expr, Kind from, Kind to, boolean sl) {
        if (from == null || from == to || to.ordinal() < from.ordinal()) return expr;
        String t = sl ? to.slang() : to.glsl();
        if (from == Kind.F) return t + "(" + expr + ")";
        if (from == Kind.V3) return t + "(" + expr + ", 1.0)";
        return to == Kind.V3 ? t + "(" + expr + ", 0.0)" : t + "(" + expr + ", 0.0, 1.0)";
    }

    private static boolean namedUsed(MosaicGraph g, String id, String name) {
        for (Node n : g.nodes()) for (String r : n.inputs().values()) if (r.equals(id + "." + name)) return true;
        return false;
    }

    private static String inOr(Plan p, Node n, String port, String fallback) {
        return n.inputs().containsKey(port) ? in(p, n, port) : fallback;
    }

    private static String expr(MosaicGraph g, Plan p, Node n, Kind k, boolean sl) {
        String uv = n.inputs().containsKey("uv") ? "(" + in(p, n, "uv") + ")" : "m_uv";
        return switch (n.type()) {
            case "mosaic.uv" -> "m_uv";
            case "mosaic.texel" -> (sl ? "float2" : "vec2") + "(m_texel)";
            case "mosaic.size" -> "frame.viewSize.xy";
            case "mosaic.time" -> "frame.timing.x";
            case "mosaic.bufferIn" -> sl ? "bedrayBuf" + n.index() + ".SampleLevel(" + uv + ", 0.0)" : "texture(bedrayBuf" + n.index() + ", " + uv + ")";
            case "mosaic.history" -> sl ? "bedrayHistory.SampleLevel(" + uv + ", 0.0)" : "texture(bedrayHistory, " + uv + ")";
            case "mosaic.reproject" -> "mosaicReproject(" + uv + ")";
            case "mosaic.taa" -> "mosaicTaa(" + (n.mask() == null ? "bedraySceneColor" : "bedrayBuf" + n.index()) + ", " + uv + ", " + in(p, n, "blend") + ")";
            case "mosaic.blur" -> "mosaicBlur(bedrayBuf" + n.index() + ", " + uv + ", " + in(p, n, "radius") + ")";
            case "mosaic.kawase" -> "mosaicKawase(bedrayBuf" + n.index() + ", " + uv + ", " + in(p, n, "radius") + ")";
            case "mosaic.bufferPrev" -> sl ? "bedrayBuf" + n.index() + ".SampleLevel(" + uv + ", 0.0)" : "texture(bedrayBuf" + n.index() + ", " + uv + ")";
            case "mosaic.accumulate" -> "mosaicAccumulate(bedrayBuf" + n.index() + ", bedrayBuf" + (n.value() != null && n.value().length > 0 ? (int) n.value()[0] : n.index())
                + ", " + in(p, n, "color") + ", " + uv + ", " + in(p, n, "frames") + ")";
            case "mosaic.atrous" -> "mosaicAtrous(bedrayBuf" + n.index() + ", " + uv + ", " + in(p, n, "step") + ", " + in(p, n, "edges") + ")";
            case "mosaic.rain" -> "worldInfo.weather.x";
            case "mosaic.thunder" -> "worldInfo.weather.y";
            case "mosaic.temperature" -> "worldInfo.weather.z";
            case "mosaic.skyColor" -> "worldInfo.skyColor.xyz";
            case "mosaic.fogColor" -> "worldInfo.fogColor.xyz";
            case "mosaic.frame" -> "frame.timing.z";
            case "mosaic.eye" -> "mosaicEye()";
            case "mosaic.sceneColor", "bedray.render" -> sl ? "bedraySceneColor.SampleLevel(" + uv + ", 0.0)" : "texture(bedraySceneColor, " + uv + ")";
            case "mosaic.sceneDepth" -> sl ? "bedrayDepth.SampleLevel(" + uv + ", 0.0).r" : "texture(bedrayDepth, " + uv + ").r";
            case "mosaic.lut" -> sl ? "bedrayLut.SampleLevel(" + uv + ", 0.0)" : "texture(bedrayLut, " + uv + ")";
            case "mosaic.reroute" -> in(p, n, "a");
            case "mosaic.math" -> mathExpr(p, n, k, sl);
            case "mosaic.mixColor" -> mixExpr(p, n, k, sl);
            case "mosaic.mapRange" -> mapRangeExpr(p, n, k, sl);
            case "mosaic.vectorMath" -> vectorMathExpr(p, n, sl);
            case "mosaic.brightContrast" -> {
                String c = in(p, n, "color"), ct = in(p, n, "contrast");
                yield (sl ? "float4" : "vec4") + "(max((1.0 + " + ct + ") * (" + c + ").rgb + (" + in(p, n, "bright") + " - " + ct + " * 0.5), 0.0), (" + c + ").a)";
            }
            case "mosaic.gamma" -> {
                String c = in(p, n, "color");
                yield (sl ? "float4" : "vec4") + "(pow(max((" + c + ").rgb, 0.0), " + (sl ? "float3" : "vec3") + "(" + in(p, n, "gamma") + ")), (" + c + ").a)";
            }
            case "mosaic.invert" -> {
                String c = in(p, n, "color");
                yield (sl ? "lerp(" : "mix(") + c + ", " + (sl ? "float4" : "vec4") + "(1.0 - (" + c + ").rgb, (" + c + ").a), " + in(p, n, "fac") + ")";
            }
            case "mosaic.fresnel" -> {
                String ior = in(p, n, "ior");
                String f0 = "pow((" + ior + " - 1.0) / (" + ior + " + 1.0), 2.0)";
                String cosT = "clamp(abs(dot(normalize(" + in(p, n, "normal") + "), normalize(" + in(p, n, "view") + "))), 0.0, 1.0)";
                yield "(" + f0 + " + (1.0 - " + f0 + ") * pow(1.0 - " + cosT + ", 5.0))";
            }
            case "mosaic.smoothstep" -> "smoothstep(" + in(p, n, "edge0") + ", " + in(p, n, "edge1") + ", " + in(p, n, "x") + ")";
            case "mosaic.blackbody" -> {
                String t = "(" + in(p, n, "temperature") + " / 100.0)", v3 = sl ? "float3" : "vec3";
                yield v3 + "(" + t + " <= 66.0 ? 1.0 : clamp(1.29293618606 * pow(max(" + t + " - 60.0, 1e-3), -0.1332047592), 0.0, 1.0), "
                    + t + " <= 66.0 ? clamp(0.39008157876 * log(max(" + t + ", 1e-3)) - 0.63184144378, 0.0, 1.0)"
                    + " : clamp(1.12989086089 * pow(max(" + t + " - 60.0, 1e-3), -0.0755148492), 0.0, 1.0), "
                    + t + " >= 66.0 ? 1.0 : (" + t + " <= 19.0 ? 0.0 : clamp(0.54320678911 * log(max(" + t + " - 10.0, 1e-3)) - 1.19625408914, 0.0, 1.0)))";
            }
            case "mosaic.whiteNoise" -> "mosaicHash31(" + vector(p, n, sl) + ")";
            case "mosaic.wave" -> "mosaicWave(" + vector(p, n, sl) + " * " + in(p, n, "scale") + ", " + in(p, n, "distortion") + ", "
                + in(p, n, "detail") + ", " + ("rings".equals(n.mask()) ? "1.0" : "0.0") + ")";
            case "mosaic.gradient" -> "mosaicGradient(" + vector(p, n, sl) + ", " + Math.max(0, MosaicGraph.GRADIENT_MODES.indexOf(n.mask() == null ? "linear" : n.mask())) + ".0)";
            case "mosaic.noise" -> "mosaicNoise(" + vector(p, n, sl) + " * " + in(p, n, "scale") + ", " + in(p, n, "detail") + ", "
                + in(p, n, "roughness") + ", " + in(p, n, "distortion") + ")";
            case "mosaic.position" -> "mosaicHitPos(" + uv + ")";
            case "mosaic.normal" -> "mosaicNormal(" + uv + ")";
            case "mosaic.sunDir" -> "mosaicSunDir()";
            case "mosaic.worldPos" -> "mosaicWorldPos(" + uv + ")";
            case "mosaic.waterDepth" -> "mosaicWaterDepth(" + uv + ")";
            case "mosaic.waterMask" -> "step(0.001, mosaicWaterDepth(" + uv + "))";
            case "mosaic.lavaMask" -> "step(0.001, mosaicLavaDepth(" + uv + "))";
            case "mosaic.underwater" -> "mosaicUnderwater()";
            case "mosaic.cameraMaterial" -> "mosaicCameraMaterial()";
            case "mosaic.hitMaterial" -> "mosaicHitMaterial(" + uv + ")";
            case "mosaic.materialMask" -> "(mosaicHitMaterial(" + uv + ") == " + n.index() + ".0 ? 1.0 : 0.0)";
            case "mosaic.materialDepth" -> "mosaicMaterialDepth(" + uv + ", " + n.index() + ".0)";
            case "mosaic.skyLight" -> "mosaicHitSkyLight(" + uv + ")";
            case "mosaic.blockLight" -> "mosaicHitBlockLight(" + uv + ")";
            case "mosaic.buffer" -> "bedrayParams[" + n.index() + "]";
            case "mosaic.push" -> "bedrayPush.p[" + n.index() + "]";
            case "mosaic.const" -> MosaicEval.literal(n.value(), k, sl);
            case "mosaic.swizzle" -> "(" + in(p, n, "a") + ")." + n.mask();
            case "mosaic.vec" -> vec(p, n, k, sl);
            case "mosaic.add" -> "(" + in(p, n, "a") + " + " + in(p, n, "b") + ")";
            case "mosaic.sub" -> "(" + in(p, n, "a") + " - " + in(p, n, "b") + ")";
            case "mosaic.mul" -> "(" + in(p, n, "a") + " * " + in(p, n, "b") + ")";
            case "mosaic.div" -> "(" + in(p, n, "a") + " / " + in(p, n, "b") + ")";
            case "mosaic.gt" -> "(" + in(p, n, "a") + " > " + in(p, n, "b") + " ? 1.0 : 0.0)";
            case "mosaic.lt" -> "(" + in(p, n, "a") + " < " + in(p, n, "b") + " ? 1.0 : 0.0)";
            case "mosaic.min", "mosaic.max", "mosaic.step", "mosaic.dot", "mosaic.cross", "mosaic.reflect" ->
                n.type().substring(7) + "(" + in(p, n, "a") + ", " + in(p, n, "b") + ")";
            case "mosaic.mix" -> (sl ? "lerp(" : "mix(") + in(p, n, "a") + ", " + in(p, n, "b") + ", " + inOr(p, n, "t", "0.5") + ")";
            case "mosaic.clamp" -> "clamp(" + in(p, n, "a") + ", " + inOr(p, n, "lo", "0.0") + ", " + inOr(p, n, "hi", "1.0") + ")";
            case "mosaic.neg" -> "(-(" + in(p, n, "a") + "))";
            case "mosaic.fract" -> (sl ? "frac(" : "fract(") + in(p, n, "a") + ")";
            case "mosaic.floor", "mosaic.sqrt", "mosaic.exp", "mosaic.abs", "mosaic.normalize", "mosaic.length" ->
                n.type().substring(7) + "(" + in(p, n, "a") + ")";
            case "mosaic.saturate" -> "clamp(" + in(p, n, "a") + ", 0.0, 1.0)";
            case "mosaic.lum" -> "dot(" + in(p, n, "a") + ", " + (sl ? "float3" : "vec3") + "(0.2126, 0.7152, 0.0722))";
            default -> {
                if (MosaicFunction.isFunctionType(n.type())) yield call(g, p, n, sl);
                throw new IllegalStateException("unhandled node type " + n.type());
            }
        };
    }

    /** Texture coordinate: the wired vector, else screen coordinates corrected for aspect (Blender's "generated" analogue). */
    private static String vector(Plan p, Node n, boolean sl) {
        if (n.inputs().containsKey("vector")) return in(p, n, "vector");
        if (n.defaultOf("vector") != null) return in(p, n, "vector");
        return sl ? "float3(m_uv * float2(frame.viewSize.x / frame.viewSize.y, 1.0), 0.0)" : "vec3(m_uv * vec2(frame.viewSize.x / frame.viewSize.y, 1.0), 0.0)";
    }

    private static String mathExpr(Plan p, Node n, Kind k, boolean sl) {
        String a = as(in(p, n, "a"), inKind(p, n, "a"), k, sl), b = as(in(p, n, "b"), inKind(p, n, "b"), k, sl);
        String one = "1.0";
        String e = switch (n.mask() == null ? "add" : n.mask()) {
            case "add" -> "(" + a + " + " + b + ")";
            case "subtract" -> "(" + a + " - " + b + ")";
            case "multiply" -> "(" + a + " * " + b + ")";
            case "divide" -> "(" + a + " / " + b + ")";
            case "power" -> "pow(" + a + ", " + b + ")";
            case "logarithm" -> "(log(" + a + ") / log(" + b + "))";
            case "sqrt" -> "sqrt(" + a + ")";
            case "inverseSqrt" -> (sl ? "rsqrt(" : "inversesqrt(") + a + ")";
            case "absolute" -> "abs(" + a + ")";
            case "exponent" -> "exp(" + a + ")";
            case "minimum" -> "min(" + a + ", " + b + ")";
            case "maximum" -> "max(" + a + ", " + b + ")";
            case "lessThan" -> "(" + one + " - step(" + b + ", " + a + "))";
            case "greaterThan" -> "(" + one + " - step(" + a + ", " + b + "))";
            case "sign" -> "sign(" + a + ")";
            case "round" -> "floor(" + a + " + 0.5)";
            case "floor" -> "floor(" + a + ")";
            case "ceil" -> "ceil(" + a + ")";
            case "truncate" -> "trunc(" + a + ")";
            case "fraction" -> (sl ? "frac(" : "fract(") + a + ")";
            case "modulo" -> "(" + a + " - " + b + " * floor(" + a + " / " + b + "))";
            case "snap" -> "(floor(" + a + " / " + b + ") * " + b + ")";
            case "pingpong" -> "(" + b + " - abs((" + a + " - 2.0 * " + b + " * floor(" + a + " / (2.0 * " + b + "))) - " + b + "))";
            case "sine" -> "sin(" + a + ")";
            case "cosine" -> "cos(" + a + ")";
            case "tangent" -> "tan(" + a + ")";
            case "arcsine" -> "asin(" + a + ")";
            case "arccosine" -> "acos(" + a + ")";
            case "arctangent" -> "atan(" + a + ")";
            case "arctan2" -> (sl ? "atan2(" : "atan(") + a + ", " + b + ")";
            case "radians" -> "radians(" + a + ")";
            case "degrees" -> "degrees(" + a + ")";
            default -> a;
        };
        return n.clampFlag() ? "clamp(" + e + ", 0.0, 1.0)" : e;
    }

    private static String mixExpr(Plan p, Node n, Kind k, boolean sl) {
        String a = as(in(p, n, "a"), inKind(p, n, "a"), k, sl), b = as(in(p, n, "b"), inKind(p, n, "b"), k, sl), f = in(p, n, "fac");
        String lerp = sl ? "lerp(" : "mix(";
        String target = switch (n.mask() == null ? "mix" : n.mask()) {
            case "add" -> a + " + " + b;
            case "multiply" -> a + " * " + b;
            case "subtract" -> a + " - " + b;
            case "screen" -> "1.0 - (1.0 - " + a + ") * (1.0 - " + b + ")";
            case "overlay" -> lerp + "2.0 * " + a + " * " + b + ", 1.0 - 2.0 * (1.0 - " + a + ") * (1.0 - " + b + "), step(0.5, " + a + "))";
            case "darken" -> "min(" + a + ", " + b + ")";
            case "lighten" -> "max(" + a + ", " + b + ")";
            case "difference" -> "abs(" + a + " - " + b + ")";
            case "divide" -> a + " / " + b;
            case "softLight" -> "(1.0 - 2.0 * " + b + ") * " + a + " * " + a + " + 2.0 * " + b + " * " + a;
            case "linearLight" -> a + " + 2.0 * " + b + " - 1.0";
            case "burn" -> "1.0 - (1.0 - " + a + ") / max(" + b + ", 1e-5)";
            case "dodge" -> a + " / max(1.0 - " + b + ", 1e-5)";
            default -> b;
        };
        String e = lerp + a + ", (" + target + "), " + f + ")";
        return n.clampFlag() ? "clamp(" + e + ", 0.0, 1.0)" : e;
    }

    /** Blender's Vector Math on vec3 (dot, distance and length give a float). */
    private static String vectorMathExpr(Plan p, Node n, boolean sl) {
        String a = in(p, n, "a"), b = in(p, n, "b"), op = n.mask() == null ? "add" : n.mask();
        return switch (op) {
            case "subtract" -> "(" + a + " - " + b + ")";
            case "multiply" -> "(" + a + " * " + b + ")";
            case "divide" -> "(" + a + " / " + b + ")";
            case "crossProduct" -> "cross(" + a + ", " + b + ")";
            case "project" -> "(dot(" + a + ", " + b + ") / max(dot(" + b + ", " + b + "), 1e-8) * " + b + ")";
            case "reflect" -> "reflect(" + a + ", normalize(" + b + "))";
            case "dotProduct" -> "dot(" + a + ", " + b + ")";
            case "distance" -> "distance(" + a + ", " + b + ")";
            case "length" -> "length(" + a + ")";
            case "scale" -> "(" + a + " * " + in(p, n, "scale") + ")";
            case "normalize" -> "normalize(" + a + ")";
            case "absolute" -> "abs(" + a + ")";
            case "minimum" -> "min(" + a + ", " + b + ")";
            case "maximum" -> "max(" + a + ", " + b + ")";
            case "floor" -> "floor(" + a + ")";
            case "ceil" -> "ceil(" + a + ")";
            case "fraction" -> (sl ? "frac(" : "fract(") + a + ")";
            case "modulo" -> "(" + a + " - " + b + " * floor(" + a + " / " + b + "))";
            case "snap" -> "(floor(" + a + " / " + b + ") * " + b + ")";
            case "sine" -> "sin(" + a + ")";
            case "cosine" -> "cos(" + a + ")";
            case "tangent" -> "tan(" + a + ")";
            default -> "(" + a + " + " + b + ")";
        };
    }

    /** Blender's Hue/Saturation/Value: through HSV and back, blended in by fac; alpha kept. */
    private static void hueSat(Out o, Plan p, Node n, boolean sl) {
        String v4 = sl ? "float4" : "vec4", v3 = sl ? "float3" : "vec3", mix = sl ? "lerp(" : "mix(", fr = sl ? "frac(" : "fract(", id = "g_" + n.id();
        o.a("    " + v4 + " " + id + ";\n    { " + v4 + " c = " + in(p, n, "color") + ";\n");
        o.a("      " + v4 + " K = " + v4 + "(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n");
        o.a("      " + v4 + " pp = " + mix + v4 + "(c.bg, K.wz), " + v4 + "(c.gb, K.xy), step(c.b, c.g));\n");
        o.a("      " + v4 + " q = " + mix + v4 + "(pp.xyw, c.r), " + v4 + "(c.r, pp.yzx), step(pp.x, c.r));\n");
        o.a("      float d = q.x - min(q.w, q.y);\n");
        o.a("      " + v3 + " hsv = " + v3 + "(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);\n");
        o.a("      hsv.x = " + fr + "hsv.x + " + in(p, n, "hue") + " - 0.5); hsv.y = clamp(hsv.y * " + in(p, n, "saturation") + ", 0.0, 1.0); hsv.z *= "
            + in(p, n, "value") + ";\n");
        o.a("      " + v3 + " rgb = hsv.z * " + mix + v3 + "(1.0), clamp(abs(" + fr + "hsv.xxx + " + v3 + "(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0), hsv.y);\n");
        o.a("      " + id + " = " + mix + "c, " + v4 + "(rgb, c.a), " + in(p, n, "fac") + "); }\n");
    }

    private static String mapRangeExpr(Plan p, Node n, Kind k, boolean sl) {
        String v = in(p, n, "value"), f0 = in(p, n, "fromMin"), f1 = in(p, n, "fromMax"), t0 = in(p, n, "toMin"), t1 = in(p, n, "toMax");
        String t = "((" + v + " - " + f0 + ") / (" + f1 + " - " + f0 + "))";
        if ("smooth".equals(n.mask())) t = "smoothstep(0.0, 1.0, " + t + ")";
        String e = "(" + t0 + " + " + t + " * (" + t1 + " - " + t0 + "))";
        return n.clampFlag() ? "clamp(" + e + ", min(" + t0 + ", " + t1 + "), max(" + t0 + ", " + t1 + "))" : e;
    }

    /** Color ramp as straight-line code: start at the first stop, blend toward each next stop. */
    private static void ramp(Out o, Plan p, Node n, boolean sl) {
        double[] s = n.value() == null || n.value().length < 5 ? new double[] {0, 0, 0, 0, 1, 1, 1, 1, 1, 1} : n.value();
        String v4 = sl ? "float4" : "vec4", id = "g_" + n.id(), lerp = sl ? "lerp(" : "mix(";
        String mode = n.mask() == null ? "linear" : n.mask();
        o.a("    " + v4 + " " + id + " = " + MosaicEval.literal(new double[] {s[1], s[2], s[3], s[4]}, Kind.V4, sl) + ";\n");
        o.a("    { float rf = clamp(" + in(p, n, "fac") + ", 0.0, 1.0);\n");
        for (int i = 5; i + 4 < s.length; i += 5) {
            String c = MosaicEval.literal(new double[] {s[i + 1], s[i + 2], s[i + 3], s[i + 4]}, Kind.V4, sl);
            String p0 = MosaicEval.number(s[i - 5]), p1 = MosaicEval.number(s[i]);
            String t = switch (mode) {
                case "constant" -> "(rf >= " + p1 + " ? 1.0 : 0.0)";
                case "ease" -> "smoothstep(" + p0 + ", max(" + p1 + ", " + p0 + " + 1e-5), rf)";
                default -> "clamp((rf - " + p0 + ") / max(" + p1 + " - " + p0 + ", 1e-5), 0.0, 1.0)";
            };
            o.a("      " + id + " = " + lerp + id + ", " + c + ", " + t + ");\n");
        }
        o.a("    }\n");
    }

    private static String vec(Plan p, Node n, Kind k, boolean sl) {
        String t = sl ? k.slang() : k.glsl();
        boolean onlyX = !n.inputs().containsKey("y") && !n.inputs().containsKey("z") && !n.inputs().containsKey("w");
        if (onlyX) return t + "(" + in(p, n, "x") + ")";
        StringBuilder sb = new StringBuilder(t).append('(').append(in(p, n, "x"));
        String[] ports = {"y", "z", "w"};
        for (int i = 1; i < k.size(); i++)
            sb.append(", ").append(inOr(p, n, ports[i - 1], i == 3 ? "1.0" : "0.0"));
        return sb.append(')').toString();
    }

    private static String call(MosaicGraph g, Plan p, Node n, boolean sl) {
        MosaicFunction f = g.function(n.type());
        StringBuilder sb = new StringBuilder("mosaic_fn_").append(f.name()).append('(');
        for (int i = 0; i < f.params().size(); i++) {
            MosaicFunction.Param prm = f.params().get(i);
            if (i > 0) sb.append(", ");
            sb.append(n.inputs().containsKey(prm.name()) ? in(p, n, prm.name())
                : MosaicEval.literal(prm.defaultValue(), prm.kind(), sl));
        }
        return sb.append(')').toString();
    }

    private static void trace(Out o, Plan p, Node n, boolean sl) {
        String origin = "(" + inOr(p, n, "origin", "mosaicEye()") + ")";
        String dir = "(" + inOr(p, n, "direction", "mosaicViewDir(m_uv)") + ")";
        String tMax = inOr(p, n, "tMax", "512.0");
        String id = "g_" + n.id();
        if (sl) {
            o.a(String.format("""

                    RayQuery<RAY_FLAG_NONE> %1$s_q;
                    RayDesc %1$s_d;
                    %1$s_d.Origin = %2$s;
                    %1$s_d.Direction = %3$s;
                    %1$s_d.TMin = frame.depthInfo.x;
                    %1$s_d.TMax = %4$s;
                    %1$s_q.TraceRayInline(bedrayScene, RAY_FLAG_NONE, MOSAIC_CULL_MASK, %1$s_d);
                    while (%1$s_q.Proceed()) { }
                    float %1$s = %1$s_q.CommittedStatus() == COMMITTED_TRIANGLE_HIT ? %1$s_q.CommittedRayT() : -1.0;
                    float %1$s_hit = %1$s_q.CommittedStatus() != COMMITTED_NOTHING ? 1.0 : 0.0;
                    float3 %1$s_pos = %2$s + %3$s * max(%1$s, 0.0);
                    float %1$s_inst = float(%1$s_q.CommittedInstanceID());
                    float %1$s_prim = float(%1$s_q.CommittedPrimitiveIndex());
                """, id, origin, dir, tMax));
        } else {
            o.a(String.format("""

                    rayQueryEXT %1$s_q;
                    rayQueryInitializeEXT(%1$s_q, bedrayScene, gl_RayFlagsOpaqueEXT, MOSAIC_CULL_MASK, %2$s, frame.depthInfo.x, %3$s, %4$s);
                    while (rayQueryProceedEXT(%1$s_q)) { }
                    float %1$s = rayQueryGetIntersectionTypeEXT(%1$s_q, true) == gl_RayQueryCommittedIntersectionTriangleEXT ? rayQueryGetIntersectionTEXT(%1$s_q, true) : -1.0;
                    float %1$s_hit = rayQueryGetIntersectionTypeEXT(%1$s_q, true) != gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
                    vec3 %1$s_pos = %2$s + %3$s * max(%1$s, 0.0);
                    float %1$s_inst = float(rayQueryGetIntersectionInstanceCustomIndexEXT(%1$s_q, true));
                    float %1$s_prim = float(rayQueryGetIntersectionPrimitiveIndexEXT(%1$s_q, true));
                """, id, origin, dir, tMax));
        }
    }

    private static void shadow(Out o, Plan p, Node n, boolean sl) {
        String origin = "(" + inOr(p, n, "origin", "mosaicEye()") + ")";
        String dir = "(" + inOr(p, n, "direction", "normalize(MOSAIC_SKY_DIRECTION)") + ")";
        o.a("    float g_" + n.id() + " = mosaicShadow(" + origin + ", " + dir + ");\n");
    }

    // =================================================================== Slang

    private static void slangHeader(Out o, Needs nd, Stats st) {
        o.a("// Generated by Bedray Mosaic from shaders/bedray/mosaic.json - edit the graph, not this file.\n");
        o.a("// Slang mirror of the GLSL emitter: same bindings (set 0: TLAS 0, frame 1, color 2, output 3,\n");
        o.a("// depth 4, user SSBO 5, user LUT 6), same node order and kinds.\n");
        o.a(statsLine(st)).a("\n");
        o.a("struct BedrayFrame\n{\n");
        o.a("    float4x4 projection;\n    float4x4 projectionInverse;\n");
        o.a("    float4x4 modelView;          // camera-relative: bobbing * view rotation, no world translation\n");
        o.a("    float4x4 modelViewInverse;\n");
        o.a("    float4 cameraPosition;       // xyz: world camera position; the scene TLAS is relative to it\n");
        o.a("    float4 viewSize;             // width, height, 1/width, 1/height\n");
        o.a("    float4 timing;               // frameTimeCounter (s), frameTime (s), frameCounter, 0\n");
        o.a("    float4 depthInfo;            // near, far, depthAvailable (1/0), 0\n");
        o.a("};\n[[vk::binding(1, 0)]] ConstantBuffer<BedrayFrame> frame;\n\n");
        if (nd.ray()) o.a("[[vk::binding(0, 0)]] RaytracingAccelerationStructure bedrayScene;\n\n");
        if (nd.scene()) o.a("[[vk::binding(2, 0)]] Sampler2D bedraySceneColor;\n");
        o.a("[[vk::image_format(\"rgba16f\")]] [[vk::binding(3, 0)]] RWTexture2D<float4> bedrayOutput;\n");
        if (nd.depth()) o.a("[[vk::binding(4, 0)]] Sampler2D bedrayDepth; // RT primary-ray distance, 1e6 = no hit\n");
        if (nd.buffer()) o.a("[[vk::binding(5, 0)]] StructuredBuffer<float4> bedrayParams;\n");
        if (nd.lut()) o.a("[[vk::binding(6, 0)]] Sampler2D bedrayLut;\n");
        for (int b : nd.buffers()) o.a("[[vk::binding(" + b + ", 0)]] Sampler2D bedrayBuf" + b + ";\n");
        if (nd.history()) o.a("[[vk::binding(19, 0)]] Sampler2D bedrayHistory;\n");
        if (nd.prev()) o.a("struct BedrayPrevFrame { float4x4 projection; float4x4 modelView; float4 cameraPosition; };\n"
            + "[[vk::binding(17, 0)]] ConstantBuffer<BedrayPrevFrame> prevFrame;\n");
        if (nd.worldInfo()) o.a("struct BedrayWorldInfo { float4 weather; float4 skyColor; float4 fogColor; float4 extra; };\n"
            + "[[vk::binding(16, 0)]] ConstantBuffer<BedrayWorldInfo> worldInfo;\n");
        if (nd.maxPush() >= 0)
            o.a("\nstruct BedrayPush { float4 p[" + (nd.maxPush() + 1) + "]; };\n[[vk::push_constant]] ConstantBuffer<BedrayPush> bedrayPush;\n");
        o.a("""

            float3 mosaicEye() { return mul(frame.modelViewInverse, float4(0.0, 0.0, 0.0, 1.0)).xyz; }
            float3 mosaicViewDir(float2 uv)
            {
                float4 view = mul(frame.projectionInverse, float4(uv * 2.0 - 1.0, 1.0, 1.0));
                return normalize(mul(frame.modelViewInverse, float4(normalize(view.xyz / view.w), 0.0)).xyz);
            }
            float3 mosaicSunDir()
            {
                float a = frame.timing.w * 6.2831853;
                return normalize(float3(-sin(a), cos(a), 0.25));
            }
            float3 mosaicSky(float3 dir)
            {
                float3 sun = mosaicSunDir();
                float day = clamp(sun.y * 2.0 + 0.3, 0.05, 1.0);
                float3 c = lerp(float3(0.70, 0.80, 0.95), float3(0.25, 0.45, 0.85), clamp(dir.y, 0.0, 1.0)) * day;
                return c + float3(1.0, 0.9, 0.7) * pow(max(dot(dir, sun), 0.0), 256.0) * 4.0 * day;
            }
            """);
        if (nd.scene()) o.a("float4 mosaicScene(float2 uv) { return bedraySceneColor.SampleLevel(uv, 0.0); }\n");
        if (nd.depth()) o.a("""
            float mosaicDepth(float2 uv) { return bedrayDepth.SampleLevel(uv, 0.0).r; }
            float3 mosaicHitPos(float2 uv) { return mosaicEye() + mosaicViewDir(uv) * min(mosaicDepth(uv), 4096.0); }
            float3 mosaicWorldPos(float2 uv) { return frame.cameraPosition.xyz + mosaicHitPos(uv); }
            float3 mosaicNormal(float2 uv)
            {
                float2 px = frame.viewSize.zw;
                float3 p = mosaicHitPos(uv);
                float3 x1 = mosaicHitPos(uv + float2(px.x, 0.0)) - p, x0 = p - mosaicHitPos(uv - float2(px.x, 0.0));
                float3 y1 = mosaicHitPos(uv + float2(0.0, px.y)) - p, y0 = p - mosaicHitPos(uv - float2(0.0, px.y));
                float3 dx = dot(x0, x0) < dot(x1, x1) ? x0 : x1, dy = dot(y0, y0) < dot(y1, y1) ? y0 : y1;
                float3 n = normalize(cross(dy, dx));
                return dot(n, mosaicViewDir(uv)) > 0.0 ? -n : n;
            }
            """);
        if (nd.world()) o.a(SLANG_WORLD.replace("%B", String.valueOf(MosaicWorld.BINDING)) + (nd.depth() ? SLANG_WORLD_VIEW : ""));
        if (nd.noise()) o.a(SLANG_NOISE);
        if (nd.blur()) o.a(SLANG_BLUR);
        if (nd.prev()) o.a(SLANG_REPROJECT);
        if (nd.taa()) o.a(SLANG_TAA);
        if (nd.accumulate()) o.a(SLANG_ACCUMULATE);
        if (nd.atrous()) o.a(SLANG_ATROUS);
        if (nd.height()) o.a(MosaicHeight.SLANG);
        if (nd.ray()) o.a("""
            static const uint MOSAIC_CULL_MASK = 0x01; // section geometry
            static const float3 MOSAIC_SKY_DIRECTION = float3(0.32, 0.90, 0.28); // shadow-node default
            float mosaicTrace(float3 origin, float3 dir, float tMax)
            {
                RayQuery<RAY_FLAG_NONE> q;
                RayDesc d;
                d.Origin = origin; d.Direction = dir; d.TMin = 0.001; d.TMax = tMax;
                q.TraceRayInline(bedrayScene, RAY_FLAG_NONE, MOSAIC_CULL_MASK, d);
                while (q.Proceed()) { }
                return q.CommittedStatus() == COMMITTED_TRIANGLE_HIT ? q.CommittedRayT() : -1.0;
            }
            float mosaicShadow(float3 origin, float3 dir)
            {
                RayQuery<RAY_FLAG_NONE> q;
                RayDesc d;
                d.Origin = origin; d.Direction = dir; d.TMin = 0.01; d.TMax = 256.0;
                q.TraceRayInline(bedrayScene, RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH, MOSAIC_CULL_MASK, d);
                while (q.Proceed()) { }
                return q.CommittedStatus() == COMMITTED_NOTHING ? 1.0 : 0.0;
            }
            """);
    }
}
