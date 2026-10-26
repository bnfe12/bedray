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
 * Immutable per-frame routing of the post resource graph. Logical colortex indices are independent of fragment output
 * locations and of the two physical banks each target owns.
 * <p>
 * Every frame starts with each target's defined value in bank 0: colortex0 holds the completed world image, a target with
 * {@code colortexNClear = true} (the default) holds its clear color, and a persistent target ({@code Clear = false}) holds
 * its value from the end of the previous frame. Reading a target before any pass of this frame wrote it therefore reads
 * that value, as in Iris for a pack without gbuffers. A write copies the current bank into the other bank and draws there,
 * so pixels the pass does not cover keep their value; reads within the pass still see the previous value. A persistent
 * target that ends a frame in bank 1 is copied back to bank 0, so the next frame starts from a fixed state.
 */
public final class IrisResourcePlan {
    public static final int MAX_PASSES = 32;
    public static final int MAX_COLOR_ATTACHMENTS = IrisUniforms.MAX_DRAW_BUFFERS;

    /** One logical output: copy {@code previousBank} into {@code bank}, then draw into {@code bank}. */
    public record Write(int target, int bank, int previousBank) {}

    /**
     * Sampler inputs refer to the banks before any of this pass's outputs are selected. Unused descriptors may sample
     * colortex0 at fallbackBank. A final pass writes a separate presentation image, so its writes list is empty; copy
     * colortex0 at fallbackBank to it before drawing. {@code mipmapped}: sampled targets whose mip chain this pass enables;
     * the executor regenerates their mips on the read bank before the pass.
     */
    public record Step(IrisPassGraph.Pass pass, Map<Integer, Integer> readBanks, int fallbackBank,
                       List<Write> writes, boolean finalPass, Set<Integer> mipmapped) {
        public Step {
            Objects.requireNonNull(pass, "pass");
            readBanks = Collections.unmodifiableMap(new LinkedHashMap<>(readBanks));
            writes = List.copyOf(writes);
            mipmapped = Set.copyOf(mipmapped);
        }

        public Step(IrisPassGraph.Pass pass, Map<Integer, Integer> readBanks, int fallbackBank, List<Write> writes, boolean finalPass) {
            this(pass, readBanks, fallbackBank, writes, finalPass, Set.of());
        }
    }

    private final Set<Integer> targets;
    private final Set<Integer> mipmappedTargets;
    private final List<Step> steps;
    private final int resultBank;
    private final boolean hasFinal;
    private final List<Integer> persistCopies;
    private final IrisRenderTargets.Resolution settings;

    private IrisResourcePlan(Set<Integer> targets, Set<Integer> mipmappedTargets, List<Step> steps, int resultBank,
                             boolean hasFinal, List<Integer> persistCopies, IrisRenderTargets.Resolution settings) {
        this.targets = Collections.unmodifiableSet(new LinkedHashSet<>(targets));
        this.mipmappedTargets = Collections.unmodifiableSet(new LinkedHashSet<>(mipmappedTargets));
        this.steps = List.copyOf(steps);
        this.resultBank = resultBank;
        this.hasFinal = hasFinal;
        this.persistCopies = List.copyOf(persistCopies);
        this.settings = settings;
    }

    /** Logical targets that need an owned pair of images, in ascending target order. */
    public Set<Integer> targets() { return targets; }
    /** Targets that need a mip chain because at least one pass enables it. */
    public Set<Integer> mipmappedTargets() { return mipmappedTargets; }
    public List<Step> steps() { return steps; }
    /** Last current colortex0 bank; use it for presentation when no final pass is present. */
    public int resultBank() { return resultBank; }
    public boolean hasFinal() { return hasFinal; }
    /** Persistent targets whose last write landed in bank 1: copy bank 1 to bank 0 at the end of the frame. */
    public List<Integer> persistCopies() { return persistCopies; }
    public IrisRenderTargets.Resolution settings() { return settings; }

    /** Targets cleared at the start of every frame (allocated, not colortex0, clear enabled). */
    public List<Integer> frameClears() {
        List<Integer> out = new ArrayList<>();
        for (int t : targets) if (t != 0 && settings.target(t).clear()) out.add(t);
        return out;
    }

    /** Rejects ambiguous routing before recording any Vulkan commands. */
    public static IrisResourcePlan build(IrisPassGraph graph) {
        Objects.requireNonNull(graph, "graph");
        if (graph.passCount() > MAX_PASSES)
            throw new IllegalArgumentException("Iris graph exceeds " + MAX_PASSES + " passes");
        IrisRenderTargets.Resolution settings = graph.targets();
        if (!settings.problems().isEmpty())
            throw new IllegalArgumentException("Iris render targets: " + String.join("; ", settings.problems()));

        int[] banks = new int[IrisUniforms.COLOR_TARGETS];
        Arrays.fill(banks, 0);
        Set<Integer> targets = new TreeSet<>();
        targets.add(0);
        Set<Integer> mipmappedTargets = new TreeSet<>();
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

            // Resolve every sampled input before changing any output bank.
            Map<Integer, Integer> reads = new LinkedHashMap<>();
            for (int binding : pass.allSamplerBindings()) {
                int target = IrisUniforms.colorIndex(binding);
                if (target < 0) {
                    if (binding != IrisUniforms.BINDING_DEPTHTEX0)
                        throw invalid(pass, "unknown sampler binding " + binding);
                    continue;
                }
                reads.put(target, banks[target]);
                targets.add(target);
            }
            Set<Integer> mips = new TreeSet<>(settings.mipmapped(pass.name()));
            mips.retainAll(reads.keySet()); // Iris generates mips only for buffers the program samples.
            mipmappedTargets.addAll(mips);

            int fallback = banks[0];
            List<Write> writes = new ArrayList<>();
            if (pass.isFinal()) {
                hasFinal = true;
            } else {
                for (int target : outputs) {
                    int previous = banks[target];
                    int next = 1 - previous;
                    writes.add(new Write(target, next, previous));
                    banks[target] = next;
                    targets.add(target);
                }
            }
            steps.add(new Step(pass, reads, fallback, writes, pass.isFinal(), mips));
        }
        List<Integer> persistCopies = new ArrayList<>();
        for (int t : targets) if (settings.target(t).persistent() && banks[t] != 0) persistCopies.add(t);
        return new IrisResourcePlan(targets, mipmappedTargets, steps, banks[0], hasFinal, persistCopies, settings);
    }

    private static IllegalArgumentException invalid(IrisPassGraph.Pass pass, String reason) {
        return new IllegalArgumentException("Iris resource plan for " + pass.name() + ": " + reason);
    }
}
