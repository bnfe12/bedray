package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Path Tracing mode on the AOT Slang programs: {@code passes/path_trace.slang} traces one path per pixel into linear
 * HDR radiance ({@link RtPathTraceSlangPass}), then {@code passes/path_display.slang} folds it into a running mean and
 * writes exposure + tone curve into the renderer's RGBA8 {@code rt_output}. Depth goes straight into {@code rt_depth},
 * so the composite, bloom, particles and upscalers downstream are unchanged.
 *
 * <p>What this pass does not do yet, compared with the GLSL {@link RtPathTracerPass}: no NRD / DLSS-RR G-buffer (the
 * renderer only selects this pass when no G-buffer denoiser is wanted, or when the Slang path is forced), no auto
 * exposure (the GLSL shader feeds the luminance statistics; exposure is fixed at 1), a two-colour sky instead of
 * {@code rt_sky.glsl}, and primary rays start at the camera origin without the view-bob offset.
 *
 * <p>Recording lifetime: every command buffer passed to {@link #dispatch} keeps native descriptor pools until
 * {@link #releaseRecording(long)}; the renderer calls it when the same transient handle comes back from the pool reset
 * after its previous submission completed, exactly as for the Slang bloom.
 */
public final class RtSlangPathPass implements RtPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    static final String DISPLAY_PASS = "path_display";
    static final int DISPLAY_PUSH_BYTES = 16;
    static final int DISPLAY_FLAG_ACCUMULATE = 1;
    static final int DISPLAY_FLAG_TONEMAP = 1 << 1;
    static final float MAX_RADIANCE = 64f;

    /** True when this runtime can run both Slang programs: AOT artifacts present and every required capability enabled. */
    static boolean isSupported(SlangRuntime runtime) {
        return supportProblem(runtime) == null;
    }

    /** Why this runtime cannot run the Slang path, or null when it can. Used verbatim in the fallback/failure log. */
    static String supportProblem(SlangRuntime runtime) {
        if (runtime == null) {
            return "no Slang runtime";
        }
        if (!SlangPassLibrary.available(RtPathTraceSlangPass.PASS_NAME)) {
            return "AOT pass " + RtPathTraceSlangPass.PASS_NAME + " is missing from the jar";
        }
        if (!runtime.supports(RtPathTraceSlangPass.REQUIRED_CAPABILITIES)) {
            return "device capabilities required by " + RtPathTraceSlangPass.PASS_NAME
                + " (rayQuery, bufferDeviceAddress, shaderInt64) are not enabled";
        }
        if (!SlangPassLibrary.available(DISPLAY_PASS)) {
            return "AOT pass " + DISPLAY_PASS + " is missing from the jar";
        }
        return null;
    }

    private final VkDevice device;
    private final SlangRuntime runtime;
    private final boolean requestedGbuffer;
    private final RtPathTraceSlangPass trace;
    private final SlangProgram display;
    private final long sampler;
    private final RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
    private final Set<Long> recordings = new LinkedHashSet<>();
    private RtMemory memory;
    private RtMemory.Img accum;
    private RtPipeline.Views views;
    private int accumFrames;
    private boolean forceReset = true;
    private int lastSettingsHash;
    private int lastWidth;
    private int lastHeight;
    private final Matrix4f lastInvViewProj = new Matrix4f();
    private double lastCamX = Double.NaN;
    private double lastCamY;
    private double lastCamZ;
    private final float[] lastSunDirection = new float[3];
    private final float[] lastSunColor = new float[3];
    private final float[] lastSkyColor = new float[3];
    private boolean hasLighting;
    private int sampleIndex;
    private boolean destroyed;

    /**
     * {@code requestedGbuffer}: what the renderer asked for. This pass never writes the G-buffer, but it remembers the
     * request so the renderer does not rebuild it every frame for not matching (see {@link #builtFor(boolean)}).
     */
    RtSlangPathPass(VkDevice device, SlangRuntime runtime, RtMemory memory, boolean requestedGbuffer) {
        this.device = device;
        this.runtime = runtime;
        this.memory = memory;
        this.requestedGbuffer = requestedGbuffer;
        this.trace = new RtPathTraceSlangPass(device, runtime, memory);
        try {
            Optional<SlangProgram> program = runtime.program(DISPLAY_PASS);
            if (program.isEmpty()) {
                throw new IllegalStateException("missing Slang pass " + DISPLAY_PASS);
            }
            this.display = program.get();
            requireDisplayManifest(display);
            this.sampler = createAtlasSampler(device);
        } catch (RuntimeException e) {
            trace.close();
            throw e;
        }
        LOGGER.info("[VKB-RT] path tracing on AOT Slang passes {} + {} (GLSL rt_path.comp not used{})",
            RtPathTraceSlangPass.PASS_NAME, DISPLAY_PASS, requestedGbuffer ? ", G-buffer denoiser disabled" : "");
    }

    static void requireDisplayManifest(SlangProgram program) {
        SlangPassManifest manifest = program.manifest();
        if (manifest.pushConstantBytes() != DISPLAY_PUSH_BYTES) {
            throw new IllegalStateException(program.name() + " pushes " + manifest.pushConstantBytes() + " B, host block is "
                + DISPLAY_PUSH_BYTES + " B");
        }
        for (String name : List.of("radiance", "accum", "outImage")) {
            SlangPassManifest.Binding binding = manifest.binding(name);
            if (binding == null || binding.descriptorType() != BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE) {
                throw new IllegalStateException(program.name() + ": '" + name + "' must be a storage image");
            }
        }
        if (manifest.bindings().size() != 3) {
            throw new IllegalStateException(program.name() + " declares " + manifest.bindings().size()
                + " binding(s), host supplies 3");
        }
    }

    /** Same sampler as the GLSL pipeline uses for the block atlases: nearest, clamp, level 0 only. */
    private static long createAtlasSampler(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .minLod(0f).maxLod(0f).unnormalizedCoordinates(false);
            LongBuffer p = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateSampler(device, info, null, p), "create_sampler");
            return p.get(0);
        }
    }

    @Override
    public RtMode mode() {
        return RtMode.PATH_TRACED;
    }

    @Override
    public String describe(RtFrameParams p) {
        return "path_slang(bounces=" + p.bounces + ",spp=1,dist=" + p.rayDistance
            + (RtSettings.accumulate() ? ",accumulate" : "") + ",frames=" + (accumFrames + 1) + ",denoiser=off)";
    }

    @Override
    public void prepare(VkCommandBuffer cmd, RtMemory mem, int width, int height) {
        memory = mem;
        if (accum != null && lastWidth == width && lastHeight == height) {
            return;
        }
        if (accum != null) {
            VK10.vkDeviceWaitIdle(device); // frames in flight may still read the old running mean
            releaseRecordings();
            mem.destroy(accum);
            accum = null;
        }
        accum = mem.createImage("rt_path_slang_accum", width, height, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
            VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        VkBarriers.transition(cmd, accum.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT,
            VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
        lastWidth = width;
        lastHeight = height;
        forceReset = true;
    }

    @Override
    public void configure(RtFrameParams params, boolean denoising) {
        params.mode = RtConfig.effectiveMode();
        params.bounces = RtAdaptiveQuality.bounces(RtSettings.spp(), RtSettings.bounces());
        params.spp = 1;
        params.rayDistance = RtSettings.pathMaxDistance();
        params.flags |= RtFrameParams.FLAG_EMISSIVE;

        boolean cameraMoved = !Double.isNaN(lastCamX) && (Math.abs(params.cameraX - lastCamX) > RtAccumulation.POS_EPS
            || Math.abs(params.cameraY - lastCamY) > RtAccumulation.POS_EPS
            || Math.abs(params.cameraZ - lastCamZ) > RtAccumulation.POS_EPS
            || !params.invViewProjStable.equals(lastInvViewProj, RtAccumulation.MATRIX_EPS));
        // The running mean is not reprojected: any lighting change starts it over instead of ghosting the old sun.
        boolean lightingChanged = hasLighting
            && (differs(params.key.dir, lastSunDirection)
                || differs(params.key.color, lastSunColor)
                || differs(params.skyColor, lastSkyColor));
        int settingsHash = Objects.hash(params.bounces, params.rayDistance, RtSettings.accumulate(), params.mode,
            RtSettings.pathPreset, RtSettings.preset);
        if (forceReset || cameraMoved || lightingChanged || params.sceneChanged || settingsHash != lastSettingsHash
            || !RtSettings.accumulate()) {
            accumFrames = 0;
        } else if (accumFrames < RtFrameParams.MAX_ACCUM) {
            accumFrames++;
        }
        forceReset = false;
        lastSettingsHash = settingsHash;
        lastCamX = params.cameraX;
        lastCamY = params.cameraY;
        lastCamZ = params.cameraZ;
        lastInvViewProj.set(params.invViewProjStable);
        System.arraycopy(params.key.dir, 0, lastSunDirection, 0, 3);
        System.arraycopy(params.key.color, 0, lastSunColor, 0, 3);
        System.arraycopy(params.skyColor, 0, lastSkyColor, 0, 3);
        hasLighting = true;
        params.accumCount = accumFrames;
    }

    private static boolean differs(float[] a, float[] b) {
        return Math.abs(a[0] - b[0]) > RtAccumulation.SUN_EPS
            || Math.abs(a[1] - b[1]) > RtAccumulation.SUN_EPS
            || Math.abs(a[2] - b[2]) > RtAccumulation.SUN_EPS;
    }

    @Override
    public void bind(RtPipeline.Views common) {
        views = common;
    }

    @Override
    public boolean usesGbuffer() {
        return false;
    }

    @Override
    public boolean builtFor(boolean gbuffer) {
        return gbuffer == requestedGbuffer;
    }

    @Override
    public void resetHistory() {
        forceReset = true;
    }

    @Override
    public void dispatch(VkCommandBuffer cmd, RtFrameParams params, int width, int height) {
        if (views == null || accum == null) {
            throw new IllegalStateException("path_slang dispatched before prepare/bind");
        }
        fillFrame(params, width, height);
        RtPathTraceSlangPass.Dispatch dispatch = new RtPathTraceSlangPass.Dispatch(Math.max(1, params.bounces),
            8, sampleIndex++, 0, 0, 0.01f, 0.1f, Math.max(1f, params.rayDistance), MAX_RADIANCE, 0.5f);
        recordings.add(cmd.address());
        trace.record(cmd, new RtPathTraceSlangPass.Scene(views.tlas(), views.table()),
            new RtPathTraceSlangPass.Atlases(views.atlas(), views.material(), views.normal(), sampler),
            views.depth(), frame, dispatch);

        int flags = DISPLAY_FLAG_TONEMAP | (RtSettings.accumulate() ? DISPLAY_FLAG_ACCUMULATE : 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(DISPLAY_PUSH_BYTES);
            push.putInt(0, accumFrames).putInt(4, flags).putFloat(8, 1.0f).putFloat(12, MAX_RADIANCE);
            display.dispatch(cmd.address(), display.groups(width, 0), display.groups(height, 1), 1, List.of(
                SlangProgram.Resource.storageImage("radiance", trace.radianceView()),
                SlangProgram.Resource.storageImage("accum", accum.view),
                SlangProgram.Resource.storageImage("outImage", views.output())), push);
        }
        VkBarriers.fullMemoryBarrier(cmd); // rt_output and rt_depth are read by the composite, bloom and upscalers next
    }

    /**
     * FrameConstants from the renderer's frame state. The camera basis is derived from the same inverse view-projection
     * and the same pixel-to-NDC mapping (including flipY) the GLSL cameraRay uses: three points on one plane of constant
     * clip depth give forward, right, down, tan(half fov) and aspect exactly, so a Slang ray through a pixel is the GLSL
     * ray through that pixel.
     */
    private void fillFrame(RtFrameParams p, int width, int height) {
        float yUp = p.flipY ? 1f : -1f; // matrix-space NDC y of the top edge (Slang ndc.y = -1)
        Vector3f centre = unproject(p.invViewProj, 0f, 0f);
        Vector3f rightEdge = unproject(p.invViewProj, 1f, 0f);
        Vector3f bottomEdge = unproject(p.invViewProj, 0f, -yUp);
        Vector3f rightVec = new Vector3f(rightEdge).sub(centre);
        Vector3f downVec = new Vector3f(bottomEdge).sub(centre);
        float centreLen = Math.max(centre.length(), 1.0e-6f);
        float rightLen = Math.max(rightVec.length(), 1.0e-6f);
        float downLen = Math.max(downVec.length(), 1.0e-6f);
        Vector3f forward = new Vector3f(centre).div(centreLen);
        Vector3f right = new Vector3f(rightVec).div(rightLen);
        Vector3f up = new Vector3f(downVec).div(-downLen);

        frame.invViewProjection.set(p.invViewProj);
        frame.viewProjection.set(p.invViewProj).invert();
        frame.forward.set(forward.x, forward.y, forward.z, downLen / centreLen);
        frame.right.set(right.x, right.y, right.z, rightLen / downLen);
        frame.up.set(up.x, up.y, up.z, 0f);
        frame.origin.set(0f, 0f, 0f, p.frame / 20f);
        // Sub-pixel jitter per frame so the running mean also antialiases; zw is the pixel size the shader uses for NDC.
        float jx = accumFrames == 0 ? 0f : halton(sampleIndex + 1, 2) - 0.5f;
        float jy = accumFrames == 0 ? 0f : halton(sampleIndex + 1, 3) - 0.5f;
        frame.jitter.set(jx, jy, 1f / width, 1f / height);
        frame.width = width;
        frame.height = height;
        frame.displayWidth = width;
        frame.displayHeight = height;

        float[] dir = p.key.dir;
        float[] color = p.key.color;
        float sunRadius = Math.max(RtSettings.sunRadiusRad(), 1.0e-4f);
        frame.sunDirection.set(dir[0], dir[1], dir[2], sunRadius);
        frame.sunColor.set(color[0], color[1], color[2], 1f);
        frame.moonDirection.set(-dir[0], -dir[1], -dir[2], sunRadius);
        frame.moonColor.set(0f, 0f, 0f, 0f);

        // SkyRenderState.skyColor is the colour vanilla draws (display space); the legacy sky converts it to radiance with
        // tonemapInverse before shading.  The display pass tone-maps this frame again, so writing the raw colour here would
        // tone-map the sky twice (gain 1.2 + shoulder on an already display-referred value) and wash it out.
        float[] sky = p.skyColor;
        float[] zenith = RtTonemap.invert(sky[0], sky[1], sky[2]);
        float[] horizon = RtTonemap.invert(sky[0] * 0.7f + 0.3f, sky[1] * 0.7f + 0.3f, sky[2] * 0.7f + 0.3f);
        frame.zenithColor.set(zenith[0], zenith[1], zenith[2], 1f);
        frame.horizonColor.set(horizon[0], horizon[1], horizon[2], 1f);
        frame.fogColor.set(frame.horizonColor);
        frame.fog.set(0f, 0f, 0f, 0f);
        frame.cloud.set(0f, 0f, 0f, 0f);

        frame.exposure.set(1f, 0f, 0f, 0f);
        frame.autoExposure = 0;
        frame.tonemapOperator = 0;
        frame.bloomEnabled = 0;
        frame.frameIndex = p.frame;
        frame.sampleIndex = sampleIndex;
        frame.samplesPerFrame = 1;
        frame.flags = 0;
    }

    private static Vector3f unproject(Matrix4f invViewProj, float x, float y) {
        Vector4f v = new Vector4f(x, y, 0.5f, 1f).mul(invViewProj);
        float w = Math.abs(v.w) < 1.0e-12f ? 1.0e-12f : v.w;
        return new Vector3f(v.x / w, v.y / w, v.z / w);
    }

    private static float halton(int index, int base) {
        float f = 1f;
        float r = 0f;
        int i = index;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

    @Override
    public void releaseRecording(long commandBuffer) {
        if (!recordings.remove(commandBuffer)) {
            return;
        }
        trace.releaseRecording(commandBuffer);
        if (!runtime.isClosed()) {
            runtime.releaseRecording(commandBuffer); // the display dispatch shares the command buffer's native record
        }
    }

    private void releaseRecordings() {
        for (Long commandBuffer : List.copyOf(recordings)) {
            releaseRecording(commandBuffer);
        }
    }

    @Override
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        VK10.vkDeviceWaitIdle(device);
        releaseRecordings();
        trace.close();
        if (accum != null && memory != null) {
            memory.destroy(accum);
            accum = null;
        }
        VK10.vkDestroySampler(device, sampler, null);
    }
}
