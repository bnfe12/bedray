package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Host-side contract of {@code passes/path_trace.slang}. These tests run without a Vulkan device: they pin the two
 * packed blocks the host fills, and check the host's expectations against the generated manifest. They do not prove a
 * dispatch works on a GPU.
 */
class RtPathTraceSlangPassTest {
    @Test
    void frameConstantsKeepTheSlangLayout() {
        RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
        frame.view.m00(7f);
        frame.origin.set(1f, 2f, 3f, 4f);
        frame.forward.set(5f, 6f, 7f, 8f);
        frame.width = 1920;
        frame.height = 1080;
        frame.displayWidth = 2560;
        frame.displayHeight = 1440;
        frame.sunColor.set(9f, 10f, 11f, 12f);
        frame.fog.set(13f, 14f, 15f, 16f);
        frame.exposure.set(17f, 18f, 19f, 20f);
        frame.autoExposure = 1;
        frame.frameIndex = 42;
        frame.sampleIndex = 3;
        frame.samplesPerFrame = 2;
        frame.flags = 0x15;

        ByteBuffer out = ByteBuffer.allocate(RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES);
        frame.writeTo(out);

        assertEquals(7f, out.getFloat(RtPathTraceSlangPass.Frame.CAMERA), "CameraState.view at offset 0");
        assertEquals(1f, out.getFloat(RtPathTraceSlangPass.Frame.CAMERA + 320), "CameraState.origin at 320");
        assertEquals(4f, out.getFloat(RtPathTraceSlangPass.Frame.CAMERA + 332), "CameraState.origin.w at 332");
        assertEquals(5f, out.getFloat(RtPathTraceSlangPass.Frame.CAMERA + 336), "CameraState.forward at 336");
        assertEquals(1920, out.getInt(RtPathTraceSlangPass.Frame.CAMERA + 400), "CameraState.size.x at 400");
        assertEquals(1440, out.getInt(RtPathTraceSlangPass.Frame.CAMERA + 412), "CameraState.size.w at 412");
        assertEquals(9f, out.getFloat(RtPathTraceSlangPass.Frame.SUN + 16), "SunState.color at 432");
        assertEquals(16f, out.getFloat(RtPathTraceSlangPass.Frame.ATMOSPHERE + 60), "AtmosphereState.fog.w at 540");
        assertEquals(17f, out.getFloat(RtPathTraceSlangPass.Frame.EXPOSURE), "ExposureState.values at 560");
        assertEquals(1, out.getInt(RtPathTraceSlangPass.Frame.EXPOSURE + 16), "ExposureState.mode.x at 576");
        assertEquals(42, out.getInt(RtPathTraceSlangPass.Frame.FRAME), "FrameConstants.frame.x at 592");
        assertEquals(0x15, out.getInt(RtPathTraceSlangPass.Frame.FRAME + 12), "frame flags at 604");
        assertEquals(RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES, out.capacity());
    }

    @Test
    void frameConstantsRejectShortBuffers() {
        RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
        assertThrows(IllegalArgumentException.class,
            () -> frame.writeTo(ByteBuffer.allocate(RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES - 8)));
    }

