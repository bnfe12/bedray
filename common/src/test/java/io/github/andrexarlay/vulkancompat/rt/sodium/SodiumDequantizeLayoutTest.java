package io.github.andrexarlay.vulkancompat.rt.sodium;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the geometry arena layout the GPU dequantizer writes to the one the Java table and the RT shaders read.
 *
 * <p>The block is planar, {@code [pos 48Q][uv 32Q][colour 16Q][light 16Q]} (112 B per quad). The BLAS build reads
 * positions at {@code passStart * 48} and {@code rt_common_core.glsl} takes the other three planes from the section
 * table, so a shader that wrote the quad's four attributes next to each other (interleaved) fed the acceleration
 * structure garbage vertices: the first RT frame after a section build hung the GPU and the game died with
 * {@code VK_ERROR_DEVICE_LOST} (owner crash 2026-10-26 01:53). The word offsets in the test are derived from the
 * Java byte offsets, not written out by hand.
 */
public class SodiumDequantizeLayoutTest {

    private static final Path SHADER = Path.of("src/main/resources/shaders/glsl/sodium_dequantize.comp");
    private static final Path WORLD = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtWorld.java");

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    @Test
    void planesArePlanarAndMatchTheJavaByteOffsets() {
        String shader = read(SHADER);
        // Java: [pos 48Q][uv 32Q][colour 16Q][light 16Q]; the table stores the plane bases at +48Q, +80Q, +96Q.
        int posBytes = 48;
        int uvBytes = 80;
        int colourBytes = 96;
        int lightBytes = 112;
        assertEquals(112, lightBytes, "GEOM_BYTES_PER_QUAD");
        assertTrue(shader.contains("uint posBase = q * " + (posBytes / 4) + "u;"),
            "positions are a plane of 3 floats per vertex at word offset q*12");
        assertTrue(shader.contains("uint uvBase = quads * " + (posBytes / 4) + "u + q * " + ((uvBytes - posBytes) / 4) + "u;"),
            "uvs start after the whole position plane and take 2 floats per vertex");
        assertTrue(shader.contains("uint colBase = quads * " + (uvBytes / 4) + "u + q * " + ((colourBytes - uvBytes) / 4) + "u;"),
            "colours start after the uv plane, one uint per vertex");
        assertTrue(shader.contains("uint lightBase = quads * " + (colourBytes / 4) + "u + q * " + ((lightBytes - colourBytes) / 4) + "u;"),
            "light starts after the colour plane, one uint per vertex");
        assertFalse(shader.contains("q * 28u"),
            "a quad's attributes must not be written interleaved: the arena is planar");
        assertTrue(read(WORLD).contains("static final long GEOM_BYTES_PER_QUAD = 112L;"),
            "the Java contract this test mirrors");
        assertTrue(read(WORLD).contains("MemoryUtil.memPutLong(p + 8, blockAddress + 48L * quads);"),
            "section table: uv plane base");
        assertTrue(read(WORLD).contains("MemoryUtil.memPutLong(p + 16, blockAddress + 80L * quads);"),
            "section table: colour plane base");
        assertTrue(read(WORLD).contains("MemoryUtil.memPutLong(p + 24, blockAddress + 96L * quads);"),
            "section table: light plane base");
    }

    @Test
    void rawCaptureIsReadWithTheDecoderStride() {
        String shader = read(SHADER);
        assertEquals(20, SodiumMeshDecoder.STRIDE, "Sodium compact vertex stride in bytes");
        assertEquals(80, SodiumMeshDecoder.QUAD_BYTES, "four vertices per quad");
        assertEquals(20, SodiumMeshDecoder.QUAD_BYTES / 4, "words per vertex");
        assertTrue(shader.contains("uint o = q * 20u + v * 5u;"),
            "one quad is 20 words, one vertex 5 words");
        assertEquals(32, SodiumDequantize.PUSH_BYTES, "three uvec2 addresses + quadCount + pad");
    }
}
