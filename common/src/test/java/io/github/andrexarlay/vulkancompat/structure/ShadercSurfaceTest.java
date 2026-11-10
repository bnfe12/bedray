package io.github.andrexarlay.vulkancompat.structure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-11: the migration target is AOT Slang, but runtime GLSL -> SPIR-V through Shaderc is still load-bearing in
 * code that is not the Slang renderer (the Iris pack host and an extension probe). The inventory is frozen here
 * instead of being re-guessed every audit; the FrameGen producer left it when `passes/fg_prepare.slang` landed and
 * the Iris depth converter left it when `passes/iris_depth.slang` replaced `iris_depth.comp`, so the resource tree
 * now ships no first-party GLSL at all.
 *
 * <p>Ways this silently breaks: a new pass reaches for Shaderc because it is the shortest way to get a compute
 * shader, and every player machine then needs the Shaderc native library at runtime; or the Slang path grows a
 * runtime GLSL compile and inherits exactly the driver-dependent behaviour AOT was supposed to remove.
 */
class ShadercSurfaceTest {

    @Test
    void companionRendererAlsoUsesAotAndShipsNoGlsl() throws IOException {
        Path companion = Path.of("..", "compute-common", "src", "main");
        assertTrue(Files.isDirectory(companion));
        try (Stream<Path> files = Files.walk(companion)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                assertFalse(name.matches(".*\\.(comp|frag|vert|glsl)$"), "first-party GLSL: " + file);
                if (name.endsWith(".java")) assertFalse(Files.readString(file).contains("org.lwjgl.util.shaderc"),
                    "NoRT must use the host AOT service: " + file);
            }
        }
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/shaders/slang/nort"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("#include") || source.contains("#ifdef"),
                    "NoRT specializes typed modules, not preprocessor variants: " + file);
            }
        }
    }

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Path SLANG_PATH =
        SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/slang");
    private static final Path RENDERER =
        SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");
    private static final Path RT_SHADERS = Path.of("src", "main", "resources", "shaders", "glsl");
    private static final Path WORLD_PACKAGE =
        SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/world");

    @Test
    void runtimeCompilationRemainsLimitedToUserPacks() throws IOException {
        TreeSet<String> actual = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("Shaderc.shaderc_") || source.contains("import org.lwjgl.util.shaderc.Shaderc;"))
                    actual.add(SOURCES.relativize(file).toString().replace('\\', '/'));
            }
        }
        assertEquals(new TreeSet<>(List.of(
            "io/github/andrexarlay/vulkancompat/shaderhost/glsl/IrisSourcePreprocessor.java",
            "io/github/andrexarlay/vulkancompat/shaderhost/glsl/SpirvCompiler.java",
            "io/github/andrexarlay/vulkancompat/shaderhost/glsl/VulkanGlslPackCompiler.java")), actual,
            "built-in renderer and upscaler passes use AOT Slang; user packs remain runtime inputs");
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

    /**
     * The legacy renderer was deleted, not disabled. Every one of these files is a way back into a path the owner
     * has forbidden, so a re-add has to be a deliberate decision that updates this list.
     */
    @Test
    void theLegacyRendererStaysDeleted() throws IOException {
        TreeSet<String> shipped = new TreeSet<>();
        if (Files.isDirectory(RT_SHADERS)) {
            try (Stream<Path> files = Files.list(RT_SHADERS)) {
                files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .forEach(shipped::add);
            }
        }
        assertEquals(new TreeSet<String>(), shipped,
            "no first-party GLSL ships any more: fg_prepare and the Iris depth converter are AOT Slang passes, and"
                + " the spatial upscaler kernels are third-party host code");
        TreeSet<String> gone = new TreeSet<>();
        for (String name : new String[] {"RtPipeline.java", "RtShaderCompiler.java", "RtShaderDiskCache.java",
            "RtPathTracerPass.java", "RtRayEffectsPass.java", "RtCompositePipeline.java", "RtVolumetricPass.java"}) {
            if (Files.exists(WORLD_PACKAGE.resolve(name))) {
                gone.add(name);
            }
        }
        assertTrue(gone.isEmpty(), "the legacy pipeline, its passes and its runtime compiler are deleted: " + gone);
        assertFalse(Files.exists(SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/poc/RtTestPipeline.java")),
            "the ray-tracing proof of concept is gone with them");
    }

    /** Renderer code loads precompiled Slang programs. */
    @Test
    void theRtPackageDoesNotUseShaderc() throws IOException {
        TreeSet<String> users = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt"))) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                if (Files.readString(file).contains("org.lwjgl.util.shaderc")) {
                    users.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(users.isEmpty(), "renderer code does not compile GLSL at runtime: " + users);
    }
}
