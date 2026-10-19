package io.github.andrexarlay.vulkancompat.rtx.framegen.fsr3;

import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two extra {@code VkQueue}s that the FSR 3 frame interpolation swapchain needs besides the engine's graphics queue
 * (docs/framegen/FSR3_FINDINGS.md section 3: all queues must be distinct; present queue = family with present support, image acquire
 * queue = any). The engine creates exactly one queue per role, so while FSR 3 frame generation is the provider this class raises the
 * queue count of the engine's graphics family by two when the device is created ({@code MixinVulkanPhysicalDeviceFgQueues}); the engine
 * never uses those two queue indices (its graphics queue is index {@code count-before}-1 or lower, see VulkanPhysicalDevice l.116-123).
 * The async compute queue is optional in the SDK and is not provided.
 */
public final class Fsr3Queues {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/Fsr3Queues");

    /** Reservation per physical device (the engine builds a VulkanPhysicalDevice for every GPU it enumerates). */
    private record Reservation(int family, int presentIndex, int acquireIndex) {}

    private static final java.util.concurrent.ConcurrentHashMap<Long, Reservation> RESERVED = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String note = "not_planned";
    private static volatile int activeFamily = -1;
    private static VkQueue present;
    private static VkQueue acquire;

    private Fsr3Queues() {}

    public static String note() {
        return note;
    }

    public static boolean reserved() {
        return activeFamily >= 0;
    }

    public static int family() {
        return activeFamily;
    }

    /** Pure planning step (tests): new family->count map with {@code extra} more queues in {@code graphicsFamily}, or null when the family has no spare queues. */
    public static Int2IntMap plan(Int2IntMap current, int graphicsFamily, int familyQueueCount, int extra) {
        int used = current.get(graphicsFamily);
        if (graphicsFamily < 0 || used + extra > familyQueueCount) {
            return null;
        }
        Int2IntMap out = new Int2IntArrayMap(current);
        out.put(graphicsFamily, used + extra);
        return out;
    }

    /** Called from the VulkanPhysicalDevice constructor: returns the map to hand to vkCreateDevice (unchanged when no spare queues). */
    public static Int2IntMap reserve(VkPhysicalDevice pd, Int2IntMap current, int graphicsFamily) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.IntBuffer n = stack.ints(0);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd, n, null);
            VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.calloc(n.get(0), stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd, n, props);
            if (graphicsFamily < 0 || graphicsFamily >= props.capacity()) {
                note = "no_graphics_family";
                return current;
            }
            int count = props.get(graphicsFamily).queueCount();
            int before = current.get(graphicsFamily);
            Int2IntMap planned = plan(current, graphicsFamily, count, 2);
            if (planned == null) {
                note = "no_spare_queue (graphics family " + graphicsFamily + " has " + count + " queues, the engine uses " + before + ")";
                LOGGER.warn("[VKB-FG] fsr3 queues: {}", note);
                return current;
            }
            RESERVED.put(pd.address(), new Reservation(graphicsFamily, before, before + 1));
            note = "reserved family=" + graphicsFamily + " present_index=" + before + " acquire_index=" + (before + 1) + " family_queues=" + count;
            LOGGER.info("[VKB-FG] fsr3 queues: {}", note);
            return planned;
        }
    }

    /** vkGetDeviceQueue for the two reserved queues (after the device exists); false when nothing was reserved. */
    public static synchronized boolean fetch(VkDevice device) {
        Reservation r = RESERVED.get(device.getPhysicalDevice().address());
        if (r == null) {
            return false;
        }
        if (present == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer p = stack.mallocPointer(1);
                VK10.vkGetDeviceQueue(device, r.family(), r.presentIndex(), p);
                present = new VkQueue(p.get(0), device);
                VK10.vkGetDeviceQueue(device, r.family(), r.acquireIndex(), p);
                acquire = new VkQueue(p.get(0), device);
                activeFamily = r.family();
            }
        }
        return true;
    }

    public static VkQueue presentQueue() {
        return present;
    }

    public static VkQueue acquireQueue() {
        return acquire;
    }
}
