package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * measure-before-shader A/B for fg_prepare: the Slang Tier 1 port must be byte-identical to the frozen GLSL
 * reference under the production ABI (4 image bindings, 8-byte ivec2 PC, dispatch (w+7)/8). Gated like rtProbe.
 */
class FgPrepareAbTest {
    private static final int W = PostBaselineRunner.W, H = PostBaselineRunner.H;

    @Test
    @EnabledIfSystemProperty(named = "bedray.rt.postProbe", matches = "true")
    void slangMatchesFrozenGlslBaseline() throws Exception {
        String frozen = frozenGlslHash();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            byte[] glsl = dispatch(probe, PostBaselineRunner.compileGlsl("/shaders/fg_prepare.comp", "fg_prepare.comp"));
            byte[] slang = dispatch(probe, loadSpv("/assets/bedray_rt/shaders/fg_prepare_v14.spv"));
            String glslHash = PostBaselineRunner.sha256(glsl);
            String slangHash = PostBaselineRunner.sha256(slang);
            System.out.printf("[BEDRAY-POST-AB] fg_prepare frozen=%s glsl=%s slang=%s%n", frozen, glslHash, slangHash);
            assertEquals(frozen, glslHash, "GLSL reference drifted from the frozen baseline (fg_prepare.comp changed?)");
            assertEquals(glslHash, slangHash, "Slang port must be byte-identical to the GLSL reference");
            assertArrayEquals(glsl, slang, "Slang output bytes must equal GLSL output bytes");
        }
    }

    /** Runs fg_prepare under the production ABI over the frozen synthetic inputs; returns depth+mv bytes. */
    private byte[] dispatch(PostShaderProbe probe, ByteBuffer spirv) {
        RtMemory mem = probe.memory;
        RtMemory.Img depthIn = mem.createImage("ab_depth_in", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img mvIn = mem.createImage("ab_mv_in", W, H, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img depthOut = mem.createImage("ab_depth_out", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        RtMemory.Img mvOut = mem.createImage("ab_mv_out", W, H, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            probe.upload(depthIn, PostBaselineRunner.depthBytes(), 4);
            probe.upload(mvIn, PostBaselineRunner.mvBytes(), 4);
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new PostShaderProbe.Binding(2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                new PostShaderProbe.Binding(3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            byte[] pc = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(W).putInt(H).array();
            byte[] depthBack, mvBack;
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 8)) {
                pipe.image(0, depthIn.view, true);
                pipe.image(1, mvIn.view, false);
                pipe.image(2, depthOut.view, false);
                pipe.image(3, mvOut.view, false);
                probe.submit(cmd -> {
                    probe.toGeneral(cmd, depthOut.image, VK_IMAGE_LAYOUT_UNDEFINED);
                    probe.toGeneral(cmd, mvOut.image, VK_IMAGE_LAYOUT_UNDEFINED);
                    pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1);
                });
                depthBack = probe.readback(depthOut, 4);
                mvBack = probe.readback(mvOut, 4);
            }
            byte[] all = new byte[depthBack.length + mvBack.length];
            System.arraycopy(depthBack, 0, all, 0, depthBack.length);
            System.arraycopy(mvBack, 0, all, depthBack.length, mvBack.length);
            return all;
        } finally {
            mem.destroy(depthIn);
            mem.destroy(mvIn);
            mem.destroy(depthOut);
            mem.destroy(mvOut);
        }
    }

    private static String frozenGlslHash() throws Exception {
        // The runner writes docs/POST_BASELINE.md at the repo root; postProbe sets workingDir = repo root.
        Path doc = Path.of("docs/POST_BASELINE.md");
        assertTrue(Files.exists(doc), "Missing frozen baseline docs/POST_BASELINE.md (run :common:postBaseline first)");
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("fg_prepare glsl_sha256=([0-9a-f]{64})").matcher(text);
        assertTrue(m.find(), "No fg_prepare glsl_sha256= line in docs/POST_BASELINE.md");
        return m.group(1);
    }

    private static ByteBuffer loadSpv(String path) throws Exception {
        try (java.io.InputStream in = FgPrepareAbTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing precompiled SPIR-V: " + path);
            byte[] bytes = in.readAllBytes();
            ByteBuffer b = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
            b.put(bytes).flip();
            return b;
        }
    }
}