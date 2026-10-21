package io.github.andrexarlay.vulkancompat.mixin.rtx;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rtx.GameRendererTargetAccessor;
import io.github.andrexarlay.vulkancompat.rtx.VulkanRtxManager;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.debug.DebugInfo;
import io.github.andrexarlay.vulkancompat.rtx.debug.PerfMonitor;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtCaptureControl;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.CameraJitterManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link GameRenderer} for 3D world target isolation, subpixel jitter, and upscaling.
 * <p>
 * Eliminates scissor bounds crashes (known pitfall: scissor bounds) under resolution scaling by keeping
 * {@code mainRenderTarget} and GUI at 100% native window size, while temporarily
 * substituting dedicated low-res targets during {@code renderLevel()}.
 */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class MixinGameRenderer implements GameRendererTargetAccessor {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;

    @Shadow
    @Final
    @Mutable
    private RenderTarget hud3DTarget;

    @Override
    public RenderTarget vkb$getMainRenderTarget() {
        return this.mainRenderTarget;
    }

    @Override
    public void vkb$setMainRenderTarget(RenderTarget target) {
        this.mainRenderTarget = target;
    }

    @Override
    public RenderTarget vkb$getHud3DTarget() {
        return this.hud3DTarget;
    }

    @Override
    public void vkb$setHud3DTarget(RenderTarget target) {
        this.hud3DTarget = target;
    }

    /**
     * A3: Safety reset at HEAD of render(). Restores original targets if a previous frame
     * was interrupted by an exception between A1 and A2, and processes pending settings.
     */
    @Inject(method = "render()V", at = @At("HEAD"))
    private void onRenderBegin(CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            PerfMonitor.frameBegin();
            io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisRenderBridge.onFrameBegin(this.minecraft.level);
            VulkanRtxManager.processPendingChanges();
            WorldTargetManager.resetPhase((GameRenderer) (Object) this);
            // after resetPhase: the XeSS/DLSS runtimes have applied the current request (the RT block reads their state)
            RtCaptureControl.onFrameBegin();
            io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.beginFrame();
            // entity/block-entity/item submits of renderLevel() are captured for the RT scene only while RT is gated on;
            // RtFrameRenderer.render (head of render3dHud, before the hand) publishes and closes the capture
            io.github.andrexarlay.vulkancompat.rt.entity.EntityCaptureManager.getInstance().beginFrame(
                System.nanoTime(), io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.enabled());
            VulkanRtxManager.setFrameRendering(true);
        }
    }

    /**
     * A1: Swaps mainRenderTarget and hud3DTarget with low-res world targets right before renderLevel().
     * Also passes current camera view rotation matrix and position to WorldTargetManager.
     */
    @Inject(
        method = "render()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"
        )
    )
    private void onBeforeRenderLevel(CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            WorldTargetManager.beginLevelProjectionFrame();
            if (this.gameRenderState != null
                && this.gameRenderState.levelRenderState != null
                && this.gameRenderState.levelRenderState.cameraRenderState != null) {
                var camera = this.gameRenderState.levelRenderState.cameraRenderState;
                WorldTargetManager.setCameraState(camera.viewRotationMatrix, camera.pos);
            }
            int winW = this.minecraft.getWindow() != null
                ? this.minecraft.getWindow().getWidth()
                : this.gameRenderState.windowRenderState.width;
            int winH = this.minecraft.getWindow() != null
                ? this.minecraft.getWindow().getHeight()
                : this.gameRenderState.windowRenderState.height;
            WorldTargetManager.beginWorldPhase((GameRenderer) (Object) this, winW, winH);
            io.github.andrexarlay.vulkancompat.rtx.config.SamplerBiasSync.onFrameBegin();
            var wt = WorldTargetManager.getWorldTarget();
            boolean phase = WorldTargetManager.isInWorldPhase() && wt != null;
            DebugInfo.noteWorldPhase(phase, phase ? wt.width : winW, phase ? wt.height : winH, winW, winH);
            PerfMonitor.mark(PerfMonitor.Mark.WORLD_BEGIN);
        }
    }

    /**
     * Injects camera sub-pixel jitter into the projection matrix buffer during level rendering,
     * while storing the unjittered matrix in WorldTargetManager for temporal motion vectors.
     */
    @ModifyArg(
        method = "renderLevel()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
        ),
        index = 0
    )
    private Matrix4f onGetLevelProjectionMatrixBuffer(Matrix4f projMatrix) {
        Matrix4f baseProj = this.gameRenderState != null && this.gameRenderState.levelRenderState != null
            && this.gameRenderState.levelRenderState.cameraRenderState != null
            ? this.gameRenderState.levelRenderState.cameraRenderState.projectionMatrix : null;
        Matrix4f viewRotation = this.gameRenderState != null && this.gameRenderState.levelRenderState != null
            && this.gameRenderState.levelRenderState.cameraRenderState != null
            ? this.gameRenderState.levelRenderState.cameraRenderState.viewRotationMatrix : null;
        boolean levelCall = VulkanBridge.isVulkan() && WorldTargetManager.noteLevelProjection(projMatrix, baseProj, viewRotation);
        if (VulkanBridge.isVulkan() && WorldTargetManager.isInWorldPhase()) {
            if (levelCall) {
                // the hand (hud3d) projection that follows in the same frame must not replace the full bobbed world projection.
                WorldTargetManager.setCurrentProjectionMatrix(WorldTargetManager.levelProjectionOr(projMatrix));
            }
            return CameraJitterManager.applyJitter(projMatrix);
        }
        return projMatrix;
    }

    /**
     * A2: Restores original full-res render targets and executes the upscale blit pass into fullMain
     * right after applyPostEffects(), before depth clear and GUI rendering.
     */
    @Inject(
        method = "render()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterApplyPostEffects(CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            PerfMonitor.mark(PerfMonitor.Mark.POST_END);
            WorldTargetManager.endWorldPhase((GameRenderer) (Object) this);
            PerfMonitor.mark(PerfMonitor.Mark.UPSCALE_END);
        }
    }

    /**
     * Head of {@code render3dHud} (world raster recorded, hand not drawn yet, no render pass open). One injection with a fixed
     * order: RASTER_END stamp, R1 world ray tracing (BLAS/TLAS from Sodium sections, rays; it also shuts its GPU objects down
     * when the gate turned off, so it is called whenever the backend is Vulkan), RT_END stamp, then the X4 depth copy for the
     * temporal upscaler (the hand pass clears the world depth via clearDepthTexture). The RT pass does not read the depth copy.
     * Signature (javap, client jar and NeoForge-patched jar):
     * {@code private void render3dHud(CameraRenderState, PlayerRenderState, OptionsRenderState, boolean)}.
     */
    @Inject(
        method = "render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",
        at = @At("HEAD")
    )
    private void onBeforeRender3dHud(CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            PerfMonitor.mark(PerfMonitor.Mark.RASTER_END);
            // the particles of the world pass are drawn again into their own layer (the RT image replaces the raster colour)
            io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.replay(this.mainRenderTarget);
            io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer.render(
                this.gameRenderState.levelRenderState.cameraRenderState, this.mainRenderTarget,
                this.gameRenderState.levelRenderState);
            // One host-owned post stage after the world renderer and before the hand/HUD.
            io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisRenderBridge.renderFrame(
                this.mainRenderTarget, this.gameRenderState.levelRenderState.cameraRenderState,
                !io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.enabled());
            PerfMonitor.mark(PerfMonitor.Mark.RT_END);
            WorldTargetManager.copyWorldDepth();
        }
    }

    @Inject(method = "render()V", at = @At("RETURN"))
    private void onRenderEnd(CallbackInfo ci) {
        if (VulkanBridge.isVulkan()) {
            VulkanRtxManager.setFrameRendering(false);
        }
    }
}
