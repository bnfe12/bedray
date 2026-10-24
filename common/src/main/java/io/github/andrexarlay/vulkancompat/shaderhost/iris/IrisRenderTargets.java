package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisConstDirectives;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisConstDirectives.ConstDirective;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;
import org.lwjgl.vulkan.VK10;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-target settings of the colortex buffers, resolved from the programs' const directives the way Iris does:
 * {@code colortexNFormat} (and the legacy names gcolor, gdepth, gnormal, composite, gaux1..4), {@code colortexNClear},
 * {@code colortexNClearColor}, {@code colortexNMipmapEnabled} (per program) and the {@code GAUX4FORMAT} comment.
 * <p>
 * Defaults follow Iris: format RGBA (8-bit), clear enabled, clear color (1,1,1,1) for colortex1 and (0,0,0,0) for the
 * others; colortex0 is replaced by the world image every frame, so its clear has no visible effect here. A sampled
 * {@code gdepth} without an explicit colortex1 format selects RGBA32F (OptiFine legacy rule kept by Iris).
 * GL formats map to Vulkan formats; formats without an alpha channel in GL (RGB8, RGB16F, ...) are stored as RGBA with
 * alpha writes masked and alpha cleared to 1, so sampling returns alpha 1 as in GL. Unsupported formats are refused by name.
 */
public final class IrisRenderTargets {

    /** A mapped GL internal format. {@code alphaLocked}: GL has no alpha, the RGBA storage masks alpha writes. */
    public record Format(String glName, int vkFormat, boolean alphaLocked, int bytesPerPixel) {}

    public record Target(int index, Format format, boolean explicitFormat, boolean clear, float[] clearColor) {
        public Target {
            clearColor = clearColor.clone();
        }

        @Override
        public float[] clearColor() {
            return clearColor.clone();
        }

        /** Persistent targets keep their last value into the next frame (Iris {@code colortexNClear = false}). */
        public boolean persistent() {
            return !clear && index != 0;
        }
    }

    /** Directives of one program as the resolver needs them. */
    public record ProgramDirectives(String program, List<ConstDirective> directives, boolean samplesGdepth, String gaux4Format) {
        public ProgramDirectives {
            directives = List.copyOf(directives);
        }
    }

    /**
     * @param targets   settings of colortex0..15 (defaults where nothing was declared)
     * @param mipmapped program name -> targets whose mip chain that program enables
     * @param problems  refusals: unsupported or conflicting declarations (empty = usable)
     */
    public record Resolution(List<Target> targets, Map<String, Set<Integer>> mipmapped, List<String> problems) {
        public Resolution {
            targets = List.copyOf(targets);
            Map<String, Set<Integer>> copy = new LinkedHashMap<>();
            mipmapped.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
            mipmapped = Collections.unmodifiableMap(copy);
            problems = List.copyOf(problems);
        }

        public Target target(int index) {
            return targets.get(index);
        }

        public Set<Integer> mipmapped(String program) {
            return mipmapped.getOrDefault(program, Set.of());
        }

        public static Resolution defaults() {
            return resolve(List.of());
        }
    }

    private static final Map<String, Format> FORMATS = new LinkedHashMap<>();
    private static final Map<String, String> REFUSED = new LinkedHashMap<>();
    private static final Pattern NAME = Pattern.compile(
        "^(colortex(\\d+)|gcolor|gdepth|gnormal|composite|gaux([1-4]))(Format|Clear|ClearColor|MipmapEnabled)$");
    private static final List<String> LEGACY = List.of("gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");

