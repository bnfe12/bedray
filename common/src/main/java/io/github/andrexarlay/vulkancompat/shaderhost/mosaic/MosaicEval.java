package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Kind;
import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicGraph.Node;

/**
 * CPU twin of the GLSL each pure node emits: the same math on double vectors with GLSL broadcasting rules. Used for
 * constant folding in the optimizer, for material graphs (whose values are evaluated once) and for the editor's live
 * preview. Values are {@code double[]} of length 1..4; a null argument means "not known on the CPU".
 */
public final class MosaicEval {

    private MosaicEval() {}

    /** Node types whose value depends only on their inputs and parameters. */
    public static boolean isPure(String type) {
        return switch (type) {
            case "mosaic.const", "mosaic.swizzle", "mosaic.vec", "mosaic.add", "mosaic.sub", "mosaic.mul", "mosaic.div",
                 "mosaic.gt", "mosaic.lt", "mosaic.min", "mosaic.max", "mosaic.mix", "mosaic.clamp", "mosaic.step",
                 "mosaic.dot", "mosaic.cross", "mosaic.reflect", "mosaic.neg", "mosaic.floor", "mosaic.fract",
                 "mosaic.sqrt", "mosaic.exp", "mosaic.abs", "mosaic.saturate", "mosaic.normalize", "mosaic.length",
                 "mosaic.lum", "mosaic.math", "mosaic.mixColor", "mosaic.mapRange", "mosaic.colorRamp", "mosaic.reroute" -> true;
            default -> false;
        };
    }

    /** Default of an optional port that has no wire (also what the codegen emits). */
    public static double[] portDefault(String type, String port) {
        return switch (type + "." + port) {
            case "mosaic.mix.t", "mosaic.math.a", "mosaic.math.b", "mosaic.mixColor.fac", "mosaic.colorRamp.fac",
                 "mosaic.noise.roughness" -> new double[] {0.5};
            case "mosaic.clamp.lo", "mosaic.mapRange.fromMin", "mosaic.mapRange.toMin", "mosaic.noise.distortion",
                 "mosaic.wave.distortion" -> new double[] {0.0};
            case "mosaic.clamp.hi", "mosaic.mapRange.fromMax", "mosaic.mapRange.toMax", "mosaic.mapRange.value",
                 "mosaic.voronoi.randomness" -> new double[] {1.0};
            case "mosaic.mixColor.a", "mosaic.mixColor.b" -> new double[] {0.5, 0.5, 0.5, 1.0};
            case "mosaic.blur.radius", "mosaic.kawase.radius" -> new double[] {1.0};
            case "mosaic.noise.scale", "mosaic.voronoi.scale", "mosaic.checker.scale", "mosaic.wave.scale" -> new double[] {5.0};
            case "mosaic.noise.detail", "mosaic.wave.detail" -> new double[] {2.0};
            case "mosaic.checker.color1" -> new double[] {0.8, 0.8, 0.8, 1.0};
            case "mosaic.checker.color2" -> new double[] {0.2, 0.2, 0.2, 1.0};
            case "mosaic.vectorMath.scale", "mosaic.hueSat.saturation", "mosaic.hueSat.value", "mosaic.hueSat.fac", "mosaic.gamma.gamma",
                 "mosaic.invert.fac", "mosaic.smoothstep.edge1" -> new double[] {1.0};
            case "mosaic.hueSat.hue" -> new double[] {0.5};
            case "mosaic.hueSat.color", "mosaic.brightContrast.color", "mosaic.gamma.color", "mosaic.invert.color" -> new double[] {0.8, 0.8, 0.8, 1.0};
            case "mosaic.fresnel.normal" -> new double[] {0.0, 0.0, 1.0};
            case "mosaic.fresnel.view" -> new double[] {0.0, 0.0, -1.0};
            case "mosaic.fresnel.ior" -> new double[] {1.45};
            case "mosaic.mapping.scale" -> new double[] {1.0, 1.0, 1.0};
            case "mosaic.blackbody.temperature" -> new double[] {6500.0};
            case "mosaic.smoothstep.x" -> new double[] {0.5};
            default -> null;
        };
    }

