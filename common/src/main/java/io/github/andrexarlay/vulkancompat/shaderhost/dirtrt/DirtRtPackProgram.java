package io.github.andrexarlay.vulkancompat.shaderhost.dirtrt;

import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipeline.DirtRtShaderBundle;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipeline.PassShaders;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtPipelineConfig.RayPass;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IncludeExpander;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvFeatureRequirements;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.SpirvReflection;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler.Compiled;
import io.github.andrexarlay.vulkancompat.shaderhost.glsl.VulkanGlslPackCompiler.Kind;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPackOptions;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackReport;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackSource;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.ScanLimits;
import org.lwjgl.vulkan.KHRRayTracingPipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Loads the ray-tracing stages of a Vulkanite-style pack (Dirt RT: {@code shaders/rayN.rgen} plus
 * {@code rayN_0.rmiss/.rchit/.rahit}) from the pack the user selected, compiles them with shaderc and reads their real
 * descriptor layout and device-feature needs back from SPIR-V. Nothing here touches Vulkan; it runs at the early bootstrap so
 * the device requirement can name exactly the features the compiled code uses.
 * <p>
 * The binding table at the end states, per reflected binding, whether the host has a producer for it. Only a binding with a
 * producer may ever be bound; the trace stays blocked while any binding lacks one.
 */
public final class DirtRtPackProgram {

    /** Host producers that exist in Bedray code today, keyed by "set.binding" and descriptor kind. */
    public enum Producer {
        /** Bedray's camera-relative scene TLAS (RtWorld), rebuilt every frame while hardware RT renders. */
        HOST_TLAS("Bedray scene TLAS (custom index = Bedray section slot, not a Vulkanite geometry index)"),
        /** CameraInfo block packed by {@link DirtRtCameraUbo}. */
        HOST_CAMERA_UBO("CameraInfo packed by DirtRtCameraUbo"),
        NONE("no host producer");

        public final String description;

        Producer(String description) {
            this.description = description;
        }
    }

    public record BindingStatus(SpirvReflection.Binding binding, int stageFlags, Producer producer, String note) {
        public String line() {
            var b = binding;
            return b.set() + "." + b.binding() + " " + (b.name().isEmpty() ? "?" : b.name()) + " " + b.kind()
                + (b.runtimeArray() ? "[]" : b.count() > 1 ? "[" + b.count() + "]" : "") + " -> "
                + (producer == Producer.NONE ? "MISSING (" + note + ")" : producer.description);
        }
    }

    /**
     * @param bundle      compiled stages in the order {@link DirtRtPipeline} expects
     * @param stages      per-stage compile results (12 entries)
     * @param bindings    merged descriptor bindings with stage flags and host producer
     * @param features    device features the compiled code requires (from SPIR-V capabilities + scalar layout)
     * @param featureText capability names behind {@link #features}
     * @param optionsChanged number of pack options that differ from source defaults (Iris saved settings applied)
     */
    public record Result(String packName, DirtRtShaderBundle bundle, List<Compiled> stages, List<BindingStatus> bindings,
                         List<FeatureRequest> features, List<String> featureText, int optionsChanged) {
        public List<BindingStatus> missing() {
            return bindings.stream().filter(b -> b.producer() == Producer.NONE).toList();
        }

        public long spirvBytes() {
            return stages.stream().mapToLong(c -> c.spirv().remaining()).sum();
        }

        public boolean hasRuntimeArrays() {
            return bindings.stream().anyMatch(b -> b.binding().runtimeArray());
        }
    }

    private DirtRtPackProgram() {}

    /** True when the report describes a pack this loader can try: a ray-tracing-pipeline pack with exactly ray0..ray2. */
    public static String unsupportedReason(PackReport report) {
        if (report.rtModel() != PackReport.RtModel.RAY_TRACING_PIPELINE) return "pack does not use VK_KHR_ray_tracing_pipeline";
        if (!report.rtDispatch().isEmpty() && !report.rtDispatch().keySet().equals(Set.of(0, 1, 2)))
            return "pack declares rt passes " + report.rtDispatch().keySet() + "; this host builds exactly ray0..ray2";
        return null;
    }

