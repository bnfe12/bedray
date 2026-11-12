package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Name of the settings file, {@code config/bedray.toml}. Readers that parse it before the config class loads call {@link #resolve}. */
public final class ConfigFiles {
    public static final String NAME = "bedray.toml";

    private ConfigFiles() {}

    public static Path resolve(Path file) {
        return file;
    }

    public static File resolve(File file) {
        return file;
    }
}
