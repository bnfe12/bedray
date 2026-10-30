package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NRD's {@code IN_MV} channel is a per-pixel 16-byte record at {@code StatsBuf.motionAddr}: the native pack reads it
 * with that stride, so an RGBA16F image (8 B/px) cannot carry it and the Slang path writes the buffer instead.
 * The value is the primary hit's world motion, prev minus cur, plus a trust flag that is 1 for any captured model.
 * <p>
 * Ways this silently breaks: the push bit set without the buffer (a descriptor the host never filled), the buffer bound
 * without the bit (stale motion denoised as if it were this frame), a range shorter than the frame (a write into a
 * neighbour buffer), motion enabled without the guides NRD also consumes, and a renderer that binds the buffer after the
 * pass already captured its views.
 */
class RtSlangNrdMotionTest {

    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String TRACER = SHADERS + "/pathtrace/path_tracer.slang";
    private static final String PASS = SHADERS + "/passes/path_trace.slang";
    private static final String SCENE_PASS = SHADERS + "/passes/path_trace_scene.slang";
    private static final String HOST_PASS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java";
    private static final String HOST_FRAME = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java";
    private static final String BACKEND = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/ReferencePathTraceBackend.java";
    private static final String RENDERER = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java";
    private static final String BINDINGS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameBindings.java";

    @Test
    void theMotionRecordIsOneVec4PerPixel() {
        assertTrue(read(TRACER).contains("PATH_TRACE_OPTION_NRD_MOTION     = 1u << 1u;"),
            "the option word is shared with the guides: bit 0 is the G-buffer, bit 1 is NRD's motion");
        for (String pass : new String[] {PASS, SCENE_PASS}) {
            String source = read(pass) + "\n" + read(SHADERS + "/pathtrace/path_trace_entry.slang");
            assertTrue(source.contains("[[vk::binding(33, 0)]] RWStructuredBuffer<float4> outMotionBuffer;"),
                pass + ": one vec4 per pixel, not an RGBA16F image the pack could not read with its 16-byte stride");
            String motion = block(source, "float4 pathTraceNrdMotion(");
            assertTrue(motion.contains("if (!sampleTrusted || !result.primary.valid) {"),
                pass + ": a miss or a rejected sample has no surface to reproject");
            assertTrue(motion.contains("return float4(0.0);"), pass + ": sky writes zero and the pack keeps its static miss");
            assertTrue(motion.contains("float3 motion = result.primaryMotion.xyz;"),
                pass + ": the same primary displacement the accumulator reprojects");
            assertTrue(motion.contains(
                "float trusted = (result.primaryMotion.w > 0.5 || dot(motion, motion) > 1.0e-8) ? 1.0 : 0.0;"),
                pass + ": the primary-hit trust flag, so a still surface stays out of the entity motion");
            assertTrue(motion.contains("return float4(motion, trusted);"), pass + ": xyz + flag, four floats");
        }
    }

    @Test
    void theWriteIsGatedByItsOwnOptionBit() {
        for (String pass : new String[] {PASS, SCENE_PASS}) {
            String source = read(pass) + "\n" + read(SHADERS + "/pathtrace/path_trace_entry.slang");
            String store = block(source, "if ((options.flags & PATH_TRACE_OPTION_NRD_MOTION) != 0u) {");
            assertTrue(store.contains(
                "outMotionBuffer[uint(p.x) + uint(p.y) * f.camera.size.x] = pathTraceNrdMotion(result, sampleTrusted);"),
                pass + ": the pixel index is the row-major one, one record per render pixel, and the"
                    + " sample's verdict gates the write");
        }
    }

    @Test
    void theRejectedSampleNeverPublishesGuidesOrMotion() {
        String rule = block(read(TRACER), "bool pathTraceSampleRejected(");
        assertTrue(rule.contains(
            "return (flags & (PATH_TRACE_FLAG_INVALID_SAMPLE | PATH_TRACE_FLAG_RC_UNRESOLVED)) != 0u;"),
            "one rule names the samples the accumulator refuses");
        assertFalse(rule.contains("PATH_TRACE_FLAG_TRUNCATED"),
            "a truncated path stopped short but its primary vertex is real, so it stays trusted");
        String entry = read(SHADERS + "/pathtrace/path_trace_entry.slang");
        assertTrue(entry.contains("bool sampleTrusted = !pathTraceSampleRejected(result.flags);"),
            "the verdict is computed once and every output reads the same bit");
        assertTrue(entry.contains("outImage[p] = float4(result.radiance, sampleTrusted ? 1.0 : 0.0);"),
            "the radiance lane and the guides cannot disagree about the same sample");
        assertTrue(entry.contains("outMotion[p] = sampleTrusted && (result.flags & PATH_TRACE_FLAG_PRIMARY_HIT) != 0u"),
            "the accumulator must not reproject through a refused path");
        assertTrue(entry.contains(
            "pathTraceWriteGuides(p, f, result, sampleTrusted, outViewZ, outNormalRough, outSpecular, outDiffuse,"),
            "the guides get the same verdict the alpha lane used");
        assertTrue(block(entry, "void pathTraceWriteGuides(").contains("if (!sampleTrusted || !result.primary.valid) {"),
            "a rejected sample writes the neutral set, exactly like a miss");
        assertTrue(block(entry, "float4 pathTraceNrdMotion(").contains("if (!sampleTrusted || !result.primary.valid) {"),
            "NRD's motion gets the same verdict");
        for (String pass : new String[] {PASS, SCENE_PASS}) {
            assertTrue((read(pass) + "\n" + entry).contains("bool sampleTrusted = !pathTraceSampleRejected(result.flags);"),
                pass + ": the pass wrappers share the one entry body that computes the verdict");
        }
    }

