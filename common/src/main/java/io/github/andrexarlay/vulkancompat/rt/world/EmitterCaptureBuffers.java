package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingResourceOwner;
import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingResourceOwner.Generation;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;

import java.nio.ByteBuffer;

/** Immutable published light lists, borrowed until exact recording completion/discard. */
final class EmitterCaptureBuffers implements AutoCloseable {
    interface Storage {
        RtMemory.Buf create(long bytes);
        void write(RtMemory.Buf buffer, ByteBuffer data);
        void destroy(RtMemory.Buf buffer);
    }

    private final Storage storage;
    private final RecordingResourceOwner<RtMemory.Buf[]> owner;
    private Generation<RtMemory.Buf[]> current;

    EmitterCaptureBuffers(RtMemory memory) {
        this(new Storage() {
            public RtMemory.Buf create(long bytes) {
                return memory.createBuffer("rt_emitters", bytes,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
            }
            public void write(RtMemory.Buf buffer, ByteBuffer data) {
                MemoryUtil.memByteBuffer(buffer.mapped, data.remaining()).put(data);
            }
            public void destroy(RtMemory.Buf buffer) { memory.destroy(buffer); }
        });
    }

    EmitterCaptureBuffers(Storage storage) {
        this.storage = java.util.Objects.requireNonNull(storage);
        owner = new RecordingResourceOwner<>(buffers -> {
            if (buffers[0] == null) return;
            storage.destroy(buffers[0]);
            buffers[0] = null;
        });
    }

    RtMemory.Buf current() { return current == null ? null : current.value()[0]; }

    /** A complete copy precedes publication; pending readers keep the old generation. */
    private final EmitterTable table = new EmitterTable();
    private EmitterTable.Publication publication;
    EmitterTable.Publication publication() { return publication; }

    void publish(float[][] records, int count) { publish(records, count, 0, 0, 0); }

    void publish(float[][] records, int count, int originX, int originY, int originZ) {
        publish(records, count, originX, originY, originZ, 0);
    }

    void publish(float[][] records, int count, int originX, int originY, int originZ, int materialRevision) {
        var prepared = table.prepare(records, count, originX, originY, originZ, materialRevision);
        // Register before allocation, preserving ownership even when copy and cleanup both fail.
        var next = owner.create(new RtMemory.Buf[1], 0);
        try {
            if (count > 0) {
                next.value()[0] = storage.create(prepared.data().remaining());
                storage.write(next.value()[0], prepared.data().duplicate());
            }
        } catch (RuntimeException | Error failure) {
            try { owner.retire(next); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        var old = current;
        current = next;
        table.commit(prepared);
        publication = prepared;
        if (old != null) owner.retire(old);
    }

    void retain(long command) {
        var recording = owner.begin(command);
        if (current != null) owner.pin(recording, current);
    }

    /** Caller supplies proof through the exact host recording ticket. */
    void releaseRecording(long command) { owner.releaseAfterProof(owner.recording(command)); }

    @Override public void close() {
        owner.close();
        current = null;
        publication = null;
        table.clear();
    }
}