    /**
     * Evaluates a pure node. {@code in} gives the value of each wired port (null entries: unknown). Returns null when
     * any needed input is unknown.
     */
    public static double[] apply(Node n, java.util.function.Function<String, double[]> in, Kind out) {
        String t = n.type();
        switch (t) {
            case "mosaic.const":
                return n.value().clone();
            case "mosaic.vec": {
                double[] x = in.apply("x"), y = in.apply("y"), z = in.apply("z"), w = in.apply("w");
                if (x == null) return null;
                int size = out.size();
                double[] r = new double[size];
                boolean onlyX = !has(n, "y") && !has(n, "z") && !has(n, "w");
                r[0] = x[0];
                if (onlyX) {
                    for (int i = 1; i < size; i++) r[i] = x[0];
                    return r;
                }
                if ((has(n, "y") && y == null) || (has(n, "z") && z == null) || (has(n, "w") && w == null)) return null;
                if (size > 1) r[1] = y == null ? 0 : y[0];
                if (size > 2) r[2] = z == null ? 0 : z[0];
                if (size > 3) r[3] = w == null ? 1 : w[0];
                return r;
            }
            case "mosaic.reroute":
                return in.apply("a");
            case "mosaic.math":
                return math(n, in.apply("a"), in.apply("b"), out);
            case "mosaic.mixColor":
                return mixColor(n, in.apply("a"), in.apply("b"), in.apply("fac"), out);
            case "mosaic.mapRange":
                return mapRange(n, in.apply("value"), in.apply("fromMin"), in.apply("fromMax"), in.apply("toMin"), in.apply("toMax"), out);
            case "mosaic.colorRamp":
                return ramp(n, in.apply("fac"));
            case "mosaic.swizzle": {
                double[] a = in.apply("a");
                if (a == null) return null;
                double[] r = new double[n.mask().length()];
                for (int i = 0; i < r.length; i++) {
                    char c = n.mask().charAt(i);
                    int k = "xyzw".indexOf(c) >= 0 ? "xyzw".indexOf(c) : "rgba".indexOf(c);
                    r[i] = a[k];
                }
                return r;
            }
            default:
                break;
        }
        double[] a = in.apply("a"), b = in.apply("b");
        switch (t) {
            case "mosaic.add": return a == null || b == null ? null : zip(a, b, out, Double::sum);
            case "mosaic.sub": return a == null || b == null ? null : zip(a, b, out, (x, y) -> x - y);
            case "mosaic.mul": return a == null || b == null ? null : zip(a, b, out, (x, y) -> x * y);
            case "mosaic.div": return a == null || b == null ? null : zip(a, b, out, (x, y) -> x / y);
            case "mosaic.min": return a == null || b == null ? null : zip(a, b, out, Math::min);
            case "mosaic.max": return a == null || b == null ? null : zip(a, b, out, Math::max);
            case "mosaic.step": return a == null || b == null ? null : zip(a, b, out, (e, x) -> x < e ? 0 : 1);
            case "mosaic.gt": return a == null || b == null ? null : new double[] {a[0] > b[0] ? 1 : 0};
            case "mosaic.lt": return a == null || b == null ? null : new double[] {a[0] < b[0] ? 1 : 0};
            case "mosaic.mix": {
                double[] tt = has(n, "t") ? in.apply("t") : portDefault(t, "t");
                if (a == null || b == null || tt == null) return null;
                double[] r = new double[out.size()];
                for (int i = 0; i < r.length; i++) r[i] = lane(a, i) + (lane(b, i) - lane(a, i)) * tt[0];
                return r;
            }
            case "mosaic.clamp": {
                double[] lo = has(n, "lo") ? in.apply("lo") : portDefault(t, "lo");
                double[] hi = has(n, "hi") ? in.apply("hi") : portDefault(t, "hi");
                if (a == null || lo == null || hi == null) return null;
                double[] r = new double[out.size()];
                for (int i = 0; i < r.length; i++) r[i] = Math.min(Math.max(lane(a, i), lo[0]), hi[0]);
                return r;
            }
            case "mosaic.dot": {
                if (a == null || b == null) return null;
                double s = 0;
                for (int i = 0; i < a.length; i++) s += a[i] * b[i];
                return new double[] {s};
            }
            case "mosaic.cross":
                if (a == null || b == null) return null;
                return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
            case "mosaic.reflect": {
                if (a == null || b == null) return null;
                double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
                return new double[] {a[0] - 2 * d * b[0], a[1] - 2 * d * b[1], a[2] - 2 * d * b[2]};
            }
            case "mosaic.lum":
                return a == null ? null : new double[] {a[0] * 0.2126 + a[1] * 0.7152 + a[2] * 0.0722};
            case "mosaic.length": {
                if (a == null) return null;
                double s = 0;
                for (double v : a) s += v * v;
                return new double[] {Math.sqrt(s)};
            }
            case "mosaic.normalize": {
                if (a == null) return null;
                double s = 0;
                for (double v : a) s += v * v;
                double len = Math.sqrt(s);
                double[] r = new double[a.length];
                for (int i = 0; i < r.length; i++) r[i] = a[i] / len;
                return r;
            }
            case "mosaic.neg": return a == null ? null : map(a, x -> -x);
            case "mosaic.floor": return a == null ? null : map(a, Math::floor);
            case "mosaic.fract": return a == null ? null : map(a, x -> x - Math.floor(x));
            case "mosaic.sqrt": return a == null ? null : map(a, Math::sqrt);
            case "mosaic.exp": return a == null ? null : map(a, Math::exp);
            case "mosaic.abs": return a == null ? null : map(a, Math::abs);
            case "mosaic.saturate": return a == null ? null : map(a, x -> Math.min(Math.max(x, 0), 1));
            default: return null;
        }
    }