    @Test
    void theHostAndThePushKeepTheirPromise() {
        assertEquals(2, RtPathTraceSlangPass.OPTION_NRD_MOTION, "the Java mirror of the shader's option bit");
        assertEquals(16, RtPathTraceSlangPass.MOTION_ELEMENT_BYTES, "one vec4 per pixel, the pack's stride");
        String record = read(HOST_PASS);
        assertTrue(record.contains("new Expected(\"outMotionBuffer\", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER));"),
            "the manifest must declare the binding as a storage buffer");
        assertTrue(record.contains("requireElementBytes(manifest, \"outMotionBuffer\", MOTION_ELEMENT_BYTES);"),
            "a shader whose vec4 is not 16 bytes would index past the bound range");
        assertTrue(record.contains("motionDummy = zeroedBuffer(MOTION_ELEMENT_BYTES);"),
            "a zero element keeps the descriptor complete when the layout is not NRD");
        assertTrue(record.contains("nativeBuffers.retain(cmd.address(), motionDummy);"),
            "the dummy outlives every recording that references it");
        assertTrue(record.contains("boolean wantsMotion = (dispatch.flags() & OPTION_NRD_MOTION) != 0;"),
            "the flag and the binding are two halves of one contract");
        assertTrue(record.contains("if (wantsMotion != (boundMotion.buffer() != 0L)) {"),
            "record refuses a flag without a buffer and a buffer without the flag");
        assertTrue(record.contains("long motionBytes = (long) frame.width * frame.height * MOTION_ELEMENT_BYTES;"),
            "the shader writes exactly one record per pixel");
        assertTrue(record.contains("if (boundMotion.buffer() != 0L && boundMotion.bytes() < motionBytes) {"),
            "a short range is a host bug, and the missing-buffer case is not a size failure");
        assertTrue(record.contains("if (wantsMotion && boundGuides == null) {"),
            "NRD also consumes the guides, so motion without a G-buffer is refused");
        String resources = block(record, "private List<SlangProgram.Resource> resources(");
        assertTrue(resources.contains("motionBuffer(nrdMotion, motionDummy));"), "the binding is always supplied");
        String helper = block(record, "private static SlangProgram.Resource motionBuffer(");
        assertTrue(helper.contains("return SlangProgram.Resource.storageBuffer(\"outMotionBuffer\", dummy.handle(), 0L, dummy.size());"),
            "the dummy is the fallback, never a stale ring slice");
    }

    @Test
    void theFrameTurnsTheBitOnOnlyWhenTheViewsCarryTheBuffer() {
        String frame = read(HOST_FRAME);
        assertTrue(frame.contains("| (views.motion() != 0L && guides != null ? RtPathTraceSlangPass.OPTION_NRD_MOTION : 0);"),
            "the push follows the bound views, never a setting");
        String backend = read(BACKEND);
        assertTrue(backend.contains("RtPathTraceSlangPass.BufferView motion = (dispatch.flags() & RtPathTraceSlangPass.OPTION_NRD_MOTION) != 0"),
            "the backend derives the binding from the same flag the push carries");
        assertTrue(backend.contains("(long) width * height * RtPathTraceSlangPass.MOTION_ELEMENT_BYTES)"),
            "and binds exactly the pixels this frame writes");
        assertTrue(backend.contains("motion, views.depth(), frame, dispatch);"), "the range travels with the frame");
    }

    @Test
    void theMotionBufferIsTheDenoisersOwnVec4PixelBuffer() {
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("if (useDenoiser && denoiseMode.isNrd() && wantsEntityMotion()) {"),
            "only the NRD layout reads this buffer from the trace; RR keeps its velocity pass");
        assertTrue(renderer.contains("motionHandle = entityMotionBuf.handle;"),
            "the pass receives the handle of the buffer the pack reads by address");
        assertTrue(renderer.contains("lightmapView, motionHandle));"), "the handle rides with the frame's views");
        assertTrue(renderer.contains("private static void ensureEntityMotionBuffer(VkDevice device, int w, int h) {"),
            "one allocation site for both the address and the binding");
        int alloc = renderer.indexOf("ensureEntityMotionBuffer(device, w, h);");
        int bind = renderer.indexOf("pass.bind(new RtFrameBindings.Views(");
        assertTrue(alloc >= 0 && bind >= 0 && alloc < bind,
            "the buffer has to exist before bind, or the pass captures zero and never writes");
        assertFalse(renderer.contains("the Slang path does not fill yet"),
            "the refusal that used to gate this buffer must not come back");
        String views = block(read(BINDINGS), "public record Views(");
        assertTrue(views.contains("long depth, long lightmap, long motion) {"),
            "the motion handle is part of the frame's views");
        assertTrue(views.contains("gbuffer, 0L, 0L, 0L);"),
            "a frame without motion uses the same record, so the allocation is not a descriptor rebind");
    }

    private static String block(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block: " + header);
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
