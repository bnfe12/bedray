package io.github.andrexarlay.vulkancompat.rt.particles;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.vulkan.VkCommandBuffer;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Particles in the ray traced frame.
 * <p>
 * Why they were missing: the RT image is copied over the whole colour target of the world pass ({@code RtFrameRenderer}), and
 * particles are only rasterised into that target (as are weather, entities of the raster fallback and so on), so the copy erased
 * them; in RT the particle quads are not part of the TLAS (only the glow of emissive ones is captured as light, see
 * {@code EntityCaptureManager.captureParticles}).
 * <p>
 * Approach (hybrid, no shader changes in the RT passes):
 * <ol>
 *   <li><b>Capture</b> ({@code MixinQuadParticleFeatureRenderer}): while the game executes a particle group in the world pass, the
 *       exact draw state is recorded (pipeline, vertex / index buffer, atlas texture, transform slice, lightmap). Nothing is
 *       cancelled, the vanilla draw stays as the raster fallback.</li>
 *   <li><b>Replay</b> (head of {@code render3dHud}, no pass open): the same draws are issued again by the game's own pipelines
 *       into a private RGBA8 layer cleared to zero, with the world depth buffer of the raster pass as the depth attachment. So
 *       the particles are lit by the vanilla light map (emissive sprites like flame are full bright), fogged and tinted exactly as
 *       in vanilla, and are occluded by terrain, blocks and entities because the raster pass draws the same geometry as the
 *       TLAS.</li>
 *   <li><b>Composite</b> ({@link RtParticleComposite}, compute): premultiplied "over" onto the final RT image after the denoiser
 *       (Ray Reconstruction / NRD never see the particles, so no ghosting or smearing from missing motion vectors) and before
 *       bloom / god rays (flames and magic bloom). The image then goes through the upscaler like the rest of the scene.</li>
 * </ol>
 * Everything runs on the render thread. Cost without particles: one boolean check per group; no pass, no dispatch.
 * <p>
 * Rain and snow columns use this same layer, depth attachment and composite: {@link RtWeatherLayer} records them during the world
 * pass and adds its draws here (after the particle draws, which is the order of the world pass), so weather is occluded by geometry
 * and invisible to the denoisers exactly like particles.
 */
