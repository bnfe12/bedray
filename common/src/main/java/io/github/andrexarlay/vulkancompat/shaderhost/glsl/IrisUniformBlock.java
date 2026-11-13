package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * std140 uniform-block layout + writer for the Iris host, and an evaluator for pack-declared {@code uniform.*} expressions.
 * Layout is computed from the ordered name->type map the translator reports as used; samplers are bound separately.
 */
public final class IrisUniformBlock {

    public record Layout(Map<String, Integer> offsets, Map<String, String> types, int bytes) {
        public int offset(String name) {
            Integer o = offsets.get(name);
            if (o == null) throw new IllegalArgumentException("uniform not in layout: " + name);
            return o;
        }
    }

    private IrisUniformBlock() {}

    public static Layout layout(Map<String, String> types) {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        int cursor = 0;
        for (Map.Entry<String, String> e : types.entrySet()) {
            int align = alignOf(e.getValue());
            int size = sizeOf(e.getValue());
            cursor = align(cursor, align);
            offsets.put(e.getKey(), cursor);
            cursor += size;
        }
        return new Layout(Map.copyOf(offsets), Map.copyOf(types), align(cursor, 16));
    }

    private static int align(int v, int a) {
        return (v + a - 1) / a * a;
    }

    private static int alignOf(String type) {
        return switch (type) {
            case "vec2" -> 8;
            case "vec3", "vec4", "mat4", "mat3" -> 16;
            default -> 4;
        };
    }

    private static int sizeOf(String type) {
        return switch (type) {
            case "vec2" -> 8;
            case "vec3" -> 16;
            case "vec4" -> 16;
            case "mat3" -> 48;
            case "mat4" -> 64;
            default -> 4;
        };
    }

    /** std140 byte size of one member (vec3 occupies a 16-byte slot). */
    public static int byteSize(String type) {
        return sizeOf(type);
    }

    public static void write(ByteBuffer dst, Layout layout, Map<String, float[]> values) {
        for (Map.Entry<String, float[]> e : values.entrySet()) {
            writeMember(dst, layout.offset(e.getKey()), layout.types().getOrDefault(e.getKey(), "float"), e.getValue());
        }
    }

    /** Writes one member at a fixed offset, encoding int/bool as 32-bit integers (std140). */
    public static void writeMember(ByteBuffer dst, int offset, String type, float[] v) {
        for (int i = 0; i < v.length; i++) {
            switch (type) {
                case "int", "uint" -> dst.putInt(offset + i * 4, (int) v[i]);
                case "bool" -> dst.putInt(offset + i * 4, v[i] != 0f ? 1 : 0);
                default -> dst.putFloat(offset + i * 4, v[i]);
            }
        }
    }

    /** Evaluator for pack uniform expressions; unsupported functions throw {@link Unsupported} naming the function. */
    public static final class Expr {
        public static class Unsupported extends RuntimeException {
            public Unsupported(String feature) {
                super("unsupported uniform expression function: " + feature);
            }
        }

        /** Resolves a name to a vector (scalars are length-1). Swizzles index into the result. */
        public interface Resolver {
            double[] resolve(String name);
        }

        /** Stateful temporal smoothing: {@code smooth(id, target, upSeconds, downSeconds)}. */
        public interface Smooth {
            double apply(int id, double target, double up, double down);
        }

        private final String src;
        private final Resolver resolver;
        private final Smooth smooth;
        private int pos;

        private Expr(String src, Resolver resolver, Smooth smooth) {
            this.src = src;
            this.resolver = resolver;
            this.smooth = smooth;
        }

        public static double eval(String expr, Map<String, Double> vars) {
            return eval(expr, name -> new double[] {vars.getOrDefault(name, 0.0)}, null);
        }

        public static double eval(String expr, Resolver resolver, Smooth smooth) {
            Expr e = new Expr(expr, resolver, smooth);
            double v = e.or();
            e.ws();
            if (e.pos != e.src.length()) throw new Unsupported("trailing:" + e.src.substring(e.pos));
            return v;
        }

