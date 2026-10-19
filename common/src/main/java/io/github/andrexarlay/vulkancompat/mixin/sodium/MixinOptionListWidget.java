package io.github.andrexarlay.vulkancompat.mixin.sodium;

import io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups;
import io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfig;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Collapsible groups of our page (backlog item 19, {@link CollapsibleGroups}). Targets of Sodium 0.9.2
 * (javap -p libs/sodium-core.jar and decompiled source local reference):
 * {@code private int renderAllPages(Screen, int, int, int)} calls {@code OptionGroup.name()} (record accessor, returns
 * Component) for the header and {@code OptionGroup.options()} (returns java.util.List) for the rows;
 * {@code public void rebuild(Screen)}, {@code public void extractRenderState(GuiGraphicsExtractor, int, int, float)},
 * {@code AbstractScrollable.getScrollAmount()}, {@code ScrollbarWidget.scrollTo(int)}.
 * Only groups registered in {@link CollapsibleGroups} (our translation keys) are changed; every other group is untouched.
 */
@Mixin(value = OptionListWidget.class, remap = false)
public abstract class MixinOptionListWidget {

    private String vkb$lastRowSignature = null;
    private boolean vkb$builtOnce;

    @Inject(method = "rebuild", at = @At("HEAD"))
    private void vkb$clearHeaders(Screen screen, CallbackInfo ci) {
        CollapsibleGroups.clearRenderedState();
    }

    @Redirect(method = "renderAllPages", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/config/structure/OptionGroup;name()Lnet/minecraft/network/chat/Component;"))
    private Component vkb$groupName(OptionGroup group) {
        Component name = group.name();
        String key = CollapsibleGroups.keyOf(name);
        if (key == null) {
            return name;
        }
        return CollapsibleGroups.decorate(key, name, group.options().size());
    }

    @Redirect(method = "renderAllPages", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/config/structure/OptionGroup;options()Ljava/util/List;"))
    private List<Option> vkb$groupOptions(OptionGroup group) {
        String key = CollapsibleGroups.keyOf(group.name());
        if (key != null && !CollapsibleGroups.isExpanded(key)) {
            return List.of();
        }
        // rows of options that do not apply right now (an upscaler mode of another backend) disappear completely
        List<Option> all = group.options();
        List<Option> visible = null;
        for (int i = 0; i < all.size(); i++) {
            Option o = all.get(i);
            boolean hide = VulkanSodiumConfig.isRowHidden(o, ((OptionIdAccessor) o).vkb$id());
            if (hide && visible == null) {
                visible = new java.util.ArrayList<>(all.subList(0, i));
            } else if (!hide && visible != null) {
                visible.add(o);
            }
        }
        return visible == null ? all : visible;
    }

    /** A header click only sets a flag (the click handler iterates the children); the list is rebuilt here, scroll kept. */
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void vkb$applyToggle(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (VulkanSodiumConfig.consumeValueRefreshRequest()) {
            // a preset of our page changed values behind the controls: reload every option from its binding
            ConfigManager.CONFIG.resetAllOptionsFromBindings();
        }
        String sig = VulkanSodiumConfig.hiddenRowSignature();
        boolean rowsChanged = !sig.equals(vkb$lastRowSignature);
        vkb$lastRowSignature = sig;
        if (!CollapsibleGroups.consumeRebuildRequest() && !(rowsChanged && vkb$builtOnce)) {
            vkb$builtOnce = true;
            return;
        }
        vkb$builtOnce = true;
        OptionListWidget self = (OptionListWidget) (Object) this;
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null) {
            return;
        }
        int scroll = self.getScrollAmount();
        self.rebuild(screen);
        ((AbstractScrollableAccessor) self).vkb$scrollbar().scrollTo(scroll);
    }
}
