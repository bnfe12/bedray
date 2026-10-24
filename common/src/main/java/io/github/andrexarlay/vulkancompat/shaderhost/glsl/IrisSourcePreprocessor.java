package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackPaths;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackScanException;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves includes and macro/conditional compilation BEFORE resource and interface analysis.
 * Uses shaderc's preprocessor rather than a second, approximate implementation of #if expressions.
 * Include callbacks are synchronous and read only from the already bounded pack source.
 * No GPU or Vulkan device is involved. All native include allocations belong to one invocation.
 *
 * This is the post-host dialect: GLSL 450 preprocessing, the pack's original numeric __VERSION__,
 * and the pack's original numeric __VERSION__. Source option editing happens before this layer.
 * It does not advertise Iris capabilities or world-dependent engine macros.
 */
public final class IrisSourcePreprocessor {
    public static final int MAX_INCLUDE_READS = 1024;
    public static final int MAX_SOURCE_CHARS = 16 << 20;
    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+(\\d+)([^\\n]*)");
    // Preserve malformed active directives too: otherwise preprocessing erases them and translation silently defaults to 0.
    private static final Pattern TARGET_COMMENT = Pattern.compile(
        "/\\*\\s*(?:DRAWBUFFERS|RENDERTARGETS|GAUX4FORMAT)\\b[\\s\\S]*?\\*/");
    private static final Pattern TARGET_PRAGMA = Pattern.compile(
        "(?m)^[ \\t]*#[ \\t]*pragma[ \\t]+bedray_iris_target_([0-9a-f]+)[ \\t]*$");

    private IrisSourcePreprocessor() {}

    public static String preprocess(String shadersRoot, String entry, IncludeExpander.Reader reader,
                                    IrisGlslTranslator.Stage stage)
        throws IOException, SpirvCompiler.SpirvCompileException {
        return preprocess(shadersRoot, entry, reader, stage, Map.of());
    }

    public static String preprocess(String shadersRoot, String entry, IncludeExpander.Reader reader,
                                    IrisGlslTranslator.Stage stage, Map<String, String> environment)
        throws IOException, SpirvCompiler.SpirvCompileException {
        return new Session(shadersRoot, reader, environment).run(entry, stage);
    }

    private static final class Session {
        private final String root;
        private final IncludeExpander.Reader reader;
        private final Map<String, String> environment;
        private final Map<Long, NativeInclude> includes = new LinkedHashMap<>();
        private int reads;
        private long sourceChars;
        private int originalVersion = 110;
        private String failure;

        private Session(String root, IncludeExpander.Reader reader, Map<String, String> environment) {
            this.root = root;
            this.reader = reader;
            this.environment = Map.copyOf(environment);
        }

