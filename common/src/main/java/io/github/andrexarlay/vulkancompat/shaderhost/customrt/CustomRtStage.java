package io.github.andrexarlay.vulkancompat.shaderhost.customrt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
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

import java.io.IOException;
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
    private static CustomRtResources resources;
    private static CustomRtExecutor executor;
    private static VulkanDevice owner;
    private static long dispatches;
    private static boolean broken;
    // Folder-pack hot reload (extended contract): shader source edits recompile without a game restart.
    private static Path liveDir;
    private static long lastSourceStamp = Long.MIN_VALUE;
    private static long lastPollNanos;
    private static long reloads;

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
                if (src.kind() == PackSource.Kind.DIRECTORY) {
                    // A fixed typo later must not need a game restart either: enable hot reload even after a failed compile.
                    liveDir = path;
                    lastSourceStamp = sourceStamp();
                }
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
                try {
                    resources = CustomRtResources.build(c.module(), root,
                        (fileName, what) -> {
                            try {
                                return src.read(root + fileName);
                            } catch (Exception read) {
                                throw new IllegalArgumentException(fileName + " (" + what + "): " + read.getMessage());
                            }
                        },
                        src.kind() == PackSource.Kind.DIRECTORY ? path.resolve(root) : null);
                } catch (IllegalArgumentException e) {
                    fail("user resources refused: " + e.getMessage());
                    return;
                }
                compiled = c;
                int[] ls = c.module().localSize();
                state = State.COMPILED;
                detail = "compiled " + c.spirv().remaining() + " B SPIR-V, local size " + ls[0] + "x" + ls[1] + "x" + ls[2]
                    + ", bindings " + bindingNames(c.module().bindings())
                    + resourceSummary(resources)
                    + "; waiting for a hardware RT frame";
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
        // Push constants are part of the extended contract: rt.push must cover the whole block (CustomRtResources).
        int[] ls = m.localSize();
        if (ls[2] != 1 || ls[0] < 1 || ls[1] < 1) return "local size must be X x Y x 1, found " + ls[0] + "x" + ls[1] + "x" + ls[2];
        for (SpirvReflection.Binding b : m.bindings()) {
            SpirvReflection.DescriptorKind expected = b.set() == 0 ? CONTRACT.get(b.binding()) : null;
            if (expected != null) {
                if (expected != b.kind() || b.count() != 1)
                    return "fixed binding set 0 binding " + b.binding() + " is " + b.kind() + "[" + b.count()
                        + "], the contract requires " + expected + "[1]";
                continue;
            }
            String refusal = extraBindingRefusal(b);
            if (refusal != null) return refusal;
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

    /**
     * Extended set-0 bindings 5..31 are backed by pack files ({@code bedray/buffer<N>.bin} or
     * {@code bedray/texture<N>.png}); one descriptor each, no arrays, only kinds with a known producer.
     */
    private static String extraBindingRefusal(SpirvReflection.Binding b) {
        if (b.set() != 0)
            return "binding set " + b.set() + " binding " + b.binding() + " is outside the contract (set 0 only: 0 scene TLAS,"
                + " 1 BedrayFrame UBO, 2 color sampler, 3 rgba16f output image, 4 depth sampler, 5+ user resources)";
        if (b.binding() < 5 || b.binding() > 31)
            return "binding " + b.binding() + " is reserved by the fixed contract (bindings 0..4)";
        if (b.count() != 1)
            return "binding " + b.binding() + "[" + b.count() + "]: the extended contract supports single descriptors, not arrays";
        if (b.kind() != SpirvReflection.DescriptorKind.UNIFORM_BUFFER && b.kind() != SpirvReflection.DescriptorKind.STORAGE_BUFFER
            && b.kind() != SpirvReflection.DescriptorKind.COMBINED_IMAGE_SAMPLER && b.kind() != SpirvReflection.DescriptorKind.SAMPLED_IMAGE)
            return "binding " + b.binding() + " kind " + b.kind() + " has no pack file producer"
                + " (supported extras: uniform/storage buffer via bedray/buffer" + b.binding() + ".bin, sampled image via bedray/texture"
                + b.binding() + ".png)";
        return null;
    }

    /** Called once per frame after the world producer, before the Iris post stage. Never throws. */
    public static synchronized void render(RenderTarget target, CameraRenderState camera, WorldFrame.Renderer renderer) {
        if (liveDir != null) hotReload();
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
                executor = new CustomRtExecutor(device.vkDevice(), compiled, resources);
            }
            // Folder-pack live editing: changed rt.push / buffer<N>.bin bytes reach the next dispatch without a restart.
            if (resources != null && resources.refresh()) {
                device.createCommandEncoder().submit(); // queued references first; the executor waits for the device itself
                executor.stageUser(resources);
            }
            // After any frame without a dispatch the TLAS/depth objects may have been recreated under the same handle
            // values, so the set is rewritten. Changes happen here, outside an open command buffer (they may wait).
            if (state != State.ACTIVE) executor.invalidate();
            executor.prepare(device, frame, tlas, depth);
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            LOGGER.error("[VKB-CUSTOMRT] preparation failed\n{}", LogPaths.stack(e));
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
            LOGGER.error("[VKB-CUSTOMRT] recording failed\n{}", LogPaths.stack(e));
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

    /** Highest modification stamp of the shader sources in a folder pack; -1 when the walk fails. */
    private static long sourceStamp() {
        if (liveDir == null) return -1;
        long max = Long.MIN_VALUE;
        int visited = 0;
        try (var walk = Files.walk(liveDir)) {
            for (java.util.Iterator<Path> it = walk.iterator(); it.hasNext() && visited < 2048; visited++) {
                Path file = it.next();
                String n = file.getFileName().toString();
                if (!(n.endsWith(".comp") || n.endsWith(".glsl") || n.endsWith(".vert") || n.endsWith(".frag")
                    || n.endsWith(".rgen") || n.endsWith(".rchit") || n.endsWith(".rmiss") || n.endsWith(".rahit"))) continue;
                try {
                    long m = Files.getLastModifiedTime(file).toMillis();
                    if (m > max) max = m;
                } catch (IOException ignored) {
                    // a transient stat failure is not a change
                }
            }
        } catch (IOException e) {
            return -1;
        }
        return max;
    }

    /** 2 Hz source stamp poll from the render thread; recompiles when a shader source changed. */
    private static void hotReload() {
        long now = System.nanoTime();
        if (now - lastPollNanos < 500_000_000L) return;
        lastPollNanos = now;
        long stamp = sourceStamp();
        if (stamp <= 0 || stamp == lastSourceStamp) return;
        lastSourceStamp = stamp;
        reload();
    }

    /** Recompiles rt.comp from disk; on any failure the previous stage keeps rendering and the reason lands in F3. */
    private static void reload() {
        try (PackSource src = PackSource.open(liveDir, ScanLimits.defaults())) {
            String entry = src.entries().stream().filter(e -> e.equals(ENTRY) || e.endsWith("/" + ENTRY)).findFirst().orElse(null);
            if (entry == null) {
                reloadFailed("no " + ENTRY);
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
                reloadFailed(problem);
                return;
            }
            CustomRtResources fresh = CustomRtResources.build(c.module(), root,
                (fileName, what) -> {
                    try {
                        return src.read(root + fileName);
                    } catch (Exception read) {
                        throw new IllegalArgumentException(fileName + " (" + what + "): " + read.getMessage());
                    }
                },
                liveDir.resolve(root));
            VulkanDevice device = VulkanCapabilityChecker.getVulkanDevice();
            if (device != null && owner != null && owner != device) {
                reloadFailed("device changed without cleanup; restart required");
                return;
            }
            // Resources may have grown or shrunk: the stage's executor is replaced wholesale, outside a frame.
            if (device != null && executor != null) {
                device.createCommandEncoder().submit(); // queued references first
                VK10.vkDeviceWaitIdle(device.vkDevice());
                executor.close();
                executor = null;
            }
            compiled = c;
            resources = fresh;
            broken = false;
            state = State.COMPILED;
            reloads++;
            int[] ls = c.module().localSize();
            detail = "recompiled " + c.spirv().remaining() + " B SPIR-V, local size " + ls[0] + "x" + ls[1] + "x" + ls[2]
                + ", bindings " + bindingNames(c.module().bindings()) + resourceSummary(fresh) + ", reload #" + reloads;
            LOGGER.info("[VKB-CUSTOMRT] hot reload pack=\"{}\" {}", pack, detail);
        } catch (SpirvCompiler.SpirvCompileException e) {
            reloadFailed("compile failed: " + flat(e.errorLog()));
        } catch (IllegalArgumentException e) {
            reloadFailed("user resources refused: " + e.getMessage());
        } catch (Exception | LinkageError e) {
            reloadFailed("load failed: " + flat(String.valueOf(e.getMessage())));
        }
    }

    private static void reloadFailed(String why) {
        detail = LogPaths.show("hot reload kept the previous stage: " + why);
        LOGGER.warn("[VKB-CUSTOMRT] pack=\"{}\" {}", pack, detail);
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
            detail = LogPaths.show(why);
        }
    }

    private static void fail(String why) {
        state = State.FAILED;
        detail = LogPaths.show(why);
        LOGGER.warn("[VKB-CUSTOMRT] pack=\"{}\" {}", pack, detail);
    }

    /** One line for the F3 detail: what the extended-contract resources of this pack resolved to. */
    private static String resourceSummary(CustomRtResources r) {
        if (r == null) return "";
        return ", push " + (r.pushBytes() > 0 ? r.pushBytes() + " B" : "off")
            + ", user buffers " + r.bufferSpecs().size() + ", user textures " + r.textureSpecs().size();
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
