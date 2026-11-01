package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Releases vendor objects only after host completion and acknowledged native destruction. */
public final class UpscalerContextRetirement {
    private UpscalerContextRetirement() {}

    /** Assign the result back to the sole owning field; either failure leaves that field unchanged. */
    public static <T> T release(T handle, Runnable completionProof, Consumer<T> destroy) {
        if (handle == null) return null;
        completionProof.run();
        destroy.accept(handle);
        return null;
    }

    /** Primitive handle variant used by NGX features. */
    public static long release(long handle, Runnable completionProof, LongConsumer destroy) {
        if (handle == 0) return 0;
        completionProof.run();
        destroy.accept(handle);
        return 0;
    }
}