public final class RtParticleLayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private static final Vector4fc CLEAR = new Vector4f(0f, 0f, 0f, 0f);

    /** Draw state of one particle layer (pipeline + atlas) captured from the game. */
    record LayerDraw(RenderPipeline pipeline, GpuBufferSlice vertices, GpuBuffer indices, IndexType indexType, GpuTextureView texture,
                     GpuSampler sampler, int indexCount, int firstIndex, int baseVertex) {
    }

    private static final ParticleGroupLedger LEDGER = new ParticleGroupLedger();
    private static final List<LayerDraw> DRAWS = new ArrayList<>();
    private static GpuBufferSlice transforms;
    private static GpuTextureView lightmap;

    private static TextureTarget layer;
    private static RtParticleComposite composite;
    private static final List<TextureTarget> retiredLayers = new ArrayList<>();
    private static boolean layerReady;
    private static boolean failed;

    // telemetry (cumulative since the game started)
    private static long framesWithParticles;
    private static long quadsLastFrame;
    private static int drawsLastFrame;
    private static long compositedFrames;
    private static long replayNanosWindow;
    private static int replayFramesWindow;

    private RtParticleLayer() {
    }

    /** Setting on, RT image displayed, no failure: the layer is wanted this frame. */
    public static boolean capturing() {
        return !failed && RtSettings.particles() && RtGeometryGate.enabled() && RtGeometryGate.failure() == null && RtConfig.displayed();
    }

    /** Head of {@code GameRenderer.render()}: forgets the previous frame. */
    public static void beginFrame() {
        LEDGER.clear();
        DRAWS.clear();
        RtWeatherLayer.beginFrame();
        transforms = null;
        lightmap = null;
        layerReady = false;
        if (layer != null && !RtSettings.particles()) {
            release("setting_off");
        }
    }

    /** Called by the capture mixin while a particle group executes in the world pass. */
    public static void capture(FeatureFrameContext ctx, GpuBufferSlice dynamicTransforms, int groupIndex, ParticleGroupAccess group) {
        if (group == null || dynamicTransforms == null || !LEDGER.firstVisit(groupIndex)) {
            return;
        }
        StagedVertexBuffer staged = ctx.stagedVertexBuffer();
        Map<SingleQuadParticle.Layer, AbstractTexture> textures = group.vkb$textures();
        for (Map.Entry<SingleQuadParticle.Layer, StagedVertexBuffer.Draw> e : group.vkb$layers().entrySet()) {
            StagedVertexBuffer.ExecuteInfo info = staged.getExecuteInfo(e.getValue());
            AbstractTexture tex = textures.get(e.getKey());
            if (info == null || tex == null || info.indexCount() <= 0) {
                continue;
            }
            DRAWS.add(new LayerDraw(e.getKey().pipeline(), info.vertexBuffer().slice(), info.indexBuffer(), info.indexType(),
                tex.getTextureView(), tex.getSampler(), info.indexCount(), info.firstIndex(), info.baseVertex()));
        }
        transforms = dynamicTransforms;
        lightmap = ctx.lightmap();
    }

    /**
     * Head of {@code render3dHud} (world pass recorded, hand not drawn, no pass open): draws the captured particles into the layer.
     * @param target the world target the RT image will replace (its depth buffer is the raster depth of this frame)
     */
    public static void replay(RenderTarget target) {
        boolean weather = RtWeatherLayer.hasDraws();
        if ((DRAWS.isEmpty() && !weather) || target == null || target.getDepthTextureView() == null || !capturing()) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            ensureLayer(target.width, target.height);
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(() -> "Bedray RT particles", layer.getColorTextureView(),
                Optional.<Vector4fc>of(CLEAR), target.getDepthTextureView(), OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                // the particle state can be absent while weather is captured (a frame with rain and no particles): the weather
                // draws bind their own DynamicTransforms / Sampler2, so nothing is set from the particle capture here
                if (transforms != null) {
                    pass.setUniform("DynamicTransforms", transforms);
                }
                if (lightmap != null) {
                    pass.setUniform("Sampler2", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                }
                long quads = 0;
                for (LayerDraw d : DRAWS) {
                    pass.setPipeline(RenderSystem.getCompiledPipeline(d.pipeline()));
                    pass.setVertexBuffer(0, d.vertices());
                    pass.setIndexBuffer(d.indices(), d.indexType());
                    pass.setUniform("Sampler0", d.texture(), d.sampler());
                    pass.drawIndexed(d.indexCount(), 1, d.firstIndex(), d.baseVertex(), 0);
                    quads += d.indexCount() / 6;
                }
                // weather after particles: the order of the world pass, where the columns are drawn over sprites and clouds.
                // One pass for both, so the columns test against the same depth the particles were drawn with.
                if (weather) {
                    RtWeatherLayer.draw(pass);
                }
                quadsLastFrame = quads;
                drawsLastFrame = DRAWS.size();
            }
            layerReady = true;
            // a frame can carry only the columns (rain without particles): counted by RtWeatherLayer, not as a particle frame
            if (!DRAWS.isEmpty()) {
                framesWithParticles++;
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            layerReady = false;
            LOGGER.error("[VKB-RT] particles layer FAILED (particles are not drawn while RT is displayed; setting stays on): {}",
                e.toString(), e);
            return;
        }
        replayNanosWindow += System.nanoTime() - t0;
        replayFramesWindow++;
    }

    /**
     * Inside the RT command buffer, after the denoiser and before bloom: lays the layer over {@code rtOutputView}.
     * @return true when the layer was composited
     */
    public static boolean composite(VkCommandBuffer cmd, SlangRuntime runtime, long rtOutputView, int width, int height) {
        if (!layerReady || layer == null || failed) {
            return false;
        }
        layerReady = false;
        long layerView = WorldTargetManager.extractVkImageView(layer.getColorTextureView());
        if (layerView == 0L || rtOutputView == 0L || layer.width != width || layer.height != height) {
            return false;
        }
        if (composite == null) {
            composite = new RtParticleComposite(runtime);
            LOGGER.info("[VKB-RT] particles composite pipeline ready");
        }
        io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers.fullMemoryBarrier(cmd);
        composite.record(cmd, layerView, rtOutputView, width, height, 1.0f);
        io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers.fullMemoryBarrier(cmd);
        compositedFrames++;
        return true;
    }

    private static void ensureLayer(int w, int h) {
        if (layer == null) {
            layer = new TextureTarget("bedray_rt_particles", w, h, GpuFormat.RGBA8_UNORM, null);
            LOGGER.info("[VKB-RT] particles layer created {}x{} RGBA8 (depth: world depth of the raster pass)", w, h);
        } else if (layer.width != w || layer.height != h) {
            // Never resize an image still referenced by a queued composite.
            // The renderer collects the old target only after its host drain.
            TextureTarget next = new TextureTarget("bedray_rt_particles", w, h, GpuFormat.RGBA8_UNORM, null);
            retiredLayers.add(layer);
            layer = next;
            LOGGER.info("[VKB-RT] particles layer resized {}x{}", w, h);
        }
    }

    /** Frees the layer target and the compute pipeline (RT shutdown, setting turned off). */
    public static void release(String reason) {
        boolean had = layer != null || composite != null;
        if (layer != null) {
            retiredLayers.add(layer);
            layer = null;
        }
        layerReady = false;
        if (had) {
            LOGGER.info("[VKB-RT] particles layer released reason={}", reason);
        }
    }

    public static boolean needsHostDrain() {
        return !retiredLayers.isEmpty();
    }

    /** Caller proves completion or discard, never merely command-buffer reuse. */
    public static void releaseRecording(long commandBuffer) {
        if (composite != null) composite.releaseRecording(commandBuffer);
    }

    /** Capture the recorded owner so a late completion cannot target its replacement. */
    public static Runnable recordingRelease(long commandBuffer) {
        RtParticleComposite recorded = composite;
        return () -> { if (recorded != null) recorded.releaseRecording(commandBuffer); };
    }

    /** Caller has drained the game's submissions and excluded resubmission. */
    public static void collectAfterHostDrain(boolean shutdown) {
        if (composite != null) {
            if (shutdown) {
                composite.destroyAfterHostDrain();
                composite = null;
            } else {
                composite.releaseRecordingsAfterHostDrain();
            }
        }
        while (!retiredLayers.isEmpty()) {
            TextureTarget retired = retiredLayers.get(0);
            retired.destroyBuffers();
            retiredLayers.remove(0);
        }
    }

    /** Failure of an earlier frame is forgotten when RT is switched on again. */
    public static void clearFailure() {
        failed = false;
        RtWeatherLayer.clearFailure();
    }

    /** One line for the autotest log. */
    public static String telemetry() {
        double ms = replayFramesWindow == 0 ? 0.0 : replayNanosWindow / 1.0e6 / replayFramesWindow;
        return String.format(Locale.ROOT, "enabled=%s capturing=%s failed=%s layer=%s draws=%d quads=%d frames=%d composited=%d replay_cpu_ms=%.3f weather=%s",
            RtSettings.particles(), capturing(), failed, layer == null ? "none" : layer.width + "x" + layer.height, drawsLastFrame,
            quadsLastFrame, framesWithParticles, compositedFrames, ms, RtWeatherLayer.telemetry());
    }

    /** Restarts the CPU averaging window of the replay (autotest, between measurements). */
    public static void resetWindow() {
        replayNanosWindow = 0L;
        replayFramesWindow = 0;
    }
}
