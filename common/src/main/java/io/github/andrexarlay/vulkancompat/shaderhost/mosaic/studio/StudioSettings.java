package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Mosaic Studio preferences: theme, accent, scale and canvas look. Stored as JSON in
 * {@code ~/.bedray/mosaic-studio.json}; unknown or broken values fall back to defaults.
 */
public final class StudioSettings {

    /** Color sets; every surface of the studio reads from the active one. */
    public enum Theme {
        DARK("Dark", 0x0E1116, 0x171C25, 0x1C2230, 0x2B3344, 0xE8ECF4, 0x8B93A8, 0x5D6478, 0x1A1F2A, 0x11151C, 0x16202C, false),
        MIDNIGHT("Midnight blue", 0x0A0F1E, 0x111A2E, 0x16213A, 0x24345A, 0xE6EEFF, 0x8C9CC2, 0x5A6890, 0x13203A, 0x0C1426, 0x142240, false),
        GRAPHITE("Graphite", 0x161616, 0x1F1F1F, 0x262626, 0x383838, 0xEDEDED, 0x9A9A9A, 0x666666, 0x232323, 0x141414, 0x1E1E1E, false),
        LIGHT("Light", 0xEEF1F6, 0xFFFFFF, 0xF4F6FA, 0xCCD3DF, 0x1E2430, 0x5B6476, 0x8A93A6, 0xFFFFFF, 0xFAFBFD, 0xDDE3EC, true),
        OLED("OLED black", 0x000000, 0x000000, 0x050505, 0x1C1C1C, 0xE6E6E6, 0x8A8A8A, 0x555555, 0x0A0A0A, 0x000000, 0x0B0B0B, false),
        CONTRAST("High contrast", 0x000000, 0x0A0A0A, 0x111111, 0x7A7A7A, 0xFFFFFF, 0xD0D0D0, 0xA0A0A0, 0x050505, 0x000000, 0x202020, false);

        public final String label;
        public final int bg, panel, panel2, edge, text, muted, faint, node, code, grid;
        public final boolean light;

        Theme(String label, int bg, int panel, int panel2, int edge, int text, int muted, int faint, int node, int code, int grid,
              boolean light) {
            this.label = label;
            this.bg = bg;
            this.panel = panel;
            this.panel2 = panel2;
            this.edge = edge;
            this.text = text;
            this.muted = muted;
            this.faint = faint;
            this.node = node;
            this.code = code;
            this.grid = grid;
            this.light = light;
        }

        @Override public String toString() { return label; }
    }

    /** Accent colors for selection, focus and highlights. */
    public enum Accent {
        BLUE("Blue", 0x9CC4FF), VIOLET("Violet", 0xB59CFF), TEAL("Teal", 0x6FD2C0), AMBER("Amber", 0xFFC266), PINK("Pink", 0xFF8FC8),
        GREEN("Green", 0x8FE07A);

        public final String label;
        public final int rgb;

        Accent(String label, int rgb) {
            this.label = label;
            this.rgb = rgb;
        }

        @Override public String toString() { return label; }
    }

    public enum WireStyle {
        CURVED("Curved"), STRAIGHT("Straight"), STEPPED("Right angles");

        public final String label;

