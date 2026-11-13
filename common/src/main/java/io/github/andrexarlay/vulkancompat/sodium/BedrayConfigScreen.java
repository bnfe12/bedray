package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
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

import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;
import static com.mojang.blaze3d.platform.InputConstants.KEY_F;

/** The single Bedray settings interface. Only Apply commits its private option state. */
public final class BedrayConfigScreen extends Screen {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VKB-SETTINGS");
    private static final int ROW_HEIGHT = SettingsLayout.ROW_HEIGHT;
    private final Screen parent;
    private SettingsDraft draft;
    private SettingsDraft.Category category = SettingsDraft.Category.OVERVIEW;
    private String query = "";
    private EditBox search;
    private int scroll, maxScroll, contentX, contentWidth, listTop, listBottom, detailsX, detailsWidth;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private final List<SettingsDraft.Entry> rows = new ArrayList<>();
    private final List<Button> popupButtons = new ArrayList<>();
    private int popupX, popupY, popupWidth, popupHeight, popupScroll, popupFocus;
    private SettingsDraft.Entry selected;
    private Button applyButton, revertButton, captureButton;
    private Component runtime = Component.empty();
    private int statusTicks;
    private SettingsLayout layout;
    private final java.util.Map<SettingsDraft.Category, Button> navigation = new java.util.EnumMap<>(SettingsDraft.Category.class);

    public BedrayConfigScreen(Screen parent) {
        super(tr("bedray.settings.title", "Bedray Settings"));
        this.parent = parent;
        // Construct before Gui.setScreen replaces the parent, so a model initialization failure
        // cannot leave Minecraft rendering a partially initialized screen.
        this.draft = new SettingsDraft();
    }
    SettingsDraft draft() { return draft; }
    private static Component tr(String key, String fallback, Object... args) { return Component.translatableWithFallback(key, fallback, args); }

    @Override protected void init() {
        if (draft == null) draft = new SettingsDraft();
        PreviewPanel.clearSelection();
        clearWidgets(); rowWidgets.clear(); rows.clear(); popupButtons.clear();
        layout = SettingsLayout.measure(width, height);
        detailsWidth = layout.detailsWidth();
        detailsX = layout.detailsX();
        contentX = layout.contentX();
        contentWidth = layout.contentWidth();
        listTop = layout.listTop();
        listBottom = layout.listBottom();
        search = new EditBox(font, 12, SettingsLayout.SEARCH_Y, width - 24, 20, tr("bedray.settings.search", "Search settings"));
        search.setHint(tr("bedray.settings.search", "Search settings")); search.setMaxLength(80); search.setValue(query);
        search.setResponder(text -> { query = text; scroll = 0; rebuildRows(); }); addRenderableWidget(search);
        navigation.clear();
        if (layout.sidebar()) {
            int index = 0;
            for (SettingsDraft.Category value : SettingsDraft.Category.values()) {
                Button button = addRenderableWidget(Button.builder(value.title(), b -> selectCategory(value))
                    .bounds(16, 78 + index++ * 27, 128, 22).build());
                navigation.put(value, button);
            }
        } else addRenderableWidget(Button.builder(category.title(), b -> {
            var values = SettingsDraft.Category.values();
            openChoices(java.util.Arrays.stream(values).map(SettingsDraft.Category::title).toList(), i -> selectCategory(values[i]), 12, SettingsLayout.CATEGORY_Y, width - 24);
        }).bounds(12, SettingsLayout.CATEGORY_Y, width - 24, 20).build());
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
        category = value;
        scroll = 0;
        if (!layout.sidebar()) {
            query = "";
            rebuildWidgets();
        } else if (!query.isEmpty()) {
            search.setValue("");
        } else {
            rebuildRows();
        }
    }

