package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerContextRetirement;
import java.util.function.LongConsumer;

/** Sole owner of a feature, including partial creation and partially acknowledged retirement. */
public final class DlssFeatureOwner {
    private long handle;
    private boolean retiring;

    public long handle() { return handle; }
    public boolean empty() { return handle == 0; }
    public boolean retiring() { return retiring; }
    public boolean usable() { return handle != 0 && !retiring; }

    public void adopt(long created, boolean success) {
        if (!empty()) throw new IllegalStateException("Cannot overwrite a retained DLSS feature");
        if (success && created == 0) throw new IllegalStateException("SDK returned success without a DLSS feature");
        handle = created;
        retiring = !success;
    }

    public void retire(Runnable completionProof, LongConsumer release) {
        // Mark first: even a failed proof must prevent evaluate/reuse of an owner being retired.
        retiring = true;
        handle = UpscalerContextRetirement.release(handle, completionProof, release);
        retiring = false;
    }

    public void transferTo(DlssFeatureOwner target) {
        if (target == this || !target.empty()) throw new IllegalStateException("DLSS retirement slot is occupied");
        target.handle = handle;
        target.retiring = true;
        handle = 0;
        retiring = false;
    }
}
