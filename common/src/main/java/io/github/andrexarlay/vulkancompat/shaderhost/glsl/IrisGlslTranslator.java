package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage I2, second half (step A scope): rewrites the expanded source of one composite/final program from Iris (OpenGL) GLSL to
 * Vulkan GLSL 450 for shaderc. Covered:
 * <ul>
 *   <li>{@code #version N [profile]} -> {@code #version 450} (+ {@code GL_GOOGLE_cpp_style_line_directive});</li>
 *   <li>loose uniforms of {@link IrisUniforms#PUSH} -> one push-constant block with fixed offsets; samplers of
 *       {@link IrisUniforms#SAMPLERS} -> {@code layout(set=0,binding=N)}; the fixed fullscreen matrices -> constants;</li>
 *   <li>varyings ({@code varying}/{@code in}/{@code out}) -> explicit {@code layout(location)} matched BY NAME between stages;</li>
 *   <li>compatibility built-ins of a fullscreen pass: {@code gl_Vertex}, {@code gl_MultiTexCoord0}, {@code ftransform()},
 *       {@code gl_ModelViewProjectionMatrix}, {@code gl_ProjectionMatrix}, {@code gl_ModelViewMatrix}, {@code gl_TextureMatrix[0]},
 *       {@code gl_Color}, core-profile {@code vaPosition}/{@code vaUV0}, constant-index {@code gl_FragData},
 *       {@code texture2D}/{@code texture2DLod};</li>
 *   <li>{@code RENDERTARGETS}/{@code DRAWBUFFERS}: up to eight output locations mapped to colortex0..15.</li>
 * </ul>
 * Everything else that a fullscreen pass could reference (other uniforms/samplers, uniform/buffer blocks, images,
 * vertex attributes, shadow samplers) is collected in {@link Result#unsupported()}; the caller then refuses the pack.
 * <p>
 * Coordinate convention: Minecraft's internal color/depth targets retain GL orientation; its surface presentation flips Y.
 * This host keeps the same positive viewport and copies image rows unchanged, so texcoord/gl_FragCoord agree with GL window
 * coordinates. The vertex entry wrapper converts GL clip depth [-w,w] into Vulkan [0,w].
 */
public final class IrisGlslTranslator {

    public enum Stage { VERTEX, FRAGMENT }

    /**
     * @param source      Vulkan GLSL for shaderc
     * @param unsupported human-readable list of what this program needs and step A does not provide (empty = translatable)
     * @param drawBuffers colortex indices written (fragment), from RENDERTARGETS/DRAWBUFFERS or the default [0]
     * @param varyings    varying name -> location (vertex: assigned here; fragment: looked up)
     * @param pushUsed    push-block members referenced
     * @param samplerBindings sampler bindings referenced
     */
    public record Result(String source, Set<String> unsupported, List<Integer> drawBuffers, Map<String, Integer> varyings,
                         Set<String> pushUsed, Set<Integer> samplerBindings, Map<String, InterfaceVariable> stageInterface,
                         Set<String> frameUsed, List<IrisConstDirectives.ConstDirective> directives, String gaux4Format,
                         boolean samplesGdepth) {
        public Result {
            directives = List.copyOf(directives);
        }

        /** Result without directive metadata (tests and synthetic passes). */
        public Result(String source, Set<String> unsupported, List<Integer> drawBuffers, Map<String, Integer> varyings,
                      Set<String> pushUsed, Set<Integer> samplerBindings, Map<String, InterfaceVariable> stageInterface,
                      Set<String> frameUsed) {
            this(source, unsupported, drawBuffers, varyings, pushUsed, samplerBindings, stageInterface, frameUsed, List.of(), null, false);
        }
    }

    /** A program interface is shared by name AND type; matrices/arrays occupy multiple locations. */
    public record InterfaceVariable(String type, int arraySize, int location, int locations, String interpolation) {}

    private static final Pattern VARIABLE = Pattern.compile("([A-Za-z_]\\w*)(?:\\s*\\[\\s*(\\d+)\\s*\\])?");

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[^\\n]*$");
    private static final Pattern DECL = Pattern.compile(
        "^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid|invariant)\\s+)*)"
            + "(uniform|in|out|varying|attribute)\\s+(?:(?:lowp|mediump|highp)\\s+)?(\\w+)\\s+([\\w\\s,\\[\\]]+?)\\s*;\\s*$");
    private static final Pattern BLOCK = Pattern.compile("^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:uniform|buffer|readonly\\s+buffer|writeonly\\s+buffer)\\s+\\w+\\s*\\{?\\s*$");
    private static final Pattern TARGETS = Pattern.compile("/\\*\\s*(RENDERTARGETS|DRAWBUFFERS)\\b((?:(?!\\*/).)*)\\*/", Pattern.DOTALL);
    private static final Pattern FRAGDATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern GAUX4FORMAT = Pattern.compile("/\\*\\s*GAUX4FORMAT\\s*:\\s*(\\w*)\\s*\\*/");

    private static final String VERTEX_PRELUDE = String.join("\n",
        "const mat4 vkb_ortho = mat4(2.0, 0.0, 0.0, 0.0,  0.0, 2.0, 0.0, 0.0,  0.0, 0.0, 1.0, 0.0,  -1.0, -1.0, 0.0, 1.0);",
        "const mat4 vkb_orthoInverse = mat4(0.5, 0.0, 0.0, 0.0,  0.0, 0.5, 0.0, 0.0,  0.0, 0.0, 1.0, 0.0,  0.5, 0.5, 0.0, 1.0);",
        "vec2 vkb_corner() { return vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2)); }",
        "");
    private static final String FRAGMENT_PRELUDE = String.join("\n",
        "const mat4 vkb_ortho = mat4(2.0, 0.0, 0.0, 0.0,  0.0, 2.0, 0.0, 0.0,  0.0, 0.0, 1.0, 0.0,  -1.0, -1.0, 0.0, 1.0);",
        "const mat4 vkb_orthoInverse = mat4(0.5, 0.0, 0.0, 0.0,  0.0, 0.5, 0.0, 0.0,  0.0, 0.0, 1.0, 0.0,  0.5, 0.5, 0.0, 1.0);",
        "");

    /** Identifiers that need data a fullscreen pass of step A does not have. */
    private static final List<String> UNSUPPORTED_BUILTINS = List.of("gl_Normal", "gl_NormalMatrix", "gl_MultiTexCoord1",
        "gl_MultiTexCoord2", "gl_LightSource", "gl_Fog", "gl_FogFragCoord", "gl_SecondaryColor", "shadow2D", "shadow2DLod",
        "imageLoad", "imageStore", "gl_ModelViewProjectionMatrixInverse", "gl_TexCoord", "gl_FragDepth",
        "gl_FrontFacing", "gl_PointCoord");

    private IrisGlslTranslator() {}

    /**
     * @param varyingLocations for VERTEX an empty map that is filled; for FRAGMENT the map produced by the vertex stage
     * @param displayName      file name used in the leading {@code #line}
     */
    public static Result translate(String expanded, Stage stage, Map<String, Integer> varyingLocations, String displayName) {
        Set<String> unsupported = new TreeSet<>();
        // Target format/clear/mipmap directives are resolved per graph (IrisRenderTargets); here they are only collected.
        List<IrisConstDirectives.ConstDirective> directives = IrisConstDirectives.parse(expanded);
        String gaux4Format = null;
        Matcher gaux4 = GAUX4FORMAT.matcher(expanded);
        while (gaux4.find()) {
            if (gaux4Format != null && !gaux4Format.equals(gaux4.group(1))) unsupported.add("multiple GAUX4FORMAT directives");
            gaux4Format = gaux4.group(1);
        }
        if (gaux4Format == null && Pattern.compile("/\\*\\s*GAUX4FORMAT\\b").matcher(expanded).find())
            unsupported.add("malformed GAUX4FORMAT directive");
        boolean samplesGdepth = false;
        List<Integer> drawBuffers = new ArrayList<>();
        if (stage == Stage.FRAGMENT) {
            drawBuffers.addAll(parseDrawBuffers(expanded, unsupported));
            if (displayName.replace('\\', '/').matches("(?:.*/)?final(?:_[a-z])?\\.fsh")
                && !drawBuffers.equals(List.of(0)))
                unsupported.add("final writes one presentation output at location 0 (draw buffers must be [0])");
        }

        Map<String, String> pushDecl = new LinkedHashMap<>();
        Map<String, InterfaceVariable> stageInterface = new LinkedHashMap<>();
        Set<Integer> samplerBindings = new TreeSet<>();
        Set<String> pushUsed = new TreeSet<>();
        Set<String> frameUsed = new TreeSet<>();
        Map<String, Integer> fragOutputs = new LinkedHashMap<>();
        boolean usesVaPosition = false;
        boolean usesVaUV0 = false;
        String uncommented = stripCommentsPreservingLines(expanded);
        StringBuilder body = new StringBuilder();
        for (String line : topLevelUnits(VERSION_LINE.matcher(uncommented).replaceAll(""))) {
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
                Matcher variable = VARIABLE.matcher(name);
                if (!variable.matches()) {
                    unsupported.add("unsupported declaration " + type + " " + name);
                    continue;
                }
                String identifier = variable.group(1);
                int arraySize = 0;
                if (variable.group(2) != null) {
                    try { arraySize = Integer.parseInt(variable.group(2)); }
                    catch (NumberFormatException e) { unsupported.add("array size " + name); continue; }
                    if (arraySize < 1 || arraySize > 16) { unsupported.add("array size " + name); continue; }
                }
                switch (storage) {
                    case "uniform" -> {
                        if (arraySize != 0) { unsupported.add("uniform array " + name); continue; }
                        if (type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler")
                            || type.startsWith("image")) {
                            Integer b = IrisUniforms.SAMPLERS.get(name);
                            if (b != null && type.equals("sampler2D")) {
                                samplerBindings.add(b);
                                if (name.equals("gdepth")) samplesGdepth = true;
                                repl.append("layout(set = 0, binding = ").append(b).append(") uniform sampler2D ").append(name).append(";\n");
                            } else {
                                unsupported.add("sampler " + type + " " + name);
                            }
                        } else if (IrisUniforms.PUSH.containsKey(name) && IrisUniforms.PUSH.get(name).glslType().equals(type)) {
                            pushDecl.put(name, type);
                            pushUsed.add(name);
                        } else if (IrisUniforms.FRAME.containsKey(name) && IrisUniforms.FRAME.get(name).glslType().equals(type)) {
                            frameUsed.add(name);
                        } else if (IrisUniforms.CONSTANT_MATRICES.contains(name) && type.equals("mat4")) {
                            // replaced by constants below
                        } else {
                            unsupported.add("uniform " + type + " " + name);
                        }
                    }
                    case "attribute" -> unsupported.add("vertex attribute " + type + " " + name);
                    case "in", "varying" -> {
                        if (stage == Stage.VERTEX && storage.equals("in")) {
                            if (name.equals("vaPosition") && type.equals("vec3")) {
                                usesVaPosition = true;
                            } else if (name.equals("vaUV0") && type.equals("vec2")) {
                                usesVaUV0 = true;
                            } else {
                                unsupported.add("vertex input " + type + " " + name);
                            }
                        } else if (stage == Stage.VERTEX) { // varying in the vertex stage = output
                            int loc = addInterface(stageInterface, varyingLocations, identifier, type, arraySize, interp,
                                true, unsupported);
                            repl.append(declare(loc, interp, "out", type, name));
                        } else {
                            Integer loc = varyingLocations.get(identifier);
                            if (loc == null) {
                                unsupported.add("fragment input " + name + " is not written by the vertex stage");
                            } else {
                                addInterface(stageInterface, varyingLocations, identifier, type, arraySize, interp,
                                    false, unsupported);
                                repl.append(declare(loc, interp, "in", type, name));
                            }
                        }
                    }
                    case "out" -> {
                        if (stage == Stage.VERTEX) {
                            int loc = addInterface(stageInterface, varyingLocations, identifier, type, arraySize, interp,
                                true, unsupported);
                            repl.append(declare(loc, interp, "out", type, name));
                        } else {
                            if (arraySize != 0) { unsupported.add("fragment output array " + name); continue; }
                            int loc = outputLocation(layout, fragOutputs, unsupported);
                            if (!type.equals("vec4")) unsupported.add("fragment output " + type + " " + name + " requires vec4 color");
                            if (fragOutputs.containsKey(name)) unsupported.add("duplicate fragment output " + name);
                            if (fragOutputs.containsValue(loc)) unsupported.add("duplicate fragment output location " + loc);
                            fragOutputs.put(name, loc);
                            if (loc < 0 || loc >= drawBuffers.size())
                                unsupported.add("fragment output " + name + " at location " + loc + " exceeds draw buffers " + drawBuffers);
                            repl.append("layout(location = ").append(loc).append(") out ").append(type).append(' ').append(name).append(";\n");
                        }
                    }
                    default -> throw new IllegalStateException(storage);
                }
            }
            body.append(repl.length() == 0 ? "" : repl).append(repl.length() == 0 ? "\n" : "");
        }

        String code = body.toString();
        code = code.replaceAll("\\btexture2DLod\\b", "textureLod").replaceAll("\\btexture2D\\b", "texture");
        code = code.replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex").replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex");
        code = code.replaceAll("\\bftransform\\s*\\(\\s*\\)", "(vkb_ortho * vec4(vkb_corner(), 0.0, 1.0))");
        code = code.replaceAll("\\bgl_Vertex\\b", "vec4(vkb_corner(), 0.0, 1.0)");
        code = code.replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(vkb_corner(), 0.0, 1.0)");
        code = code.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]", "mat4(1.0)");
        code = code.replaceAll("\\bgl_ModelViewProjectionMatrix\\b", "vkb_ortho");
        code = code.replaceAll("\\bgl_ProjectionMatrix\\b", "vkb_ortho");
        code = code.replaceAll("\\bgl_ModelViewMatrix\\b", "mat4(1.0)");
        code = code.replaceAll("\\bgl_Color\\b", "vec4(1.0)");
        code = code.replaceAll("\\bprojectionMatrixInverse\\b", "vkb_orthoInverse");
        code = code.replaceAll("\\bprojectionMatrix\\b", "vkb_ortho");
        code = code.replaceAll("\\bmodelViewMatrixInverse\\b", "mat4(1.0)");
        code = code.replaceAll("\\bmodelViewMatrix\\b", "mat4(1.0)");
        code = code.replaceAll("\\btextureMatrix\\b", "mat4(1.0)");
        if (usesVaPosition) {
            code = code.replaceAll("\\bvaPosition\\b", "vec3(vkb_corner(), 0.0)");
        }
        if (usesVaUV0) {
            code = code.replaceAll("\\bvaUV0\\b", "vkb_corner()");
        }
        if (stage == Stage.VERTEX) {
            code = code.replaceAll("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)", "void vkb_packMain()");
            code += "\nvoid main() { vkb_packMain(); gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5; }\n";
        }
        Set<Integer> legacyOutputs = new TreeSet<>();
        if (stage == Stage.FRAGMENT) {
            Matcher fd = FRAGDATA.matcher(code);
            StringBuilder replaced = new StringBuilder();
            while (fd.find()) {
                int location;
                try { location = Integer.parseInt(fd.group(1)); }
                catch (NumberFormatException e) { location = -1; }
                if (location < 0 || location >= drawBuffers.size())
                    unsupported.add("gl_FragData[" + fd.group(1) + "] exceeds draw buffers " + drawBuffers);
                else legacyOutputs.add(location);
                fd.appendReplacement(replaced, "vkb_FragData" + Math.max(0, location));
            }
            fd.appendTail(replaced);
            code = replaced.toString();
            if (Pattern.compile("\\bgl_FragData\\b").matcher(code).find())
                unsupported.add("gl_FragData needs a constant nonnegative output location");
            boolean fragColor = Pattern.compile("\\bgl_FragColor\\b").matcher(code).find();
            if (fragColor) {
                if (!legacyOutputs.isEmpty()) unsupported.add("gl_FragColor mixed with gl_FragData");
                legacyOutputs.add(0);
                code = code.replaceAll("\\bgl_FragColor\\b", "vkb_FragData0");
            }
            if (!legacyOutputs.isEmpty() && !fragOutputs.isEmpty()) {
                unsupported.add("gl_FragColor/gl_FragData mixed with declared outputs");
            }
            Set<Integer> outputs = new TreeSet<>(fragOutputs.values());
            outputs.addAll(legacyOutputs);
            for (int location = 0; location < drawBuffers.size(); location++)
                if (!outputs.contains(location)) unsupported.add("draw-buffer location " + location + " has no fragment output");
        }
        String stripped = stripComments(code);
        for (String id : UNSUPPORTED_BUILTINS) {
            if (Pattern.compile("\\b" + Pattern.quote(id) + "\\b").matcher(stripped).find()) {
                unsupported.add("built-in " + id);
            }
        }

        StringBuilder src = new StringBuilder("#version 450\n#extension GL_GOOGLE_cpp_style_line_directive : require\n");
        src.append(stage == Stage.VERTEX ? VERTEX_PRELUDE : FRAGMENT_PRELUDE);
        if (!pushDecl.isEmpty()) {
            src.append("layout(push_constant) uniform VkbIrisUniforms {\n");
            List<Map.Entry<String, String>> sortedPush = new ArrayList<>(pushDecl.entrySet());
            sortedPush.sort((a, b) -> Integer.compare(
                IrisUniforms.PUSH.get(a.getKey()).offset(),
                IrisUniforms.PUSH.get(b.getKey()).offset()
            ));
            for (Map.Entry<String, String> e : sortedPush) {
                src.append("    layout(offset = ").append(IrisUniforms.PUSH.get(e.getKey()).offset()).append(") ")
                    .append(e.getValue()).append(' ').append(e.getKey()).append(";\n");
            }
            src.append("};\n");
        }
        for (int location : legacyOutputs)
            src.append("layout(location = ").append(location).append(") out vec4 vkb_FragData").append(location).append(";\n");
        if (!frameUsed.isEmpty()) {
            src.append("layout(std140, set = 0, binding = ").append(IrisUniforms.BINDING_FRAME).append(") uniform VkbIrisFrame {\n");
            IrisUniforms.FRAME.forEach((name, member) -> {
                if (frameUsed.contains(name)) src.append("    layout(offset = ").append(member.offset()).append(") ")
                    .append(member.glslType()).append(' ').append(name).append(";\n");
            });
            src.append("};\n");
        }
        src.append("#line 1 \"").append(displayName).append("\"\n").append(code);
        return new Result(src.toString(), unsupported, List.copyOf(drawBuffers), Map.copyOf(varyingLocations),
            pushUsed, samplerBindings, Map.copyOf(stageInterface), Set.copyOf(frameUsed), directives, gaux4Format, samplesGdepth);
    }

    /** Validate before either module reaches pipeline creation; successful individual compiles cannot establish this. */
    public static Set<String> validateInterface(Result vertex, Result fragment) {
        Set<String> errors = new TreeSet<>();
        fragment.stageInterface().forEach((name, input) -> {
            InterfaceVariable output = vertex.stageInterface().get(name);
            if (output == null) errors.add("fragment input " + name + " has no vertex output");
            else if (!output.equals(input)) errors.add("stage interface mismatch " + name + ": vertex " + output + ", fragment " + input);
        });
        // Push members use the same typed catalogue and explicit offsets in both stages.
        // Unread vertex outputs are legal: this host submits explicit Vulkan locations without game reflection remapping.
        return errors;
    }

    private static int addInterface(Map<String, InterfaceVariable> variables, Map<String, Integer> locations,
                                    String name, String type, int arraySize, String qualifiers, boolean vertex,
                                    Set<String> unsupported) {
        int span = locationSpan(type);
        if (span == 0) { unsupported.add("interface type " + type + " " + name); span = 1; }
        span *= Math.max(1, arraySize);
        int next = variables.values().stream().mapToInt(v -> v.location() + v.locations()).max().orElse(0);
        int location = vertex ? next : locations.getOrDefault(name, 0);
        if (location + span > 16) unsupported.add("post interface exceeds 16 locations at " + name);
        String interpolation = Pattern.compile("\\bflat\\b").matcher(qualifiers).find() ? "flat"
            : Pattern.compile("\\bnoperspective\\b").matcher(qualifiers).find() ? "noperspective" : "smooth";
        InterfaceVariable previous = variables.putIfAbsent(name, new InterfaceVariable(type, arraySize, location, span, interpolation));
        if (previous != null) unsupported.add("duplicate interface variable " + name);
        if (vertex) locations.put(name, location);
        return location;
    }

    private static int locationSpan(String type) {
        if (type.matches("(?:float|int|uint|bool|double|[biud]?vec[234])"))
            return type.equals("dvec3") || type.equals("dvec4") ? 2 : 1;
        Matcher matrix = Pattern.compile("(d?)mat([234])(?:x([234]))?").matcher(type);
        if (!matrix.matches()) return 0;
        int columns = Integer.parseInt(matrix.group(2));
        int rows = matrix.group(3) == null ? columns : Integer.parseInt(matrix.group(3));
        return columns * (!matrix.group(1).isEmpty() && rows > 2 ? 2 : 1);
    }

    /** Split at top-level statement boundaries, not physical lines. Functions remain untouched. */
    private static List<String> topLevelUnits(String source) {
        List<String> units = new ArrayList<>();
        int start = 0, braces = 0, parentheses = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '#' && source.substring(start, i).isBlank()) {
                int end = source.indexOf('\n', i);
                if (end < 0) end = source.length();
                units.add(source.substring(start, end));
                start = end;
                i = end - 1;
            } else if (c == '(') parentheses++;
            else if (c == ')') parentheses--;
            else if (c == '{') {
                if (braces++ == 0) { units.add(source.substring(start, i + 1)); start = i + 1; }
            } else if (c == '}') {
                if (--braces == 0) { units.add(source.substring(start, i + 1)); start = i + 1; }
            } else if (c == ';' && braces == 0 && parentheses == 0) {
                units.add(source.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < source.length()) units.add(source.substring(start));
        return units;
    }

    private static String declare(int loc, String interp, String dir, String type, String name) {
        return "layout(location = " + loc + ") " + (interp.isEmpty() ? "" : interp + " ") + dir + " " + type + " " + name + ";\n";
    }

    private static int outputLocation(String layout, Map<String, Integer> outputs, Set<String> unsupported) {
        if (layout != null) {
            Matcher m = Pattern.compile("\\s*location\\s*=\\s*(\\d+)\\s*").matcher(layout);
            if (m.matches()) {
                try { return Integer.parseInt(m.group(1)); }
                catch (NumberFormatException e) { /* Diagnosed below instead of failing translation. */ }
            }
            unsupported.add("fragment output layout " + layout + " needs one integer location");
            return -1;
        }
        int free = 0;
        while (outputs.containsValue(free)) free++;
        return free;
    }

    private static List<Integer> parseDrawBuffers(String expanded, Set<String> unsupported) {
        Matcher targets = TARGETS.matcher(expanded);
        List<Integer> result = null;
        while (targets.find()) {
            if (result != null) unsupported.add("multiple active draw-buffer directives");
            result = new ArrayList<>();
            String name = targets.group(1);
            String body = targets.group(2).strip();
            if (!body.startsWith(":")) {
                unsupported.add("malformed " + name + " directive: missing colon");
                continue;
            }
            String spec = body.substring(1).strip();
            if (name.equals("DRAWBUFFERS")) {
                String digits = spec.replaceAll("\\s", "");
                if (!digits.matches("[0-9]+")) unsupported.add("malformed DRAWBUFFERS " + spec + " (expected digits 0..9)");
                else for (char digit : digits.toCharArray()) result.add(digit - '0');
            } else {
                if (!spec.matches("\\d{1,2}(?:\\s*,\\s*\\d{1,2})*"))
                    unsupported.add("malformed RENDERTARGETS " + spec + " (expected comma-separated colortex indices)");
                else for (String target : spec.split(",")) {
                    int index = Integer.parseInt(target.strip());
                    if (index >= IrisUniforms.COLOR_TARGETS)
                        unsupported.add("RENDERTARGETS colortex" + index + " (this host provides colortex0.." + (IrisUniforms.COLOR_TARGETS - 1) + ")");
                    else result.add(index);
                }
            }
            if (result.isEmpty() || result.size() > IrisUniforms.MAX_DRAW_BUFFERS)
                unsupported.add("draw buffers need 1.." + IrisUniforms.MAX_DRAW_BUFFERS + " color attachments: " + result);
            if (new TreeSet<>(result).size() != result.size()) unsupported.add("duplicate draw-buffer targets " + result);
        }
        if (result == null) return List.of(0);
        // Unsupported directives are refused by the caller; keep generated diagnostics bounded and valid.
        return result.isEmpty() ? List.of(0) : List.copyOf(result);
    }

    static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", "");
    }

    /**
     * Strips C-style comments, replacing comment characters with spaces (preserving newlines)
     * so that line numbering in compiler diagnostics and #line directives remains exactly intact.
     */
    public static String stripCommentsPreservingLines(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(source.length());
        int len = source.length();
        int i = 0;
        while (i < len) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < len) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    sb.append(' ').append(' ');
                    i += 2;
                    while (i < len && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                        sb.append(' ');
                        i++;
                    }
                    continue;
                } else if (next == '*') {
                    sb.append(' ').append(' ');
                    i += 2;
                    while (i + 1 < len && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                        char b = source.charAt(i);
                        sb.append(b == '\n' || b == '\r' ? b : ' ');
                        i++;
                    }
                    if (i < len) {
                        sb.append(' '); // '*'
                        i++;
                    }
                    if (i < len) {
                        sb.append(' '); // '/'
                        i++;
                    }
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
