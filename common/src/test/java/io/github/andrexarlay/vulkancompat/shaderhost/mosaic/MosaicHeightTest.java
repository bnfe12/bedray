package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** The far-shadow height field: sampling, following the camera, the buffer layout, and the shaders that read it. */
class MosaicHeightTest {

    @Test
    void fieldSamplesFollowsTheCameraAndKeepsTheOverlap() {
        MosaicHeight.Field f = new MosaicHeight.Field();
        MosaicHeight.Sampler hill = (x, z) -> x * x + z * z < 100 ? 90 : 64;
        f.update(hill, 0.5, 0.5, MosaicHeight.COLUMNS);
        assertEquals(90, f.get(0, 0));
        assertEquals(64, f.get(50, 50));
        assertEquals(Short.MIN_VALUE, f.get(500, 0), "outside the field");
        long v = f.version();
        assertFalse(f.update(hill, 0.5, 0.5, MosaicHeight.COLUMNS), "nothing changed");
        assertEquals(v, f.version());
        f.update((x, z) -> 0, 40.5, 0.5, 0); // a move past RECENTER, no new samples yet
        assertEquals(90, f.get(0, 0), "overlap kept after recentering");
        assertEquals(Short.MIN_VALUE, f.get(167, 0), "new margin is unknown until sampled");
    }

    @Test
    void headerAndColumnsMatchTheShaderLayout() {
        MosaicHeight.Field f = new MosaicHeight.Field();
        f.update((x, z) -> -10, 1000.25, -2000.75, MosaicHeight.COLUMNS);
        ByteBuffer b = ByteBuffer.allocate(MosaicHeight.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        f.writeHeader(b, 1000.25, 70.5, -2000.75);
        f.writeColumns(b, MosaicHeight.HEADER_BYTES);
        assertEquals(f.originX() - 1000, b.getInt(0));
        assertEquals(f.originZ() + 2001, b.getInt(4));
        assertEquals(70, b.getInt(8));
        assertEquals(1, b.getInt(12));
        assertEquals(0.25f, b.getFloat(16));
        assertEquals(-10, b.getShort(MosaicHeight.HEADER_BYTES));
    }

    @Test
    void sunShadowsReadTheHeightFieldInBothLanguages() {
        MosaicGraph g = MosaicGraph.parse(MosaicTemplates.all().stream()
            .filter(t -> t.name().equals("Sun shadows (no RT)")).findFirst().orElseThrow().json());
        MosaicCodegen.Generated glsl = MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, false);
        assertTrue(glsl.source().contains("binding = 18"), "BedrayHeight");
        MosaicShaderCheck.Result r = MosaicShaderCheck.compile(glsl);
        assertTrue(r.ok(), String.valueOf(r.errors()));
        java.nio.file.Path slangc = MosaicSlang.locate(System.getProperty("mosaic.slangc"));
        if (slangc != null) {
            MosaicShaderCheck.Result s = MosaicSlang.compile(MosaicCodegen.generate(g, MosaicCodegen.Options.DEFAULT, true), slangc);
            assertTrue(s.ok(), String.valueOf(s.errors()));
        }
    }
}
