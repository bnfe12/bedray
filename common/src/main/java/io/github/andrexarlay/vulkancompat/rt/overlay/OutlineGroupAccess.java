package io.github.andrexarlay.vulkancompat.rt.overlay;

import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import java.util.List;

/** Read-only access to Minecraft's prepared outline draws; Minecraft retains all GPU ownership. */
public interface OutlineGroupAccess {
    List<StagedVertexBuffer.Draw> vkb$outlineDraws();
    List<PreparedRenderType> vkb$outlineTypes();
}
