package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.andrexarlay.vulkancompat.rt.overlay.OutlineGroupAccess;
import io.github.andrexarlay.vulkancompat.rt.overlay.RtBlockOutline;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Capture prepared buffers while they are still available, before PreparedFrame closes. */
@Mixin(value = RenderTypeFeatureRenderer.class, remap = false)
public abstract class MixinOutlineFeatureRenderer {
    @Shadow private List<?> groups;

    @Inject(method = "executeGroup", at = @At("HEAD"))
    private void vkb$captureOutline(FeatureFrameContext context, OitStage stage, RenderPass pass, int groupIndex,
                                    List<?> submits, boolean strictlyOrdered, CallbackInfo ci) {
        if ((Object) this instanceof ShapeOutlineFeatureRenderer && RtBlockOutline.capturing()
                && groupIndex >= 0 && groupIndex < groups.size()) {
            RtBlockOutline.capture(context, (OutlineGroupAccess) groups.get(groupIndex));
        }
    }
}
