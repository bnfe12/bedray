package io.github.andrexarlay.vulkancompat.structure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule: module common contains no loader API. Scans this module's main sources (working dir = module dir). */
class NoLoaderImportsTest {
    @Test
    void commonSourcesHaveNoLoaderImports() throws IOException {
        Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), "test must run with the common module as working directory: " + root.toAbsolutePath());
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String line : Files.readAllLines(file)) {
                    String t = line.trim();
                    if (t.startsWith("import net.neoforged.") || t.startsWith("import net.fabricmc.")
                            || t.contains("net.neoforged.fml") || t.contains("net.fabricmc.loader")) {
                        offenders.add(root.relativize(file) + ": " + t);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "loader API in common: " + offenders);
    }
}
