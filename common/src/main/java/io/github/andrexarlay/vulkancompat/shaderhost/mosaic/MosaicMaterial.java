package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Kind;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Material graphs: a node graph that ends in {@code mat.output <- mat.bsdf} (a Principled-BSDF-like surface) is evaluated
 * on the CPU for every texel of a block or item texture and baked into what Bedray's path tracer already reads:
 * the albedo, a LabPBR {@code _s} map (smoothness, F0 or metal, emission) and a LabPBR {@code _n} map (DirectX normal
 * from the height input, AO, height). Procedural textures tile across the texture (Minecraft textures repeat on every
 * block). Pure Java: the studio bakes, the game only reloads resources.
 */
public final class MosaicMaterial {

    private MosaicMaterial() {}

    /** A texture as ARGB ints (alpha in the top byte), rows top-down. */
    public record Image(int w, int h, int[] argb) {
        public int at(double u, double v) {
            int x = Math.floorMod((int) Math.floor(u * w), w), y = Math.floorMod((int) Math.floor(v * h), h);
            return argb[y * w + x];
        }
    }

    /**
     * What a bake produced. {@code albedo} is null when the graph keeps the original colours; {@code normal} is null when the
     * graph sets no height, normal strength or AO (Bedray keeps its automatic normals then).
     */
    public record Baked(int w, int h, int[] albedo, int[] specular, int[] normal, List<String> warnings, double albedoChange) {}

    /** The surface of one texel, before encoding. */
    public record Surface(double[] base, double roughness, double metallic, double specular, double emission, double height,
                          double normalStrength, double ao) {}

    public static final double STRONG_CHANGE = 0.22;

