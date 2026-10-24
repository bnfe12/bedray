package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Minimal SPIR-V reader for the facts a host needs before it can build a pipeline layout for a shader it did not write:
 * descriptor bindings (set, binding, descriptor kind, array size), push-constant use, capabilities, execution model and
 * the compute local size. It reads the module the compiler produced, so the layout always matches the code instead of a
 * hand-written guess. Only the instructions listed below are interpreted; everything else is skipped by word count.
 * <p>
 * No Vulkan or Minecraft dependency; safe to call before the device exists.
 */
public final class SpirvReflection {
    public static final int MAGIC = 0x07230203;

    // Opcodes
    private static final int OP_NAME = 5, OP_ENTRY_POINT = 15, OP_EXECUTION_MODE = 16, OP_CAPABILITY = 17,
        OP_TYPE_IMAGE = 25, OP_TYPE_SAMPLER = 26, OP_TYPE_SAMPLED_IMAGE = 27, OP_TYPE_ARRAY = 28,
        OP_TYPE_RUNTIME_ARRAY = 29, OP_TYPE_STRUCT = 30, OP_TYPE_POINTER = 32, OP_CONSTANT = 43, OP_VARIABLE = 59,
        OP_DECORATE = 71, OP_EXECUTION_MODE_ID = 331, OP_TYPE_ACCELERATION_STRUCTURE = 5341;
    // Decorations
    private static final int DEC_BLOCK = 2, DEC_BUFFER_BLOCK = 3, DEC_BINDING = 33, DEC_DESCRIPTOR_SET = 34;
    // Storage classes
    private static final int SC_UNIFORM_CONSTANT = 0, SC_UNIFORM = 2, SC_PUSH_CONSTANT = 9, SC_STORAGE_BUFFER = 12;
    // Execution modes
    private static final int EM_LOCAL_SIZE = 17, EM_LOCAL_SIZE_ID = 38;

    /** Execution models this host distinguishes. */
    public enum Stage {
        VERTEX(0), FRAGMENT(4), COMPUTE(5), RAYGEN(5313), INTERSECTION(5314), ANY_HIT(5315), CLOSEST_HIT(5316),
        MISS(5317), CALLABLE(5318), OTHER(-1);

        final int model;

        Stage(int model) {
            this.model = model;
        }

        static Stage of(int model) {
            for (Stage s : values()) if (s.model == model) return s;
            return OTHER;
        }
    }

    public enum DescriptorKind {
        UNIFORM_BUFFER, STORAGE_BUFFER, COMBINED_IMAGE_SAMPLER, SAMPLED_IMAGE, STORAGE_IMAGE, SAMPLER,
        UNIFORM_TEXEL_BUFFER, STORAGE_TEXEL_BUFFER, INPUT_ATTACHMENT, ACCELERATION_STRUCTURE
    }

    /**
     * One descriptor binding.
     *
     * @param count array size; 1 for a plain resource, 0 for a runtime-sized ({@code []}) array
     * @param name  debug name of the variable, or of its block type when the variable is anonymous; may be empty
     */
    public record Binding(int set, int binding, DescriptorKind kind, int count, String name) {
        public boolean runtimeArray() {
            return count == 0;
        }

        public String key() {
            return set + "." + binding;
        }
    }

