package io.github.andrexarlay.vulkanbridge.glbridge;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Locale;

import static org.lwjgl.opengl.GL46C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Cost of the overlay per frame, logged as {@code [VKB-GLBRIDGE] stage=8 perf ...} every {@link #WINDOW} game frames:
 * <ul>
 * <li>frameMs: wall time between two ends of GameRenderer.render() (the whole game frame, overlay or not);</li>
 * <li>cpuMs: render-thread time inside the bridge per composited frame (release submit + slot fence wait, hand-off to the GL
 *     thread, recording); releaseMs / glCallMs / recordMs are its parts. SAME_FRAME: glCallMs = blocking wait until the GL commands
 *     are issued and flushed. PIPELINED_N_MINUS_1: glCallMs = queueing the GL frame (no wait) + joinMs, the wait in the next frame
 *     for that GL frame to be flushed before its semaphore is waited on (0 when the GL thread finished during the frame);</li>
 * <li>latency: SAME_FRAME, PIPELINED_N_MINUS_1 or MIXED (composites of both kinds in the window);</li>
 * <li>glGpuMs: GL_TIME_ELAPSED query around clear + renderers on the GL context (GPU time of the GL work);</li>
 * <li>vkGpuMs: vkCmdWriteTimestamp TOP_OF_PIPE / BOTTOM_OF_PIPE around the composite command buffer (acquire barrier + blend pass)
 *     inside the engine's submission.</li>
 * </ul>
 * GPU queries are read {@link #LAG} frames later without waiting (GL: QUERY_RESULT_AVAILABLE; Vulkan: WITH_AVAILABILITY; the submission
 * of frame f-LAG is complete because the engine waits for submission f-2 before it records f). Unavailable samples are counted as
 * missing, never faked.
 */
final class GlbPerf {
    static final int WINDOW = 600;
    static final int N = 8;
    static final int LAG = 4;

    // render thread
    private long lastFrameEnd;
    private long frames, composited;
    private double frameSum, frameMax, cpuSum, cpuMax, relSum, glCallSum, recSum, vkSum, joinSum;
    private long sameN, pipeN;
    private long vkN, vkMissing;
    volatile String lastLine = "none";

    // GL thread (read by the render thread under the lock)
    private int[] glQueries;
    private double glGpuSum;
    private long glN, glMissing;

    // Vulkan
    private long queryPool;
    private float tsPeriodNs;
    private boolean vkSupported;
    private boolean vkInitDone;
    private final boolean[] vkWritten = new boolean[N];

    void initVulkan(EngineVk e) {
        vkInitDone = true;
        try (MemoryStack s = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(e.physicalDevice(), props);
            tsPeriodNs = props.limits().timestampPeriod();
            IntBuffer count = s.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(e.physicalDevice(), count, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(count.get(0), s);
            vkGetPhysicalDeviceQueueFamilyProperties(e.physicalDevice(), count, qf);
            vkSupported = qf.get(e.queueFamily()).timestampValidBits() > 0 && tsPeriodNs > 0f;
            if (!vkSupported) {
                GlLog.line(8, "event=perf_vk_timestamps_unsupported");
                return;
            }
            VkQueryPoolCreateInfo ci = VkQueryPoolCreateInfo.calloc(s).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(2 * N);
            LongBuffer p = s.mallocLong(1);
            SharedRing.check(vkCreateQueryPool(e.vkDevice(), ci, null, p), "vkCreateQueryPool(perf)");
            queryPool = p.get(0);
        }
    }

    /** Shutdown only (queue idle). */
    void destroyVulkan(EngineVk e) {
        if (queryPool != 0L) {
            vkDestroyQueryPool(e.vkDevice(), queryPool, null);
            queryPool = 0L;
        }
    }

    /** Recorded at the start of the composite CB (outside any render pass). */
    void vkBegin(VkCommandBuffer cb, long frame) {
        if (queryPool == 0L) {
            return;
        }
        int q = (int) (frame % N);
        vkCmdResetQueryPool(cb, queryPool, 2 * q, 2);
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 2 * q);
    }

    void vkEnd(VkCommandBuffer cb, long frame) {
        if (queryPool == 0L) {
            return;
        }
        int q = (int) (frame % N);
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 2 * q + 1);
        vkWritten[q] = true;
    }

    /** Render thread: reads the Vulkan sample of frame-LAG without waiting. */
    void vkCollect(EngineVk e, long frame) {
        if (queryPool == 0L || frame < LAG) {
            return;
        }
        int q = (int) ((frame - LAG) % N);
        if (!vkWritten[q]) {
            return;
        }
        vkWritten[q] = false;
        try (MemoryStack s = stackPush()) {
            LongBuffer r = s.mallocLong(4); // t0, avail0, t1, avail1
            int res = vkGetQueryPoolResults(e.vkDevice(), queryPool, 2 * q, 2, r, 16,
                VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (res != VK_SUCCESS && res != VK_NOT_READY) {
                SharedRing.check(res, "vkGetQueryPoolResults(perf)");
            }
            if (r.get(1) != 0 && r.get(3) != 0) {
                vkSum += (r.get(2) - r.get(0)) * (double) tsPeriodNs / 1e6;
                vkN++;
            } else {
                vkMissing++;
            }
        }
    }

    /** GL thread: collect the sample of frame-LAG and begin the timer of this frame (call after the semaphore wait). */
    void glBegin(long frame) {
        if (glQueries == null) {
            glQueries = new int[N];
            glGenQueries(glQueries);
        }
        if (frame >= LAG) {
            int old = glQueries[(int) ((frame - LAG) % N)];
            boolean avail = glGetQueryObjecti(old, GL_QUERY_RESULT_AVAILABLE) != 0;
            long ns = avail ? glGetQueryObjecti64(old, GL_QUERY_RESULT) : 0L;
            synchronized (this) {
                if (avail) {
                    glGpuSum += ns / 1e6;
                    glN++;
                } else {
                    glMissing++;
                }
            }
        }
        glBeginQuery(GL_TIME_ELAPSED, glQueries[(int) (frame % N)]);
    }

    void glEnd() {
        glEndQuery(GL_TIME_ELAPSED);
    }

    /** GL thread, shutdown. */
    void glDestroy() {
        if (glQueries != null) {
            glDeleteQueries(glQueries);
            glQueries = null;
        }
    }

    /** Render thread: one composited frame. {@code joinNs} is part of {@code glNs} (pipelined only, else 0). */
    void composite(boolean pipelined, long cpuNs, long relNs, long glNs, long recNs, long joinNs) {
        composited++;
        if (pipelined) {
            pipeN++;
        } else {
            sameN++;
        }
        joinSum += joinNs / 1e6;
        double cpu = cpuNs / 1e6;
        cpuSum += cpu;
        cpuMax = Math.max(cpuMax, cpu);
        relSum += relNs / 1e6;
        glCallSum += glNs / 1e6;
        recSum += recNs / 1e6;
    }

    /** Render thread, every game frame. Returns the log text when a window of WINDOW frames is complete, else null. */
    String frameEnd(int w, int h) {
        long now = System.nanoTime();
        if (lastFrameEnd != 0L) {
            double ms = (now - lastFrameEnd) / 1e6;
            frameSum += ms;
            frameMax = Math.max(frameMax, ms);
            frames++;
        }
        lastFrameEnd = now;
        if (frames < WINDOW) {
            return null;
        }
        double gl;
        long gn, gm;
        synchronized (this) {
            gl = glGpuSum;
            gn = glN;
            gm = glMissing;
            glGpuSum = 0;
            glN = glMissing = 0;
        }
        double c = Math.max(1, composited);
        String line = String.format(Locale.ROOT,
            "perf frames=%d composited=%d latency=%s size=%dx%d frameMs=%.3f frameMaxMs=%.2f cpuMs=%.3f cpuMaxMs=%.2f releaseMs=%.3f glCallMs=%.3f joinMs=%.3f recordMs=%.3f glGpuMs=%s vkGpuMs=%s",
            frames, composited, pipeN == 0 ? (sameN == 0 ? "none" : "SAME_FRAME") : (sameN == 0 ? "PIPELINED_N_MINUS_1" : "MIXED"),
            w, h, frameSum / frames, frameMax, cpuSum / c, cpuMax, relSum / c, glCallSum / c, joinSum / c, recSum / c,
            gn == 0 ? "n/a(missing=" + gm + ")" : String.format(Locale.ROOT, "%.3f(n=%d,missing=%d)", gl / gn, gn, gm),
            vkN == 0 ? (vkSupported || !vkInitDone ? "n/a(missing=" + vkMissing + ")" : "unsupported")
                : String.format(Locale.ROOT, "%.3f(n=%d,missing=%d)", vkSum / vkN, vkN, vkMissing));
        frames = composited = 0;
        frameSum = frameMax = cpuSum = cpuMax = relSum = glCallSum = recSum = vkSum = joinSum = 0;
        sameN = pipeN = 0;
        vkN = vkMissing = 0;
        lastLine = line;
        return line;
    }
}
