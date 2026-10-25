package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.config.structure.IntegerOption;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The single Bedray settings interface. Only Apply commits its private option state. */
public final class BedrayConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 30;
    private final Screen parent;
    private SettingsDraft draft;
    private SettingsDraft.Category category = SettingsDraft.Category.OVERVIEW;
    private String query = "";
    private int scroll, maxScroll, contentX, contentWidth, listTop, listBottom, detailsX, detailsWidth;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private final List<SettingsDraft.Entry> rows = new ArrayList<>();
    private final List<Button> popupButtons = new ArrayList<>();
    private int popupX, popupY, popupWidth, popupHeight, popupScroll, popupFocus;
    private SettingsDraft.Entry selected;
    private Button applyButton, revertButton;
    private Component runtime = Component.empty();
    private int statusTicks;

    public BedrayConfigScreen(Screen parent) { super(tr("bedray.settings.title", "Bedray Settings")); this.parent = parent; }
    SettingsDraft draft() { return draft; }
    private static Component tr(String key, String fallback, Object... args) { return Component.translatableWithFallback(key, fallback, args); }

    @Override protected void init() {
        if (draft == null) draft = new SettingsDraft();
        PreviewPanel.clearSelection();
        clearWidgets(); rowWidgets.clear(); popupButtons.clear();
        boolean sidebar = width >= 440;
        detailsWidth = width >= 760 ? Math.min(320, width / 3) : 0;
        detailsX = width - detailsWidth - 8;
        contentX = sidebar ? 132 : 8;
        contentWidth = Math.max(120, width - contentX - 8 - (detailsWidth > 0 ? detailsWidth + 8 : 0));
        listTop = sidebar ? 54 : 78;
        listBottom = height - 50;
        EditBox search = new EditBox(font, 8, 27, width - 16, 20, tr("bedray.settings.search", "Search settings"));
        search.setHint(tr("bedray.settings.search", "Search settings")); search.setMaxLength(80); search.setValue(query);
        search.setResponder(text -> { query = text; scroll = 0; rebuildRows(); }); addRenderableWidget(search);
        if (sidebar) {
            int index = 0;
            for (SettingsDraft.Category value : SettingsDraft.Category.values()) addRenderableWidget(Button.builder(value.title(), b -> selectCategory(value))
                .bounds(8, listTop + index++ * 23, 116, 20).build());
        } else addRenderableWidget(Button.builder(category.title(), b -> {
            var values = SettingsDraft.Category.values();
            openChoices(java.util.Arrays.stream(values).map(SettingsDraft.Category::title).toList(), i -> selectCategory(values[i]), 8, 52, width - 16);
        }).bounds(8, 52, width - 16, 20).build());
        int gap = 4, buttonWidth = (width - 16 - 3 * gap) / 4, footerY = height - 26;
        addRenderableWidget(Button.builder(tr("bedray.settings.details", "Details"), b -> { if (selected != null) PreviewPanel.openModal(selected.option()); })
            .bounds(8, footerY, buttonWidth, 20).build());
        applyButton = addRenderableWidget(Button.builder(tr("bedray.settings.apply", "Apply"), b -> apply())
            .bounds(8 + buttonWidth + gap, footerY, buttonWidth, 20).build());
        revertButton = addRenderableWidget(Button.builder(tr("bedray.settings.revert", "Revert"), b -> { draft.revert(); rebuildRows(); })
            .bounds(8 + 2 * (buttonWidth + gap), footerY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(tr("bedray.settings.cancel", "Close / Cancel"), b -> onClose())
            .bounds(8 + 3 * (buttonWidth + gap), footerY, buttonWidth, 20).build());
        rebuildRows(); updateRuntime();
    }

    private void selectCategory(SettingsDraft.Category value) {
        category = value; scroll = 0;
        if (width < 440) rebuildWidgets(); else rebuildRows();
    }

    void rebuildRows() {
        closeChoices();
        for (AbstractWidget widget : rowWidgets) removeWidget(widget);
        rowWidgets.clear(); rows.clear();
        String filter = query.trim().toLowerCase(Locale.ROOT);
        for (SettingsDraft.Entry entry : draft.entries) {
            if (!draft.visible(entry)) continue;
            if (filter.isEmpty() ? entry.category() != category : !entry.searchText().contains(filter)) continue;
            rows.add(entry);
        }
        if (!rows.contains(selected)) selected = rows.isEmpty() ? null : rows.getFirst();
        int controls = Math.max(70, Math.min(160, contentWidth / 2));
        for (SettingsDraft.Entry entry : rows) {
            AbstractWidget widget = createControl(entry, contentX + contentWidth - controls - 6, controls);
            widget.active = entry.option().isEnabled() && draft.editable(entry);
            widget.setTooltip(Tooltip.create(widget.active ? entry.option().getTooltip()
                : tr("bedray.settings.locked", "Choose Custom quality to edit details. Some controls also require another feature or runtime.")));
            rowWidgets.add(addRenderableWidget(widget));
        }
        if (filter.isEmpty() && category == SettingsDraft.Category.PRESETS) {
            addAction(tr("bedray.settings.presets_manage", "Manage presets..."), () -> minecraft.gui.setScreen(new PresetsScreen(this, draft)));
            addAction(tr("bedray.settings.presets_copy", "Copy pending settings"), () -> minecraft.keyboardHandler.setClipboard(draft.snapshot()));
            addAction(tr("bedray.settings.presets_import", "Import into pending settings"), () -> {
                int count = draft.importText(minecraft.keyboardHandler.getClipboard());
                runtime = tr("bedray.settings.preset_staged", "%s preset values staged. Apply to commit.", count); rebuildRows();
            });
        }
        if (filter.isEmpty() && category == SettingsDraft.Category.SYSTEM) {
            addAction(tr("bedray.settings.libraries", "Runtime libraries..."), () -> minecraft.gui.setScreen(new NativesManagerScreen(this)));
            addAction(tr("bedray.settings.report", "Copy diagnostics report"), () -> io.github.andrexarlay.vulkancompat.rtx.debug.Diagnostics.generateAndNotify());
            addAction(tr("bedray.settings.reset", "Reset editable settings (pending)"), () -> { draft.reset(); rebuildRows(); });
        }
        maxScroll = Math.max(0, rowWidgets.size() * ROW_HEIGHT - Math.max(0, listBottom - listTop));
        scroll = Math.min(scroll, maxScroll); positionRows();
        PreviewPanel.clearSelection();
        if (selected != null) { PreviewPanel.select(selected.option()); PreviewPanel.setRuntimeStatus(draft.status(selected).text()); }
        updateButtons();
    }

    private void addAction(Component name, Runnable action) {
        rowWidgets.add(addRenderableWidget(Button.builder(name, b -> action.run()).bounds(contentX, 0, contentWidth - 6, 20).build()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWidget createControl(SettingsDraft.Entry entry, int x, int controlWidth) {
        StatefulOption option = entry.option();
        if (option instanceof IntegerOption integer) return new AbstractSliderButton(x, 0, controlWidth, 20, integer.formatValue(integer.getValidatedValue()), normalized(integer)) {
            @Override protected void updateMessage() { setMessage(integer.formatValue(integer.getValidatedValue())); }
            @Override protected void applyValue() {
                var validator = integer.getSteppedValidator();
                int stepped = validator.min() + (int)Math.round(value * (validator.max() - validator.min()) / validator.step()) * validator.step();
                integer.modifyValue(Math.max(validator.min(), Math.min(validator.max(), stepped)));
                select(entry); updateMessage(); updateButtons();
            }
        };
        return Button.builder(valueText(option), b -> {
            select(entry);
            if (option.getValidatedValue() instanceof Boolean on) { option.modifyValue(!on); rebuildRows(); }
            else if (option instanceof EnumOption enumeration) {
                List<Enum> values = new ArrayList<>((java.util.Set<Enum>)enumeration.getAllowedValues().get(draft.config));
                openChoices(values.stream().map(enumeration::getElementName).toList(), index -> {
                    draft.choose(entry, values.get(index)); rebuildRows();
                }, b.getX(), b.getY(), Math.max(controlWidth, 150));
            }
        }).bounds(x, 0, controlWidth, 20).build();
    }

    private static double normalized(IntegerOption option) {
        var v = option.getSteppedValidator(); return (option.getValidatedValue() - v.min()) / (double)Math.max(1, v.max() - v.min());
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Component valueText(StatefulOption<?> option) {
        Object value = option.getValidatedValue();
        if (value instanceof Boolean on) return tr(on ? "bedray.onoff.on" : "bedray.onoff.off", on ? "On" : "Off");
        if (option instanceof IntegerOption number) return number.formatValue((Integer)value);
        if (option instanceof EnumOption enumeration) return enumeration.getElementName((Enum)value);
        return Component.literal(String.valueOf(value));
    }
    private void select(SettingsDraft.Entry entry) {
        if (selected != entry) { selected = entry; PreviewPanel.setRuntimeStatus(draft.status(entry).text()); }
        PreviewPanel.select(entry.option());
    }
    private void positionRows() {
        for (int i = 0; i < rowWidgets.size(); i++) {
            AbstractWidget w = rowWidgets.get(i); int y = listTop + i * ROW_HEIGHT - scroll;
            w.setY(y); w.visible = y >= listTop && y + 20 <= listBottom;
        }
    }
    private void updateButtons() { if (applyButton != null) applyButton.active = draft.changed(); if (revertButton != null) revertButton.active = draft.changed(); }
    private void apply() { draft.apply(); draft = new SettingsDraft(); rebuildRows(); updateRuntime(); }
    @Override public void tick() {
        super.tick();
        for (int i = 0; i < rows.size(); i++) if (rowWidgets.get(i).isFocused()) select(rows.get(i));
        if (++statusTicks >= 20) { statusTicks = 0; updateRuntime(); }
    }
    private void updateRuntime() {
        if (selected != null) PreviewPanel.setRuntimeStatus(draft.status(selected).text());
        if (VulkanRtxConfig.voxelRendererSelected() && GraphicsBackendRegistry.backend() != null) {
            var status = GraphicsBackendRegistry.backend().streamingStatus();
            runtime = status.available() ? tr("bedray.settings.coverage", "Runtime: %s/%s chunks; %s resident, %s queued; GPU %s/%s MiB%s",
                status.effectiveRenderDistanceChunks(), status.requestedRenderDistanceChunks(), status.residentSections(), status.queuedSections(),
                status.usedGpuBytes() / (1024 * 1024), status.budgetGpuBytes() / (1024 * 1024),
                status.memoryLimited() ? tr("bedray.settings.memory_limited", " (memory limited)").getString() : "")
                : Component.literal(GraphicsBackendRegistry.status());
        } else runtime = Component.literal(VulkanSodiumConfig.rendererStatus());
    }

    private void openChoices(List<Component> labels, java.util.function.IntConsumer choose, int anchorX, int anchorY, int requestedWidth) {
        closeChoices(); PreviewPanel.closeModal();
        popupWidth = Math.min(width - 16, Math.max(requestedWidth, labels.stream().mapToInt(font::width).max().orElse(100) + 16));
        popupHeight = Math.min(height - 40, labels.size() * 22 + 8);
        popupX = Math.max(8, Math.min(width - popupWidth - 8, anchorX));
        popupY = Math.max(8, Math.min(height - popupHeight - 30, anchorY + 21));
        popupScroll = 0; popupFocus = 0;
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            popupButtons.add(addWidget(Button.builder(labels.get(i), b -> { closeChoices(); choose.accept(index); })
                .bounds(popupX + 4, 0, popupWidth - 8, 20).build()));
        }
        positionChoices(); if (!popupButtons.isEmpty()) setFocused(popupButtons.getFirst());
    }
    private void positionChoices() {
        popupScroll = Math.max(0, Math.min(popupScroll, Math.max(0, popupButtons.size() * 22 - popupHeight + 8)));
        for (int i = 0; i < popupButtons.size(); i++) {
            Button b = popupButtons.get(i); b.setY(popupY + 4 + i * 22 - popupScroll);
            b.visible = b.getY() >= popupY + 4 && b.getY() + 20 <= popupY + popupHeight - 4;
        }
    }
    private void closeChoices() { for (Button b : popupButtons) removeWidget(b); popupButtons.clear(); }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (!popupButtons.isEmpty()) { popupScroll -= (int)(sy * 22); positionChoices(); return true; }
        if (PreviewPanel.scrolled(x, y, sy)) return true;
        if (x >= contentX && x < contentX + contentWidth && y >= listTop && y < listBottom && maxScroll > 0) {
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int)(sy * ROW_HEIGHT))); positionRows(); return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (!popupButtons.isEmpty()) {
            for (Button b : List.copyOf(popupButtons)) if (b.visible && b.mouseClicked(event, twice)) return true;
            closeChoices(); return true;
        }
        if (event.button() == 0 && PreviewPanel.click(event.x(), event.y())) { rebuildControlMessages(); return true; }
        if (event.x() >= contentX && event.x() < contentX + contentWidth && event.y() >= listTop && event.y() < listBottom) {
            int index = ((int)event.y() - listTop + scroll) / ROW_HEIGHT;
            if (index >= 0 && index < rows.size()) select(rows.get(index));
        }
        return super.mouseClicked(event, twice);
    }
    private void rebuildControlMessages() {
        for (int i = 0; i < rows.size(); i++) rowWidgets.get(i).setMessage(valueText(rows.get(i).option())); updateButtons();
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (!popupButtons.isEmpty()) {
            if (event.key() == 256) { closeChoices(); return true; }
            if (event.key() == 264 || event.key() == 265 || event.key() == 258) {
                popupFocus = Math.floorMod(popupFocus + (event.key() == 265 ? -1 : 1), popupButtons.size());
                popupScroll = Math.max(0, popupFocus * 22 - popupHeight + 32); positionChoices(); setFocused(popupButtons.get(popupFocus)); return true;
            }
        }
        if (event.key() == 256 && PreviewPanel.isModalOpen()) { PreviewPanel.closeModal(); return true; }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.text(font, title, 8, 9, 0xFFFFFFFF);
        int labelWidth = Math.max(30, contentWidth - Math.max(70, Math.min(160, contentWidth / 2)) - 14);
        for (int i = 0; i < rows.size(); i++) {
            if (!rowWidgets.get(i).visible) continue;
            SettingsDraft.Entry row = rows.get(i); int y = rowWidgets.get(i).getY();
            g.text(font, font.plainSubstrByWidth(row.option().getName().getString(), labelWidth), contentX + 2, y + 1,
                rowWidgets.get(i).active ? 0xFFFFFFFF : 0xFF909090);
            String subtitle = query.isBlank() ? row.option().hasChanged() ? tr("bedray.settings.pending", "Pending").getString() : ""
                : row.category().title().getString();
            g.text(font, font.plainSubstrByWidth(subtitle, labelWidth), contentX + 2, y + 12, 0xFF8ABCD8);
        }
        if (rows.isEmpty() && category != SettingsDraft.Category.PRESETS && category != SettingsDraft.Category.SYSTEM) {
            Component empty = draft.voxel() && query.isBlank() ? tr("bedray.settings.voxel_unrelated", "These settings belong to the hardware renderer. Choose another category.")
                : tr("bedray.settings.empty", "No matching settings.");
            int y = listTop; for (var line : font.split(empty, contentWidth - 8)) { g.text(font, line, contentX + 2, y, 0xFFB0B0B0); y += 11; }
        }
        if (maxScroll > 0) {
            int track = Math.max(1, listBottom - listTop), thumb = Math.max(8, track * track / (maxScroll + track));
            int y = listTop + (track - thumb) * scroll / maxScroll;
            g.fill(contentX + contentWidth - 3, y, contentX + contentWidth - 1, y + thumb, 0xFF79A6BE);
        }
        g.text(font, font.plainSubstrByWidth(runtime.getString(), width - 16), 8, height - 40, 0xFFB6D4E8);
        if (selected != null && (detailsWidth > 0 || PreviewPanel.isModalOpen())) {
            PreviewPanel.setResetAllowed(draft.editable(selected));
            PreviewPanel.render(g, font, new Dim2i(detailsX, listTop, detailsWidth, listBottom - listTop), listBottom,
                selected.option(), selected.info(), mx, my);
        }
        if (!popupButtons.isEmpty()) {
            g.nextStratum(); g.fill(0, 0, width, height, 0x70000000);
            g.fill(popupX, popupY, popupX + popupWidth, popupY + popupHeight, 0xFF141C24);
            g.outline(popupX, popupY, popupWidth, popupHeight, 0xFF79A6BE);
            for (Button b : popupButtons) b.extractRenderState(g, mx, my, delta);
        }
    }
    @Override public void onClose() { PreviewPanel.clearSelection(); minecraft.gui.setScreen(parent); }
}
