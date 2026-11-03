package io.github.andrexarlay.vulkancompat.mixin.rt;

import io.github.andrexarlay.vulkancompat.rt.overlay.OutlineGroupAccess;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group", remap = false)
public interface MixinOutlineGroupAccessor extends OutlineGroupAccess {
    @Override @Accessor("draws") List<StagedVertexBuffer.Draw> vkb$outlineDraws();
    @Override @Accessor("drawRenderTypes") List<PreparedRenderType> vkb$outlineTypes();
}
