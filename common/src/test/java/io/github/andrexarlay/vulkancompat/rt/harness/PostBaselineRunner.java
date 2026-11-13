package io.github.andrexarlay.vulkancompat.rt.harness;

import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Captures the frozen GLSL-reference baseline of the plain compute post shaders (L2 offscreen). Stage 2.1a:
 * fg_prepare. The GLSL is compiled with shaderc exactly as the production loader (FrameGenGpu) does, dispatched
 * under the production ABI over synthetic inputs, and the output bytes hashed into docs/POST_BASELINE.md.
 * This baseline is captured BEFORE the Slang port and never regenerated to hide a delta.
 */
public final class PostBaselineRunner {
    static final int W = 70, H = 50;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        StringBuilder md = new StringBuilder();
        md.append("# Plain compute post-shader GLSL reference baseline (L2 offscreen)\n\n");
        md.append("Frozen GLSL-reference hashes for the Stage 2.1 Slang migration. Captured BEFORE each port;\n");
        md.append("never regenerated to hide a delta. Synthetic inputs, production descriptor ABI, shaderc GLSL.\n\n");
        md.append("| shader | size | GLSL SHA-256 (output) | device |\n|---|---|---|---|\n");
        StringBuilder machine = new StringBuilder();
        try (PostShaderProbe probe = new PostShaderProbe()) {
            String fgHash = measureFgPrepare(probe, machine);
            md.append("| fg_prepare | ").append(W).append('x').append(H).append(" | `").append(fgHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
            String irisHash = measureIrisDepth(probe, machine);
            md.append("| iris_depth | ").append(W).append('x').append(H).append(" | `").append(irisHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
            String partHash = measureParticles(probe, machine);
            md.append("| rt_particles_composite | ").append(W).append('x').append(H).append(" | `").append(partHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
            String compHash = measureComposite(probe, machine);
            md.append("| rt_composite | ").append(W).append('x').append(H).append(" | `").append(compHash)
              .append("` | ").append(probe.deviceName).append(" |\n");
        }
        md.append("\n## Machine-readable frozen hashes\n\n```\n").append(machine).append("```\n");
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/POST_BASELINE.md"), md.toString(), StandardCharsets.UTF_8);
        System.out.print("[BEDRAY-POST-BASELINE]\n" + machine);
    }

    private static String measureFgPrepare(PostShaderProbe probe, StringBuilder machine) throws Exception {
        RtMemory mem = probe.memory;
        int imgUsageIn = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        RtMemory.Img depthIn = mem.createImage("fg_depth_in", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_SAMPLED_BIT | imgUsageIn);
        RtMemory.Img mvIn = mem.createImage("fg_mv_in", W, H, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | imgUsageIn);
        RtMemory.Img depthOut = mem.createImage("fg_depth_out", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        RtMemory.Img mvOut = mem.createImage("fg_mv_out", W, H, VK_FORMAT_R16G16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            probe.upload(depthIn, depthBytes(), 4);
            probe.upload(mvIn, mvBytes(), 4);

            ByteBuffer spirv = compileGlsl("/shaders/fg_prepare.comp", "fg_prepare.comp");
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
            // Sanity: the flip is correct, not merely deterministic. outDepth(0,0) == depthIn(0,H-1).
            float expectDepth = (float) H + (float) 1 * 0.0001f;
            float gotDepth = ByteBuffer.wrap(depthBack).order(ByteOrder.LITTLE_ENDIAN).getFloat(0);
            if (Math.abs(gotDepth - expectDepth) > 1e-4f) {
                throw new IllegalStateException("fg_prepare flip sanity failed: depth(0,0)=" + gotDepth + " expected " + expectDepth);
            }
            byte[] all = new byte[depthBack.length + mvBack.length];
            System.arraycopy(depthBack, 0, all, 0, depthBack.length);
            System.arraycopy(mvBack, 0, all, depthBack.length, mvBack.length);
            String hash = sha256(all);
            machine.append("fg_prepare glsl_sha256=").append(hash).append(" size=").append(W).append('x').append(H).append('\n');
            return hash;
        } finally {
            mem.destroy(depthIn);
            mem.destroy(mvIn);
            mem.destroy(depthOut);
            mem.destroy(mvOut);
        }
    }

    private static String measureIrisDepth(PostShaderProbe probe, StringBuilder machine) throws Exception {
        byte[] out = dispatchIrisDepth(probe, compileGlsl("/shaders/iris_depth.comp", "iris_depth.comp"));
        String hash = sha256(out);
        machine.append("iris_depth glsl_sha256=").append(hash).append(" size=").append(W).append('x').append(H).append('\n');
        return hash;
    }

    private static String measureParticles(PostShaderProbe probe, StringBuilder machine) throws Exception {
        byte[] out = dispatchParticles(probe, compileGlsl("/shaders/rt_particles_composite.comp", "rt_particles_composite.comp"));
        String hash = sha256(out);
        machine.append("rt_particles_composite glsl_sha256=").append(hash).append(" size=").append(W).append('x').append(H).append('\n');
        return hash;
    }

    private static String measureComposite(PostShaderProbe probe, StringBuilder machine) throws Exception {
        byte[] out = dispatchComposite(probe, compileGlsl("/shaders/rt_composite.comp", "rt_composite.comp"));
        String hash = sha256(out);
        machine.append("rt_composite glsl_sha256=").append(hash).append(" size=").append(W).append('x').append(H).append('\n');
        return hash;
    }

    static byte[] depthBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                b.putFloat((float) (y + 1) + (float) (x + 1) * 0.0001f);
            }
        }
        return b.array();
    }

