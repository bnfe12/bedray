package io.github.andrexarlay.vulkancompat.sodium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * "My presets": the built-in factory looks first, then the player's own files. Save the current Bedray settings under a
 * name, load, delete, copy to / import from the clipboard. Built-ins are read-only (Load only, no Delete). Layout is
 * computed from the screen size in GUI units (any GUI scale / window size): the list shows as many rows as fit and pages
 * with the arrow buttons. Player presets are files in bedray/presets (PresetStore); built-ins come from BuiltinPresets.
 */
public final class PresetsScreen extends Screen {
    private static final int ROW_H = 22;
    private final Screen parent;
    private final SettingsDraft draft;
    private EditBox nameBox;
    private int page;
    private Component status = Component.empty();
    private List<Row> rows = List.of();

    /** One list row: a built-in factory look (read-only) or a player preset file. */
    private record Row(String name, boolean builtin) {
    }

    public PresetsScreen(Screen parent) {
        this(parent, parent instanceof BedrayConfigScreen screen ? screen.draft() : new SettingsDraft());
    }

    PresetsScreen(Screen parent, SettingsDraft draft) {
        super(Component.translatableWithFallback("bedray.presets.title", "My Bedray presets"));
        this.parent = parent;
        this.draft = draft;
    }

    private static Component tr(String k, String f, Object... a) {
        return Component.translatableWithFallback(k, f, a);
    }

    private static List<Row> collectRows() {
        List<Row> all = new ArrayList<>();
        for (BuiltinPresets.Preset p : BuiltinPresets.all()) {
            all.add(new Row(p.name(), true));
        }
        for (String n : PresetStore.list()) {
            all.add(new Row(n, false));
        }
        return all;
    }

    @Override
    protected void init() {
        rows = collectRows();
        int w = Math.min(width - 8, 300);
        int x = (width - w) / 2;
        int y = 22;
        nameBox = new EditBox(font, x, y, w - 82, 20, tr("bedray.presets.name", "Preset name"));
        nameBox.setMaxLength(32);
        nameBox.setHint(tr("bedray.presets.name_hint", "Name of the new preset"));
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(tr("bedray.presets.save", "Save current"), b -> {
            String n = PresetStore.sanitize(nameBox.getValue());
            if (n == null) {
                status = tr("bedray.presets.bad_name", "Enter a name (letters, digits, space, - and _).");
            } else if (PresetStore.save(n, draft.snapshot())) {
                status = tr("bedray.presets.saved", "Saved \"%s\".", n);
                nameBox.setValue("");
                rebuild();
            } else {
                status = tr("bedray.presets.save_failed", "Could not save the preset (see the log).");
            }
        }).bounds(x + w - 80, y, 80, 20).build());

        int bottom = height - 28;
        int listTop = y + 28;
        int perPage = Math.max(1, (bottom - listTop - 16) / ROW_H);
        int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= rows.size()) {
                break;
            }
            Row row = rows.get(idx);
            String n = row.name();
            int ry = listTop + i * ROW_H;
            int bw = Math.max(40, Math.min(58, (w - 60) / 3));
            addRenderableWidget(Button.builder(tr("bedray.presets.load", "Load"), b -> {
                String t = row.builtin() ? BuiltinPresets.find(n).text() : PresetStore.read(n);
                int c = t == null ? -1 : draft.importText(t);
                status = c < 0 ? tr("bedray.presets.load_failed", "Could not load \"%s\".", n)
                    : tr("bedray.settings.preset_staged", "%s preset values staged. Apply to commit.", c);
            }).bounds(x + w - bw * 2 - 4, ry, bw, 20).build());
            if (!row.builtin()) {
                addRenderableWidget(Button.builder(tr("bedray.presets.delete", "Delete"), b -> {
                    PresetStore.delete(n);
                    status = tr("bedray.presets.deleted", "Deleted \"%s\".", n);
                    rebuild();
                }).bounds(x + w - bw, ry, bw, 20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); rebuild(); })
            .bounds(x, bottom - 22, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = Math.min(pages - 1, page + 1); rebuild(); })
            .bounds(x + 24, bottom - 22, 20, 20).build());
        int hw = (w - 48 - 8) / 2;
        addRenderableWidget(Button.builder(tr("bedray.presets.copy", "Copy to clipboard"), b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(draft.snapshot());
            status = tr("bedray.presets.copied", "Current settings copied as text.");
        }).bounds(x + 48, bottom - 22, hw, 20).build());
        addRenderableWidget(Button.builder(tr("bedray.presets.import", "Import from clipboard"), b -> {
            int c = draft.importText(Minecraft.getInstance().keyboardHandler.getClipboard());
            status = c < 0 ? tr("bedray.presets.import_failed", "The clipboard has no Bedray settings.")
                : tr("bedray.settings.preset_staged", "%s preset values staged. Apply to commit.", c);
        }).bounds(x + 48 + hw + 8, bottom - 22, hw, 20).build());
        addRenderableWidget(Button.builder(tr("gui.done", "Done"), b -> onClose()).bounds(x, bottom + 2, w, 20).build());
    }

    private void rebuild() {
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.text(font, title, (width - font.width(title)) / 2, 8, 0xFFFFFFFF);
        int w = Math.min(width - 8, 300);
        int x = (width - w) / 2;
        int listTop = 50;
        int perPage = Math.max(1, (height - 28 - listTop - 16) / ROW_H);
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= rows.size()) {
                break;
            }
            Row row = rows.get(idx);
            String label = row.builtin()
                ? row.name() + "  [" + tr("bedray.presets.builtin", "built-in").getString() + "]"
                : row.name();
            g.text(font, font.plainSubstrByWidth(label, w - 130), x + 2, listTop + i * ROW_H + 6,
                row.builtin() ? 0xFF9CDCFE : 0xFFE0E0E0);
        }
        if (rows.isEmpty()) {
            g.text(font, tr("bedray.presets.none", "No presets yet. Set the options you like and save them here."), x + 2, listTop + 6, 0xFF909090);
        }
        g.text(font, status, x, height - 24 - 26 - 10 < listTop ? height - 10 : height - 28 - 22 - 11, 0xFFFFD866);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
