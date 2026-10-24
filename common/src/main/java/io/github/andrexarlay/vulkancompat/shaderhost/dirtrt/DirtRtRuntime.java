package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.shaderhost.IrisPackChoice;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipeline.State;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackDiscovery;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackScanner;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Makes the Dirt RT path reachable: early bootstrap compiles the selected pack's ray stages, registers the device
 * requirement that names exactly the features those stages use, and the first frame on the live device builds the real
 * ray-tracing pipeline and shader binding table from the reflected layout.
 * <p>
 * The trace itself is NOT dispatched. Every binding of the pack needs a producer, and several have none in Bedray
 * (Vulkanite quad geometry, LabPBR atlases, Iris SSBOs written by the pack's own compute programs). Recording a trace with
 * unbound descriptors is undefined behaviour on the GPU, so the status names the missing producers instead. Opt-in with
 * {@code -Dbedray.dirtrt=true}; without it the pack is only detected.
 */
public final class DirtRtRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/DirtRt");
    public static final String OPT_IN_PROPERTY = "bedray.dirtrt";

    private static DirtRtPackProgram.Result program;
    private static boolean requirementRegistered;
    private static boolean attempted;
    private static VulkanDevice owner;
    private static DirtRtPipeline pipeline;
    private static RtMemory memory;
    private static RtMemory.Buf sbtBuffer;
    private static String bindingSummary = "";

    private DirtRtRuntime() {}

    /** Early bootstrap (before the device): detect the selected pack and, when opted in, compile and reflect it. */
    public static synchronized void bootstrap(Path gameDir) {
        Optional<String> choice;
        try {
            choice = IrisPackChoice.read(gameDir);
        } catch (Exception e) {
            return; // no readable pack choice: nothing to report for Dirt RT
        }
        if (choice.isEmpty()) return;
        Path pack;
        PackReport report;
        try {
            pack = ShaderpackDiscovery.resolveChoice(gameDir.resolve("shaderpacks"), choice.get());
            if (!Files.exists(pack)) return;
            try (PackSource src = PackSource.open(pack, ScanLimits.defaults())) {
                report = ShaderpackScanner.scan(src);
            }
        } catch (Exception e) {
            return; // the Iris host reports unreadable packs; Dirt RT only claims ray-tracing-pipeline packs
        }
        if (report.rtModel() != PackReport.RtModel.RAY_TRACING_PIPELINE) return;
        String unsupported = DirtRtPackProgram.unsupportedReason(report);
        if (unsupported != null) {
            DirtRtPipeline.updateState(State.FAILED, report.name(), unsupported);
            return;
        }
        if (!Boolean.getBoolean(OPT_IN_PROPERTY)) {
            DirtRtPipeline.updateState(State.DISCOVERED, report.name(),
                "ray-tracing pack detected; opt in with -D" + OPT_IN_PROPERTY + "=true to compile it and request VK_KHR_ray_tracing_pipeline");
            return;
        }
        DirtRtPipeline.updateState(State.COMPILING, report.name(), "compiling ray stages");
        long start = System.nanoTime();
        try {
            program = DirtRtPackProgram.load(pack, report);
        } catch (SpirvCompiler.SpirvCompileException e) {
            DirtRtPipeline.onFailure("compile failed: " + oneLine(e.errorLog()), 0L);
            LOGGER.error("[VKB-DIRTRT] {} compile failed: {}", report.name(), e.errorLog());
            return;
        } catch (Exception | LinkageError e) {
            DirtRtPipeline.onFailure("load failed: " + oneLine(String.valueOf(e.getMessage())), 0L);
            LOGGER.error("[VKB-DIRTRT] {} load failed", report.name(), e);
            return;
        }
        bindingSummary = program.missing().stream().map(b -> b.binding().set() + "." + b.binding().binding())
            .collect(Collectors.joining(",", "[", "]"));
        DirtRtPipeline.updateState(State.VALIDATING, report.name(), "12 ray stages compiled (" + program.spirvBytes()
            + " B SPIR-V); waiting for the device");
        LOGGER.info("[VKB-DIRTRT] pack=\"{}\" compiled 12 ray stages, {} B SPIR-V in {} ms; features={}",
            report.name(), program.spirvBytes(), (System.nanoTime() - start) / 1_000_000, program.featureText());
        for (DirtRtPackProgram.BindingStatus b : program.bindings()) {
            LOGGER.info("[VKB-DIRTRT] binding {}", b.line());
        }
    }

    /** Late device-requirement provider; registered only when a pack compiled. */
    public static synchronized void registerDeviceRequirement(DeviceFeatureRegistry registry) {
        if (program == null) return;
        registry.register(DirtRtDeviceRequirements.requirement(program.features()));
        requirementRegistered = true;
        LOGGER.info("[VKB-DIRTRT] requested device group '{}' features={}", DirtRtDeviceRequirements.ID, program.featureText());
    }

    public static synchronized boolean hasProgram() {
        return program != null;
    }

    /** Render thread, frame start: builds the pipeline and SBT once on the live device. */
    public static synchronized void onFrame() {
        if (program == null || attempted) return;
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        if (device == null) return;
        attempted = true;
        if (!requirementRegistered) {
            DirtRtPipeline.onFailure("device requirement was not registered before device creation", 0L);
            return;
        }
        var vk = device.vkDevice();
        if (!vk.getCapabilities().VK_KHR_ray_tracing_pipeline) {
            DirtRtPipeline.onFailure("device group '" + DirtRtDeviceRequirements.ID + "' was dropped: this GPU lacks "
                + "VK_KHR_ray_tracing_pipeline or one of " + program.featureText(), 0L);
            return;
        }
        DirtRtPipeline.updateState(State.CREATING_PIPELINE, null, "creating pipeline");
        try {
            var properties = DirtRtDeviceRequirements.queryProperties(vk.getPhysicalDevice());
            if (!properties.isValid()) throw new IllegalStateException("invalid ray tracing properties " + properties);
            int capacity = runtimeArrayCapacity(vk.getPhysicalDevice());
            owner = device;
            pipeline = new DirtRtPipeline(vk, false);
            pipeline.initializeReflected(program.bundle(), program.bindings(), capacity, VK10.VK_NULL_HANDLE);
            DirtRtPipeline.updateState(State.CREATING_SBT, null, "creating shader binding table");
            var layout = properties.toSbtLayout();
            memory = new RtMemory(vk);
            int probeSize = DirtRtSbtBuilder.createThreePassSbt(0L, layout,
                java.nio.ByteBuffer.allocateDirect(DirtRtPipeline.TOTAL_SHADER_GROUPS * layout.handleSize())).totalSizeBytes();
            sbtBuffer = memory.createBuffer("dirtrt_sbt", probeSize + layout.baseAlignment(),
                KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                true);
            long aligned = (sbtBuffer.address + layout.baseAlignment() - 1) & -(long) layout.baseAlignment();
            var sbt = pipeline.queryAndBuildSbt(aligned, layout);
            MemoryUtil.memCopy(MemoryUtil.memAddress(sbt.sbtBuffer()), sbtBuffer.mapped + (aligned - sbtBuffer.address),
                sbt.totalSizeBytes());
            DirtRtPipeline.onPipelineCreated(pipeline.pipeline(), DirtRtPipeline.TOTAL_STAGES,
                DirtRtPipeline.TOTAL_SHADER_GROUPS, program.bindings().size());
            String blocked = "pipeline and SBT created; trace NOT dispatched: " + program.missing().size()
                + " bindings have no host producer " + bindingSummary;
            DirtRtPipeline.updateState(State.READY, null, blocked);
            LOGGER.info("[VKB-DIRTRT] {} (runtime array capacity {})", blocked, capacity);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[VKB-DIRTRT] pipeline creation failed", e);
            release();
            DirtRtPipeline.onFailure("pipeline creation failed: " + oneLine(String.valueOf(e.getMessage())), 0L);
        }
    }

    /** Descriptor budget for the unbounded geometry array, below the per-stage storage-buffer limit. */
    private static int runtimeArrayCapacity(org.lwjgl.vulkan.VkPhysicalDevice physical) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(physical, props);
            long others = program.bindings().stream()
                .filter(b -> b.binding().kind() == io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection.DescriptorKind.STORAGE_BUFFER
                    && !b.binding().runtimeArray())
                .mapToLong(b -> b.binding().count()).sum();
            long limit = Math.min(props.limits().maxPerStageDescriptorStorageBuffers() & 0xFFFFFFFFL,
                props.limits().maxDescriptorSetStorageBuffers() & 0xFFFFFFFFL) - others;
            if (limit < 1) throw new IllegalStateException("device storage-buffer descriptor limit too small: " + limit);
            return (int) Math.min(DirtRtPipeline.DEFAULT_MAX_GEOMETRY_BUFFERS, limit);
        }
    }

    public static synchronized void onDeviceClose(VulkanDevice device) {
        if (owner == device) {
            release();
            attempted = false;
            if (program != null) DirtRtPipeline.updateState(State.VALIDATING, null, "device closed; pipeline released");
        }
    }

    private static void release() {
        if (pipeline != null) {
            pipeline.close(); // waits for the device to idle before destroying
            pipeline = null;
        }
        if (sbtBuffer != null && memory != null) {
            memory.destroy(sbtBuffer);
            sbtBuffer = null;
        }
        if (memory != null) {
            memory.close();
            memory = null;
        }
        owner = null;
    }

    /** F3 lines: the scaffold telemetry plus, after compilation, which bindings block the trace. */
    public static synchronized void appendDebugLines(List<String> out) {
        if ("none".equals(DirtRtPipeline.getSelectedPack())) return;
        out.add(DirtRtPipeline.getF3Telemetry());
        if (program != null && !program.missing().isEmpty()) {
            out.add("[DirtRT] missing producers " + bindingSummary + ": Vulkanite quad geometry (set 1), terrain/LabPBR atlases,"
                + " Iris SSBOs; pack compute/composite programs not run by this host");
        }
    }

    private static String oneLine(String s) {
        String flat = s.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