    private static boolean has(Node n, String port) { return n.inputs().containsKey(port); }

    static double mathOp(String op, double a, double b) {
        return switch (op == null ? "add" : op) {
            case "add" -> a + b;
            case "subtract" -> a - b;
            case "multiply" -> a * b;
            case "divide" -> a / b;
            case "power" -> Math.pow(a, b);
            case "logarithm" -> Math.log(a) / Math.log(b);
            case "sqrt" -> Math.sqrt(a);
            case "inverseSqrt" -> 1 / Math.sqrt(a);
            case "absolute" -> Math.abs(a);
            case "exponent" -> Math.exp(a);
            case "minimum" -> Math.min(a, b);
            case "maximum" -> Math.max(a, b);
            case "lessThan" -> a < b ? 1 : 0;
            case "greaterThan" -> a > b ? 1 : 0;
            case "sign" -> Math.signum(a);
            case "round" -> Math.floor(a + 0.5);
            case "floor" -> Math.floor(a);
            case "ceil" -> Math.ceil(a);
            case "truncate" -> a < 0 ? Math.ceil(a) : Math.floor(a);
            case "fraction" -> a - Math.floor(a);
            case "modulo" -> a - b * Math.floor(a / b);
            case "snap" -> Math.floor(a / b) * b;
            case "pingpong" -> b - Math.abs((a - 2 * b * Math.floor(a / (2 * b))) - b);
            case "sine" -> Math.sin(a);
            case "cosine" -> Math.cos(a);
            case "tangent" -> Math.tan(a);
            case "arcsine" -> Math.asin(a);
            case "arccosine" -> Math.acos(a);
            case "arctangent" -> Math.atan(a);
            case "arctan2" -> Math.atan2(a, b);
            case "radians" -> Math.toRadians(a);
            case "degrees" -> Math.toDegrees(a);
            default -> Double.NaN;
        };
    }

