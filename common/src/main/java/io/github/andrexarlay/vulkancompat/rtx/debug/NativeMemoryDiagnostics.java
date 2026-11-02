package io.github.andrexarlay.vulkancompat.rtx.debug;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayMemoryNative;
import org.slf4j.LoggerFactory;
import java.util.IdentityHashMap;
import java.util.Map;

/** Render-thread polling outside hot dispatches. F3 uses cached data; missing budget is never shown as free VRAM. */
public final class NativeMemoryDiagnostics {
    private static final Map<BedrayMemoryNative, State> SOURCES = new IdentityHashMap<>();
    private static long lastLog;
    private static final class State { BedrayMemoryNative.Snapshot snapshot; boolean failed; }
    private NativeMemoryDiagnostics() {}
    static {
        DebugInfo.register(DebugInfo.provider("native_memory", lines -> {
            for (var state : SOURCES.values()) lines.add(state.snapshot == null ? "Native memory: unavailable"
                : "Native memory: " + state.snapshot.logValues());
        }));
    }
    public static void register(BedrayMemoryNative memory) { SOURCES.put(memory, new State()); }
    public static void unregister(BedrayMemoryNative memory) { SOURCES.remove(memory); }
    static void sample() {
        boolean log = System.nanoTime() - lastLog >= 30_000_000_000L;
        if (log) lastLog = System.nanoTime();
        for (var entry : SOURCES.entrySet()) {
            var state = entry.getValue();
            try {
                state.snapshot = entry.getKey().snapshot();
                if (log || state.failed)
                    LoggerFactory.getLogger("VKB-MEMORY").info("[VKB-MEMORY] event=sample {}", state.snapshot.logValues());
                state.failed = false;
            } catch (RuntimeException failure) {
                state.snapshot = null;
                if (!state.failed) LoggerFactory.getLogger("VKB-MEMORY").error("[VKB-MEMORY] event=query_failed", failure);
                state.failed = true;
            }
        }
    }
}