    @Test
    void pushBlockKeepsTheSlangLayout() {
        RtPathTraceSlangPass.Dispatch dispatch = new RtPathTraceSlangPass.Dispatch(
            8, 32, 5, 0, 0x21, 0.01f, 0.5f, 512f, 1000f, 0.75f, 64, 0.5f, 1.5f, -2.25f, 0.75f, 8,
            0.25f, -0.5f, 1.75f, 0b1011, 1);
        ByteBuffer out = ByteBuffer.allocate(RtPathTraceSlangPass.PUSH_BYTES);
        dispatch.writeTo(out);

        assertEquals(8, out.getInt(0), "maxDepth at 0");
        assertEquals(32, out.getInt(4), "maxSurfaceSteps at 4");
        assertEquals(5, out.getInt(8), "sampleIndex at 8");
        assertEquals(0, out.getInt(12), "materialCount at 12");
        assertEquals(0x21, out.getInt(16), "flags at 16");
        assertEquals(0.01f, out.getFloat(20), "rayBias at 20");
        assertEquals(0.5f, out.getFloat(24), "alphaCutoff at 24");
        assertEquals(512f, out.getFloat(28), "maxDistance at 28");
        assertEquals(1000f, out.getFloat(32), "maxRadiance at 32");
        assertEquals(0.75f, out.getFloat(36), "sunProbability at 36");
        assertEquals(64, out.getInt(40), "emitterCount at 40");
        assertEquals(0.5f, out.getFloat(44), "emitterProbability at 44");
        assertEquals(1.5f, out.getFloat(48), "camRel.x at 48 (float4 wants a 16-byte boundary)");
        assertEquals(-2.25f, out.getFloat(52), "camRel.y at 52");
        assertEquals(0.75f, out.getFloat(56), "camRel.z at 56");
        assertEquals(8f, out.getFloat(60), "candidates at 60");
        assertEquals(0.25f, out.getFloat(64), "diCameraDelta.x at 64");
        assertEquals(-0.5f, out.getFloat(68), "diCameraDelta.y at 68");
        assertEquals(1.75f, out.getFloat(72), "diCameraDelta.z at 72");
        assertEquals(0, out.getInt(76), "diCameraDelta.w is the reserved lane");
        assertEquals(0b1011, out.getInt(80), "diState.x at 80");
        assertEquals(1, out.getInt(84), "diState.y (parity) at 84");
        assertEquals(0, out.getInt(88), "diState.z is a reserved lane");
        assertEquals(0, out.getInt(92), "diState.w is a reserved lane");
        assertEquals(RtPathTraceSlangPass.PUSH_BYTES, out.capacity());
        assertEquals(96, RtPathTraceSlangPass.PUSH_BYTES, "the 64 B block plus the two DI vectors");
    }

    /** The DI fields are read only inside the gated branch, but a garbage value there is still an unusable frame. */
    @Test
    void diPushRejectsValuesTheShaderCannotUse() {
        assertThrows(IllegalArgumentException.class,
            () -> dispatch(8, 1, 0.5f, 100f, 1f, 0.5f, 4, 0.5f, 8, 0f, 0f, 0f, 1, 2));
        assertThrows(IllegalArgumentException.class,
            () -> dispatch(8, 1, 0.5f, 100f, 1f, 0.5f, 4, 0.5f, 8, 0f, 0f, 0f, 1, -1));
        assertThrows(IllegalArgumentException.class,
            () -> dispatch(8, 1, 0.5f, 100f, 1f, 0.5f, 4, 0.5f, 8, Float.POSITIVE_INFINITY, 0f, 0f, 1, 0));
    }

