package io.github.andrexarlay.vulkancompat.shaderhost.mosaic.studio;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The light of the material preview: a sun (colour, strength), a sky (zenith, horizon, ground) or an HDRI (equirect,
 * pre-blurred for rough reflections), and a fill light of your own. Presets cover the usual tests: noon, sunset, night,
 * overcast, a torch-lit cave, a neutral studio.
 */
final class PreviewLight {
    final String name;
    final double[] sun, sky, horizon, ground, fill;
    final double sunStrength, fillYaw, fillPitch;
    /** HDRI levels (sharp to blurred), each {w, h, rgb...} linear; null without one. */
    final float[][] hdri;
    final int[][] hdriSize;

    PreviewLight(String name, double[] sun, double sunStrength, double[] sky, double[] horizon, double[] ground, double[] fill,
                 double fillYaw, double fillPitch, float[][] hdri, int[][] hdriSize) {
        this.name = name;
        this.sun = sun;
        this.sunStrength = sunStrength;
        this.sky = sky;
        this.horizon = horizon;
        this.ground = ground;
        this.fill = fill;
        this.fillYaw = fillYaw;
        this.fillPitch = fillPitch;
        this.hdri = hdri;
        this.hdriSize = hdriSize;
    }

    static double[] rgb(double r, double g, double b) { return new double[] {r, g, b}; }

    static final List<PreviewLight> PRESETS = List.of(
        new PreviewLight("Noon", rgb(1.0, 0.97, 0.92), 1.0, rgb(0.42, 0.58, 0.92), rgb(0.78, 0.84, 0.92), rgb(0.32, 0.29, 0.25), rgb(0, 0, 0), 2.4, 0.4, null, null),
        new PreviewLight("Sunset", rgb(1.0, 0.62, 0.34), 0.85, rgb(0.30, 0.36, 0.62), rgb(0.95, 0.58, 0.40), rgb(0.22, 0.17, 0.14), rgb(0.10, 0.12, 0.25), 2.4, 0.5, null, null),
        new PreviewLight("Night (moon)", rgb(0.55, 0.65, 0.95), 0.25, rgb(0.03, 0.05, 0.12), rgb(0.06, 0.08, 0.16), rgb(0.03, 0.03, 0.04), rgb(0, 0, 0), 2.4, 0.4, null, null),
        new PreviewLight("Overcast", rgb(0.85, 0.87, 0.9), 0.25, rgb(0.72, 0.75, 0.8), rgb(0.78, 0.8, 0.84), rgb(0.35, 0.34, 0.33), rgb(0, 0, 0), 2.4, 0.4, null, null),
        new PreviewLight("Cave, torch", rgb(1.0, 0.6, 0.28), 0.9, rgb(0.02, 0.02, 0.025), rgb(0.03, 0.025, 0.02), rgb(0.02, 0.018, 0.015), rgb(0.05, 0.06, 0.09), 2.4, 0.2, null, null),
        new PreviewLight("Studio (neutral)", rgb(1, 1, 1), 0.9, rgb(0.5, 0.5, 0.5), rgb(0.5, 0.5, 0.5), rgb(0.35, 0.35, 0.35), rgb(0.35, 0.35, 0.35), 2.6, 0.3, null, null));

    /** The preview's current light (shared by every material preview). */
    static volatile PreviewLight current = PRESETS.get(0);

    PreviewLight withSun(double[] c, double strength) {
        return new PreviewLight(name, c, strength, sky, horizon, ground, fill, fillYaw, fillPitch, hdri, hdriSize);
    }

    PreviewLight withFill(double[] c) {
        return new PreviewLight(name, sun, sunStrength, sky, horizon, ground, c, fillYaw, fillPitch, hdri, hdriSize);
    }

    /** Light arriving from a direction (unit), as seen by a surface of this roughness (0 sharp .. 1 fully diffuse). */
    double[] env(double x, double y, double z, double rough) {
        if (hdri != null) {
            double u = Math.atan2(z, x) / (2 * Math.PI) + 0.5, v = Math.acos(Math.max(-1, Math.min(1, y))) / Math.PI;
            double level = Math.max(0, Math.min(hdri.length - 1, rough * (hdri.length - 1)));
            int l0 = (int) Math.floor(level), l1 = Math.min(hdri.length - 1, l0 + 1);
            double t = level - l0;
            double[] a = sample(l0, u, v), b = sample(l1, u, v);
            return rgb(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
        }
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            double up = y > 0 ? horizon[k] + (sky[k] - horizon[k]) * Math.pow(y, 0.6) : horizon[k] + (ground[k] - horizon[k]) * Math.min(1, -y * 3);
            // rough surfaces see the average of the hemisphere: pull toward the mean of sky and ground
            double mean = (sky[k] + horizon[k] + ground[k]) / 3;
            out[k] = up + (mean - up) * rough * 0.5;
        }
        return out;
    }

    private double[] sample(int level, double u, double v) {
        int w = hdriSize[level][0], h = hdriSize[level][1];
        int x = Math.floorMod((int) Math.floor(u * w), w), y = Math.max(0, Math.min(h - 1, (int) Math.floor(v * h)));
        float[] d = hdri[level];
        int i = (y * w + x) * 3;
        return rgb(d[i], d[i + 1], d[i + 2]);
    }