    public static Result load(Path pack, PackReport report) throws IOException, SpirvCompiler.SpirvCompileException {
        String reason = unsupportedReason(report);
        if (reason != null) throw new IllegalArgumentException(reason);
        try (PackSource src = PackSource.open(pack, ScanLimits.defaults())) {
            IrisPackOptions options = IrisPackOptions.resolve(src, report, IrisPackOptions.readRequest(pack));
            Set<String> entries = new LinkedHashSet<>(src.entries());
            String root = report.shadersRoot();
            IncludeExpander.Reader reader = entry -> {
                if (!entries.contains(entry)) throw new IOException("missing pack file: " + entry);
                // Options live in .glsl includes; entry stage files are read verbatim.
                return entry.matches(".*\\.(?:vsh|fsh|glsl|inc)$") ? options.readSource(entry)
                    : new String(src.read(entry), StandardCharsets.UTF_8);
            };
            Map<String, String> macros = Map.of();
            List<Compiled> stages = new ArrayList<>(DirtRtPipeline.TOTAL_STAGES);
            Map<RayPass, Compiled[]> byPass = new LinkedHashMap<>();
            for (RayPass pass : DirtRtPipelineConfig.PASSES) {
                String[] files = {pass.raygenShader(), pass.missShader(), pass.closestHitShader(), pass.anyHitShader()};
                Kind[] kinds = {Kind.RAYGEN, Kind.MISS, Kind.CLOSEST_HIT, Kind.ANY_HIT};
                Compiled[] compiled = new Compiled[4];
                for (int s = 0; s < 4; s++) {
                    String entry = root + files[s];
                    if (!entries.contains(entry)) throw new IOException("pack has no " + files[s]);
                    compiled[s] = VulkanGlslPackCompiler.compile(root, entry, reader, kinds[s], macros);
                    stages.add(compiled[s]);
                }
                byPass.put(pass, compiled);
            }
            // DirtRtPipeline stage order is RAYGEN, MISS, CLOSEST_HIT, ANY_HIT (ShaderStageType order).
            DirtRtShaderBundle bundle = new DirtRtShaderBundle(
                passShaders(RayPass.DIFFUSE, byPass), passShaders(RayPass.REFLECTION, byPass), passShaders(RayPass.REFRACTION, byPass));

            List<SpirvReflection.Module> modules = stages.stream().map(Compiled::module).toList();
            for (Compiled c : stages) {
                if (c.module().pushConstants())
                    throw new IllegalArgumentException(c.entry() + " declares push constants; this host binds descriptors only");
            }
            List<SpirvReflection.Binding> merged = SpirvReflection.merge(modules);
            Map<String, Integer> flags = new TreeMap<>();
            for (Compiled c : stages) {
                int bit = stageBit(c.kind());
                for (SpirvReflection.Binding b : c.module().bindings()) flags.merge(b.key(), bit, (a, x) -> a | x);
            }
            List<BindingStatus> statuses = new ArrayList<>();
            for (SpirvReflection.Binding b : merged) {
                statuses.add(classify(b, flags.getOrDefault(b.key(), 0), report));
            }

            Set<Integer> capabilities = new LinkedHashSet<>();
            boolean scalar = false;
            for (Compiled c : stages) {
                capabilities.addAll(c.module().capabilities());
                scalar |= c.scalarBlockLayout();
            }
            SpirvFeatureRequirements.Result feats = SpirvFeatureRequirements.of(capabilities, scalar);
            if (!feats.unknown().isEmpty())
                throw new IllegalArgumentException("compiled stages use SPIR-V capabilities this host cannot map to device features: "
                    + feats.unknown());
            List<FeatureRequest> features = new ArrayList<>(feats.features());
            List<String> text = new ArrayList<>(feats.description());
            if (statuses.stream().anyMatch(s -> s.binding().runtimeArray())) {
                // A layout with an unbounded descriptor array must allow unwritten elements and a variable count.
                features.add(FeatureRequest.core(FeatureRequest.VK12, "descriptorBindingPartiallyBound"));
                features.add(FeatureRequest.core(FeatureRequest.VK12, "descriptorBindingVariableDescriptorCount"));
                text.add("variable-count descriptor arrays");
            }
            return new Result(report.name(), bundle, List.copyOf(stages), List.copyOf(statuses), List.copyOf(features),
                List.copyOf(text), options.summary().changedOptions());
        }
    }

    private static PassShaders passShaders(RayPass pass, Map<RayPass, Compiled[]> byPass) {
        Compiled[] c = byPass.get(pass);
        return new PassShaders(pass, c[0].spirv(), c[1].spirv(), c[2].spirv(), c[3].spirv());
    }

    static int stageBit(Kind kind) {
        return switch (kind) {
            case RAYGEN -> KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
            case MISS -> KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
            case CLOSEST_HIT -> KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
            case ANY_HIT -> KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
            case COMPUTE -> org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
        };
    }

    /**
     * Maps one reflected binding to a host producer. The table is deliberately short: it lists only producers that exist
     * in Bedray code. Everything Vulkanite feeds from its own geometry pipeline or from Iris SSBO/custom-image programs is
     * reported as missing with the reason.
     */
    static BindingStatus classify(SpirvReflection.Binding b, int stageFlags, PackReport report) {
        if (b.kind() == SpirvReflection.DescriptorKind.ACCELERATION_STRUCTURE && b.count() == 1)
            return new BindingStatus(b, stageFlags, Producer.HOST_TLAS, "");
        if (b.set() == 0 && b.binding() == 0 && b.kind() == SpirvReflection.DescriptorKind.UNIFORM_BUFFER)
            return new BindingStatus(b, stageFlags, Producer.HOST_CAMERA_UBO, "");
        String note;
        if (b.runtimeArray() && b.kind() == SpirvReflection.DescriptorKind.STORAGE_BUFFER)
            note = "Vulkanite per-geometry quad buffers indexed by instance custom index + geometry index; Bedray keeps no geometry in that layout";
        else if (b.kind() == SpirvReflection.DescriptorKind.STORAGE_BUFFER && b.set() == 3 && report.bufferObjects().contains(b.binding()))
            note = "Iris bufferObject." + b.binding() + "; its contents come from the pack's compute/composite programs, which this host cannot run";
        else if (b.kind() == SpirvReflection.DescriptorKind.COMBINED_IMAGE_SAMPLER)
            note = "terrain atlas / LabPBR atlas as Vulkanite binds it; not exported by the host";
        else if (b.kind() == SpirvReflection.DescriptorKind.STORAGE_IMAGE)
            note = "Vulkanite RT output image consumed by the pack's later programs";
        else
            note = "unknown resource";
        return new BindingStatus(b, stageFlags, Producer.NONE, note);
    }
}
