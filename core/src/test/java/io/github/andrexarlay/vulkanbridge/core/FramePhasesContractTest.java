package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.ClaimResult;
import io.github.andrexarlay.vulkanbridge.api.ExclusiveRole;
import io.github.andrexarlay.vulkanbridge.api.FrameInfo;
import io.github.andrexarlay.vulkanbridge.api.FramePhase;
import io.github.andrexarlay.vulkanbridge.api.PhasePriority;
import io.github.andrexarlay.vulkanbridge.api.Registration;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** L2: contract of FramePhases (ADR-0003) without the game: order, removal, exceptions, roles, generation ids, projection chain. */
class FramePhasesContractTest {
    private final FramePhasesImpl phases = new FramePhasesImpl();
    private final Object device = new Object();

    @Test
    void facadeReturnsTheSingleton() {
        assertSame(FramePhasesImpl.INSTANCE, VulkanBridge.framePhases());
    }

    @Test
    void phaseOrderIsPriorityThenRegistration() {
        List<String> seen = new ArrayList<>();
        phases.on(FramePhase.BEFORE_GUI, PhasePriority.LOW, (p, f) -> seen.add("low1"));
        phases.on(FramePhase.BEFORE_GUI, PhasePriority.NORMAL, (p, f) -> seen.add("normal1"));
        phases.on(FramePhase.BEFORE_GUI, PhasePriority.HIGH, (p, f) -> seen.add("high"));
        phases.on(FramePhase.BEFORE_GUI, PhasePriority.NORMAL, (p, f) -> seen.add("normal2"));
        phases.on(FramePhase.FRAME_END, PhasePriority.HIGH, (p, f) -> seen.add("end"));
        phases.beginFrame(device, 100, 50, 0.5);
        phases.fire(FramePhase.BEFORE_GUI);
        phases.fire(FramePhase.FRAME_END);
        assertEquals(List.of("high", "normal1", "normal2", "low1", "end"), seen);
    }

    @Test
    void listenerGetsPhaseAndFrame() {
        List<String> seen = new ArrayList<>();
        phases.on(FramePhase.FRAME_BEGIN, PhasePriority.NORMAL, (p, f) -> seen.add(p + ":" + f.frameIndex() + ":" + f.width() + "x" + f.height()));
        phases.beginFrame(device, 640, 480, 0.25);
        phases.beginFrame(device, 640, 480, 0.75);
        assertEquals(List.of("FRAME_BEGIN:1:640x480", "FRAME_BEGIN:2:640x480"), seen);
        assertEquals(0.75, phases.currentFrame().partialTick());
    }

    @Test
    void nothingIsDispatchedBeforeTheFirstFrame() {
        List<FramePhase> seen = new ArrayList<>();
        phases.on(FramePhase.FRAME_END, PhasePriority.NORMAL, (p, f) -> seen.add(p));
        phases.fire(FramePhase.FRAME_END);
        assertTrue(seen.isEmpty());
        assertNull(phases.currentFrame());
        assertEquals(0, phases.deviceGeneration());
        assertEquals(0, phases.mainTargetGeneration());
        assertThrows(IllegalArgumentException.class, () -> phases.fire(FramePhase.FRAME_BEGIN));
    }

    @Test
    void removedListenerIsNotCalledAndRemoveIsIdempotent() {
        List<String> seen = new ArrayList<>();
        Registration r = phases.on(FramePhase.FRAME_END, PhasePriority.NORMAL, (p, f) -> seen.add("x"));
        phases.beginFrame(device, 1, 1, 0);
        phases.fire(FramePhase.FRAME_END);
        assertTrue(r.remove());
        assertFalse(r.remove());
        phases.fire(FramePhase.FRAME_END);
        assertEquals(List.of("x"), seen);
        assertEquals(0, phases.listenerCount(FramePhase.FRAME_END));
    }

