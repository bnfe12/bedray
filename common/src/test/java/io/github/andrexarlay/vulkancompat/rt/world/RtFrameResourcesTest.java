package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RtFrameResourcesTest {
    static final class Allocator implements RtFrameResources.Allocator {
        long serial;
        int imageCalls, motionCalls, failImageCall;
        final Set<Long> images = new HashSet<>(), buffers = new HashSet<>(), failedDestroy = new HashSet<>();
        public RtMemory.Img image(String name, int w, int h, int format, int usage) {
            if (++imageCalls == failImageCall) throw new OutOfMemoryError("injected image allocation");
            long id = ++serial;
            images.add(id);
            return new RtMemory.Img(id, id, 0, w, h, (long) w * h * 4);
        }
        public RtMemory.Buf motion(long bytes) {
            motionCalls++;
            long id = ++serial;
            buffers.add(id);
            return new RtMemory.Buf(id, 0, bytes, id * 4096, 0, false);
        }
        public void destroy(RtMemory.Img image) {
            assertNotNull(image, "RtMemory.destroy is not null-safe");
            if (failedDestroy.contains(image.image)) throw new IllegalStateException("destroy image " + image.image);
            assertTrue(images.remove(image.image), "image freed exactly once");
        }
        public void destroy(RtMemory.Buf buffer) {
            assertNotNull(buffer, "RtMemory.destroy is not null-safe");
            if (failedDestroy.contains(buffer.handle)) throw new IllegalStateException("destroy buffer " + buffer.handle);
            assertTrue(buffers.remove(buffer.handle), "buffer freed exactly once");
        }
    }

    @Test void firstFrameAndRepeatedCloseNeverDestroyNull() {
        var allocator = new Allocator();
        var resources = new RtFrameResources(allocator);
        resources.ensureOutput(64, 32);
        resources.ensureMotion(64, 32);
        assertEquals(3, allocator.images.size());
        assertEquals(1, allocator.buffers.size());
        resources.ensureOutput(64, 32);
        resources.ensureMotion(32, 16);
        assertEquals(3, allocator.imageCalls);
        assertEquals(1, allocator.motionCalls);
        resources.close(); resources.close();
        assertTrue(allocator.images.isEmpty());
        assertTrue(allocator.buffers.isEmpty());
    }

    @Test void partialAllocationPreservesTheCompleteOldGeneration() {
        var allocator = new Allocator();
        var resources = new RtFrameResources(allocator);
        resources.ensureOutput(8, 8);
        var output = resources.output(); var depth = resources.depth(); var composite = resources.composite();
        allocator.failImageCall = allocator.imageCalls + 2;
        assertThrows(OutOfMemoryError.class, () -> resources.ensureOutput(16, 16));
        assertSame(output, resources.output()); assertSame(depth, resources.depth()); assertSame(composite, resources.composite());
        assertEquals(3, allocator.images.size());
        allocator.failImageCall = 0;
        resources.ensureOutput(16, 16);
        assertNotSame(output, resources.output());
        assertEquals(3, allocator.images.size());
        resources.close();
        assertTrue(allocator.images.isEmpty());
    }

    @Test void failedRollbackKeepsEveryAllocationReachableForRetry() {
        var allocator = new Allocator();
        var resources = new RtFrameResources(allocator);
        resources.ensureOutput(8, 8);
        allocator.failedDestroy.add(allocator.serial + 1);
        allocator.failedDestroy.add(allocator.serial + 2);
        allocator.failImageCall = allocator.imageCalls + 3;
        var failure = assertThrows(OutOfMemoryError.class, () -> resources.ensureOutput(16, 16));
        assertEquals("injected image allocation", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(5, allocator.images.size());
        assertThrows(IllegalStateException.class, resources::close);
        allocator.failedDestroy.clear();
        resources.close();
        assertTrue(allocator.images.isEmpty());
    }

    @Test void initializationAndEveryLaterRecordingPinTheOwnedImages() {
        var resources = new RtFrameResources(new Allocator());
        resources.ensureOutput(8, 8);
        var transitions = new AtomicInteger();
        resources.prepare(17, image -> transitions.incrementAndGet());
        assertEquals(3, transitions.get());
        assertThrows(IllegalStateException.class, () -> resources.prepare(29, image -> fail("initializer unproven")));
        assertThrows(IllegalStateException.class, () -> resources.ensureOutput(16, 16));
        resources.releaseRecording(17);
        resources.prepare(29, image -> fail("already initialized"));
        assertThrows(IllegalStateException.class, resources::close);
        resources.discardRecording(29);
        resources.close();
    }

    @Test void abandoningPartialInitialTransitionsAllowsACompleteRetry() {
        var resources = new RtFrameResources(new Allocator());
        resources.ensureOutput(8, 8);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> resources.prepare(17, image -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("transition failed");
        }));
        resources.discardRecording(17);
        resources.prepare(29, image -> calls.incrementAndGet());
        assertEquals(5, calls.get());
        resources.releaseRecording(29);
        resources.close();
    }

    @Test void motionOnlyGenerationAndFailedMotionRollbackAreOwned() {
        var allocator = new Allocator(); var resources = new RtFrameResources(allocator);
        resources.ensureMotion(8, 8);
        long old = resources.motion().handle;
        allocator.failedDestroy.add(old); allocator.failedDestroy.add(allocator.serial + 1);
        assertThrows(IllegalStateException.class, () -> resources.ensureMotion(16, 16));
        assertEquals(old, resources.motion().handle);
        assertEquals(2, allocator.buffers.size());
        allocator.failedDestroy.clear();
        resources.close();
        assertTrue(allocator.buffers.isEmpty());
        assertTrue(allocator.images.isEmpty());
    }

    @Test void invalidExtentNeverAllocates() {
        var allocator = new Allocator(); var resources = new RtFrameResources(allocator);
        assertThrows(IllegalArgumentException.class, () -> resources.ensureOutput(0, 8));
        assertThrows(IllegalArgumentException.class, () -> resources.ensureMotion(Integer.MAX_VALUE, 2));
        assertEquals(0, allocator.imageCalls + allocator.motionCalls);
        resources.close();
    }
}
