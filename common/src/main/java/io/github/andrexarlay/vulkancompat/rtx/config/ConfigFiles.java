package io.github.andrexarlay.vulkancompat.rtx.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Names of the settings file and its one-time migration from the pre-release name. The file is {@code config/bedray.toml};
 * if it does not exist and the old {@code config/vulkan_rtx.toml} sits next to it, the old file is copied (not moved or
 * deleted) and the copy is logged. Every reader that parses the file before the config class is loaded calls
 * {@link #resolve(Path)}, so the result is the same everywhere.
 */
public final class ConfigFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Config");

    public static final String NAME = "bedray.toml";
    public static final String LEGACY_NAME = "vulkan_rtx.toml";

    private ConfigFiles() {}

    /** Returns {@code file}; when it is missing and a legacy file exists in the same folder, copies the legacy file first. */
    public static Path resolve(Path file) {
        if (file == null || Files.exists(file)) {
            return file;
        }
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null || !NAME.equals(file.getFileName().toString())) {
            return file;
        }
        Path legacy = parent.resolve(LEGACY_NAME);
        if (Files.isRegularFile(legacy)) {
            try {
                Files.copy(legacy, file, StandardCopyOption.COPY_ATTRIBUTES);
                LOGGER.info("[Bedray] settings migrated: copied {} to {} (the old file is left in place)", legacy, file);
            } catch (IOException e) {
                LOGGER.warn("[Bedray] could not copy old settings {} to {}: {}", legacy, file, e.toString());
            }
        }
        return file;
    }

    public static File resolve(File file) {
        resolve(file.toPath());
        return file;
    }
}
