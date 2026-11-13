package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostState;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.IrisPackSettings;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackScanner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3: the real BSL pack must translate and compile offline through the raster-core graph (slangc when available, shaderc
 * otherwise). Skipped when no installed BSL is found; roots come from {@code -Dvkrtx.test.instanceRoots=a;b}.
 */
class IrisBslCompileTest {

    private static Path findBsl() throws IOException {
        String prop = System.getProperty("vkrtx.test.instanceRoots");
        List<Path> roots = new ArrayList<>();
        if (prop != null && !prop.isBlank()) {
            for (String s : prop.split(";")) {
                roots.add(Path.of(s.trim()));
            }
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path inst : ds) {
                    Path sp = inst.resolve("minecraft").resolve("shaderpacks");
                    if (!Files.isDirectory(sp)) {
                        continue;
                    }
                    try (DirectoryStream<Path> packs = Files.newDirectoryStream(sp, "BSL*.zip")) {
                        for (Path pack : packs) {
                            return pack;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Test
    void bslTranslatesAndCompiles() throws IOException {
        Path bsl = findBsl();
        Assumptions.assumeTrue(bsl != null, "no installed BSL pack (set -Dvkrtx.test.instanceRoots)");
        try (PackSource src = PackSource.open(bsl, ScanLimits.DEFAULT)) {
            PackReport report = ShaderpackScanner.scan(src);
            IrisPackSettings settings = IrisPackSettings.parse(src, report);
            IrisPassGraph.FullResult res = IrisPassGraph.build(src, report, settings);
            assertTrue(res.success(), () -> "BSL refused: " + res.status().detail());
            assertEquals(ShaderHostState.READY, res.status().state());
            assertTrue(res.passes().size() >= 5, "expected shadow+gbuffers+composite+final, got " + res.passes().size());
            for (IrisPassGraph.FullPass pass : res.passes()) {
                assertNotNull(pass.vertexSpirv(), pass.name());
                assertNotNull(pass.fragmentSpirv(), pass.name());
                assertTrue(SpirvCompiler.isValidSpirv(pass.vertexSpirv()), pass.name() + " vertex SPIR-V invalid");
                assertTrue(SpirvCompiler.isValidSpirv(pass.fragmentSpirv()), pass.name() + " fragment SPIR-V invalid");
            }
            System.out.println("[IrisBslCompileTest] BSL compiled " + res.passes().size() + " passes; skipped: " + res.skipped());
        }
    }
}