    @Test
    void dispatchRejectsValuesTheShaderCannotUse() {
        assertThrows(IllegalArgumentException.class, () -> dispatch(0, 1, 0.5f, 100f, 1f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 0, 0.5f, 100f, 1f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 1.5f, 100f, 1f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, Float.NaN, 1f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, 0f, 1f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, 100f, 0f, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, 100f, 1f, -0.1f));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, 100f, 1f, 1.1f));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, 100f, 1f, 0.5f, -1, 0.5f, 8));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, 100f, 1f, 0.5f, 4, 1.5f, 8));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, 100f, 1f, 0.5f, 4, 0.5f, 0));
        assertThrows(IllegalArgumentException.class, () -> dispatch(8, 1, 0.5f, 100f, 1f, 0.5f, 4, 0.5f, 33));
        assertThrows(IllegalArgumentException.class,
            () -> dispatch(8, 1, 0.5f, 100f, 1f, 0.5f, 4, Float.NaN, 8));
    }

    private static RtPathTraceSlangPass.Dispatch dispatch(int depth, int steps, float alphaCutoff, float maxDistance,
                                                          float maxRadiance, float sunProbability) {
        return dispatch(depth, steps, alphaCutoff, maxDistance, maxRadiance, sunProbability, 0, 0f, 1);
    }

    private static RtPathTraceSlangPass.Dispatch dispatch(int depth, int steps, float alphaCutoff, float maxDistance,
                                                          float maxRadiance, float sunProbability, int emitterCount,
                                                          float emitterProbability, int emitterCandidates) {
        return dispatch(depth, steps, alphaCutoff, maxDistance, maxRadiance, sunProbability, emitterCount,
            emitterProbability, emitterCandidates, 0f, 0f, 0f, 0, 0);
    }

    private static RtPathTraceSlangPass.Dispatch dispatch(int depth, int steps, float alphaCutoff, float maxDistance,
                                                          float maxRadiance, float sunProbability, int emitterCount,
                                                          float emitterProbability, int emitterCandidates,
                                                          float diDeltaX, float diDeltaY, float diDeltaZ, int diFlags,
                                                          int diParity) {
        return new RtPathTraceSlangPass.Dispatch(depth, steps, 0, 0, 0, 0.01f, alphaCutoff, maxDistance, maxRadiance,
            sunProbability, emitterCount, emitterProbability, 0f, 0f, 0f, emitterCandidates,
            diDeltaX, diDeltaY, diDeltaZ, diFlags, diParity);
    }

    @Test
    void unsupportedRuntimeIsReportedBeforeAnyResourceIsCreated() {
        assertFalse(RtPathTraceSlangPass.isSupported(null));
    }

    /**
     * The host's expectation list is the contract it will validate at construction; if the shader moves a binding or
     * changes a descriptor type, this fails before a frame can bind the wrong image.
     */
    @Test
    void hostExpectationsMatchTheGeneratedManifest() {
        Assumptions.assumeTrue(SlangPassLibrary.available(RtPathTraceSlangPass.PASS_NAME),
            "path_trace.slang was not compiled into this build");
        SlangPassManifest manifest = SlangPassLibrary.load(RtPathTraceSlangPass.PASS_NAME).orElseThrow().manifest();

        assertEquals(RtPathTraceSlangPass.PUSH_BYTES, manifest.pushConstantBytes(), "push block");
        assertEquals(RtPathTraceSlangPass.EXPECTED.size(), manifest.bindings().size(), "binding count");
        assertEquals(RtPathTraceSlangPass.REQUIRED_CAPABILITIES, manifest.requiredCapabilities(),
            "the host must enable exactly the capabilities the shader declares");
        for (RtPathTraceSlangPass.Expected want : RtPathTraceSlangPass.EXPECTED) {
            SlangPassManifest.Binding binding = manifest.binding(want.name());
            assertNotNull(binding, "binding '" + want.name() + "' exists");
            assertEquals(want.descriptorType(), binding.descriptorType(), "binding '" + want.name() + "' type");
        }
        assertEquals(RtPathTraceSlangPass.SECTION_BYTES, manifest.binding("sections").elementBytes(), "LegacySection");
        assertEquals(RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES, manifest.binding("frame").elementBytes(),
            "FrameConstants");
        assertEquals(RtPathTraceSlangPass.BLOCK_MATERIAL_BYTES, manifest.binding("blockMaterials").elementBytes(),
            "BlockMaterial");
        assertEquals(RtPathTraceSlangPass.EMITTER_BYTES, manifest.binding("emitters").elementBytes(),
            "BlockEmitterRecord: the six vec4 RtEmitters writes, so one buffer serves both paths");
        assertEquals(RtPathTraceSlangPass.RESERVOIR_BYTES, manifest.binding("reservoirs").elementBytes(),
            "DIReservoir: sample, stats, source and receiver");
        assertEquals(RtPathTraceSlangPass.THREAD_GROUP, manifest.threadGroupSize()[0], "group x");
        assertEquals(RtPathTraceSlangPass.THREAD_GROUP, manifest.threadGroupSize()[1], "group y");
    }

    @Test
    void requiredCapabilitiesAreTheThreeBitsTheShaderUses() {
        assertEquals(BedrayCoreLayouts.CAP_RAY_QUERY | BedrayCoreLayouts.CAP_BUFFER_DEVICE_ADDRESS
            | BedrayCoreLayouts.CAP_SHADER_INT64, RtPathTraceSlangPass.REQUIRED_CAPABILITIES);
    }
}
