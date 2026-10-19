package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.ClaimResult;
import io.github.andrexarlay.vulkanbridge.api.ExclusiveRole;
import io.github.andrexarlay.vulkanbridge.api.FrameInfo;
import io.github.andrexarlay.vulkanbridge.api.FramePhase;
import io.github.andrexarlay.vulkanbridge.api.FramePhaseListener;
import io.github.andrexarlay.vulkanbridge.api.FramePhases;
import io.github.andrexarlay.vulkanbridge.api.PhasePriority;
import io.github.andrexarlay.vulkanbridge.api.Registration;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * Implementation of {@link FramePhases}. Pure Java (JOML only): the Minecraft side is {@code MixinGameRendererFramePhases}, which
 * calls {@link #beginFrame}, {@link #fire}, {@link #applyProjection} and {@link #onMainTargetResized}. Listener lists are
 * copy-on-write (registration from any thread, dispatch without locks on the render thread).
 */
public final class FramePhasesImpl implements FramePhases {
    public static final FramePhasesImpl INSTANCE = new FramePhasesImpl();

    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/FramePhases");

    private static final class Entry<T> {
        final PhasePriority priority;
        final T value;
        final AtomicBoolean live = new AtomicBoolean(true);

        Entry(PhasePriority priority, T value) {
            this.priority = priority;
            this.value = value;
        }
    }

    private final Object lock = new Object();
    @SuppressWarnings("unchecked")
    private final List<Entry<FramePhaseListener>>[] listeners = new List[FramePhase.values().length];
    private volatile List<Entry<UnaryOperator<Matrix4f>>> modifiers = List.of();
    private final Map<ExclusiveRole, String> owners = new EnumMap<>(ExclusiveRole.class);

    // render thread state
    private long frameIndex;
    private long deviceGeneration;
    private Object lastDevice;
    private long mainTargetGeneration;
    private int width, height;
    private double partialTick;
    private volatile FrameInfo current;

    public FramePhasesImpl() {
        for (int i = 0; i < listeners.length; i++) {
            listeners[i] = List.of();
        }
    }

    // ---- registration

    @Override
    public Registration on(FramePhase phase, PhasePriority priority, FramePhaseListener listener) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(listener, "listener");
        Entry<FramePhaseListener> e = new Entry<>(priority, listener);
        synchronized (lock) {
            listeners[phase.ordinal()] = inserted(listeners[phase.ordinal()], e);
        }
        return () -> {
            if (!e.live.compareAndSet(true, false)) {
                return false;
            }
            synchronized (lock) {
                listeners[phase.ordinal()] = without(listeners[phase.ordinal()], e);
            }
            return true;
        };
    }

    @Override
    public Registration modifyProjection(PhasePriority priority, UnaryOperator<Matrix4f> modifier) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(modifier, "modifier");
        Entry<UnaryOperator<Matrix4f>> e = new Entry<>(priority, modifier);
        synchronized (lock) {
            modifiers = inserted(modifiers, e);
        }
        return () -> {
            if (!e.live.compareAndSet(true, false)) {
                return false;
            }
            synchronized (lock) {
                modifiers = without(modifiers, e);
            }
            return true;
        };
    }

    /** New list with {@code e} after every entry of the same or a higher priority (stable: registration order inside a priority). */
    private static <T> List<Entry<T>> inserted(List<Entry<T>> list, Entry<T> e) {
        List<Entry<T>> copy = new ArrayList<>(list);
        int index = copy.size();
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i).priority.ordinal() > e.priority.ordinal()) {
                index = i;
                break;
            }
        }
        copy.add(index, e);
        return List.copyOf(copy);
    }

    private static <T> List<Entry<T>> without(List<Entry<T>> list, Entry<T> e) {
        List<Entry<T>> copy = new ArrayList<>(list);
        copy.remove(e);
        return List.copyOf(copy);
    }

    // ---- exclusive roles

    @Override
    public ClaimResult claim(ExclusiveRole role, String modId) {
        Objects.requireNonNull(role, "role");
        requireModId(modId);
        String owner;
        synchronized (lock) {
            owner = owners.putIfAbsent(role, modId);
        }
        if (owner == null) {
            LOGGER.info("[VKB-CORE] frame_role_claimed role={} owner={}", role, modId);
            return new ClaimResult(role, modId, true, modId);
        }
        if (owner.equals(modId)) {
            return new ClaimResult(role, modId, true, modId);
        }
        LOGGER.warn("[VKB-CORE] frame_role_refused role={} requester={} owner={}", role, modId, owner);
        return new ClaimResult(role, modId, false, owner);
    }

    @Override
    public boolean release(ExclusiveRole role, String modId) {
        Objects.requireNonNull(role, "role");
        requireModId(modId);
        boolean released;
        synchronized (lock) {
            released = owners.remove(role, modId);
        }
        if (released) {
            LOGGER.info("[VKB-CORE] frame_role_released role={} owner={}", role, modId);
        }
        return released;
    }

    @Override
    public Optional<String> owner(ExclusiveRole role) {
        synchronized (lock) {
            return Optional.ofNullable(owners.get(role));
        }
    }

    private static void requireModId(String modId) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
    }

    // ---- state

    @Override
    public FrameInfo currentFrame() {
        return current;
    }

    @Override
    public long deviceGeneration() {
        FrameInfo f = current;
        return f == null ? 0L : f.deviceGeneration();
    }

    @Override
    public long mainTargetGeneration() {
        FrameInfo f = current;
        return f == null ? 0L : f.mainTargetGeneration();
    }

    // ---- driven by the GameRenderer mixin (render thread)

    /**
     * FRAME_BEGIN. {@code device} is the engine's device object (identity only: a new object = a new device generation);
     * {@code targetWidth/Height} the main target size, used only for the first observation (later sizes come from
     * {@link #onMainTargetResized}).
     */
    public void beginFrame(Object device, int targetWidth, int targetHeight, double partialTick) {
        Objects.requireNonNull(device, "device");
        frameIndex++;
        if (device != lastDevice) {
            lastDevice = device;
            deviceGeneration++;
            LOGGER.info("[VKB-CORE] frame_device_generation gen={} device={} frame={}", deviceGeneration, device.getClass().getName(), frameIndex);
        }
        if (mainTargetGeneration == 0L) {
            mainTargetGeneration = 1L;
            width = targetWidth;
            height = targetHeight;
        }
        this.partialTick = partialTick;
        current = snapshot();
        dispatch(FramePhase.FRAME_BEGIN);
    }

    /** {@code GameRenderer.resize(int,int)} returned: the main target was recreated at this size. */
    public void onMainTargetResized(int newWidth, int newHeight) {
        mainTargetGeneration++;
        width = newWidth;
        height = newHeight;
        if (current != null) {
            current = snapshot();
        }
        LOGGER.info("[VKB-CORE] frame_main_target_generation gen={} size={}x{} frame={}", mainTargetGeneration, newWidth, newHeight, frameIndex);
    }

    /** The device is being closed: the next device observed gets a new generation. */
    public void onDeviceClosed() {
        lastDevice = null;
    }

    /** Any phase other than FRAME_BEGIN. Does nothing before the first frame. */
    public void fire(FramePhase phase) {
        if (phase == FramePhase.FRAME_BEGIN) {
            throw new IllegalArgumentException("FRAME_BEGIN is dispatched by beginFrame");
        }
        if (current == null) {
            return;
        }
        dispatch(phase);
    }

    private void dispatch(FramePhase phase) {
        FrameInfo f = current;
        for (Entry<FramePhaseListener> e : listeners[phase.ordinal()]) {
            if (e.live.get()) {
                e.value.onPhase(phase, f);
            }
        }
    }

    /** Applies the projection modifiers; returns {@code engine} itself when there are none. */
    public Matrix4f applyProjection(Matrix4f engine) {
        List<Entry<UnaryOperator<Matrix4f>>> list = modifiers;
        if (list.isEmpty()) {
            return engine;
        }
        Matrix4f m = new Matrix4f(engine);
        for (Entry<UnaryOperator<Matrix4f>> e : list) {
            if (e.live.get()) {
                m = Objects.requireNonNull(e.value.apply(m), "projection modifier returned null");
            }
        }
        return m;
    }

    private FrameInfo snapshot() {
        return new FrameInfo(frameIndex, deviceGeneration, mainTargetGeneration, width, height, partialTick);
    }

    /** Number of live listeners of a phase (tests and the log). */
    public int listenerCount(FramePhase phase) {
        return listeners[phase.ordinal()].size();
    }
}
