package io.github.andrexarlay.vulkancompat.rt.slang;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreException;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLocator;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreNative;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayGpuImagesNative;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayGpuBuffersNative;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Device-bound Slang runtime: owns one native Bedray core ({@code bedray_core.dll}, ABI v2) and the compute
 * programs registered with it.
 *
 * <p>The core owns renderer resources and never submits: the host passes its {@code VkInstance},
 * {@code VkDevice} and queue, and records the dispatches into the frame's command buffer. This class is the only
 * place where the two sides meet.
 *
 * <p>Lifecycle: create after the Vulkan device exists, close before it is destroyed. A runtime that cannot be
 * created (no DLL in {@code bedray/natives}, no AOT shaders in the jar) reports why the new renderer is unavailable.
 */
public final class SlangRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-SLANG");

    private final BedrayCoreNative library;
    private final long core;
    private final BedrayCoreNative.Capabilities capabilities;
    private final int enabledCapabilities;
    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final long getInstanceProcAddr;
    private final long getDeviceProcAddr;
    private final int apiVersion;
    private final VulkanDevice hostDevice;
    private final Map<String, SlangProgram> programs = new HashMap<>();
    private boolean closed;

    private SlangRuntime(BedrayCoreNative library, long core, BedrayCoreNative.Capabilities capabilities,
                         int enabledCapabilities, VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device,
                         long getInstanceProcAddr, long getDeviceProcAddr, int apiVersion, VulkanDevice hostDevice) {
        this.library = library;
        this.core = core;
        this.capabilities = capabilities;
        this.enabledCapabilities = enabledCapabilities;
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.getInstanceProcAddr = getInstanceProcAddr;
        this.getDeviceProcAddr = getDeviceProcAddr;
        this.apiVersion = apiVersion;
        this.hostDevice = hostDevice;
    }

    /**
     * Creates the native core for {@code device}. The caller must pass only
     * capabilities actually enabled on this logical device, not all features
     * supported by its physical device. The core additionally checks support.
     * Throws {@link BedrayCoreException} when the DLL is missing or refuses the device.
     */
    public static SlangRuntime create(VulkanDevice device, int enabledCapabilities) {
        NativeLibraries libraries;
        try {
            libraries = VulkanBridge.libraries();
        } catch (RuntimeException e) {
            throw new BedrayCoreException("locate", -1, "Bedray NativeLibraries are not available: " + e.getMessage(), e);
        }
        BedrayCoreLocator.Location location = BedrayCoreLocator.locate(libraries);
        if (!location.usable()) {
            throw new BedrayCoreException("locate", -1, BedrayCoreLocator.DLL + " is not installed in "
                + libraries.nativesDirectory() + " (" + location.detail() + ")", null);
        }

        BedrayCoreNative library = BedrayCoreNative.load(location.dll());
        try {
            VkDevice vkDevice = device.vkDevice();
            VkPhysicalDevice physical = vkDevice.getPhysicalDevice();
            VkInstance instance = device.instance().vkInstance();
            VulkanQueue queue = device.graphicsQueue();
            if (queue == null) {
                throw new BedrayCoreException("create", -1, "the Vulkan device has no graphics queue", null);
            }
            FunctionProvider functions = VK.getFunctionProvider();
            long getInstanceProcAddr = functions == null ? 0L : functions.getFunctionAddress("vkGetInstanceProcAddr");
            long getDeviceProcAddr = vkDevice.getCapabilities().vkGetDeviceProcAddr;
            int apiVersion;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                VK10.vkGetPhysicalDeviceProperties(physical, properties);
                apiVersion = properties.apiVersion();
            }
            long core = library.create(instance.address(), physical.address(), vkDevice.address(),
                queue.vkQueue().address(), queue.queueFamilyIndex(), apiVersion, getInstanceProcAddr, getDeviceProcAddr,
                enabledCapabilities);
            BedrayCoreNative.Capabilities capabilities = library.queryCapabilities(core);
            LOGGER.info("[VKB-SLANG] core ABI {} ready: push_constants={} max_group={}x{}x{} invocations={}",
                capabilities.abiVersion(), capabilities.maxPushConstants(), capabilities.maxComputeWorkGroupCountX(),
                capabilities.maxComputeWorkGroupCountY(), capabilities.maxComputeWorkGroupCountZ(),
                capabilities.maxComputeWorkGroupInvocations());
            return new SlangRuntime(library, core, capabilities, enabledCapabilities, instance, physical, vkDevice,
                getInstanceProcAddr, getDeviceProcAddr, apiVersion, device);
        } catch (RuntimeException | Error e) {
            library.close();
            throw e;
        }
    }

    /** True when the jar carries at least one AOT pass; without them there is nothing to register. */
    public static boolean hasCompiledPasses() {
        return !SlangPassLibrary.names().isEmpty();
    }

    public BedrayCoreNative.Capabilities capabilities() {
        return capabilities;
    }

    /** True when every optional capability bit the pass was compiled to require is enabled on this runtime. */
    public boolean supports(int requiredCapabilities) {
        return (requiredCapabilities & ~enabledCapabilities) == 0;
    }

    /**
     * Returns the registered program for {@code name}, registering it on first use.
     * Missing artifacts and registration failures are surfaced to the caller.
     */
    public Optional<SlangProgram> program(String name) {
        if (closed) {
            throw new IllegalStateException("SlangRuntime is closed");
        }
        SlangProgram existing = programs.get(name);
        if (existing != null) {
            return Optional.of(existing);
        }
        Optional<SlangPass> pass = SlangPassLibrary.load(name);
        if (pass.isEmpty()) {
            throw new IllegalStateException("missing Slang pass '" + name + "'");
        }
        int required = pass.get().manifest().requiredCapabilities();
        if (!supports(required)) {
            int missing = required & ~enabledCapabilities;
            int unsupported = missing & ~capabilities.flags();
            throw new IllegalStateException("Slang pass '" + name + "' requires capabilities 0x"
                + Integer.toHexString(required) + " but this runtime enabled only 0x"
                + Integer.toHexString(enabledCapabilities)
                + (unsupported != 0 ? " (the device lacks 0x" + Integer.toHexString(unsupported) + ")" : ""));
        }
        try {
            SlangProgram program = SlangProgram.register(library, core, pass.get());
            programs.put(name, program);
            LOGGER.info("[VKB-SLANG] registered {}", program);
            return Optional.of(program);
        } catch (RuntimeException e) {
            LOGGER.error("[VKB-SLANG] cannot register pass '{}': {}", name, e.getMessage(), e);
            throw e;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Sampler lifetime is tied to this runtime's native core and its recordings. */
    public long atlasSampler() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return library.atlasSampler(core);
    }

    public long linearSampler() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return library.linearSampler(core);
    }

    /** Creates a native image owner on the same game-owned logical device. */
    public BedrayGpuImagesNative createImageOwner(long budgetBytes) {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return new BedrayGpuImagesNative(library, physicalDevice, device, budgetBytes);
    }

    public BedrayGpuBuffersNative createBufferOwner(long budgetBytes) {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return new BedrayGpuBuffersNative(library, physicalDevice, device, budgetBytes);
    }

    /**
     * The FFM library and core handle this runtime owns. Native pass adapters
     * need them to call their own additive ABI entry points; both stay owned by
     * this runtime and are invalid after {@link #close()}.
     */
    public BedrayCoreNative library() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return library;
    }

    /** @see #library() */
    public long core() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return core;
    }

    /**
     * The host objects a second native renderer (the GPU scene) has to borrow.
     * They stay owned by the game; nothing here may create, replace or destroy
     * a device, queue or swapchain.
     */
    public VkInstance instance() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return instance;
    }

    public VkPhysicalDevice physicalDevice() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return physicalDevice;
    }

    public VkDevice device() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return device;
    }

    /**
     * The renderpearl device this runtime was created on. Its shared command
     * encoder owns the destruction queue that proves when a recording's
     * submission completed.
     */
    public VulkanDevice hostDevice() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return hostDevice;
    }

    /** Address of {@code vkGetInstanceProcAddr}, as the core received it. */
    public long getInstanceProcAddr() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return getInstanceProcAddr;
    }

    /** Address of {@code vkGetDeviceProcAddr}, as the core received it. */
    public long getDeviceProcAddr() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return getDeviceProcAddr;
    }

    /** Vulkan API version of the physical device, as the core received it. */
    public int apiVersion() {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        return apiVersion;
    }

    /** Host calls this only after completion or discard of this recording. */
    public void releaseRecording(long commandBuffer) {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        library.releaseRecording(core, commandBuffer);
    }

    /**
     * Queue retirement after the command buffer has been attached to this
     * encoder's current submission and before that submission is sent.
     * The encoder's destruction queue runs after its submission-completion
     * wait. Register once per recording, not once per dispatch. A discarded
     * recording instead requires {@link #releaseRecording(long)} directly.
     */
    public void retireSubmittedRecording(VulkanCommandEncoder encoder, long commandBuffer) {
        if (closed) throw new IllegalStateException("SlangRuntime is closed");
        if (encoder == null || commandBuffer == 0) throw new IllegalArgumentException("encoder and command buffer are required");
        encoder.queueForDestroy(() -> releaseRecording(commandBuffer));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        library.requireResourceOwnersReleased();
        for (SlangProgram program : programs.values()) {
            try {
                program.close();
            } catch (RuntimeException e) {
                LOGGER.warn("[VKB-SLANG] destroy program {}: {}", program.name(), e.getMessage());
            }
        }
        programs.clear();
        // The native core rejects shutdown while a recording is still registered. That is a host bug (the caller
        // must release after GPU completion or discard), and destroying pools under in-flight work would be worse
        // than leaking the core until process exit: log it and keep the library loaded instead of throwing out of
        // the renderer's shutdown path.
        try {
            library.destroy(core);
        } catch (RuntimeException e) {
            LOGGER.error("[VKB-SLANG] core shutdown refused ({}); keeping {} loaded", e.getMessage(),
                BedrayCoreLocator.DLL);
            return;
        }
        library.close();
        closed = true;
    }
}
