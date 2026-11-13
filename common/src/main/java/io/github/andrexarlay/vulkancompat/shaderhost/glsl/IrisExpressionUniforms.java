package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.IrisPackSettings;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates a pack's {@code uniform.*} / {@code variable.*} expressions (shaders.properties) into concrete values once per
 * frame, for the UBO tail the translator appends after the canonical {@link IrisUniforms#UBO_LAYOUT}. Resolves names against
 * the canonical per-frame context, other expressions (recursively, cycle-guarded), pack options, and a biome input.
 * <p>
 * Biome is not captured by the raster core, so {@code biome} defaults to plains (1) and {@code BIOME_*} constants resolve to
 * an unknown sentinel (-1); biome-tinted uniforms therefore render a plains approximation (logged once). {@code smooth(id,
 * target, up, down)} keeps per-id temporal state across frames. Truly unsupported expression functions throw
 * {@link IrisUniformBlock.Expr.Unsupported} by name.
 */
public final class IrisExpressionUniforms {

    private final IrisPackSettings settings;
    private final Map<String, String> catalog;
    private final Map<Integer, Double> smoothState = new LinkedHashMap<>();
    private boolean loggedBiome;

    public IrisExpressionUniforms(IrisPackSettings settings) {
        this.settings = settings;
        this.catalog = settings.expressionUniforms();
    }

    public Map<String, String> catalog() {
        return catalog;
    }

    public IrisUniformBlock.Layout layout() {
        return IrisUniformBlock.layout(catalog);
    }

    /** Evaluates the whole catalog once per frame; {@code context} carries canonical inputs (scalars and vectors). */
    public Map<String, float[]> evaluate(Map<String, double[]> context, double frameTime) {
        Map<String, double[]> ctx = new LinkedHashMap<>(context);
        ctx.putIfAbsent("biome", new double[] {1});
        Map<String, float[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : catalog.entrySet()) {
            Resolver r = new Resolver(ctx, frameTime, new HashSet<>());
            double v = r.evalName(e.getKey());
            out.put(e.getKey(), new float[] {(float) v});
        }
        return out;
    }

    private final class Resolver implements IrisUniformBlock.Expr.Resolver {
        private final Map<String, double[]> ctx;
        private final double frameTime;
        private final Set<String> visiting;

        Resolver(Map<String, double[]> ctx, double frameTime, Set<String> visiting) {
            this.ctx = ctx;
            this.frameTime = frameTime;
            this.visiting = visiting;
        }

        double evalName(String name) {
            double[] v = resolve(name);
            return v.length == 0 ? 0 : v[0];
        }

        @Override
        public double[] resolve(String name) {
            double[] direct = ctx.get(name);
            if (direct != null) {
                return direct;
            }
            if (name.startsWith("BIOME_")) {
                if (!loggedBiome) {
                    loggedBiome = true;
                    org.slf4j.LoggerFactory.getLogger("Bedray/IrisExpressionUniforms")
                        .warn("[VKB-IRIS] biome input not captured; biome-tinted uniforms use a plains approximation");
                }
                return new double[] {-1};
            }
            if (!visiting.add(name)) {
                return new double[] {0};
            }
            try {
                String expr = settings.uniformExpr(name);
                if (expr == null) {
                    expr = settings.variableExpr(name);
                }
                if (expr != null) {
                    double v = IrisUniformBlock.Expr.eval(expr, this, this::smooth);
                    return new double[] {v};
                }
                String opt = settings.options().get(name);
                if (opt != null) {
                    return new double[] {parseOption(opt)};
                }
                return new double[] {0};
            } finally {
                visiting.remove(name);
            }
        }

        private double smooth(int id, double target, double up, double down) {
            double cur = smoothState.getOrDefault(id, target);
            double time = target >= cur ? up : down;
            if (time > 0 && frameTime > 0) {
                cur += (target - cur) * Math.min(1.0, frameTime / time);
            } else {
                cur = target;
            }
            smoothState.put(id, cur);
            return cur;
        }
    }

    private static double parseOption(String opt) {
        if ("true".equalsIgnoreCase(opt)) return 1;
        if ("false".equalsIgnoreCase(opt)) return 0;
        try {
            return Double.parseDouble(opt.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}