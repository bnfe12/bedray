package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable, frame-local routing for the bounded composite/final resource graph.
 * Logical colortex indices are independent of fragment output locations and physical image banks.
 * Each frame starts with the world image in colortex0 bank zero; auxiliary buffers have no initial
 * contents. A sampled auxiliary buffer must have been produced by an earlier active pass.
 * No image contents or bank selection carry across frame boundaries.
 */
public final class IrisResourcePlan {
    public static final int MAX_PASSES = 32;
    public static final int MAX_COLOR_ATTACHMENTS = 4;

    /**
     * One logical output. A defined previous bank is copied before drawing to preserve discarded
     * pixels; a first auxiliary write instead clears its output bank to transparent black.
     */
    public record Write(int target, int bank, int previousBank, boolean preserve) {}

    /**
     * Sampler inputs refer to the banks before any of this pass's outputs are selected. Unused
     * descriptors may sample colortex0 at fallbackBank. A final pass writes a separate presentation
     * image, so its writes list is empty; copy colortex0 at fallbackBank to it before drawing.
     */
    public record Step(IrisPassGraph.Pass pass, Map<Integer, Integer> readBanks, int fallbackBank,
                       List<Write> writes, boolean finalPass) {
        public Step {
            Objects.requireNonNull(pass, "pass");
            readBanks = Collections.unmodifiableMap(new LinkedHashMap<>(readBanks));
            writes = List.copyOf(writes);
        }
    }

    private final Set<Integer> targets;
    private final List<Step> steps;
    private final int resultBank;
    private final boolean hasFinal;

    private IrisResourcePlan(Set<Integer> targets, List<Step> steps, int resultBank, boolean hasFinal) {
        this.targets = Collections.unmodifiableSet(new LinkedHashSet<>(targets));
        this.steps = List.copyOf(steps);
        this.resultBank = resultBank;
        this.hasFinal = hasFinal;
    }

    /** Logical targets that need an owned pair of images, in ascending target order. */
    public Set<Integer> targets() { return targets; }
    public List<Step> steps() { return steps; }
    /** Last current colortex0 bank; use it for presentation when no final pass is present. */
    public int resultBank() { return resultBank; }
    public boolean hasFinal() { return hasFinal; }

    /** Rejects unavailable inputs or ambiguous routing before recording any Vulkan commands. */
    public static IrisResourcePlan build(IrisPassGraph graph) {
        Objects.requireNonNull(graph, "graph");
        if (graph.passCount() > MAX_PASSES)
            throw new IllegalArgumentException("Iris graph exceeds " + MAX_PASSES + " passes");

        int[] banks = new int[IrisUniforms.COLOR_TARGETS];
        Arrays.fill(banks, -1);
        banks[0] = 0;
        Set<Integer> targets = new TreeSet<>();
        targets.add(0);
        Set<String> names = new LinkedHashSet<>();
        List<Step> steps = new ArrayList<>();
        boolean hasFinal = false;

        for (int index = 0; index < graph.passCount(); index++) {
            IrisPassGraph.Pass pass = graph.passes().get(index);
            if (!names.add(pass.name()))
                throw invalid(pass, "duplicate program name");
            if (pass.isFinal() && (hasFinal || index != graph.passCount() - 1))
                throw invalid(pass, "final must be unique and last");

            List<Integer> outputs = pass.drawBuffers();
            if (outputs.isEmpty() || outputs.size() > MAX_COLOR_ATTACHMENTS)
                throw invalid(pass, "requires one to " + MAX_COLOR_ATTACHMENTS + " color outputs");
            Set<Integer> distinct = new LinkedHashSet<>();
            for (int target : outputs) {
                if (target < 0 || target >= IrisUniforms.COLOR_TARGETS)
                    throw invalid(pass, "colortex index " + target + " is outside 0.." + (IrisUniforms.COLOR_TARGETS - 1));
                if (!distinct.add(target)) throw invalid(pass, "duplicate output colortex" + target);
            }
            if (pass.isFinal() && !outputs.equals(List.of(0)))
                throw invalid(pass, "final has one presentation output at location zero");

            // Resolve every sampled input before changing any output bank. Reading a newly written
            // auxiliary attachment in the same pass cannot substitute for a preceding producer.
            Map<Integer, Integer> reads = new LinkedHashMap<>();
            for (int binding : pass.allSamplerBindings()) {
                int target = IrisUniforms.colorIndex(binding);
                if (target < 0) {
                    if (binding != IrisUniforms.BINDING_DEPTHTEX0)
                        throw invalid(pass, "unknown sampler binding " + binding);
                    continue;
                }
                if (banks[target] < 0)
                    throw invalid(pass, "samples colortex" + target + " before an earlier pass writes it");
                reads.put(target, banks[target]);
                targets.add(target);
            }

            int fallback = banks[0];
            List<Write> writes = new ArrayList<>();
            if (pass.isFinal()) {
                hasFinal = true;
            } else {
                for (int target : outputs) {
                    int previous = banks[target];
                    int next = previous < 0 ? 0 : 1 - previous;
                    writes.add(new Write(target, next, previous, previous >= 0));
                    banks[target] = next;
                    targets.add(target);
                }
            }
            steps.add(new Step(pass, reads, fallback, writes, pass.isFinal()));
        }
        return new IrisResourcePlan(targets, steps, banks[0], hasFinal);
    }

    private static IllegalArgumentException invalid(IrisPassGraph.Pass pass, String reason) {
        return new IllegalArgumentException("Iris resource plan for " + pass.name() + ": " + reason);
    }
}
