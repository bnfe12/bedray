package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.world.RtFrameRenderer;
import io.github.andrexarlay.vulkancompat.rt.world.RtMemory;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrame;
import io.github.andrexarlay.vulkancompat.rtx.frame.WorldFrameCapture;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenRuntime;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenSettings;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IncludeExpander;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvFeatureRequirements;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ShaderpackDiscovery;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host path for a user-supplied ray-query compute shader (contract: {@code docs/CUSTOM_RT_SHADERS.md}).
 * <p>
 * {@code -Dbedray.customrt=<shaderpack file or folder name>} loads {@code shaders/bedray/rt.comp} from that pack at the
 * early bootstrap, compiles it with shaderc and checks its reflected bindings against the fixed set-0 contract. Each frame
 * in which hardware RT replaced the world image, the stage binds Bedray's scene TLAS, a camera/frame UBO, a copy of the
 * world color and the RT primary-ray distance, dispatches the shader and blits its RGBA16F output back into the world color
 * before hand/HUD (and before the optional Iris post stage). Same device, queue and target owner as the rest of the host.
 */
public final class CustomRtStage {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/CustomRt");
    public static final String PACK_PROPERTY = "bedray.customrt";
    public static final String ENTRY = "shaders/bedray/rt.comp";

    /** The fixed contract: set 0, these bindings and kinds only. */
    static final int B_SCENE = 0, B_FRAME = 1, B_COLOR = 2, B_OUTPUT = 3, B_DEPTH = 4;
    private static final Map<Integer, SpirvReflection.DescriptorKind> CONTRACT = Map.of(
        B_SCENE, SpirvReflection.DescriptorKind.ACCELERATION_STRUCTURE,
        B_FRAME, SpirvReflection.DescriptorKind.UNIFORM_BUFFER,
        B_COLOR, SpirvReflection.DescriptorKind.COMBINED_IMAGE_SAMPLER,
        B_OUTPUT, SpirvReflection.DescriptorKind.STORAGE_IMAGE,
        B_DEPTH, SpirvReflection.DescriptorKind.COMBINED_IMAGE_SAMPLER);

    public enum State { DISABLED, FAILED, COMPILED, WAITING, ACTIVE }

    private static State state = State.DISABLED;
    private static String pack = "none";
    private static String detail = "not requested (-D" + PACK_PROPERTY + "=<shaderpack>)";
    private static VulkanGlslPackCompiler.Compiled compiled;
    private static CustomRtExecutor executor;
    private static VulkanDevice owner;
    private static long dispatches;
    private static boolean broken;

    private CustomRtStage() {}

    /** Early bootstrap: compile and validate; no device work. */
    public static synchronized void bootstrap(Path gameDir) {
        String name = System.getProperty(PACK_PROPERTY);
        if (name == null || name.isBlank()) return;
        pack = name.trim();
        try {
            Path path = ShaderpackDiscovery.resolveChoice(gameDir.resolve("shaderpacks"), pack);
            if (!Files.exists(path)) {
                fail("shaderpack not found: shaderpacks/" + pack);
                return;
            }
            try (PackSource src = PackSource.open(path, ScanLimits.defaults())) {
                String entry = src.entries().stream().filter(e -> e.equals(ENTRY) || e.endsWith("/" + ENTRY)).findFirst().orElse(null);
                if (entry == null) {
                    fail("pack has no " + ENTRY);
                    return;
                }
                String root = entry.substring(0, entry.length() - "bedray/rt.comp".length());
                Set<String> entries = new LinkedHashSet<>(src.entries());
                IncludeExpander.Reader reader = e -> {
                    if (!entries.contains(e)) throw new java.io.IOException("missing pack file: " + e);
                    return new String(src.read(e), StandardCharsets.UTF_8);
                };
                VulkanGlslPackCompiler.Compiled c = VulkanGlslPackCompiler.compile(root, entry, reader,
                    VulkanGlslPackCompiler.Kind.COMPUTE, Map.of("BEDRAY_CUSTOM_RT", "1"));
                String problem = validate(c);
                if (problem != null) {
                    fail(problem);
                    return;
                }
                compiled = c;
                int[] ls = c.module().localSize();
                state = State.COMPILED;
                detail = "compiled " + c.spirv().remaining() + " B SPIR-V, local size " + ls[0] + "x" + ls[1] + "x" + ls[2]
                    + ", bindings " + bindingNames(c.module().bindings()) + "; waiting for a hardware RT frame";
                LOGGER.info("[VKB-CUSTOMRT] pack=\"{}\" {}", pack, detail);
            }
        } catch (SpirvCompiler.SpirvCompileException e) {
            fail("compile failed: " + flat(e.errorLog()));
        } catch (Exception | LinkageError e) {
            fail("load failed: " + flat(String.valueOf(e.getMessage())));
        }
    }

