package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 guards for the cost of glass. A stained-glass window is two faces per block and several panes deep, and both the walk
 * through it and the mirror ray it spawns are multiplied by every secondary ray of the frame. Each guard removes a traversal
 * whose result was already known, or pins the rule that keeps the cheaper walk equivalent to the one it replaced.
 */
class RtGlassCostTest {

    private static final String WATER = "/shaders/rt_common_water.glsl";
    private static final String RAYS = "/shaders/rt_rays.comp";
    private static final String CORE = "/shaders/rt_common_core.glsl";

    private static String function(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, signature + " moved");
        int end = source.indexOf("\n}", at);
        return source.substring(at, end < 0 ? source.length() : end).replace("\r\n", "\n");
    }

    @Test
    void theInterfaceWalkTraversesTheRayOnce() {
        String walk = function(RtShaderCompiler.loadSource(WATER),
            "bool traceThroughInterfacesM(vec3 origin, vec3 dir, float reach,");
        assertTrue(walk.contains("rayQueryInitializeEXT"), "the walk must own its traversal");
        assertEquals(1, walk.split("rayQueryInitializeEXT", -1).length - 1,
            "one ray query for the whole ray: a pane is classified from the candidate, not from a closest hit");
        assertFalse(walk.contains("traceSurface("),
            "one traceSurface per glass face was the cost: a window paid a traversal for every layer");
        assertTrue(walk.contains("rayQueryConfirmIntersectionEXT(sq)"),
            "the surface that really ends the ray still has to be committed");
    }

    @Test
    void theFilteredLayerIsFoldedInWithTheSameFormula() {
        String walk = function(RtShaderCompiler.loadSource(WATER),
            "bool traceThroughInterfacesM(vec3 origin, vec3 dir, float reach,");
        assertTrue(walk.contains("filterOut *= interfaceTransmittance(c, interfaceReflectance(c, dir));"),
            "a filtered candidate keeps the Beer-Lambert layer of the per-face walk");
        assertTrue(walk.contains("crossWaterBoundary(m, c, dir);"), "the water medium still toggles at every crossing");
        assertTrue(walk.contains("waterSegment(m, origin + dir * lastT, dir, max(t - lastT, 0.0), T, ins);"),
            "the segment since the last layer, otherwise the body of the water is counted twice; the clamp keeps a candidate that"
                + " arrives out of order from turning exp() into a gain above one");
        assertTrue(walk.contains("clamp(reach - lastT, 0.0, 48.0)"),
            "the closing water segment is clamped for the same reason");
        assertTrue(walk.contains("++layers >= 16u"), "the layer budget stays at 8 glass blocks (now counted in interfaces, not hops)");
        assertTrue(walk.contains("!gSawDust"), "the walk has to consume the dust flag, or the fetch it exists for is never taken");
        assertTrue(walk.contains("fetchSurface(slot, geom, prim, bary, t, origin, dir, true, c);"),
            "wantNormal must stay true: interfaceReflectance reads s.ns, which is the wave-perturbed normal of a water face");
    }

    @Test
    void dustStaysOutOfShadowRaysButInsideTheWalk() {
        String core = RtShaderCompiler.loadSource(CORE);
        int redstone = core.indexOf("            // emitter sampler remain unchanged.");
        assertTrue(redstone > 0, "the redstone skip branch of candidateAccepted moved");
        String tail = core.substring(redstone, Math.min(core.length(), redstone + 200)).replace("\r\n", "\n");
        assertTrue(tail.contains("gSawDust = true;"),
            "the interface walk needs to know that this candidate was dust, not a hole, or a mirror loses the dust line");
        String transmittance = function(RtShaderCompiler.loadSource("/shaders/rt_common_surface.glsl"),
            "vec3 traceTransmittance(vec3 origin, vec3 d, float tmax)");
        assertFalse(transmittance.contains("gSawDust"),
            "dust must never be shaded as an alpha filter: that made its shadow a block-shaped quad");
    }

    @Test
    void onlyTheCameraFacingInterfacePaysForAMirror() {
        String rays = RtShaderCompiler.loadSource(RAYS);
        assertFalse(rays.contains("step < 2u"),
            "the old gate mirrored the exit face of a glass block and a pane behind a pane for 2..8 % of the light");
        assertTrue(rays.contains("mirrors < 2u && (interfaces == 0u || s.isWater)"),
            "the first interface keeps its mirror, and so does a water face behind glass in either style (the look of the water)");
        assertTrue(rays.contains("interfaces++;"), "the interface count has to advance past every interface");
        assertTrue(rays.contains("mirrors++;"), "the mirror budget is per mirror ray, not per iteration of the walk");
        int gate = rays.indexOf("mirrors < 2u && (interfaces == 0u || s.isWater)");
        int mirror = rays.indexOf("interfaceMirror(", gate);
        assertTrue(mirror > gate, "the gate must guard the interface mirror ray");
    }
}
