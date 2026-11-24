package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rebindable shortcuts of the Mosaic editor. Every {@link Action} has zero or more {@link Combo}s; a combo belongs to
 * at most one action (binding it elsewhere takes it away). Key codes are GLFW codes, modifiers the GLFW bit mask
 * (shift 1, ctrl 2, alt 4). Combos read and write as text ("Ctrl+Shift+Z") for the settings file. Pure Java.
 */
public final class MosaicKeymap {

    public static final int SHIFT = 1, CTRL = 2, ALT = 4;

    /** Editor commands that can carry a shortcut, in the order the Keys window lists them. */
    public enum Action {
        QUICK_ADD("Add node at cursor"),
        SEARCH("Search the palette"),
        UNDO("Undo"),
        REDO("Redo"),
        COPY("Copy"),
        PASTE("Paste"),
        DUPLICATE("Duplicate"),
        DELETE("Delete"),
        SELECT_ALL("Select all"),
        ARRANGE("Arrange nodes"),
        CODE("Generated code window"),
        APPLY("Save / apply"),
        FIT("Fit graph in view"),
        CENTER("Center on selection"),
        ZOOM_IN("Zoom in"),
        ZOOM_OUT("Zoom out"),
        ZOOM_RESET("Zoom 100%"),
        TOGGLE_PALETTE("Show / hide palette"),
        TOGGLE_INSPECTOR("Show / hide inspector"),
        TOGGLE_LIVE("Live save on / off"),
        TOGGLE_PREVIEW("See-through preview"),
        TOGGLE_SNAP("Snap to grid"),
        HELP("Keys window"),
        SETTINGS("Settings window");

        public final String label;

        Action(String label) { this.label = label; }

        /** The settings-file key: lower snake case. */
        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One key with its modifiers. */
    public record Combo(int key, int mods) {
        public Combo {
            mods &= SHIFT | CTRL | ALT;
        }

        public boolean matches(int key, int mods) { return this.key == key && this.mods == (mods & (SHIFT | CTRL | ALT)); }

        @Override public String toString() { return format(this); }
    }

    private final EnumMap<Action, List<Combo>> bindings = new EnumMap<>(Action.class);

    public MosaicKeymap() { resetToDefaults(); }

    public static Map<Action, List<Combo>> defaults() {
        Map<Action, List<Combo>> m = new EnumMap<>(Action.class);
        m.put(Action.QUICK_ADD, List.of(c(32, 0), c('A', SHIFT)));
        m.put(Action.SEARCH, List.of(c('F', CTRL)));
        m.put(Action.UNDO, List.of(c('Z', CTRL)));
        m.put(Action.REDO, List.of(c('Y', CTRL), c('Z', CTRL | SHIFT)));
        m.put(Action.COPY, List.of(c('C', CTRL)));
        m.put(Action.PASTE, List.of(c('V', CTRL)));
        m.put(Action.DUPLICATE, List.of(c('D', CTRL)));
        m.put(Action.DELETE, List.of(c(261, 0), c(259, 0)));
        m.put(Action.SELECT_ALL, List.of(c('A', CTRL)));
        m.put(Action.ARRANGE, List.of(c('L', CTRL)));
        m.put(Action.CODE, List.of(c(291, 0)));
        m.put(Action.APPLY, List.of(c('S', CTRL)));
        m.put(Action.FIT, List.of(c('F', 0), c(268, 0)));
        m.put(Action.CENTER, List.of(c('C', 0)));
        m.put(Action.ZOOM_IN, List.of(c('=', 0), c(334, 0)));
        m.put(Action.ZOOM_OUT, List.of(c('-', 0), c(333, 0)));
        m.put(Action.ZOOM_RESET, List.of(c('0', CTRL)));
        m.put(Action.TOGGLE_PALETTE, List.of(c(258, 0)));
        m.put(Action.TOGGLE_INSPECTOR, List.of(c('I', 0)));
        m.put(Action.TOGGLE_LIVE, List.of(c('L', 0)));
        m.put(Action.TOGGLE_PREVIEW, List.of(c('P', 0)));
        m.put(Action.TOGGLE_SNAP, List.of(c('G', 0)));
        m.put(Action.HELP, List.of(c(290, 0)));
        m.put(Action.SETTINGS, List.of(c(',', CTRL)));
        return m;
    }

    private static Combo c(int key, int mods) { return new Combo(key, mods); }

    public void resetToDefaults() {
        bindings.clear();
        for (Action a : Action.values()) bindings.put(a, new ArrayList<>(defaults().getOrDefault(a, List.of())));
    }

    public List<Combo> get(Action a) { return List.copyOf(bindings.get(a)); }

