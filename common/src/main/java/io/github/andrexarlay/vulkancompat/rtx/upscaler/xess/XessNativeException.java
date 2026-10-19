package io.github.andrexarlay.vulkancompat.rtx.upscaler.xess;

/** libxess.dll could not be loaded or a downcall failed (the cause is always attached, never swallowed). */
public final class XessNativeException extends RuntimeException {
    public XessNativeException(String message) {
        super(message);
    }

    public XessNativeException(String message, Throwable cause) {
        super(message, cause);
    }
}
