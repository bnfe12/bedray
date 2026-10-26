package io.github.andrexarlay.vulkanbridge.glbridge;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.opengl.GL46C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Ring of shared render targets on the ENGINE's VkDevice (port of tools/gl-interop-smoke Ring, which proved the scheme
 * outside the game, docs/experiments/gl-interop-smoke.md). Slot = exported VkImage (RGBA8_UNORM, OPTIMAL, dedicated
 * allocation, OPAQUE_WIN32) imported into GL as a texture, two exported binary semaphores (vkToGl, glToVk), a fence and a
 * prerecorded "release" command buffer.
 * <p>
 * Frame protocol, render thread = R, GL thread = G:
 * R {@link #release}: wait the slot fence, submit CB(release barrier: UNDEFINED to COLOR_ATTACHMENT, queue family to EXTERNAL),
 * signal vkToGl. R then calls {@code gl.call(glFrame)}: G waits vkToGl (srcLayout COLOR_ATTACHMENT), draws, signals glToVk
 * (dstLayout given by the caller), flushes. R waits glToVk in a later queue submission (self-test: its own submit; overlay:
 * the engine's frame submission through VulkanCommandEncoder.waitSemaphore) and acquires the image (EXTERNAL to queue family).
 * The Vulkan queue is only touched from the render thread (the engine does the same: RenderSystem.assertOnRenderThread).
 */
final class SharedRing {
    static final int MEM_TYPE = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    static final int SEM_TYPE = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
    private static final int[] NO_BUFFERS = new int[0];

    final EngineVk e;
    final int w, h, slots;

    final long[] image, memory, memSize, imageView, semVkToGl, semGlToVk, fence;
    final VkCommandBuffer[] cmdRelease;
    final long cmdPool;
    /** Not final: {@link #destroyVulkanObjects()} frees it and clears the reference, and a ring whose constructor threw never assigned it. */
    private VkPhysicalDeviceMemoryProperties memProps;

    // GL side (valid after importIntoGl on the GL thread)
    final int[] glMemObj, glTex, glFbo, glSemVkToGl, glSemGlToVk;
    /** Plain GL depth/stencil renderbuffer shared by all slot FBOs (GL thread only uses it, in order; never exported to Vulkan). */
    int glDepth;

    /** What runs between the clear and the signal of one frame (GL thread). */
    @FunctionalInterface
    interface GlPass {
        void run(int fbo, int width, int height);
    }

    static int usage() {
        return VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    }

    static void check(int r, String what) {
        if (r != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult=" + r);
        }
    }

    /**
     * Asks the driver whether an RGBA8 optimal image and a binary semaphore are exportable as OPAQUE_WIN32.
     * vkGetPhysicalDeviceImageFormatProperties2 + VkPhysicalDeviceExternalImageFormatInfo are core 1.1 (the engine instance is 1.2).
     */
    static String probe(EngineVk e) {
        try (MemoryStack s = stackPush()) {
            VkPhysicalDeviceExternalImageFormatInfo ei = VkPhysicalDeviceExternalImageFormatInfo.calloc(s).sType$Default()
                .handleType(MEM_TYPE);
            VkPhysicalDeviceImageFormatInfo2 fi = VkPhysicalDeviceImageFormatInfo2.calloc(s).sType$Default().pNext(ei.address())
                .format(VK_FORMAT_R8G8B8A8_UNORM).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage());
            VkExternalImageFormatProperties eo = VkExternalImageFormatProperties.calloc(s).sType$Default();
            VkImageFormatProperties2 fo = VkImageFormatProperties2.calloc(s).sType$Default().pNext(eo.address());
            check(vkGetPhysicalDeviceImageFormatProperties2(e.physicalDevice(), fi, fo), "vkGetPhysicalDeviceImageFormatProperties2");
            int feat = eo.externalMemoryProperties().externalMemoryFeatures();
            boolean exportable = (feat & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) != 0;
            boolean importable = (feat & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0;
            boolean dedicatedOnly = (feat & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT) != 0;

            VkPhysicalDeviceExternalSemaphoreInfo si = VkPhysicalDeviceExternalSemaphoreInfo.calloc(s).sType$Default()
                .handleType(SEM_TYPE);
            VkExternalSemaphoreProperties so = VkExternalSemaphoreProperties.calloc(s).sType$Default();
            vkGetPhysicalDeviceExternalSemaphoreProperties(e.physicalDevice(), si, so);
            int sf = so.externalSemaphoreFeatures();
            boolean sExp = (sf & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0;
            boolean sImp = (sf & VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0;
            String text = "image.externalMemoryFeatures=0x" + Integer.toHexString(feat) + " exportable=" + exportable
                + " importable=" + importable + " dedicatedOnly=" + dedicatedOnly + " compatibleHandleTypes=0x"
                + Integer.toHexString(eo.externalMemoryProperties().compatibleHandleTypes())
                + " semaphore.features=0x" + Integer.toHexString(sf) + " semExportable=" + sExp + " semImportable=" + sImp;
            if (!exportable || !importable || !sExp || !sImp) {
                throw new IllegalStateException("driver cannot export/import RGBA8 OPAQUE_WIN32 images/semaphores: " + text);
            }
            return text;
        }
    }

    /** Render thread. Creates the Vulkan objects; GL import follows with {@link #exportHandles} + {@link #importIntoGl}. */
    SharedRing(EngineVk e, int w, int h, int slots) {
        this.e = e;
        LIVE.incrementAndGet();
        try {
            this.w = w;
            this.h = h;
            this.slots = slots;
            image = new long[slots];
            memory = new long[slots];
            memSize = new long[slots];
            imageView = new long[slots];
            semVkToGl = new long[slots];
            semGlToVk = new long[slots];
            fence = new long[slots];
            cmdRelease = new VkCommandBuffer[slots];
            glMemObj = new int[slots];
            glTex = new int[slots];
            glFbo = new int[slots];
            glSemVkToGl = new int[slots];
            glSemGlToVk = new int[slots];
            VkDevice dev = e.vkDevice();
            memProps = VkPhysicalDeviceMemoryProperties.calloc();
            vkGetPhysicalDeviceMemoryProperties(e.physicalDevice(), memProps);
            try (MemoryStack s = stackPush()) {
                LongBuffer p = s.mallocLong(1);
                VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(s).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(e.queueFamily());
                check(vkCreateCommandPool(dev, pci, null, p), "vkCreateCommandPool");
                cmdPool = p.get(0);
            }
            for (int i = 0; i < slots; i++) {
                createSlot(i);
                recordRelease(i);
            }
        } catch (Throwable t) {
            // Until the constructor returns, no Generation references this ring, so neither shutdown() nor a retire queue can
            // ever reach the slots that did get created: a driver refusing the third image would keep the first two alive
            // (VkImage + dedicated memory + 4 semaphores + a fence + the command pool + the calloc'd memory properties)
            // for the rest of the process, and vkDestroyDevice cannot clean up what Java forgot.
            try {
                destroyVulkanObjects();
            } catch (Throwable unwind) {
                GlLog.error(4, "result=ring_ctor_unwind_failed reason=" + unwind, unwind);
            }
            destroyed = true;
            LIVE.decrementAndGet();
            throw t;
        }
    }

    int findMemoryType(int typeBits, int required) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & required) == required) {
                return i;
            }
        }
        throw new IllegalStateException("no Vulkan memory type for bits=0x" + Integer.toHexString(typeBits) + " props=0x" + Integer.toHexString(required));
    }

    private void createSlot(int i) {
        VkDevice dev = e.vkDevice();
        try (MemoryStack s = stackPush()) {
            LongBuffer p = s.mallocLong(1);
            VkExternalMemoryImageCreateInfo ext = VkExternalMemoryImageCreateInfo.calloc(s).sType$Default().handleTypes(MEM_TYPE);
            VkImageCreateInfo ici = VkImageCreateInfo.calloc(s).sType$Default().pNext(ext.address())
                .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                .extent(x -> x.width(w).height(h).depth(1)).mipLevels(1).arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage())
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            check(vkCreateImage(dev, ici, null, p), "vkCreateImage(shared)");
            image[i] = p.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(dev, image[i], req);
            VkMemoryDedicatedAllocateInfo ded = VkMemoryDedicatedAllocateInfo.calloc(s).sType$Default().image(image[i]);
            VkExportMemoryAllocateInfo exp = VkExportMemoryAllocateInfo.calloc(s).sType$Default().pNext(ded.address()).handleTypes(MEM_TYPE);
            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(s).sType$Default().pNext(exp.address())
                .allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(dev, mai, null, p), "vkAllocateMemory(export,dedicated)");
            memory[i] = p.get(0);
            memSize[i] = req.size();
            check(vkBindImageMemory(dev, image[i], memory[i], 0), "vkBindImageMemory");

            VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(s).sType$Default().image(image[i])
                .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            check(vkCreateImageView(dev, vci, null, p), "vkCreateImageView(shared)");
            imageView[i] = p.get(0);

            VkExportSemaphoreCreateInfo xs = VkExportSemaphoreCreateInfo.calloc(s).sType$Default().handleTypes(SEM_TYPE);
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(s).sType$Default().pNext(xs.address());
            check(vkCreateSemaphore(dev, sci, null, p), "vkCreateSemaphore(vkToGl)");
            semVkToGl[i] = p.get(0);
            check(vkCreateSemaphore(dev, sci, null, p), "vkCreateSemaphore(glToVk)");
            semGlToVk[i] = p.get(0);

            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(s).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            check(vkCreateFence(dev, fci, null, p), "vkCreateFence");
            fence[i] = p.get(0);

            VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(cmdPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer cb = s.mallocPointer(1);
            check(vkAllocateCommandBuffers(dev, cai, cb), "vkAllocateCommandBuffers");
            cmdRelease[i] = new VkCommandBuffer(cb.get(0), dev);
        }
    }

    /**
     * Release command buffer: UNDEFINED to COLOR_ATTACHMENT_OPTIMAL with a queue family release to VK_QUEUE_FAMILY_EXTERNAL.
     * UNDEFINED discards the previous content, which is fine because GL clears the image every frame. The matching acquire
     * barrier (EXTERNAL to the queue family, old=new=the layout GL left the image in) is recorded by the consumer
     * (see docs/experiments/gl-interop-smoke.md, stage 3: ownership barriers are kept although this NVIDIA driver does not need them).
     */
    private void recordRelease(int i) {
        try (MemoryStack s = stackPush()) {
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(s).sType$Default();
            check(vkBeginCommandBuffer(cmdRelease[i], bi), "vkBeginCommandBuffer(release)");
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default()
                .srcAccessMask(0).dstAccessMask(0)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .srcQueueFamilyIndex(e.queueFamily()).dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL).image(image[i])
                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
            vkCmdPipelineBarrier(cmdRelease[i], VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, b);
            check(vkEndCommandBuffer(cmdRelease[i]), "vkEndCommandBuffer(release)");
        }
    }

    /** Acquire barrier for a consumer command buffer: EXTERNAL to the queue family, old=new=layout GL left the image in. */
    void recordAcquire(VkCommandBuffer cb, MemoryStack s, int slot, int layout, int dstStage, int dstAccess) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default()
            .srcAccessMask(0).dstAccessMask(dstAccess).oldLayout(layout).newLayout(layout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL).dstQueueFamilyIndex(e.queueFamily()).image(image[slot])
            .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
        vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, dstStage, 0, null, null, b);
    }

    /** Render thread. Exports {memory, vkToGl, glToVk} NT handles of a slot. The importer must close them. */
    long[] exportHandles(int i) {
        try (MemoryStack s = stackPush()) {
            PointerBuffer ph = s.mallocPointer(1);
            VkMemoryGetWin32HandleInfoKHR mi = VkMemoryGetWin32HandleInfoKHR.calloc(s).sType$Default().memory(memory[i]).handleType(MEM_TYPE);
            check(vkGetMemoryWin32HandleKHR(e.vkDevice(), mi, ph), "vkGetMemoryWin32HandleKHR");
            long mem = ph.get(0);
            long a = exportSemaphore(semVkToGl[i], s, ph);
            long b = exportSemaphore(semGlToVk[i], s, ph);
            return new long[] {mem, a, b};
        }
    }

    private long exportSemaphore(long sem, MemoryStack s, PointerBuffer ph) {
        VkSemaphoreGetWin32HandleInfoKHR gi = VkSemaphoreGetWin32HandleInfoKHR.calloc(s).sType$Default().semaphore(sem).handleType(SEM_TYPE);
        check(vkGetSemaphoreWin32HandleKHR(e.vkDevice(), gi, ph), "vkGetSemaphoreWin32HandleKHR");
        return ph.get(0);
    }

    /**
     * GL thread. GL calls: EXT_external_objects (glCreateMemoryObjectsEXT, glMemoryObjectParameteriEXT(DEDICATED_MEMORY_OBJECT_EXT),
     * glTextureStorageMem2DEXT), EXT_external_objects_win32 (glImportMemoryWin32HandleEXT(memory, size, handleType, handle),
     * glImportSemaphoreWin32HandleEXT(semaphore, handleType, handle)); the exported handle copies are closed once every
     * import that needs them has run (see the finally).
     */
    void importIntoGl(int i, long[] handles) {
        try {
            glMemObj[i] = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameteriEXT(glMemObj[i], EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT, GL_TRUE);
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(glMemObj[i], memSize[i], EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handles[0]);
            glCheck("glImportMemoryWin32HandleEXT");
            glTex[i] = glCreateTextures(GL_TEXTURE_2D);
            EXTMemoryObject.glTextureStorageMem2DEXT(glTex[i], 1, GL_RGBA8, w, h, glMemObj[i], 0L);
            glCheck("glTextureStorageMem2DEXT");
            if (glDepth == 0) {
                glDepth = glCreateRenderbuffers();
                glNamedRenderbufferStorage(glDepth, GL_DEPTH32F_STENCIL8, w, h);
                glCheck("glNamedRenderbufferStorage(depth)");
            }
            glFbo[i] = glCreateFramebuffers();
            glNamedFramebufferTexture(glFbo[i], GL_COLOR_ATTACHMENT0, glTex[i], 0);
            glNamedFramebufferRenderbuffer(glFbo[i], GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, glDepth);
            int st = glCheckNamedFramebufferStatus(glFbo[i], GL_FRAMEBUFFER);
            if (st != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("imported FBO incomplete: 0x" + Integer.toHexString(st));
            }
            glSemVkToGl[i] = importSemaphore(handles[1]);
            glSemGlToVk[i] = importSemaphore(handles[2]);
        } finally {
            // Each entry is our own export of a Vulkan object; the GL import above already duplicated it into the driver,
            // so closing after the last use is safe, and closing here instead of per-import is what stops an incomplete FBO
            // from leaking the two semaphore handles nobody had reached yet. One failing CloseHandle must not hide the import
            // error that brought us here, nor skip the remaining handles.
            for (long handle : handles) {
                if (handle != 0L) {
                    try {
                        Win32Handles.close(handle);
                    } catch (Throwable t) {
                        GlLog.error(4, "result=nt_handle_close_failed handle=0x" + Long.toHexString(handle) + " reason=" + t, t);
                    }
                }
            }
        }
    }

    private static int importSemaphore(long handle) {
        int sem = EXTSemaphore.glGenSemaphoresEXT();
        EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(sem, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
        glCheck("glImportSemaphoreWin32HandleEXT");
        return sem;
    }

    /** Render thread. Waits the slot's previous release submission (fence) and submits the release CB, signalling vkToGl. */
    void release(int slot) {
        VkDevice dev = e.vkDevice();
        try (MemoryStack s = stackPush()) {
            check(vkWaitForFences(dev, fence[slot], true, 5_000_000_000L), "vkWaitForFences(release slot " + slot + ")");
            check(vkResetFences(dev, fence[slot]), "vkResetFences");
            VkSubmitInfo si = VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmdRelease[slot]))
                .pSignalSemaphores(s.longs(semVkToGl[slot]));
            check(vkQueueSubmit(e.queue(), si, fence[slot]), "vkQueueSubmit(release)");
        }
    }

    /**
     * GL thread. Waits vkToGl, clears the shared image to {@code clear}, runs the painter, signals glToVk with the layout the
     * Vulkan consumer will use (EXT_semaphore LAYOUT_* tokens map to the Vulkan layouts: COLOR_ATTACHMENT, TRANSFER_SRC,
     * SHADER_READ_ONLY), and flushes (SignalSemaphoreEXT itself flushes; the explicit call documents the intent).
     */
    void glFrame(int slot, float[] clear, GlPass pass, long frame, int dstGlLayout, GlbPerf perf) {
        EXTSemaphore.glWaitSemaphoreEXT(glSemVkToGl[slot], NO_BUFFERS, new int[] {glTex[slot]},
            new int[] {EXTSemaphore.GL_LAYOUT_COLOR_ATTACHMENT_EXT});
        if (perf != null) {
            perf.glBegin(frame);
        }
        // Known state for the clear and the first renderer (a previous renderer may have left masks/scissor changed).
        glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glStencilMask(0xFF);
        glBindFramebuffer(GL_FRAMEBUFFER, glFbo[slot]);
        glViewport(0, 0, w, h);
        glClearNamedFramebufferfv(glFbo[slot], GL_COLOR, 0, clear);
        glClearNamedFramebufferfi(glFbo[slot], GL_DEPTH_STENCIL, 0, 1.0f, 0);
        try {
            if (pass != null) {
                pass.run(glFbo[slot], w, h);
            }
        } finally {
            // A third-party renderer that throws here used to leave GL_TIME_ELAPSED open on this query object: the next
            // frame's glBeginQuery on a different id is then GL_INVALID_OPERATION, which the glCheck below reports as a
            // bridge failure with an unrelated call, and the FBO stays bound.
            if (perf != null) {
                perf.glEnd();
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
        EXTSemaphore.glSignalSemaphoreEXT(glSemGlToVk[slot], NO_BUFFERS, new int[] {glTex[slot]}, new int[] {dstGlLayout});
        glFlush();
        glCheck("glFrame slot " + slot + " frame " + frame);
    }

    private boolean destroyed;
    /** Rings created minus rings destroyed (leak accounting, logged at shutdown). */
    static final java.util.concurrent.atomic.AtomicInteger LIVE = new java.util.concurrent.atomic.AtomicInteger();

    boolean isDestroyed() {
        return destroyed;
    }

    /**
     * Destroys the GL objects (on the GL thread, via {@code gl}) and then the Vulkan objects. Idempotent. The caller guarantees that the
     * GPU no longer uses the ring: either the engine's own destruction queue ran it (resize path: {@code VulkanCommandEncoder.queueForDestroy},
     * which runs after the submission that last used the ring completed), or the graphics queue is idle (shutdown path).
     * The render thread blocks on the GL thread for the GL half (delete calls only).
     */
    void destroy(GlThread gl) {
        if (destroyed) {
            return;
        }
        destroyed = true;
        LIVE.decrementAndGet();
        try {
            gl.run(() -> {
                glFinish();
                for (int i = 0; i < slots; i++) {
                    glDeleteFramebuffers(glFbo[i]);
                    glDeleteTextures(glTex[i]);
                    // The core delete functions ignore name 0; EXT_external_objects and EXT_semaphore_external do not document
                    // that, and a ring that failed half-way through importIntoGl reaches here with entries never assigned.
                    if (glMemObj[i] != 0) {
                        EXTMemoryObject.glDeleteMemoryObjectsEXT(glMemObj[i]);
                    }
                    if (glSemVkToGl[i] != 0) {
                        EXTSemaphore.glDeleteSemaphoresEXT(glSemVkToGl[i]);
                    }
                    if (glSemGlToVk[i] != 0) {
                        EXTSemaphore.glDeleteSemaphoresEXT(glSemGlToVk[i]);
                    }
                }
                glDeleteRenderbuffers(glDepth);
                glCheck("SharedRing.destroy");
            });
        } finally {
            // A GL error in the half above (glCheck throws) used to skip this, and with destroyed already set no later call
            // would ever retry: the images, memory, semaphores, fences and the command pool survived until vkDestroyDevice.
            try {
                destroyVulkanObjects();
            } catch (Throwable unwind) {
                GlLog.error(4, "result=ring_destroy_unwind_failed reason=" + unwind, unwind);
            }
        }
    }

    /**
     * Vulkan half of {@link #destroy}: every slot handle is either live or {@code VK_NULL_HANDLE}, so a ring that failed
     * half-way through the constructor unwinds through the same code. Also releases the off-heap memory properties.
     */
    private void destroyVulkanObjects() {
        if (fence == null) {
            return;
        }
        VkDevice dev = e.vkDevice();
        for (int i = 0; i < slots; i++) {
            vkDestroyFence(dev, fence[i], null);
            vkDestroySemaphore(dev, semVkToGl[i], null);
            vkDestroySemaphore(dev, semGlToVk[i], null);
            vkDestroyImageView(dev, imageView[i], null);
            vkDestroyImage(dev, image[i], null);
            vkFreeMemory(dev, memory[i], null);
        }
        vkDestroyCommandPool(dev, cmdPool, null);
        if (memProps != null) {
            memProps.free();
            memProps = null;
        }
    }

    static void glCheck(String what) {
        int err = glGetError();
        if (err != GL_NO_ERROR) {
            throw new IllegalStateException("GL error 0x" + Integer.toHexString(err) + " after " + what);
        }
    }
}