    /**
     * @param stage         execution model of the first entry point
     * @param capabilities  numeric SPIR-V capabilities declared by the module
     * @param bindings      descriptor bindings sorted by set and binding
     * @param pushConstants whether a push-constant block is declared
     * @param localSize     compute local size (x, y, z); {1,1,1} when not a compute module or not literal
     */
    public record Module(Stage stage, Set<Integer> capabilities, List<Binding> bindings, boolean pushConstants, int[] localSize) {
        public Module {
            capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities));
            bindings = List.copyOf(bindings);
            localSize = localSize.clone();
        }

        @Override
        public int[] localSize() {
            return localSize.clone();
        }
    }

    private SpirvReflection() {}

    /** Reads a module; the buffer's position and order are not changed. */
    public static Module reflect(ByteBuffer spirv) {
        Objects.requireNonNull(spirv, "spirv");
        ByteBuffer b = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = b.position();
        int words = b.remaining() / 4;
        if (words < 5 || b.remaining() % 4 != 0) throw new IllegalArgumentException("SPIR-V module is truncated");
        if (b.getInt(base) != MAGIC) {
            b.order(ByteOrder.BIG_ENDIAN);
            if (b.getInt(base) != MAGIC) throw new IllegalArgumentException("not a SPIR-V module");
        }
        int[] w = new int[words];
        for (int i = 0; i < words; i++) w[i] = b.getInt(base + i * 4);
        return parse(w);
    }

    private record Type(int opcode, int[] operands) {}

    private static Module parse(int[] w) {
        Map<Integer, Type> types = new HashMap<>();
        Map<Integer, Long> constants = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, Integer> sets = new HashMap<>();
        Map<Integer, Integer> bindings = new HashMap<>();
        Set<Integer> blocks = new LinkedHashSet<>();
        Set<Integer> bufferBlocks = new LinkedHashSet<>();
        Set<Integer> capabilities = new LinkedHashSet<>();
        List<int[]> variables = new ArrayList<>(); // {resultType, id, storageClass}
        Stage stage = null;
        int entryId = -1;
        int[] localSize = {1, 1, 1};
        int[] localSizeIds = null;

        int i = 5;
        while (i < w.length) {
            int count = w[i] >>> 16;
            int op = w[i] & 0xFFFF;
            if (count == 0 || i + count > w.length) throw new IllegalArgumentException("malformed SPIR-V instruction at word " + i);
            switch (op) {
                case OP_CAPABILITY -> capabilities.add(w[i + 1]);
                case OP_ENTRY_POINT -> {
                    if (stage == null) {
                        stage = Stage.of(w[i + 1]);
                        entryId = w[i + 2];
                    }
                }
                case OP_EXECUTION_MODE -> {
                    if (w[i + 1] == entryId && w[i + 2] == EM_LOCAL_SIZE && count >= 6) {
                        localSize = new int[] {w[i + 3], w[i + 4], w[i + 5]};
                    }
                }
                case OP_EXECUTION_MODE_ID -> {
                    if (w[i + 1] == entryId && w[i + 2] == EM_LOCAL_SIZE_ID && count >= 6) {
                        localSizeIds = new int[] {w[i + 3], w[i + 4], w[i + 5]};
                    }
                }
                case OP_NAME -> names.put(w[i + 1], string(w, i + 2, i + count));
                case OP_DECORATE -> {
                    int target = w[i + 1];
                    int decoration = w[i + 2];
                    if (decoration == DEC_DESCRIPTOR_SET && count >= 4) sets.put(target, w[i + 3]);
                    else if (decoration == DEC_BINDING && count >= 4) bindings.put(target, w[i + 3]);
                    else if (decoration == DEC_BLOCK) blocks.add(target);
                    else if (decoration == DEC_BUFFER_BLOCK) bufferBlocks.add(target);
                }
                case OP_TYPE_IMAGE, OP_TYPE_SAMPLER, OP_TYPE_SAMPLED_IMAGE, OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY,
                     OP_TYPE_STRUCT, OP_TYPE_POINTER, OP_TYPE_ACCELERATION_STRUCTURE -> {
                    int[] operands = new int[count - 2];
                    System.arraycopy(w, i + 2, operands, 0, operands.length);
                    types.put(w[i + 1], new Type(op, operands));
                }
                case OP_CONSTANT -> {
                    if (count >= 4) {
                        long value = w[i + 3] & 0xFFFFFFFFL;
                        if (count >= 5) value |= ((long) w[i + 4]) << 32;
                        constants.put(w[i + 2], value);
                    }
                }
                case OP_VARIABLE -> variables.add(new int[] {w[i + 1], w[i + 2], w[i + 3]});
                default -> { }
            }
            i += count;
        }
        if (localSizeIds != null) {
            for (int axis = 0; axis < 3; axis++) {
                Long value = constants.get(localSizeIds[axis]);
                if (value != null) localSize[axis] = value.intValue();
            }
        }

        boolean push = false;
        TreeMap<Long, Binding> out = new TreeMap<>();
        for (int[] v : variables) {
            int storage = v[2];
            if (storage == SC_PUSH_CONSTANT) {
                push = true;
                continue;
            }
            if (storage != SC_UNIFORM_CONSTANT && storage != SC_UNIFORM && storage != SC_STORAGE_BUFFER) continue;
            Integer set = sets.get(v[1]);
            Integer binding = bindings.get(v[1]);
            if (set == null || binding == null) continue;
            Type pointer = types.get(v[0]);
            if (pointer == null || pointer.opcode() != OP_TYPE_POINTER) continue;
            int typeId = pointer.operands()[1];
            int arrayCount = 1;
            Type t = types.get(typeId);
            while (t != null && (t.opcode() == OP_TYPE_ARRAY || t.opcode() == OP_TYPE_RUNTIME_ARRAY)) {
                if (t.opcode() == OP_TYPE_RUNTIME_ARRAY) {
                    arrayCount = 0;
                } else {
                    Long len = constants.get(t.operands()[1]);
                    if (len == null) throw new IllegalArgumentException("descriptor array length is not a literal constant (set "
                        + set + " binding " + binding + ")");
                    arrayCount = arrayCount == 0 ? 0 : Math.multiplyExact(arrayCount, len.intValue());
                }
                typeId = t.operands()[0];
                t = types.get(typeId);
            }
            DescriptorKind kind = kindOf(storage, typeId, t, blocks, bufferBlocks);
            if (kind == null) continue;
            String name = names.getOrDefault(v[1], "");
            if (name.isEmpty()) name = names.getOrDefault(typeId, "");
            out.put(((long) set << 32) | (binding & 0xFFFFFFFFL), new Binding(set, binding, kind, arrayCount, name));
        }
        return new Module(stage == null ? Stage.OTHER : stage, capabilities, new ArrayList<>(out.values()), push, localSize);
    }

    private static DescriptorKind kindOf(int storage, int typeId, Type t, Set<Integer> blocks, Set<Integer> bufferBlocks) {
        if (storage == SC_STORAGE_BUFFER) return DescriptorKind.STORAGE_BUFFER;
        if (storage == SC_UNIFORM) {
            if (bufferBlocks.contains(typeId)) return DescriptorKind.STORAGE_BUFFER;
            return blocks.contains(typeId) || (t != null && t.opcode() == OP_TYPE_STRUCT) ? DescriptorKind.UNIFORM_BUFFER : null;
        }
        if (t == null) return null;
        return switch (t.opcode()) {
            case OP_TYPE_SAMPLED_IMAGE -> DescriptorKind.COMBINED_IMAGE_SAMPLER;
            case OP_TYPE_SAMPLER -> DescriptorKind.SAMPLER;
            case OP_TYPE_ACCELERATION_STRUCTURE -> DescriptorKind.ACCELERATION_STRUCTURE;
            case OP_TYPE_IMAGE -> {
                // operands: sampledType, Dim, Depth, Arrayed, MS, Sampled, Format
                int dim = t.operands()[1];
                int sampled = t.operands()[5];
                if (dim == 6) yield DescriptorKind.INPUT_ATTACHMENT;
                if (dim == 5) yield sampled == 2 ? DescriptorKind.STORAGE_TEXEL_BUFFER : DescriptorKind.UNIFORM_TEXEL_BUFFER;
                yield sampled == 2 ? DescriptorKind.STORAGE_IMAGE : DescriptorKind.SAMPLED_IMAGE;
            }
            default -> null;
        };
    }

    private static String string(int[] w, int from, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < end; i++) {
            int word = w[i];
            for (int k = 0; k < 4; k++) {
                int c = (word >>> (8 * k)) & 0xFF;
                if (c == 0) return sb.toString();
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    /**
     * Merges the bindings of several stages that share one pipeline layout. The same set/binding must declare the same
     * kind and array size in every stage that uses it; a conflict is a pack error, not something to paper over.
     */
    public static List<Binding> merge(List<Module> modules) {
        TreeMap<Long, Binding> merged = new TreeMap<>();
        for (Module m : modules) {
            for (Binding b : m.bindings()) {
                long key = ((long) b.set() << 32) | (b.binding() & 0xFFFFFFFFL);
                Binding prev = merged.get(key);
                if (prev == null) {
                    merged.put(key, b);
                } else if (prev.kind() != b.kind() || prev.count() != b.count()) {
                    throw new IllegalArgumentException("stages disagree on set " + b.set() + " binding " + b.binding() + ": "
                        + prev.kind() + "[" + prev.count() + "] vs " + b.kind() + "[" + b.count() + "]");
                } else if (prev.name().isEmpty() && !b.name().isEmpty()) {
                    merged.put(key, b);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }
}