    /** An HDRI from a file: Radiance .hdr (RGBE), or PNG / JPG (sRGB, taken as linear 0..1 light). */
    static PreviewLight hdri(Path file) throws IOException {
        int w, h;
        float[] px;
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".hdr")) {
            try (InputStream in = Files.newInputStream(file)) {
                Object[] r = readHdr(new DataInputStream(new java.io.BufferedInputStream(in)));
                w = (Integer) r[0];
                h = (Integer) r[1];
                px = (float[]) r[2];
            }
        } else {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) throw new IOException("not an image");
            w = img.getWidth();
            h = img.getHeight();
            px = new float[w * h * 3];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int c = img.getRGB(x, y), i = (y * w + x) * 3;
                    for (int k = 0; k < 3; k++) px[i + k] = (float) Math.pow(((c >> (16 - 8 * k)) & 255) / 255.0, 2.2);
                }
        }
        // levels: about 256 wide sharp, then each a box blur of the one before at a quarter of the size
        int[][] sizes = new int[4][];
        float[][] levels = new float[4][];
        int lw = Math.min(256, w), lh = Math.max(1, lw * h / w);
        levels[0] = resize(px, w, h, lw, lh);
        sizes[0] = new int[] {lw, lh};
        for (int l = 1; l < 4; l++) {
            int nw = Math.max(2, sizes[l - 1][0] / 4), nh = Math.max(1, sizes[l - 1][1] / 4);
            levels[l] = resize(levels[l - 1], sizes[l - 1][0], sizes[l - 1][1], nw, nh);
            sizes[l] = new int[] {nw, nh};
        }
        // the brightest texel's direction becomes the sun of the preview; its colour too
        int best = 0;
        float bestL = -1;
        for (int i = 0; i < lw * lh; i++) {
            float l = levels[0][i * 3] + levels[0][i * 3 + 1] + levels[0][i * 3 + 2];
            if (l > bestL) {
                bestL = l;
                best = i;
            }
        }
        double s = Math.max(1e-6, bestL / 3);
        double[] sunC = rgb(levels[0][best * 3] / s, levels[0][best * 3 + 1] / s, levels[0][best * 3 + 2] / s);
        return new PreviewLight("HDRI " + file.getFileName(), sunC, Math.min(1.5, 0.2 + s * 0.15), null, null, null, rgb(0, 0, 0), 2.4, 0.4, levels, sizes);
    }

    static float[] resize(float[] src, int w, int h, int nw, int nh) {
        float[] out = new float[nw * nh * 3];
        for (int y = 0; y < nh; y++)
            for (int x = 0; x < nw; x++) {
                int x0 = x * w / nw, x1 = Math.max(x0 + 1, (x + 1) * w / nw), y0 = y * h / nh, y1 = Math.max(y0 + 1, (y + 1) * h / nh);
                double r = 0, g = 0, b = 0;
                int c = 0;
                for (int yy = y0; yy < y1; yy++)
                    for (int xx = x0; xx < x1; xx++) {
                        int i = (yy * w + xx) * 3;
                        r += src[i];
                        g += src[i + 1];
                        b += src[i + 2];
                        c++;
                    }
                int o = (y * nw + x) * 3;
                out[o] = (float) (r / c);
                out[o + 1] = (float) (g / c);
                out[o + 2] = (float) (b / c);
            }
        return out;
    }

    /** Radiance RGBE: header, -Y h +X w, then new-style RLE scanlines (or flat RGBE). */
    static Object[] readHdr(DataInputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int w = 0, h = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("truncated .hdr");
            if (c == '\n') {
                String l = line.toString().trim();
                line.setLength(0);
                if (l.startsWith("-Y") || l.startsWith("+Y")) {
                    String[] p = l.split("\\s+");
                    h = Integer.parseInt(p[1]);
                    w = Integer.parseInt(p[3]);
                    break;
                }
            } else line.append((char) c);
        }
        float[] px = new float[w * h * 3];
        byte[] scan = new byte[w * 4];
        for (int y = 0; y < h; y++) {
            int a = in.read(), b = in.read(), c = in.read(), d = in.read();
            if (a == 2 && b == 2 && ((c << 8) | d) == w && w >= 8 && w < 32768) {
                for (int ch = 0; ch < 4; ch++) {
                    int x = 0;
                    while (x < w) {
                        int count = in.read();
                        if (count > 128) {
                            count -= 128;
                            byte v = (byte) in.read();
                            for (int k = 0; k < count && x < w; k++) scan[(x++) * 4 + ch] = v;
                        } else {
                            for (int k = 0; k < count && x < w; k++) scan[(x++) * 4 + ch] = (byte) in.read();
                        }
                    }
                }
            } else { // flat
                scan[0] = (byte) a;
                scan[1] = (byte) b;
                scan[2] = (byte) c;
                scan[3] = (byte) d;
                in.readFully(scan, 4, w * 4 - 4);
            }
            for (int x = 0; x < w; x++) {
                int e = scan[x * 4 + 3] & 255;
                float f = e == 0 ? 0 : (float) Math.scalb(1.0, e - 136);
                int o = (y * w + x) * 3;
                px[o] = (scan[x * 4] & 255) * f;
                px[o + 1] = (scan[x * 4 + 1] & 255) * f;
                px[o + 2] = (scan[x * 4 + 2] & 255) * f;
            }
        }
        return new Object[] {w, h, px};
    }
}