        private void ws() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        private char peek() {
            ws();
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private boolean consume(String op) {
            ws();
            if (src.startsWith(op, pos)) {
                pos += op.length();
                return true;
            }
            return false;
        }

        private double or() {
            double v = and();
            while (consume("||")) {
                double r = and();
                v = (v != 0 || r != 0) ? 1 : 0;
            }
            return v;
        }

        private double and() {
            double v = comparison();
            while (consume("&&")) {
                double r = comparison();
                v = (v != 0 && r != 0) ? 1 : 0;
            }
            return v;
        }

        private double comparison() {
            double v = expr();
            while (true) {
                if (consume("<=")) {
                    v = v <= expr() ? 1 : 0;
                } else if (consume(">=")) {
                    v = v >= expr() ? 1 : 0;
                } else if (consume("==")) {
                    v = v == expr() ? 1 : 0;
                } else if (consume("!=")) {
                    v = v != expr() ? 1 : 0;
                } else if (consume("<")) {
                    v = v < expr() ? 1 : 0;
                } else if (consume(">")) {
                    v = v > expr() ? 1 : 0;
                } else {
                    return v;
                }
            }
        }

        private double expr() {
            double v = term();
            while (peek() == '+' || peek() == '-') {
                char op = src.charAt(pos++);
                double r = term();
                v = op == '+' ? v + r : v - r;
            }
            return v;
        }

        private double term() {
            double v = factor();
            while (peek() == '*' || peek() == '/' || peek() == '%') {
                char op = src.charAt(pos++);
                double r = factor();
                v = op == '*' ? v * r : op == '/' ? v / r : v % r;
            }
            return v;
        }

        private double factor() {
            char c = peek();
            if (c == '-') {
                pos++;
                return -factor();
            }
            if (c == '+') {
                pos++;
                return factor();
            }
            if (c == '!') {
                pos++;
                return factor() == 0 ? 1 : 0;
            }
            return primary();
        }

        private double primary() {
            char c = peek();
            if (c == '(') {
                pos++;
                double v = or();
                if (peek() == ')') pos++;
                return v;
            }
            if (Character.isDigit(c) || c == '.') {
                int j = pos;
                while (j < src.length() && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) j++;
                double v = Double.parseDouble(src.substring(pos, j));
                pos = j;
                return v;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = pos;
                while (j < src.length() && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) j++;
                String name = src.substring(pos, j);
                pos = j;
                if (peek() == '(') {
                    pos++;
                    List<Double> args = new ArrayList<>();
                    if (peek() != ')') {
                        args.add(or());
                        while (consume(",")) {
                            args.add(or());
                        }
                    }
                    if (peek() == ')') pos++;
                    return call(name, args);
                }
                double[] vec = resolver.resolve(name);
                if (consume(".")) {
                    int k = pos;
                    while (k < src.length() && "xyzwrgba".indexOf(src.charAt(k)) >= 0) k++;
                    String sw = src.substring(pos, k);
                    pos = k;
                    if (sw.length() == 1) {
                        return vec[swizzleIndex(sw.charAt(0)) % Math.max(1, vec.length)];
                    }
                    return vec[swizzleIndex(sw.charAt(0)) % Math.max(1, vec.length)];
                }
                return vec.length == 0 ? 0 : vec[0];
            }
            throw new Unsupported("char:" + c);
        }

        private static int swizzleIndex(char c) {
            return switch (c) {
                case 'x', 'r' -> 0;
                case 'y', 'g' -> 1;
                case 'z', 'b' -> 2;
                case 'w', 'a' -> 3;
                default -> 0;
            };
        }

        private double call(String name, List<Double> a) {
            double x = a.isEmpty() ? 0 : a.get(0);
            double y = a.size() > 1 ? a.get(1) : 0;
            double z = a.size() > 2 ? a.get(2) : 0;
            return switch (name) {
                case "abs" -> Math.abs(x);
                case "sin" -> Math.sin(x);
                case "cos" -> Math.cos(x);
                case "tan" -> Math.tan(x);
                case "atan" -> a.size() > 1 ? Math.atan2(x, y) : Math.atan(x);
                case "sqrt" -> Math.sqrt(x);
                case "exp" -> Math.exp(x);
                case "log" -> Math.log(x);
                case "floor" -> Math.floor(x);
                case "ceil" -> Math.ceil(x);
                case "sign" -> Math.signum(x);
                case "frac", "fract" -> x - Math.floor(x);
                case "min" -> Math.min(x, y);
                case "max" -> Math.max(x, y);
                case "pow" -> Math.pow(x, y);
                case "mod" -> x % y;
                case "step" -> x <= y ? 1 : 0;
                case "clamp" -> Math.min(Math.max(x, y), z);
                case "mix" -> x * (1 - z) + y * z;
                case "smoothstep" -> {
                    double t = Math.min(Math.max((z - x) / (y - x), 0), 1);
                    yield t * t * (3 - 2 * t);
                }
                case "if" -> x != 0 ? y : z;
                case "in" -> {
                    for (int i = 1; i < a.size(); i++) {
                        if (a.get(i) == x) yield 1;
                    }
                    yield 0;
                }
                case "smooth" -> {
                    if (smooth == null) throw new Unsupported("smooth");
                    double w = a.size() > 3 ? a.get(3) : 0;
                    yield smooth.apply((int) x, y, z, w);
                }
                default -> throw new Unsupported(name);
            };
        }
    }
}