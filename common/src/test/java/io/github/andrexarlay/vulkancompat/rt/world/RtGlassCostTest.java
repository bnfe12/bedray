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

    private static final String WATER = "/shaders/glsl/rt_common_water.glsl";
    private static final String RAYS = "/shaders/glsl/rt_rays.comp";
    private static final String CORE = "/shaders/glsl/rt_common_core.glsl";

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
        assertTrue(walk.contains("filterOut *= interfaceTransmittance(c, interfaceReflectance(c, dir), dir);"),
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
        String transmittance = function(RtShaderCompiler.loadSource("/shaders/glsl/rt_common_surface.glsl"),
            "vec3 traceTransmittance(vec3 origin, vec3 d, float tmax)");
        assertFalse(transmittance.contains("gSawDust"),
            "dust must never be shaded as an alpha filter: that made its shadow a block-shaped quad");
    }

    @Test
    void onlyTheCameraFacingInterfacePaysForAMirror() {
        String rays = RtShaderCompiler.loadSource(RAYS);
        assertFalse(rays.contains("step < 2u"),
            "the old gate mirrored the exit face of a glass block and a pane behind a pane for 2..8 % of the light");
        assertTrue(rays.contains("cp.mirrors < 2u && (cp.interfaces == 0u || s.isWater)"),
            "the first interface keeps its mirror, and so does a water face behind glass in either style (the look of the water)");
        assertTrue(rays.contains("cp.interfaces++;"), "the interface count has to advance past every interface");
        assertTrue(rays.contains("cp.mirrors++;"), "the mirror budget is per mirror ray, not per iteration of the walk");
        int gate = rays.indexOf("cp.mirrors < 2u && (cp.interfaces == 0u || s.isWater)");
        int mirror = rays.indexOf("interfaceMirror(", gate);
        assertTrue(mirror > gate, "the gate must guard the interface mirror ray");
    }

    @Test
    void theCameraRayCrossesEveryPaneInOneTraversal() {
        String rays = RtShaderCompiler.loadSource(RAYS);
        assertFalse(rays.contains("traceSurface("),
            "one closest-hit walk per glass face was the camera-side cost this walk exists to remove");
        String walk = function(rays, "bool cameraWalk(inout CameraPath cp,");
        assertEquals(1, walk.split("rayQueryInitializeEXT", -1).length - 1,
            "one query for the whole straight run, however many panes are in front of the surface");
        assertTrue(walk.contains("bend = cameraInterface(c, dir, cp, seed);"),
            "a pane is folded into the path from its candidate, not re-traversed from behind it");
        assertTrue(walk.contains("candidateAccepted(slot, geom, prim, bary, false)"),
            "the camera classification, not the filter one: a dust texel below its coverage is a hole and a visible one is a hit");
        assertTrue(walk.contains("isSelfAvatarPrim(slot, prim)"),
            "the first-person body is still not a surface the camera sees, now that the walk owns the traversal");
        assertTrue(walk.contains("fetchSurface(slot, geom, prim, bary, t, origin, dir, true, c);"),
            "wantNormal must stay true: interfaceReflectance reads s.ns, the wave normal of a water face");
        assertTrue(walk.contains("if (!cp.hadFirst) {\n            cp.hadFirst = true;\n            cp.firstDist = t;"),
            "outDepth reports the FIRST surface of the ray, as the per-face walk wrote firstDist before the layer: an"
                + " unconditional overwrite would report the surface behind the glass");
        assertTrue(walk.contains("if (isSelfAvatarPrim(slot, prim)) {\n            continue;"),
            "the exclusion has to skip the candidate, now that the walk owns the traversal the old traceSurface call did");
        int exhausted = walk.indexOf("return false;   // nothing is left of the light behind these layers");
        int tail = walk.indexOf("// The traversal ran out");
        assertTrue(exhausted > 0 && tail > exhausted,
            "a throughput of nearly zero ends the ray where it is: the water tail of a MISS must not run after it");
        assertTrue(walk.contains("if (cp.interfaces >= 16u)"), "the interface budget survives the rewrite");
        assertTrue(walk.contains("waterSegment(cp.med, origin + dir * lastT, dir, max(t - lastT, 0.0), T, ins);"),
            "the water column is split at the layers it crosses, and the clamp keeps an out-of-order candidate from turning"
                + " exp() into a gain above one");
        assertTrue(walk.contains("clamp(4096.0 - lastT, 0.0, 48.0)"),
            "the closing water segment of a miss keeps its 48 block tail and the same clamp");
    }

    @Test
    void aFoldedPaneKeepsTheSingleLayerMath() {
        String fn = function(RtShaderCompiler.loadSource(RAYS),
            "bool cameraInterface(Surface s, vec3 rayDir, inout CameraPath cp, inout uint seed)");
        assertTrue(fn.contains("cp.thr *= interfaceTransmittance(s, F, rayDir);"), "the tint of every pane still accumulates");
        assertTrue(fn.contains("cp.glassRefl += cp.thr * F * refl;"), "the mirror still carries the Fresnel weight and the throughput");
        assertTrue(fn.contains("cp.glassRefl += cp.thr * ((1.0 - F) * VANILLA_WATER_ALPHA) * vanillaWaterSurface(s, rayDir,"
                + " emissiveDistance(), seed);"),
            "the vanilla water sheet still blends its sprite over the scene, weighted by (1 - F) and the vanilla alpha");
        int mirror = fn.indexOf("interfaceMirror(");
        int cross = fn.indexOf("crossWaterBoundary(cp.med, s, rayDir);");
        assertTrue(mirror > 0 && cross > mirror,
            "the mirror ray starts in the medium IN FRONT of the face: the medium must toggle after the mirror is traced");
    }

    @Test
    void realisticWaterStillBendsTheCameraRay() {
        String rays = RtShaderCompiler.loadSource(RAYS);
        assertTrue(rays.contains("hit = cameraWalk(cp, rayOrig, rayDir, seed, s, bend);"), "the caller owns the Snell bend");
        assertTrue(rays.contains("float waterIor = surfaceIor(s);"), "the bend reads the IOR of the hit surface, not a global");
        assertTrue(rays.contains("vec3 rd = refract(rayDir, s.ns, rayDir.y < 0.0 ? 1.0 / waterIor : waterIor);"),
            "entering and leaving the water body use the reciprocal IOR, as the per-face loop did");
        int zero = rays.indexOf("cp.thr = vec3(0.0);");
        int dark = rays.indexOf("hit = false;", zero);
        assertTrue(zero > 0 && dark > zero,
            "total internal reflection leaves only the mirror light: the throughput goes to zero AND the pixel stops being a hit,"
                + " or the sky behind the surface leaks through the wave");
        assertTrue(rays.contains("rayOrig = s.pos + rayDir * 0.002;"),
            "the re-entered walk starts just behind the wavy face, along the refracted direction");
        assertTrue(rays.contains("bendIdx < 4u"), "a nested water body (entry, exit, entry again) stays bounded");
    }
}
