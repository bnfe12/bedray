package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import io.github.andrexarlay.vulkancompat.shaderhost.mosaic.MosaicWorld.Material;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/** The world volume: incremental scanning, recentering that keeps data, the buffer layout and the codegen use. */
class MosaicWorldTest {

    /** A world with water below y = 60, stone below y = 50, torch light around x = 5. */
    static int sample(int x, int y, int z) {
        Material m = y < 50 ? Material.STONE : y < 60 ? Material.WATER : Material.AIR;
        return MosaicWorld.cell(m, Math.max(0, 15 - Math.abs(x - 5)), y >= 60 ? 15 : 0);
    }

    @Test
    void scanCompletesAndRecenterKeepsTheOverlap() {
        MosaicWorld.Volume v = new MosaicWorld.Volume();
        int frames = 0;
        while (!v.complete()) {
            v.update(MosaicWorldTest::sample, 0.5, 60.5, 0.5, 6144);
            frames++;
        }
        assertEquals((MosaicWorld.CELLS + 6143) / 6144, frames);
        assertEquals(Material.WATER, MosaicWorld.material(v.get(0, 55, 0)));
        assertEquals(15, MosaicWorld.skyLight(v.get(0, 61, 0)));
        assertEquals(15, MosaicWorld.blockLight(v.get(5, 61, 0)));
        long version = v.version();
        assertFalse(v.update(MosaicWorldTest::sample, 0.5, 60.5, 0.5, 6144), "a static world does not change");
        assertEquals(version, v.version());
        // walk 20 blocks east (past RECENTER): the volume moves, the old overlap is still there before any rescan
        v.update((x, y, z) -> 0, 20.5, 60.5, 0.5, 0);
        assertEquals(20 - MosaicWorld.SX / 2, v.originX());
        assertEquals(Material.WATER, MosaicWorld.material(v.get(0, 55, 0)));
        assertFalse(v.complete());
    }

    @Test
    void headerIsCameraRelative() {
        MosaicWorld.Volume v = new MosaicWorld.Volume();
        v.update(MosaicWorldTest::sample, 1000.25, 64.5, -2000.75, 10);
        ByteBuffer b = ByteBuffer.allocate(MosaicWorld.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        v.writeHeader(b, 1003.5, 64.5, -2000.75);
        assertEquals(1000 - MosaicWorld.SX / 2 - 1003, b.getInt(0));            // origin - floor(camera) after the camera moved
        assertEquals(1, b.getInt(12));
        assertEquals(0.5f, b.getFloat(16), 1e-6);
        assertEquals(0.25f, b.getFloat(24), 1e-6);               // -2000.75 - floor(-2000.75) = 0.25
        assertEquals(MosaicWorld.SX, b.getInt(32));
    }

    @Test
    void shadersWithoutRayNodesDeclareNoAccelerationStructure() {
        MosaicGraph voxel = MosaicGraph.parse(MosaicTemplates.all().stream()
            .filter(t -> t.name().equals("Voxel world")).findFirst().orElseThrow().json());
        String glsl = MosaicCodegen.glsl(voxel);
        assertFalse(glsl.contains("accelerationStructureEXT"), "voxel rendering must run without the TLAS");
        assertTrue(glsl.contains("binding = " + MosaicWorld.BINDING));
        for (MosaicTemplates.Template t : MosaicTemplates.all())
            assertFalse(MosaicGraph.analyze(t.json()).problems().stream()
                .anyMatch(p -> p.severity() == MosaicGraph.Severity.WARNING), t.name() + " ships a warning");
    }

    @Test
    void functionsSurviveTheDocumentHistoryAndClipboard() {
        MosaicDocument d = new MosaicDocument();
        MosaicDocument.ENode c = d.add("mosaic.sceneColor", 0, 0);
        d.ensureFunction(MosaicEffects.get("vignette"));
        MosaicDocument.ENode v = d.add("fn.vignette", 100, 0);
        v.inputs.put("color", c.id);
        MosaicDocument.ENode u = d.add("mosaic.uv", 0, 60);
        v.inputs.put("uv", u.id);
        MosaicDocument.ENode o = d.add("mosaic.output", 200, 0);
        o.inputs.put("color", v.id);
        assertNull(d.validate(), d.validate());
        String before = d.snapshot();
        d.functions.get(0).glsl = "return color * 2.0;";
        d.commit(before);
        d.undo();
        assertTrue(d.functions.get(0).glsl.contains("smoothstep"), "undo restores the code");
        // a broken function survives a round trip instead of disappearing
        d.functions.get(0).signature = "vec4 vignette(vec4 color, vec2 uv,";
        MosaicDocument re = new MosaicDocument();
        re.functions.addAll(MosaicDocument.readFunctions(d.toJson()));
        assertEquals("vec4 vignette(vec4 color, vec2 uv,", re.functions.get(0).signature);
        assertNotNull(d.validate());
        // clipboard carries the definition
        d.functions.get(0).signature = MosaicEffects.get("vignette").signature();
        String clip = MosaicDocument.fragmentJson(java.util.List.of(v), d.functions);
        MosaicDocument other = new MosaicDocument();
        other.mergeFunctions(clip);
        assertNotNull(other.function("vignette"));
    }
}