        WireStyle(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    public enum GridStyle {
        LINES("Lines"), DOTS("Dots"), NONE("None");

        public final String label;

        GridStyle(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    public Theme theme = Theme.DARK;
    public Accent accent = Accent.BLUE;
    public int fontPercent = 100;        // 80..160
    public WireStyle wires = WireStyle.CURVED;
    public float wireWidth = 2.2f;       // 1..5
    public int nodeWidth = 170;          // 130..260
    public GridStyle grid = GridStyle.LINES;
    public int gridStep = 24;            // 8..64
    public boolean snap = false;
    public boolean portNames = true;
    public boolean liveByDefault = true;
    public boolean animateWires = false;

    // ---------------------------------------------------------------- input
    public enum WheelMode {
        ZOOM("Mouse wheel zooms (mouse)"), PAN("Two-finger scroll pans, pinch zooms (touchpad)");

        public final String label;

        WheelMode(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    /** Where Slang node code is compiled: inside the game (optional compiler add-on) or here, shipping SPIR-V. */
    public enum SlangCompiler {
        STUDIO("Studio compiles, the game loads SPIR-V"), IN_GAME("The game compiles (needs the Slang add-on)");

        public final String label;

        SlangCompiler(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    public WheelMode wheel = WheelMode.ZOOM;
    public int zoomSpeed = 12;           // percent per wheel step, 3..40
    public boolean invertWheel = false;
    public boolean altDragPans = true;   // touchpads without a middle button
    public boolean rightDragPans = true;
    public boolean keyboardHints = true; // focus ring around the keyboard node, key names in tooltips
    public SlangCompiler slangCompiler = SlangCompiler.STUDIO;
    /** Action id -> key strokes (Swing syntax, e.g. "ctrl shift Z"); missing actions keep their defaults. */
    public java.util.Map<String, java.util.List<String>> keys = new java.util.LinkedHashMap<>();
    /** Command that starts Minecraft with Bedray (for example a Prism Launcher --launch call); empty = ask. */
    public String gameCommand = "";
    /** Title-bar buttons: AUTO draws traffic lights on macOS and the Mosaic set elsewhere. */
    public enum WindowButtons {
        AUTO("Match the system"), MOSAIC("Mosaic (right)"), MAC("Traffic lights (left)");
        public final String label;
        WindowButtons(String l) { label = l; }
        @Override public String toString() { return label; }
    }
    public WindowButtons windowButtons = WindowButtons.AUTO;
    /** slangc to use; empty finds it (BEDRAY_SLANGC, PATH, ~/.bedray/slang, SLANG_DIR). */
    public String slangcPath = "";
    /** Palette libraries the user switched off (categories: Effects, Rendering, Shading, My nodes...). */
    public java.util.Set<String> hiddenLibraries = new java.util.LinkedHashSet<>();
    /** Palette / add-menu groups the user collapsed. */
    public java.util.Set<String> collapsed = new java.util.LinkedHashSet<>();
    /** Saved window layout (see Dock), or null for the default. */
    public String layout;
    /** Layout of the Materials workspace (the Shaders workspace uses {@link #layout}). */
    public String materialLayout;
    /** Minecraft folder with Bedray (resourcepacks/ for materials); empty: found from the shader pack path. */
    public String gameDir = "";
    /** A client jar to take vanilla textures from; empty: found in launcher and build caches. */
    public String vanillaJar = "";
    /** Last files of each workspace. */
    public String lastShader = "", lastMaterial = "";
    /** What an AI agent may do through the mosaic command (Settings > Agent). Off until the user turns it on. */
    public static final class Agent {
        public boolean enabled = false;
        public boolean editGraphs = true;
        public boolean editMaterials = true;
        public boolean editDesign = false;
        public boolean compile = true;
    }

    public Agent agent = new Agent();

    /** Document tabs of the last session ("SHADERS|path", "MATERIALS|path"). */
    public java.util.List<String> openTabs = new java.util.ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path file() {
        String custom = System.getProperty("mosaic.settings"); // a separate settings file (tests, portable installs)
        if (custom != null && !custom.isBlank()) return Path.of(custom);
        return Path.of(System.getProperty("user.home"), ".bedray", "mosaic-studio.json");
    }

    /** When this process last wrote the settings file: a newer file came from outside (the mosaic command). */
    static volatile long ownWrite = -1;

    public static StudioSettings load() {
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return new StudioSettings();
            StudioSettings s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), StudioSettings.class);
            return s == null ? new StudioSettings() : s.sanitized();
        } catch (Exception e) {
            return new StudioSettings();
        }
    }

    public void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(sanitized()), StandardCharsets.UTF_8);
            ownWrite = Files.getLastModifiedTime(f).toMillis();
        } catch (Exception ignored) {
            // settings are a convenience; failing to save must not break editing
        }
    }

    public StudioSettings sanitized() {
        if (theme == null) theme = Theme.DARK;
        if (accent == null) accent = Accent.BLUE;
        if (wires == null) wires = WireStyle.CURVED;
        if (grid == null) grid = GridStyle.LINES;
        fontPercent = Math.clamp(fontPercent, 80, 160);
        wireWidth = Math.clamp(wireWidth, 1f, 5f);
        nodeWidth = Math.clamp(nodeWidth, 130, 260);
        gridStep = Math.clamp(gridStep, 8, 64);
        if (wheel == null) wheel = WheelMode.ZOOM;
        if (slangCompiler == null) slangCompiler = SlangCompiler.STUDIO;
        if (openTabs == null) openTabs = new java.util.ArrayList<>();
        if (agent == null) agent = new Agent();
        zoomSpeed = Math.clamp(zoomSpeed, 3, 40);
        if (keys == null) keys = new java.util.LinkedHashMap<>();
        if (hiddenLibraries == null) hiddenLibraries = new java.util.LinkedHashSet<>();
        if (collapsed == null) collapsed = new java.util.LinkedHashSet<>();
        return this;
    }

    public StudioSettings copy() { return GSON.fromJson(GSON.toJson(this), StudioSettings.class).sanitized(); }

    @Override public String toString() { return GSON.toJson(this).toLowerCase(Locale.ROOT); }
}
