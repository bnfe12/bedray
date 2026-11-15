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
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;
import static com.mojang.blaze3d.platform.InputConstants.KEY_F;

/** The single Bedray settings interface. Only Apply commits its private option state. */
public final class BedrayConfigScreen extends Screen {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("VKB-SETTINGS");
    private static final int ROW_HEIGHT = SettingsLayout.ROW_HEIGHT;
    private static final int CONTROL_HEIGHT = SettingsLayout.CONTROL_HEIGHT;
    private final Screen parent;
    private SettingsDraft draft;
    private SettingsDraft.Category category = SettingsDraft.Category.OVERVIEW;
    private String query = "";
    private EditBox search;
    private boolean reviewing;
    private int scroll, maxScroll, contentX, contentWidth, listTop, listBottom;
    private final List<AbstractWidget> rowWidgets = new ArrayList<>();
    private final List<SettingsDraft.Entry> rows = new ArrayList<>();
    private final List<Button> popupButtons = new ArrayList<>();
    private int popupX, popupY, popupWidth, popupHeight, popupScroll, popupFocus;
    private SettingsDraft.Entry selected;
    private long tabSelectedAt;
    private long rowSelectedAt;
    private Button applyButton, revertButton, captureButton, detailsButton, closeButton, reviewButton;
    private Component runtime = Component.empty();
    private Component notice = Component.empty();
    private int statusTicks;
    private SettingsLayout layout;
    private final java.util.Map<SettingsDraft.Category, Button> navigation = new java.util.EnumMap<>(SettingsDraft.Category.class);

    public static void open(Screen parent) {
        parent.getMinecraft().gui.setScreen(new BedrayConfigScreen(parent));
    }

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
        contentX = layout.contentX();
        contentWidth = layout.contentWidth();
        listTop = layout.listTop();
        listBottom = layout.listBottom();
        int reviewWidth = layout.reviewWidth(font.width(tr("bedray.settings.review_count", "Review (%s)", 999)));
        search = new EditBox(font, contentX, SettingsLayout.SEARCH_Y, layout.searchWidth(reviewWidth), SettingsLayout.SEARCH_HEIGHT,
            tr("bedray.settings.search", "Search settings"));
        search.setMaxLength(80);
        search.setValue(query);
        search.setResponder(text -> {
            query = text;
            scroll = 0;
            rebuildRows();
        });
        addRenderableWidget(search);
        reviewButton = addRenderableWidget(new PlainTextButton(contentX + contentWidth - reviewWidth,
            SettingsLayout.SEARCH_Y, reviewWidth, SettingsLayout.SEARCH_HEIGHT, tr("bedray.settings.review", "Review"),
            b -> toggleReview(), font));
        reviewButton.setTooltip(Tooltip.create(tr("bedray.settings.review_hint", "Show staged changes across all settings.")));
        navigation.clear();
        for (SettingsDraft.Category value : SettingsDraft.Category.values()) {
            int index = value.ordinal();
            int tabWidth = Math.max(1, layout.navWidth(index) - 9);
            Button tab = addRenderableWidget(new PlainTextButton(layout.navX(index) + 6, layout.navY(index),
                tabWidth, SettingsLayout.NAV_HEIGHT, fit(value.title(), tabWidth), b -> selectCategory(value), font));
            tab.setTooltip(Tooltip.create(value.title()));
            navigation.put(value, tab);
        }

        int footerY = layout.footerY();
        int footerHeight = SettingsLayout.FOOTER_HEIGHT;
        Component details = tr("bedray.settings.details", "Details");
        detailsButton = addRenderableWidget(new PlainTextButton(contentX, footerY, font.width(details) + 6, footerHeight,
            details, b -> { if (selected != null) PreviewPanel.openModal(selected.option()); }, font));

