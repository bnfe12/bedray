package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles pack shaders that are already written in Vulkan GLSL (ray-tracing stages of Vulkanite-style packs and the
 * host's custom RT compute stage). Unlike the Iris post dialect these sources are NOT translated: they declare their own
 * {@code set}/{@code binding} layout, which the host later reads back from SPIR-V with {@link SpirvReflection}.
 * <p>
 * Includes are expanded textually by {@link IncludeExpander} (pack-root confined, Iris semantics), because entry files such
 * as {@code ray0_0.rchit} consist of a single {@code #include} whose target carries the {@code #version} line. The first
 * {@code #version} of the expanded text is moved to the top and the line-directive extension is enabled after it, so
 * compiler errors keep naming the real pack file and line. Target: Vulkan 1.2, SPIR-V 1.4 (the minimum for ray tracing).
 */
public final class VulkanGlslPackCompiler {
    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+(\\d+)[^\\n]*$");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+(\\w+)[ \\t]*:");

    public enum Kind {
        RAYGEN(Shaderc.shaderc_raygen_shader, ".rgen"),
        MISS(Shaderc.shaderc_miss_shader, ".rmiss"),
        CLOSEST_HIT(Shaderc.shaderc_closesthit_shader, ".rchit"),
        ANY_HIT(Shaderc.shaderc_anyhit_shader, ".rahit"),
        COMPUTE(Shaderc.shaderc_compute_shader, ".comp");

        public final int shadercKind;
        public final String extension;

        Kind(int shadercKind, String extension) {
            this.shadercKind = shadercKind;
            this.extension = extension;
        }
    }

    /**
     * @param spirv               compiled module (direct buffer, little-endian)
     * @param module              reflection of {@link #spirv}
     * @param scalarBlockLayout   the source enables {@code GL_EXT_scalar_block_layout}
     */
    public record Compiled(String entry, Kind kind, ByteBuffer spirv, SpirvReflection.Module module, boolean scalarBlockLayout) {}

    private VulkanGlslPackCompiler() {}

    /**
     * @param shadersRoot normalized pack path of the shaders folder ("shaders/")
     * @param entry       normalized pack path of the entry file
     * @param reader      bounded pack reader (options may already be applied to includes)
     * @param macros      extra macro definitions (name -> value)
     */
    public static Compiled compile(String shadersRoot, String entry, IncludeExpander.Reader reader, Kind kind,
                                   Map<String, String> macros) throws IOException, SpirvCompiler.SpirvCompileException {
        String expanded = new IncludeExpander(shadersRoot, reader).expand(entry);
        String source = hoistVersion(expanded, entry, macros);
        boolean scalar = false;
        Matcher ext = EXTENSION.matcher(IrisGlslTranslator.stripCommentsPreservingLines(expanded));
        while (ext.find()) {
            if (ext.group(1).equals("GL_EXT_scalar_block_layout")) scalar = true;
        }
        ByteBuffer spirv = SpirvCompiler.compile(source, kind.shadercKind, entry);
        return new Compiled(entry, kind, spirv, SpirvReflection.reflect(spirv), scalar);
    }

    /** Puts the first {@code #version} directive first, then the line-directive extension, macros and the source. */
    static String hoistVersion(String expanded, String entry, Map<String, String> macros) throws PackScanException {
        String clean = IrisGlslTranslator.stripCommentsPreservingLines(expanded);
        Matcher version = VERSION.matcher(clean);
        if (!version.find()) throw new PackScanException(entry + ": no #version directive in the expanded source");
        if (Integer.parseInt(version.group(1)) < 450)
            throw new PackScanException(entry + ": Vulkan GLSL stages need #version 450 or newer, found " + version.group(1));
        String directive = expanded.substring(version.start(), version.end()).trim();
        String body = expanded.substring(0, version.start()) + " ".repeat(version.end() - version.start())
            + expanded.substring(version.end());
        StringBuilder sb = new StringBuilder(body.length() + 256);
        sb.append(directive).append('\n');
        sb.append("#extension GL_GOOGLE_cpp_style_line_directive : require\n");
        for (var macro : macros.entrySet()) {
            if (!macro.getKey().matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new PackScanException("invalid macro name: " + macro.getKey());
            sb.append("#define ").append(macro.getKey()).append(' ').append(macro.getValue()).append('\n');
        }
        String display = entry.contains("/") ? entry.substring(entry.indexOf('/') + 1) : entry;
        sb.append("#line 1 \"").append(display).append("\"\n");
        sb.append(body);
        return sb.toString();
    }
}
