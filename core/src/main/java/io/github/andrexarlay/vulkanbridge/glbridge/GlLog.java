package io.github.andrexarlay.vulkanbridge.glbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single log format of the GL bridge: {@code [VKB-GLBRIDGE] stage=N key=value ...}. */
final class GlLog {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-GLBRIDGE");

    private GlLog() {}

    static void line(int stage, String message) {
        LOGGER.info("[VKB-GLBRIDGE] stage={} {}", stage, message);
    }

    static void error(int stage, String message, Throwable t) {
        LOGGER.error("[VKB-GLBRIDGE] stage={} {}", stage, message, t);
    }
}
