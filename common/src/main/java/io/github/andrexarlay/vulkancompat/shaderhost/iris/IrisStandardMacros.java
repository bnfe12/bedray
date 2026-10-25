package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine macros Iris defines for every pack source and for {@code shaders.properties} (its StandardMacros), as far as
 * this host can state them truthfully before the Vulkan device exists (packs compile at the early bootstrap).
 * <p>
 * Defined like Iris: {@code MC_VERSION} (from the game's own {@code version.json}, Iris's formatting: 26.3 -> 260300),
 * {@code MC_MIPMAP_LEVEL} (from options.txt), {@code MC_OS_*}, {@code IS_IRIS}, {@code MC_NORMAL_MAP},
 * {@code MC_SPECULAR_MAP}, {@code MC_RENDER_QUALITY}/{@code MC_SHADOW_QUALITY} 1.0, {@code MC_HAND_DEPTH} 0.125,
 * {@code MC_RENDER_STAGE_*} (Iris's WorldRenderingPhase ordinals) and the {@code DH_BLOCK_*} ids.
 * Deliberately different: {@code MC_GL_VERSION}/{@code MC_GLSL_VERSION} are 450, the GLSL dialect this host translates to
 * (Iris reports the driver's GL version); {@code MAX_COLOR_BUFFERS} is 16, this host's colortex count.
 * Not defined: {@code IRIS_VERSION}, the {@code MC_GL_VENDOR_*}/{@code MC_GL_RENDERER_*}/{@code MC_GL_ARB_*} families
 * (no GL context and no device yet) and Iris's geometry-pass capability flags. {@link #NOT_DEFINED_FAMILIES} lets the
 * graph report packs that reference them.
 */
public final class IrisStandardMacros {
    /** Iris WorldRenderingPhase in declaration order (javap of Iris 1.11.7); MC_RENDER_STAGE_X = ordinal. */
    static final List<String> RENDER_STAGES = List.of("NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID",
        "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG",
        "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT");
    static final List<String> DH_BLOCKS = List.of("UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE",
        "SNOW", "SAND", "TERRACOTTA", "NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED");
    /** Macro families Iris defines from the GL context; referencing them here means the pack sees them undefined. */
    public static final List<String> NOT_DEFINED_FAMILIES = List.of("IRIS_VERSION", "MC_GL_VENDOR_", "MC_GL_RENDERER_", "MC_GL_ARB_",
        "MC_GL_EXT_", "MC_GL_NV_", "MC_GL_AMD_");

    /**
     * @param macros      name -> value ("" for a bare define)
     * @param unavailable macro name -> reason it could not be stated (a pack referencing it is refused by name)
     */
    public record Environment(Map<String, String> macros, Map<String, String> unavailable) {
        public Environment {
            macros = Collections.unmodifiableMap(new LinkedHashMap<>(macros));
            unavailable = Collections.unmodifiableMap(new LinkedHashMap<>(unavailable));
        }
    }

    private static volatile Environment current;

    private IrisStandardMacros() {}

    /** Environment of this session; computed on first use from the classpath and, once configured, the game directory. */
    public static Environment current() {
        Environment e = current;
        if (e == null) {
            e = create(null, readGameVersion(), System.getProperty("os.name", ""));
            current = e;
        }
        return e;
    }

    /** Called by the Iris host probe with the game directory (options.txt). */
    public static void configure(Path gameDir) {
        current = create(gameDir, readGameVersion(), System.getProperty("os.name", ""));
    }

    static Environment create(Path gameDir, String gameVersion, String osName) {
        Map<String, String> m = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        String version = gameVersion == null ? null : formatVersion(gameVersion);
        if (version != null) m.put("MC_VERSION", version);
        else unavailable.put("MC_VERSION", gameVersion == null ? "the game's version.json is not readable" : "unparsable game version " + gameVersion);
        m.put("MC_MIPMAP_LEVEL", Integer.toString(mipmapLevels(gameDir)));
        m.put("MC_GL_VERSION", "450");
        m.put("MC_GLSL_VERSION", "450");
        m.put(osMacro(osName), "");
        m.put("IS_IRIS", "");
        m.put("MAX_COLOR_BUFFERS", Integer.toString(IrisUniforms.COLOR_TARGETS));
        for (int i = 0; i < DH_BLOCKS.size(); i++) m.put("DH_BLOCK_" + DH_BLOCKS.get(i), Integer.toString(i));
        m.put("MC_NORMAL_MAP", "");
        m.put("MC_SPECULAR_MAP", "");
        m.put("MC_RENDER_QUALITY", "1.0");
        m.put("MC_SHADOW_QUALITY", "1.0");
        m.put("MC_HAND_DEPTH", Float.toString(0.125f));
        for (int i = 0; i < RENDER_STAGES.size(); i++) m.put("MC_RENDER_STAGE_" + RENDER_STAGES.get(i), Integer.toString(i));
        return new Environment(m, unavailable);
    }

    /** Iris's formatVersionString: major + two-digit minor + two-digit patch ("26.3" -> "260300", "1.21.4" -> "12104"). */
    static String formatVersion(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) return null;
        for (String p : parts) if (!p.matches("\\d+")) return null;
        String minor = parts[1].length() == 1 ? "0" + parts[1] : parts[1];
        String patch = parts.length < 3 ? "00" : parts[2].length() == 1 ? "0" + parts[2] : parts[2];
        return parts[0] + minor + patch;
    }

    static String osMacro(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "MC_OS_WINDOWS";
        if (os.contains("mac") || os.contains("darwin")) return "MC_OS_MAC";
        if (os.contains("linux") || os.contains("nux") || os.contains("bsd")) return "MC_OS_LINUX";
        return "MC_OS_UNKNOWN";
    }

    /** The game's own release name from {@code version.json} on the classpath (the client jar), or null. */
    static String readGameVersion() {
        ClassLoader[] loaders = {Thread.currentThread().getContextClassLoader(), IrisStandardMacros.class.getClassLoader()};
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try (InputStream in = loader.getResourceAsStream("version.json")) {
                if (in == null) continue;
                String json = new String(in.readNBytes(1 << 16), StandardCharsets.UTF_8);
                Matcher name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (name.find()) return name.group(1);
            } catch (IOException | RuntimeException ignored) {
                // try the next loader
            }
        }
        return null;
    }

    /** options.txt {@code mipmapLevels}; the game's default 4 when absent or unreadable. */
    static int mipmapLevels(Path gameDir) {
        if (gameDir == null) return 4;
        Path options = gameDir.resolve("options.txt");
        if (!Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)) return 4;
        try {
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                if (line.startsWith("mipmapLevels:")) {
                    int v = Integer.parseInt(line.substring("mipmapLevels:".length()).trim());
                    return Math.max(0, Math.min(4, v));
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // fall through to the default
        }
        return 4;
    }

    static void setForTesting(Environment environment) {
        current = environment;
    }
}
