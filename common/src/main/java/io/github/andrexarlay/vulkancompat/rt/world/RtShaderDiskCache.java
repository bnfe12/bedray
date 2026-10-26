package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Local, hardware-independent cache of shaderc's SPIR-V output. */
final class RtShaderDiskCache {
    private static final int MAX_SHADER_BYTES = 32 * 1024 * 1024;
    private static final String KEY_PROFILE = "bedray-spirv-cache-v2\nvulkan=1.2\noptimization=performance\n";
    /** shaderc_spirv_version_1_4 (0x00010400), the default compile target. */
    private static final int DEFAULT_SPIRV_TARGET = 0x00010400;

    private final Path root;

    RtShaderDiskCache(Path root) {
        this.root = root;
    }

    static String key(String expandedSource) {
        return key(expandedSource, DEFAULT_SPIRV_TARGET);
    }

    /** The SPIR-V target is part of the key: the opacity micromap probe can move compilation to 1.5. */
    static String key(String expandedSource, int spirvTarget) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((KEY_PROFILE + "spirv=0x" + Integer.toHexString(spirvTarget) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(expandedSource.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java runtime", impossible);
        }
    }

    ByteBuffer read(String key) {
        Path file = file(key);
        try {
            long size = Files.size(file);
            if (size < Integer.BYTES * 5L || size > MAX_SHADER_BYTES || (size & 3L) != 0L) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer result = BufferUtils.createByteBuffer(bytes.length).order(ByteOrder.nativeOrder());
            result.put(bytes).flip();
            return validSpirv(result) ? result : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    void write(String key, ByteBuffer spirv) throws IOException {
        ByteBuffer data = spirv.duplicate();
        if (!validSpirv(data) || data.remaining() > MAX_SHADER_BYTES) {
            throw new IOException("refusing to cache invalid or oversized SPIR-V data");
        }

        Path target = file(key);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), key, ".tmp");
        try {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("cache key must be a SHA-256 hex string");
        }
        return root.resolve(key.substring(0, 2)).resolve(key + ".spv");
    }

    private static boolean validSpirv(ByteBuffer data) {
        return data != null && data.remaining() >= Integer.BYTES * 5
            && (data.remaining() & 3) == 0
            && data.order(ByteOrder.nativeOrder()).getInt(data.position()) == 0x07230203;
    }
}