    /** The action a key press triggers, or null. */
    public Action match(int key, int mods) {
        for (Map.Entry<Action, List<Combo>> e : bindings.entrySet())
            for (Combo c : e.getValue()) if (c.matches(key, mods)) return e.getKey();
        return null;
    }

    /**
     * Binds {@code combo} to slot {@code index} of the action (index >= size appends). The combo is taken away from
     * any other action; that action is returned so the editor can say so, else null.
     */
    public Action bind(Action action, int index, Combo combo) {
        Action stolen = null;
        for (Map.Entry<Action, List<Combo>> e : bindings.entrySet())
            if (e.getKey() != action && e.getValue().remove(combo)) stolen = e.getKey();
        List<Combo> list = bindings.get(action);
        int existing = list.indexOf(combo);
        if (existing >= 0) return stolen;
        if (index >= 0 && index < list.size()) list.set(index, combo);
        else list.add(combo);
        return stolen;
    }

    public void unbind(Action action, int index) {
        List<Combo> list = bindings.get(action);
        if (index >= 0 && index < list.size()) list.remove(index);
    }

    // ---------------------------------------------------------------- text form

    private static final Map<Integer, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put(32, "Space");
        NAMES.put(256, "Esc");
        NAMES.put(257, "Enter");
        NAMES.put(258, "Tab");
        NAMES.put(259, "Backspace");
        NAMES.put(260, "Insert");
        NAMES.put(261, "Delete");
        NAMES.put(262, "Right");
        NAMES.put(263, "Left");
        NAMES.put(264, "Down");
        NAMES.put(265, "Up");
        NAMES.put(266, "PageUp");
        NAMES.put(267, "PageDown");
        NAMES.put(268, "Home");
        NAMES.put(269, "End");
        NAMES.put(333, "Num-");
        NAMES.put(334, "Num+");
        NAMES.put(335, "NumEnter");
        for (int i = 1; i <= 12; i++) NAMES.put(289 + i, "F" + i);
        for (char ch : "',-./;=[\\]`".toCharArray()) NAMES.put((int) ch, String.valueOf(ch));
    }

    /** GLFW modifier keys: pressing one alone never makes a combo. */
    public static boolean isModifierKey(int key) { return key >= 340 && key <= 347; }

    public static String keyName(int key) {
        if ((key >= 'A' && key <= 'Z') || (key >= '0' && key <= '9')) return String.valueOf((char) key);
        String n = NAMES.get(key);
        return n != null ? n : "Key" + key;
    }

    public static String format(Combo c) {
        StringBuilder sb = new StringBuilder();
        if ((c.mods() & CTRL) != 0) sb.append("Ctrl+");
        if ((c.mods() & ALT) != 0) sb.append("Alt+");
        if ((c.mods() & SHIFT) != 0) sb.append("Shift+");
        return sb.append(keyName(c.key())).toString();
    }

    /** Parses "Ctrl+Shift+Z" style text; null when it names no key. */
    public static Combo parse(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        int mods = 0;
        while (true) {
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.startsWith("ctrl+")) mods |= CTRL;
            else if (lower.startsWith("alt+")) mods |= ALT;
            else if (lower.startsWith("shift+")) mods |= SHIFT;
            else break;
            t = t.substring(t.indexOf('+') + 1);
        }
        if (t.isEmpty()) return null;
        if (t.length() == 1) {
            char ch = Character.toUpperCase(t.charAt(0));
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || NAMES.containsKey((int) ch)) return new Combo(ch, mods);
            return null;
        }
        for (Map.Entry<Integer, String> e : NAMES.entrySet())
            if (e.getValue().equalsIgnoreCase(t)) return new Combo(e.getKey(), mods);
        if (t.startsWith("Key")) {
            try {
                return new Combo(Integer.parseInt(t.substring(3)), mods);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Settings-file form: {action id: ["Ctrl+Z", ...]} for every action. */
    public Map<String, List<String>> toText() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (Action a : Action.values()) m.put(a.id(), bindings.get(a).stream().map(MosaicKeymap::format).toList());
        return m;
    }

    /** Reads the settings-file form; unknown actions and unreadable combos are skipped, missing actions keep defaults. */
    public void fromText(Map<String, List<String>> text) {
        resetToDefaults();
        if (text == null) return;
        for (Action a : Action.values()) if (text.get(a.id()) != null) bindings.get(a).clear();
        for (Action a : Action.values()) {
            List<String> combos = text.get(a.id());
            if (combos == null) continue;
            for (String s : combos) {
                Combo c = parse(s);
                if (c != null) bind(a, Integer.MAX_VALUE, c);
            }
        }
    }
}
