package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

/** The DLSS wrapper DLL could not be loaded or a native call itself failed (not an NGX error code: those are {@link DlssNative.Status}). */
public final class DlssNativeException extends RuntimeException {
    public DlssNativeException(String message, Throwable cause) {
        super(message, cause);
    }
}
