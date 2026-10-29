package io.github.andrexarlay.vulkancompat.rt.world;

/** One host completion proof permits one legacy update of shared mapped inputs. */
final class LegacySceneLifetime {
    private long recording;
    private boolean prepared;

    void prepareAfterHostDrain(Runnable collectRetired) {
        if (recording != 0) throw new IllegalStateException("legacy scene recording has no completion/discard proof");
        prepared = false;
        collectRetired.run();
        prepared = true;
    }

    void begin(long commandBuffer) {
        if (commandBuffer == 0 || recording != 0 || !prepared)
            throw new IllegalStateException("legacy scene inputs require host drain before update");
        prepared = false;
        recording = commandBuffer;
    }

    void release(long commandBuffer) {
        if (recording == commandBuffer) recording = 0;
    }

    void requireReleased() {
        if (recording != 0) throw new IllegalStateException("legacy scene recording is still in use");
    }
}
