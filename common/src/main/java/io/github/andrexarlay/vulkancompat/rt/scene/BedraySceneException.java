package io.github.andrexarlay.vulkancompat.rt.scene;

/** A failure reported by the native GPU scene, with the exact {@code BedraySceneStatus} and the scene's message. */
public final class BedraySceneException extends RuntimeException {
    private final String operation;
    private final int status;

    public BedraySceneException(String operation, int status, String message, Throwable cause) {
        super(buildMessage(operation, status, message), cause);
        this.operation = operation;
        this.status = status;
    }

    /** Native call that failed, for example {@code update}. */
    public String operation() {
        return operation;
    }

    /** {@code BedraySceneStatus} value returned by the native call, or -1 when the call itself could not be made. */
    public int status() {
        return status;
    }

    private static String buildMessage(String operation, int status, String message) {
        String text = message == null ? "" : message.strip();
        String head = "bedray_scene " + operation + " failed: " + statusName(status);
        return text.isEmpty() ? head : head + " (" + text + ")";
    }

    private static String statusName(int status) {
        return switch (status) {
            case BedraySceneLayouts.STATUS_OK -> "OK";
            case BedraySceneLayouts.STATUS_INVALID_ARGUMENT -> "INVALID_ARGUMENT";
            case BedraySceneLayouts.STATUS_UNSUPPORTED -> "UNSUPPORTED";
            case BedraySceneLayouts.STATUS_VULKAN -> "VULKAN";
            case BedraySceneLayouts.STATUS_OUT_OF_MEMORY -> "OUT_OF_MEMORY";
            case BedraySceneLayouts.STATUS_DEVICE_LOST -> "DEVICE_LOST";
            case BedraySceneLayouts.STATUS_ABI_MISMATCH -> "ABI_MISMATCH";
            case BedraySceneLayouts.STATUS_INTERNAL -> "INTERNAL";
            case BedraySceneLayouts.STATUS_IN_USE -> "IN_USE";
            case BedraySceneLayouts.STATUS_STALE -> "STALE";
            case BedraySceneLayouts.STATUS_UNKNOWN_ID -> "UNKNOWN_ID";
            case BedraySceneLayouts.STATUS_LIMIT -> "LIMIT";
            default -> "status " + status;
        };
    }
}
