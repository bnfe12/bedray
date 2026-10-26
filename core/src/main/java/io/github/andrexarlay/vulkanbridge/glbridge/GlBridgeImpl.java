package io.github.andrexarlay.vulkanbridge.glbridge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.FrameInfo;
import io.github.andrexarlay.vulkanbridge.api.FramePhase;
import io.github.andrexarlay.vulkanbridge.api.GlBridge;
import io.github.andrexarlay.vulkanbridge.api.GlBridgeCapabilities;
import io.github.andrexarlay.vulkanbridge.api.GlCamera;
import io.github.andrexarlay.vulkanbridge.api.GlFrame;
import io.github.andrexarlay.vulkanbridge.api.GlOverlayPainter;
import io.github.andrexarlay.vulkanbridge.api.GlRenderer;
import io.github.andrexarlay.vulkanbridge.api.GlRendererOptions;
import io.github.andrexarlay.vulkanbridge.api.GlRendererOptions.Latency;
import io.github.andrexarlay.vulkanbridge.api.GlTier;
import io.github.andrexarlay.vulkanbridge.api.PhasePriority;
import io.github.andrexarlay.vulkanbridge.core.BridgeCore;
import io.github.andrexarlay.vulkanbridge.core.FramePhasesImpl;
import io.github.andrexarlay.vulkanbridge.core.VulkanBackendDetector;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

import static org.lwjgl.opengl.GL46C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL46C.glBindFramebuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * Implementation of {@link GlBridge}. Single instance; all frame work runs on the render thread, driven by the frame phases
 * ({@link FramePhasesImpl}, listeners on BEFORE_GUI and FRAME_END with priority LOW, registered in the constructor).
 * <p>
 * Lanes: one shared-image ring per (phase, latency) pair that has visible renderers, created lazily (the FRAME_END/SAME_FRAME lane at
 * initialisation, for the pixel self-test). Inside a phase the SAME_FRAME lane is composited first, then the pipelined one.
 * <ul>
 *   <li>SAME_FRAME: release slot, hand the GL frame to the GL thread and wait until it is issued and flushed, composite it now
 *       (0.5.x behaviour).</li>
 *   <li>PIPELINED_N_MINUS_1: first composite the GL frame queued in the previous frame (join its future: a binary semaphore wait may
 *       only be submitted after its signal was submitted, i.e. after the GL thread flushed glSignalSemaphoreEXT), then release the next
 *       slot and queue this frame's GL work without waiting. With 2 slots the slot GL writes in frame N was last sampled by the
 *       submission of frame N-1, which is queue-ordered before the release submission of frame N whose vkToGl signal GL waits on.</li>
 * </ul>
 */
public final class GlBridgeImpl implements GlBridge {
    public static final GlBridgeImpl INSTANCE = new GlBridgeImpl(FramePhasesImpl.INSTANCE);

    /** Shared ring size: 2 slots (see the class comment for why this is enough for both latencies). */
    private static final int SLOTS = 2;
    private static final float[] CLEAR = {0f, 0f, 0f, 0f};
    /** Phases the Tier 2 bridge can composite in. */
    static final Set<FramePhase> PHASES = Set.copyOf(EnumSet.of(FramePhase.BEFORE_GUI, FramePhase.FRAME_END));

    private volatile State state = State.DISABLED;
    private volatile String failure;
    private volatile Boolean identityMatch;
    private final Set<String> requesters = ConcurrentHashMap.newKeySet();

    /** A registered renderer; {@code options == null}: follows the deprecated global defaults. */
    private static final class Reg {
        final GlRenderer renderer;
        volatile GlRendererOptions options;

        Reg(GlRenderer renderer, GlRendererOptions options) {
            this.renderer = renderer;
            this.options = options;
        }
    }

    private final List<Reg> regs = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<GlRenderer> removed = new ConcurrentLinkedQueue<>();
    private volatile GlRenderer legacyAdapter;
    /** GL thread only: renderer -> size it was last told with onResize. */
    private final Map<GlRenderer, Long> attached = new IdentityHashMap<>();
    /** GL thread only. */
    private final OffscreenTargets offscreen = new OffscreenTargets();
    /** GL thread only: framebuffer the renderer being run draws into, 0 outside a renderer. */
    private int frameFbo;
    private volatile boolean overlayVisible = true;
    private volatile AlphaMode alphaMode = AlphaMode.PREMULTIPLIED;
    private volatile float opacity = 1f;
    private volatile long composited;
    private volatile int width;
    private volatile int height;

    private EngineVk engine;
    private boolean stage4Done;
    private GlThread gl;
    private GlContext glCtx;
    private OverlayCompositor compositor;
    private final GlbPerf perf = new GlbPerf();
    private long jobCounter;

