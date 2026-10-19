package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.IOException;

/** The pack was refused (unsafe path, limit exceeded, unreadable archive). The message names the pack entry when there is one. */
public final class PackScanException extends IOException {
    public PackScanException(String message) {
        super(message);
    }

    public PackScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
