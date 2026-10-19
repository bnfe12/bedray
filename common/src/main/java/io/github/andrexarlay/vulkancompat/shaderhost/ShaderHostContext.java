package io.github.andrexarlay.vulkancompat.shaderhost;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a host may look at while probing. A host never owns device creation (the Iris dual-path design, "Shared core boundary"):
 * it only sees the extension set of the live device, and only once the device exists.
 *
 * @param gameDir          game directory (shaderpacks/ and config/ are resolved below it, never outside)
 * @param deviceExtensions extensions enabled on the live VkDevice; empty before the device is created (early bootstrap)
 */
@Experimental("I0")
public record ShaderHostContext(Path gameDir, Optional<Set<String>> deviceExtensions) {
    public ShaderHostContext {
        Objects.requireNonNull(gameDir, "gameDir");
        gameDir = gameDir.toAbsolutePath().normalize();
        deviceExtensions = deviceExtensions == null ? Optional.empty() : deviceExtensions.map(Set::copyOf);
    }

    public static ShaderHostContext beforeDevice(Path gameDir) {
        return new ShaderHostContext(gameDir, Optional.empty());
    }

    public Path shaderpacksDir() {
        return gameDir.resolve("shaderpacks");
    }
}