    /** One ring of shared images (+ its descriptor sets) for one framebuffer size. Replaced on resize, retired through the engine's destruction queue. */
    private static final class Generation {
        final SharedRing ring;
        OverlayCompositor.Binding binding;
        int nextSlot;
        boolean dead;

        Generation(SharedRing ring) {
            this.ring = ring;
        }
    }

    /** A GL frame queued on the GL thread, composited in the next frame (pipelined lanes). */
    private record Pending(Generation gen, int slot, long job, boolean straight, float opacity, Future<?> future, long releaseNs, long postNs) {}

    private static final class Lane {
        final FramePhase phase;
        final Latency latency;
        Generation gen;
        int pendingW, pendingH, pendingStable;
        Pending pending;

        Lane(FramePhase phase, Latency latency) {
            this.phase = phase;
            this.latency = latency;
        }

        String name() {
            return phase + "/" + latency;
        }
    }

    /** [phase][latency], render thread only. */
    private final Lane[][] lanes = new Lane[FramePhase.values().length][Latency.values().length];
    /** Generations whose destruction is queued but has not run yet (render thread only); destroyed explicitly at shutdown. */
    private final List<Generation> retired = new ArrayList<>();
    /** The target size must be stable this many composited frames before a ring is recreated (a window drag fires a resize per frame). */
    static final int RESIZE_STABLE_FRAMES = 10;
    private int recreations;

    /** Package-private for tests (a private {@link FramePhasesImpl}); the game uses {@link #INSTANCE}. */
    GlBridgeImpl(FramePhasesImpl phases) {
        phases.on(FramePhase.BEFORE_GUI, PhasePriority.LOW, this::onPhase);
        phases.on(FramePhase.FRAME_END, PhasePriority.LOW, this::onPhase);
    }

    // ---- switching on

    /** BridgeBootstrap.earlyInit: the JVM switch -Dvkbridge.glbridge=true registers the device requirement now. */
    public void earlyInit(DeviceFeatureRegistry registry) {
        if (!GlBridgeConfig.enabled()) {
            return;
        }
        if (!GlBridgeConfig.platformSupported()) {
            state = State.UNSUPPORTED_PLATFORM;
        }
        GlBridgeConfig.registerDeviceRequirement(registry);
        if (GlBridgeConfig.platformSupported()) {
            state = State.WAITING_FOR_DEVICE;
        }
    }

    @Override
    public State request(String modId) {
        return request(modId, BridgeCore.registry());
    }

