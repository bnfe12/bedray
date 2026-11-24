package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The Slang path: every graph the GLSL path compiles also compiles with slangc, with the same bindings. */
class MosaicSlangTest {

    static Path slangc;

    @BeforeAll
    static void needsSlangc() {
        slangc = MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        Assumptions.assumeTrue(slangc != null, "slangc not installed");
    }

    @Test
    void translatesNodeCode() {
        assertEquals("float3 c = lerp(a, frac(b), 0.5); float t = atan2(y, x); float u = atan(z);",
            MosaicSlang.fromGlsl("vec3 c = mix(a, fract(b), 0.5); float t = atan(y, x); float u = atan(z);"));
    }

    static String bindings(byte[] spv) {
        ByteBuffer b = ByteBuffer.allocateDirect(spv.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(spv).flip();
        Set<String> out = new TreeSet<>();
        for (SpirvReflection.Binding x : SpirvReflection.reflect(b).bindings()) out.add(x.set() + ":" + x.binding() + ":" + x.kind());
        return out.toString();
    }

    void sameAsGlsl(String what, MosaicGraph g, List<String> failures) {
        MosaicShaderCheck.Result s = MosaicSlang.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, true), slangc);
        if (!s.ok()) {
            failures.add(what + ": " + s.errors());
            return;
        }
        MosaicShaderCheck.Result glsl = MosaicShaderCheck.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false));
        if (glsl.ok() && !bindings(s.spirv()).equals(bindings(glsl.spirv())))
            failures.add(what + ": bindings differ, slang " + bindings(s.spirv()) + " glsl " + bindings(glsl.spirv()));
    }

    @Test
    void everyTemplateAndEffectCompilesWithSlangc() {
        List<String> failures = new ArrayList<>();
        for (MosaicTemplates.Template t : MosaicTemplates.all()) sameAsGlsl(t.name(), MosaicGraph.parse(t.json()), failures);
        for (MosaicFunction f : MosaicEffects.all()) sameAsGlsl(f.name(), MosaicGraph.parse(MosaicCompileTest.effectGraph(f)), failures);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void blenderNodesCompileWithSlangc() {
        List<String> failures = new ArrayList<>();
        String head = "{\"id\": \"c\", \"type\": \"mosaic.sceneColor\"}, {\"id\": \"t\", \"type\": \"mosaic.time\"}, ";
        List<String> bodies = new ArrayList<>();
        for (String op : MosaicGraph.MATH_OPS)
            bodies.add("{\"id\": \"x\", \"type\": \"mosaic.math\", \"mask\": \"" + op + "\", \"index\": 1, \"inputs\": {\"a\": \"c\", \"b\": \"t\"}}");
        for (String mode : MosaicGraph.BLEND_MODES)
            bodies.add("{\"id\": \"x\", \"type\": \"mosaic.mixColor\", \"mask\": \"" + mode + "\", \"inputs\": {\"a\": \"c\"}}");
        for (String mode : MosaicGraph.GRADIENT_MODES) bodies.add("{\"id\": \"x\", \"type\": \"mosaic.gradient\", \"mask\": \"" + mode + "\"}");
        bodies.add("{\"id\": \"n\", \"type\": \"mosaic.noise\"}, {\"id\": \"x\", \"type\": \"mosaic.colorRamp\", \"mask\": \"ease\", \"value\": [0, 0, 0, 0, 1, 1, 1, 1, 1, 1], \"inputs\": {\"fac\": \"n\"}}");
        bodies.add("{\"id\": \"v\", \"type\": \"mosaic.voronoi\"}, {\"id\": \"x\", \"type\": \"mosaic.mixColor\", \"inputs\": {\"a\": \"c\", \"b\": \"v.color\", \"fac\": \"v\"}}");
        bodies.add("{\"id\": \"x\", \"type\": \"mosaic.checker\"}");
        bodies.add("{\"id\": \"x\", \"type\": \"mosaic.wave\", \"mask\": \"rings\", \"defaults\": {\"distortion\": [2]}}");
        bodies.add("{\"id\": \"x\", \"type\": \"mosaic.mapRange\", \"mask\": \"smooth\", \"index\": 1, \"inputs\": {\"value\": \"c\"}}");
        bodies.add("{\"id\": \"w\", \"type\": \"mosaic.whiteNoise\"}, {\"id\": \"x\", \"type\": \"mosaic.reroute\", \"inputs\": {\"a\": \"w.color\"}}");
        for (String b : bodies) {
            String json = "{\"nodes\": [" + head + b + ", {\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"x\"}}]}";
            sameAsGlsl(b, MosaicGraph.parse(json), failures);
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void prebuiltSidecarTrustsOnlyTheSameGraph() {
        MosaicGraph a = MosaicGraph.parse(MosaicTemplates.all().get(0).json());
        String hash = MosaicSlang.expectedHash(a, MosaicSlang.PREBUILT_OPTIONS);
        String side = MosaicSlang.sidecar(hash, new MosaicSlang.Result("test", 4));
        assertEquals(hash, MosaicSlang.sidecarHash(side));
        MosaicGraph b = MosaicGraph.parse(MosaicTemplates.all().get(1).json());
        assertNotEquals(hash, MosaicSlang.expectedHash(b, MosaicSlang.PREBUILT_OPTIONS));
        // the debug view changes the source, so the game never runs a stale prebuilt while previewing a node
        assertNotEquals(hash, MosaicSlang.expectedHash(a, new MosaicCodegen.Options(null, true, true)));
        assertNull(MosaicSlang.sidecarHash("{}"));
        assertNull(MosaicSlang.sidecarHash("not json"));
    }

    @Test
    void slangErrorsLandOnTheNodeCodeLine() {
        MosaicFunction bad = MosaicFunction.parseSignature("vec4 broken(vec4 color, float k = 1)", "vec4 c = color;\nc.rgb *= kk;\nreturn c;");
        MosaicCodegen.Generated g = MosaicCodegen.generate(MosaicGraph.parse(MosaicCompileTest.effectGraph(bad)), MosaicCodegen.Options.DEFAULT, true);
        MosaicShaderCheck.Result r = MosaicSlang.compile(g, slangc);
        assertFalse(r.ok());
        MosaicShaderCheck.Error e = r.errors().get(0);
        assertEquals("broken", e.function(), r.raw());
        assertEquals(2, e.bodyLine(), r.raw());
    }
}
