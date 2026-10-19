package io.github.andrexarlay.vulkancompat.mixin.sodium;

import io.github.andrexarlay.vulkancompat.sodium.PreviewPanel;
import io.github.andrexarlay.vulkancompat.sodium.SettingsCatalog;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlElement;
import net.caffeinemc.mods.sodium.client.gui.widgets.ScrollableTooltip;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Right-hand panel ({@link PreviewPanel}) in the area Sodium reserves for the tooltip. Targets of Sodium 0.9.2 ScrollableTooltip
 * (decompiled source scratch/decomp/sodium092 and javap -p libs/sodium-core.jar): fields {@code ControlElement hoveredElement},
 * {@code Dim2i tooltipArea}, {@code Font font}, {@code Vector2i reservedArea} (top-left of the Done / Apply / Undo buttons minus 3,
 * set every frame by VideoSettingsScreen via setReservedAreaTopLeftCorner); methods {@code render(GuiGraphicsExtractor)},
 * {@code mouseScrolled(double, double, double)}, constructor {@code ScrollableTooltip(TooltipParent)}.
 * Sodium's tooltip area runs down to the screen's bottom edge, under those buttons; the panel is therefore cut at
 * {@code reservedArea.y} (this was the cause of the panel covering "Done" and "Back"). Options without a catalog entry keep
 * Sodium's tooltip unchanged.
 */
@Mixin(value = ScrollableTooltip.class, remap = false)
public abstract class MixinScrollableTooltip {
    @Shadow
    private ControlElement hoveredElement;
    @Shadow
    private Dim2i tooltipArea;
    @Shadow
    @Final
    private Font font;
    @Shadow
    @Final
    private Vector2i reservedArea;

    @Shadow
    private Vector2i contentSize;
    @Shadow
    private Dim2i visibleDim;
    @Shadow
    private boolean overlayMode;

    /**
     * Overlay mode (narrow window or high GUI scale: panel area under 100 px, so PreviewPanel is not used) draws Sodium's tooltip over
     * the rows. Its height was only limited by the screen, so a long text covered almost everything. Cap it to 40% of the area (at
     * least 4 lines); the rest scrolls with Sodium's own scrollbar (positionTooltip returns true when scrolling is needed, and
     * onControlHover then re-runs it and adds the scrollbar with visibleDim.height / contentSize.y).
     */
    @Inject(method = "positionTooltip", at = @At("RETURN"), cancellable = true)
    private void vkb$capOverlayHeight(boolean needsScrolling, CallbackInfoReturnable<Boolean> cir) {
        if (!this.overlayMode || this.visibleDim == null || this.tooltipArea == null || this.hoveredElement == null) {
            return;
        }
        int cap = Math.max(4 * 11 + 8, this.tooltipArea.height() * 2 / 5);
        if (this.visibleDim.height() <= cap) {
            return;
        }
        int y = this.visibleDim.y();
        if (y < this.hoveredElement.getLimitY()) {
            // box was placed above the row, bottom edge anchored
            y = this.visibleDim.getLimitY() - cap;
        }
        this.visibleDim = new Dim2i(this.visibleDim.x(), y, this.visibleDim.width(), cap);
        cir.setReturnValue(this.contentSize.y() > cap);
    }

    /** Overlay background -369098752 (0xE8000000) let the rows show through the text; make it opaque. */
    @ModifyConstant(method = "render", constant = @Constant(intValue = -369098752))
    private int vkb$opaqueOverlay(int original) {
        return 0xFF0C0C10;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void vkb$newScreen(ScrollableTooltip.TooltipParent parent, CallbackInfo ci) {
        PreviewPanel.clearSelection();
    }

    private int vkb$limitY() {
        // reservedArea is (0,0) before the first frame; once set it is the top of the button block
        return this.reservedArea.y > this.tooltipArea.y() ? this.reservedArea.y : this.tooltipArea.getLimitY();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vkb$previewPanel(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (this.tooltipArea == null) {
            return;
        }
        Option option;
        if (this.hoveredElement != null) {
            option = this.hoveredElement.getOption();
            SettingsCatalog.Info e = PreviewPanel.lookup(((OptionIdAccessor) option).vkb$id(), option);
            if (e == null) {
                // Non-catalog row hovered: let Sodium draw its own tooltip, or if our modal is open keep our modal drawn
                if (PreviewPanel.isModalOpen()) {
                    Option sel = PreviewPanel.selected();
                    if (sel != null) {
                        SettingsCatalog.Info selInfo = PreviewPanel.lookup(((OptionIdAccessor) sel).vkb$id(), sel);
                        if (selInfo != null && PreviewPanel.render(graphics, this.font, this.tooltipArea, vkb$limitY(), sel, selInfo, PreviewPanel.mouseX(), PreviewPanel.mouseY())) {
                            ci.cancel();
                        }
                    }
                }
                return;
            }
            if (!PreviewPanel.isModalOpen()) {
                PreviewPanel.select(option);
            } else {
                option = PreviewPanel.selected() != null ? PreviewPanel.selected() : option;
                e = PreviewPanel.lookup(((OptionIdAccessor) option).vkb$id(), option);
            }
            if (e != null && PreviewPanel.render(graphics, this.font, this.tooltipArea, vkb$limitY(), option, e, PreviewPanel.mouseX(), PreviewPanel.mouseY())) {
                ci.cancel();
            }
            return;
        }
        option = PreviewPanel.selected();
        if (option != null) {
            SettingsCatalog.Info e = PreviewPanel.lookup(((OptionIdAccessor) option).vkb$id(), option);
            if (e != null) {
                if (PreviewPanel.render(graphics, this.font, this.tooltipArea, vkb$limitY(), option, e, PreviewPanel.mouseX(), PreviewPanel.mouseY())) {
                    ci.cancel();
                }
            }
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void vkb$scrollPanel(double x, double y, double amount, CallbackInfoReturnable<Boolean> cir) {
        if (PreviewPanel.scrolled(x, y, amount)) {
            cir.setReturnValue(true);
        }
    }
}
