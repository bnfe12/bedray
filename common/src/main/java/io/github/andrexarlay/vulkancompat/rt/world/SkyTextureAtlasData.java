package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.IntBuffer;

/** Immutable CPU encoding of the shader's twelve-slot UInt/ABGR atlas. */
final class SkyTextureAtlasData {
    static final int SLOTS = 12;
    static final int HEADER_WORDS = SLOTS * 4;
    static final int CLOUDS = 9;
    record Texture(int width, int height, int[] pixels) {}

    private final int[] words;
    private final int loadedMask;
    private final int cloudWidth;
    private final int cloudHeight;

    private SkyTextureAtlasData(int[] words, int loadedMask, int cloudWidth, int cloudHeight) {
        this.words = words;
        this.loadedMask = loadedMask;
        this.cloudWidth = cloudWidth;
        this.cloudHeight = cloudHeight;
    }

    static SkyTextureAtlasData pack(Texture[] textures) {
        if (textures == null || textures.length != SLOTS)
            throw new IllegalArgumentException("sky atlas needs exactly " + SLOTS + " slots");
        long total = HEADER_WORDS;
        for (int slot = 0; slot < SLOTS; slot++) {
            Texture texture = textures[slot];
            if (texture == null) continue;
            long pixels = (long) texture.width * texture.height;
            if (texture.width <= 0 || texture.height <= 0 || texture.pixels == null || pixels != texture.pixels.length)
                throw new IllegalArgumentException("sky atlas slot=" + slot + " extent=" + texture.width + "x"
                    + texture.height + " does not match its pixel array");
            total += pixels;
            if (total > Integer.MAX_VALUE)
                throw new IllegalArgumentException("sky atlas exceeds the CPU/shader word range at slot=" + slot);
        }
        int[] words = new int[(int) total];
        int offset = HEADER_WORDS, mask = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            Texture texture = textures[slot];
            if (texture == null) continue;
            int header = slot * 4;
            words[header] = offset;
            words[header + 1] = texture.width;
            words[header + 2] = texture.height;
            words[header + 3] = 1;
            System.arraycopy(texture.pixels, 0, words, offset, texture.pixels.length);
            offset += texture.pixels.length;
            mask |= 1 << slot;
        }
        Texture clouds = textures[CLOUDS];
        return new SkyTextureAtlasData(words, mask, clouds != null ? clouds.width : 0, clouds != null ? clouds.height : 0);
    }

    int wordCount() { return words.length; }
    long byteCount() { return (long) words.length * Integer.BYTES; }
    int loadedMask() { return loadedMask; }
    int cloudWidth() { return cloudWidth; }
    int cloudHeight() { return cloudHeight; }

    void writeTo(IntBuffer destination) {
        if (destination == null || destination.remaining() < words.length)
            throw new IllegalArgumentException("sky atlas upload needs " + words.length + " uint words");
        destination.put(words);
    }

    static int[] moonPhaseTile(int[] pixels, int width, int height, int phase) {
        int tileWidth = width / 4, tileHeight = height / 2;
        if (tileWidth <= 0 || tileHeight <= 0 || pixels == null || (long) width * height > pixels.length)
            return new int[0];
        int[] result = new int[tileWidth * tileHeight];
        int x = (phase & 3) * tileWidth;
        int y = ((phase & 7) >>> 2) * tileHeight;
        for (int row = 0; row < tileHeight; row++)
            System.arraycopy(pixels, (y + row) * width + x, result, row * tileWidth, tileWidth);
        return result;
    }
}
