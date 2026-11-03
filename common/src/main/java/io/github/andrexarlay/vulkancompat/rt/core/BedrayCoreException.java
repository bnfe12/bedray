package io.github.andrexarlay.vulkancompat.rt.core;

/** A failure reported by the native Bedray core, with the exact {@code BedrayStatus} and the core's message. */
public final class BedrayCoreException extends RuntimeException {
    private final String operation;
    private final int status;

    public BedrayCoreException(String operation, int status, String message, Throwable cause) {
        super(buildMessage(operation, status, message), cause);
        this.operation = operation;
        this.status = status;
    }

    /** Native call that failed, for example {@code register_program}. */
    public String operation() {
        return operation;
    }

    /** {@code BedrayStatus} value returned by the native call, or -1 when the call itself could not be made. */
    public int status() {
        return status;
    }

    private static String buildMessage(String operation, int status, String message) {
        String text = message == null ? "" : message.strip();
        String head = "bedray_core " + operation + " failed: " + statusName(status);
        return text.isEmpty() ? head : head + " (" + text + ")";
    }

    static String statusName(int status) {
        return switch (status) {
            case BedrayCoreLayouts.STATUS_OK -> "OK";
            case BedrayCoreLayouts.STATUS_INVALID_ARGUMENT -> "INVALID_ARGUMENT";
            case BedrayCoreLayouts.STATUS_UNSUPPORTED -> "UNSUPPORTED";
            case BedrayCoreLayouts.STATUS_VULKAN -> "VULKAN";
            case BedrayCoreLayouts.STATUS_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
            case BedrayCoreLayouts.STATUS_DEVICE_LOST -> "DEVICE_LOST";
            case BedrayCoreLayouts.STATUS_ABI_MISMATCH -> "ABI_MISMATCH";
            case BedrayCoreLayouts.STATUS_INTERNAL -> "INTERNAL";
            default -> "status " + status;
        };
    }
}
