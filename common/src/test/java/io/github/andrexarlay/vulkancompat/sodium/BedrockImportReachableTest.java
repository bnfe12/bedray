package io.github.andrexarlay.vulkancompat.sodium;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Bedrock RTX material-pack importer must stay reachable from the single Bedray settings screen. The old two-page
 * registration still carries the open-folder and import buttons, but that page is now only the draft's private Config;
 * a player reaches every setting through {@link BedrayConfigScreen}, so both actions have to be wired there too. Without
 * this, the import feature ships but no player can find it.
 */
class BedrockImportReachableTest {
    private static String java(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat", relative)).replace("\r\n", "\n");
    }

    @Test
    void theSettingsScreenExposesTheBedrockPackActions() throws Exception {
        String screen = java("sodium/BedrayConfigScreen.java");
        assertTrue(screen.contains("BedrockRtxPackImporter.importAllAsync()"),
            "the new settings screen must offer the Bedrock RTX pack import");
        assertTrue(screen.contains("BedrockRtxPackImporter.sourceDir("),
            "the new settings screen must offer opening the Bedrock RTX drop folder");
    }
}