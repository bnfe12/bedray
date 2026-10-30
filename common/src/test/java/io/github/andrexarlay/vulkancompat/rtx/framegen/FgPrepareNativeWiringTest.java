package io.github.andrexarlay.vulkancompat.rtx.framegen;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemoryLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame-generation inputs used to be prepared by {@code fg_prepare.comp}, compiled at runtime through Shaderc. That
 * was the last renderer pass outside the AOT Slang pipeline, so the host now calls the additive native producer
 * {@code bedray_core_render_fg_prepare}: the core owns the pipeline, the flipped blit, the barriers and the recording
 * pin, and the host only hands over the images it already owns.
 *
 * <p>What is pinned here is the wiring, not the maths (the C++ pass has its own CTest): the descriptor the host packs
 * is the header's, the program is the AOT artifact the pass validates, and the recording is released only after the
 * encoder proved retirement - a recording leaked there is a runtime the pool can never close.
 */
class FgPrepareNativeWiringTest {
    private static final Path GPU = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/"
        + "FrameGenGpu.java");
    private static final Path DLSS = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/"
        + "FrameGenRuntime.java");
    private static final Path FSR3 = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/fsr3/"
        + "Fsr3FrameGen.java");
    private static final Path NATIVE = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/"
        + "BedrayCoreNative.java");
    private static final Path HEADER = Path.of("../native/bedray_core/include/bedray_fg_prepare.h");
    private static final Path PASS = Path.of("src/main/resources/shaders/slang/passes/fg_prepare.slang");
    private static final Path LEGACY = Path.of("src/main/resources/shaders/glsl/fg_prepare.comp");

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /** The producer is the core's recording, and nothing in the host compiles a shader for it any more. */
    @Test
    void theHostRecordsTheProducerThroughTheCore() throws IOException {
        String gpu = read(GPU);
        assertFalse(gpu.contains("org.lwjgl.util.shaderc") || gpu.contains("Shaderc.shaderc_"),
            "a renderer pass may not compile GLSL at runtime");
        assertFalse(gpu.contains("fg_prepare.comp"), "the legacy producer shader is not read any more");
        assertTrue(gpu.contains("SlangRuntimePool.acquire("),
            "the pass is borrowed from the per-device native core, so the program handle has an owner");
        assertTrue(gpu.contains("runtime.program(PASS_NAME)"),
            "the AOT artifact is registered by the runtime cache, not re-created here");
        assertTrue(gpu.contains("library.renderFgPrepare(core, desc)"),
            "the core records the flipped blit, the barriers and the dispatch");
        assertFalse(gpu.contains("vkCmdBlitImage") || gpu.contains("vkCmdDispatch"),
            "the host owns no part of the recording, so the two paths cannot drift");
        assertFalse(gpu.contains("vkCmdUpdateDescriptorSets") || gpu.contains("vkCreateComputePipelines"),
            "descriptors and pipelines belong to the core's program");
    }

    /** The 104-byte descriptor the host packs is the one the header's static asserts pin. */
    @Test
    void theDescriptorIsTheHeaders() throws IOException {
        Map<String, Integer> header = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile(
            "offsetof\\(BedrayFgPrepareFrameDesc, (\\w+)\\) == (\\d+)\\);").matcher(read(HEADER));
        while (matcher.find()) {
            header.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        assertEquals(Map.of("command_buffer", 8, "sampler", 24, "source_color_image", 32, "depth_view", 48,
            "window_width", 80, "reserved", 96), header, "the header still pins the fields the host relies on");
        assertEquals(104L, BedrayCoreLayouts.FG_PREPARE_DESC.byteSize(),
            "sizeof(BedrayFgPrepareFrameDesc) is part of the ABI check the core performs");
        assertEquals(8L, offset("command_buffer"));
        assertEquals(24L, offset("sampler"));
        assertEquals(32L, offset("source_color_image"));
        assertEquals(48L, offset("depth_view"));
        assertEquals(80L, offset("window_width"));
        assertEquals(96L, offset("reserved"));
        assertTrue(read(NATIVE).contains("\"bedray_core_render_fg_prepare\""),
            "the export is bound under the name the header declares");
        assertTrue(read(NATIVE).contains("hasFgPrepare()"),
            "the host refuses the producer before the first frame when the DLL predates the export");
    }

    private static long offset(String field) {
        return BedrayCoreLayouts.FG_PREPARE_DESC.byteOffset(groupElement(field));
    }

    /** The AOT artifact has the exact contract the native pass checks before it records a dispatch. */
    @Test
    void theHostChecksTheProgramContractBeforeTheFirstDispatch() throws IOException {
        String gpu = read(GPU);
        assertTrue(gpu.contains("requireManifest("),
            "a one-sided shader edit must fail at creation, not as a wrong dispatch");
        assertTrue(gpu.contains("static final int PUSH_BYTES = 8;"), "the pass pushes uint2");
        assertTrue(gpu.contains("manifest.pushConstantBytes() != PUSH_BYTES"), "and the host checks that width");
        assertTrue(gpu.contains("manifest.bindings().size() != 4"), "one sampler and three storage images");
        assertTrue(gpu.contains("static final int[] THREADS = {8, 8, 1};"), "the dispatch grid the native pass divides by");
        assertTrue(gpu.contains("Arrays.equals(manifest.threadGroupSize(), THREADS)"),
            "and the grid is checked, not assumed");
        String pass = read(PASS);
        assertTrue(pass.contains("Sampler2D depthTexture"), "binding 0 samples the depth copy");
        assertTrue(pass.contains("inMotionVectors"), "binding 1 is the motion input");
        assertTrue(pass.contains("outDepth"), "binding 2 is the flipped depth");
        assertTrue(pass.contains("outMotionVectors"), "binding 3 is the flipped motion");
        assertTrue(pass.contains("float2(motion.x, -motion.y)"),
            "the row flip negates y, which is the whole reason the pass exists");
    }

    /** The check itself, against manifests the compiler could actually emit: a disagreeing one is refused whole. */
    @Test
    void aManifestThatDisagreesWithTheProducerIsRefused() {
        FrameGenGpu.requireManifest(fgManifest(8, new int[] {8, 8, 1}, List.of(
            binding("depthTexture", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
            binding("inMotionVectors", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
            binding("outDepth", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
            binding("outMotionVectors", 3, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE))));
        assertThrows(IllegalStateException.class, () -> FrameGenGpu.requireManifest(fgManifest(12,
            new int[] {8, 8, 1}, List.of(
                binding("depthTexture", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                binding("inMotionVectors", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
                binding("outDepth", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
                binding("outMotionVectors", 3, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)))),
            "a push block the native pass does not write");
        assertThrows(IllegalStateException.class, () -> FrameGenGpu.requireManifest(fgManifest(8,
            new int[] {8, 8, 1}, List.of(
                binding("depthTexture", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                binding("inMotionVectors", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
                binding("outDepth", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)))),
            "a binding the host would not supply");
        assertThrows(IllegalStateException.class, () -> FrameGenGpu.requireManifest(fgManifest(8,
            new int[] {8, 8, 1}, List.of(
                binding("depthTexture", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                binding("inMotionVectors", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER),
                binding("outDepth", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
                binding("outMotionVectors", 3, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)))),
            "the motion input must be the storage image the core binds");
        assertThrows(IllegalStateException.class, () -> FrameGenGpu.requireManifest(fgManifest(8,
            new int[] {16, 16, 1}, List.of(
                binding("depthTexture", 0, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                binding("inMotionVectors", 1, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
                binding("outDepth", 2, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE),
                binding("outMotionVectors", 3, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE)))),
            "the native pass divides the extent by 8");
    }

    private static io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest.Binding binding(
        String name, int slot, int type) {
        return new io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest.Binding(name, slot, type, 1, 0);
    }

    private static io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest fgManifest(
        int push, int[] threads, List<io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest.Binding> bindings) {
        return new io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest(1, FrameGenGpu.PASS_NAME, "src",
            "sha", "main", "compute", "cs_6_5", 0, threads, push, 100, 0, List.of(), bindings);
    }

    /** Deleting the GLSL is the point: with it gone, no code path can fall back to a runtime compile. */
    @Test
    void theLegacyProducerShaderIsDeleted() {
        assertFalse(Files.exists(LEGACY), "fg_prepare.comp is replaced by the AOT pass and the native producer");
    }

    /** A recording is a pinned descriptor pool: release it after the submission, or the runtime never closes. */
    @Test
    void theRecordingIsReleasedAfterTheEncoderProvesRetirement() throws IOException {
        String gpu = read(GPU);
        assertTrue(gpu.contains("library.releaseRecording(core, command)"),
            "the pool is recycled once the encoder's fence proved the work retired");
        assertTrue(gpu.contains("library.discardRecording(core, command)"),
            "an unsubmitted recording is invalidated instead of pinned");
        for (Path caller : new Path[] {DLSS, FSR3}) {
            String source = read(caller);
            assertTrue(source.contains("encoder.queueForDestroy("),
                caller.getFileName() + ": the release rides the fence the encoder already owns");
            assertTrue(source.contains("gpu.discardRecording("),
                caller.getFileName() + ": a failed submission discards the recording");
        }
    }
}
