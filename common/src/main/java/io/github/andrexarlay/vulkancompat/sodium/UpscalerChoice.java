package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;

/** Player-facing choices. The persisted config keeps its backend and FSR version keys. */
enum UpscalerChoice {
    BILINEAR(UpscalerBackend.BILINEAR),
    FSR_AUTO(UpscalerBackend.FSR, FsrVersion.AUTO),
    FSR_4(UpscalerBackend.FSR, FsrVersion.FSR_4),
    FSR_3_1(UpscalerBackend.FSR, FsrVersion.FSR_3_1),
    FSR_2(UpscalerBackend.FSR, FsrVersion.FSR_2),
    DLSS(UpscalerBackend.DLSS),
    XESS(UpscalerBackend.XESS),
    FSR1(UpscalerBackend.FSR1),
    NIS(UpscalerBackend.NIS);

    private final UpscalerBackend backend;
    private final FsrVersion version;

    UpscalerChoice(UpscalerBackend backend) {
        this(backend, null);
    }

    UpscalerChoice(UpscalerBackend backend, FsrVersion version) {
        this.backend = backend;
        this.version = version;
    }

    UpscalerBackend backend() {
        return backend;
    }

    FsrVersion version() {
        return version;
    }

    static UpscalerChoice of(UpscalerBackend backend, FsrVersion version) {
        if (backend == null) return BILINEAR;
        if (backend != UpscalerBackend.FSR) return valueOf(backend.name());
        return switch (version == null ? FsrVersion.AUTO : version) {
            case AUTO -> FSR_AUTO;
            case FSR_4 -> FSR_4;
            case FSR_3_1 -> FSR_3_1;
            case FSR_2 -> FSR_2;
        };
    }
}
