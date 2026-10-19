package io.github.andrexarlay.vulkanbridge.api;

/**
 * Points inside one {@code GameRenderer.render()} call where the core dispatches {@link FramePhaseListener}s (ADR-0003).
 * Injection points (signatures: {@code javap -p} / {@code javap -c} on libs/minecraft-26.3-client.jar; the same points the
 * RTX mod's MixinGameRenderer and the former MixinGameRendererGlBridge use):
 * <ul>
 *   <li>{@link #FRAME_BEGIN}: HEAD of {@code public void render()}; every frame, before the main target is resized.</li>
 *   <li>{@link #BEFORE_LEVEL}: before {@code invokevirtual GameRenderer.renderLevel()V} in {@code render()}; only when the level is rendered.</li>
 *   <li>{@link #AFTER_LEVEL_POST}: after {@code invokevirtual GameRenderer.applyPostEffects()V} in {@code render()}; only when the level is rendered.</li>
 *   <li>{@link #BEFORE_HUD3D}: HEAD of {@code private void render3dHud(CameraRenderState, PlayerRenderState, OptionsRenderState, boolean)}
 *       (called from {@code renderLevel()}); the world depth is still alive and no render pass is open.</li>
 *   <li>{@link #BEFORE_GUI}: before {@code invokevirtual GuiRenderer.render()V} in {@code render()}; every frame, world and post effects done.</li>
 *   <li>{@link #FRAME_END}: RETURN of {@code render()}; every frame, after the GUI, before {@code Minecraft.renderFrame} blits the main target.</li>
 * </ul>
 * All phases run on the render thread, outside any engine render pass. Declaration order is execution order.
 */
@Experimental
public enum FramePhase {
    FRAME_BEGIN,
    BEFORE_LEVEL,
    AFTER_LEVEL_POST,
    BEFORE_HUD3D,
    BEFORE_GUI,
    FRAME_END
}
