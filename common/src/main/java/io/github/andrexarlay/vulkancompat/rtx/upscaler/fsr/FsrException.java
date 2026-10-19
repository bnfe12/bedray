package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

/** A FidelityFX call or setup step failed; {@code stage} is the token used in {@code [VKB-FSR] fsr=FAILED stage=...}. */
public final class FsrException extends RuntimeException {
    private final String stage;

    public FsrException(String stage, String message, Throwable cause) {
        super(stage + ": " + message, cause);
        this.stage = stage;
    }

    public String stage() {
        return stage;
    }
}
