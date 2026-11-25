package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every Mosaic Studio command and its key strokes. Everything the mouse does has a keyboard path, so the studio
 * works with the keyboard alone. Bindings are rebindable in the settings and stored per action id.
 */
public final class StudioKeys {

    public enum Action {
        // nodes
        ADD_NODE("Add node (search)", "Nodes", "SPACE", "shift A"),
        DELETE("Delete selection", "Nodes", "DELETE", "BACK_SPACE"),
        DUPLICATE("Duplicate", "Nodes", "ctrl D"),
        COPY("Copy (JSON, works across packs)", "Nodes", "ctrl C"),
        PASTE("Paste at the cursor", "Nodes", "ctrl V"),
        RENAME("Rename / label", "Nodes", "F2"),
        GROUP("Group into a frame", "Nodes", "ctrl G"),
        UNGROUP("Remove frame", "Nodes", "ctrl shift G"),
        ARRANGE("Arrange by dependency", "Nodes", "ctrl L"),
        MUTE("Mute / unmute selected nodes (pass the input through)", "Nodes", "M"),
        EXPAND("Show / hide the unused inputs of selected nodes", "Nodes", "ctrl H"),
        CONNECT_FROM("Wire selected output into ...", "Wires", "K"),
        CONNECT_INTO("Feed selected input from ...", "Wires", "shift K"),
        DISCONNECT("Disconnect inputs of selection", "Wires", "ctrl K"),
        // selection and keyboard navigation
        SELECT_ALL("Select all", "Select", "ctrl A"),
        DESELECT("Clear selection / close popup", "Select", "ESCAPE"),
        NEXT_NODE("Next node", "Select", "TAB"),
        PREV_NODE("Previous node", "Select", "shift TAB"),
        NAV_LEFT("Go to node on the left", "Select", "alt LEFT"),
        NAV_RIGHT("Go to node on the right", "Select", "alt RIGHT"),
        NAV_UP("Go to node above", "Select", "alt UP"),
        NAV_DOWN("Go to node below", "Select", "alt DOWN"),
        EXTEND_LEFT("Add node on the left to selection", "Select", "alt shift LEFT"),
        EXTEND_RIGHT("Add node on the right to selection", "Select", "alt shift RIGHT"),
        MOVE_LEFT("Move selection left", "Move", "LEFT"),
        MOVE_RIGHT("Move selection right", "Move", "RIGHT"),
        MOVE_UP("Move selection up", "Move", "UP"),
        MOVE_DOWN("Move selection down", "Move", "DOWN"),
        MOVE_LEFT_FAR("Move left by a grid step", "Move", "shift LEFT"),
        MOVE_RIGHT_FAR("Move right by a grid step", "Move", "shift RIGHT"),
        MOVE_UP_FAR("Move up by a grid step", "Move", "shift UP"),
        MOVE_DOWN_FAR("Move down by a grid step", "Move", "shift DOWN"),
        EDIT_PROPERTIES("Edit properties (focus the inspector)", "Select", "ENTER"),
        // view
        FIT("Fit graph", "View", "F", "HOME"),
        CENTER("Center on selection", "View", "C"),
        ZOOM_IN("Zoom in", "View", "ctrl EQUALS", "ADD"),
        ZOOM_OUT("Zoom out", "View", "ctrl MINUS", "SUBTRACT"),
        ZOOM_RESET("Zoom 100%", "View", "ctrl 0"),
        PAN_LEFT("Pan left", "View", "ctrl LEFT"),
        PAN_RIGHT("Pan right", "View", "ctrl RIGHT"),
        PAN_UP("Pan up", "View", "ctrl UP"),
        PAN_DOWN("Pan down", "View", "ctrl DOWN"),
        // areas (Blender-like layout)
        NEXT_AREA("Focus next area", "Areas", "F6"),
        PREV_AREA("Focus previous area", "Areas", "shift F6"),
        MAXIMIZE_AREA("Maximize / restore area", "Areas", "ctrl SPACE"),
        SPLIT_RIGHT("Split area side by side", "Areas", "ctrl alt RIGHT"),
        SPLIT_DOWN("Split area top / bottom", "Areas", "ctrl alt DOWN"),
        CLOSE_AREA("Close area", "Areas", "ctrl alt W"),
        DETACH_AREA("Pop area out into a window", "Areas", "ctrl alt D"),
        CYCLE_EDITOR("Change area editor type", "Areas", "ctrl alt E"),
        // file and tools
        NEW_TEMPLATE("New from template", "File", "ctrl N"),
        OPEN("Open...", "File", "ctrl O"),
        SAVE("Save", "File", "ctrl S"),
        UNDO("Undo", "File", "ctrl Z"),
        REDO("Redo", "File", "ctrl Y", "ctrl shift Z"),
        TOGGLE_LIVE("Live save on / off", "File", "ctrl shift L"),
        COMPILE("Compile now", "File", "F5"),
        SETTINGS("Settings", "File", "ctrl COMMA"),
        HELP("Show all keys", "File", "F1");

