package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Mosaic shader graph as an Iris shader pack: the graph's GLSL becomes {@code composite.fsh} (Iris uniforms in place
 * of Bedray's bindings: colortex0 for the scene, view size, time, camera matrices, rain / thunder, sky and fog colour)
 * with the standard full-screen {@code composite.vsh}. Nodes Iris has nothing for (rays, the world volume, Bedray's
 * depth and buffers, multi-pass graphs) are refused with their names.
 */
public final class MosaicIrisExport {

    private MosaicIrisExport() {}

    public record Result(Map<String, String> files, List<String> problems) {
        public boolean ok() { return problems.isEmpty(); }
    }

    /** Node types the export can carry over. */
    static boolean supported(String type) {
        if (type.startsWith("mat.") || type.startsWith("bedray.") || type.startsWith("slang.") || type.startsWith("user.")) return false;
        return switch (type) {
            case "mosaic.trace", "mosaic.shadow", "mosaic.sceneDepth", "mosaic.position", "mosaic.worldPos", "mosaic.normal",
                 "mosaic.waterDepth", "mosaic.waterMask", "mosaic.lavaMask", "mosaic.underwater", "mosaic.hitMaterial",
                 "mosaic.materialMask", "mosaic.materialDepth", "mosaic.skyLight", "mosaic.blockLight", "mosaic.cameraMaterial",
                 "mosaic.buffer", "mosaic.push", "mosaic.lut", "mosaic.pass", "mosaic.bufferIn", "mosaic.blur", "mosaic.kawase", "mosaic.history", "mosaic.reproject", "mosaic.taa", "mosaic.repeat", "mosaic.bufferPrev", "mosaic.accumulate", "mosaic.atrous", "mosaic.average", "mosaic.ssr", "mosaic.temperature" -> false;
            default -> true;
        };
    }

    public static Result export(MosaicGraph g, String name) {
        List<String> problems = new ArrayList<>();
        if (g == null) return new Result(Map.of(), List.of("the graph has errors"));
        if (g.isMaterial() || g.isRenderer() || g.isPackMap() || g.isFunctionGraph() || g.isStageMap())
            return new Result(Map.of(), List.of("only shader graphs export to Iris"));
        if (g.multiPass()) problems.add("multi-pass graphs (Buffer nodes) do not export to Iris yet");
        for (MosaicGraph.Node n : g.nodes())
            if (!supported(n.type()) && !MosaicFunction.isFunctionType(n.type()))
                problems.add("node " + n.id() + " (" + MosaicCatalog.of(n.type()).label() + ") has nothing to read in Iris");
        if (!problems.isEmpty()) return new Result(Map.of(), problems);
        String glsl = MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false).source();
        String fsh;
        try {
            fsh = fragment(glsl);
        } catch (IllegalStateException e) {
            return new Result(Map.of(), List.of(e.getMessage()));
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put("shaders/composite.fsh", fsh);
        files.put("shaders/composite.vsh", VERTEX);
        files.put("shaders/shaders.properties", "# Exported from Mosaic Studio: " + name + "\nprogram.composite.enabled=true\n");
        files.put("README.txt", "Shader pack " + name + ", exported from a Mosaic Studio graph (Bedray). Iris / OptiFine format:\n"
            + "one composite pass over the scene colour. Edit the graph in Mosaic Studio, not these files.\n");
        return new Result(files, List.of());
    }

    static final String VERTEX = """
        #version 330 compatibility
        // Mosaic Studio: full-screen pass
        out vec2 texcoord;
        void main() {
            gl_Position = ftransform();
            texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
        }
        """;

