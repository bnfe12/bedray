package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Versioned disk envelope; driver-produced opaque bytes reach Vulkan only after length, digest and device checks. */
final class PipelineCacheData {
    static final int ENVELOPE_BYTES = 48;
    private static final long MAGIC = 0x3143505941524442L; // "BDRAYPC1", little endian

    private PipelineCacheData() {}

    static byte[] encode(ByteBuffer payload) {
        if (payload.remaining() < PipelineCacheIdentity.HEADER_BYTES || payload.remaining() > PipelineCacheIdentity.MAX_BYTES)
            throw new IllegalArgumentException("pipeline cache payload size");
        ByteBuffer encoded = ByteBuffer.allocate(ENVELOPE_BYTES + payload.remaining()).order(ByteOrder.LITTLE_ENDIAN);
        encoded.putLong(MAGIC).putInt(1).putInt(payload.remaining()).put(digest(payload)).put(payload.duplicate());
        return encoded.array();
    }

    static ByteBuffer decode(byte[] bytes, PipelineCacheIdentity identity) {
        if (bytes.length < ENVELOPE_BYTES + PipelineCacheIdentity.HEADER_BYTES
            || (long) bytes.length > (long) ENVELOPE_BYTES + PipelineCacheIdentity.MAX_BYTES)
            throw new IllegalArgumentException("envelope_size");
        ByteBuffer encoded = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (encoded.getLong() != MAGIC || encoded.getInt() != 1) throw new IllegalArgumentException("envelope_version");
        int length = encoded.getInt();
        if (length != bytes.length - ENVELOPE_BYTES) throw new IllegalArgumentException("payload_size");
        byte[] expected = new byte[32];
        encoded.get(expected);
        ByteBuffer payload = encoded.slice();
        if (!MessageDigest.isEqual(expected, digest(payload))) throw new IllegalArgumentException("payload_digest");
        String rejection = identity.rejection(payload);
        if (rejection != null) throw new IllegalArgumentException("header_" + rejection);
        return payload;
    }

    private static byte[] digest(ByteBuffer payload) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(payload.duplicate());
            return hash.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }
}
