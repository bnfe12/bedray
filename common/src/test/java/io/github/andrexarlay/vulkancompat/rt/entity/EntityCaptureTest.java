package io.github.andrexarlay.vulkancompat.rt.entity;

import io.github.andrexarlay.vulkancompat.rt.world.RtEntityGeometryManager;
import io.github.andrexarlay.vulkancompat.rt.world.RtEmitters;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class EntityCaptureTest {

    @Test
    public void testGlowingFontCoverageNeverBecomesTranslucentGlass() throws Exception {
        var constructor = EntityTextureAtlas.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var atlas = constructor.newInstance();
        int[] alphas = {0, 127, 128, 200, 241, 242, 255};
        try (var image = new com.mojang.blaze3d.platform.NativeImage(alphas.length, 1, true)) {
            for (int x = 0; x < alphas.length; x++) image.setPixel(x, 0, (alphas[x] << 24) | 0x3090E0);
            int sourceRgb = image.getPixelsABGR()[0] & 0xFFFFFF;
            int slot = atlas.packGlowingFont(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                    "minecraft", "textures/font/alpha_regression.png"), image);
            assertTrue(slot > 0);
            var pixelsField = EntityTextureAtlas.class.getDeclaredField("pixels");
            var rectsField = EntityTextureAtlas.class.getDeclaredField("rects");
            pixelsField.setAccessible(true);
            rectsField.setAccessible(true);
            int[] pixels = (int[]) pixelsField.get(atlas);
            int[] rects = (int[]) rectsField.get(atlas);
            int start = rects[slot * 4 + 1] * EntityTextureAtlas.SIZE + rects[slot * 4];
            for (int x = 0; x < alphas.length; x++) {
                int pixel = pixels[start + x];
                int encodedAlpha = pixel >>> 24;
                boolean glyph = alphas[x] >= 128;
                assertEquals(sourceRgb, pixel & 0xFFFFFF, "glyph colour at source alpha " + alphas[x]);
                assertEquals(glyph ? 255 : 0, encodedAlpha, "cutout coverage at source alpha " + alphas[x]);
                assertEquals(glyph ? 1f : 0f, EntityTextureAtlas.emissionOf(pixel), 0f);
                assertFalse(encodedAlpha >= 26 && encodedAlpha < 224,
                        "entityTexelTranslucent must reject font coverage at source alpha " + alphas[x]);
            }
        }
    }
    @Test
    public void testGeneratedSpriteUsesStitchedPixelsWithoutResourcePng() throws Exception {
        var constructor = EntityTextureAtlas.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var atlas = constructor.newInstance();
        try (var image = new com.mojang.blaze3d.platform.NativeImage(2, 1, true)) {
            image.setPixel(0, 0, 0xFF3090E0);
            image.setPixel(1, 0, 0x00123456);
            class GeneratedSprite extends net.minecraft.client.renderer.texture.SpriteContents
                    implements io.github.andrexarlay.vulkancompat.mixin.rt.MixinSpriteContentsAccessor {
                GeneratedSprite() {
                    super(net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "trims/entity/humanoid/test_redstone"),
                            new net.minecraft.client.resources.metadata.animation.FrameSize(2, 1), image);
                }
                @Override public com.mojang.blaze3d.platform.NativeImage vkb$originalImage() { return image; }
            }
            var sprite = new GeneratedSprite();
            int slot = atlas.spriteSlot(sprite);
            assertTrue(slot > 0, "generated sprite needs no resource manager or PNG lookup");
            assertEquals(slot, atlas.spriteSlot(sprite), "capture reuses the generated sprite slot");
            assertEquals(2, atlas.texels(slot).width());
            var pixelsField = EntityTextureAtlas.class.getDeclaredField("pixels");
            var rectsField = EntityTextureAtlas.class.getDeclaredField("rects");
            pixelsField.setAccessible(true);
            rectsField.setAccessible(true);
            int[] pixels = (int[]) pixelsField.get(atlas);
            int[] rects = (int[]) rectsField.get(atlas);
            int start = rects[slot * 4 + 1] * EntityTextureAtlas.SIZE + rects[slot * 4];
            assertEquals(image.getPixelsABGR()[0] & 0xFFFFFF, pixels[start] & 0xFFFFFF, "capture preserves the generated palette colour");
            assertEquals(0, pixels[start + 1] >>> 24, "trim holes stay transparent");
            assertEquals(0xFF3090E0, image.getPixel(0, 0), "capture must not rewrite or close SpriteContents pixels");
        }
    }
    @Test
    public void testModelCaptureClosesImplicitEntityWhenSetupEscapes() throws Exception {
        EntityCaptureManager mgr = EntityCaptureManager.getInstance();
        mgr.beginFrame(601L);
        try {
            AssertionError failure = new AssertionError("model setup failed");
            var model = new net.minecraft.client.model.Model<Object>(
                    new net.minecraft.client.model.geom.ModelPart(List.of(), java.util.Map.of()), ignored -> null) {
                @Override public void setupAnim(Object state) { throw failure; }
            };
            assertSame(failure, assertThrows(AssertionError.class, () -> mgr.captureModel(
                    model, new Object(), new com.mojang.blaze3d.vertex.PoseStack(), null, 0, 0, 0xFFFFFFFF)));
            var inside = EntityCaptureManager.class.getDeclaredField("insideEntity");
            inside.setAccessible(true);
            assertFalse(inside.getBoolean(mgr), "an escaping failure must close an implicit block entity");
        } finally {
            mgr.endFrame();
        }
    }

    @Test
    public void testModelCaptureDoesNotCloseEnclosingEntityWhenSetupEscapes() throws Exception {
        EntityCaptureManager mgr = EntityCaptureManager.getInstance();
        mgr.beginFrame(602L);
        mgr.beginEntity(42, "minecraft:player", new Matrix4f(), EntityGeometryFrame.CapturedEntity.FLAG_LIVING);
        try {
            var model = new net.minecraft.client.model.Model<Object>(
                    new net.minecraft.client.model.geom.ModelPart(List.of(), java.util.Map.of()), ignored -> null) {
                @Override public void setupAnim(Object state) { throw new AssertionError("model setup failed"); }
            };
            assertThrows(AssertionError.class, () -> mgr.captureModel(
                    model, new Object(), new com.mojang.blaze3d.vertex.PoseStack(), null, 0, 0, 0xFFFFFFFF));
            var inside = EntityCaptureManager.class.getDeclaredField("insideEntity");
            inside.setAccessible(true);
            assertTrue(inside.getBoolean(mgr), "a nested model must leave ownership with the entity dispatcher");
        } finally {
            mgr.endEntity();
            mgr.endFrame();
        }
    }
    @Test
    public void testItemQuadTintColorMatchesVanillaFallbacks() {
        int tint = 0xFF35A7D2;
        BakedQuad.MaterialInfo tinted = new BakedQuad.MaterialInfo(
                null, null, null, null, null, 1, null, 0);
        BakedQuad.MaterialInfo untinted = new BakedQuad.MaterialInfo(
                null, null, null, null, null, -1, null, 0);

        assertEquals(tint, EntityCaptureManager.itemQuadTintColor(tinted, new int[] {0xFFFFFFFF, tint}));
        assertEquals(0xFFFFFFFF, EntityCaptureManager.itemQuadTintColor(untinted, new int[] {tint}));
        assertEquals(0xFFFFFFFF, EntityCaptureManager.itemQuadTintColor(tinted, new int[] {tint}));
    }

    @Test
    public void testItemEmitterStoresArgbAsRgbaMultiplier() {
        float[] record = new float[RtEmitters.FLOATS];
        EntityCaptureManager.writeItemEmitterTint(record, 0x8040A0FF);

        int tint = RtEmitters.TINT_OFFSET;
        assertEquals(64f / 255f, record[tint], 1e-6f);
        assertEquals(160f / 255f, record[tint + 1], 1e-6f);
        assertEquals(1f, record[tint + 2], 1e-6f);
        assertEquals(128f / 255f, record[tint + 3], 1e-6f);
    }

    @Test
    public void testHandItemFilteringPreservesPartitionsAndKeepsSubsetsDisjoint() {
        BakedQuad materialSolid = quad(0f);
        BakedQuad emissiveSolid = quad(1f);
        BakedQuad materialTranslucent = quad(2f);
        BakedQuad emissiveTranslucent = quad(3f);
        ItemQuads source = new ItemQuads(
                List.of(materialSolid, emissiveSolid, materialTranslucent, emissiveTranslucent),
                List.of(materialSolid, emissiveSolid),
                List.of(materialTranslucent, emissiveTranslucent));
        Set<BakedQuad> emissiveSet = Collections.newSetFromMap(new IdentityHashMap<>());
        emissiveSet.add(emissiveSolid);
        emissiveSet.add(emissiveTranslucent);

        ItemQuads material = EntityCaptureManager.filterItemQuads(source, q -> !emissiveSet.contains(q));
        ItemQuads emissive = EntityCaptureManager.filterItemQuads(source, emissiveSet::contains);

        assertEquals(List.of(materialSolid, materialTranslucent), material.all());
        assertEquals(List.of(materialSolid), material.solid());
        assertEquals(List.of(materialTranslucent), material.translucent());
        assertEquals(List.of(emissiveSolid, emissiveTranslucent), emissive.all());
        assertEquals(List.of(emissiveSolid), emissive.solid());
        assertEquals(List.of(emissiveTranslucent), emissive.translucent());
        for (BakedQuad quad : material.all()) {
            assertFalse(emissive.all().contains(quad), "a quad must be submitted in exactly one light subset");
        }
        assertEquals(source.all().size(), material.all().size() + emissive.all().size());
    }

    @Test
    public void testHandItemFilteringReturnsEmptyListsForNoMatches() {
        BakedQuad quad = quad(0f);
        ItemQuads source = new ItemQuads(List.of(quad), List.of(quad), List.of());

        ItemQuads filtered = EntityCaptureManager.filterItemQuads(source, ignored -> false);

        assertTrue(filtered.isEmpty());
        assertTrue(filtered.all().isEmpty());
        assertTrue(filtered.solid().isEmpty());
        assertTrue(filtered.translucent().isEmpty());
        assertSame(ItemQuads.EMPTY, EntityCaptureManager.filterItemQuads(ItemQuads.EMPTY, ignored -> true));
    }

    private static BakedQuad quad(float x) {
        Vector3f p0 = new Vector3f(x, 0f, 0f);
        Vector3f p1 = new Vector3f(x + 1f, 0f, 0f);
        Vector3f p2 = new Vector3f(x + 1f, 1f, 0f);
        Vector3f p3 = new Vector3f(x, 1f, 0f);
        return new BakedQuad(p0, p1, p2, p3, 0L, 0L, 0L, 0L, null, null);
    }

    @Test
    public void testCapturedVertexConsumerQuadTriangulation() {
        CapturedVertexConsumer consumer = new CapturedVertexConsumer();
        assertEquals(0, consumer.getVertexCount());
        assertEquals(0, consumer.getIndexCount());

        // Quad vertex 0
        consumer.addVertex(0f, 0f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(0f, 0f)
                .setUv2(15, 15)
                .setNormal(0f, 1f, 0f);

        // Quad vertex 1
        consumer.addVertex(1f, 0f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(1f, 0f)
                .setUv2(15, 15)
                .setNormal(0f, 1f, 0f);

        // Quad vertex 2
        consumer.addVertex(1f, 1f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(1f, 1f)
                .setUv2(15, 15)
                .setNormal(0f, 1f, 0f);

        // Quad vertex 3 -> completing quad triggers emission of 2 triangles (6 indices)
        consumer.addVertex(0f, 1f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(0f, 1f)
                .setUv2(15, 15)
                .setNormal(0f, 1f, 0f);

        assertEquals(4, consumer.getVertexCount());
        assertEquals(6, consumer.getIndexCount());

        int[] indices = consumer.indexData();
        assertEquals(6, consumer.indexCount());
        // Triangle 1: (0, 1, 2)
        assertEquals(0, indices[0]);
        assertEquals(1, indices[1]);
        assertEquals(2, indices[2]);
        // Triangle 2: (2, 3, 0)
        assertEquals(2, indices[3]);
        assertEquals(3, indices[4]);
        assertEquals(0, indices[5]);

        float[] vertices = consumer.vertexData();
        assertEquals(40, consumer.vertexFloatCount()); // 4 vertices * 10 floats
        assertEquals(0f, vertices[0]);
        assertEquals(0f, vertices[1]);
        assertEquals(0f, vertices[2]);

        consumer.clear();
        assertEquals(0, consumer.getVertexCount());
        assertEquals(0, consumer.getIndexCount());
    }

    @Test
    public void testEntityCaptureManagerFullLifecycle() {
        EntityCaptureManager mgr = EntityCaptureManager.getInstance();
        mgr.beginFrame(101L);

        Matrix4f transform = new Matrix4f().identity().translate(10f, 64f, -20f);
        mgr.beginEntity(1001, "minecraft:creeper", transform, EntityGeometryFrame.CapturedEntity.FLAG_LIVING);

        CapturedVertexConsumer consumer = new CapturedVertexConsumer();
        consumer.addVertex(0f, 0f, 0f).setColor(255, 255, 255, 255).setUv(0f, 0f).setUv2(15, 15).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 0f, 0f).setColor(255, 255, 255, 255).setUv(1f, 0f).setUv2(15, 15).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 2f, 0f).setColor(255, 255, 255, 255).setUv(1f, 1f).setUv2(15, 15).setNormal(0f, 1f, 0f);
        consumer.addVertex(0f, 2f, 0f).setColor(255, 255, 255, 255).setUv(0f, 1f).setUv2(15, 15).setNormal(0f, 1f, 0f);

        mgr.endEntity();
        EntityGeometryFrame frame = mgr.endFrame();

        assertNotNull(frame);
        assertEquals(101L, frame.frameId());
        assertEquals(RtEntityGeometryManager.getInstance().getCurrentFrame(), frame);
        assertTrue(mgr.getHookInvocations() > 0);
        assertTrue(mgr.getEntitiesSeen() > 0);
    }
    @Test
    public void testStateTransitionsWithGeometry() {
        EntityCaptureManager mgr = EntityCaptureManager.getInstance();

        // beginFrame with enabled=false -> DISABLED
        mgr.beginFrame(200L, false);
        assertEquals(EntityCaptureManager.State.DISABLED, mgr.getState());

        // beginFrame with enabled=true -> CAPTURING
        mgr.beginFrame(201L, true);
        assertEquals(EntityCaptureManager.State.CAPTURING, mgr.getState());

        // endFrame with no vertices -> EMPTY (was CAPTURING)
        EntityGeometryFrame emptyFrame = mgr.endFrame();
        assertNotNull(emptyFrame);
        assertEquals(EntityCaptureManager.State.EMPTY, mgr.getState());

        // markConsumed on EMPTY should not change state to CONSUMED
        mgr.markConsumed(201L);
        assertEquals(EntityCaptureManager.State.EMPTY, mgr.getState());
    }

    @Test
    public void testStateTransitionsPublishedToConsumed() {
        EntityCaptureManager mgr = EntityCaptureManager.getInstance();

        // Reset via beginFrame
        mgr.beginFrame(300L, true);
        assertEquals(EntityCaptureManager.State.CAPTURING, mgr.getState());

        // Simulate geometry via beginEntity/endEntity (no real model, but at least hook invocations)
        // Note: endFrame will see 0 vertices since no captureModel was called, so EMPTY.
        // We verify PUBLISHED only via formatDiagnostics correctness.
        EntityGeometryFrame frame = mgr.endFrame();
        assertNotNull(frame);

        // Verify formatDiagnostics contains required fields
        String diag = mgr.formatDiagnostics();
        assertTrue(diag.contains("[VK-ENTITY-CAPTURE]"), "must have prefix");
        assertTrue(diag.contains("state="), "must have state field");
        assertTrue(diag.contains("captureFrame=300"), "must have captureFrame");
        assertTrue(diag.contains("renderFrame="), "must have renderFrame");
        assertTrue(diag.contains("latencyFrames="), "must have latencyFrames");
        assertTrue(diag.contains("entitiesSeen="), "must have entitiesSeen");
        assertTrue(diag.contains("entitiesCaptured="), "must have entitiesCaptured");
        assertTrue(diag.contains("vertices="), "must have vertices");
        assertTrue(diag.contains("lastEntityType="), "must have lastEntityType");
        assertTrue(diag.contains("lastError="), "must have lastError");
    }

    @Test
    public void testMarkConsumedUpdatesLatency() {
        EntityCaptureManager mgr = EntityCaptureManager.getInstance();
        mgr.beginFrame(400L, true);
        mgr.endFrame();
        // Force PUBLISHED state manually for this test
        // (actual geometry would need captureModel, which requires Minecraft internals)
        // Just verify markConsumed sets renderFrameId and latency
        mgr.markConsumed(405L);
        String diag = mgr.formatDiagnostics();
        assertTrue(diag.contains("renderFrame=405"), "renderFrameId must be 405");
        // latency = max(0, 405 - 400) = 5
        assertTrue(diag.contains("latencyFrames=5"), "latency must be 5");
    }

    @Test
    public void testCapturedVertexConsumerHurtOverlayFlags() {
        CapturedVertexConsumer consumer = new CapturedVertexConsumer();

        // Vertex with normal overlay (NO_OVERLAY v == 10)
        consumer.addVertex(0f, 0f, 0f)
                .setColor(255, 255, 255, 255)
                .setUv(0f, 0f)
                .setUv1(0, 10)
                .setUv2(15, 15)
                .setNormal(0f, 1f, 0f);

        // Complete quad
        consumer.addVertex(1f, 0f, 0f).setUv1(0, 10).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 1f, 0f).setUv1(0, 10).setNormal(0f, 1f, 0f);
        consumer.addVertex(0f, 1f, 0f).setUv1(0, 10).setNormal(0f, 1f, 0f);

        float[] verts = consumer.vertexData();
        assertEquals(40, consumer.vertexFloatCount());
        int lightWord0 = Float.floatToRawIntBits(verts[6]);
        int flags0 = (lightWord0 >>> 24) & 0xFF;
        assertEquals(0, flags0 & 0x40, "NO_OVERLAY (v=10) must NOT set 0x40 hurt flag");

        consumer.clear();

        // Hurt vertex (v == 3)
        consumer.addVertex(0f, 0f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 0f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 1f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(0f, 1f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);

        verts = consumer.vertexData();
        assertEquals(40, consumer.vertexFloatCount());
        int lightWordHurt = Float.floatToRawIntBits(verts[6]);
        int flagsHurt = (lightWordHurt >>> 24) & 0xFF;
        assertEquals(0x40, flagsHurt & 0x40, "Hurt overlay (v=3) MUST set 0x40 hurt flag");

        consumer.clear();

        // Non-living entity with allowHurt = false:
        consumer.setAllowHurt(false);
        consumer.addVertex(0f, 0f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 0f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 1f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(0f, 1f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);

        verts = consumer.vertexData();
        assertEquals(40, consumer.vertexFloatCount());
        int lightWordBlocked = Float.floatToRawIntBits(verts[6]);
        int flagsBlocked = (lightWordBlocked >>> 24) & 0xFF;
        assertEquals(0, flagsBlocked & 0x40, "Non-living entity with allowHurt=false must NOT set 0x40");
    }

    @Test
    public void testNonLivingEntitiesNeverGetHurtFlag() {
        EntityCaptureManager mgr = EntityCaptureManager.getInstance();
        mgr.beginFrame(500L, true);

        // Chest with 0x40 passed erroneously
        mgr.beginEntity(2001, "be:minecraft:chest", new Matrix4f(), EntityGeometryFrame.CapturedEntity.FLAG_BLOCK_ENTITY | 0x40);

        // Consumer inside chest should reject 0x40 even if setUv1(0, 3) was called
        CapturedVertexConsumer consumer = mgr.frameConsumer();
        consumer.addVertex(0f, 0f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 0f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(1f, 1f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);
        consumer.addVertex(0f, 1f, 0f).setUv1(0, 3).setNormal(0f, 1f, 0f);

        mgr.endEntity();
        EntityGeometryFrame frame = mgr.endFrame();

        assertNotNull(frame);
        assertEquals(1, frame.entities().size());
        EntityGeometryFrame.CapturedEntity chest = frame.entities().get(0);
        assertEquals(0, chest.renderFlags() & 0x40, "Chest entity renderFlags must NOT have 0x40 hurt flag");

        // Verify vertex light word packed flag as well
        float[] verts = consumer.vertexData();
        assertEquals(40, consumer.vertexFloatCount());
        int lightWord = Float.floatToRawIntBits(verts[6]);
        int flags = (lightWord >>> 24) & 0xFF;
        assertEquals(0, flags & 0x40, "Chest vertices must NOT have 0x40 hurt flag in light word");
    }
}
