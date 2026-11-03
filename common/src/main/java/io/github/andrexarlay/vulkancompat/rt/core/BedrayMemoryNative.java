package io.github.andrexarlay.vulkancompat.rt.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.slf4j.LoggerFactory;
import static java.lang.foreign.ValueLayout.*;

/** Coarse calls to the core-owned allocation service. The runtime owns the borrowed core/library lifetime. */
public final class BedrayMemoryNative {
    public record Snapshot(long limit, long reserved, long used, long peakReserved, long allocations, long blocks,
                           long heapBudget, long heapUsage, long flags) {
        public boolean budgetReported() { return (flags & 1) != 0; }
        public boolean deviceLost() { return (flags & 2) != 0; }
        public String logValues() {
            return "limit_bytes=" + limit + " reserved_bytes=" + reserved + " used_bytes=" + used
                + " peak_reserved_bytes=" + peakReserved + " allocations=" + allocations + " blocks=" + blocks
                + " heap_budget_bytes=" + (budgetReported() ? heapBudget : "unavailable")
                + " heap_usage_bytes=" + (budgetReported() ? heapUsage : "unavailable") + " device_lost=" + deviceLost();
        }
    }
    private final long core;
    private final MethodHandle attach, stats;

    public BedrayMemoryNative(BedrayCoreNative library, long core, NativeMemoryPolicy policy, boolean heapBudgetEnabled) {
        this.core = core;
        var version = library.resourceFunction("bedray_core_memory_version", FunctionDescriptor.of(JAVA_INT));
        var configure = library.resourceFunction("bedray_core_memory_configure",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        attach = library.resourceFunction("bedray_core_memory_attach", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        stats = library.resourceFunction("bedray_core_memory_stats", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            if ((int) version.invokeExact() != 1) throw new IllegalStateException("native memory service version mismatch");
            var error = arena.allocate(1028, 4);
            check("configure", (int) configure.invokeExact(MemorySegment.ofAddress(core), policy.limitBytes(), policy.blockBytes(),
                heapBudgetEnabled ? 1 : 0, error), error);
            LoggerFactory.getLogger("VKB-MEMORY").info("[VKB-MEMORY] event=configured block_bytes={} {}",
                policy.blockBytes(), snapshot().logValues());
        } catch (Throwable failure) { throw failure("configure", failure); }
    }

    public void attach(BedrayGpuBuffersNative owner) { owner.attachMemory(this); }
    public void attach(BedrayGpuImagesNative owner) { owner.attachMemory(this); }
    public void attachScene(long scene) { attach(2, MemorySegment.ofAddress(scene)); }

    void attach(int kind, MemorySegment owner) {
        try (Arena arena = Arena.ofConfined()) {
            var error = arena.allocate(1028, 4);
            check("attach_kind_" + kind, (int) attach.invokeExact(MemorySegment.ofAddress(core), kind, owner, error), error);
        } catch (Throwable failure) { throw failure("attach_kind_" + kind, failure); }
    }

    public Snapshot snapshot() {
        try (Arena arena = Arena.ofConfined()) {
            var out = arena.allocate(72, 8);
            var error = arena.allocate(1028, 4);
            check("stats", (int) stats.invokeExact(MemorySegment.ofAddress(core), out, 72, error), error);
            return decode(out);
        } catch (Throwable failure) { throw failure("stats", failure); }
    }

    static Snapshot decode(MemorySegment data) {
        return new Snapshot(data.get(JAVA_LONG, 0), data.get(JAVA_LONG, 8), data.get(JAVA_LONG, 16),
            data.get(JAVA_LONG, 24), data.get(JAVA_LONG, 32), data.get(JAVA_LONG, 40), data.get(JAVA_LONG, 48),
            data.get(JAVA_LONG, 56), data.get(JAVA_LONG, 64));
    }

    IllegalStateException allocationFailure(String operation, long requestedBytes, int status, int vkResult) {
        String message = operation + " request_bytes=" + (requestedBytes < 0 ? "unavailable" : requestedBytes)
            + " status=" + status + " VkResult=" + vkResult;
        try { message += " " + snapshot().logValues(); }
        catch (RuntimeException unavailable) { message += " memory_stats_error=" + unavailable.getMessage(); }
        LoggerFactory.getLogger("VKB-MEMORY").error("[VKB-MEMORY] event=allocation_failed {}", message);
        return new IllegalStateException(message);
    }

    private static void check(String operation, int status, MemorySegment error) {
        if (status != 0) throw new BedrayCoreException("memory_" + operation, status, error.asSlice(4, 1024).getString(0), null);
    }
    private static RuntimeException failure(String operation, Throwable failure) {
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("native memory " + operation + " failed", failure);
    }
}
