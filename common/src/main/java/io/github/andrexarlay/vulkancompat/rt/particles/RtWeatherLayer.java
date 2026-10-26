package io.github.andrexarlay.vulkancompat.rt.particles;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Rain and snow columns inside the ray traced frame.
 * <p>
 * Why they were missing: {@code LevelRenderer.executeClassicTransparency} draws weather with
 * {@code WeatherEffectRenderer.render(WeatherRenderState, RenderPass)} into the render pass of the world target, i.e. into the same
 * colour image the RT result is copied over, so the copy erased the columns; in RT the weather quads are not part of the TLAS and
 * they have no motion vectors, so the denoisers could not keep them stable either.
 * <p>
 * Approach: exactly the stages {@link RtParticleLayer} uses for particles, sharing its layer, its depth attachment and its composite
 * instead of adding a second one:
 * <ol>
 *   <li><b>Capture</b> ({@code MixinWeatherEffectRenderer}): the draw state of the vanilla call is recorded - the game's own vertex
 *       buffer slice, its primitive topology, the rain / snow textures, the transform slice written from
 *       {@code RenderSystem.getDynamicUniforms()} and the lightmap of {@code GameRenderer}. Nothing is cancelled, so the vanilla
 *       draw stays and remains the raster fallback.</li>
 *   <li><b>Replay</b>: {@link RtParticleLayer#replay(com.mojang.blaze3d.pipeline.RenderTarget)} opens one pass over
 *       {@code bedray_rt_particles} and asks this class to add its draws after the particle draws - the vanilla order, in which
 *       weather comes after particles and clouds inside the world pass. The pass uses the depth attachment of the raster pass, so
 *       terrain, blocks and entities occlude the columns; a single pass also keeps the depth of the particles and of the weather
 *       consistent, which two passes over the same layer could not guarantee.
 *       <p>
 *       The replay always uses {@code RenderPipelines.WEATHER} - the classic pipeline of the game, not the OIT variant the columns
 *       may have been drawn with: the improved-transparency stages write into OIT images this private RGBA8 layer does not have,
 *       while the classic pipeline is what the vanilla path without improved transparency uses and what the premultiplied composite
 *       expects. Same convention as the particle replay, which replays {@code SingleQuadParticle.Layer#pipeline()}.
 *       <p>
 *       Uniforms are the game's own state: {@code RenderSystem.bindDefaultUniforms} (Projection / Fog / Globals / Lights) is bound by
 *       the caller, {@code DynamicTransforms} is the slice recorded during the world pass, {@code Sampler2} the lightmap the vanilla
 *       call bound with a clamp-to-edge linear sampler, {@code Sampler0} the rain or the snow texture - no hand-written constants.</p></li>
 *   <li><b>Composite</b>: {@code rt_particles_composite.comp}, unchanged. Weather never reaches the denoisers, so it cannot smear or
 *       ghost, and it goes through the upscaler with the rest of the scene.</li>
 * </ol>
 * The index buffer is not captured: it is the shared sequential buffer of the game for the recorded topology, which is recreated
 * with a different {@code IndexType} whenever it grows, so it is resolved again at replay time and the frame is left without weather
 * when it no longer covers the draw set (growing it inside an open pass is not an option).
 * <p>
 * Everything runs on the render thread. A failure sets {@code failed} and only stops weather; the frame keeps rendering.
 */
public final class RtWeatherLayer {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    /** GPU state of the columns of this frame; every field is an object of the game, taken during the world pass. */
    record Columns(GpuBufferSlice vertices, PrimitiveTopology topology, GpuTextureView rainTexture, GpuSampler rainSampler,
                   GpuTextureView snowTexture, GpuSampler snowSampler, GpuBufferSlice transforms, GpuTextureView lightmap) {
        /** True when the recorded state can actually be drawn: buffers, transform slice and one texture per drawn part. */
        boolean complete(int rainColumns, int snowColumns) {
            return vertices != null && topology != null && transforms != null && lightmap != null
                && rainColumns + snowColumns > 0
                && (rainColumns <= 0 || rainTexture != null)
                && (snowColumns <= 0 || snowTexture != null);
        }
    }

    private static final WeatherVisitLedger LEDGER = new WeatherVisitLedger();
    private static Columns columns;
    private static boolean failed;

    // telemetry (cumulative since the game started)
    private static long framesCaptured;
    private static long framesReplayed;
    private static long framesWithoutIndexBuffer;
    private static int columnsLastFrame;
    private static int drawsLastFrame;

    private RtWeatherLayer() {
    }

    /** Weather rides on the particle layer: the own setting must be on and the layer itself must be wanted. */
    public static boolean capturing() {
        return !failed && RtSettings.weather() && RtParticleLayer.capturing();
    }

    /** Head of {@code GameRenderer.render()}: forgets the previous frame. */
    public static void beginFrame() {
        LEDGER.clear();
        columns = null;
    }

    /**
     * Called by the capture mixin while the vanilla weather renderer draws the columns of this frame. The first visit of the frame
     * wins ({@link WeatherVisitLedger}), so the OIT stages do not multiply the draw set.
     *
     * @param vertexBuffer weather buffer of the game (uploaded by its own {@code prepare}), taken in full
     * @param topology     topology its mesh data was built with (quads)
     * @param rainTexture  the game's rain texture
     * @param rainColumns  rain columns of the buffer: the first part of the draw set
     * @param snowTexture  the game's snow texture
     * @param snowColumns  snow columns, drawn after the rain ones
     * @param transforms   {@code DynamicTransforms} the vanilla call wrote from the game's model view matrix
     * @param lightmap     {@code Sampler2} of the weather pipeline: the lightmap the game bound
     */
    public static void capture(GpuBuffer vertexBuffer, PrimitiveTopology topology, AbstractTexture rainTexture, int rainColumns,
                               AbstractTexture snowTexture, int snowColumns, GpuBufferSlice transforms, GpuTextureView lightmap) {
        if (failed || !LEDGER.accept(rainColumns, snowColumns)) {
            return;
        }
        columns = new Columns(vertexBuffer == null ? null : vertexBuffer.slice(), topology,
            rainTexture == null ? null : rainTexture.getTextureView(), rainTexture == null ? null : rainTexture.getSampler(),
            snowTexture == null ? null : snowTexture.getTextureView(), snowTexture == null ? null : snowTexture.getSampler(),
            transforms, lightmap);
        columnsLastFrame = LEDGER.columns();
        framesCaptured++;
    }

    /** Are weather columns waiting for the layer? Only complete state counts: nothing is drawn from a half-captured frame. */
    public static boolean hasDraws() {
        return !failed && columns != null && columns.complete(LEDGER.rainColumns(), LEDGER.snowColumns());
    }

    /** The capture itself broke: the world pass must not feel it, so weather is off from now on. */
    public static void captureFailed(Throwable e) {
        failed = true;
        columns = null;
        LOGGER.error("[VKB-RT] weather capture FAILED (rain and snow stay raster-only, the setting is untouched)\n{}", LogPaths.stack(e));
    }

    /**
     * Adds the draws of the captured frame to the open particle pass, after the particle draws.
     * The caller must have checked {@link #hasDraws()}.
     */
    public static void draw(RenderPass pass) {
        Columns c = columns;
        if (c == null || failed) {
            return;
        }
        int rain = LEDGER.rainColumns();
        int snow = LEDGER.snowColumns();
        int draws = 0;
        try {
            RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(c.topology());
            if (sequential == null || !sequential.hasStorage(LEDGER.requiredIndices())) {
                framesWithoutIndexBuffer++;
                return;
            }
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.WEATHER));
            pass.setVertexBuffer(0, c.vertices());
            pass.setIndexBuffer(sequential.getBuffer(), sequential.type());
            pass.setUniform("DynamicTransforms", c.transforms());
            pass.setUniform("Sampler2", c.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if (rain > 0) {
                pass.setUniform("Sampler0", c.rainTexture(), c.rainSampler());
                pass.drawIndexed(LEDGER.rainIndexCount(), 1, 0, 0, 0);
                draws++;
            }
            if (snow > 0) {
                pass.setUniform("Sampler0", c.snowTexture(), c.snowSampler());
                pass.drawIndexed(LEDGER.snowIndexCount(), 1, LEDGER.snowFirstIndex(), 0, 0);
                draws++;
            }
            drawsLastFrame = draws;
            framesReplayed++;
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            drawsLastFrame = 0;
            LOGGER.error("[VKB-RT] weather layer FAILED (rain and snow are not drawn while RT is displayed; setting stays on)\n{}",
                LogPaths.stack(e));
        }
    }

    /** One line for the autotest log, carried by {@link RtParticleLayer#telemetry()} (no spaces: the parent joins fields). */
    public static String telemetry() {
        return String.format(Locale.ROOT, "enabled=%s;capturing=%s;frames=%d;columns=%d;replayed=%d;draws=%d;no_index_buffer=%d;failed=%s",
            RtSettings.weather(), capturing(), framesCaptured, columnsLastFrame, framesReplayed, drawsLastFrame,
            framesWithoutIndexBuffer, failed);
    }

    /** Failure of an earlier frame is forgotten when RT is switched on again ({@link RtParticleLayer#clearFailure()}). */
    public static void clearFailure() {
        failed = false;
    }

    /** Test seam: a cold session - no counters of earlier frames, no failure, nothing captured (see {@code WeatherVisitLedger}). */
    static void resetForTests() {
        beginFrame();
        failed = false;
        framesCaptured = 0L;
        framesReplayed = 0L;
        framesWithoutIndexBuffer = 0L;
        columnsLastFrame = 0;
        drawsLastFrame = 0;
    }
}
