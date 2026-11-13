package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raster-core translator: rewrites one expanded Iris (OpenGL) GLSL program of the gbuffers/shadow/deferred/composite/final
 * families to Vulkan GLSL 450. Unlike the step-A {@link IrisGlslTranslator} (fullscreen post passes with push constants),
 * geometry passes get real vertex attributes ({@link IrisAttributes}) and all known uniforms live in one std140 UBO at
 * {@link IrisUniforms#UBO_BINDING} with the FIXED canonical offsets of {@link IrisUniforms#UBO_LAYOUT}; samplers bind via
 * {@link IrisUniforms#GB_SAMPLERS} plus pack custom textures. Multiple draw buffers (colortex0..15) are allowed.
 * <p>
 * {@code fullscreen=true} is for deferred/composite/final: no vertex buffer is bound, {@code ftransform()}/{@code gl_Vertex}/
 * {@code gl_MultiTexCoord0} become the {@code gl_VertexIndex} fullscreen triangle, and the host writes identity/ortho matrices
 * into the UBO. {@code fullscreen=false} is for gbuffers/shadow: attributes come from the captured geometry and
 * {@code ftransform()} becomes {@code projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0)}.
 * <p>
 * Anything outside the catalogs is collected by name in {@link Result#unsupported()}; the caller refuses the pack.
 */
public final class IrisGbGlslTranslator {

    /**
     * @param source         Vulkan GLSL for shaderc/slangc
     * @param unsupported    human-readable list of what this program needs and the raster core does not provide
     * @param drawBuffers    colortex indices written (fragment), from RENDERTARGETS/DRAWBUFFERS or the default [0]
     * @param varyings       varying name -> location (vertex: assigned here; fragment: looked up)
     * @param uboUsed        UBO member name -> emitted GLSL type, declaration order
     * @param samplerBindings sampler bindings referenced
     * @param uboOffsets     UBO member name -> absolute byte offset (canonical region, then the pack expression tail)
     * @param uboBytes       total UBO size this program needs (rounded to 16)
     */
    public record Result(String source, Set<String> unsupported, List<Integer> drawBuffers, Map<String, Integer> varyings,
                         Map<String, String> uboUsed, Set<Integer> samplerBindings, Map<String, Integer> uboOffsets, int uboBytes) {}

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[^\\n]*$");
    private static final Pattern DECL = Pattern.compile(
        "^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid|invariant)\\s+)*)"
            + "(uniform|in|out|varying|attribute)\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+([\\w\\s,\\[\\]]+?)\\s*;\\s*$");
    private static final Pattern BLOCK = Pattern.compile("^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:uniform|buffer|readonly\\s+buffer|writeonly\\s+buffer)\\s+\\w+\\s*\\{?\\s*$");
    private static final Pattern TARGETS = Pattern.compile("/\\*\\s*(RENDERTARGETS|DRAWBUFFERS)\\s*:\\s*([0-9, ]+?)\\s*\\*/");
    private static final Pattern FRAGDATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");

    private static final String FULLSCREEN_PRELUDE = String.join("\n",
        "const mat4 vkb_ortho = mat4(2.0, 0.0, 0.0, 0.0,  0.0, 2.0, 0.0, 0.0,  0.0, 0.0, 1.0, 0.0,  -1.0, -1.0, 0.0, 1.0);",
        "vec2 vkb_corner() { return vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2)); }",
        "");

    private static final List<String> UNSUPPORTED_BUILTINS = List.of("gl_LightSource", "gl_Fog",
        "gl_FogFragCoord", "gl_SecondaryColor", "gl_TexCoord", "imageLoad", "imageStore",
        "gl_ModelViewMatrixInverse", "gl_ProjectionMatrixInverse", "gl_ModelViewProjectionMatrixInverse");

    /** OptiFine/Iris gbuffer matrix aliases -> the canonical UBO members the host fills. */
    private static final Map<String, String> GBUFFER_ALIASES = Map.of(
        "gbufferModelView", "modelViewMatrix",
        "gbufferProjection", "projectionMatrix",
        "gbufferModelViewInverse", "modelViewMatrixInverse",
        "gbufferProjectionInverse", "projectionMatrixInverse",
        "gbufferPreviousModelView", "previousModelViewMatrix",
        "gbufferPreviousProjection", "previousProjectionMatrix");

    private static final IrisUniformBlock.Layout NO_EXPR = IrisUniformBlock.layout(Map.of());

    private IrisGbGlslTranslator() {}

    public static Result translate(String expanded, IrisGlslTranslator.Stage stage, Map<String, Integer> varyingLocations,
                                   String displayName) {
        return translate(expanded, stage, varyingLocations, displayName, false, Map.of(), NO_EXPR);
    }

    public static Result translate(String expanded, IrisGlslTranslator.Stage stage, Map<String, Integer> varyingLocations,
                                   String displayName, boolean fullscreen, Map<String, Integer> customSamplers) {
        return translate(expanded, stage, varyingLocations, displayName, fullscreen, customSamplers, NO_EXPR);
    }

    /**
     * @param varyingLocations for VERTEX an empty map that is filled; for FRAGMENT the map produced by the vertex stage
     * @param fullscreen       true for deferred/composite/final (no vertex buffer; fullscreen triangle)
     * @param customSamplers   pack custom texture name -> binding (from shaders.properties texture.* entries)
     * @param exprLayout       pack-wide layout of {@code uniform.*} expression uniforms, appended after the canonical UBO
     */
    public static Result translate(String expanded, IrisGlslTranslator.Stage stage, Map<String, Integer> varyingLocations,
                                   String displayName, boolean fullscreen, Map<String, Integer> customSamplers,
                                   IrisUniformBlock.Layout exprLayout) {
        Set<String> unsupported = new TreeSet<>();
        List<Integer> drawBuffers = new ArrayList<>();
        if (stage == IrisGlslTranslator.Stage.FRAGMENT) {
            Matcher t = TARGETS.matcher(expanded);
            String spec = null;
            boolean drawbuffers = false;
            while (t.find()) {
                spec = t.group(2);
                drawbuffers = t.group(1).equals("DRAWBUFFERS");
            }
            if (spec == null) {
                drawBuffers.add(0);
            } else if (drawbuffers) {
                for (char c : spec.replace(" ", "").toCharArray()) {
                    drawBuffers.add(c - '0');
                }
            } else {
                for (String s : spec.split(",")) {
                    if (!s.isBlank()) {
                        drawBuffers.add(Integer.parseInt(s.trim()));
                    }
                }
            }
            for (int idx : drawBuffers) {
                if (idx < 0 || idx > 15) {
                    unsupported.add("draw buffer colortex" + idx + " (raster core provides colortex0..15)");
                }
            }
        }

        Map<String, String> uboUsed = new LinkedHashMap<>();
        Set<Integer> samplerBindings = new TreeSet<>();
        Map<String, String> samplerRenames = new LinkedHashMap<>();
        Map<String, Integer> fragOutputs = new LinkedHashMap<>();
        Set<String> declaredAttrs = new TreeSet<>();
        boolean usesVaPosition = false;
        boolean usesVaUV0 = false;
        String uncommented = IrisGlslTranslator.stripCommentsPreservingLines(expanded);
        for (Map.Entry<String, String> e : GBUFFER_ALIASES.entrySet()) {
            uncommented = uncommented.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
        }
        StringBuilder body = new StringBuilder();
        for (String line : VERSION_LINE.matcher(uncommented).replaceAll("").split("\n", -1)) {
            if (BLOCK.matcher(line).matches()) {
                unsupported.add("uniform/buffer block: " + line.trim());
                body.append(line).append('\n');
                continue;
            }
            Matcher d = DECL.matcher(line);
            if (!d.matches()) {
                body.append(line).append('\n');
                continue;
            }
            String layout = d.group(1);
            String interp = d.group(2) == null ? "" : d.group(2).trim();
            String storage = d.group(3);
            String type = d.group(4);
            List<String> names = new ArrayList<>();
            for (String n : d.group(5).split(",")) {
                if (!n.isBlank()) {
                    names.add(n.trim());
                }
            }
            StringBuilder repl = new StringBuilder();
            for (String name : names) {
                switch (storage) {
                    case "uniform" -> {
                        if (type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler")
                            || type.startsWith("image")) {
                            Integer b = IrisUniforms.GB_SAMPLERS.get(name);
                            if (b == null) {
                                b = customSamplers.get(name);
                            }
                            boolean okType = type.equals("sampler2D") || type.equals("sampler2DShadow") || type.equals("sampler3D");
                            if (b != null && okType) {
                                samplerBindings.add(b);
                                // "texture" as a variable name shadows the Vulkan texture() builtin after the
                                // texture2D->texture rewrite; rename the variable (not call sites) to avoid that.
                                String emitName = name;
                                if (name.equals("texture") || name.equals("gtexture")) {
                                    emitName = "vkb_tex_" + name;
                                    samplerRenames.put(name, emitName);
                                }
                                repl.append("layout(set = 0, binding = ").append(b).append(") uniform ").append(type).append(' ').append(emitName).append(";\n");
                            } else {
                                unsupported.add("sampler " + type + " " + name);
                            }
                        } else if (IrisUniforms.UBO.containsKey(name)) {
                            String et = emitType(IrisUniforms.UBO.get(name), type);
                            if (et != null) {
                                uboUsed.put(name, et);
                            } else {
                                unsupported.add("uniform " + type + " " + name);
                            }
                        } else if (exprLayout.types().containsKey(name)) {
                            uboUsed.put(name, type);
                        } else {
                            unsupported.add("uniform " + type + " " + name);
                        }
                    }
                    case "attribute" -> {
                        if (stage != IrisGlslTranslator.Stage.VERTEX) {
                            unsupported.add("vertex attribute " + type + " " + name + " in fragment stage");
                            break;
                        }
                        IrisAttributes.Attr a = IrisAttributes.LOCATIONS.get(name);
                        if (a == null || !a.type().equals(type)) {
                            unsupported.add("vertex attribute " + type + " " + name);
                        } else if (fullscreen) {
                            if (name.equals("vaPosition")) {
                                usesVaPosition = true;
                            } else if (name.equals("vaUV0")) {
                                usesVaUV0 = true;
                            } else {
                                unsupported.add("vertex attribute " + type + " " + name + " in a fullscreen pass");
                            }
                        } else {
                            declaredAttrs.add(name);
                            repl.append("layout(location = ").append(a.location()).append(") in ").append(type).append(' ').append(name).append(";\n");
                        }
                    }
                    case "in", "varying" -> {
                        if (stage == IrisGlslTranslator.Stage.VERTEX && storage.equals("in")) {
                            IrisAttributes.Attr a = IrisAttributes.LOCATIONS.get(name);
                            if (a != null && a.type().equals(type) && !fullscreen) {
                                declaredAttrs.add(name);
                                repl.append("layout(location = ").append(a.location()).append(") in ").append(type).append(' ').append(name).append(";\n");
                            } else if (fullscreen && name.equals("vaPosition") && type.equals("vec3")) {
                                usesVaPosition = true;
                            } else if (fullscreen && name.equals("vaUV0") && type.equals("vec2")) {
                                usesVaUV0 = true;
                            } else {
                                unsupported.add("vertex input " + type + " " + name);
                            }
                        } else if (stage == IrisGlslTranslator.Stage.VERTEX) {
                            int loc = varyingLocations.computeIfAbsent(name, k -> varyingLocations.size());
                            repl.append(declare(loc, interp, "out", type, name));
                        } else {
                            Integer loc = varyingLocations.get(name);
                            if (loc == null) {
                                unsupported.add("fragment input " + name + " is not written by the vertex stage");
                            } else {
                                repl.append(declare(loc, interp, "in", type, name));
                            }
                        }
                    }
                    case "out" -> {
                        if (stage == IrisGlslTranslator.Stage.VERTEX) {
                            int loc = varyingLocations.computeIfAbsent(name, k -> varyingLocations.size());
                            repl.append(declare(loc, interp, "out", type, name));
                        } else {
                            int loc = explicitLocation(layout, fragOutputs.size());
                            fragOutputs.put(name, loc);
                            if (loc < 0 || loc > 15) {
                                unsupported.add("fragment output " + name + " at location " + loc + " (raster core provides colortex0..15)");
                            }
                            repl.append("layout(location = ").append(loc).append(") out ").append(type).append(' ').append(name).append(";\n");
                        }
                    }
                    default -> throw new IllegalStateException(storage);
                }
            }
            body.append(repl.length() == 0 ? "\n" : repl);
        }

        String code = body.toString();
        boolean vertex = stage == IrisGlslTranslator.Stage.VERTEX;

        // Rename shadowing sampler variables before texture2D becomes texture() (call sites keep the builtin name).
        for (Map.Entry<String, String> e : samplerRenames.entrySet()) {
            code = code.replaceAll("\\b" + e.getKey() + "\\b(?!\\s*\\()", e.getValue());
        }

        // Legacy texture/shadow functions and vertex ids.
        code = code.replaceAll("\\btexture2DGradARB\\b", "textureGrad").replaceAll("\\btexture2DLodARB\\b", "textureLod");
        code = code.replaceAll("\\btexture2DLod\\b", "textureLod").replaceAll("\\btexture2D\\b", "texture");
        code = code.replaceAll("\\btexture3D\\b", "texture");
        code = wrapShadowCalls(code);
        code = code.replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex").replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex");

        // Compatibility matrices -> the UBO members the host fills (auto-added when the pack never declared them).
        boolean usesMvp = code.matches("(?s).*\\bgl_ModelViewProjectionMatrix\\b.*") || code.matches("(?s).*\\bftransform\\s*\\(\\s*\\).*");
        if (usesMvp) {
            uboUsed.putIfAbsent("modelViewMatrix", "mat4");
            uboUsed.putIfAbsent("projectionMatrix", "mat4");
        }
        if (code.matches("(?s).*\\bgl_ProjectionMatrix\\b.*")) {
            uboUsed.putIfAbsent("projectionMatrix", "mat4");
        }
        if (code.matches("(?s).*\\bgl_ModelViewMatrix\\b.*")) {
            uboUsed.putIfAbsent("modelViewMatrix", "mat4");
        }
        if (code.matches("(?s).*\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\].*")) {
            uboUsed.putIfAbsent("textureMatrix", "mat4");
        }
        if (code.matches("(?s).*\\bgl_TextureMatrix\\s*\\[\\s*1\\s*\\].*")) {
            uboUsed.putIfAbsent("textureMatrix", "mat4");
        }
        boolean usesNormalMatrix = vertex && code.matches("(?s).*\\bgl_NormalMatrix\\b.*");
        if (usesNormalMatrix) {
            uboUsed.putIfAbsent("modelViewMatrix", "mat4");
            code = code.replaceAll("\\bgl_NormalMatrix\\b", "vkb_NormalMatrix");
        }
        code = code.replaceAll("\\bgl_ModelViewProjectionMatrix\\b", "(projectionMatrix * modelViewMatrix)");
        code = code.replaceAll("\\bgl_ProjectionMatrix\\b", "projectionMatrix");
        code = code.replaceAll("\\bgl_ModelViewMatrix\\b", "modelViewMatrix");
        code = code.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]", "textureMatrix");
        code = code.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*1\\s*\\]", "textureMatrix");

        // Geometry built-ins: attributes for geometry passes, fullscreen corners for fullscreen passes.
        Set<String> neededAttrs = new TreeSet<>();
        if (fullscreen) {
            code = code.replaceAll("\\bftransform\\s*\\(\\s*\\)", "(vkb_ortho * vec4(vkb_corner(), 0.0, 1.0))");
            code = code.replaceAll("\\bgl_Vertex\\b", "vec4(vkb_corner(), 0.0, 1.0)");
            code = code.replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(vkb_corner(), 0.0, 1.0)");
            if (usesVaPosition) {
                code = code.replaceAll("\\bvaPosition\\b", "vec3(vkb_corner(), 0.0)");
            }
            if (usesVaUV0) {
                code = code.replaceAll("\\bvaUV0\\b", "vkb_corner()");
            }
        } else {
            if (code.matches("(?s).*\\bftransform\\s*\\(\\s*\\).*") || code.matches("(?s).*\\bgl_Vertex\\b.*")) {
                neededAttrs.add("vaPosition");
            }
            if (code.matches("(?s).*\\bgl_MultiTexCoord0\\b.*")) {
                neededAttrs.add("vaUV0");
            }
            if (code.matches("(?s).*\\bgl_MultiTexCoord1\\b.*")) {
                neededAttrs.add("vaUV1");
            }
            if (code.matches("(?s).*\\bgl_Normal\\b.*")) {
                neededAttrs.add("vaNormal");
            }
            if (vertex && code.matches("(?s).*\\bgl_Color\\b.*")) {
                neededAttrs.add("vaColor");
            }
            code = code.replaceAll("\\bftransform\\s*\\(\\s*\\)", "(projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0))");
            code = code.replaceAll("\\bgl_Vertex\\b", "vec4(vaPosition, 1.0)");
            code = code.replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(vaUV0, 0.0, 1.0)");
            code = code.replaceAll("\\bgl_MultiTexCoord1\\b", "vec4(vaUV1, 0.0, 1.0)");
            code = code.replaceAll("\\bgl_Normal\\b", "vaNormal");
            if (vertex) {
                code = code.replaceAll("\\bgl_Color\\b", "vaColor");
            }
        }
        if (code.matches("(?s).*\\bgl_MultiTexCoord[2-7]\\b.*")) {
            unsupported.add("built-in gl_MultiTexCoord2..7");
        }

        // Legacy color varying: gl_FrontColor (vertex out) / gl_Color (fragment in) -> one named varying.
        boolean frontColorOut = false;
        boolean colorIn = false;
        if (vertex && code.matches("(?s).*\\bgl_FrontColor\\b.*")) {
            int loc = varyingLocations.computeIfAbsent("vkb_Color", k -> varyingLocations.size());
            code = code.replaceAll("\\bgl_FrontColor\\b", "vkb_Color");
            frontColorOut = true;
            varyingLocations.put("vkb_Color", loc);
        }
        if (!vertex && code.matches("(?s).*\\bgl_Color\\b.*")) {
            Integer loc = varyingLocations.get("vkb_Color");
            if (loc == null) {
                unsupported.add("fragment gl_Color without vertex gl_FrontColor");
            } else {
                code = code.replaceAll("\\bgl_Color\\b", "vkb_Color");
                colorIn = true;
            }
        }

        // Fragment outputs.
        boolean fragData = false;
        Set<Integer> fragDataIndices = new TreeSet<>();
        if (!vertex) {
            Matcher fd = FRAGDATA.matcher(code);
            while (fd.find()) {
                int idx = Integer.parseInt(fd.group(1));
                if (idx > 15) {
                    unsupported.add("gl_FragData[" + idx + "] (raster core provides colortex0..15)");
                }
                fragDataIndices.add(idx);
                fragData = true;
            }
            if (code.matches("(?s).*\\bgl_FragColor\\b.*")) {
                fragDataIndices.add(0);
                fragData = true;
            }
            if (fragData) {
                code = FRAGDATA.matcher(code).replaceAll("vkb_FragData$1");
                code = code.replaceAll("\\bgl_FragColor\\b", "vkb_FragData0");
            }
            if (fragData && !fragOutputs.isEmpty()) {
                unsupported.add("gl_FragColor/gl_FragData mixed with declared outputs");
            }
        }

        String stripped = IrisGlslTranslator.stripComments(code);
        for (String id : UNSUPPORTED_BUILTINS) {
            if (Pattern.compile("\\b" + Pattern.quote(id) + "\\b").matcher(stripped).find()) {
                unsupported.add("built-in " + id);
            }
        }

        Map<String, Integer> uboOffsets = new LinkedHashMap<>();
        int uboBytes = 0;
        for (Map.Entry<String, String> e : uboUsed.entrySet()) {
            int off = IrisUniforms.UBO.containsKey(e.getKey())
                ? IrisUniforms.UBO_LAYOUT.offset(e.getKey())
                : IrisUniforms.UBO_LAYOUT.bytes() + exprLayout.offset(e.getKey());
            uboOffsets.put(e.getKey(), off);
            uboBytes = Math.max(uboBytes, off + IrisUniformBlock.byteSize(e.getValue()));
        }
        uboBytes = (uboBytes + 15) / 16 * 16;

        StringBuilder src = new StringBuilder("#version 450\n#extension GL_GOOGLE_cpp_style_line_directive : require\n");
        if (fullscreen && vertex) {
            src.append(FULLSCREEN_PRELUDE);
        }
        if (usesNormalMatrix) {
            src.append("#define vkb_NormalMatrix (mat3(transpose(inverse(modelViewMatrix))))\n");
        }
        if (!uboUsed.isEmpty()) {
            src.append("layout(set = 0, binding = ").append(IrisUniforms.UBO_BINDING).append(") uniform VkbIrisUbo {\n");
            List<Map.Entry<String, String>> sorted = new ArrayList<>(uboUsed.entrySet());
            sorted.sort((a, b) -> Integer.compare(uboOffsets.get(a.getKey()), uboOffsets.get(b.getKey())));
            for (Map.Entry<String, String> e : sorted) {
                src.append("    layout(offset = ").append(uboOffsets.get(e.getKey())).append(") ")
                    .append(e.getValue()).append(' ').append(e.getKey()).append(";\n");
            }
            src.append("};\n");
        }
        for (String attr : neededAttrs) {
            if (!declaredAttrs.contains(attr)) {
                IrisAttributes.Attr a = IrisAttributes.LOCATIONS.get(attr);
                src.append("layout(location = ").append(a.location()).append(") in ").append(a.type()).append(' ').append(attr).append(";\n");
            }
        }
        if (frontColorOut) {
            src.append("layout(location = ").append(varyingLocations.get("vkb_Color")).append(") out vec4 vkb_Color;\n");
        }
        if (colorIn) {
            src.append("layout(location = ").append(varyingLocations.get("vkb_Color")).append(") in vec4 vkb_Color;\n");
        }
        for (int idx : fragDataIndices) {
            src.append("layout(location = ").append(idx).append(") out vec4 vkb_FragData").append(idx).append(";\n");
        }
        src.append("#line 1 \"").append(displayName).append("\"\n").append(code);
        return new Result(src.toString(), unsupported, List.copyOf(drawBuffers), Map.copyOf(varyingLocations),
            Collections.unmodifiableMap(new LinkedHashMap<>(uboUsed)), samplerBindings,
            Collections.unmodifiableMap(new LinkedHashMap<>(uboOffsets)), uboBytes);
    }

    /**
     * Type emitted for a UBO member given the canonical catalog type and the pack's declaration. Scalars are interchangeable;
     * float vectors may shrink/grow within vec2..vec4 (std140 slots are 16 bytes); an int-vector canonical read as a float
     * vector keeps the int type (GLSL implicitly converts int->float). Returns null when incompatible.
     */
    private static String emitType(String canonical, String declared) {
        if (canonical.equals(declared)) {
            return declared;
        }
        Set<String> scalars = Set.of("float", "int", "bool", "uint");
        if (scalars.contains(canonical) && scalars.contains(declared)) {
            return declared;
        }
        if (isFloatVec(canonical) && isFloatVec(declared)) {
            return declared;
        }
        if (isIntVec(canonical) && isIntVec(declared) && vecSize(canonical) == vecSize(declared)) {
            return declared;
        }
        if (isIntVec(canonical) && isFloatVec(declared) && vecSize(canonical) == vecSize(declared)) {
            return canonical;
        }
        return null;
    }

    private static boolean isFloatVec(String t) {
        return t.equals("vec2") || t.equals("vec3") || t.equals("vec4");
    }

    private static boolean isIntVec(String t) {
        return t.equals("ivec2") || t.equals("ivec3") || t.equals("ivec4");
    }

    private static int vecSize(String t) {
        return switch (t) {
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            default -> 1;
        };
    }

    private static String declare(int loc, String interp, String dir, String type, String name) {
        return "layout(location = " + loc + ") " + (interp.isEmpty() ? "" : interp + " ") + dir + " " + type + " " + name + ";\n";
    }

    private static int explicitLocation(String layout, int fallback) {
        if (layout != null) {
            Matcher m = Pattern.compile("location\\s*=\\s*(\\d+)").matcher(layout);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return fallback;
    }

    /**
     * Legacy {@code shadow2D}/{@code shadow2DLod} on a sampler2DShadow returned vec4 (the depth-compare result splatted
     * across all components); Vulkan {@code texture}/{@code textureLod} on a sampler2DShadow return a scalar float. Wrap
     * each call in vec4(...) so pack swizzles such as {@code shadow2D(shadowtex0, p).z} stay valid and keep their value.
     */
    private static String wrapShadowCalls(String code) {
        Matcher m = Pattern.compile("\\bshadow2D(Lod)?\\s*\\(").matcher(code);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (m.start() < last) {
                continue;
            }
            int open = m.end() - 1;
            int close = matchingParen(code, open);
            if (close < 0) {
                continue;
            }
            String args = wrapShadowCalls(code.substring(open + 1, close));
            out.append(code, last, m.start()).append("vec4(").append(m.group(1) != null ? "textureLod" : "texture")
                .append('(').append(args).append("))");
            last = close + 1;
        }
        out.append(code, last, code.length());
        return out.toString();
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}