    static byte[] mvBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                b.putShort(floatToHalf((float) (x - W / 2)));
                b.putShort(floatToHalf((float) (y - H / 2)));
            }
        }
        return b.array();
    }

    /** IEEE-754 half; inputs here are small integers (exact in half), so rounding is not exercised. */
    static short floatToHalf(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int exp = (bits >>> 23) & 0xff;
        int mant = bits & 0x7fffff;
        if (exp == 0xff) return (short) (sign | 0x7c00 | (mant != 0 ? 0x200 : 0));
        int e = exp - 127 + 15;
        if (e >= 0x1f) return (short) (sign | 0x7c00);
        if (e <= 0) {
            if (e < -10) return (short) sign;
            mant |= 0x800000;
            int shift = 14 - e;
            return (short) (sign | (mant >> shift));
        }
        return (short) (sign | (e << 10) | (mant >> 13));
    }

    // ---- Stage 2.1b: iris_depth ----

    /** Column-major matrix bytes via get(float[]) (pure Java); JOML's get(int,ByteBuffer) Unsafe path crashes on heap buffers under JDK 25. */
    private static void putMatrix(ByteBuffer b, int offset, Matrix4f m) {
        float[] f = m.get(new float[16]);
        for (int i = 0; i < 16; i++) {
            b.putFloat(offset + i * 4, f[i]);
        }
    }

    static Matrix4f irisInverseProjection() {
        return new Matrix4f().perspective((float) Math.toRadians(90.0), 1.0f, 0.05f, 1000.0f, true).invert();
    }

    static Matrix4f irisInverseView() {
        return new Matrix4f().rotateY(0.3f).rotateX(0.2f);
    }

    static byte[] irisDepthBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float d;
                int k = (x + y * W) % 10;
                if (k == 0) d = Float.NaN;
                else if (k == 1) d = Float.POSITIVE_INFINITY;
                else if (k == 2) d = 0.0f;
                else if (k == 3) d = 1.0f;
                else if (k == 4) d = -0.5f;
                else d = (float) (x + 1) / (float) (W + 1);
                b.putFloat(d);
            }
        }
        return b.array();
    }

    /** Mirrors IrisDepthConverter.record(): mat4 at 0, inverseView columns at 64/80/96, encoding at 76, parameters at 112. */
    static byte[] irisPc(int encoding, Matrix4f invProj, Matrix4f invView, float near, float far, float scale, float bias) {
        ByteBuffer b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        putMatrix(b, 0, invProj);
        b.putFloat(64, invView.m00()).putFloat(68, invView.m01()).putFloat(72, invView.m02());
        b.putFloat(76, (float) encoding);
        b.putFloat(80, invView.m10()).putFloat(84, invView.m11()).putFloat(88, invView.m12());
        b.putFloat(96, invView.m20()).putFloat(100, invView.m21()).putFloat(104, invView.m22());
        b.putFloat(112, near).putFloat(116, far).putFloat(120, scale).putFloat(124, bias);
        return b.array();
    }

    static byte[] dispatchIrisDepth(PostShaderProbe probe, ByteBuffer spirv) {
        RtMemory mem = probe.memory;
        RtMemory.Img depthIn = mem.createImage("iris_in", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img out = mem.createImage("iris_out", W, H, VK_FORMAT_R32_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            probe.upload(depthIn, irisDepthBytes(), 4);
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            Matrix4f invProj = irisInverseProjection(), invView = irisInverseView();
            byte[] all = new byte[4 * W * H * 4];
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 128)) {
                pipe.image(0, depthIn.view, true);
                pipe.image(1, out.view, false);
                for (int enc = 0; enc < 4; enc++) {
                    byte[] pc = irisPc(enc, invProj, invView, 0.05f, 1000.0f, 1.0f, 0.0f);
                    final int e = enc;
                    probe.submit(cmd -> {
                        if (e == 0) probe.toGeneral(cmd, out.image, VK_IMAGE_LAYOUT_UNDEFINED);
                        pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1);
                    });
                    byte[] back = probe.readback(out, 4);
                    System.arraycopy(back, 0, all, enc * W * H * 4, back.length);
                }
            }
            return all;
        } finally {
            mem.destroy(depthIn);
            mem.destroy(out);
        }
    }

    // ---- Stage 2.1b: rt_particles_composite ----

    static byte[] particleLayerBytes() {
        byte[] a = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                int k = (x + y * W) % 4;
                if (k == 0) { a[i] = 0; a[i + 1] = 0; a[i + 2] = 0; a[i + 3] = 0; }
                else {
                    a[i] = (byte) (x * 3); a[i + 1] = (byte) (y * 5); a[i + 2] = (byte) ((x + y) * 2);
                    a[i + 3] = (byte) (k == 1 ? 255 : k == 2 ? 128 : 32);
                }
            }
        }
        return a;
    }

    static byte[] particleRtInitialBytes() {
        byte[] a = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                a[i] = (byte) (255 - x * 2); a[i + 1] = (byte) (y * 4); a[i + 2] = (byte) ((x * y) % 256); a[i + 3] = (byte) 255;
            }
        }
        return a;
    }

    static byte[] particlePc(float strength) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(W).putInt(H).putFloat(strength).putFloat(0f).array();
    }

    static byte[] dispatchParticles(PostShaderProbe probe, ByteBuffer spirv) {
        RtMemory mem = probe.memory;
        RtMemory.Img layer = mem.createImage("p_layer", W, H, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img rt = mem.createImage("p_rt", W, H, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        try {
            probe.upload(layer, particleLayerBytes(), 4);
            probe.upload(rt, particleRtInitialBytes(), 4);
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            byte[] pc = particlePc(0.75f);
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 16)) {
                pipe.image(0, layer.view, true);
                pipe.image(1, rt.view, false);
                probe.submit(cmd -> pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1));
                return probe.readback(rt, 4);
            }
        } finally {
            mem.destroy(layer);
            mem.destroy(rt);
        }
    }

    // ---- Stage 2.1b: rt_composite ----

    static final Matrix4f COMPOSITE_PROJ =
        new Matrix4f().perspective((float) Math.toRadians(90.0), 1.0f, 1000.0f, 0.05f, true);

    static Matrix4f compositeInvViewProj() {
        return new Matrix4f(COMPOSITE_PROJ).invert();
    }

    static float reverseZ(float dist) {
        Vector4f c = COMPOSITE_PROJ.transform(new Vector4f(0f, 0f, -dist, 1f), new Vector4f());
        return c.z / c.w;
    }

    /** Region-mapped case index, shared by the raster-depth and ray-depth generators so they stay consistent. */
    static int compositeCase(int x, int y) {
        return (x / 10 + (y / 10) * 7) % 7;
    }

    static byte[] compositeRasterDepthBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float d;
                switch (compositeCase(x, y)) {
                    case 0: d = 0.0f; break;            // sky
                    case 1: d = reverseZ(5.0f); break;  // entity in front of wall (ray 10)
                    case 2: d = reverseZ(12.0f); break; // terrain match (ray 12)
                    case 3: d = reverseZ(15.0f); break; // occluded entity (ray 8)
                    case 4: d = reverseZ(20.0f); break; // entity against sky (ray 1e6)
                    case 5: d = reverseZ(0.6f); break;  // hand (ray 1)
                    default: d = reverseZ(11.9f); break;// near terrain (ray 12)
                }
                b.putFloat(d);
            }
        }
        return b.array();
    }

    static byte[] compositeRtDepthBytes() {
        ByteBuffer b = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float d;
                switch (compositeCase(x, y)) {
                    case 0: d = 1.0e6f; break;
                    case 1: d = 10.0f; break;
                    case 2: d = 12.0f; break;
                    case 3: d = 8.0f; break;
                    case 4: d = 1.0e6f; break;
                    case 5: d = 1.0f; break;
                    default: d = 12.0f; break;
                }
                b.putFloat(d);
            }
        }
        return b.array();
    }

    static byte[] compositeRasterColorBytes() {
        byte[] a = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                a[i] = (byte) (x * 3); a[i + 1] = (byte) (y * 5); a[i + 2] = (byte) ((x + y) * 2);
                a[i + 3] = (byte) (compositeCase(x, y) % 2 == 0 ? 255 : 128); // opaque / translucent
            }
        }
        return a;
    }

    static byte[] compositeRtColorBytes() {
        byte[] a = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                a[i] = (byte) (255 - x * 2); a[i + 1] = (byte) (y * 4); a[i + 2] = (byte) ((x * y) % 256); a[i + 3] = (byte) 255;
            }
        }
        return a;
    }

    static byte[] compositePc() {
        ByteBuffer b = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
        putMatrix(b, 0, compositeInvViewProj());
        b.putInt(64, W); b.putInt(68, H); b.putFloat(72, 0.08f); b.putFloat(76, 0f);
        return b.array();
    }

    static byte[] dispatchComposite(PostShaderProbe probe, ByteBuffer spirv) {
        RtMemory mem = probe.memory;
        RtMemory.Img rc = mem.createImage("c_rc", W, H, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img rd = mem.createImage("c_rd", W, H, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img rtc = mem.createImage("c_rtc", W, H, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img rtd = mem.createImage("c_rtd", W, H, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        RtMemory.Img out = mem.createImage("c_out", W, H, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        try {
            probe.upload(rc, compositeRasterColorBytes(), 4);
            probe.upload(rd, compositeRasterDepthBytes(), 4);
            probe.upload(rtc, compositeRtColorBytes(), 4);
            probe.upload(rtd, compositeRtDepthBytes(), 4);
            PostShaderProbe.Binding[] bindings = {
                new PostShaderProbe.Binding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                new PostShaderProbe.Binding(4, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
            };
            byte[] pc = compositePc();
            try (PostShaderProbe.Pipeline pipe = probe.new Pipeline(spirv, bindings, 80)) {
                pipe.image(0, rc.view, true); pipe.image(1, rd.view, true);
                pipe.image(2, rtc.view, true); pipe.image(3, rtd.view, true);
                pipe.image(4, out.view, false);
                probe.submit(cmd -> {
                    probe.toGeneral(cmd, out.image, VK_IMAGE_LAYOUT_UNDEFINED);
                    pipe.record(cmd, pc, (W + 7) / 8, (H + 7) / 8, 1);
                });
                return probe.readback(out, 4);
            }
        } finally {
            mem.destroy(rc); mem.destroy(rd); mem.destroy(rtc); mem.destroy(rtd); mem.destroy(out);
        }
    }

    static ByteBuffer compileGlsl(String resource, String name) throws IOException {
        String glsl;
        try (InputStream in = PostBaselineRunner.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("resource not found: " + resource);
            glsl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            long result = Shaderc.shaderc_compile_into_spv(compiler, glsl, Shaderc.shaderc_compute_shader, name, "main", options);
            try {
                if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                    throw new IllegalStateException(name + ": " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                ByteBuffer direct = BufferUtils.createByteBuffer(bytes.remaining());
                direct.put(bytes);
                direct.flip();
                return direct;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}