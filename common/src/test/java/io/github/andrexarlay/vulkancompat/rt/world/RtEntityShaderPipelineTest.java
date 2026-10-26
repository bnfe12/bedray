package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.entity.RtEntityShaderDiagnostics;
import io.github.andrexarlay.vulkancompat.rt.entity.RtSceneInstances;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

public class RtEntityShaderPipelineTest {

    @Test
    public void testEntityCustomIndexDecoding() {
        int worldCustomIndex = 42;
        int entityCustomIndex = RtSceneInstances.encodeEntityCustomIndex(0);

        // Bit 23 determines entity hit
        assertFalse(RtSceneInstances.isEntityInstance(worldCustomIndex), "World slot must not be marked as entity");
        assertTrue(RtSceneInstances.isEntityInstance(entityCustomIndex), "Entity instance must have bit 23 set");
        assertEquals(0, RtSceneInstances.getRecordIndex(entityCustomIndex), "Decoded entity index must be 0");
    }

    @Test
    public void testShaderSourceContainsEntityPath() {
        String src = RtWorldChecks.rtCommon();

        assertTrue(src.contains("INSTANCE_TYPE_ENTITY = 0x800000u"), "rt_common.glsl must declare INSTANCE_TYPE_ENTITY");
        assertTrue(src.contains("bool isEntityHit(uint customIndex)"), "rt_common.glsl must declare isEntityHit");
        assertTrue(src.contains("void fetchEntitySurface("), "rt_common.glsl must declare fetchEntitySurface");
        assertTrue(src.contains("isEntityHit(customIndex)"), "traceSurface must branch on isEntityHit");
    }

    @Test
    public void testShaderCompilationWithEntityHitPath() {
        // Compile rt_rays.comp and rt_path.comp with Shaderc runtime compiler
        ByteBuffer spvRays = RtShaderCompiler.compileResource(
            RtPipeline.SHADER_PATH,
            "rt_rays.comp"
        );
        assertNotNull(spvRays, "rt_rays.comp SPIR-V must compile successfully");
        assertTrue(spvRays.remaining() > 1000, "SPIR-V bytecode must be non-empty");

        ByteBuffer spvPath = RtShaderCompiler.compileResource(
            RtPipeline.PATH_SHADER_PATH,
            "rt_path.comp",
            RtPathTracerPass.ACCUM_DEFINE
        );
        assertNotNull(spvPath, "rt_path.comp SPIR-V must compile successfully");
        assertTrue(spvPath.remaining() > 1000, "SPIR-V bytecode must be non-empty");
    }

    /**
     * Verifies that the StatsBuf entity byte offsets in RtEntityShaderDiagnostics match
     * the layout declared in rt_common.glsl (entityTris=+180, entPosAddr=+184, ...).
     */
    @Test
    public void testStatsBufEntityOffsets() {
        // These constants must match the StatsBuf layout in rt_common.glsl exactly.
        assertEquals(180, RtEntityShaderDiagnostics.OFFSET_ENTITY_TRIS,    "+180 entityTris");
        assertEquals(184, RtEntityShaderDiagnostics.OFFSET_ENT_POS_ADDR,   "+184 entPosAddr");
        assertEquals(192, RtEntityShaderDiagnostics.OFFSET_ENT_IDX_ADDR,   "+192 entIdxAddr");
        assertEquals(200, RtEntityShaderDiagnostics.OFFSET_ENT_ATTR_ADDR,  "+200 entAttrAddr");
        assertEquals(208, RtEntityShaderDiagnostics.OFFSET_ENT_PREV_ADDR,  "+208 entPrevAddr");
        assertEquals(216, RtEntityShaderDiagnostics.OFFSET_ENT_ATLAS_ADDR, "+216 entAtlasAddr");
        assertEquals(224, RtEntityShaderDiagnostics.OFFSET_ENT_RECT_ADDR,  "+224 entRectAddr");
        assertEquals(232, RtEntityShaderDiagnostics.OFFSET_MOTION_ADDR,    "+232 motionAddr");
        assertEquals(240, RtEntityShaderDiagnostics.OFFSET_ENT_ATLAS_SIZE, "+240 entAtlasSize");
        // StatsBuf must fit within 256-byte stride (BDA_STATS_256B)
        assertEquals(256, RtEntityShaderDiagnostics.STATS_BUF_STRIDE, "StatsBuf stride must be 256");
    }

    @Test
    public void testEntityShaderDiagnosticsStateMissing() {
        RtEntityShaderDiagnostics.reset();
        assertEquals(RtEntityShaderDiagnostics.State.MISSING, RtEntityShaderDiagnostics.currentState());
        String diag = RtEntityShaderDiagnostics.formatDiagnostics();
        assertTrue(diag.contains("state=MISSING"), "must report MISSING");
        assertTrue(diag.contains("alphaCutout=ACTIVE"), "alpha cutout must always be ACTIVE");
    }

    @Test
    public void testEntityShaderDiagnosticsStateTextured() {
        RtEntityShaderDiagnostics.updateFromFrame(
            12,          // entityTris
            0xDEAD_0001L, // posAddr
            0xDEAD_0002L, // idxAddr
            0xDEAD_0003L, // attrAddr
            0xDEAD_0004L, // atlasAddr
            0xDEAD_0005L, // rectAddr
            2048,         // atlasSize
            7,            // atlasSlotCount (> 1 -> TEXTURED)
            3             // materialCount
        );
        assertEquals(RtEntityShaderDiagnostics.State.TEXTURED, RtEntityShaderDiagnostics.currentState());
        String diag = RtEntityShaderDiagnostics.formatDiagnostics();
        assertTrue(diag.contains("state=TEXTURED"), "must report TEXTURED");
        assertTrue(diag.contains("textures=7"), "must report 7 texture slots");
        assertTrue(diag.contains("materials=3"), "must report 3 materials");
        // Cleanup
        RtEntityShaderDiagnostics.reset();
    }

    @Test
    public void testEntityShaderDiagnosticsStateDebugMaterial() {
        RtEntityShaderDiagnostics.updateFromFrame(
            6,            // entityTris
            0xDEAD_0001L, // posAddr
            0xDEAD_0002L, // idxAddr
            0xDEAD_0003L, // attrAddr
            0L,           // atlasAddr = 0 -> no atlas -> DEBUG_MATERIAL
            0L,
            0,
            0,
            0
        );
        assertEquals(RtEntityShaderDiagnostics.State.DEBUG_MATERIAL, RtEntityShaderDiagnostics.currentState());
        RtEntityShaderDiagnostics.reset();
    }
}
