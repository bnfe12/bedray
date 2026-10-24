package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source check: the byte layout Java writes into the per-frame StatsBuf slot must equal the layout the
 * shaders read through pc.statsAddr (buffer StatsBuf in rt_common.glsl). The GLSL declaration is parsed
 * here with the std430 rules the compiler applies; a one-sided edit of either side fails this test
 * instead of silently misreading the frame slot (the magic-offset bug class of the 2026-10 audit).
 * The PC push-constant block and the Reservoir/Emitter record strides are checked the same way.
 */
public class RtStatsLayoutTest {

    private record G(String name, String type, int offset, int size) {}

    /** Parses the members of a GLSL block (declarations {@code TYPE name;}, comments stripped) with std430 rules. */
    private static List<G> parse(String glsl, String header) {
        Matcher m = Pattern.compile(Pattern.quote(header) + " \\{([^}]*)\\}").matcher(glsl);
        assertTrue(m.find(), header + " not found in rt_common.glsl");
        List<G> out = new ArrayList<>();
        int cursor = 0;
        for (String raw : m.group(1).split("\\n")) {
            String line = raw.replaceAll("//.*", "").trim();
            if (line.isEmpty() || line.startsWith("layout")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            assertEquals(2, parts.length, "unexpected member declaration: " + line);
            String type = parts[0];
            String name = parts[1].replace(";", "");
            int align;
            int size;
            switch (type) {
                case "float", "uint", "int" -> { align = 4; size = 4; }
                case "vec2", "uvec2", "ivec2" -> { align = 8; size = 8; }
                case "vec3", "uvec3", "ivec3" -> { align = 16; size = 12; }
                case "vec4", "uvec4", "ivec4" -> { align = 16; size = 16; }
                case "mat4" -> { align = 16; size = 64; }
                default -> throw new AssertionError("unsupported member type in " + header + ": " + type);
            }
            cursor = (cursor + align - 1) & -align;
            out.add(new G(name, type, cursor, size));
            cursor += size;
        }
        assertFalse(out.isEmpty(), header + " parsed no members");
        return out;
    }

    @Test
    public void statsBufMatchesGlsl() {
        String glsl = RtShaderCompiler.loadSource("/shaders/rt_common_core.glsl");
        List<G> gl = parse(glsl, "buffer StatsBuf");
        List<RtStatsLayout.Member> j = RtStatsLayout.members();
        assertEquals(gl.size(), j.size(), "StatsBuf member count");
        for (int i = 0; i < gl.size(); i++) {
            G g = gl.get(i);
            RtStatsLayout.Member m = j.get(i);
            assertEquals(g.name(), m.name(), "StatsBuf member " + i + " name");
            assertEquals(g.type(), m.glslType(), "StatsBuf." + g.name() + " type");
            assertEquals(g.offset(), m.offset(), "StatsBuf." + g.name() + " offset");
        }
        G last = gl.get(gl.size() - 1);
        int size = ((last.offset() + last.size()) + 15) & -16;
        assertEquals(size, RtStatsLayout.SIZE, "StatsBuf std430 size");
        assertTrue(RtStatsLayout.SIZE <= RtStatsLayout.SLOT_STRIDE, "StatsBuf must fit the frame slot stride");
    }

    @Test
    public void pushConstantBlockMatchesRtFrameParams() {
        String glsl = RtShaderCompiler.loadSource("/shaders/rt_common_core.glsl");
        List<G> gl = parse(glsl, "uniform PC");
        assertEquals(RtFrameParams.PC_TYPES.length, gl.size(), "PC member count");
        int[] offsets = RtStatsLayout.std430Offsets(RtFrameParams.PC_TYPES);
        for (int i = 0; i < gl.size(); i++) {
            assertEquals(gl.get(i).type(), RtFrameParams.PC_TYPES[i], "PC member " + i + " type");
            assertEquals(gl.get(i).offset(), offsets[i], "PC member " + gl.get(i).name() + " offset");
        }
        G last = gl.get(gl.size() - 1);
        assertTrue(last.offset() + last.size() <= RtFrameParams.BYTES, "PC block must fit maxPushConstantsSize");
    }

    @Test
    public void reservoirStrideMatchesGlsl() {
        String glsl = RtShaderCompiler.loadSource("/shaders/rt_common_core.glsl");
        List<G> gl = parse(glsl, "struct Reservoir");
        int size = 0;
        for (G g : gl) {
            size += g.size();
        }
        assertEquals(size, RtFrameRenderer.RESERVOIR_BYTES, "ReSTIR reservoir record stride");
    }

    @Test
    public void emitterStrideMatchesGlsl() {
        String glsl = RtShaderCompiler.loadSource("/shaders/rt_common_core.glsl");
        List<G> gl = parse(glsl, "struct Emitter");
        int size = 0;
        for (G g : gl) {
            size += g.size();
        }
        assertEquals(size, RtEmitters.BYTES, "Emitter record stride");
        assertEquals(size / 4, RtEmitters.FLOATS, "Emitter record in floats");
    }
}
