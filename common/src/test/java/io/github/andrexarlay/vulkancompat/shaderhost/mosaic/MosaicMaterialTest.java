package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MosaicMaterialTest {

    static MosaicMaterial.Image grey(int size) {
        int[] px = new int[size * size];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | ((i + i / size) % 2 == 0 ? 0x808080 : 0x606060);
        return new MosaicMaterial.Image(size, size, px);
    }

    @Test
    void starterGraphBakesLabPbr() {
        MosaicGraph.Analysis a = MosaicGraph.analyze(MosaicMaterial.starter(0.25, 1));
        assertNull(a.firstError(), String.valueOf(a.problems()));
        assertTrue(a.graph().isMaterial());
        MosaicMaterial.Baked b = MosaicMaterial.bake(a.graph(), grey(16), Map.of());
        assertNull(b.albedo(), "colours pass through unchanged");
        int s = b.specular()[0];
        assertEquals(Math.round(0.75 * 255), (s >> 16) & 255, "smoothness = 1 - roughness");
        assertEquals(255, (s >> 8) & 255, "metal");
        assertEquals(255, s >>> 24, "no emission");
        assertNotNull(b.normal(), "height is wired: a normal map is written");
        assertEquals(255, b.normal()[0] & 255, "AO defaults to 1");
        // a brightness ramp from left to right makes the normal lean
        int[] ramp = new int[16 * 16];
        for (int i = 0; i < ramp.length; i++) ramp[i] = 0xFF000000 | (i % 16 * 12) * 0x010101;
        MosaicMaterial.Baked r = MosaicMaterial.bake(a.graph(), new MosaicMaterial.Image(16, 16, ramp), Map.of());
        assertTrue(((r.normal()[16 * 5 + 7] >> 16) & 255) < 120, "brighter to the right = higher: the normal tilts left");
    }

    @Test
    void strongColourChangeWarns() {
        String json = "{\"nodes\": [{\"id\": \"red\", \"type\": \"mosaic.const\", \"value\": [1, 0, 0, 1]},"
            + "{\"id\": \"b\", \"type\": \"mat.bsdf\", \"inputs\": {\"baseColor\": \"red\"}},"
            + "{\"id\": \"o\", \"type\": \"mat.output\", \"inputs\": {\"surface\": \"b\"}}]}";
        MosaicGraph.Analysis a = MosaicGraph.analyze(json);
        assertNull(a.firstError(), String.valueOf(a.problems()));
        MosaicMaterial.Baked b = MosaicMaterial.bake(a.graph(), grey(16), Map.of());
        assertNotNull(b.albedo());
        assertEquals(0xFFFF0000, b.albedo()[5]);
        assertNull(b.normal(), "no height, no AO: the game keeps its automatic normals");
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("differ strongly")), String.valueOf(b.warnings()));
    }

    @Test
    void proceduralNoiseTilesAcrossTheTexture() {
        for (int period : new int[] {1, 3, 5})
            for (double y = 0.05; y < 1; y += 0.17) {
                double a = MosaicMaterial.Procedural.perlin(0.13, y * period, 0, period);
                double b = MosaicMaterial.Procedural.perlin(0.13 + period, y * period, 0, period);
                assertEquals(a, b, 1e-9, "noise repeats after its period");
            }
        String json = "{\"nodes\": [{\"id\": \"n\", \"type\": \"mosaic.noise\", \"defaults\": {\"scale\": [64]}},"
            + "{\"id\": \"b\", \"type\": \"mat.bsdf\", \"inputs\": {\"roughness\": \"n\"}},"
            + "{\"id\": \"o\", \"type\": \"mat.output\", \"inputs\": {\"surface\": \"b\"}}]}";
        MosaicMaterial.Baked b = MosaicMaterial.bake(MosaicGraph.analyze(json).graph(), grey(16), Map.of());
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("pixel noise")), String.valueOf(b.warnings()));
    }

    @Test
    void sceneNodesAreRefusedInMaterialsAndMaterialNodesInShaders() {
        MosaicGraph.Analysis m = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"c\", \"type\": \"mosaic.sceneColor\"},"
            + "{\"id\": \"b\", \"type\": \"mat.bsdf\", \"inputs\": {\"baseColor\": \"c\"}},"
            + "{\"id\": \"o\", \"type\": \"mat.output\", \"inputs\": {\"surface\": \"b\"}}]}");
        assertTrue(m.problems().stream().anyMatch(p -> p.node() != null && p.node().equals("c") && p.severity() == MosaicGraph.Severity.ERROR));
        MosaicGraph.Analysis s = MosaicGraph.analyze("{\"nodes\": [{\"id\": \"t\", \"type\": \"mat.texture\"},"
            + "{\"id\": \"o\", \"type\": \"mosaic.output\", \"inputs\": {\"color\": \"t\"}}]}");
        assertTrue(s.problems().stream().anyMatch(p -> "t".equals(p.node()) && p.severity() == MosaicGraph.Severity.ERROR));
    }
}