    @Test
    void listenerExceptionPropagatesAndStopsThePhase() {
        List<String> seen = new ArrayList<>();
        phases.on(FramePhase.BEFORE_LEVEL, PhasePriority.HIGH, (p, f) -> {
            throw new IllegalStateException("boom");
        });
        phases.on(FramePhase.BEFORE_LEVEL, PhasePriority.LOW, (p, f) -> seen.add("after"));
        phases.beginFrame(device, 1, 1, 0);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> phases.fire(FramePhase.BEFORE_LEVEL));
        assertEquals("boom", e.getMessage());
        assertTrue(seen.isEmpty());
    }

    @Test
    void deviceGenerationFollowsDeviceIdentityAndClose() {
        phases.beginFrame(device, 1, 1, 0);
        phases.beginFrame(device, 1, 1, 0);
        assertEquals(1, phases.deviceGeneration());
        phases.onDeviceClosed();
        phases.beginFrame(device, 1, 1, 0);
        assertEquals(2, phases.deviceGeneration(), "after close even the same object counts as a new device");
        phases.beginFrame(new Object(), 1, 1, 0);
        assertEquals(3, phases.deviceGeneration());
    }

    @Test
    void mainTargetGenerationFollowsResize() {
        List<FrameInfo> seen = new ArrayList<>();
        phases.on(FramePhase.BEFORE_GUI, PhasePriority.NORMAL, (p, f) -> seen.add(f));
        phases.beginFrame(device, 1280, 720, 0);
        assertEquals(1, phases.mainTargetGeneration());
        phases.onMainTargetResized(1920, 1080);
        phases.fire(FramePhase.BEFORE_GUI);
        assertEquals(2, seen.get(0).mainTargetGeneration());
        assertEquals(1920, seen.get(0).width());
        phases.beginFrame(device, 1, 1, 0);
        assertEquals(2, phases.mainTargetGeneration(), "beginFrame sizes only seed the first generation");
        assertEquals(1080, phases.currentFrame().height());
    }

    @Test
    void exclusiveRoles() {
        ClaimResult a = phases.claim(ExclusiveRole.PROJECTION_JITTER, "rtx");
        assertTrue(a.granted());
        assertTrue(phases.claim(ExclusiveRole.PROJECTION_JITTER, "rtx").granted(), "owner may claim again");
        ClaimResult b = phases.claim(ExclusiveRole.PROJECTION_JITTER, "other");
        assertFalse(b.granted());
        assertEquals("rtx", b.owner());
        assertFalse(phases.release(ExclusiveRole.PROJECTION_JITTER, "other"));
        assertTrue(phases.claim(ExclusiveRole.WORLD_RENDER_SCALE, "other").granted(), "roles are independent");
        assertTrue(phases.release(ExclusiveRole.PROJECTION_JITTER, "rtx"));
        assertTrue(phases.owner(ExclusiveRole.PROJECTION_JITTER).isEmpty());
        assertTrue(phases.claim(ExclusiveRole.PROJECTION_JITTER, "other").granted());
        assertThrows(IllegalArgumentException.class, () -> phases.claim(ExclusiveRole.STORAGE_USAGE_PATCH, " "));
    }

    @Test
    void projectionModifiersChainInPriorityOrderOnACopy() {
        Matrix4f engine = new Matrix4f().identity();
        assertSame(engine, phases.applyProjection(engine), "no modifiers: the engine matrix itself");
        phases.modifyProjection(PhasePriority.LOW, m -> m.translate(0, 0, 1)); // second
        Registration high = phases.modifyProjection(PhasePriority.HIGH, m -> m.scale(2f)); // first
        Matrix4f out = phases.applyProjection(engine);
        assertNotSame(engine, out);
        assertEquals(new Matrix4f().identity(), engine, "the engine matrix is not mutated");
        assertEquals(new Matrix4f().scale(2f).translate(0, 0, 1), out);
        high.remove();
        assertEquals(new Matrix4f().translate(0, 0, 1), phases.applyProjection(engine));
        phases.modifyProjection(PhasePriority.NORMAL, m -> null);
        assertThrows(NullPointerException.class, () -> phases.applyProjection(engine));
    }
}