    private static double[] math(Node n, double[] a, double[] b, Kind out) {
        if (a == null || b == null) return null;
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) {
            double v = mathOp(n.mask(), lane(a, i), lane(b, i));
            r[i] = n.clampFlag() ? Math.min(Math.max(v, 0), 1) : v;
        }
        return r;
    }

    static double blend(String mode, double a, double b) {
        return switch (mode == null ? "mix" : mode) {
            case "add" -> a + b;
            case "multiply" -> a * b;
            case "subtract" -> a - b;
            case "screen" -> 1 - (1 - a) * (1 - b);
            case "overlay" -> a < 0.5 ? 2 * a * b : 1 - 2 * (1 - a) * (1 - b);
            case "darken" -> Math.min(a, b);
            case "lighten" -> Math.max(a, b);
            case "difference" -> Math.abs(a - b);
            case "divide" -> a / b;
            case "softLight" -> (1 - 2 * b) * a * a + 2 * b * a;
            case "linearLight" -> a + 2 * b - 1;
            case "burn" -> 1 - (1 - a) / Math.max(b, 1e-5);
            case "dodge" -> a / Math.max(1 - b, 1e-5);
            default -> b;
        };
    }

    private static double[] mixColor(Node n, double[] a, double[] b, double[] f, Kind out) {
        if (a == null || b == null || f == null) return null;
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) {
            double x = wide(a, i), v = x + (blend(n.mask(), x, wide(b, i)) - x) * f[0];
            r[i] = n.clampFlag() ? Math.min(Math.max(v, 0), 1) : v;
        }
        return r;
    }

    private static double[] mapRange(Node n, double[] v, double[] fMin, double[] fMax, double[] tMin, double[] tMax, Kind out) {
        if (v == null || fMin == null || fMax == null || tMin == null || tMax == null) return null;
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) {
            double t = (lane(v, i) - fMin[0]) / (fMax[0] - fMin[0]);
            if ("smooth".equals(n.mask())) {
                t = Math.min(Math.max(t, 0), 1);
                t = t * t * (3 - 2 * t);
            }
            double x = tMin[0] + t * (tMax[0] - tMin[0]);
            if (n.clampFlag()) x = Math.min(Math.max(x, Math.min(tMin[0], tMax[0])), Math.max(tMin[0], tMax[0]));
            r[i] = x;
        }
        return r;
    }

    /** Color ramp: stops of (position, r, g, b, a); linear, constant or eased between them. */
    static double[] ramp(Node n, double[] fac) {
        double[] s = n.value() == null || n.value().length < 5 ? new double[] {0, 0, 0, 0, 1, 1, 1, 1, 1, 1} : n.value();
        if (fac == null) return null;
        double f = Math.min(Math.max(fac[0], 0), 1);
        double[] c = {s[1], s[2], s[3], s[4]};
        for (int i = 5; i + 4 < s.length; i += 5) {
            double p0 = s[i - 5], p1 = s[i];
            double t = Math.min(Math.max((f - p0) / Math.max(p1 - p0, 1e-5), 0), 1);
            if ("constant".equals(n.mask())) t = f >= p1 ? 1 : 0;
            else if ("ease".equals(n.mask())) t = t * t * (3 - 2 * t);
            for (int k = 0; k < 4; k++) c[k] = c[k] + (s[i + 1 + k] - c[k]) * t;
        }
        return c;
    }

    private static double lane(double[] v, int i) { return v.length == 1 ? v[0] : v[i]; }

    /** Lane of a value widened like the codegen does: broadcast floats, alpha 1, missing components 0. */
    private static double wide(double[] v, int i) {
        if (v.length == 1) return v[0];
        if (i < v.length) return v[i];
        return i == 3 ? 1 : 0;
    }

    private static double[] zip(double[] a, double[] b, Kind out, java.util.function.DoubleBinaryOperator f) {
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = f.applyAsDouble(lane(a, i), lane(b, i));
        return r;
    }

    private static double[] map(double[] a, java.util.function.DoubleUnaryOperator f) {
        double[] r = new double[a.length];
        for (int i = 0; i < r.length; i++) r[i] = f.applyAsDouble(a[i]);
        return r;
    }

    /** GLSL literal of a value at a kind: a single number broadcasts ({@code vec3(0.5)}). */
    public static String literal(double[] v, Kind k, boolean slang) {
        if (k == Kind.F) return number(v[0]);
        String type = slang ? k.slang() : k.glsl();
        if (v.length == 1) return type + "(" + number(v[0]) + ")";
        StringBuilder sb = new StringBuilder(type).append('(');
        for (int i = 0; i < k.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(number(i < v.length ? v[i] : 0));
        }
        return sb.append(')').toString();
    }

    public static String number(double v) {
        if (Double.isNaN(v)) return "(0.0 / 0.0)";
        if (Double.isInfinite(v)) return v > 0 ? "(1.0 / 0.0)" : "(-1.0 / 0.0)";
        if (v == Math.rint(v) && Math.abs(v) < 1.0e15) return (long) v + ".0";
        String s = Double.toString(v);
        return s.contains("E") ? String.format(java.util.Locale.ROOT, "%.9e", v) : s;
    }
}