    static final String IRIS_HEADER = """
        #version 330 compatibility
        // Exported by Mosaic Studio for Iris: the graph's own code with Iris uniforms in place of Bedray's bindings.
        uniform sampler2D colortex0;
        uniform float viewWidth, viewHeight, frameTimeCounter, frameTime, near, far, rainStrength, thunderStrength, sunAngle;
        uniform int frameCounter;
        uniform vec3 cameraPosition, skyColor, fogColor;
        uniform mat4 gbufferProjection, gbufferProjectionInverse, gbufferModelView, gbufferModelViewInverse;
        in vec2 texcoord;
        /* DRAWBUFFERS:0 */
        layout(location = 0) out vec4 mosaicFragColor;
        struct MosaicIrisFrame { mat4 projection; mat4 projectionInverse; mat4 modelView; mat4 modelViewInverse; vec4 cameraPosition;
            vec4 viewSize; vec4 timing; vec4 depthInfo; };
        MosaicIrisFrame frame;
        struct MosaicIrisWorld { vec4 weather; vec4 skyColor; vec4 fogColor; vec4 extra; };
        MosaicIrisWorld worldInfo;
        #define bedraySceneColor colortex0
        """;

    /** The compute shader of the graph, rewritten as an Iris composite fragment shader. */
    static String fragment(String glsl) {
        String s = glsl;
        s = s.replaceFirst("#version 460\\s*\\n", "");
        s = s.replaceAll("layout\\(local_size_x[^;]*;\\s*\\n", "");
        s = s.replaceAll("(?s)layout\\(std140, set = 0, binding = 1\\) uniform BedrayFrame \\{.*?\\} frame;\\s*\\n", "");
        s = s.replaceAll("(?s)layout\\(std140, set = 0, binding = 16\\) uniform BedrayWorldInfo \\{.*?\\} worldInfo;[^\\n]*\\n", "");
        s = s.replaceAll("layout\\(set = 0, binding = 2\\) uniform sampler2D bedraySceneColor;\\s*\\n", "");
        s = s.replaceAll("layout\\(set = 0, binding = 3, rgba16f\\) uniform writeonly image2D bedrayOutput;\\s*\\n", "");
        Matcher left = Pattern.compile("layout\\([^)]*set = 0[^)]*\\)[^;]*;").matcher(s);
        if (left.find()) throw new IllegalStateException("the graph uses a Bedray binding Iris does not have: " + left.group());
        String main = """
            void main() {
                frame.projection = gbufferProjection;
                frame.projectionInverse = gbufferProjectionInverse;
                frame.modelView = gbufferModelView;
                frame.modelViewInverse = gbufferModelViewInverse;
                frame.cameraPosition = vec4(cameraPosition, 0.0);
                frame.viewSize = vec4(viewWidth, viewHeight, 1.0 / viewWidth, 1.0 / viewHeight);
                frame.timing = vec4(frameTimeCounter, frameTime, float(frameCounter), sunAngle - 0.25);
                frame.depthInfo = vec4(near, far, 0.0, 0.0);
                worldInfo.weather = vec4(rainStrength, thunderStrength, 0.5, 0.0);
                worldInfo.skyColor = vec4(skyColor, 1.0);
                worldInfo.fogColor = vec4(fogColor, 1.0);
                ivec2 m_size = ivec2(viewWidth, viewHeight);
                ivec2 m_texel = ivec2(gl_FragCoord.xy);
                vec2 m_uv = texcoord;
            """;
        s = s.replaceFirst("(?s)void main\\(\\) \\{\\s*ivec2 m_texel = ivec2\\(gl_GlobalInvocationID\\.xy\\);\\s*ivec2 m_size = imageSize\\(bedrayOutput\\);\\s*"
            + "if \\(m_texel\\.x >= m_size\\.x \\|\\| m_texel\\.y >= m_size\\.y\\) return;\\s*vec2 m_uv = \\(vec2\\(m_texel\\) \\+ 0\\.5\\) / vec2\\(m_size\\);\\s*",
            Matcher.quoteReplacement(main));
        if (!s.contains("frame.projection = gbufferProjection")) throw new IllegalStateException("unexpected shader layout: the export needs an update");
        s = s.replaceAll("imageStore\\(bedrayOutput, m_texel, ([^;]+)\\);", "mosaicFragColor = $1;");
        if (s.contains("bedrayOutput") || s.contains("gl_GlobalInvocationID"))
            throw new IllegalStateException("the graph writes the output in a way the export does not know yet");
        return IRIS_HEADER + s;
    }
}
