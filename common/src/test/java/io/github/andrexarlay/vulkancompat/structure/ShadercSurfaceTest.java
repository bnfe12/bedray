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
 * code that is not the Slang renderer. Seven compiler users remain after the legacy renderer was deleted, so
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
    private static final Path RT_SHADERS = Path.of("src", "main", "resources", "shaders", "glsl");
    private static final Path WORLD_PACKAGE =
        SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt/world");


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
        try (Stream<Path> files = Files.list(RT_SHADERS)) {
            TreeSet<String> shipped = new TreeSet<>(files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toList()));
            assertEquals(new TreeSet<>(List.of("fg_prepare.comp", "iris_depth.comp")), shipped,
                "FrameGen and the Iris depth converter are the only runtime-compiled GLSL left in the resource tree");
        }
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

    /** The one Shaderc user inside the RT package is a capability probe, and it must stay the only one. */
    @Test
    void theOnlyShadercUserInTheRtPackageIsTheProbe() throws IOException {
        TreeSet<String> users = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES.resolve("io/github/andrexarlay/vulkancompat/rt"))) {
            for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                if (Files.readString(file).contains("org.lwjgl.util.shaderc")) {
                    users.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(new TreeSet<>(List.of("RtExtensionProbe.java")), users,
            "the opacity-micromap probe may compile GLSL at runtime; a renderer pass may not");
    }
}
