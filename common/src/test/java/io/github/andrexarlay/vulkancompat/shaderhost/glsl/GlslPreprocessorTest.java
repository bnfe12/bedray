package io.github.andrexarlay.vulkancompat.shaderhost.glsl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlslPreprocessorTest {

    @Test
    void stageGuardsSelectBranch() {
        String src = String.join("\n",
            "#ifdef VSH",
            "attribute vec4 mc_Entity;",
            "#endif",
            "#ifdef FSH",
            "uniform float fogFactor;",
            "#endif");
        String vsh = GlslPreprocessor.process(src, Map.of("VSH", ""));
        assertTrue(vsh.contains("mc_Entity"));
        assertFalse(vsh.contains("fogFactor"));
        String fsh = GlslPreprocessor.process(src, Map.of("FSH", ""));
        assertFalse(fsh.contains("mc_Entity"));
        assertTrue(fsh.contains("fogFactor"));
    }

    @Test
    void ifDefinedLogicalAndNumericComparison() {
        String src = String.join("\n",
            "#if defined MULTICOLORED_BLOCKLIGHT || defined MCBL_SS",
            "uniform int heldItemId;",
            "#endif",
            "#if MC_VERSION < 12111",
            "oldVersion();",
            "#else",
            "newVersion();",
            "#endif");
        String out = GlslPreprocessor.process(src, Map.of("MCBL_SS", "", "MC_VERSION", "260300"));
        assertTrue(out.contains("heldItemId"));
        assertFalse(out.contains("oldVersion"));
        assertTrue(out.contains("newVersion"));
    }

    @Test
    void nestedIfElseAndObjectMacro() {
        String src = String.join("\n",
            "#define SPEED 2.0",
            "#ifdef WORLD_TIME_ANIMATION",
            "float t = worldTime * SPEED;",
            "#else",
            "float t = frameTimeCounter * SPEED;",
            "#endif");
        String out = GlslPreprocessor.process(src, Map.of());
        assertTrue(out.contains("frameTimeCounter * 2.0"));
        assertFalse(out.contains("worldTime"));
    }

    @Test
    void functionLikeMacro() {
        String src = "#define TWICE(x) ((x) * 2)\nfloat a = TWICE(3.0);";
        String out = GlslPreprocessor.process(src, Map.of());
        assertTrue(out.contains("((3.0) * 2)"));
    }

    @Test
    void preservesDrawbuffersInActiveBranchOnly() {
        String src = String.join("\n",
            "#ifdef FSH",
            "/* DRAWBUFFERS:0 */",
            "gl_FragData[0] = vec4(1.0);",
            "#endif",
            "#ifdef VSH",
            "/* DRAWBUFFERS:08 */",
            "#endif");
        String out = GlslPreprocessor.process(src, Map.of("FSH", ""));
        assertTrue(out.contains("DRAWBUFFERS:0"));
        assertFalse(out.contains("DRAWBUFFERS:08"));
    }

    @Test
    void versionAndLinePassThrough() {
        String src = "#version 120\n#line 1 \"foo\"\nvoid main() {}";
        String out = GlslPreprocessor.process(src, Map.of());
        assertTrue(out.contains("#version 120"));
        assertTrue(out.contains("#line 1 \"foo\""));
    }
}