    static {
        f("RGBA", VK10.VK_FORMAT_R8G8B8A8_UNORM, false, 4);
        f("R8", VK10.VK_FORMAT_R8_UNORM, false, 1);
        f("RG8", VK10.VK_FORMAT_R8G8_UNORM, false, 2);
        f("RGB8", VK10.VK_FORMAT_R8G8B8A8_UNORM, true, 4);
        f("RGBA8", VK10.VK_FORMAT_R8G8B8A8_UNORM, false, 4);
        f("R8_SNORM", VK10.VK_FORMAT_R8_SNORM, false, 1);
        f("RG8_SNORM", VK10.VK_FORMAT_R8G8_SNORM, false, 2);
        f("RGB8_SNORM", VK10.VK_FORMAT_R8G8B8A8_SNORM, true, 4);
        f("RGBA8_SNORM", VK10.VK_FORMAT_R8G8B8A8_SNORM, false, 4);
        f("R16", VK10.VK_FORMAT_R16_UNORM, false, 2);
        f("RG16", VK10.VK_FORMAT_R16G16_UNORM, false, 4);
        f("RGB16", VK10.VK_FORMAT_R16G16B16A16_UNORM, true, 8);
        f("RGBA16", VK10.VK_FORMAT_R16G16B16A16_UNORM, false, 8);
        f("R16_SNORM", VK10.VK_FORMAT_R16_SNORM, false, 2);
        f("RG16_SNORM", VK10.VK_FORMAT_R16G16_SNORM, false, 4);
        f("RGB16_SNORM", VK10.VK_FORMAT_R16G16B16A16_SNORM, true, 8);
        f("RGBA16_SNORM", VK10.VK_FORMAT_R16G16B16A16_SNORM, false, 8);
        f("R16F", VK10.VK_FORMAT_R16_SFLOAT, false, 2);
        f("RG16F", VK10.VK_FORMAT_R16G16_SFLOAT, false, 4);
        f("RGB16F", VK10.VK_FORMAT_R16G16B16A16_SFLOAT, true, 8);
        f("RGBA16F", VK10.VK_FORMAT_R16G16B16A16_SFLOAT, false, 8);
        f("R32F", VK10.VK_FORMAT_R32_SFLOAT, false, 4);
        f("RG32F", VK10.VK_FORMAT_R32G32_SFLOAT, false, 8);
        f("RGB32F", VK10.VK_FORMAT_R32G32B32A32_SFLOAT, true, 16);
        f("RGBA32F", VK10.VK_FORMAT_R32G32B32A32_SFLOAT, false, 16);
        f("R11F_G11F_B10F", VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32, false, 4); // no alpha component: samples as 1
        f("RGB10_A2", VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32, false, 4);
        f("RGB565", VK10.VK_FORMAT_R5G6B5_UNORM_PACK16, false, 2);
        f("RGB5_A1", VK10.VK_FORMAT_A1R5G5B5_UNORM_PACK16, false, 2);
        f("RGBA4", VK10.VK_FORMAT_R4G4B4A4_UNORM_PACK16, false, 2);
        for (String base : List.of("R8", "RG8", "RGB8", "RGBA8", "R16", "RG16", "RGB16", "RGBA16", "R32", "RG32", "RGB32", "RGBA32")) {
            REFUSED.put(base + "I", "integer format needs isampler/ivec outputs, which this post dialect does not translate");
            REFUSED.put(base + "UI", "integer format needs usampler/uvec outputs, which this post dialect does not translate");
        }
        REFUSED.put("RGB10_A2UI", "integer format needs usampler/uvec outputs, which this post dialect does not translate");
        REFUSED.put("RGB9_E5", "shared-exponent format is not color-renderable on Vulkan");
        REFUSED.put("RGBA2", "no Vulkan equivalent");
        REFUSED.put("R3_G3_B2", "no Vulkan equivalent");
    }

    private static void f(String gl, int vk, boolean alphaLocked, int bytes) {
        FORMATS.put(gl, new Format(gl, vk, alphaLocked, bytes));
    }

    private IrisRenderTargets() {}

    /** Mapped format for a GL name, or null. */
    public static Format format(String glName) {
        return FORMATS.get(glName);
    }

    /** Why a GL format name is refused (unknown names included). */
    public static String refusal(String glName) {
        String reason = REFUSED.get(glName);
        return reason != null ? reason : "unknown format name";
    }

    public static Format defaultFormat() {
        return FORMATS.get("RGBA");
    }

