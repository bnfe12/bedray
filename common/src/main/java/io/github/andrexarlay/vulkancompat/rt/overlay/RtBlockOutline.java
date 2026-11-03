package io.github.andrexarlay.vulkancompat.rt.overlay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate;
import io.github.andrexarlay.vulkancompat.rt.world.RtConfig;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Replays only vanilla's block-selection lines after the RT image has replaced world colour.
 * The game's prepared state preserves shape, colour, pixel width, view offset and depth test.
 * The outline is display geometry: it never enters a BLAS, lighting or Ray Reconstruction.
 * Render-thread only. References live for this frame; all buffers and retirement belong to Minecraft.
 */
public final class RtBlockOutline {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT-OUTLINE");
    private record Draw(PreparedRenderType type, StagedVertexBuffer.ExecuteInfo buffers) {}
    private static final List<Draw> DRAWS = new ArrayList<>();
    private static final Set<OutlineGroupAccess> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean failureLogged;
    private static long replayedFrames;

    private RtBlockOutline() {}

    public static void beginFrame() { DRAWS.clear(); SEEN.clear(); }

    public static boolean capturing() {
        return RtGeometryGate.enabled() && RtGeometryGate.failure() == null && RtConfig.displayed();
    }

    public static void capture(FeatureFrameContext context, OutlineGroupAccess group) {
        // Improved transparency can execute one prepared group more than once.
        if (!capturing() || !SEEN.add(group)) return;
        var draws = group.vkb$outlineDraws();
        var types = group.vkb$outlineTypes();
        if (draws.size() != types.size()) throw new IllegalStateException("outline draw/type count mismatch");
        for (int i = 0; i < draws.size(); ++i) {
            var info = context.stagedVertexBuffer().getExecuteInfo(draws.get(i));
            if (info != null && info.indexCount() > 0) DRAWS.add(new Draw(types.get(i), info));
        }
    }

    /** Called before the hand clears world depth, only when RT was actually displayed this frame. */
    public static void replay(RenderTarget target, boolean rtDisplayed) {
        if (DRAWS.isEmpty()) return;
        try {
            if (!rtDisplayed || target == null || target.getDepthTextureView() == null) return;
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            try (var pass = encoder.createRenderPass(() -> "Bedray block selection", target.getColorTextureView(),
                    Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                for (var draw : DRAWS) draw.type().drawFromBuffer(draw.buffers(), pass);
            }
            if (failureLogged) LOGGER.info("outline_recovered draws={}", DRAWS.size());
            if (replayedFrames++ == 0) LOGGER.info("outline_replay active=true draws={} size={}x{} depth=world stage=after_rt",
                DRAWS.size(), target.width, target.height);
            failureLogged = false;
        } catch (RuntimeException failure) {
            if (!failureLogged) LOGGER.error("outline_replay_failed draws={} reason={}", DRAWS.size(), failure.toString(), failure);
            failureLogged = true;
        } finally {
            DRAWS.clear(); // never retry partial draws or retain another frame's staged buffers
        }
    }
}
