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
 * measure-before-shader A/B for iris_depth: the Slang Tier 1 port must be byte-identical to the frozen GLSL
 * reference under the production ABI (2 image bindings, 128-byte PC, 4 depth encodings). Gated like rtProbe.
 */
class IrisDepthAbTest {
    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void slangMatchesFrozenGlslBaseline() throws Exception {
        String frozen = frozenGlslHash();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            byte[] glsl = PostBaselineRunner.dispatchIrisDepth(probe, PostBaselineRunner.compileGlsl("/shaders/iris_depth.comp", "iris_depth.comp"));
            byte[] slang = PostBaselineRunner.dispatchIrisDepth(probe, loadSpv("/assets/bedray_rt/shaders/iris_depth_v14.spv"));
            String glslHash = PostBaselineRunner.sha256(glsl);
            String slangHash = PostBaselineRunner.sha256(slang);
            System.out.printf("[BEDRAY-POST-AB] iris_depth frozen=%s glsl=%s slang=%s%n", frozen, glslHash, slangHash);
            assertEquals(frozen, glslHash, "GLSL reference drifted from the frozen baseline (iris_depth.comp changed?)");
            assertEquals(glslHash, slangHash, "Slang port must be byte-identical to the GLSL reference");
            assertArrayEquals(glsl, slang, "Slang output bytes must equal GLSL output bytes");
        }
    }

    private static String frozenGlslHash() throws Exception {
        Path doc = Path.of("docs/POST_BASELINE.md");
        assertTrue(Files.exists(doc), "Missing frozen baseline docs/POST_BASELINE.md (run :common:postBaseline first)");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("iris_depth glsl_sha256=([0-9a-f]{64})").matcher(text);
        assertTrue(m.find(), "No iris_depth glsl_sha256= line in docs/POST_BASELINE.md");
        return m.group(1);
    }

    private static ByteBuffer loadSpv(String path) throws Exception {
        try (java.io.InputStream in = IrisDepthAbTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = in.readAllBytes();
            ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            b.put(bytes).flip();
            return b;
        }
    }
}