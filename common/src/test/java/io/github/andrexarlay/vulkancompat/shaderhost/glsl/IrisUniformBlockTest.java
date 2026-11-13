package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisUniformBlockTest {

    @Test
    void std140OffsetsAndSize() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("a", "float");
        types.put("b", "vec3");
        types.put("c", "float");
        types.put("m", "mat4");
        IrisUniformBlock.Layout l = IrisUniformBlock.layout(types);
        assertEquals(0, l.offset("a"));
        assertEquals(16, l.offset("b"));
        assertEquals(32, l.offset("c"));
        assertEquals(48, l.offset("m"));
        assertEquals(112, l.bytes());
    }

    @Test
    void vec2AlignsTo8() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("f", "float");
        types.put("v", "vec2");
        IrisUniformBlock.Layout l = IrisUniformBlock.layout(types);
        assertEquals(0, l.offset("f"));
        assertEquals(8, l.offset("v"));
        assertEquals(16, l.bytes());
    }

    @Test
    void writePutsValuesAtOffsets() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("a", "float");
        types.put("b", "vec3");
        IrisUniformBlock.Layout l = IrisUniformBlock.layout(types);
        ByteBuffer buf = ByteBuffer.allocate(l.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, float[]> values = Map.of("a", new float[] {1.5f}, "b", new float[] {1, 2, 3});
        IrisUniformBlock.write(buf, l, values);
        assertEquals(1.5f, buf.getFloat(0));
        assertEquals(1f, buf.getFloat(16));
        assertEquals(2f, buf.getFloat(20));
        assertEquals(3f, buf.getFloat(24));
    }

    @Test
    void exprEvaluatorClampAbs() {
        double v = IrisUniformBlock.Expr.eval("clamp(1.0 - abs(sunAngle - 0.5), 0.0, 1.0)", Map.of("sunAngle", 0.5));
        assertEquals(1.0, v, 1e-9);
        double v2 = IrisUniformBlock.Expr.eval("clamp(1.0 - abs(sunAngle - 0.5), 0.0, 1.0)", Map.of("sunAngle", 0.0));
        assertEquals(0.5, v2, 1e-9);
    }

    @Test
    void exprEvaluatorArithmeticAndFunctions() {
        assertEquals(7.0, IrisUniformBlock.Expr.eval("1 + 2 * 3", Map.of()), 1e-9);
        assertEquals(0.0, IrisUniformBlock.Expr.eval("sin(0)", Map.of()), 1e-9);
        assertEquals(2.0, IrisUniformBlock.Expr.eval("max(1, 2)", Map.of()), 1e-9);
        assertEquals(-1.0, IrisUniformBlock.Expr.eval("-(3 - 2)", Map.of()), 1e-9);
    }

    @Test
    void unsupportedFunctionNamedNotSilent() {
        IrisUniformBlock.Expr.Unsupported e = assertThrows(IrisUniformBlock.Expr.Unsupported.class,
            () -> IrisUniformBlock.Expr.eval("texture(noise, uv)", Map.of()));
        assertTrue(e.getMessage().contains("texture"));
    }
}