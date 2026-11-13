package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings;
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.BooleanOption;
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.ChoiceOption;
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.IntegerOption;
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.Option;
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.Registration;
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.Text;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders loader-independent add-on descriptors and applies only the selected owner's draft. */
final class AddonSettingsScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Addons");
    private static final int ROW_HEIGHT = 33;
    private static final int LIST_TOP = 81;

    private final Screen parent;
    private final List<Registration> addons = AddonSettings.registrations();
    private final Map<String, Draft> drafts = new LinkedHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private int selection;
    private int page;
    private int pageCount;
    private int left;
    private int panelWidth;
    private String query = "";
    private Component status = Component.empty();
    private Button previousPage;
    private Button nextPage;
    private Button apply;
    private Button defaults;
    private boolean readFailed;

    AddonSettingsScreen(Screen parent) {
        super(tr("bedray.addons.title", "Bedray add-on settings"));
        this.parent = parent;
    }

    private static Component tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback, args);
    }

    private static Component text(Text text) {
        return text.key().isEmpty() ? Component.literal(text.fallback())
            : Component.translatableWithFallback(text.key(), text.fallback());
    }

    private Registration selected() {
        return addons.isEmpty() ? null : addons.get(selection);
    }

    private Draft currentDraft() {
        return selected() == null ? null : drafts.get(selected().namespace());
    }

    @Override
    protected void init() {
        rows.clear();
        panelWidth = Math.max(1, Math.min(640, width - 24));
        left = (width - panelWidth) / 2;
        if (addons.isEmpty()) {
            addRenderableWidget(Button.builder(tr("gui.back", "Back"), b -> onClose())
                .bounds(left, height - 26, panelWidth, 20).build());
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> select(-1))
            .bounds(left, 28, 22, 20).build()).active = addons.size() > 1;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> select(1))
            .bounds(left + panelWidth - 22, 28, 22, 20).build()).active = addons.size() > 1;
        var search = new EditBox(font, left, 53, panelWidth, 20, tr("bedray.settings.search", "Search settings"));
        search.setHint(tr("bedray.settings.search", "Search settings"));
        search.setMaxLength(80);
        search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; rebuildRows(); });
        addRenderableWidget(search);

        int bottom = height - 26;
        int buttonWidth = Math.max(1, (panelWidth - 60) / 3);
        previousPage = addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
            .bounds(left, bottom, 22, 20).build());
        nextPage = addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
            .bounds(left + 26, bottom, 22, 20).build());
        defaults = addRenderableWidget(Button.builder(tr("bedray.addons.defaults", "Defaults"), b -> restoreDefaults())
            .bounds(left + 52, bottom, buttonWidth, 20).build());
        apply = addRenderableWidget(Button.builder(tr("bedray.settings.apply", "Apply"), b -> apply())
            .bounds(left + 56 + buttonWidth, bottom, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(tr("gui.back", "Back"), b -> onClose())
            .bounds(left + 60 + buttonWidth * 2, bottom, buttonWidth, 20).build());
        loadDraft();
        rebuildRows();
    }

    private void select(int direction) {
        selection = Math.floorMod(selection + direction, addons.size());
        page = 0;
        status = Component.empty();
        rebuildWidgets();
    }

    private void loadDraft() {
        Registration addon = selected();
        readFailed = false;
        if (drafts.containsKey(addon.namespace())) return;
        try {
            drafts.put(addon.namespace(), new Draft(addon.read()));
        } catch (RuntimeException failure) {
            readFailed = true;
            LOGGER.error("[VKB-ADDONS] read failed addon={}", addon.namespace(), failure);
        }
    }

    private void rebuildRows() {
        for (Row row : rows) removeWidget(row.control());
        rows.clear();
        Draft draft = currentDraft();
        int perPage = Math.max(1, (height - 53 - LIST_TOP) / ROW_HEIGHT);
        List<String> terms = SettingsSearch.terms(query);
        List<Option<?>> matches = draft == null ? List.of() : selected().options().stream()
            .filter(option -> query.isBlank() || matchesQuery(option, terms))
            .sorted(Comparator.comparing((Option<?> option) -> text(option.details().category()).getString())
                .thenComparingInt(option -> option.details().order()).thenComparing(option -> option.details().id()))
            .toList();
        pageCount = Math.max(1, (matches.size() + perPage - 1) / perPage);
        page = Math.min(page, pageCount - 1);
        for (int i = page * perPage; i < Math.min(matches.size(), (page + 1) * perPage); i++) {
            Option<?> option = matches.get(i);
            int y = LIST_TOP + (i - page * perPage) * ROW_HEIGHT;
            int controlWidth = Math.max(50, Math.min(166, panelWidth / 2));
            AbstractWidget control = control(option, left + panelWidth - controlWidth - 4, y, controlWidth);
            rows.add(new Row(option, addRenderableWidget(control)));
        }
        updateControls();
    }

    private boolean matchesQuery(Option<?> option, List<String> terms) {
        var details = option.details();
        String index = SettingsSearch.index(String.join(" ", details.id(), text(details.name()).getString(),
            details.name().fallback(), text(details.help()).getString(), details.help().fallback(),
            text(details.category()).getString()));
        return SettingsSearch.matches(index, terms);
    }

    private AbstractWidget control(Option<?> option, int x, int y, int controlWidth) {
        String id = option.details().id();
        Draft draft = currentDraft();
        if (option instanceof IntegerOption range && range.min() < range.max()) {
            long steps = ((long) range.max() - range.min()) / range.step();
            double fraction = ((Integer) draft.values.get(id) - (double) range.min()) / (steps * range.step());
            return new AbstractSliderButton(x, y, controlWidth, 20, valueText(option), fraction) {
                @Override protected void updateMessage() { setMessage(valueText(option)); }
                @Override protected void applyValue() {
                    draft.values.put(id, (int) (range.min() + Math.round(value * steps) * range.step()));
                    updateControls();
                }
            };
        }
        return Button.builder(valueText(option), button -> {
            Object previous = draft.values.get(id);
            if (option instanceof BooleanOption) {
                draft.values.put(id, !(Boolean) previous);
            } else if (option instanceof ChoiceOption choice) {
                int index = 0;
                while (!choice.choices().get(index).id().equals(previous)) index++;
                draft.values.put(id, choice.choices().get((index + 1) % choice.choices().size()).id());
            }
            updateControls();
        }).bounds(x, y, controlWidth, 20).build();
    }

    private Component valueText(Option<?> option) {
        Object value = currentDraft().values.get(option.details().id());
        if (value instanceof Boolean on) return on ? tr("options.on", "On") : tr("options.off", "Off");
        if (option instanceof ChoiceOption choice) {
            return choice.choices().stream().filter(item -> item.id().equals(value)).findFirst()
                .map(item -> text(item.label())).orElseGet(() -> Component.literal(value.toString()));
        }
        return Component.literal(value.toString());
    }

    private void updateControls() {
        Draft draft = currentDraft();
        previousPage.active = page > 0;
        nextPage.active = page + 1 < pageCount;
        defaults.active = draft != null;
        apply.active = draft != null && draft.changed();
        for (Row row : rows) {
            boolean enabled = row.option().enabled(draft.values);
            row.control().active = enabled
                && !(row.option() instanceof IntegerOption range && range.min() == range.max());
            row.control().setMessage(valueText(row.option()));
            var help = Component.empty().append(text(row.option().details().help()));
            if (!enabled) help.append("\n").append(tr("bedray.addons.dependency", "Requires: %s",
                row.option().details().requires().stream()
                    .map(condition -> condition.optionId() + " = " + condition.value())
                    .collect(java.util.stream.Collectors.joining(", "))));
            row.control().setTooltip(Tooltip.create(help));
        }
    }

    private void changePage(int direction) {
        int next = Math.max(0, Math.min(pageCount - 1, page + direction));
        if (next == page) return;
        page = next;
        rebuildRows();
    }

    private void restoreDefaults() {
        var draft = currentDraft();
        for (Option<?> option : selected().options()) {
            draft.values.put(option.details().id(), option.defaultValue());
        }
        rebuildRows();
    }

    private void apply() {
        Registration addon = selected();
        Draft draft = currentDraft();
        Map<String, Object> changes = draft.changes();
        try {
            addon.apply(changes);
        } catch (RuntimeException failure) {
            LOGGER.error("[VKB-ADDONS] apply failed addon={} options={}", addon.namespace(), changes.keySet(), failure);
            status = tr("bedray.addons.apply_failed", "Could not apply settings. Changes are still pending.");
            return;
        }
        draft.baseline = Map.copyOf(draft.values);
        try {
            drafts.put(addon.namespace(), new Draft(addon.read()));
            status = tr("bedray.addons.applied", "Settings applied.");
        } catch (RuntimeException failure) {
            LOGGER.warn("[VKB-ADDONS] settings applied but readback failed addon={}", addon.namespace(), failure);
            status = tr("bedray.addons.readback_failed", "Applied. The add-on could not report its saved values.");
        }
        rebuildRows();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (x >= left && x < left + panelWidth && y >= LIST_TOP && y < height - 51 && vertical != 0) {
            changePage(vertical < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int x, int y, float delta) {
        super.extractBackground(g, x, y, delta);
        g.fill(0, 0, width, height, 0xF20C1119);
        g.fill(left - 4, 5, left + panelWidth + 4, height - 3, 0xFF121D29);
        for (Row row : rows) {
            String id = row.option().details().id();
            Draft draft = currentDraft();
            SettingsChrome.row(g, left, row.control().getY(), panelWidth, row.control().isFocused(),
                !draft.values.get(id).equals(draft.baseline.get(id)));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        super.extractRenderState(g, x, y, delta);
        g.text(font, title, left, 11, SettingsChrome.TEXT);
        Registration addon = selected();
        if (addon == null) {
            drawLines(g, tr("bedray.addons.none", "Installed add-ons can register their settings here."), 36);
            return;
        }
        String label = text(addon.title()).getString() + " (" + addon.namespace() + ")";
        g.text(font, font.plainSubstrByWidth(label, panelWidth - 52), left + 26, 35, SettingsChrome.ACCENT);
        for (Row row : rows) {
            var details = row.option().details();
            int labelWidth = Math.max(1, row.control().getX() - left - 10);
            g.text(font, font.plainSubstrByWidth(text(details.name()).getString(), labelWidth), left + 4,
                row.control().getY(), row.control().active ? SettingsChrome.TEXT : SettingsChrome.MUTED);
            g.text(font, font.plainSubstrByWidth(text(details.category()).getString(), labelWidth), left + 4,
                row.control().getY() + 12, SettingsChrome.MUTED);
        }
        if (rows.isEmpty()) drawLines(g, readFailed
            ? tr("bedray.addons.read_failed", "The add-on could not load its settings. See the log.")
            : tr("bedray.settings.empty", "No matching settings."), LIST_TOP);
        String footer = (page + 1) + "/" + pageCount;
        long pending = drafts.values().stream().filter(Draft::changed).count();
        if (pending > 0) footer += "  " + tr("bedray.addons.pending", "Add-ons with changes: %s", pending).getString();
        g.text(font, font.plainSubstrByWidth(footer, panelWidth), left, height - 48, SettingsChrome.MUTED);
        g.text(font, font.plainSubstrByWidth(status.getString(), panelWidth), left, height - 37, SettingsChrome.PENDING);
    }

    private void drawLines(GuiGraphicsExtractor g, Component message, int y) {
        for (var line : font.split(message, panelWidth)) {
            g.text(font, line, left, y, SettingsChrome.MUTED);
            y += 11;
        }
    }

    @Override
    public void onClose() {
        if (drafts.values().stream().anyMatch(Draft::changed)) {
            minecraft.gui.setScreen(new ConfirmScreen(discard -> minecraft.gui.setScreen(discard ? parent : this),
                tr("bedray.addons.discard_title", "Discard add-on changes?"),
                tr("bedray.addons.discard_message", "Unsaved changes will be discarded.")));
        } else {
            minecraft.gui.setScreen(parent);
        }
    }

    private record Row(Option<?> option, AbstractWidget control) {}

    private static final class Draft {
        private Map<String, Object> baseline;
        private final Map<String, Object> values;

        Draft(Map<String, Object> initial) {
            baseline = Map.copyOf(initial);
            values = new LinkedHashMap<>(initial);
        }

        boolean changed() {
            return !baseline.equals(values);
        }

        Map<String, Object> changes() {
            var changes = new LinkedHashMap<>(values);
            changes.entrySet().removeIf(entry -> entry.getValue().equals(baseline.get(entry.getKey())));
            return changes;
        }
    }
}
