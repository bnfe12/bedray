package io.github.andrexarlay.vulkancompat.rt.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * measure-before-shader A/B for rt_composite: byte-identical to the frozen GLSL reference under the production
 * ABI (4 CIS + 1 storage, 80-byte PC with a column-major mat4). Exercises sky / entity / terrain / hand /
 * translucent branches. Gated like rtProbe.
 */
class CompositeAbTest {
    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void slangMatchesFrozenGlslBaseline() throws Exception {
        String frozen = frozenGlslHash();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            byte[] glsl = PostBaselineRunner.dispatchComposite(probe, PostBaselineRunner.compileGlsl("/shaders/rt_composite.comp", "rt_composite.comp"));
            byte[] slang = PostBaselineRunner.dispatchComposite(probe, loadSpv("/assets/bedray_rt/shaders/rt_composite_v14.spv"));
            String glslHash = PostBaselineRunner.sha256(glsl);
            String slangHash = PostBaselineRunner.sha256(slang);
            System.out.printf("[BEDRAY-POST-AB] rt_composite frozen=%s glsl=%s slang=%s%n", frozen, glslHash, slangHash);
            assertEquals(frozen, glslHash, "GLSL reference drifted from the frozen baseline (rt_composite.comp changed?)");
            assertEquals(glslHash, slangHash, "Slang port must be byte-identical to the GLSL reference");
            assertArrayEquals(glsl, slang, "Slang output bytes must equal GLSL output bytes");
        }
    }

    private static String frozenGlslHash() throws Exception {
        Path doc = Path.of("docs/POST_BASELINE.md");
        assertTrue(Files.exists(doc), "Missing frozen baseline docs/POST_BASELINE.md (run :common:postBaseline first)");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("rt_composite glsl_sha256=([0-9a-f]{64})").matcher(text);
        assertTrue(m.find(), "No rt_composite glsl_sha256= line in docs/POST_BASELINE.md");
        return m.group(1);
    }

    private static ByteBuffer loadSpv(String path) throws Exception {
        try (java.io.InputStream in = CompositeAbTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = in.readAllBytes();
            ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            b.put(bytes).flip();
            return b;
        }
    }
}