    synchronized State request(String modId, DeviceFeatureRegistry registry) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        State s = state();
        if (s == State.NOT_NEEDED) {
            GlLog.line(4, "event=request mod=" + modId + " result=NOT_NEEDED reason=opengl_backend");
            return s;
        }
        if (Boolean.FALSE.equals(GlBridgeConfig.forced())) {
            GlLog.line(4, "event=request mod=" + modId + " result=" + s + " reason=forced_off_by_-D" + GlBridgeConfig.PROPERTY + "=false");
            return s;
        }
        if (!GlBridgeConfig.platformSupported()) {
            state = State.UNSUPPORTED_PLATFORM;
            GlLog.line(4, "event=request mod=" + modId + " result=UNSUPPORTED_PLATFORM os=\"" + System.getProperty("os.name") + "\"");
            return state;
        }
        requesters.add(modId);
        if (s != State.DISABLED) {
            GlLog.line(4, "event=request mod=" + modId + " result=" + s + " reason=already_on");
            return s;
        }
        if (registry.isFrozen()) {
            GlLog.line(4, "event=request mod=" + modId + " result=DISABLED reason=too_late_device_requirements_frozen");
            return s;
        }
        GlBridgeConfig.ensureRegistered(registry, "request:" + modId);
        state = State.WAITING_FOR_DEVICE;
        GlLog.line(4, "event=request mod=" + modId + " result=WAITING_FOR_DEVICE");
        return state;
    }

    @Override
    public State state() {
        if (VulkanBackendDetector.isBackendResolved() && !VulkanBackendDetector.isVulkanActive()) {
            return State.NOT_NEEDED;
        }
        return state;
    }

    @Override
    public GlBridgeCapabilities capabilities() {
        State s = state();
        boolean usable = s == State.WAITING_FOR_DEVICE || s == State.READY;
        return new GlBridgeCapabilities(s, System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
            GlBridgeConfig.platformSupported() ? "OPAQUE_WIN32" : "none",
            usable ? EnumSet.of(GlTier.TIER_1, GlTier.TIER_2) : EnumSet.noneOf(GlTier.class),
            usable ? EnumSet.allOf(Latency.class) : EnumSet.noneOf(Latency.class),
            usable ? PHASES : Set.of(), identityMatch);
    }

    /** Mod ids that called {@link #request} (log/tests). */
    Set<String> requesters() {
        return Set.copyOf(requesters);
    }

    @Override public String failure() { return failure; }
    @Override public int width() { return width; }
    @Override public int height() { return height; }

    // ---- renderers and options

    @Override
    public void registerRenderer(GlRenderer r) {
        register(r, null);
    }

    @Override
    public void registerRenderer(GlRenderer r, GlRendererOptions options) {
        register(r, validate(options));
    }

    static GlRendererOptions validate(GlRendererOptions o) {
        Objects.requireNonNull(o, "options");
        if (!PHASES.contains(o.phase())) {
            throw new IllegalArgumentException("GL bridge cannot composite in phase " + o.phase() + " (Tier 3, not implemented); supported: " + PHASES);
        }
        return o;
    }

    private void register(GlRenderer r, GlRendererOptions o) {
        Objects.requireNonNull(r, "renderer");
        synchronized (regs) {
            Reg existing = find(r);
            if (existing != null) {
                if (o != null) {
                    existing.options = o;
                }
                return;
            }
            regs.add(new Reg(r, o));
        }
    }

    private Reg find(GlRenderer r) {
        for (Reg g : regs) {
            if (g.renderer == r) {
                return g;
            }
        }
        return null;
    }

    private boolean isRegistered(GlRenderer r) {
        return find(r) != null;
    }

    @Override
    public boolean unregisterRenderer(GlRenderer r) {
        synchronized (regs) {
            Reg g = find(r);
            if (g == null) {
                return false;
            }
            regs.remove(g);
            removed.add(r);
            return true;
        }
    }

    @Override
    public boolean setOptions(GlRenderer r, GlRendererOptions options) {
        validate(options);
        Reg g = find(r);
        if (g == null) {
            return false;
        }
        g.options = options;
        return true;
    }

    @Override
    public GlRendererOptions options(GlRenderer r) {
        Reg g = find(r);
        return g == null ? null : effective(g);
    }

    private GlRendererOptions effective(Reg g) {
        GlRendererOptions o = g.options;
        return o != null ? o : new GlRendererOptions(FramePhase.FRAME_END, alphaMode, opacity, overlayVisible, Latency.SAME_FRAME);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setPainter(GlOverlayPainter p) {
        GlRenderer old = legacyAdapter;
        if (old != null) {
            unregisterRenderer(old);
        }
        if (p == null) {
            legacyAdapter = null;
            return;
        }
        GlRenderer adapter = f -> p.paint(f.fbo(), f.width(), f.height(), f.frame());
        legacyAdapter = adapter;
        registerRenderer(adapter);
    }

    @Override public boolean onGlThread() { GlThread g = gl; return g != null && g.isGlThread(); }

    @Override
    public void bindTarget() {
        glBindFramebuffer(GL_FRAMEBUFFER, requireFrameFbo());
    }

    @Override public int targetFramebuffer() { return requireFrameFbo(); }

    @Override
    public int mapFramebuffer(int requested) {
        if (requested != 0 || !onGlThread() || frameFbo == 0) {
            return requested;
        }
        return frameFbo;
    }

    private int requireFrameFbo() {
        if (!onGlThread()) {
            throw new IllegalStateException("GlBridge target framebuffer is only available on the GL bridge thread");
        }
        if (frameFbo == 0) {
            throw new IllegalStateException("GlBridge target framebuffer is only valid inside GlRenderer.render");
        }
        return frameFbo;
    }

    @Override
    @Deprecated
    public void setOverlayVisible(boolean visible) { this.overlayVisible = visible; }

    @Override public long compositedFrames() { return composited; }

    @Override
    @Deprecated
    public void setAlphaMode(AlphaMode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        alphaMode = mode;
    }

    @Override public AlphaMode alphaMode() { return alphaMode; }

    @Override
    @Deprecated
    public void setOpacity(float value) {
        if (!(value >= 0f && value <= 1f)) {
            throw new IllegalArgumentException("opacity must be in [0,1]: " + value);
        }
        opacity = value;
    }

    @Override public float opacity() { return opacity; }

    /** Last perf line (render thread writes, scenarios read). */
    String lastPerfLine() { return perf.lastLine; }

    // ---- frame

    /** Listener of BEFORE_GUI and FRAME_END (render thread). Initialisation runs at FRAME_END, like the 0.5.x RETURN hook. */
    private void onPhase(FramePhase phase, FrameInfo info) {
        State st = state();
        if (st != State.WAITING_FOR_DEVICE && st != State.READY) {
            return;
        }
        if (st == State.WAITING_FOR_DEVICE && phase != FramePhase.FRAME_END) {
            return;
        }
        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
        RenderTarget main = gameRenderer.mainRenderTarget();
        int stage = 4;
        try {
            if (st == State.WAITING_FOR_DEVICE) {
                if (engine == null) {
                    engine = EngineVk.acquire();
                }
                if (!stage4Done) {
                    stage4Check(engine);
                    stage4Done = true;
                }
                stage = 5;
                initSharedImages(engine, main);
                state = State.READY;
            }
            stage = 6;
            runLanes(phase, gameRenderer, main);
            if (phase == FramePhase.FRAME_END) {
                if (!removed.isEmpty()) {
                    gl.run(this::processRemovals);
                }
                String perfLine = perf.frameEnd(main.width, main.height);
                if (perfLine != null) {
                    GlLog.line(8, perfLine);
                }
            }
        } catch (Throwable t) {
            fail(stage, t);
            throw t;
        }
    }

    private void fail(int stage, Throwable t) {
        failure = stage + ": " + t;
        state = State.FAILED;
        GlLog.error(stage, "result=FAIL reason=" + t, t);
    }

    private void runLanes(FramePhase phase, GameRenderer gr, RenderTarget main) {
        Snapshot data = null;
        for (Latency lat : Latency.values()) {
            List<GlRenderer> rs = new ArrayList<>();
            List<GlRendererOptions> os = new ArrayList<>();
            for (Reg g : regs) {
                GlRendererOptions o = effective(g);
                if (o.visible() && o.phase() == phase && o.latency() == lat) {
                    rs.add(g.renderer);
                    os.add(o);
                }
            }
            Lane lane = lanes[phase.ordinal()][lat.ordinal()];
            if (lat == Latency.PIPELINED_N_MINUS_1 && lane != null && lane.pending != null) {
                compositePending(lane, main);
            }
            if (rs.isEmpty()) {
                continue;
            }
            if (data == null) {
                data = captureFrame(gr);
            }
            lane = lane(phase, lat, main);
            CompositePlan plan = CompositePlan.of(rs, os);
            if (lat == Latency.SAME_FRAME) {
                sameFrame(lane, plan, main, data);
            } else {
                postPipelined(lane, plan, main, data);
            }
        }
    }

    private Lane lane(FramePhase phase, Latency lat, RenderTarget main) {
        Lane lane = lanes[phase.ordinal()][lat.ordinal()];
        if (lane == null) {
            lane = new Lane(phase, lat);
            lane.gen = createGeneration(engine, main.width, main.height, "lane_" + phase + "_" + lat);
            lanes[phase.ordinal()][lat.ordinal()] = lane;
            GlLog.line(6, "event=lane_created lane=" + lane.name() + " size=" + main.width + "x" + main.height + " pixel=" + lastSelfTestPixel
                + " result=" + (lastSelfTestPixel.equals("FF00FFFF") ? "PASS" : "FAIL"));
            if (!lastSelfTestPixel.equals("FF00FFFF")) {
                throw new IllegalStateException("lane " + lane.name() + " self-test pixel mismatch: " + lastSelfTestPixel);
            }
        }
        return lane;
    }

    private void ensureCompositor(RenderTarget main) {
        if (compositor == null) {
            compositor = new OverlayCompositor(engine, main);
            perf.initVulkan(engine);
        }
    }

    /** SAME_FRAME: GL draws the slot (render thread waits until the commands are flushed), the engine frame samples it now. */
    private void sameFrame(Lane lane, CompositePlan plan, RenderTarget main, Snapshot data) {
        ensureCompositor(main);
        handleResize(lane, main);
        long t0 = System.nanoTime();
        Generation g = lane.gen;
        if (g.binding == null) {
            g.binding = compositor.bind(g.ring);
        }
        long job = jobCounter++;
        int slot = g.nextSlot;
        g.nextSlot = (slot + 1) % SLOTS;
        SharedRing ring = g.ring;
        perf.vkCollect(engine, job);
        ring.release(slot);
        long t1 = System.nanoTime();
        gl.run(() -> ring.glFrame(slot, CLEAR, (fbo, w, h) -> runPlan(plan, fbo, w, h, job, data), job,
            org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT, perf));
        long t2 = System.nanoTime();
        compositor.record(ring, g.binding, slot, main, perf, job, plan.straight, plan.opacity);
        long t3 = System.nanoTime();
        perf.composite(false, t3 - t0, t1 - t0, t2 - t1, t3 - t2, 0L);
        composited++;
    }

    /** PIPELINED_N_MINUS_1, frame N: release the next slot and queue the GL frame without waiting; composited in frame N+1. */
    private void postPipelined(Lane lane, CompositePlan plan, RenderTarget main, Snapshot data) {
        ensureCompositor(main);
        handleResize(lane, main);
        long t0 = System.nanoTime();
        Generation g = lane.gen;
        long job = jobCounter++;
        int slot = g.nextSlot;
        g.nextSlot = (slot + 1) % SLOTS;
        SharedRing ring = g.ring;
        ring.release(slot);
        long t1 = System.nanoTime();
        Future<?> f = gl.submit(() -> ring.glFrame(slot, CLEAR, (fbo, w, h) -> runPlan(plan, fbo, w, h, job, data), job,
            org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT, perf));
        long t2 = System.nanoTime();
        lane.pending = new Pending(g, slot, job, plan.straight, plan.opacity, f, t1 - t0, t2 - t1);
    }

    /** PIPELINED_N_MINUS_1, frame N+1: wait for the GL frame of frame N to be flushed (normally already done), composite it. */
    private void compositePending(Lane lane, RenderTarget main) {
        Pending p = lane.pending;
        lane.pending = null;
        long t0 = System.nanoTime();
        GlThread.join(p.future());
        long t1 = System.nanoTime();
        if (p.gen().dead) {
            throw new IllegalStateException("pipelined GL frame " + p.job() + " belongs to a destroyed ring (lane " + lane.name() + ")");
        }
        if (p.gen().binding == null) {
            p.gen().binding = compositor.bind(p.gen().ring);
        }
        perf.vkCollect(engine, p.job());
        compositor.record(p.gen().ring, p.gen().binding, p.slot(), main, perf, p.job(), p.straight(), p.opacity());
        long t2 = System.nanoTime();
        perf.composite(true, p.releaseNs() + p.postNs() + (t2 - t0), p.releaseNs(), p.postNs() + (t1 - t0), t2 - t1, t1 - t0);
        composited++;
    }

    /** Stage 4: did the engine's vkCreateDevice accept the four extensions? Read from the LWJGL capabilities of the live VkDevice. */
    private void stage4Check(EngineVk e) {
        var caps = e.vkDevice().getCapabilities();
        boolean mem = caps.VK_KHR_external_memory;
        boolean memWin = caps.VK_KHR_external_memory_win32;
        boolean sem = caps.VK_KHR_external_semaphore;
        boolean semWin = caps.VK_KHR_external_semaphore_win32;
        boolean ok = mem && memWin && sem && semWin;
        GlLog.line(4, "event=device_extensions VK_KHR_external_memory=" + mem + " VK_KHR_external_memory_win32=" + memWin
            + " VK_KHR_external_semaphore=" + sem + " VK_KHR_external_semaphore_win32=" + semWin
            + " result=" + (ok ? "PASS" : "FAIL"));
        if (!ok) {
            throw new IllegalStateException("the engine VkDevice was created without the Win32 external memory/semaphore extensions"
                + " (the GPU does not report them or the 'VKB GL Bridge' FeatureSet was dropped; see 'Optional FeatureSet' lines)");
        }
    }

    /** Stage 5: GL context, identity match, shared images of the FRAME_END/SAME_FRAME lane, import, pixel self-test. */
    private void initSharedImages(EngineVk e, RenderTarget main) {
        long t0 = System.nanoTime();
        GlLog.line(5, "event=probe " + SharedRing.probe(e));

        gl = new GlThread();
        glCtx = gl.call(() -> {
            GlContext c = GlContext.create();
            if (!c.hasInteropExtensions()) {
                throw new IllegalStateException("GL context lacks GL_EXT_memory_object(_win32)/GL_EXT_semaphore(_win32): " + c.version);
            }
            c.readIdentity();
            return c;
        });
        GlLog.line(5, "event=gl_context vendor=\"" + glCtx.vendor + "\" renderer=\"" + glCtx.renderer + "\" version=\"" + glCtx.version
            + "\" thread=VKB-GL-Bridge requesters=" + requesters);

        byte[] vkDevUuid = new byte[16], vkDrvUuid = new byte[16], vkLuid = new byte[8];
        boolean luidValid;
        int vkNodeMask;
        try (MemoryStack s = stackPush()) {
            VkPhysicalDeviceIDProperties id = VkPhysicalDeviceIDProperties.calloc(s).sType$Default();
            VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(id);
            vkGetPhysicalDeviceProperties2(e.physicalDevice(), p2);
            id.deviceUUID().get(vkDevUuid);
            id.driverUUID().get(vkDrvUuid);
            id.deviceLUID().get(vkLuid);
            luidValid = id.deviceLUIDValid();
            vkNodeMask = id.deviceNodeMask();
        }
        boolean luidMatch = luidValid && Arrays.equals(glCtx.luid, vkLuid);
        boolean nodeMatch = (glCtx.nodeMask & vkNodeMask) != 0;
        boolean devMatch = glCtx.deviceUuids.stream().anyMatch(u -> Arrays.equals(u, vkDevUuid));
        boolean drvMatch = Arrays.equals(glCtx.driverUuid, vkDrvUuid);
        GlLog.line(5, "event=identity vk.deviceUUID=" + hex(vkDevUuid) + " vk.LUID=" + hex(vkLuid) + " vk.LUIDValid=" + luidValid
            + " vk.nodeMask=" + vkNodeMask + " gl.LUID=" + hex(glCtx.luid) + " gl.nodeMask=" + glCtx.nodeMask
            + " match.LUID=" + luidMatch + " match.nodeMask=" + nodeMatch + " match.deviceUUID=" + devMatch + " match.driverUUID=" + drvMatch);
        identityMatch = luidMatch && nodeMatch && devMatch && drvMatch;
        if (!identityMatch) {
            throw new IllegalStateException("the GL context and the engine VkPhysicalDevice are not the same GPU");
        }

        Lane lane = new Lane(FramePhase.FRAME_END, Latency.SAME_FRAME);
        lane.gen = createGeneration(e, main.width, main.height, "init");
        lanes[FramePhase.FRAME_END.ordinal()][Latency.SAME_FRAME.ordinal()] = lane;
        GlLog.line(5, "pixel=" + lastSelfTestPixel + " expected=FF00FFFF initMs=" + (System.nanoTime() - t0) / 1_000_000L
            + " result=" + (lastSelfTestPixel.equals("FF00FFFF") ? "PASS" : "FAIL"));
        if (!lastSelfTestPixel.equals("FF00FFFF")) {
            throw new IllegalStateException("stage 5 pixel mismatch: " + lastSelfTestPixel);
        }
    }

    private String lastSelfTestPixel;

    /**
     * Render thread: creates a ring for a size, exports/imports the slots, runs the pixel self-test on it (all frames must match,
     * otherwise this throws). Used for every lane's first ring and for every resize.
     */
    private Generation createGeneration(EngineVk e, int w, int h, String why) {
        SharedRing ring = new SharedRing(e, w, h, SLOTS);
        try {
            for (int i = 0; i < SLOTS; i++) {
                final int slot = i;
                long[] handles = ring.exportHandles(slot);
                gl.run(() -> ring.importIntoGl(slot, handles));
            }
            GlLog.line(5, "event=shared_images why=" + why + " slots=" + SLOTS + " size=" + w + "x" + h + " format=RGBA8_UNORM handleType=OPAQUE_WIN32");
            lastSelfTestPixel = GlSelfTest.run(e, gl, ring);
        } catch (Throwable t) {
            // The caller only stores the ring in a Generation once this method returns, so nothing (not shutdown(), not the
            // retire queue) holds a reference to unwind it. A failed import or self-test would otherwise leak the whole ring
            // on every retried resize. The self-test submits release work and can fault on a fence TIMEOUT, i.e. with GPU work
            // of this ring still in flight, and SharedRing.destroy requires the queue to be idle.
            try {
                e.graphicsQueue().waitIdle();
            } catch (Throwable unwind) {
                GlLog.error(5, "result=ring_unwind_wait_failed reason=" + unwind, unwind);
            }
            try {
                ring.destroy(gl);
            } catch (Throwable unwind) {
                GlLog.error(5, "result=ring_unwind_failed reason=" + unwind, unwind);
            }
            throw t;
        }
        width = w;
        height = h;
        return new Generation(ring);
    }

    /**
     * Render thread, before a lane renders. If the main target size differs from the lane's ring size for RESIZE_STABLE_FRAMES frames in a
     * row, the ring is recreated at the new size and the old one is handed to the engine's destruction queue (VulkanCommandEncoder.queueForDestroy;
     * decompiled VulkanCommandEncoder.submit: an item queued in frame G is destroyed at the end of submission G+1, after submission G completed).
     * No vkDeviceWaitIdle. Until then the overlay is stretched to the new size (logged once per distinct size). A pipelined lane joins and
     * composites its pending frame before this runs, so no GL work of the old ring is in flight.
     */
    private void handleResize(Lane lane, RenderTarget main) {
        Generation cur = lane.gen;
        if (main.width == cur.ring.w && main.height == cur.ring.h) {
            lane.pendingStable = 0;
            lane.pendingW = 0;
            lane.pendingH = 0;
            return;
        }
        if (main.width != lane.pendingW || main.height != lane.pendingH) {
            lane.pendingW = main.width;
            lane.pendingH = main.height;
            lane.pendingStable = 0;
            GlLog.line(6, "event=size_mismatch lane=" + lane.name() + " shared=" + cur.ring.w + "x" + cur.ring.h + " target=" + main.width + "x" + main.height
                + " note=overlay_stretched_until_stable");
        }
        if (++lane.pendingStable < RESIZE_STABLE_FRAMES) {
            return;
        }
        long t0 = System.nanoTime();
        lane.gen = createGeneration(engine, main.width, main.height, "resize");
        recreations++;
        retire(cur);
        lane.pendingStable = 0;
        lane.pendingW = 0;
        lane.pendingH = 0;
        GlLog.line(6, "event=ring_recreated lane=" + lane.name() + " from=" + cur.ring.w + "x" + cur.ring.h + " to=" + main.width + "x" + main.height
            + " ms=" + String.format("%.2f", (System.nanoTime() - t0) / 1e6) + " pixel=" + lastSelfTestPixel + " retiredPending=" + retired.size()
            + " result=" + (lastSelfTestPixel.equals("FF00FFFF") ? "PASS" : "FAIL"));
    }

    /**
     * Called from {@code MixinVulkanDeviceClose} at the head of {@code VulkanDevice.close()} (render thread), the safe point: no frame will be
     * recorded again and the VkDevice is still alive. Order: join pipelined GL frames still queued, wait the graphics queue idle (shutdown, not
     * hot path), renderers' onRemoved + offscreen targets, all ring generations (GL objects then Vulkan objects), compositor, GL context + hidden
     * window, stop the GL thread. Not run from a JVM shutdown hook on purpose (no ordering against VulkanDevice.close).
     */
    public void shutdown() {
        if (gl == null) {
            return;
        }
        long t0 = System.nanoTime();
        GlLog.line(7, "event=shutdown_begin state=" + state + " liveRings=" + SharedRing.LIVE.get() + " retiredPending=" + retired.size());
        List<Generation> all = new ArrayList<>(retired);
        Throwable err = null;
        err = step(err, "collect_generations", () -> {
            for (Lane[] byPhase : lanes) {
                for (Lane lane : byPhase) {
                    if (lane != null) {
                        all.add(lane.gen);
                    }
                }
            }
        });
        // Every pending frame is joined on its own: one faulting pipelined frame must not leave the later lanes' generations
        // out of {@code all} (the ring loop below would then never destroy them, and SharedRing.LIVE would stay elevated).
        err = step(err, "join_queued_frames", () -> {
            for (Lane[] byPhase : lanes) {
                for (Lane lane : byPhase) {
                    if (lane == null || lane.pending == null) {
                        continue;
                    }
                    Pending p = lane.pending;
                    lane.pending = null;
                    try {
                        GlThread.join(p.future());
                    } catch (Throwable t) {
                        GlLog.error(7, "result=shutdown_join_failed lane=" + lane.name() + " reason=" + t, t);
                    }
                }
            }
        });
        err = step(err, "graphics_wait_idle", () -> engine.graphicsQueue().waitIdle());
        err = step(err, "perf_vulkan", () -> perf.destroyVulkan(engine));
        err = step(err, "renderers_removed", () -> gl.run(() -> {
            perf.glDestroy();
            for (GlRenderer r : new ArrayList<>(attached.keySet())) {
                try {
                    r.onRemoved();
                } catch (Throwable t) {
                    GlLog.error(7, "result=renderer_removed_failed renderer="
                        + r.getClass().getName() + " reason=" + t, t);
                }
            }
            attached.clear();
            // The offscreen targets belong to the bridge, not to the third-party renderers: they still have to go.
            offscreen.destroy();
        }));
        err = step(err, "rings", () -> {
            for (Generation g : all) {
                if (g.dead) {
                    continue;
                }
                g.dead = true;
                try {
                    g.ring.destroy(gl);
                } catch (Throwable t) {
                    GlLog.error(7, "result=ring_destroy_failed reason=" + t, t);
                }
                if (g.binding != null) {
                    try {
                        compositor.unbind(g.binding);
                    } catch (Throwable t) {
                        GlLog.error(7, "result=compositor_unbind_failed reason=" + t, t);
                    }
                }
            }
        });
        err = step(err, "lanes", () -> {
            retired.clear();
            for (Lane[] byPhase : lanes) {
                Arrays.fill(byPhase, null);
            }
        });
        err = step(err, "compositor", () -> {
            if (compositor != null) {
                compositor.destroy();
                compositor = null;
            }
        });
        // glCtx is null when stage 5 threw before/while the context was created, while gl itself already exists.
        err = step(err, "gl_context", () -> {
            if (glCtx != null) {
                gl.run(() -> glCtx.destroy());
            }
            glCtx = null;
        });
        // last line of defence against the 64x64 window bug: with a stale handle the engine keeps cancelling the real
        // window's own resize/move/close events once SDL reuses that address.
        err = step(err, "window_handle", () -> GlBridgeConfig.setBridgeWindowHandle(0L));
        boolean stopped = false;
        try {
            stopped = gl.shutdown(5000);
        } catch (Throwable t) {
            err = keepFirst(err, t);
            GlLog.error(7, "result=shutdown_step_failed step=gl_thread reason=" + t, t);
        }
        gl = null;
        engine = null;
        stage4Done = false;
        identityMatch = null;
        state = State.DISABLED;
        if (err != null && failure == null) {
            // failure() is what the autotest and the status line read; a teardown fault would otherwise live only in the log.
            failure = "shutdown: " + err;
        }
        GlLog.line(7, "event=shutdown_done ms=" + String.format("%.1f", (System.nanoTime() - t0) / 1e6) + " liveRings=" + SharedRing.LIVE.get()
            + " glThreadStopped=" + stopped + (err == null ? "" : " first_error=" + err));
    }

    /**
     * One teardown step. Nothing here is retryable, and letting a fault escape would abort the {@code VulkanDevice.close()} this is
     * injected into: the engine device would never be destroyed, the bridge window handle would stay published, and the GL thread
     * would never stop. So every step runs and only the first fault is kept, for the log.
     */
    private Throwable step(Throwable first, String name, Runnable body) {
        try {
            body.run();
            return first;
        } catch (Throwable t) {
            GlLog.error(7, "result=shutdown_step_failed step=" + name + " reason=" + t, t);
            return keepFirst(first, t);
        }
    }

    private static Throwable keepFirst(Throwable first, Throwable t) {
        return first != null ? first : t;
    }

    private void retire(Generation g) {
        retired.add(g);
        engine.encoder().queueForDestroy(() -> destroyGeneration(g));
    }

    private void destroyGeneration(Generation g) {
        if (g.dead) {
            return;
        }
        g.dead = true;
        retired.remove(g);
        g.ring.destroy(gl);
        if (g.binding != null) {
            compositor.unbind(g.binding);
        }
        GlLog.line(6, "event=ring_destroyed size=" + g.ring.w + "x" + g.ring.h + " pending=" + retired.size());
    }

    private record Snapshot(float partialTick, GlCamera camera) {}

    /** Render thread: partial tick and camera snapshot (Camera.position()/xRot()/yRot()/getFov()/getViewRotationMatrix, javap on the game jar). */
    private static Snapshot captureFrame(GameRenderer gr) {
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera c = gr.mainCamera();
        GlCamera cam = null;
        if (c != null && c.isInitialized()) {
            float[] m = new float[16];
            c.getViewRotationMatrix(new Matrix4f()).get(m);
            var pos = c.position();
            cam = new GlCamera(pos.x(), pos.y(), pos.z(), c.xRot(), c.yRot(), c.getFov(), m);
        }
        return new Snapshot(pt, cam);
    }

    /** GL thread: renderers removed since the last GL job get onRemoved (if they ever ran) and lose their offscreen target. */
    private void processRemovals() {
        for (GlRenderer gone; (gone = removed.poll()) != null; ) {
            if (isRegistered(gone)) {
                continue; // registered again meanwhile
            }
            offscreen.remove(gone);
            if (attached.remove(gone) != null) {
                gone.onRemoved();
            }
        }
    }

    /** GL thread: runs the plan's renderers on the bound shared slot framebuffer {@code fbo} (see {@link CompositePlan}). */
    private void runPlan(CompositePlan plan, int fbo, int w, int h, long job, Snapshot data) {
        processRemovals();
        long sizeKey = ((long) w << 32) | (h & 0xFFFFFFFFL);
        try {
            for (CompositePlan.Item it : plan.items) {
                GlRenderer r = it.renderer();
                if (!isRegistered(r)) {
                    continue; // unregistered after the plan was made (pipelined)
                }
                Long known = attached.get(r);
                if (known == null || known != sizeKey) {
                    attached.put(r, sizeKey);
                    r.onResize(w, h);
                }
                int target;
                if (it.intermediate()) {
                    target = offscreen.begin(r, w, h);
                } else {
                    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                    target = fbo;
                }
                frameFbo = target;
                r.render(new GlFrame(target, w, h, job, data.partialTick(), data.camera()));
                SharedRing.glCheck("renderer " + r.getClass().getName());
                frameFbo = 0;
                if (it.intermediate()) {
                    offscreen.blendInto(r, fbo, w, h, it.straight(), it.opacity());
                }
            }
        } finally {
            frameFbo = 0;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