    /** Contract check on the reflected module; returns the reason for refusal or null. */
    static String validate(VulkanGlslPackCompiler.Compiled c) {
        SpirvReflection.Module m = c.module();
        if (m.stage() != SpirvReflection.Stage.COMPUTE) return "entry is not a compute shader";
        if (m.pushConstants()) return "push constants are not part of the contract; use the BedrayFrame block (binding 1)";
        int[] ls = m.localSize();
        if (ls[2] != 1 || ls[0] < 1 || ls[1] < 1) return "local size must be X x Y x 1, found " + ls[0] + "x" + ls[1] + "x" + ls[2];
        for (SpirvReflection.Binding b : m.bindings()) {
            SpirvReflection.DescriptorKind expected = b.set() == 0 ? CONTRACT.get(b.binding()) : null;
            if (expected == null || expected != b.kind() || b.count() != 1)
                return "binding set " + b.set() + " binding " + b.binding() + " " + b.kind() + "[" + b.count()
                    + "] is outside the contract (set 0: 0 scene TLAS, 1 BedrayFrame UBO, 2 color sampler, 3 rgba16f output image, 4 depth sampler)";
        }
        if (m.bindings().stream().noneMatch(b -> b.binding() == B_OUTPUT))
            return "shader never writes the output image (binding 3); nothing would reach the screen";
        SpirvFeatureRequirements.Result features = SpirvFeatureRequirements.of(m.capabilities(), c.scalarBlockLayout());
        if (!features.unknown().isEmpty()) return "unknown SPIR-V capabilities " + features.unknown();
        List<String> extra = features.description().stream().filter(d -> !d.equals("RayQueryKHR")).toList();
        if (!extra.isEmpty())
            return "needs device features the host does not request for this stage: " + extra
                + " (only ray query from the hardware RT group is guaranteed)";
        return null;
    }

    /** Called once per frame after the world producer, before the Iris post stage. Never throws. */
    public static synchronized void render(RenderTarget target, CameraRenderState camera, WorldFrame.Renderer renderer) {
        if (compiled == null || broken) return;
        if (renderer != WorldFrame.Renderer.HARDWARE_RT) {
            waiting("hardware RT did not render this frame (" + renderer + "); the scene TLAS exists only then");
            return;
        }
        if (TemporalUpscaler.isActive() || FrameGenRuntime.isActive() || FrameGenSettings.mode() != FrameGenSettings.Mode.OFF) {
            waiting("disable temporal upscaling and frame generation: their history would not include this stage");
            return;
        }
        VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
        long tlas = RtFrameRenderer.sceneTlas(target);
        if (device == null || tlas == 0L) {
            waiting("no scene TLAS for this frame");
            return;
        }
        if (!device.vkDevice().getCapabilities().VK_KHR_ray_query) {
            fail("device has no VK_KHR_ray_query");
            return;
        }
        WorldFrame frame;
        try {
            frame = WorldFrameCapture.capture(device, target, camera, renderer);
        } catch (IllegalArgumentException | IllegalStateException e) {
            waiting("world frame unavailable: " + e.getMessage());
            return;
        }
        if (frame.camera() == null) {
            waiting("no current-frame camera/projection");
            return;
        }
        RtMemory.Img depth = RtFrameRenderer.rayDistance(target);
        if (depth != null && (depth.width != frame.color().width() || depth.height != frame.color().height())) depth = null;
        if (owner != null && owner != device) {
            broken = true;
            fail("device changed without cleanup; restart required");
            return;
        }
        try {
            if (executor == null) {
                owner = device;
                executor = new CustomRtExecutor(device.vkDevice(), compiled);
            }
            // After any frame without a dispatch the TLAS/depth objects may have been recreated under the same handle
            // values, so the set is rewritten. Changes happen here, outside an open command buffer (they may wait).
            if (state != State.ACTIVE) executor.invalidate();
            executor.prepare(device, frame, tlas, depth);
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            LOGGER.error("[VKB-CUSTOMRT] preparation failed", e);
            fail("preparation failed: " + flat(String.valueOf(e.getMessage())));
            return;
        }
        var encoder = device.createCommandEncoder();
        var command = encoder.allocateAndBeginTransientCommandBuffer();
        boolean recorded = false;
        try {
            VkBarriers.fullMemoryBarrier(command);
            executor.record(command, frame);
            recorded = true;
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            LOGGER.error("[VKB-CUSTOMRT] recording failed", e);
            fail("recording failed: " + flat(String.valueOf(e.getMessage())));
        } finally {
            VkBarriers.fullMemoryBarrier(command);
            int result = VK10.vkEndCommandBuffer(command);
            if (result == VK10.VK_SUCCESS) {
                encoder.execute(command);
            } else {
                broken = true;
                recorded = false;
                fail("command finalization failed (VkResult " + result + "); restart required");
            }
        }
        if (recorded) {
            dispatches++;
            state = State.ACTIVE;
            detail = "dispatch recorded " + frame.color().width() + "x" + frame.color().height()
                + (depth != null ? ", depth bound" : ", no depth guide") + " (unverified in-game)";
        }
    }

    /** Device close: release every resource of this stage (the bridge already flushed queued work). */
    public static synchronized void onDeviceClose(VulkanDevice device) {
        if (owner == device && executor != null) {
            device.createCommandEncoder().submit(); // queued references first
            VK10.vkDeviceWaitIdle(device.vkDevice());
            executor.close();
            executor = null;
            owner = null;
            if (state == State.ACTIVE || state == State.WAITING) state = State.COMPILED;
        }
    }

    public static synchronized void appendDebugLines(List<String> out) {
        if (state == State.DISABLED) return;
        out.add("[CUSTOM-RT] pack=" + pack + " state=" + state + " dispatches=" + dispatches + " detail=" + detail);
    }

    private static void waiting(String why) {
        if (state != State.FAILED) {
            state = State.WAITING;
            detail = why;
        }
    }

    private static void fail(String why) {
        state = State.FAILED;
        detail = why;
        LOGGER.warn("[VKB-CUSTOMRT] pack=\"{}\" {}", pack, why);
    }

    private static String bindingNames(List<SpirvReflection.Binding> bindings) {
        StringBuilder sb = new StringBuilder("[");
        for (SpirvReflection.Binding b : bindings) {
            if (sb.length() > 1) sb.append(',');
            sb.append(b.binding());
        }
        return sb.append(']').toString();
    }

    private static String flat(String s) {
        String f = s.replace('\r', ' ').replace('\n', ' ').trim();
        return f.length() > 300 ? f.substring(0, 300) + "..." : f;
    }
}
