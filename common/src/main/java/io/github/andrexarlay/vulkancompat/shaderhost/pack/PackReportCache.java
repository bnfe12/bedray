package io.github.andrexarlay.vulkancompat.shaderhost.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory report cache keyed by content SHA-256. A pack whose on-disk fingerprint (size + mtime, or for folders count + sizes
 * + newest mtime) is unchanged is not re-read at all; a changed fingerprint re-hashes the content, and if the hash is known
 * (same content under another name or rewritten without change) the stored report is reused. Thread-safe by synchronization:
 * probes happen at startup and on host switches, not per frame.
 */
public final class PackReportCache {
    private final ScanLimits limits;
    private final Map<String, String> shaByFingerprint = new HashMap<>();
    private final Map<String, PackReport> bySha = new HashMap<>();
    private int scans;

    public PackReportCache(ScanLimits limits) {
        this.limits = limits;
    }

    public synchronized PackReport get(Path pack) throws IOException {
        try (PackSource src = PackSource.open(pack, limits)) {
            String key = pack.toAbsolutePath().normalize() + "|" + src.fingerprint();
            String sha = shaByFingerprint.get(key);
            if (sha != null && bySha.containsKey(sha)) {
                return bySha.get(sha).withSource(src.name(), src.kind());
            }
            scans++;
            PackReport fresh = ShaderpackScanner.scan(src);
            shaByFingerprint.put(key, fresh.sha256());
            PackReport known = bySha.putIfAbsent(fresh.sha256(), fresh);
            return known == null ? fresh : known.withSource(src.name(), src.kind());
        }
    }

    /** Full content scans done so far (tests: a fingerprint hit must not scan again). */
    public synchronized int scans() {
        return scans;
    }

    public synchronized int distinctContents() {
        return bySha.size();
    }
}