        public final String label, group;
        final String[] defaults;

        Action(String label, String group, String... defaults) {
            this.label = label;
            this.group = group;
            this.defaults = defaults;
        }

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    private final Map<Action, List<KeyStroke>> bindings = new EnumMap<>(Action.class);

    public StudioKeys(Map<String, List<String>> saved) {
        for (Action a : Action.values()) {
            List<String> texts = saved != null && saved.containsKey(a.id()) ? saved.get(a.id()) : List.of(a.defaults);
            List<KeyStroke> ks = new ArrayList<>();
            for (String t : texts) {
                KeyStroke k = KeyStroke.getKeyStroke(t);
                if (k != null) ks.add(k);
            }
            bindings.put(a, ks);
        }
    }

    public List<KeyStroke> get(Action a) { return List.copyOf(bindings.get(a)); }

    /** The action of a pressed key (modifiers compared exactly, mouse-button masks ignored). */
    public Action match(KeyStroke pressed) {
        KeyStroke k = KeyStroke.getKeyStroke(pressed.getKeyCode(), pressed.getModifiers() & MODS);
        for (var e : bindings.entrySet()) for (KeyStroke s : e.getValue()) if (KeyStroke.getKeyStroke(s.getKeyCode(), s.getModifiers() & MODS).equals(k)) return e.getKey();
        return null;
    }

    private static final int MODS = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;

    /** Binds a stroke (taking it from any other action, which is returned). */
    public Action bind(Action a, int slot, KeyStroke k) {
        Action stolen = null;
        for (var e : bindings.entrySet()) if (e.getKey() != a && e.getValue().remove(k)) stolen = e.getKey();
        List<KeyStroke> list = bindings.get(a);
        if (!list.contains(k)) {
            if (slot >= 0 && slot < list.size()) list.set(slot, k);
            else list.add(k);
        }
        return stolen;
    }

    public void unbind(Action a, int slot) {
        List<KeyStroke> list = bindings.get(a);
        if (slot >= 0 && slot < list.size()) list.remove(slot);
    }

    public void reset() {
        for (Action a : Action.values()) {
            List<KeyStroke> ks = new ArrayList<>();
            for (String t : a.defaults) ks.add(KeyStroke.getKeyStroke(t));
            bindings.put(a, ks);
        }
    }

    /** The settings-file form (only actions that differ from their defaults). */
    public Map<String, List<String>> toText() {
        Map<String, List<String>> m = new java.util.LinkedHashMap<>();
        for (Action a : Action.values()) {
            List<String> now = bindings.get(a).stream().map(StudioKeys::text).toList();
            List<String> def = java.util.Arrays.stream(a.defaults).map(KeyStroke::getKeyStroke).map(StudioKeys::text).toList();
            if (!now.equals(def)) m.put(a.id(), now);
        }
        return m;
    }

    /** Swing parse form of a stroke, e.g. "ctrl shift Z". */
    public static String text(KeyStroke k) {
        String s = k.toString().replace("pressed ", "");
        return s.trim();
    }

    /** Human form, e.g. "Ctrl+Shift+Z". */
    public static String human(KeyStroke k) {
        StringBuilder sb = new StringBuilder();
        int m = k.getModifiers();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) sb.append("Ctrl+");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) sb.append("Alt+");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append("Shift+");
        if ((m & InputEvent.META_DOWN_MASK) != 0) sb.append("Meta+");
        return sb.append(java.awt.event.KeyEvent.getKeyText(k.getKeyCode())).toString();
    }

    public String humanFor(Action a) {
        List<KeyStroke> ks = bindings.get(a);
        return ks.isEmpty() ? "" : human(ks.get(0));
    }
}
