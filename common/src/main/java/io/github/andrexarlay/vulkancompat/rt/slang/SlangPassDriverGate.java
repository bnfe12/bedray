package io.github.andrexarlay.vulkancompat.rt.slang;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses a Slang module that the NVIDIA driver cannot compile.
 *
 * <p>Reading a resource handle that no binding ever assigned makes {@code nvoglv64.dll} take an access
 * violation inside {@code vkCreateComputePipelines}. That is not a {@code VkResult} and not a Java
 * exception: the JVM dies with the game, so nothing on this side of the call can catch it and the only
 * defence is to never hand the driver that module. Slang lowers an unassigned resource field to an
 * {@code OpUndef} pointer, which is visible in the compiled artifact, so the rule reads the bytes rather
 * than a list of names: a pass fixed in Slang stops being refused without touching this file.
 *
 * <p>A headless replay of the four Vulkan steps {@code bedray_core_register_program} performs on the
 * owner's GPU (RTX 5060 Ti) faults on some modules the game registers without trouble - {@code path_trace}
 * and {@code restir_spatial} among them - because the replay brings up a leaner device than the game's
 * Vulkan backend does. A replay fault is therefore only evidence when the module has never been
 * registered in a live frame either; those names are listed below and nothing else is.
 *
 * <p>Ways this silently breaks:
 * <ul>
 *   <li>An {@code OpUndef} pointer that the march never reads still refuses the module, so a pass can go
 *       missing with no shader-side symptom.</li>
 *   <li>A name added to the replay list without a faulting replay puts a working feature behind a refusal,
 *       and the log line that explains it is the only trace.</li>
 *   <li>A driver update can change either verdict, so the property below exists to retest.</li>
 * </ul>
 * Override with {@code -Dbedray.slang.passes=a,b,c} (extra names) or {@code -Dbedray.slang.passes=all} to
 * remove the gate.
 */
public final class SlangPassDriverGate {
    /** Property that widens the gate; {@code all} removes it. */
    public static final String PASSES_PROPERTY = "bedray.slang.passes";

    /** Faulted in the headless replay and never registered by a live frame. */
    private static final Set<String> REPLAY_REFUSED = Set.of("volumetric_march_scene", "rc_probe_trace",
        "velocity_reproject");

    // SPIR-V words, little endian: OpUndef=1, OpTypePointer=32. A pointer carries Result Id,
    // Storage Class and the pointee type in that order.
    private static final int OP_UNDEF = 1;
    private static final int OP_TYPE_POINTER = 32;
    private static final int SPIRV_MAGIC = 0x07230203;
    /** UniformConstant, Uniform and StorageBuffer - the classes a descriptor slot can hold. */
    private static final int STORAGE_UNIFORM_CONSTANT = 0;
    private static final int STORAGE_UNIFORM = 2;
    private static final int STORAGE_STORAGE_BUFFER = 12;

    private SlangPassDriverGate() {
    }

    /** True when {@code name} with module {@code spirv} may be handed to the driver. */
    public static boolean permits(String name, byte[] spirv) {
        return blocker(name, spirv) == null;
    }

    /** The replay-list half of the decision, for diagnostics that have no module bytes at hand. */
    public static String blocker(String name) {
        return blocker(name, null);
    }

    /** Why {@code name} must not reach the driver, or {@code null} when it may. */
    public static String blocker(String name, byte[] spirv) {
        if (name == null || name.isEmpty()) {
            return "the pass name is empty";
        }
        String override = System.getProperty(PASSES_PROPERTY, "").toLowerCase(Locale.ROOT);
        if (override.equals("all") || contains(override, name)) {
            return null;
        }
        int undefs = undefinedDescriptorPointers(spirv);
        if (undefs > 0) {
            return name + " reads " + undefs + " descriptor handle(s) that no binding assigns (SPIR-V "
                + "OpUndef pointer); the NVIDIA driver faults while compiling the pipeline. Bind the "
                + "resource or drop the code path - see docs/AUDIT_DEFECTS_AND_ISSUES.md P0-9. Set -D"
                + PASSES_PROPERTY + "=" + name + " to try it anyway";
        }
        if (REPLAY_REFUSED.contains(name)) {
            return name + " faulted the headless driver replay and no live frame has ever registered it; "
                + "set -D" + PASSES_PROPERTY + "=" + name + " to try it";
        }
        return null;
    }

    /** Names this gate blocks without looking at a module, for diagnostics and tests. */
    public static Set<String> blockedNames() {
        Set<String> out = new LinkedHashSet<>();
        for (String name : REPLAY_REFUSED) {
            if (blocker(name, new byte[0]) != null) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Counts {@code OpUndef} results whose type is a pointer into a descriptor slot. Zero is the only
     * answer a module may have: a handle to a buffer, image, sampler, acceleration structure or ray query
     * that no binding ever assigned is what the driver dereferences into an access violation.
     */
    public static int undefinedDescriptorPointers(byte[] spirv) {
        if (spirv == null || spirv.length < 24 || spirv.length % 4 != 0) {
            return 0;
        }
        int count = spirv.length / 4;
        int[] words = new int[count];
        for (int i = 0; i < count; i++) {
            int b = i * 4;
            words[i] = (spirv[b] & 0xFF) | ((spirv[b + 1] & 0xFF) << 8)
                | ((spirv[b + 2] & 0xFF) << 16) | ((spirv[b + 3] & 0xFF) << 24);
        }
        if (words[0] != SPIRV_MAGIC) {
            return 0;
        }
        // OpTypePointer carries Result Id, Storage Class and the pointee type in that order. Result ids
        // are numbered independently of the word count, so the index space comes from the header bound.
        boolean[] descriptorPointer = new boolean[Math.max(words[3], count)];
        int i = 5;
        while (i + 1 < count) {
            int size = words[i] >>> 16;
            int opcode = words[i] & 0xFFFF;
            if (size <= 0 || i + size > count) {
                break;
            }
            if (opcode == OP_TYPE_POINTER && size >= 4) {
                int id = words[i + 1];
                int storage = words[i + 2];
                if (id >= 0 && id < descriptorPointer.length && (storage == STORAGE_UNIFORM_CONSTANT
                    || storage == STORAGE_UNIFORM || storage == STORAGE_STORAGE_BUFFER)) {
                    descriptorPointer[id] = true;
                }
            }
            i += size;
        }
        int undefs = 0;
        i = 5;
        while (i + 1 < count) {
            int size = words[i] >>> 16;
            int opcode = words[i] & 0xFFFF;
            if (size <= 0 || i + size > count) {
                break;
            }
            if (opcode == OP_UNDEF && size >= 2 && words[i + 1] >= 0
                && words[i + 1] < descriptorPointer.length && descriptorPointer[words[i + 1]]) {
                undefs++;
            }
            i += size;
        }
        return undefs;
    }

    private static boolean contains(String csv, String name) {
        for (String part : csv.split(",")) {
            if (part.trim().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