        private String run(String entry, IrisGlslTranslator.Stage stage)
            throws IOException, SpirvCompiler.SpirvCompileException {
            if (!entry.startsWith(root)) throw new PackScanException("shader is outside shaders root: " + entry);
            String source = read(entry);
            String clean = IrisGlslTranslator.stripCommentsPreservingLines(source);
            Matcher version = VERSION.matcher(clean);
            if (version.find()) {
                originalVersion = Integer.parseInt(version.group(1));
                if (version.group(2).contains("es")) throw new PackScanException("GLSL ES packs are not supported");
                // Keep the line but hide the original directive; legacy tokens are parsed only after translation.
                source = source.substring(0, version.start())
                    + " ".repeat(version.end() - version.start()) + source.substring(version.end());
            }
            source = "#version 450\n#extension GL_GOOGLE_include_directive : require\n"
                + "#extension GL_GOOGLE_cpp_style_line_directive : require\n#line 1\n" + prepare(source);
            long compiler = Shaderc.shaderc_compiler_initialize();
            if (compiler == 0) throw new SpirvCompiler.SpirvCompileException(entry + ": cannot initialize preprocessor");
            long options = 0;
            long result = 0;
            try (ShadercIncludeResolve resolve = ShadercIncludeResolve.create(this::resolve);
                 ShadercIncludeResultRelease release = ShadercIncludeResultRelease.create((user, address) -> release(address))) {
                options = Shaderc.shaderc_compile_options_initialize();
                if (options == 0) throw new SpirvCompiler.SpirvCompileException(entry + ": cannot initialize preprocessor options");
                Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                    Shaderc.shaderc_env_version_vulkan_1_2);
                Shaderc.shaderc_compile_options_set_include_callbacks(options, resolve, release, 0);
                for (var macro : environment.entrySet())
                    Shaderc.shaderc_compile_options_add_macro_definition(options, macro.getKey(), macro.getValue());
                // Heap-encoded: the CharSequence overload uses the 64 KiB thread MemoryStack, too small for large packs.
                ByteBuffer sourceText = MemoryUtil.memUTF8(source, false);
                ByteBuffer fileName = MemoryUtil.memUTF8(entry);
                ByteBuffer entryPoint = MemoryUtil.memUTF8("main");
                try {
                    result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, sourceText,
                        SpirvCompiler.toShadercKind(stage), fileName, entryPoint, options);
                } finally {
                    MemoryUtil.memFree(entryPoint);
                    MemoryUtil.memFree(fileName);
                    MemoryUtil.memFree(sourceText);
                }
                if (failure != null) throw new PackScanException(failure);
                if (result == 0) throw new SpirvCompiler.SpirvCompileException(entry + ": null preprocessor result");
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success)
                    throw new SpirvCompiler.SpirvCompileException(entry, status, Shaderc.shaderc_result_get_error_message(result));
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                if (bytes == null || bytes.remaining() > IncludeExpander.MAX_OUTPUT_CHARS)
                    throw new PackScanException(entry + ": preprocessed source exceeds output budget");
                // Unknown pragmas survive preprocessing, including only the branch the compiler took.
                // Restore the target comments for the translator; the compiler never saw them as code.
                Matcher targets = TARGET_PRAGMA.matcher(MemoryUtil.memUTF8(bytes));
                StringBuilder restored = new StringBuilder();
                while (targets.find()) {
                    String comment;
                    try { comment = new String(HexFormat.of().parseHex(targets.group(1)), StandardCharsets.UTF_8); }
                    catch (IllegalArgumentException e) { throw new PackScanException("invalid preserved Iris target directive"); }
                    targets.appendReplacement(restored, Matcher.quoteReplacement(comment));
                }
                targets.appendTail(restored);
                return restored.toString();
            } finally {
                if (result != 0) Shaderc.shaderc_result_release(result);
                if (options != 0) Shaderc.shaderc_compile_options_release(options);
                includes.values().forEach(NativeInclude::close);
                includes.clear();
                Shaderc.shaderc_compiler_release(compiler);
            }
        }

        private long resolve(long user, long requested, int type, long requesting, long depth) {
            String name = "";
            String content;
            try {
                if (failure != null) throw new PackScanException(failure);
                if (depth > IncludeExpander.MAX_DEPTH) throw new PackScanException("#include depth exceeds " + IncludeExpander.MAX_DEPTH);
                name = PackPaths.resolveWithin(root, MemoryUtil.memUTF8(requesting), MemoryUtil.memUTF8(requested));
                content = prepare(read(name));
            } catch (IOException | RuntimeException e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                name = ""; // shaderc's failed-include contract: empty source name, diagnostic in content.
                content = failure;
            }
            NativeInclude include = NativeInclude.create(name, content);
            includes.put(include.result.address(), include);
            return include.result.address();
        }

        private void release(long address) {
            NativeInclude include = includes.remove(address);
            if (include != null) include.close();
        }

        private String read(String file) throws IOException {
            if (++reads > MAX_INCLUDE_READS) throw new PackScanException("#include read budget exceeds " + MAX_INCLUDE_READS);
            String text = reader.read(file).replace("\r\n", "\n").replace('\r', '\n');
            sourceChars += text.length();
            if (sourceChars > MAX_SOURCE_CHARS) throw new PackScanException("shader source budget exceeds " + MAX_SOURCE_CHARS + " chars");
            return text;
        }

        /** Token-aware handling keeps comment directives out of // comments and quoted paths. */
        private String prepare(String source) {
            StringBuilder out = new StringBuilder(source.length());
            int line = 1;
            for (int i = 0; i < source.length();) {
                int start = i;
                char c = source.charAt(i++);
                if (c == '/' && i < source.length() && source.charAt(i) == '/') {
                    while (i < source.length() && source.charAt(i) != '\n') i++;
                } else if (c == '/' && i < source.length() && source.charAt(i) == '*') {
                    int end = source.indexOf("*/", i + 1);
                    i = end < 0 ? source.length() : end + 2;
                    Matcher target = TARGET_COMMENT.matcher(source.substring(start, i));
                    if (target.matches()) {
                        String encoded = HexFormat.of().formatHex(source.substring(start, i).getBytes(StandardCharsets.UTF_8));
                        out.append("\n#pragma bedray_iris_target_").append(encoded)
                            .append("\n#line ").append(line).append('\n');
                        for (int p = start; p < i; p++) if (source.charAt(p) == '\n') { out.append('\n'); line++; }
                        continue;
                    }
                } else if (c == '"') {
                    while (i < source.length()) {
                        char q = source.charAt(i++);
                        if (q == '\\' && i < source.length()) i++;
                        else if (q == '"') break;
                    }
                } else if (Character.isLetter(c) || c == '_') {
                    while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) i++;
                    if (source.substring(start, i).equals("__VERSION__")) { out.append(originalVersion); continue; }
                }
                out.append(source, start, i);
                for (int p = start; p < i; p++) if (source.charAt(p) == '\n') line++;
            }
            return out.toString();
        }
    }

    private record NativeInclude(ShadercIncludeResult result, ByteBuffer name, ByteBuffer content) {
        private static NativeInclude create(String name, String content) {
            ByteBuffer nativeName = MemoryUtil.memUTF8(name);
            nativeName.limit(nativeName.limit() - 1); // Sized C API, but keep an allocated byte for an empty name.
            ByteBuffer nativeContent = null;
            ShadercIncludeResult result = null;
            try {
                nativeContent = MemoryUtil.memUTF8(content);
                nativeContent.limit(nativeContent.limit() - 1);
                result = ShadercIncludeResult.calloc();
                result.source_name(nativeName).content(nativeContent);
                return new NativeInclude(result, nativeName, nativeContent);
            } catch (RuntimeException | Error e) {
                if (result != null) result.free();
                MemoryUtil.memFree(nativeContent);
                MemoryUtil.memFree(nativeName);
                throw e;
            }
        }

        private void close() {
            result.free();
            MemoryUtil.memFree(content);
            MemoryUtil.memFree(name);
        }
    }
}
