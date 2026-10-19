package io.github.andrexarlay.vulkanbridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the disk and reports, per catalog slot, whether the file is there and structurally valid (regular non-link x64 DLL of a sane
 * size). This is the file level truth; whether a loader accepts the file (FSR hash gate, GPU vendor, wrapper present) is reported
 * separately by the loaders themselves. Pure Java.
 */
public final class NativeScanner {
    private static final Map<String, String> HASHES = new ConcurrentHashMap<>();

    private NativeScanner() {}

    public enum State { FOUND, MISSING, INVALID }

    /**
     * @param detail          empty for MISSING; the folder it was found in or the rejection reason otherwise
     * @param legacy          found only in a read-only folder of an earlier test build
     * @param matchesBundled  for a bundled slot that is FOUND: the file is byte-identical to the one in the jar; null otherwise
     */
    public record SlotState(NativeCatalog.Slot slot, State state, String detail, long size, String version, boolean legacy,
                            Boolean matchesBundled) {}

    public record GroupState(NativeCatalog.Group group, List<SlotState> slots) {
        public int requiredCount() {
            return (int) slots.stream().filter(s -> s.slot().required()).count();
        }

        public int requiredFound() {
            return (int) slots.stream().filter(s -> s.slot().required() && s.state() == State.FOUND).count();
        }

        public boolean anyInvalid() {
            return slots.stream().anyMatch(s -> s.state() == State.INVALID);
        }

        /** Every required file is present and valid (wrappers and optional files do not count). */
        public boolean complete() {
            return requiredCount() > 0 && requiredFound() == requiredCount() && !anyInvalid();
        }
    }

    public static List<GroupState> scan(Path nativesDir, List<Path> legacyNativesDirs) {
        List<GroupState> out = new ArrayList<>();
        for (NativeCatalog.Group g : NativeCatalog.GROUPS) {
            List<SlotState> states = new ArrayList<>();
            for (NativeCatalog.Slot s : g.slots()) {
                states.add(scanSlot(s, nativesDir, legacyNativesDirs));
            }
            out.add(new GroupState(g, states));
        }
        return out;
    }

    static SlotState scanSlot(NativeCatalog.Slot s, Path nativesDir, List<Path> legacyDirs) {
        List<Path> dirs = new ArrayList<>();
        dirs.add(s.folder().resolve(nativesDir));
        for (Path l : legacyDirs) {
            dirs.add(s.folder().resolve(l));
        }
        for (int i = 0; i < dirs.size(); i++) {
            Path found;
            try {
                found = NativeFiles.find(dirs.get(i), s.file());
            } catch (IOException e) {
                return new SlotState(s, State.INVALID, "cannot list " + dirs.get(i) + ": " + e, 0, null, i > 0, null);
            }
            if (found == null) {
                continue;
            }
            boolean legacy = i > 0;
            String problem = NativeFiles.problem(found);
            if (problem != null) {
                return new SlotState(s, State.INVALID, problem, sizeOrZero(found), null, legacy, null);
            }
            Boolean same = null;
            if (s.bundled()) {
                try {
                    same = s.bundledSha256().equals(cachedSha(found));
                } catch (IOException e) {
                    return new SlotState(s, State.INVALID, "cannot read: " + e, sizeOrZero(found), null, legacy, null);
                }
            }
            return new SlotState(s, State.FOUND, legacy ? "legacy folder " + dirs.get(i) : dirs.get(i).toString(), sizeOrZero(found),
                NativeFiles.version(found), legacy, same);
        }
        return new SlotState(s, State.MISSING, "", 0, null, false, null);
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String cachedSha(Path p) throws IOException {
        String key = p + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
        String h = HASHES.get(key);
        if (h == null) {
            h = NativeFiles.sha256(p);
            HASHES.put(key, h);
        }
        return h;
    }
}
