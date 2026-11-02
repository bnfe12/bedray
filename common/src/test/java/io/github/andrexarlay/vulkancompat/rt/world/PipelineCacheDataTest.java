package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class PipelineCacheDataTest {
    private static final byte[] UUID = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    private static PipelineCacheIdentity identity() { return new PipelineCacheIdentity(0x10de, 0x1234, 42, UUID); }
    private static ByteBuffer payload() {
        var data = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        return data.putInt(32).putInt(1).putInt(0x10de).putInt(0x1234).put(UUID).putLong(0x123456789abcdef0L).flip();
    }

    @Test void authenticPayloadRoundTripsAndCallerBufferStateIsPreserved() {
        var data = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
        data.position(4); data.put(payload()); data.position(4); data.limit(44);
        byte[] encoded = PipelineCacheData.encode(data);
        assertEquals(4, data.position()); assertEquals(44, data.limit()); assertEquals(ByteOrder.BIG_ENDIAN, data.order());
        assertEquals(payload(), PipelineCacheData.decode(encoded, identity()));
    }

    @Test void lengthVersionAndOpaquePayloadCorruptionAreRejectedBeforeDriverAccess() {
        byte[] valid = PipelineCacheData.encode(payload());
        for (int offset : new int[]{0, 8, 12, 16, valid.length - 1}) {
            byte[] broken = valid.clone(); broken[offset] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> PipelineCacheData.decode(broken, identity()), "offset " + offset);
        }
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheData.decode(new byte[31], identity()));
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheData.decode(payload().array(), identity()), "unprotected old raw file");
    }

    @Test void matchingDigestCannotMakeAForeignDeviceOrInvalidVulkanHeaderCompatible() {
        for (int offset : new int[]{0, 4, 8, 12, 16, 31}) {
            var wrong = payload(); wrong.put(offset, (byte) (wrong.get(offset) ^ 1));
            var encoded = PipelineCacheData.encode(wrong);
            assertThrows(IllegalArgumentException.class, () -> PipelineCacheData.decode(encoded, identity()), "header offset " + offset);
        }
    }

    @Test void filenameIncludesVendorDeviceDriverAndUuidAndIdentityOwnsItsUuid() {
        byte[] uuid = UUID.clone(); var identity = new PipelineCacheIdentity(0x10de, 0x1234, 42, uuid);
        uuid[0] = 99;
        assertNull(identity.rejection(payload()));
        assertEquals("v000010de-d00001234-r0000002a-000102030405060708090a0b0c0d0e0f.v1.bin", identity.filename());
        assertNotEquals(identity.filename(), new PipelineCacheIdentity(0x10de, 0x1234, 43, UUID).filename());
        assertEquals("size", identity.rejection(ByteBuffer.allocate(31)));
    }
}
