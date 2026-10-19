package io.github.andrexarlay.vulkancompat.rt.denoise;

/** A vkb_nrd.dll call returned a non-zero VkbNrdResult; the message carries vkb_nrd_get_last_error_string(). */
public final class NrdException extends RuntimeException {
    private final String stage;
    private final int code;

    public NrdException(String stage, int code, String detail) {
        super("stage=" + stage + " code=" + code + " " + detail);
        this.stage = stage;
        this.code = code;
    }

    public NrdException(String stage, String detail, Throwable cause) {
        super("stage=" + stage + " " + detail, cause);
        this.stage = stage;
        this.code = -1;
    }

    public String stage() {
        return stage;
    }

    public int code() {
        return code;
    }
}
