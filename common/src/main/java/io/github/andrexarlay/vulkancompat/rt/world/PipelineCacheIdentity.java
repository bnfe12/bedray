package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.Locale;

/** Vulkan's version-one cache header is exactly 32 bytes, little endian on every host. */
final class PipelineCacheIdentity {
    static final int HEADER_BYTES = 32;
    static final int MAX_BYTES = 256 * 1024 * 1024;
    private final int vendor, device, driver;
    private final byte[] uuid;

    PipelineCacheIdentity(int vendor, int device, int driver, byte[] uuid) {
        if (uuid == null || uuid.length != 16) throw new IllegalArgumentException("pipeline cache UUID needs 16 bytes");
        this.vendor = vendor; this.device = device; this.driver = driver; this.uuid = uuid.clone();
    }

    String filename() {
        return String.format(Locale.ROOT, "v%08x-d%08x-r%08x-%s.v1.bin", vendor, device, driver, HexFormat.of().formatHex(uuid));
    }

    String rejection(ByteBuffer data) {
        if (data == null || data.remaining() < HEADER_BYTES || data.remaining() > MAX_BYTES) return "size";
        ByteBuffer header = data.slice().order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != HEADER_BYTES || header.getInt(4) != 1) return "header_version";
        if (header.getInt(8) != vendor || header.getInt(12) != device) return "device";
        for (int i = 0; i < uuid.length; ++i) if (header.get(16 + i) != uuid[i]) return "uuid";
        return null;
    }
}
