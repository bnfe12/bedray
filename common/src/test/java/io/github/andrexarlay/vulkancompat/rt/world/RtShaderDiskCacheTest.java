package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RtShaderDiskCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void keyChangesWhenExpandedSourceChanges() {
        assertNotEquals(RtShaderDiskCache.key("#version 460\nvoid main() {}"),
            RtShaderDiskCache.key("#version 460\n#define FEATURE 1\nvoid main() {}"));
    }

    @Test
    void storesAndReadsSpirvAtomically() throws Exception {
        RtShaderDiskCache cache = new RtShaderDiskCache(tempDir);
        String key = RtShaderDiskCache.key("shader source");
        ByteBuffer source = validModule();

        cache.write(key, source);

        ByteBuffer cached = cache.read(key);
        assertNotNull(cached);
        assertArrayEquals(bytes(source), bytes(cached));
    }

    @Test
    void ignoresCorruptCacheEntries() throws Exception {
        RtShaderDiskCache cache = new RtShaderDiskCache(tempDir);
        String key = RtShaderDiskCache.key("shader source");
        Path file = tempDir.resolve(key.substring(0, 2)).resolve(key + ".spv");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[20]);

        assertNull(cache.read(key));
    }

    private static ByteBuffer validModule() {
        ByteBuffer data = BufferUtils.createByteBuffer(20).order(ByteOrder.nativeOrder());
        data.putInt(0x07230203).putInt(0x00010400).putInt(0).putInt(1).putInt(0).flip();
        return data;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer copy = buffer.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }
}