        Component done = Component.translatable("gui.done");
        int doneWidth = Math.max(56, font.width(done) + 12);
        int doneX = contentX + contentWidth - doneWidth;
        closeButton = addRenderableWidget(Button.builder(done, b -> onClose())
            .bounds(doneX, footerY, doneWidth, footerHeight).build());

        Component apply = tr("bedray.settings.apply", "Apply");
        int applyWidth = font.width(apply) + 12;
        int applyX = doneX - applyWidth - 4;
        applyButton = addRenderableWidget(Button.builder(apply, b -> apply()).bounds(applyX, footerY, applyWidth, footerHeight).build());

        Component revert = tr("bedray.settings.revert", "Revert");
        int revertWidth = font.width(revert) + 6;
        revertButton = addRenderableWidget(new PlainTextButton(applyX - revertWidth - 6, footerY, revertWidth, footerHeight,
            revert, b -> {
                draft.revert();
                if (reviewing) scroll = 0;
                reviewing = false;
                notice = Component.empty();
                rebuildRows();
            }, font));
        rebuildRows();
        updateRuntime();
    }

    private void selectCategory(SettingsDraft.Category value) {
        if (!activeTab(value)) tabSelectedAt = System.nanoTime();
        category = value;
        reviewing = false;
        scroll = 0;
        if (!query.isEmpty()) {
            search.setValue("");
        } else {
            rebuildRows();
        }
    }

    private void toggleReview() {
        reviewing = !reviewing;
        scroll = 0;
        notice = Component.empty();
        if (reviewing && !query.isEmpty()) {
            search.setValue("");
        } else {
            rebuildRows();
        }
    }

    private void openSetting(SettingsDraft.Entry entry) {
        selectCategory(entry.category());
        int row = rows.indexOf(entry);
        if (row < 0) return;
        select(entry);
        scroll = Math.min(row * ROW_HEIGHT, maxScroll);
        positionRows();
        AbstractWidget widget = rowWidgets.get(row);
        if (widget.active) setFocused(widget);
    }

    void rebuildRows() {
        captureButton = null;
        // Evaluate dependent option state and construct all controls before publishing either list.
        // A failed predicate must not leave entries without matching widgets for tick/render.
        List<SettingsDraft.Entry> nextRows = new ArrayList<>();
        List<AbstractWidget> nextWidgets = new ArrayList<>();
        Set<SettingsDraft.Entry> visibleInCategory = new HashSet<>();
        Map<SettingsDraft.Entry, Component> prerequisites = new HashMap<>();
        boolean searching = !query.isBlank();
        boolean browsing = !reviewing && !searching;
        search.setHint(reviewing ? tr("bedray.settings.filter_hint", "Filter") : tr("bedray.settings.search_hint", "Search"));
        search.setTooltip(Tooltip.create(reviewing
            ? tr("bedray.settings.review_search", "Filter pending settings")
            : tr("bedray.settings.search", "Search all settings")));
        List<String> terms = SettingsSearch.terms(query);
        // Report each failed predicate with its option before replacing the existing rows.
        List<String> broken = new ArrayList<>();
        RuntimeException firstFailure = null;
        for (SettingsDraft.Entry entry : draft.entries) {
            if (searching && !SettingsSearch.matches(entry.searchText(), terms)) continue;
            boolean visible;
            try {
                if (reviewing && !draft.pending(entry)) continue;
                visible = draft.visible(entry);
                if (reviewing && visible) visibleInCategory.add(entry);
                if (!reviewing && searching) {
                    Component prerequisite = draft.searchPrerequisiteName(entry);
                    if (prerequisite != null) prerequisites.put(entry, prerequisite);
                }
            } catch (RuntimeException failure) {
                LOGGER.error("[VKB-SETTINGS] option={} category={} failed to evaluate its state: {}", entry.path(),
                    entry.category(), failure.toString());
                broken.add(entry.path());
                if (firstFailure == null) firstFailure = failure;
                continue;
            }
            if (!reviewing && !visible && !prerequisites.containsKey(entry)) continue;
            if (browsing && entry.category() != category) continue;
            nextRows.add(entry);
        }
        if (firstFailure != null) {
            throw new IllegalStateException(broken.size() + " Bedray setting(s) cannot evaluate their state: " + broken,
                firstFailure);
        }
        int controls = layout.controlWidth();
        for (SettingsDraft.Entry entry : nextRows) {
            int x = contentX + contentWidth - controls - 6;
            if (reviewing) {
                nextWidgets.add(reviewControl(entry, visibleInCategory.contains(entry), x, controls));
                continue;
            }
            AbstractWidget widget = createControl(entry, x, controls);
            Component prerequisite = prerequisites.get(entry);
            widget.active = prerequisite == null && entry.option().isEnabled() && draft.editable(entry);
            if (widget.active && Boolean.TRUE.equals(entry.option().getValidatedValue())) {
                widget.setMessage(widget.getMessage().copy().withColor(SettingsChrome.TITLE));
            }
            widget.setTooltip(Tooltip.create(controlHelp(entry, widget.active, prerequisite)));
            nextWidgets.add(widget);
        }
        closeChoices();
        int focusedRow = rowWidgets.indexOf(getFocused());
        SettingsDraft.Entry focusedEntry = focusedRow >= 0 && focusedRow < rows.size() ? rows.get(focusedRow) : null;
        if (focusedRow >= 0) setFocused(null);
        for (AbstractWidget widget : rowWidgets) removeWidget(widget);
        rowWidgets.clear(); rows.clear();
        rows.addAll(nextRows);
        for (AbstractWidget widget : nextWidgets) rowWidgets.add(addRenderableWidget(widget));
        if (!rows.contains(selected)) {
            selected = rows.isEmpty() ? null : rows.getFirst();
            rowSelectedAt = 0L;
        }
        if (browsing && category == SettingsDraft.Category.PRESETS) {
            addAction(tr("bedray.settings.looks", "Visual style…"), () -> openLooks(contentX, listTop));
            addAction(tr("bedray.settings.experience_library", "Rendering experiences..."),
                () -> openExperiences(contentX, listTop, contentWidth - 6));
            addAction(tr("bedray.settings.presets_manage", "Manage presets..."), () -> minecraft.gui.setScreen(new PresetsScreen(this, draft)));
            addAction(tr("bedray.settings.presets_copy", "Copy pending settings"), () -> minecraft.keyboardHandler.setClipboard(draft.snapshot()));
            addAction(tr("bedray.settings.presets_import", "Import into pending settings"), () -> {
                int count = draft.importText(minecraft.keyboardHandler.getClipboard());
                notice = tr("bedray.settings.preset_staged", "%s preset values staged. Apply to commit.", count);
                rebuildRows();
            });
        }
        if (browsing && category == SettingsDraft.Category.OVERVIEW && draft.hasQualityProfile())
            addAction(tr("bedray.settings.customize", "Customize current quality"), () -> { draft.customize(); rebuildRows(); });
        if (browsing && category == SettingsDraft.Category.OVERVIEW && draft.hasQualityProfile() && !draft.voxel())
            addAction(tr("bedray.settings.gi_auto", "Auto-select GI quality (recommended)"), () -> {
                var device = io.github.andrexarlay.vulkancompat.rtx.raytracing.GiDeviceProbe.probe();
                if (device == null) {
                    notice = tr("bedray.settings.gi_auto_unavailable", "Auto-select needs the Vulkan device; open a world first.");
                    return;
                }
                var plan = io.github.andrexarlay.vulkancompat.rtx.config.GiStackPolicy.recommend(
                    device, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
                draft.applyGiPlan(plan);
                notice = tr("bedray.settings.gi_auto_staged", "Auto: %s. Apply to commit.",
                    io.github.andrexarlay.vulkancompat.rtx.config.GiStackPolicy.describe(plan));
                rebuildRows();
            });
        if (browsing && category == SettingsDraft.Category.WORLD && !draft.voxel())
            addAction(tr("bedray.settings.texture_style", "Texture style..."), () -> {
                var presets = io.github.andrexarlay.vulkancompat.rtx.config.TexturePreset.values();
                openChoices(java.util.Arrays.stream(presets).map(p -> switch (p) {
                    case PIXEL_ART -> tr("bedray.settings.texture.pixel_art", "Pixel-Sharp");
                    case BALANCED -> tr("bedray.settings.texture.balanced", "Balanced");
                    case SOFT -> tr("bedray.settings.texture.soft", "Soft");
                }).toList(), index -> {
                    draft.applyTexturePreset(presets[index]);
                    notice = tr("bedray.settings.texture_staged", "Texture style staged. Apply to commit.");
                    rebuildRows();
                }, contentX, listTop, contentWidth - 6);
            });
        if (browsing && category == SettingsDraft.Category.SYSTEM) {
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
        int visibleRows = Math.max(1, (listBottom - listTop + ROW_HEIGHT - CONTROL_HEIGHT) / ROW_HEIGHT);
        maxScroll = Math.max(0, rowWidgets.size() - visibleRows) * ROW_HEIGHT;
        scroll = Math.min(scroll, maxScroll);
        positionRows();
        int restoredFocus = rows.indexOf(focusedEntry);
        if (restoredFocus >= 0 && rowWidgets.get(restoredFocus).visible && rowWidgets.get(restoredFocus).active) {
            setFocused(rowWidgets.get(restoredFocus));
        }
        PreviewPanel.clearSelection();
        if (selected != null) { PreviewPanel.select(selected.option()); PreviewPanel.setRuntimeStatus(draft.status(selected).text()); }
        updateButtons();
        navigation.forEach((value, button) -> button.setMessage(
            fit(value.title(), button.getWidth()).copy().withColor(activeTab(value) ? SettingsChrome.TITLE : SettingsChrome.MUTED)));
    }

    private boolean activeTab(SettingsDraft.Category value) {
        return value == category && !reviewing && query.isBlank();
    }

    private Button addAction(Component name, Runnable action) {
        int actionWidth = Math.min(contentWidth - 6, Math.max(140, font.width(name) + 16));
        Button button = addRenderableWidget(Button.builder(fit(name, actionWidth - 8), b -> action.run())
            .bounds(contentX + (contentWidth - actionWidth) / 2, 0, actionWidth, CONTROL_HEIGHT).build());
        button.setTooltip(Tooltip.create(name));
        rowWidgets.add(button);
        return button;
    }

    private Component controlHelp(SettingsDraft.Entry entry, boolean enabled, Component prerequisite) {
        if (enabled) return entry.option().getName().copy().append("\n").append(entry.option().getTooltip());
        if (prerequisite != null) {
            return tr("bedray.settings.search_prerequisite", "Turn on %s to edit this setting.", prerequisite)
                .copy().append("\n").append(entry.option().getTooltip());
        }
        if (draft.hasQualityProfile() && draft.profileOwned(entry.path())) {
            return tr("bedray.settings.locked_by_profile",
                "Set by the Fast/Beautiful quality profile. Switch the profile to Custom to tune it by hand.");
        }
        return tr("bedray.settings.locked", "Needs another option, feature or runtime library to be active first.");
    }

    private AbstractWidget reviewControl(SettingsDraft.Entry entry, boolean visibleInCategory, int x, int width) {
        Component value = entry.path().equals("renderer") ? draft.experienceTitle() : valueText(entry.option());
        Button button = Button.builder(fit(value, width - 8), b -> openSetting(entry))
            .bounds(x, 0, width, CONTROL_HEIGHT).build();
        button.active = visibleInCategory;
        button.setTooltip(Tooltip.create(value.copy().append("\n").append(visibleInCategory
            ? tr("bedray.settings.review_open", "Open in %s", entry.category().title())
            : tr("bedray.settings.review_hidden", "Staged value. This control is hidden in the current mode."))));
        return button;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWidget createControl(SettingsDraft.Entry entry, int x, int controlWidth) {
        StatefulOption option = entry.option();
        if (entry.path().equals("renderer")) return Button.builder(fit(draft.experienceTitle(), controlWidth - 8), b -> {
            select(entry); openExperiences(b.getX(), b.getY(), controlWidth);
        }).bounds(x, 0, controlWidth, CONTROL_HEIGHT).build();
        if (option instanceof IntegerOption integer) return new AbstractSliderButton(x, 0, controlWidth, CONTROL_HEIGHT, integer.formatValue(integer.getValidatedValue()), normalized(integer)) {
            @Override protected void updateMessage() { setMessage(integer.formatValue(integer.getValidatedValue())); }
            @Override protected void applyValue() {
                var validator = integer.getSteppedValidator();
                int stepped = validator.min() + (int)Math.round(value * (validator.max() - validator.min()) / validator.step()) * validator.step();
                integer.modifyValue(Math.max(validator.min(), Math.min(validator.max(), stepped)));
                select(entry); updateMessage(); updateButtons();
            }
        };
        if (option.getValidatedValue() instanceof Boolean) {
            int toggleWidth = Math.min(controlWidth, 56);
            return Button.builder(fit(valueText(option), toggleWidth - 8), b -> {
                select(entry);
                option.modifyValue(!(Boolean) option.getValidatedValue());
                rebuildRows();
            }).bounds(x + controlWidth - toggleWidth, 0, toggleWidth, CONTROL_HEIGHT).build();
        }
        return Button.builder(fit(valueText(option), controlWidth - 8), b -> {
            select(entry);
            if (option instanceof EnumOption enumeration) {
                List<Enum> values = new ArrayList<>((java.util.Set<Enum>)enumeration.getAllowedValues().get(draft.config));
                openChoices(values.stream().map(enumeration::getElementName).toList(), index -> {
                    draft.choose(entry, values.get(index)); rebuildRows();
                }, b.getX(), b.getY(), Math.max(controlWidth, 150));
            }
        }).bounds(x, 0, controlWidth, CONTROL_HEIGHT).build();
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
    private Component fit(Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return Component.literal(font.plainSubstrByWidth(text.getString(), Math.max(0, maxWidth - font.width("…"))) + "…");
    }

    private void select(SettingsDraft.Entry entry) {
        if (selected != entry) {
            selected = entry;
            rowSelectedAt = System.nanoTime();
            PreviewPanel.setRuntimeStatus(draft.status(entry).text());
        }
        PreviewPanel.select(entry.option());
    }
    private void positionRows() {
        for (int i = 0; i < rowWidgets.size(); i++) {
            AbstractWidget w = rowWidgets.get(i); int y = listTop + i * ROW_HEIGHT - scroll;
            w.setY(y);
            w.visible = y >= listTop && y + CONTROL_HEIGHT <= listBottom;
            if (!w.visible && w.isFocused()) setFocused(null);
        }
    }
    private void updateButtons() {
        boolean changed = draft.changed();
        if (detailsButton != null) detailsButton.active = selected != null;
        if (closeButton != null) closeButton.setTooltip(changed
            ? Tooltip.create(tr("bedray.settings.close_unsaved", "Close with unsaved changes"))
            : null);
        if (reviewButton != null) {
            Component label = changed
                ? tr("bedray.settings.review_count", "Review (%s)", draft.pendingCount())
                : tr("bedray.settings.review", "Review");
            reviewButton.setMessage(fit(label, reviewButton.getWidth() - 4).copy()
                .withColor(reviewing ? SettingsChrome.ACCENT : changed ? SettingsChrome.PENDING : SettingsChrome.MUTED));
            reviewButton.active = changed || reviewing;
        }
        if (applyButton != null) applyButton.visible = applyButton.active = changed;
        if (revertButton != null) revertButton.visible = revertButton.active = changed;
        if (!changed && (getFocused() == applyButton || getFocused() == revertButton)) setFocused(closeButton);
        if (captureButton != null) captureButton.active = !changed && io.github.andrexarlay.vulkancompat.rtx.debug.GameplayCapture.canStart();
    }
    private void apply() {
        draft.apply();
        draft = new SettingsDraft();
        if (reviewing) scroll = 0;
        reviewing = false;
        notice = Component.empty();
        rebuildRows();
        updateRuntime();
    }
    @Override public void tick() {
        super.tick();
        int focusedRow = rowWidgets.indexOf(getFocused());
        if (focusedRow >= 0 && focusedRow < rows.size() && selected != rows.get(focusedRow)) {
            select(rows.get(focusedRow));
        }
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
    private void openLooks(int x, int y) {
        var styles = SettingsLook.values();
        openChoices(java.util.Arrays.stream(styles).map(SettingsLook::title).toList(), i -> {
            styles[i].valuesToStage().forEach(draft::stage);
            notice = tr("bedray.settings.look_staged", "%s staged. Apply to save.", styles[i].title());
            rebuildRows();
        }, x, y, 145);
        for (int i = 0; i < styles.length; i++) {
            popupButtons.get(i).setTooltip(Tooltip.create(styles[i].description()));
        }
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
        if (PreviewPanel.isModalOpen()) {
            PreviewPanel.scrolled(x, y, sy);
            return true;
        }
        if (x >= contentX && x < contentX + contentWidth && y >= listTop && y < listBottom && maxScroll > 0 && sy != 0) {
            int step = (int) Math.copySign(Math.max(1, Math.round(Math.abs(sy))), sy);
            scroll = Math.clamp(scroll - step * ROW_HEIGHT, 0, maxScroll);
            positionRows();
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (!popupButtons.isEmpty()) {
            for (Button b : List.copyOf(popupButtons)) if (b.visible && b.mouseClicked(event, twice)) return true;
            closeChoices(); return true;
        }
        if (PreviewPanel.isModalOpen()) {
            if (event.button() == 0) PreviewPanel.click(event.x(), event.y(), this::resetSelected);
            return true;
        }
        if (event.x() >= contentX && event.x() < contentX + contentWidth && event.y() >= listTop && event.y() < listBottom) {
            int index = ((int)event.y() - listTop + scroll) / ROW_HEIGHT;
            if (index >= 0 && index < rows.size() && rowWidgets.get(index).visible) {
                select(rows.get(index));
                if (event.button() == 0 && event.x() < rowWidgets.get(index).getX()) return true;
            }
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
        long now = System.nanoTime();
        float tabOpacity = SettingsChrome.selectionOpacity(now, tabSelectedAt);
        float rowOpacity = SettingsChrome.selectionOpacity(now, rowSelectedAt);
        navigation.forEach((value, tab) -> {
            int index = value.ordinal();
            int x = layout.navX(index);
            int y = layout.navY(index);
            int w = layout.navWidth(index);
            boolean hovered = mx >= x && mx < x + w && my >= y && my < y + SettingsLayout.NAV_HEIGHT;
            SettingsChrome.navigation(g, x, y, w, activeTab(value) ? tabOpacity : 0f, hovered || tab.isFocused());
        });
        for (int i = 0; i < rows.size(); i++) {
            AbstractWidget widget = rowWidgets.get(i);
            if (!widget.visible) continue;
            int y = widget.getY();
            boolean hovered = mx >= contentX && mx < contentX + contentWidth && my >= y && my < y + CONTROL_HEIGHT;
            SettingsChrome.row(g, contentX, y, contentWidth, selected == rows.get(i) ? rowOpacity : 0f, hovered, draft.pending(rows.get(i)));
        }
        super.extractRenderState(g, mx, my, delta);
        g.text(font, title, (width - font.width(title)) / 2, 8, SettingsChrome.TITLE);
        for (int i = 0; i < rows.size(); i++) {
            AbstractWidget widget = rowWidgets.get(i);
            if (!widget.visible) continue;
            SettingsDraft.Entry row = rows.get(i);
            Component label = row.option().getName();
            if (reviewing || !query.isBlank()) label = label.copy().append(" · ").append(row.category().title());
            int labelWidth = widget.getX() - contentX - 12;
            g.text(font, fit(label, labelWidth), contentX + 7, widget.getY() + (CONTROL_HEIGHT - font.lineHeight) / 2,
                widget.active ? SettingsChrome.TEXT : SettingsChrome.MUTED);
        }
        if (rowWidgets.isEmpty()) {
            Component empty;
            if (reviewing && query.isBlank()) {
                empty = draft.importedCount() > 0
                    ? tr("bedray.settings.review_imported_only", "Imported values are staged. Apply to save them.")
                    : tr("bedray.settings.review_empty", "No settings to review.");
            } else {
                empty = draft.voxel() && query.isBlank()
                    ? tr("bedray.settings.voxel_unrelated", "These settings belong to the hardware renderer. Choose another category.")
                    : tr("bedray.settings.empty", "No matching settings.");
            }
            int y = listTop; for (var line : font.split(empty, contentWidth - 8)) { g.text(font, line, contentX + 2, y, SettingsChrome.MUTED); y += 11; }
        }
        if (maxScroll > 0) {
            int track = Math.max(1, listBottom - listTop), thumb = Math.max(8, track * track / (maxScroll + track));
            int y = listTop + (track - thumb) * scroll / maxScroll;
            g.fill(contentX + contentWidth - 3, y, contentX + contentWidth - 1, y + thumb, SettingsChrome.THUMB);
        }
        Component footer = reviewing && draft.importedCount() > 0
            ? tr("bedray.settings.review_imported_count", "Additional preset values: %s", draft.importedCount())
            : !notice.getString().isEmpty() ? notice : draft.changed()
            ? tr("bedray.settings.staged_hint", "Changes pending · Apply to save")
            : category == SettingsDraft.Category.SYSTEM ? runtime : Component.empty();
        g.text(font, fit(footer, contentWidth), contentX, layout.footerY() - 12,
            draft.changed() ? SettingsChrome.PENDING : SettingsChrome.MUTED);
        if (selected != null && PreviewPanel.isModalOpen()) {
            PreviewPanel.setResetAllowed(selected.option().isEnabled() && draft.editable(selected));
            PreviewPanel.render(g, font, new Dim2i(contentX, listTop, contentWidth, listBottom - listTop), listBottom,
                selected.option(), selected.info(), mx, my);
        }
        if (!popupButtons.isEmpty()) {
            g.nextStratum(); g.fill(0, 0, width, height, 0x70000000);
            SettingsChrome.panel(g, popupX, popupY, popupWidth, popupHeight);
            for (Button b : popupButtons) b.extractRenderState(g, mx, my, delta);
        }
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        int transparency = draft.menuTransparency();
        if (minecraft.level == null) {
            super.extractBackground(g, mx, my, delta);
        } else {
            g.fill(0, 0, width, height, SettingsChrome.worldShade(transparency));
        }
        if (layout != null) SettingsChrome.background(g, layout, transparency);
    }
    @Override public void onClose() {
        PreviewPanel.clearSelection();
        if (!draft.changed()) {
            minecraft.gui.setScreen(parent);
            return;
        }
        minecraft.gui.setScreen(new ConfirmScreen(discard -> minecraft.gui.setScreen(discard ? parent : this),
            tr("bedray.settings.discard_title", "Discard pending Bedray changes?"),
            tr("bedray.settings.discard_message", "Return to the settings and select Apply to keep your changes."),
            tr("bedray.settings.discard", "Discard"), tr("bedray.settings.keep_editing", "Keep editing")));
    }
}
