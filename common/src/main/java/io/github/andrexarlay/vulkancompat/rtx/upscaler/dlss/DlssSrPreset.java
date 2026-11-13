package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

/** NVIDIA Super Resolution model hints. AUTO lets NGX or Streamline choose per quality mode. */
public enum DlssSrPreset {
    AUTO(0), J(10), K(11), L(12), M(13);

    private final int id;

    DlssSrPreset(int id) {
        this.id = id;
    }

    public int nativeValue() {
        return id;
    }
}
