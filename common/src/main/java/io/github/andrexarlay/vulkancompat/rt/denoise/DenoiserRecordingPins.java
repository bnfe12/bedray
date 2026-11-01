package io.github.andrexarlay.vulkancompat.rt.denoise;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pins a complete guide generation, including recordings that never reach the pack pass. */
final class DenoiserRecordingPins {
    private final Set<Long> commands = new LinkedHashSet<>();

    void retain(long command) {
        if (command == 0) throw new IllegalArgumentException("denoiser preparation needs a recording");
        commands.add(command);
    }

    void release(long command) { commands.remove(command); }

    void requireUnreferenced() {
        requireUnreferenced("denoiser resource replacement");
    }

    void requireUnreferenced(String operation) {
        if (!commands.isEmpty())
            throw new IllegalStateException(operation + " requires completion/discard; pendingCommands="
                + commands.stream().map(command -> "0x" + Long.toUnsignedString(command, 16)).toList());
    }

    /** Caller has submitted and completed all host work, and closed or discarded open recordings. */
    void clearAfterHostDrain() { commands.clear(); }
}