    void rebuildRows() {
        captureButton = null;
        // Evaluate dependent option state and construct all controls before publishing either list.
        // A failed predicate must not leave entries without matching widgets for tick/render.
        List<SettingsDraft.Entry> nextRows = new ArrayList<>();
        List<AbstractWidget> nextWidgets = new ArrayList<>();
        boolean searching = !query.isBlank();
        List<String> terms = SettingsSearch.terms(query);
        // One broken option predicate (e.g. Sodium's "not a declared dependency") used to throw out of the first row it
        // reached and leave the whole screen empty with no option name in the log. Every row is evaluated, every failure
        // is logged with its option, and the screen still fails loudly afterwards: the error is reported, never hidden.
        List<String> broken = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (SettingsDraft.Entry entry : draft.entries) {
            boolean visible;
            try {
                visible = draft.visible(entry);
            } catch (RuntimeException failure) {
                LOGGER.error("[VKB-SETTINGS] option={} category={} failed to evaluate its state: {}", entry.path(),
                    entry.category(), failure.toString());
                broken.add(entry.path());
                if (firstFailure == null) firstFailure = failure;
                continue;
            }
            if (!visible) continue;
            if (searching ? !SettingsSearch.matches(entry.searchText(), terms) : entry.category() != category) continue;
            nextRows.add(entry);
        }
        if (firstFailure != null) {
            throw new IllegalStateException(broken.size() + " Bedray setting(s) cannot evaluate their state: " + broken,
                firstFailure);
        }
        int controls = Math.max(70, Math.min(160, contentWidth / 2));
        for (SettingsDraft.Entry entry : nextRows) {
            AbstractWidget widget = createControl(entry, contentX + contentWidth - controls - 6, controls);
            boolean enabled = entry.option().isEnabled() && draft.editable(entry);
            widget.active = enabled;
            // Say which of the two reasons locked the row: the profile that sets this value, or a missing prerequisite.
            widget.setTooltip(Tooltip.create(enabled ? entry.option().getTooltip()
                : draft.hasQualityProfile() && draft.profileOwned(entry.path())
                    ? tr("bedray.settings.locked_by_profile", "Set by the Fast/Beautiful quality profile. Switch the profile to Custom to tune it by hand.")
                    : tr("bedray.settings.locked", "Needs another option, feature or runtime library to be active first.")));
            nextWidgets.add(widget);
        }
        closeChoices();
        for (AbstractWidget widget : rowWidgets) removeWidget(widget);
        rowWidgets.clear(); rows.clear();
        rows.addAll(nextRows);
        for (AbstractWidget widget : nextWidgets) rowWidgets.add(addRenderableWidget(widget));
        if (!rows.contains(selected)) selected = rows.isEmpty() ? null : rows.getFirst();
        if (!searching && category == SettingsDraft.Category.PRESETS) {
            addAction(tr("bedray.settings.experience_library", "Rendering experiences..."),
                () -> openExperiences(contentX, listTop, contentWidth - 6));
            addAction(tr("bedray.settings.presets_manage", "Manage presets..."), () -> minecraft.gui.setScreen(new PresetsScreen(this, draft)));
            addAction(tr("bedray.settings.presets_copy", "Copy pending settings"), () -> minecraft.keyboardHandler.setClipboard(draft.snapshot()));
            addAction(tr("bedray.settings.presets_import", "Import into pending settings"), () -> {
                int count = draft.importText(minecraft.keyboardHandler.getClipboard());
                runtime = tr("bedray.settings.preset_staged", "%s preset values staged. Apply to commit.", count); rebuildRows();
            });
        }
        if (!searching && category == SettingsDraft.Category.OVERVIEW && draft.hasQualityProfile())
            addAction(tr("bedray.settings.customize", "Customize current quality"), () -> { draft.customize(); rebuildRows(); });
        if (!searching && category == SettingsDraft.Category.OVERVIEW && draft.hasQualityProfile() && !draft.voxel())
            addAction(tr("bedray.settings.gi_auto", "Auto-select GI quality (recommended)"), () -> {
                var device = io.github.andrexarlay.vulkancompat.rtx.raytracing.GiDeviceProbe.probe();
                if (device == null) {
                    runtime = tr("bedray.settings.gi_auto_unavailable", "Auto-select needs the Vulkan device; open a world first.");
                    return;
                }
                var plan = io.github.andrexarlay.vulkancompat.rtx.config.GiStackPolicy.recommend(
                    device, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
                draft.applyGiPlan(plan);
                runtime = tr("bedray.settings.gi_auto_staged", "Auto: %s. Apply to commit.",
                    io.github.andrexarlay.vulkancompat.rtx.config.GiStackPolicy.describe(plan));
                rebuildRows();
            });
        if (!searching && category == SettingsDraft.Category.OVERVIEW)
            addAction(tr("bedray.settings.texture_style", "Texture style..."), () -> {
                var presets = io.github.andrexarlay.vulkancompat.rtx.config.TexturePreset.values();
                openChoices(java.util.Arrays.stream(presets).map(p -> switch (p) {
                    case PIXEL_ART -> tr("bedray.settings.texture.pixel_art", "Pixel-Sharp");
                    case BALANCED -> tr("bedray.settings.texture.balanced", "Balanced");
                    case SOFT -> tr("bedray.settings.texture.soft", "Soft");
                }).toList(), index -> {
                    draft.applyTexturePreset(presets[index]);
                    runtime = tr("bedray.settings.texture_staged", "Texture style staged. Apply to commit.");
                    rebuildRows();
                }, contentX, listTop, contentWidth - 6);
            });
        if (!searching && category == SettingsDraft.Category.SYSTEM) {
            addAction(tr("bedray.settings.addons", "Add-on settings…"), () -> minecraft.gui.setScreen(new AddonSettingsScreen(this)));
            captureButton = addAction(tr("bedray.settings.capture_profile", "Record 30 s gameplay profile"),
                io.github.andrexarlay.vulkancompat.rtx.debug.GameplayCapture::start);
            captureButton.setTooltip(Tooltip.create(tr("bedray.settings.capture_profile_hint",
                "Apply or revert changes first. Requires a world. Returns to play, waits 5 seconds and records 30 seconds at your current settings. Saves FPS statistics, raw CPU/GPU timings and a tester ZIP. No settings are changed.")));
            addAction(tr("bedray.settings.libraries", "Runtime libraries..."), () -> minecraft.gui.setScreen(new NativesManagerScreen(this)));
            addAction(tr("bedray.settings.bedrock_open", "Bedrock RTX packs: open folder"), () -> {
                try {
                    com.mojang.blaze3d.Blaze3D.openPath(
                        io.github.andrexarlay.vulkancompat.rt.material.bedrock.BedrockRtxPackImporter.sourceDir(minecraft));
                } catch (java.io.IOException e) {
                    LOGGER.warn("[BEDROCK-RTX] cannot open the pack folder: {}", e.toString());
                }
            });
            addAction(tr("bedray.settings.bedrock_import", "Bedrock RTX packs: import"), () ->
                io.github.andrexarlay.vulkancompat.rt.material.bedrock.BedrockRtxPackImporter.importAllAsync());
            addAction(tr("bedray.settings.report", "Copy diagnostics report"), () -> io.github.andrexarlay.vulkancompat.rtx.debug.Diagnostics.generateAndNotify());
            addAction(tr("bedray.settings.share_test", "Prepare GitHub test report"), io.github.andrexarlay.vulkancompat.rtx.debug.TestReportSharing::share);
            addAction(tr("bedray.settings.reset", "Reset editable settings (pending)"), () -> { draft.reset(); rebuildRows(); });
        }
        maxScroll = Math.max(0, rowWidgets.size() * ROW_HEIGHT - Math.max(0, listBottom - listTop));
        scroll = Math.min(scroll, maxScroll); positionRows();
        PreviewPanel.clearSelection();
        if (selected != null) { PreviewPanel.select(selected.option()); PreviewPanel.setRuntimeStatus(draft.status(selected).text()); }
        updateButtons();
        navigation.forEach((value, button) -> button.setMessage(value.title().copy()
            .withColor(value == category && query.isBlank() ? SettingsChrome.ACCENT : SettingsChrome.TEXT)));
    }

    private Button addAction(Component name, Runnable action) {
        Button button = addRenderableWidget(Button.builder(name, b -> action.run()).bounds(contentX, 0, contentWidth - 6, 20).build());
        rowWidgets.add(button);
        return button;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWidget createControl(SettingsDraft.Entry entry, int x, int controlWidth) {
        StatefulOption option = entry.option();
        if (entry.path().equals("renderer")) return Button.builder(draft.experienceTitle(), b -> {
            select(entry); openExperiences(b.getX(), b.getY(), controlWidth);
        }).bounds(x, 0, controlWidth, 20).build();
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
    private void updateButtons() {
        boolean changed = draft.changed();
        if (applyButton != null) applyButton.active = changed;
        if (revertButton != null) revertButton.active = changed;
        if (captureButton != null) captureButton.active = !changed && io.github.andrexarlay.vulkancompat.rtx.debug.GameplayCapture.canStart();
    }
    private void apply() { draft.apply(); draft = new SettingsDraft(); rebuildRows(); updateRuntime(); }
    @Override public void tick() {
        super.tick();
        for (int i = 0; i < rows.size(); i++) if (rowWidgets.get(i).isFocused()) select(rows.get(i));
        if (++statusTicks >= 20) { statusTicks = 0; updateRuntime(); updateButtons(); }
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
        } else {
            var trace = RtFrameRenderer.lastTraceTelemetry();
            var mode = RtFrameRenderer.lastPassMode();
            runtime = trace == null || mode == null || !RtFrameRenderer.worldReady()
                ? Component.literal(VulkanSodiumConfig.rendererStatus())
                : tr("bedray.settings.trace_runtime", "Last trace · %s · %s → %s · %s spp · %s bounces",
                    mode.logName(), trace.traceExtent(), trace.outputExtent(), trace.spp(), trace.bounces());
        }
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
    private void openExperiences(int x, int y, int popupWidth) {
        var experiences = SettingsDraft.Experience.values();
        openChoices(java.util.Arrays.stream(experiences).map(SettingsDraft.Experience::title).toList(), index -> {
            draft.selectExperience(experiences[index]); category = SettingsDraft.Category.OVERVIEW; query = ""; scroll = 0; rebuildWidgets();
        }, x, y, popupWidth);
        for (int i = 0; i < experiences.length; i++) {
            popupButtons.get(i).active = experiences[i].available();
            popupButtons.get(i).setTooltip(Tooltip.create(experiences[i].description().copy().append("\n")
                .append(Component.translatable("bedray.settings.experience.combinations"))));
        }
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
        if (event.button() == 0 && PreviewPanel.click(event.x(), event.y(), this::resetSelected)) return true;
        if (event.x() >= contentX && event.x() < contentX + contentWidth && event.y() >= listTop && event.y() < listBottom) {
            int index = ((int)event.y() - listTop + scroll) / ROW_HEIGHT;
            if (index >= 0 && index < rows.size()) select(rows.get(index));
        }
        return super.mouseClicked(event, twice);
    }
    private void resetSelected() {
        if (selected == null) return;
        var entry = selected;
        boolean modal = PreviewPanel.isModalOpen();
        int previousIndex = rows.indexOf(entry);
        boolean restoreFocus = previousIndex >= 0 && rowWidgets.get(previousIndex).isFocused();
        draft.reset(entry);
        rebuildRows();
        int index = rows.indexOf(entry);
        if (modal && selected == entry) PreviewPanel.openModal(entry.option());
        if (restoreFocus && index >= 0 && rowWidgets.get(index).visible) setFocused(rowWidgets.get(index));
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (!popupButtons.isEmpty()) {
            if (event.key() == KEY_ESCAPE) { closeChoices(); return true; }
            if (event.key() == 264 || event.key() == 265 || event.key() == 258) {
                popupFocus = Math.floorMod(popupFocus + (event.key() == 265 ? -1 : 1), popupButtons.size());
                popupScroll = Math.max(0, popupFocus * 22 - popupHeight + 32); positionChoices(); setFocused(popupButtons.get(popupFocus)); return true;
            }
        }
        if (PreviewPanel.isModalOpen()) {
            if (event.key() == KEY_ESCAPE) PreviewPanel.closeModal();
            return true;
        }
        if (event.key() == KEY_F && event.hasControlDown()) {
            setFocused(search);
            return true;
        }
        if (event.key() == KEY_ESCAPE && !query.isEmpty()) {
            search.setValue("");
            setFocused(search);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        for (int i = 0; i < rows.size(); i++) {
            if (rowWidgets.get(i).visible) SettingsChrome.row(g, contentX, rowWidgets.get(i).getY(), contentWidth,
                selected == rows.get(i), rows.get(i).option().hasChanged());
        }
        super.extractRenderState(g, mx, my, delta);
        g.text(font, title, 23, 15, SettingsChrome.TEXT);
        Component commitHint = tr(draft.changed() ? "bedray.settings.staged_hint" : "bedray.settings.saved_hint",
            draft.changed() ? "Changes pending · Apply to save" : "Saved settings");
        if (font.width(title) + font.width(commitHint) + 52 <= width) {
            g.text(font, commitHint, width - font.width(commitHint) - 12, 15,
                draft.changed() ? SettingsChrome.PENDING : SettingsChrome.MUTED);
        }
        Component section = query.isBlank() ? category.title() : tr("bedray.settings.results", "Search results");
        g.text(font, section, contentX + 2, listTop - 16, SettingsChrome.ACCENT);
        String count = Integer.toString(rows.size());
        g.text(font, count, contentX + contentWidth - font.width(count) - 8, listTop - 16, SettingsChrome.MUTED);
        int labelWidth = Math.max(30, contentWidth - Math.max(70, Math.min(160, contentWidth / 2)) - 14);
        for (int i = 0; i < rows.size(); i++) {
            if (!rowWidgets.get(i).visible) continue;
            SettingsDraft.Entry row = rows.get(i); int y = rowWidgets.get(i).getY();
            g.text(font, font.plainSubstrByWidth(row.option().getName().getString(), labelWidth), contentX + 2, y + 1,
                rowWidgets.get(i).active ? 0xFFFFFFFF : 0xFF909090);
            String subtitle = query.isBlank() ? row.option().hasChanged() ? tr("bedray.settings.pending", "Pending").getString()
                    : Component.translatableWithFallback("bedray.opt." + row.path() + ".desc", row.info().desc()).getString()
                : row.category().title().getString();
            g.text(font, font.plainSubstrByWidth(subtitle, labelWidth), contentX + 5, y + 12,
                row.option().hasChanged() ? SettingsChrome.PENDING : SettingsChrome.MUTED);
        }
        if (rowWidgets.isEmpty()) {
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
            PreviewPanel.setResetAllowed(selected.option().isEnabled() && draft.editable(selected));
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
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractBackground(g, mx, my, delta);
        if (layout != null) SettingsChrome.background(g, layout, width, height);
    }
    @Override public void onClose() { PreviewPanel.clearSelection(); minecraft.gui.setScreen(parent); }
}