    /** Target index of a directive/sampler base name (colortexN or a legacy alias), or -1. */
    public static int indexOf(String base) {
        int legacy = LEGACY.indexOf(base);
        if (legacy >= 0) return legacy;
        Matcher m = Pattern.compile("^colortex(\\d+)$").matcher(base);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Resolves every program's directives into target settings. Conflicting explicit values are refused, not ordered. */
    public static Resolution resolve(List<ProgramDirectives> programs) {
        Objects.requireNonNull(programs, "programs");
        int n = IrisUniforms.COLOR_TARGETS;
        Format[] format = new Format[n];
        String[] formatSource = new String[n];
        Boolean[] clear = new Boolean[n];
        String[] clearSource = new String[n];
        float[][] clearColor = new float[n][];
        String[] colorSource = new String[n];
        Map<String, Set<Integer>> mipmapped = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> unknownFormats = new TreeSet<>();
        boolean gdepthSampled = false;

        for (ProgramDirectives program : programs) {
            gdepthSampled |= program.samplesGdepth();
            if (program.gaux4Format() != null) {
                String gl = program.gaux4Format();
                if (!gl.equals("RGBA32F") && !gl.equals("RGB32F") && !gl.equals("RGB16")) {
                    problems.add(program.program() + ": GAUX4FORMAT " + gl + " (Iris accepts RGBA32F, RGB32F, RGB16)");
                } else if (format[7] != null && !format[7].glName().equals(gl)) {
                    problems.add("conflicting colortex7 format " + format[7].glName() + " (" + formatSource[7] + ") and "
                        + gl + " (" + program.program() + ")");
                } else {
                    format[7] = FORMATS.get(gl);
                    formatSource[7] = program.program();
                }
            }
            for (ConstDirective d : program.directives()) {
                Matcher m = NAME.matcher(d.name());
                if (!m.matches()) continue;
                int index = indexOf(m.group(1));
                if (index < 0 || index >= n) {
                    problems.add(program.program() + ": " + d.name() + " targets colortex" + index + "; this host provides colortex0.."
                        + (n - 1));
                    continue;
                }
                switch (m.group(4)) {
                    case "Format" -> {
                        if (d.type() != IrisConstDirectives.Type.INT) { problems.add(program.program() + ": " + d.name() + " must be const int"); continue; }
                        Format mapped = FORMATS.get(d.value());
                        if (mapped == null) {
                            unknownFormats.add("colortex" + index + "Format = " + d.value() + " (" + refusal(d.value()) + ")");
                            continue;
                        }
                        if (format[index] != null && !format[index].equals(mapped)) {
                            problems.add("conflicting colortex" + index + " format " + format[index].glName() + " (" + formatSource[index]
                                + ") and " + mapped.glName() + " (" + program.program() + ")");
                            continue;
                        }
                        format[index] = mapped;
                        formatSource[index] = program.program();
                    }
                    case "Clear" -> {
                        Boolean b = d.type() == IrisConstDirectives.Type.BOOL ? IrisConstDirectives.bool(d.value()) : null;
                        if (b == null) { problems.add(program.program() + ": " + d.name() + " must be const bool true/false"); continue; }
                        if (clear[index] != null && !clear[index].equals(b)) {
                            problems.add("conflicting colortex" + index + "Clear in " + clearSource[index] + " and " + program.program());
                            continue;
                        }
                        clear[index] = b;
                        clearSource[index] = program.program();
                    }
                    case "ClearColor" -> {
                        float[] v = d.type() == IrisConstDirectives.Type.VEC4 ? IrisConstDirectives.vector(d.value(), "vec4", 4) : null;
                        if (v == null) { problems.add(program.program() + ": " + d.name() + " must be const vec4 with literal components"); continue; }
                        if (clearColor[index] != null && !java.util.Arrays.equals(clearColor[index], v)) {
                            problems.add("conflicting colortex" + index + "ClearColor in " + colorSource[index] + " and " + program.program());
                            continue;
                        }
                        clearColor[index] = v;
                        colorSource[index] = program.program();
                    }
                    case "MipmapEnabled" -> {
                        Boolean b = d.type() == IrisConstDirectives.Type.BOOL ? IrisConstDirectives.bool(d.value()) : null;
                        if (b == null) { problems.add(program.program() + ": " + d.name() + " must be const bool true/false"); continue; }
                        if (b) mipmapped.computeIfAbsent(program.program(), k -> new TreeSet<>()).add(index);
                    }
                    default -> throw new IllegalStateException(m.group(4));
                }
            }
        }
        if (!unknownFormats.isEmpty()) problems.add("unsupported target formats: " + String.join(", ", unknownFormats));
        if (format[1] == null && gdepthSampled) {
            format[1] = FORMATS.get("RGBA32F");
            formatSource[1] = "legacy gdepth sampler";
        }
        List<Target> targets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Format fmt = format[i] != null ? format[i] : defaultFormat();
            float[] color = clearColor[i] != null ? clearColor[i].clone()
                : i == 1 ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 0};
            if (fmt.alphaLocked()) color[3] = 1f;
            targets.add(new Target(i, fmt, format[i] != null, clear[i] == null || clear[i], color));
        }
        return new Resolution(targets, mipmapped, problems);
    }
}
