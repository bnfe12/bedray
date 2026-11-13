package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;

import static io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssLayouts.offset;
import static io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssNative.invokeInt;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** The versioned Neural Rendering extension of vkb_dlss.dll, owned by the direct NGX session. */
public final class DlssNrNative {
    static final String VERSION_SYMBOL = "vkb_dlss_nr_contract_version";
    private static final int CONTRACT_VERSION = 1;
    private static final StructLayout CAPS = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"), JAVA_INT.withName("available"),
        JAVA_INT.withName("needs_updated_driver"), JAVA_INT.withName("min_driver_major"),
        JAVA_INT.withName("min_driver_minor"), JAVA_INT.withName("feature_init_result"));
    private static final StructLayout CREATE = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"), JAVA_INT.withName("width"), JAVA_INT.withName("height"),
        JAVA_INT.withName("reserved"));
    private static final StructLayout EVAL = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"), JAVA_INT.withName("reset"),
        DlssLayouts.IMAGE.withName("color"), DlssLayouts.IMAGE.withName("output"),
        DlssLayouts.IMAGE.withName("depth"), DlssLayouts.IMAGE.withName("motion_vectors"),
        DlssLayouts.IMAGE.withName("control_mask"),
        JAVA_FLOAT.withName("jitter_x"), JAVA_FLOAT.withName("jitter_y"),
        JAVA_FLOAT.withName("mv_scale_x"), JAVA_FLOAT.withName("mv_scale_y"),
        JAVA_FLOAT.withName("intensity"), JAVA_FLOAT.withName("tone_strength"),
        JAVA_FLOAT.withName("structure_strength"), JAVA_FLOAT.withName("skin_structure_strength"),
        JAVA_INT.withName("model"), JAVA_INT.withName("use_auto_mask"), JAVA_INT.withName("depth_inverted"),
        JAVA_INT.withName("reserved"));

    public record Caps(DlssNative.Status status, boolean available, boolean needsUpdatedDriver,
                       int minDriverMajor, int minDriverMinor, int featureInitResult) {}

    public record EvalInfo(boolean reset, DlssNative.Image color, DlssNative.Image output, DlssNative.Image depth,
                           DlssNative.Image motionVectors, DlssNative.Image controlMask, float jitterX, float jitterY,
                           float mvScaleX, float mvScaleY, float intensity, float toneStrength, float structureStrength,
                           float skinStructureStrength, int model, boolean useAutoMask, boolean depthInverted) {}

    private final DlssNative owner;
    private final MethodHandle instanceExtensions;
    private final MethodHandle deviceExtensions;
    private final MethodHandle caps;
    private final MethodHandle create;
    private final MethodHandle evaluate;
    private final MethodHandle release;

    private DlssNrNative(DlssNative owner, Linker linker, SymbolLookup lookup) {
        verifyLayouts();
        this.owner = owner;
        instanceExtensions = DlssNative.bind(linker, lookup, "vkb_dlss_nr_get_required_instance_extensions",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        deviceExtensions = DlssNative.bind(linker, lookup, "vkb_dlss_nr_get_required_device_extensions",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        caps = DlssNative.bind(linker, lookup, "vkb_dlss_nr_query_caps", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        create = DlssNative.bind(linker, lookup, "vkb_dlss_nr_create_feature",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
        evaluate = DlssNative.bind(linker, lookup, "vkb_dlss_nr_evaluate",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS));
        release = DlssNative.bind(linker, lookup, "vkb_dlss_nr_release_feature", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    }

    static void verifyLayouts() {
        if (CAPS.byteSize() != 24 || CREATE.byteSize() != 16 || EVAL.byteSize() != 336
            || offset(EVAL, "color") != 8 || offset(EVAL, "jitter_x") != 288 || offset(EVAL, "model") != 320) {
            throw new IllegalStateException("vkb_dlss Neural Rendering ABI layout mismatch");
        }
    }

    static DlssNrNative bind(DlssNative owner, SymbolLookup lookup) {
        var version = lookup.find(VERSION_SYMBOL);
        if (version.isEmpty()) return null;
        Linker linker = Linker.nativeLinker();
        MethodHandle query = linker.downcallHandle(version.get(), FunctionDescriptor.of(JAVA_INT));
        return invokeInt(query) == CONTRACT_VERSION ? new DlssNrNative(owner, linker, lookup) : null;
    }

    DlssNative.NameList requiredInstanceExtensions(Path featureDir, Path logDir) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(JAVA_INT);
            MemorySegment names = arena.allocate(ADDRESS);
            int code = invokeInt(instanceExtensions, DlssNative.wide(arena, featureDir), DlssNative.wide(arena, logDir), count, names);
            return names(code, count, names);
        }
    }

    DlssNative.NameList requiredDeviceExtensions(long instance, long physicalDevice, Path featureDir, Path logDir) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment count = arena.allocate(JAVA_INT);
            MemorySegment names = arena.allocate(ADDRESS);
            int code = invokeInt(deviceExtensions, instance, physicalDevice, DlssNative.wide(arena, featureDir),
                DlssNative.wide(arena, logDir), count, names);
            return names(code, count, names);
        }
    }

    private DlssNative.NameList names(int code, MemorySegment count, MemorySegment names) {
        DlssNative.Status status = owner.lastStatus(code);
        return new DlssNative.NameList(status, status.ok() ? DlssNative.readStrings(count.get(JAVA_INT, 0), names) : List.of());
    }

    public Caps queryCaps() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = sized(arena, CAPS);
            DlssNative.Status status = owner.lastStatus(invokeInt(caps, result));
            return new Caps(status, status.ok() && get(result, "available") != 0, get(result, "needs_updated_driver") != 0,
                get(result, "min_driver_major"), get(result, "min_driver_minor"), get(result, "feature_init_result"));
        }
    }

    /** A nonzero owner returned with an error still requires release after the recording is retired. */
    public DlssNative.Status createFeature(long command, int width, int height, long[] outFeature) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = sized(arena, CREATE);
            info.set(JAVA_INT, offset(CREATE, "width"), width);
            info.set(JAVA_INT, offset(CREATE, "height"), height);
            MemorySegment out = arena.allocate(JAVA_LONG);
            int code = invokeInt(create, command, info, out);
            outFeature[0] = out.get(JAVA_LONG, 0);
            return owner.lastStatus(code);
        }
    }

    public DlssNative.Status evaluate(long command, long feature, EvalInfo e) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = sized(arena, EVAL);
            info.set(JAVA_INT, offset(EVAL, "reset"), e.reset() ? 1 : 0);
            DlssNative.writeImage(info, offset(EVAL, "color"), e.color());
            DlssNative.writeImage(info, offset(EVAL, "output"), e.output());
            DlssNative.writeImage(info, offset(EVAL, "depth"), e.depth());
            DlssNative.writeImage(info, offset(EVAL, "motion_vectors"), e.motionVectors());
            DlssNative.writeImage(info, offset(EVAL, "control_mask"), e.controlMask());
            info.set(JAVA_FLOAT, offset(EVAL, "jitter_x"), e.jitterX());
            info.set(JAVA_FLOAT, offset(EVAL, "jitter_y"), e.jitterY());
            info.set(JAVA_FLOAT, offset(EVAL, "mv_scale_x"), e.mvScaleX());
            info.set(JAVA_FLOAT, offset(EVAL, "mv_scale_y"), e.mvScaleY());
            info.set(JAVA_FLOAT, offset(EVAL, "intensity"), e.intensity());
            info.set(JAVA_FLOAT, offset(EVAL, "tone_strength"), e.toneStrength());
            info.set(JAVA_FLOAT, offset(EVAL, "structure_strength"), e.structureStrength());
            info.set(JAVA_FLOAT, offset(EVAL, "skin_structure_strength"), e.skinStructureStrength());
            info.set(JAVA_INT, offset(EVAL, "model"), e.model());
            info.set(JAVA_INT, offset(EVAL, "use_auto_mask"), e.useAutoMask() ? 1 : 0);
            info.set(JAVA_INT, offset(EVAL, "depth_inverted"), e.depthInverted() ? 1 : 0);
            return owner.lastStatus(invokeInt(evaluate, command, feature, info));
        }
    }

    public DlssNative.Status releaseFeature(long feature) {
        return owner.lastStatus(invokeInt(release, feature));
    }

    private static MemorySegment sized(Arena arena, StructLayout layout) {
        MemorySegment segment = arena.allocate(layout);
        segment.set(JAVA_INT, 0, Math.toIntExact(layout.byteSize()));
        return segment;
    }

    private static int get(MemorySegment caps, String name) {
        return caps.get(JAVA_INT, offset(CAPS, name));
    }
}
