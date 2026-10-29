package io.github.andrexarlay.vulkancompat.structure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-11: the migration target is AOT Slang, but runtime GLSL -> SPIR-V through Shaderc is still load-bearing in
 * code that is not the Slang renderer. The register claimed one file was left; a scan of the module found nine, so
 * the inventory is frozen here instead of being re-guessed every audit.
 *
 * <p>Ways this silently breaks: a new pass reaches for Shaderc because it is the shortest way to get a compute
 * shader, and every player machine then needs the Shaderc native library at runtime; or the Slang path grows a
 * runtime GLSL compile and inherits exactly the driver-dependent behaviour AOT was supposed to remove.
 */
class ShadercSurfaceTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Path SLANG_PATH =
        SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/slang");
    private static final Path RENDERER =
        SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");

    /** Who may call the compiler today, and why each one is still here. */
    private static final List<String> SURFACE = List.of(
        "io/github/andrexarlay/vulkancompat/rt/poc/RtTestPipeline.java",
        "io/github/andrexarlay/vulkancompat/rt/world/RtShaderCompiler.java",
        "io/github/andrexarlay/vulkancompat/rtx/framegen/FrameGenGpu.java",
        "io/github/andrexarlay/vulkancompat/rtx/upscaler/VelocityPipeline.java",
        "io/github/andrexarlay/vulkancompat/rtx/upscaler/spatial/SpatialComputePass.java",
        "io/github/andrexarlay/vulkancompat/shaderhost/glsl/IrisSourcePreprocessor.java",
        "io/github/andrexarlay/vulkancompat/shaderhost/glsl/SpirvCompiler.java",
        "io/github/andrexarlay/vulkancompat/shaderhost/glsl/VulkanGlslPackCompiler.java",
        "io/github/andrexarlay/vulkancompat/shaderhost/iris/IrisDepthConverter.java");

    @Test
    void runtimeGlslCompilationStaysOnTheFrozenSurface() throws IOException {
        TreeSet<String> users = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                if (source.contains("Shaderc.shaderc_") || source.contains("import org.lwjgl.util.shaderc.Shaderc;")) {
                    users.add(SOURCES.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(new TreeSet<>(SURFACE), users,
            "the legacy renderer (RtShaderCompiler and its RtPipeline/RtCompositePipeline/NrdPackPipeline/"
                + "OpacityMicromapCache callers), the upscaler and frame-gen passes, the PoC entry and the Iris pack"
                + " host are the whole runtime-GLSL surface; the pack host cannot be precompiled because its GLSL is"
                + " user-authored, the rest is scheduled for Slang. A new entry has to be that decision made out loud.");
    }

    /** The Slang renderer is the one place where a runtime GLSL compile is a regression, not a shortcut. */
    @Test
    void theSlangPathNeverReachesForShaderc() throws IOException {
        assertTrue(Files.isDirectory(SLANG_PATH), "the Slang runtime package must stay discoverable: "
            + SLANG_PATH.toAbsolutePath());
        assertTrue(Files.isRegularFile(RENDERER), "the mode switch lives in " + RENDERER);
        TreeSet<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SLANG_PATH)) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                if (source.contains("Shaderc.shaderc_") || source.contains("org.lwjgl.util.shaderc")) {
                    offenders.add(SOURCES.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        String frameRenderer = Files.readString(RENDERER);
        if (frameRenderer.contains("Shaderc.shaderc_") || frameRenderer.contains("org.lwjgl.util.shaderc")) {
            offenders.add(SOURCES.relativize(RENDERER).toString().replace('\\', '/'));
        }
        assertTrue(offenders.isEmpty(),
            "the Slang path compiles AOT SPIR-V out of build/generated/slang; a runtime GLSL compile here would "
                + "make a frame depend on Shaderc being present: " + offenders);
    }
}
