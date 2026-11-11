package io.github.andrexarlay.vulkancompat.rt.material.bedrock;

import java.io.IOException;

/**
 * Truecolour TGA decoder for Bedrock texture sets (Java's ImageIO has no TGA reader). Supports image types 2 (raw) and
 * 10 (RLE) at 24 or 32 bits per pixel, either vertical origin; everything else is refused with the reason, never decoded
 * into a guess.
 */
public final class TgaReader {
    private TgaReader() {
    }

    /** Decoded image: width, height and ARGB pixels in row-major order, top row first. */
    public record Image(int width, int height, int[] argb) {
    }

    public static Image read(byte[] data) throws IOException {
        if (data.length < 18) {
            throw new IOException("TGA shorter than its header (" + data.length + " bytes)");
        }
        int idLength = data[0] & 255;
        int colorMapType = data[1] & 255;
        int imageType = data[2] & 255;
        int width = (data[12] & 255) | (data[13] & 255) << 8;
        int height = (data[14] & 255) | (data[15] & 255) << 8;
        int bpp = data[16] & 255;
        boolean topLeft = (data[17] & 0x20) != 0;
        if (colorMapType != 0 || (imageType != 2 && imageType != 10)) {
            throw new IOException("unsupported TGA image type " + imageType + " (colour map " + colorMapType + ")");
        }
        if (bpp != 24 && bpp != 32) {
            throw new IOException("unsupported TGA depth " + bpp + " bpp");
        }
        if (width <= 0 || height <= 0 || (long) width * height > 64L * 1024 * 1024) {
            throw new IOException("implausible TGA size " + width + "x" + height);
        }
        int bytes = bpp / 8;
        int pos = 18 + idLength;
        int count = width * height;
        int[] linear = new int[count];
        int i = 0;
        try {
            if (imageType == 2) {
                for (; i < count; i++, pos += bytes) {
                    linear[i] = pixel(data, pos, bytes);
                }
            } else {
                while (i < count) {
                    int header = data[pos++] & 255;
                    int run = (header & 0x7F) + 1;
                    if ((header & 0x80) != 0) {
                        int p = pixel(data, pos, bytes);
                        pos += bytes;
                        for (int k = 0; k < run && i < count; k++) linear[i++] = p;
                    } else {
                        for (int k = 0; k < run && i < count; k++, pos += bytes) linear[i++] = pixel(data, pos, bytes);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("TGA pixel data truncated at pixel " + i + " of " + count, e);
        }
        if (topLeft) {
            return new Image(width, height, linear);
        }
        int[] flipped = new int[count];
        for (int y = 0; y < height; y++) {
            System.arraycopy(linear, (height - 1 - y) * width, flipped, y * width, width);
        }
        return new Image(width, height, flipped);
    }

    private static int pixel(byte[] d, int pos, int bytes) {
        int b = d[pos] & 255;
        int g = d[pos + 1] & 255;
        int r = d[pos + 2] & 255;
        int a = bytes == 4 ? d[pos + 3] & 255 : 255;
        return a << 24 | r << 16 | g << 8 | b;
    }
}
