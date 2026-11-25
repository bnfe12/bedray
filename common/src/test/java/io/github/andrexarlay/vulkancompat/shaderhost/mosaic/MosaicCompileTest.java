package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Generated shaders compile through the real shaderc path, and compiler errors land on the node that caused them. */
class MosaicCompileTest {

    @BeforeAll
    static void needsShaderc() {
        MosaicCodegen.Generated g = gen("""
            {"nodes": [{"id": "c", "type": "mosaic.sceneColor"}, {"id": "o", "type": "mosaic.output", "inputs": {"color": "c"}}]}""",
            MosaicCodegen.Options.DEFAULT);
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(g);
        Assumptions.assumeFalse(!r.ok() && r.errors().get(0).message().startsWith("shader compiler unavailable"),
            "shaderc natives not on the test classpath");
    }

    static MosaicCodegen.Generated gen(String json, MosaicCodegen.Options o) {
        return MosaicCodegen.generate(MosaicGraph.parse(json), o, false);
    }

    static void assertCompiles(String what, MosaicCodegen.Generated g) {
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(g);
        assertTrue(r.ok(), what + " failed: " + r.errors() + "\n" + g.source());
        assertTrue(r.spirvBytes() > 0);
    }

    /** sceneColor -> effect -> output, with a shared uv node. */
    static String effectGraph(MosaicFunction f) {
        StringBuilder inputs = new StringBuilder();
        for (MosaicFunction.Param p : f.params()) {
            if (!p.required()) continue;
            if (inputs.length() > 0) inputs.append(", ");
            String src = switch (p.kind()) {
                case V4 -> "color";
                case V2 -> "uv";
                case V3 -> "rgb";
                case F, S -> "half";
            };
            inputs.append('"').append(p.name()).append("\": \"").append(src).append('"');
        }
        com.google.gson.JsonArray fns = new com.google.gson.JsonArray();
        fns.add(f.toJson());
        return "{\"functions\": " + fns + ", \"nodes\": ["
            + "{\"id\": \"color\", \"type\": \"mosaic.sceneColor\"},"
            + "{\"id\": \"uv\", \"type\": \"mosaic.uv\"},"
            + "{\"id\": \"rgb\", \"type\": \"mosaic.swizzle\", \"mask\": \"rgb\", \"inputs\": {\"a\": \"color\"}},"
            + "{\"id\": \"half\", \"type\": \"mosaic.const\", \"value\": [0.5]},"
            + "{\"id\": \"fx\", \"type\": \"" + f.type() + "\", \"inputs\": {" + inputs + "}},"
            + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"fx\"}}]}";
    }

    @Test
    void everyBuiltInEffectCompiles() {
        List<String> failed = new ArrayList<>();
        for (MosaicFunction f : MosaicEffects.all()) {
            MosaicShaderCheck.Result r = MosaicShaderCheck.compile(gen(effectGraph(f), MosaicCodegen.Options.DEFAULT));
            if (!r.ok()) failed.add(f.name() + ": " + r.errors());
        }
        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }

