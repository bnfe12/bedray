package io.github.andrexarlay.vulkanbridge.platform;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 source contract for the log privacy boundary.
 *
 * <p>An absolute path reaches {@code latest.log} through three channels, and the contract has one rule per channel:
 *
 * <ol>
 *   <li><b>The throwable itself.</b> The logging form {@code LOGGER.error(msg, e)} prints the whole cause chain, and the
 *   loaders that fail on {@code Files.list}, a resource-pack jar or {@code System.load} put the path in their own message.
 *   Rule A: in a guarded file, a logger call whose last argument is a variable caught by a {@code catch} must print it
 *   through {@link LogPaths#stack(Throwable)}. The variable names come from the file's own {@code catch} clauses, not from
 *   a hand-written list, so renaming a catch variable (or writing {@code error} instead of {@code e}) cannot reopen it.
 *   <li><b>An exception turned into text.</b> {@code e.getMessage()} and {@code e.toString()} carry the same path, and a
 *   guarded file also uses them outside any logger call — to build a status string that reaches F3, the settings page or
 *   a stored session state. Rule B therefore scans the whole file, not only the logger calls, and requires the redactor in
 *   the same statement.
 *   <li><b>A path-typed value.</b> A resolved vendor DLL or a library directory is a path by construction. Rule C, again
 *   file-wide, and again satisfied by a sink: a {@code Path} that is only stored or returned becomes text nowhere, so the
 *   rule fires where the value is concatenated into a string.
 * </ol>
 *
 * <p>The list is file-scoped on purpose: it holds the boundaries that touch the filesystem, a vendor DLL, or another
 * process's text. Sites outside it (Vulkan result codes, GPU timestamps, reflection) raise exceptions whose messages carry
 * numbers and names rather than paths, and rewriting them would move the boundary without closing a leak. A new filesystem
 * boundary belongs here with its call sites already scrubbed.
 *
 * <p>Strings that are <em>stored</em> to be shown later cannot be traced from a call site, so each user-visible surface
 * redacts once at its sink; {@link #statusSinksStillRedactAtTheSink} pins those sinks so that deleting the redaction there
 * fails the build instead of quietly reopening every caller.
 */
public class LogPathBoundaryTest {
    /** Repository-relative, from the Gradle test working directory of this module ({@code core/}). */
    private static final List<String> GUARDED = List.of(
        "src/main/java/io/github/andrexarlay/vulkanbridge/autotest/AutotestHarness.java",
        "src/main/java/io/github/andrexarlay/vulkanbridge/compat/iris/IrisCompat.java",
        "src/main/java/io/github/andrexarlay/vulkanbridge/core/NativeInstaller.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/entity/EntityCaptureManager.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/entity/EntityTextureAtlas.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/material/MaterialAtlasBaker.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/particles/RtParticleLayer.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/particles/RtWeatherLayer.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/upscale/dlss/nr/DlssNeuralRendering.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/OpacityMicromapCache.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSkyTextures.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/debug/Diagnostics.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/config/ConfigFiles.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/config/SamplerBiasSync.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/config/VulkanRtxConfig.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/FrameGenRuntime.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/FrameGenSettings.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/StreamlineBootstrap.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/upscaler/dlss/rr/DlssRayReconstruction.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/upscaler/xess/XessRuntime.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/ShaderHostConfig.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/ShaderHostStartup.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/ShaderHostStatus.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/dirtrt/DirtRtPipeline.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/dirtrt/DirtRtRuntime.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/iris/IrisRenderBridge.java",
        "../common/src/main/java/io/github/andrexarlay/vulkancompat/sodium/PreviewPanel.java",
        "../neoforge/src/main/java/io/github/andrexarlay/vulkanbridge/neoforge/mixin/NeoForgeMixinPlugin.java");

    /**
     * The user-visible surfaces that redact once for every caller. Each entry is the file, the call that closes the
     * channel, and the name a caller writes to reach it; a statement that hands its text to one of those names is
     * considered redacted, and {@link #statusSinksStillRedactAtTheSink} fails if the redaction is ever removed.
     */
    private static final List<String[]> SINKS = List.of(
        sink("../common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/ShaderHostStatus.java",
            "LogPaths.show(detail)"),
        sink("src/main/java/io/github/andrexarlay/vulkanbridge/autotest/AutotestHarness.java",
            "LogPaths.show(pairs", "kv("),
        sink("../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/FrameGenRuntime.java",
            "LogPaths.show(stage + ", "fail("),
        sink("../common/src/main/java/io/github/andrexarlay/vulkancompat/rtx/framegen/StreamlineBootstrap.java",
            "LogPaths.show(line)", "fail("),
        sink("../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java",
            "LogPaths.show(result)", "fail("),
        sink("../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/world/OpacityMicromapCache.java",
            "LogPaths.show(why)", "disable("),
        sink("../common/src/main/java/io/github/andrexarlay/vulkancompat/rt/upscale/dlss/nr/DlssNeuralRendering.java",
            "LogPaths.show(reason)", "unusable("),
        sink("../common/src/main/java/io/github/andrexarlay/vulkancompat/shaderhost/dirtrt/DirtRtPipeline.java",
            "LogPaths.show(lastError)", "onFailure("));

    private static String[] sink(String file, String... markers) {
        String[] entry = new String[markers.length + 1];
        entry[0] = file;
        System.arraycopy(markers, 0, entry, 1, markers.length);
        return entry;
    }

    private static String[] sink(String file, String marker) {
        return new String[] {file, marker};
    }

    private static final Pattern LOGGER_CALL = Pattern.compile("LOGGER\\.(?:info|warn|error|debug)\\s*\\(");
    /** Every {@code catch} parameter of the file: a name is a throwable only because a catch clause declared it. */
    private static final Pattern CATCH = Pattern.compile("\\bcatch\\s*\\(([^)]*)\\)");
    /** An exception turned into text: the message is the second leak channel, so it needs the redactor too. */
    private static final Pattern EXCEPTION_TEXT =
        Pattern.compile("\\b([a-zA-Z_$][\\w$]*)\\s*\\.\\s*(?:toString|getMessage)\\s*\\(\\s*\\)");
    /** Path-typed values, including the reason strings built by concatenating a library directory. */
    private static final Pattern PATH_VALUE = Pattern.compile("\\b(resolved(?:Model|Loader)Dll|libRes\\.reason\\(\\))");
    private static final Pattern BARE_NAME = Pattern.compile("[a-zA-Z_$][\\w$]*");

    /** How far back a statement is looked at for the redactor; a call is never longer on the line that builds it. */
    private static final int STATEMENT_LOOKBACK = 200;

    @Test
    public void guardedFilesNeverHandPathBearingTextToTheLogger() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String relative : GUARDED) {
            Path file = Path.of(relative);
            assertTrue(Files.isRegularFile(file), "the contract lost a file it guards: " + file.toAbsolutePath());
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Set<String> caught = caughtNames(source);
            for (int[] call : loggerCalls(source)) {
                String text = source.substring(call[0], call[1]);
                String last = lastArgument(text);
                if (BARE_NAME.matcher(last).matches() && caught.contains(last) && !text.contains("LogPaths.stack(")) {
                    offenders.add("raw throwable " + relative + ":" + lineOf(source, call[0]) + " " + flatten(text));
                }
            }
            for (Matcher m = EXCEPTION_TEXT.matcher(source); m.find(); ) {
                if (caught.contains(m.group(1)) && !containedByARedaction(source, m.start())) {
                    offenders.add("exception text " + relative + ":" + lineOf(source, m.start()) + " " + snippet(source, m.start(), m.end()));
                }
            }
            for (Matcher m = PATH_VALUE.matcher(source); m.find(); ) {
                // A Path field declaration, getter or assignment prints nothing by itself; only a value concatenated into
                // a string is on its way to a log or a screen.
                if (isConcatenatedInto(source, m.start(), m.end()) && !containedByARedaction(source, m.start())) {
                    offenders.add("vendor path " + relative + ":" + lineOf(source, m.start()) + " " + snippet(source, m.start(), m.end()));
                }
            }
        }
        assertEquals(List.of(), offenders, "a filesystem or library failure would print its path to latest.log");
    }

    @Test
    public void statusSinksStillRedactAtTheSink() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String[] sink : SINKS) {
            String source = Files.readString(Path.of(sink[0]), StandardCharsets.UTF_8);
            if (!source.contains(sink[1])) {
                missing.add(sink[0] + " lost its sink redaction (expected \"" + sink[1] + "\")");
            }
        }
        assertEquals(List.of(), missing, "a stored status string would reach F3 or the settings page unredacted");
    }

    @Test
    public void everyGuardedFileCanReachTheRedactor() throws IOException {
        for (String relative : GUARDED) {
            String source = Files.readString(Path.of(relative), StandardCharsets.UTF_8);
            assertTrue(source.contains("import io.github.andrexarlay.vulkanbridge.platform.LogPaths;"),
                relative + " is guarded but does not import the redactor");
        }
    }

    /** The parameter name of every {@code catch} clause, so Rule A cannot be dodged by calling the variable something else. */
    private static Set<String> caughtNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = CATCH.matcher(source);
        while (m.find()) {
            String declaration = m.group(1).strip();
            int space = declaration.lastIndexOf(' ');
            if (space >= 0) {
                names.add(declaration.substring(space + 1));
            }
        }
        return names;
    }

    /** True when the statement that builds this value runs it through the redactor or hands it to a sink that does. */
    private static boolean containedByARedaction(String source, int offset) {
        int from = Math.max(0, offset - STATEMENT_LOOKBACK);
        String head = source.substring(from, offset);
        int cut = -1;
        for (char boundary : new char[] {';', '{', '}'}) {
            cut = Math.max(cut, head.lastIndexOf(boundary));
        }
        String statement = head.substring(cut + 1);
        if (statement.contains("LogPaths.")) {
            return true;
        }
        for (String[] sink : SINKS) {
            for (int i = 2; i < sink.length; i++) {
                if (statement.contains(sink[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the value sits on a concatenation, which is the only way it becomes text. */
    private static boolean isConcatenatedInto(String source, int start, int end) {
        String before = source.substring(Math.max(0, start - 6), start).stripTrailing();
        String after = source.substring(end, Math.min(source.length(), end + 6)).stripLeading();
        return before.endsWith("+") || after.startsWith("+");
    }

    /** Start and end offset of every {@code LOGGER.x(...)} call in {@code source}, with the parentheses counted, not matched. */
    private static List<int[]> loggerCalls(String source) {
        List<int[]> calls = new ArrayList<>();
        Matcher m = LOGGER_CALL.matcher(source);
        while (m.find()) {
            int depth = 0;
            for (int i = m.end() - 1; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        calls.add(new int[] {m.start(), i + 1});
                        break;
                    }
                }
            }
        }
        return calls;
    }

    /** The text after the last comma of the call, at the call's own parenthesis depth, with nothing trimmed inside it. */
    private static String lastArgument(String call) {
        int depth = 0;
        for (int i = call.length() - 2; i >= 0; i--) {
            char c = call.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return call.substring(i + 1, call.length() - 1).strip();
            }
        }
        return "";
    }

    private static String snippet(String source, int start, int end) {
        int from = Math.max(0, start - 60);
        return flatten(source.substring(from, Math.min(source.length(), end + 20)));
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String flatten(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