    /**
     * Bakes the graph for a texture. {@code images} holds imported maps by the path in a {@code mat.image} node's mask.
     * Throws IllegalArgumentException for a graph that is not a material graph.
     */
    public static Baked bake(MosaicGraph g, Image base, Map<String, Image> images) {
        if (g == null || !g.isMaterial()) throw new IllegalArgumentException("not a material graph (needs a mat.output node)");
        Node out = g.node(g.outputId());
        String bsdfRef = out.inputs().get("surface");
        Node bsdf = bsdfRef == null ? null : g.node(MosaicGraph.split(bsdfRef)[0]);
        if (bsdf == null || !bsdf.type().equals("mat.bsdf")) throw new IllegalArgumentException("mat.output needs a Principled BSDF (mat.bsdf) node");
        int w = base.w(), h = base.h(), n = w * h;
        int[] albedo = new int[n], spec = new int[n];
        double[] height = new double[n], strength = new double[n], ao = new double[n];
        boolean albedoChanged = false, wantsNormal = bsdf.inputs().containsKey("height") || bsdf.inputs().containsKey("ao")
            || bsdf.defaults().containsKey("height") || bsdf.defaults().containsKey("ao");
        double change = 0;
        List<String> warnings = new ArrayList<>();
        Evaluator ev = new Evaluator(g, base, images == null ? Map.of() : images);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                ev.at(x, y);
                Surface s = ev.surface(bsdf);
                int orig = base.argb()[i];
                double[] c = s.base();
                int a = c.length > 3 ? clamp255(c[3]) : orig >>> 24;
                int rgb = clamp255(c[0]) << 16 | clamp255(c[1]) << 8 | clamp255(c[2]);
                albedo[i] = a << 24 | rgb;
                if (albedo[i] != orig) albedoChanged = true;
                change += (Math.abs(((orig >> 16) & 255) - clamp255(c[0])) + Math.abs(((orig >> 8) & 255) - clamp255(c[1]))
                    + Math.abs((orig & 255) - clamp255(c[2]))) / (3 * 255.0);
                spec[i] = labSpecular(s, rgb);
                height[i] = s.height();
                strength[i] = s.normalStrength();
                ao[i] = s.ao();
            }
        change /= n;
        if (change > STRONG_CHANGE)
            warnings.add(String.format("the colours differ strongly from the original texture (%.0f%% on average): at %dx%d every pixel shows,"
                + " check it on a block in the game", change * 100, w, h));
        for (String warning : ev.warnings) if (!warnings.contains(warning)) warnings.add(warning);
        int[] normal = wantsNormal ? labNormal(height, strength, ao, w, h) : null;
        return new Baked(w, h, albedoChanged ? albedo : null, spec, normal, List.copyOf(warnings), change);
    }

    static int clamp255(double v) { return (int) Math.round(Math.max(0, Math.min(1, v)) * 255); }

    /** LabPBR _s: R perceptual smoothness, G F0 (0..229) or 255 = metal (albedo is F0), B 0, A emission (255 = none). */
    static int labSpecular(Surface s, int rgb) {
        int r = clamp255(1 - s.roughness());
        int g = s.metallic() >= 0.5 ? 255 : Math.min(229, clamp255(0.08 * s.specular()));
        int a = s.emission() <= 0 ? 255 : Math.max(1, Math.min(254, (int) Math.round(Math.min(1, s.emission()) * 254)));
        return a << 24 | r << 16 | g << 8;
    }

    /** LabPBR _n: DirectX normal from height (wrapping, like the block repeats), B = AO, A = height. */
    static int[] labNormal(double[] height, double[] strength, double[] ao, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double hl = height[y * w + Math.floorMod(x - 1, w)], hr = height[y * w + Math.floorMod(x + 1, w)];
                double hu = height[Math.floorMod(y - 1, h) * w + x], hd = height[Math.floorMod(y + 1, h) * w + x];
                double k = strength[i] * 2.0;
                double nx = -(hr - hl) * 0.5 * k, nyUp = (hd - hu) * 0.5 * k, inv = 1 / Math.sqrt(nx * nx + nyUp * nyUp + 1);
                int r = clamp255(nx * inv * 0.5 + 0.5), g = clamp255(-nyUp * inv * 0.5 + 0.5);
                int b = clamp255(ao[i]), a = Math.max(1, clamp255(height[i]));
                out[i] = a << 24 | r << 16 | g << 8 | b;
            }
        return out;
    }

    // ------------------------------------------------------------------ evaluation

    static final class Evaluator {
        final MosaicGraph g;
        final Image base;
        final Map<String, Image> images;
        final List<String> warnings = new ArrayList<>();
        final Map<String, double[]> memo = new HashMap<>();
        int x, y;

        Evaluator(MosaicGraph g, Image base, Map<String, Image> images) {
            this.g = g;
            this.base = base;
            this.images = images;
        }

        void at(int x, int y) {
            this.x = x;
            this.y = y;
            memo.clear();
        }

        Surface surface(Node b) {
            double[] baseColor = b.inputs().containsKey("baseColor") ? widen(in(b, "baseColor"), 4) : rgba(base.argb()[y * base.w() + x]);
            if (!b.inputs().containsKey("baseColor") && b.defaults().containsKey("baseColor")) baseColor = widen(b.defaults().get("baseColor"), 4);
            return new Surface(baseColor, scalar(b, "roughness", 0.5), scalar(b, "metallic", 0), scalar(b, "specular", 0.5),
                scalar(b, "emission", 0), scalar(b, "height", 0.5), scalar(b, "normalStrength", 1), scalar(b, "ao", 1));
        }

        double scalar(Node n, String port, double dflt) {
            double[] v = in(n, port);
            if (v == null) return n.defaults().containsKey(port) ? n.defaults().get(port)[0] : dflt;
            return v[0];
        }

        /** Value of a wired port (null when unwired). */
        double[] in(Node n, String port) {
            String ref = n.inputs().get(port);
            return ref == null ? null : value(ref);
        }

        double[] value(String ref) {
            double[] known = memo.get(ref);
            if (known != null) return known;
            String[] p = MosaicGraph.split(ref);
            Node n = g.node(p[0]);
            double[] v = eval(n, p[1]);
            memo.put(ref, v);
            return v;
        }

        /** A port's value: wired, else the node's inline default, else the type default. */
        double[] port(Node n, String port) {
            double[] v = in(n, port);
            if (v != null) return v;
            if (n.defaults().containsKey(port)) return n.defaults().get(port);
            return MosaicEval.portDefault(n.type(), port);
        }

        double[] uv() { return new double[] {(x + 0.5) / base.w(), (y + 0.5) / base.h()}; }

        double[] eval(Node n, String named) {
            Kind kind = g.kind(n.id());
            switch (n.type()) {
                case "mosaic.uv": return uv();
                case "mosaic.texel": return new double[] {x, y};
                case "mosaic.size": return new double[] {base.w(), base.h()};
                case "mat.texture": {
                    double[] uv = in(n, "uv");
                    return rgba(uv == null ? base.argb()[y * base.w() + x] : base.at(uv[0], uv[1]));
                }
                case "mat.image": {
                    Image img = images.get(n.mask());
                    if (img == null) {
                        String w = "image " + n.mask() + " is missing: import it again (Library)";
                        if (!warnings.contains(w)) warnings.add(w);
                        return new double[] {1, 0, 1, 1};
                    }
                    double[] uv = in(n, "uv");
                    if (uv == null) uv = uv();
                    if (img.w() > base.w() * 2) {
                        String w = "image " + n.mask() + " is " + img.w() + " px wide, the texture " + base.w() + ": its detail is lost to"
                            + " the " + base.w() + "x" + base.h() + " grid";
                        if (!warnings.contains(w)) warnings.add(w);
                    }
                    return rgba(img.at(uv[0], uv[1]));
                }
                case "mosaic.noise": {
                    double scale = port(n, "scale")[0];
                    if (scale > base.w() / 2.0) aliasWarning(n, scale);
                    return Procedural.noise(vector(n), scale, port(n, "detail")[0], port(n, "roughness")[0], port(n, "distortion")[0],
                        !n.inputs().containsKey("vector"), "color".equals(named));
                }
                case "mosaic.voronoi": {
                    double scale = port(n, "scale")[0];
                    if (scale > base.w() / 2.0) aliasWarning(n, scale);
                    return Procedural.voronoi(vector(n), scale, port(n, "randomness")[0], !n.inputs().containsKey("vector"), named);
                }
                case "mosaic.whiteNoise": return Procedural.white(vector(n), "color".equals(named));
                case "mosaic.checker": {
                    double[] c = Procedural.checker(vector(n), port(n, "scale")[0], widen(port(n, "color1"), 4), widen(port(n, "color2"), 4));
                    return "fac".equals(named) ? new double[] {c[4]} : new double[] {c[0], c[1], c[2], c[3]};
                }
                case "mosaic.wave": return Procedural.wave(vector(n), port(n, "scale")[0], port(n, "distortion")[0], port(n, "detail")[0],
                    "rings".equals(n.mask()), !n.inputs().containsKey("vector"));
                case "mosaic.gradient": return Procedural.gradient(vector(n), n.mask() == null ? "linear" : n.mask());
                default: break;
            }
            double[] r = MosaicEval.apply(n, port -> {
                double[] v = in(n, port);
                if (v != null) return v;
                if (n.defaults().containsKey(port)) return n.defaults().get(port);
                return MosaicEval.portDefault(n.type(), port);
            }, kind == null ? Kind.V4 : kind);
            return r == null ? new double[kind == null ? 4 : kind.size()] : r;
        }

        void aliasWarning(Node n, double scale) {
            String w = "node " + n.id() + ": scale " + Math.round(scale) + " is finer than the " + base.w() + " px texture, it turns into"
                + " pixel noise (keep it at " + base.w() / 2 + " or less)";
            if (!warnings.contains(w)) warnings.add(w);
        }

        double[] vector(Node n) {
            double[] v = in(n, "vector");
            if (v == null) {
                double[] uv = uv();
                return new double[] {uv[0], uv[1], 0};
            }
            return widen(v, 3);
        }
    }

    static double[] rgba(int argb) {
        return new double[] {((argb >> 16) & 255) / 255.0, ((argb >> 8) & 255) / 255.0, (argb & 255) / 255.0, (argb >>> 24) / 255.0};
    }

    static double[] widen(double[] v, int size) {
        if (v == null) return null;
        if (v.length == size) return v;
        double[] r = new double[size];
        for (int i = 0; i < size; i++) r[i] = v.length == 1 ? (i == 3 ? 1 : v[0]) : i < v.length ? v[i] : (i == 3 ? 1 : 0);
        return r;
    }

    // ------------------------------------------------------------------ procedural textures (CPU, tiling)

    /** CPU procedural textures. When {@code tile} is set the lattice repeats once per texture, so blocks join seamlessly. */
    static final class Procedural {
        private Procedural() {}

        static double hash(int x, int y, int z, int seed) {
            int h = x * 374761393 + y * 668265263 + z * 1274126177 + seed * 1442695041;
            h = (h ^ (h >>> 13)) * 1274126177;
            h ^= h >>> 16;
            return (h & 0x7fffffff) / (double) 0x7fffffff;
        }

        static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

        static double grad(int ix, int iy, int iz, double fx, double fy, double fz, int period) {
            if (period > 0) {
                ix = Math.floorMod(ix, period);
                iy = Math.floorMod(iy, period);
            }
            double a = hash(ix, iy, iz, 7) * Math.PI * 2, b = hash(ix, iy, iz, 13) * 2 - 1, s = Math.sqrt(1 - b * b);
            return fx * Math.cos(a) * s + fy * Math.sin(a) * s + fz * b;
        }

        /** Gradient noise in [0,1]; {@code period} > 0 repeats in x and y. */
        static double perlin(double x, double y, double z, int period) {
            int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
            double fx = x - ix, fy = y - iy, fz = z - iz, u = fade(fx), v = fade(fy), w = fade(fz);
            double r = 0;
            for (int c = 0; c < 8; c++) {
                int dx = c & 1, dy = c >> 1 & 1, dz = c >> 2 & 1;
                double wt = (dx == 1 ? u : 1 - u) * (dy == 1 ? v : 1 - v) * (dz == 1 ? w : 1 - w);
                r += wt * grad(ix + dx, iy + dy, iz + dz, fx - dx, fy - dy, fz - dz, period);
            }
            return Math.max(0, Math.min(1, r * 0.75 + 0.5));
        }

        static double fbm(double x, double y, double z, double scale, double detail, double rough, boolean tile, int seed) {
            double sum = 0, amp = 1, norm = 0, f = 1;
            int octaves = (int) Math.max(1, Math.min(8, Math.floor(detail) + 1));
            for (int o = 0; o < octaves; o++) {
                int period = tile ? Math.max(1, (int) Math.round(scale * f)) : 0;
                double s = tile ? period : scale * f;
                sum += amp * perlin(x * s + seed * 17.3, y * s + seed * 5.1, z * s, period > 0 ? period : 0);
                norm += amp;
                amp *= Math.max(0, Math.min(1, rough));
                f *= 2;
            }
            return sum / norm;
        }

        static double[] noise(double[] p, double scale, double detail, double rough, double distortion, boolean tile, boolean color) {
            double x = p[0], y = p[1], z = p[2];
            if (distortion != 0) {
                x += (fbm(x, y, z, scale, 1, 0.5, tile, 3) - 0.5) * distortion / Math.max(1, scale);
                y += (fbm(x, y, z, scale, 1, 0.5, tile, 4) - 0.5) * distortion / Math.max(1, scale);
            }
            if (!color) return new double[] {fbm(x, y, z, scale, detail, rough, tile, 0)};
            return new double[] {fbm(x, y, z, scale, detail, rough, tile, 0), fbm(x, y, z, scale, detail, rough, tile, 1),
                fbm(x, y, z, scale, detail, rough, tile, 2)};
        }

        static double[] voronoi(double[] p, double scale, double randomness, boolean tile, String named) {
            int period = tile ? Math.max(1, (int) Math.round(scale)) : 0;
            double s = tile ? period : scale;
            double x = p[0] * s, y = p[1] * s;
            int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
            double best = 1e9;
            int bx = 0, by = 0;
            double px = 0, py = 0;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = ix + dx, cy = iy + dy;
                    int hx = period > 0 ? Math.floorMod(cx, period) : cx, hy = period > 0 ? Math.floorMod(cy, period) : cy;
                    double ox = cx + 0.5 + (hash(hx, hy, 0, 21) - 0.5) * randomness, oy = cy + 0.5 + (hash(hx, hy, 0, 22) - 0.5) * randomness;
                    double d = Math.hypot(ox - x, oy - y);
                    if (d < best) {
                        best = d;
                        bx = hx;
                        by = hy;
                        px = ox / s;
                        py = oy / s;
                    }
                }
            if ("color".equals(named)) return new double[] {hash(bx, by, 0, 31), hash(bx, by, 0, 32), hash(bx, by, 0, 33)};
            if ("position".equals(named)) return new double[] {px, py, 0};
            return new double[] {best};
        }

        static double[] white(double[] p, boolean color) {
            int x = (int) Math.floor(p[0] * 4096), y = (int) Math.floor(p[1] * 4096), z = (int) Math.floor(p[2] * 4096);
            return color ? new double[] {hash(x, y, z, 41), hash(x, y, z, 42), hash(x, y, z, 43)} : new double[] {hash(x, y, z, 40)};
        }

        /** r, g, b, a, fac. */
        static double[] checker(double[] p, double scale, double[] c1, double[] c2) {
            int k = (int) (Math.floor(p[0] * scale) + Math.floor(p[1] * scale) + Math.floor(p[2] * scale));
            boolean first = Math.floorMod(k, 2) == 0;
            double[] c = first ? c1 : c2;
            return new double[] {c[0], c[1], c[2], c[3], first ? 1 : 0};
        }

        static double[] wave(double[] p, double scale, double distortion, double detail, boolean rings, boolean tile) {
            double base = rings ? Math.hypot(p[0] - 0.5, p[1] - 0.5) * 2 : (p[0] + p[1]) * 0.5;
            if (distortion != 0) base += (fbm(p[0], p[1], p[2], 4, detail, 0.5, tile, 9) - 0.5) * distortion * 0.2;
            double periods = tile ? Math.max(1, Math.round(scale)) : scale;
            return new double[] {0.5 + 0.5 * Math.sin(base * periods * Math.PI * 2)};
        }

        static double[] gradient(double[] p, String mode) {
            double x = p[0], y = p[1];
            double v = switch (mode) {
                case "quadratic" -> Math.pow(Math.max(0, x), 2);
                case "easing" -> {
                    double t = Math.max(0, Math.min(1, x));
                    yield t * t * (3 - 2 * t);
                }
                case "diagonal" -> (x + y) * 0.5;
                case "spherical" -> Math.max(0, 1 - Math.hypot(x - 0.5, y - 0.5) * 2);
                case "radial" -> Math.atan2(y - 0.5, x - 0.5) / (Math.PI * 2) + 0.5;
                default -> x;
            };
            return new double[] {Math.max(0, Math.min(1, v))};
        }
    }

    // ------------------------------------------------------------------ starting graph

    /** A ready graph for a texture: its colour into a Principled BSDF, with roughness and a height from its brightness. */
    public static String starter(double roughness, double metallic) {
        return "{\"kind\": \"material\", \"nodes\": ["
            + "{\"id\": \"tex\", \"type\": \"mat.texture\", \"x\": 40, \"y\": 80},"
            + "{\"id\": \"rgb\", \"type\": \"mosaic.swizzle\", \"mask\": \"rgb\", \"inputs\": {\"a\": \"tex\"}, \"x\": 250, \"y\": 230},"
            + "{\"id\": \"lum\", \"type\": \"mosaic.lum\", \"inputs\": {\"a\": \"rgb\"}, \"x\": 250, \"y\": 330},"
            + "{\"id\": \"bsdf\", \"type\": \"mat.bsdf\", \"inputs\": {\"baseColor\": \"tex\", \"height\": \"lum\"},"
            + " \"defaults\": {\"roughness\": [" + roughness + "], \"metallic\": [" + metallic + "], \"normalStrength\": [1.0]}, \"x\": 480, \"y\": 80},"
            + "{\"id\": \"out\", \"type\": \"mat.output\", \"inputs\": {\"surface\": \"bsdf\"}, \"x\": 720, \"y\": 80}]}";
    }
}