    @Test
    void everyNodeTypeCompilesWithDebugOptions() {
        // one graph that uses every built-in node type, then preview each node with NaN highlighting
        StringBuilder nodes = new StringBuilder("""
            {"id": "uv", "type": "mosaic.uv"}, {"id": "tx", "type": "mosaic.texel"}, {"id": "sz", "type": "mosaic.size"},
            {"id": "tm", "type": "mosaic.time"}, {"id": "fr", "type": "mosaic.frame"}, {"id": "ey", "type": "mosaic.eye"},
            {"id": "sc", "type": "mosaic.sceneColor", "inputs": {"uv": "uv"}}, {"id": "sd", "type": "mosaic.sceneDepth"},
            {"id": "lu", "type": "mosaic.lut"}, {"id": "bu", "type": "mosaic.buffer", "index": 2}, {"id": "pu", "type": "mosaic.push", "index": 1},
            {"id": "ray", "type": "mosaic.trace"}, {"id": "sh", "type": "mosaic.shadow"},
            {"id": "po", "type": "mosaic.position"}, {"id": "wp", "type": "mosaic.worldPos"}, {"id": "wd", "type": "mosaic.waterDepth"},
            {"id": "wm", "type": "mosaic.waterMask"}, {"id": "lm", "type": "mosaic.lavaMask"}, {"id": "uw", "type": "mosaic.underwater"},
            {"id": "hm", "type": "mosaic.hitMaterial"}, {"id": "mm", "type": "mosaic.materialMask", "index": 5},
            {"id": "md", "type": "mosaic.materialDepth", "index": 11}, {"id": "sl", "type": "mosaic.skyLight"},
            {"id": "bl", "type": "mosaic.blockLight"}, {"id": "cm", "type": "mosaic.cameraMaterial"},
            {"id": "k", "type": "mosaic.const", "value": [0.25]},
            {"id": "v3", "type": "mosaic.vec", "size": 3, "inputs": {"x": "tm", "y": "sd", "z": "k"}},
            {"id": "sw", "type": "mosaic.swizzle", "mask": "xz", "inputs": {"a": "ray.pos"}},
            {"id": "ad", "type": "mosaic.add", "inputs": {"a": "sc", "b": "k"}}, {"id": "su", "type": "mosaic.sub", "inputs": {"a": "bu", "b": "pu"}},
            {"id": "mu", "type": "mosaic.mul", "inputs": {"a": "ad", "b": "su"}}, {"id": "dv", "type": "mosaic.div", "inputs": {"a": "mu", "b": "sh"}},
            {"id": "gt", "type": "mosaic.gt", "inputs": {"a": "ray", "b": "k"}}, {"id": "lt", "type": "mosaic.lt", "inputs": {"a": "wd", "b": "md"}},
            {"id": "mn", "type": "mosaic.min", "inputs": {"a": "po", "b": "wp"}}, {"id": "mx", "type": "mosaic.max", "inputs": {"a": "mn", "b": "v3"}},
            {"id": "mi", "type": "mosaic.mix", "inputs": {"a": "dv", "b": "lu", "t": "wm"}}, {"id": "cl", "type": "mosaic.clamp", "inputs": {"a": "mi"}},
            {"id": "st", "type": "mosaic.step", "inputs": {"a": "k", "b": "cl"}}, {"id": "dt", "type": "mosaic.dot", "inputs": {"a": "mx", "b": "ey"}},
            {"id": "cr", "type": "mosaic.cross", "inputs": {"a": "mx", "b": "ey"}}, {"id": "rf", "type": "mosaic.reflect", "inputs": {"a": "cr", "b": "v3"}},
            {"id": "ng", "type": "mosaic.neg", "inputs": {"a": "rf"}}, {"id": "fl", "type": "mosaic.floor", "inputs": {"a": "tx"}},
            {"id": "fc", "type": "mosaic.fract", "inputs": {"a": "fl"}}, {"id": "sq", "type": "mosaic.sqrt", "inputs": {"a": "sz"}},
            {"id": "ex", "type": "mosaic.exp", "inputs": {"a": "fr"}}, {"id": "ab", "type": "mosaic.abs", "inputs": {"a": "ng"}},
            {"id": "sa", "type": "mosaic.saturate", "inputs": {"a": "ab"}}, {"id": "nm", "type": "mosaic.normalize", "inputs": {"a": "sa"}},
            {"id": "ln", "type": "mosaic.length", "inputs": {"a": "sw"}}, {"id": "lm2", "type": "mosaic.lum", "inputs": {"a": "nm"}},
            {"id": "s1", "type": "mosaic.add", "inputs": {"a": "lm2", "b": "ln"}}, {"id": "s2", "type": "mosaic.add", "inputs": {"a": "s1", "b": "dt"}},
            {"id": "s3", "type": "mosaic.add", "inputs": {"a": "s2", "b": "ex"}}, {"id": "s4", "type": "mosaic.add", "inputs": {"a": "s3", "b": "gt"}},
            {"id": "s5", "type": "mosaic.add", "inputs": {"a": "s4", "b": "lt"}}, {"id": "s6", "type": "mosaic.add", "inputs": {"a": "s5", "b": "lm"}},
            {"id": "s7", "type": "mosaic.add", "inputs": {"a": "s6", "b": "uw"}}, {"id": "s8", "type": "mosaic.add", "inputs": {"a": "s7", "b": "hm"}},
            {"id": "s9", "type": "mosaic.add", "inputs": {"a": "s8", "b": "mm"}}, {"id": "s10", "type": "mosaic.add", "inputs": {"a": "s9", "b": "sl"}},
            {"id": "s11", "type": "mosaic.add", "inputs": {"a": "s10", "b": "bl"}}, {"id": "s12", "type": "mosaic.add", "inputs": {"a": "s11", "b": "cm"}},
            {"id": "s13", "type": "mosaic.vec", "size": 2, "inputs": {"x": "s12"}}, {"id": "s14", "type": "mosaic.mul", "inputs": {"a": "s13", "b": "sq"}},
            {"id": "s15", "type": "mosaic.length", "inputs": {"a": "s14"}}, {"id": "s16", "type": "mosaic.mul", "inputs": {"a": "st", "b": "s15"}},
            {"id": "s17", "type": "mosaic.add", "inputs": {"a": "s16", "b": "fc.x"}},
            {"id": "o", "type": "mosaic.output", "inputs": {"color": "s16"}}""");
        String json = "{\"nodes\": [" + nodes + "]}";
        // fc.x is not a valid named output: make sure that is refused, then drop it
        assertFalse(MosaicGraph.analyze(json).ok());
        json = json.replace(", {\"id\": \"s17\", \"type\": \"mosaic.add\", \"inputs\": {\"a\": \"s16\", \"b\": \"fc.x\"}}", "")
            .replace("{\"id\": \"s17\", \"type\": \"mosaic.add\", \"inputs\": {\"a\": \"s16\", \"b\": \"fc.x\"}},\n", "");
        MosaicGraph.Analysis a = MosaicGraph.analyze(json);
        assertTrue(a.ok(), String.valueOf(a.errors()));
        MosaicGraph g = a.graph();
        assertCompiles("all nodes", MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false));
        assertCompiles("all nodes, unoptimized", MosaicCodegen.generate(g, new MosaicCodegen.Options(null, true, false), false));
        for (MosaicGraph.Node n : g.nodes())
            assertCompiles("preview " + n.id(), MosaicCodegen.generate(g, new MosaicCodegen.Options(n.id(), true, true), false));
    }

    @Test
    void blenderStyleNodesCompileInEveryMode() {
        java.util.List<String> failures = new java.util.ArrayList<>();
        java.util.function.Consumer<String> check = body -> {
            String json = "{\"nodes\": [" + body + ", {\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"x\"}}]}";
            MosaicGraph.Analysis a = MosaicGraph.analyze(json);
            if (!a.ok()) {
                failures.add(body + " -> " + a.errors());
                return;
            }
            for (boolean optimize : new boolean[] {true, false}) {
                MosaicShaderCheck.Result r = MosaicShaderCheck.compile(MosaicCodegen.generate(a.graph(), new MosaicCodegen.Options(null, true, optimize), false));
                if (!r.ok()) failures.add(body + " -> " + r.errors());
            }
        };
        String color = "{\"id\": \"c\", \"type\": \"mosaic.sceneColor\"}, {\"id\": \"t\", \"type\": \"mosaic.time\"}, ";
        for (String op : MosaicGraph.MATH_OPS) {
            check.accept(color + "{\"id\": \"x\", \"type\": \"mosaic.math\", \"mask\": \"" + op + "\", \"index\": 1, \"inputs\": {\"a\": \"c\", \"b\": \"t\"}}");
            check.accept(color + "{\"id\": \"x\", \"type\": \"mosaic.math\", \"mask\": \"" + op + "\", \"inputs\": {\"a\": \"t\"}, \"defaults\": {\"b\": [2]}}");
        }
        for (String mode : MosaicGraph.BLEND_MODES)
            check.accept(color + "{\"id\": \"x\", \"type\": \"mosaic.mixColor\", \"mask\": \"" + mode + "\", \"inputs\": {\"a\": \"c\"}, \"defaults\": {\"b\": [1, 0, 0, 1], \"fac\": [0.3]}}");
        for (String mode : MosaicGraph.RAMP_MODES)
            check.accept(color + "{\"id\": \"n\", \"type\": \"mosaic.noise\", \"defaults\": {\"detail\": [4]}}, "
                + "{\"id\": \"x\", \"type\": \"mosaic.colorRamp\", \"mask\": \"" + mode + "\", \"value\": [0, 0, 0, 0.2, 1, 0.5, 1, 0.3, 0, 1, 1, 1, 1, 1, 1], \"inputs\": {\"fac\": \"n\"}}");
        for (String mode : MosaicGraph.GRADIENT_MODES)
            check.accept(color + "{\"id\": \"x\", \"type\": \"mosaic.gradient\", \"mask\": \"" + mode + "\"}");
        check.accept(color + "{\"id\": \"x\", \"type\": \"mosaic.mapRange\", \"mask\": \"smooth\", \"index\": 1, \"inputs\": {\"value\": \"c\"}, \"defaults\": {\"toMin\": [0.2]}}");
        check.accept(color + "{\"id\": \"v\", \"type\": \"mosaic.voronoi\"}, {\"id\": \"x\", \"type\": \"mosaic.mixColor\", \"inputs\": {\"a\": \"c\", \"b\": \"v.color\", \"fac\": \"v\"}}");
        check.accept(color + "{\"id\": \"v\", \"type\": \"mosaic.voronoi\"}, {\"id\": \"x\", \"type\": \"mosaic.reroute\", \"inputs\": {\"a\": \"v.position\"}}");
        check.accept(color + "{\"id\": \"n\", \"type\": \"mosaic.noise\", \"defaults\": {\"distortion\": [0.5]}}, {\"id\": \"x\", \"type\": \"mosaic.reroute\", \"inputs\": {\"a\": \"n.color\"}}");
        check.accept(color + "{\"id\": \"w\", \"type\": \"mosaic.whiteNoise\"}, {\"id\": \"x\", \"type\": \"mosaic.reroute\", \"inputs\": {\"a\": \"w.color\"}}");
        check.accept(color + "{\"id\": \"x\", \"type\": \"mosaic.checker\", \"defaults\": {\"scale\": [8]}}");
        check.accept(color + "{\"id\": \"ck\", \"type\": \"mosaic.checker\"}, {\"id\": \"x\", \"type\": \"mosaic.reroute\", \"inputs\": {\"a\": \"ck.fac\"}}");
        check.accept(color + "{\"id\": \"x\", \"type\": \"mosaic.wave\", \"mask\": \"rings\", \"defaults\": {\"distortion\": [2]}}");
        check.accept(color + "{\"id\": \"x\", \"type\": \"mosaic.add\", \"inputs\": {\"a\": \"c\"}, \"defaults\": {\"b\": [0.1]}}");
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void templatesAndExampleCompile() throws Exception {
        for (MosaicTemplates.Template t : MosaicTemplates.all()) {
            MosaicGraph.Analysis a = MosaicGraph.analyze(t.json());
            assertTrue(a.ok(), t.name() + ": " + a.errors());
            assertCompiles(t.name(), MosaicCodegen.generate(a.graph(), MosaicCodegen.Options.DEFAULT, false));
        }
    }

    @Test
    void aTypoInNodeCodeIsReportedAtTheNodeAndLine() {
        MosaicFunction bad = MosaicFunction.parseSignature("vec4 broken(vec4 color, float k = 1)",
            "vec4 c = color;\nc.rgb *= kk;\nreturn c;");
        MosaicCodegen.Generated g = gen(effectGraph(bad), MosaicCodegen.Options.DEFAULT);
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(g);
        assertFalse(r.ok());
        MosaicShaderCheck.Error e = r.errors().get(0);
        assertEquals("broken", e.function(), r.errors() + "\n" + r.raw());
        assertEquals(2, e.bodyLine(), r.errors() + "\n" + r.raw());
        assertTrue(e.message().contains("kk"), e.message());
    }
}
