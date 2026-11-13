package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import io.github.andrexarlay.vulkancompat.shaderhost.pack.IrisPackSettings;
import io.github.andrexarlay.vulkancompat.shaderhost.pack.PackProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Expression-uniform evaluation for real-pack (BSL-like) shaders.properties expressions. */
class IrisExpressionUniformsTest {

    private static IrisExpressionUniforms settings(String props) {
        return new IrisExpressionUniforms(IrisPackSettings.parse(PackProperties.parse(props), Set.of()));
    }

    @Test
    void exprSupportsComparisonsIfInFracAndSwizzle() {
        assertEquals(2.0, IrisUniformBlock.Expr.eval("if(1 > 0, 2, 3)", Map.of()), 1e-9);
        assertEquals(3.0, IrisUniformBlock.Expr.eval("if(1 < 0, 2, 3)", Map.of()), 1e-9);
        assertEquals(1.0, IrisUniformBlock.Expr.eval("in(2, 1, 2, 3)", Map.of()), 1e-9);
        assertEquals(0.0, IrisUniformBlock.Expr.eval("in(9, 1, 2, 3)", Map.of()), 1e-9);
        assertEquals(0.25, IrisUniformBlock.Expr.eval("frac(1.25)", Map.of()), 1e-9);
        assertEquals(2.0, IrisUniformBlock.Expr.eval("frameCounter % 8", Map.of("frameCounter", 10.0)), 1e-9);
        assertEquals(120.0, IrisUniformBlock.Expr.eval("cameraPosition.y",
            name -> name.equals("cameraPosition") ? new double[] {0, 120, 0} : new double[] {0}, null), 1e-9);
    }

    @Test
    void unsupportedFunctionStillNamed() {
        assertThrows(IrisUniformBlock.Expr.Unsupported.class,
            () -> IrisUniformBlock.Expr.eval("texture(noise, uv)", Map.of()));
    }

    @Test
    void bslLikeUniformsEvaluate() {
        String props = String.join("\n",
            "uniform.float.timeAngle=frac(sunAngle)",
            "uniform.float.framemod8=frameCounter % 8",
            "uniform.float.shadowFade=clamp(1.0 - abs(sunAngle - 0.5), 0.0, 1.0)",
            "variable.float.blindFactorSqrt=clamp(blindness * 2.0 - 1.0, 0.0, 1.0)",
            "uniform.float.blindFactor=blindFactorSqrt * blindFactorSqrt",
            "uniform.float.yCold=if(cameraPosition.y >= 113.0, 1, 0)",
            "uniform.float.isDesert=smooth(2, if(in(biome, BIOME_DESERT), 1, 0), 10, 10)");
        IrisExpressionUniforms u = settings(props);
        Map<String, double[]> ctx = Map.of(
            "sunAngle", new double[] {0.25},
            "frameCounter", new double[] {10},
            "blindness", new double[] {0.5},
            "cameraPosition", new double[] {0, 120, 0});
        Map<String, float[]> v = u.evaluate(ctx, 0.05);
        assertEquals(0.25f, v.get("timeAngle")[0], 1e-5);
        assertEquals(2f, v.get("framemod8")[0], 1e-5);
        assertEquals(0.75f, v.get("shadowFade")[0], 1e-5);
        assertEquals(0f, v.get("blindFactor")[0], 1e-5);
        assertEquals(1f, v.get("yCold")[0], 1e-5);
        assertEquals(0f, v.get("isDesert")[0], 1e-5);
    }

    @Test
    void catalogParsesTypesAndLayoutIsStable() {
        IrisExpressionUniforms u = settings("uniform.float.a=1\nuniform.float.b=2\nuniform.int.c=3");
        assertEquals(Map.of("a", "float", "b", "float", "c", "int"), u.catalog());
        IrisUniformBlock.Layout l = u.layout();
        assertEquals(0, l.offset("a"));
        assertEquals(4, l.offset("b"));
        assertEquals(8, l.offset("c"));
        assertEquals(16, l.bytes());
    }
}