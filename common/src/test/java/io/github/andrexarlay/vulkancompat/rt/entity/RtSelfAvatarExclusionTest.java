package io.github.andrexarlay.vulkancompat.rt.entity;

import io.github.andrexarlay.vulkancompat.rt.world.RtStatsLayout;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local player's model is captured into the shared entity BLAS in first person, so reflections show the body. The price is
 * that a camera ray starts inside that body: its inner faces are a couple of centimetres from the eye and would fill the screen
 * with a brown wedge. These checks cover the one thing that keeps both: the captured range is recorded and only the primary
 * rays skip it.
 */
public class RtSelfAvatarExclusionTest {

    private static final String CONFIG = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtConfig.java";
    private static final String CORE = "src/main/resources/shaders/rt_common_core.glsl";
    private static final String SURFACE = "src/main/resources/shaders/rt_common_surface.glsl";
    private static final String WATER = "src/main/resources/shaders/rt_common_water.glsl";
    private static final String PATH = "src/main/resources/shaders/glsl/rt_path.comp";
    private static final String RAYS = "src/main/resources/shaders/glsl/rt_rays.comp";

    @Test
    public void captureRecordsTheRangeInTheSharedEntityIndexBuffer() {
        String src = readJava("src/main/java/io/github/andrexarlay/vulkancompat/rt/entity/EntityCaptureManager.java");
        String capture = requireBlock(src, "public synchronized boolean captureSelfAvatar(");
        assertTrue(capture.contains("frameConsumer.getIndexCount()"),
            "the range must come from the index buffer: the BLAS primitive index is the triangle position there, and a partial "
                + "quad of an earlier entity would shift a vertex/4 estimate");
        assertTrue(capture.contains("selfAvatarTriStart"), "the first triangle of the capture must be recorded for the shader");
        assertTrue(capture.contains("selfAvatarTriCount"), "the length of the capture must be recorded: 0 means nothing to skip");
        assertTrue(src.contains("public synchronized int selfAvatarTriStart()"),
            "RtFrameRenderer publishes the range into StatsBuf, so it needs an accessor");
        assertTrue(src.contains("public synchronized int selfAvatarTriCount()"),
            "an empty capture must read as zero triangles");
        assertTrue(requireBlock(src, "public synchronized void beginFrame(long frameId) {").contains("selfAvatarTriCount = 0;"),
            "a frame without a capture must not inherit the range of the previous one");
    }

    @Test
    public void rangeIsPublishedInTheStatsSlot() {
        assertEquals(RtStatsLayout.RESERVOIR_AUX_ADDR + 12, RtStatsLayout.SELF_AVATAR_TRI_START,
            "the trailing uints follow the reservoir stats block directly");
        assertEquals(RtStatsLayout.RESERVOIR_AUX_ADDR + 16, RtStatsLayout.SELF_AVATAR_TRI_COUNT, "two separate uints, no uvec2 padding");
        assertTrue(RtStatsLayout.SIZE <= RtStatsLayout.SLOT_STRIDE, "the slot ring must still fit the struct");
        String core = read(CORE);
        assertTrue(core.contains("uint selfAvatarTriStart;"), "the shader reads the first triangle of the capture");
        assertTrue(core.contains("uint selfAvatarTriCount;"), "and its length");
        String renderer = readJava("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");
        assertTrue(renderer.contains("memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_START, entOk ? capture.selfAvatarTriStart() : 0)"),
            "the slot the shader reads this frame is written by the renderer before the dispatch");
        assertTrue(renderer.contains("memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_COUNT, entOk ? capture.selfAvatarTriCount() : 0)"),
            "a frame without an entity BLAS must publish 0: the primitive index would not exist");
    }

    @Test
    public void onlyPrimaryRaysSkipTheAvatar() {
        String path = read(PATH);
        assertTrue(path.contains("traceSurface(o, d, b == 0u ? 0.001 : 0.0, b == 0u ? 4096.0 : reach, false, b == 0u, s)"),
            "the path tracer must skip the avatar on the primary hit only: reflections of the player need it");
        String rays = read(RAYS);
        String walk = requireBlock(rays, "bool cameraWalk(inout CameraPath cp, vec3 origin, vec3 dir, inout uint seed,"
            + " out Surface s, out bool bend) {");
        assertTrue(walk.contains("if (isSelfAvatarPrim(slot, prim)) {\n            continue;"),
            "the raster-like mode starts its camera ray inside the same body, and the camera walk owns the traversal now");
        assertFalse(walk.contains("rayQueryConfirmIntersectionEXT"),
            "the avatar has to be dropped before anything is committed: the walk never commits for that reason");
        String water = read(WATER);
        assertFalse(requireBlock(water, "bool traceThroughInterfacesM(").contains("isSelfAvatarPrim"),
            "mirrors and GI keep the body: the exclusion belongs to the camera ray, not to the shared interface walk");
        assertTrue(water.contains("traceWaterBoundary(o, vec3(0.0, 1.0, 0.0), 160.0, true, t, tint)"),
            "under water the camera-medium probe looks straight up through the player's own head: it would read a blocker and "
                + "lose the water body");
        String refracted = requireBlock(water, "uint k = traceWaterBoundary(o, Lw, 200.0, ");
        assertFalse(refracted.contains(", true,"),
            "a sun ray refracted by the water surface must still be shaded by the player, or the body stops casting a shadow in water");
    }

    @Test
    public void traceSurfaceSkipsTheCandidateBeforeConfirming() {
        String src = read(SURFACE);
        String trace = requireBlock(src, "bool traceSurface(vec3 origin, vec3 dir, float tmin, float tmax, bool skipTranslucent, bool excludeSelfAvatar, out Surface s) {");
        int skip = trace.indexOf("if (excludeSelfAvatar && isSelfAvatarPrim(");
        int confirm = trace.indexOf("rayQueryConfirmIntersectionEXT(rq)");
        assertTrue(skip >= 0, "the candidate loop has to drop the avatar's triangles");
        assertTrue(skip < confirm, "a confirmed candidate cannot be removed any more: the test must come first");
        assertTrue(trace.substring(skip, confirm).contains("continue;"), "dropping means skipping the candidate, not confirming it");
        assertTrue(trace.contains("rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)"),
            "the range is compared against the candidate primitive, read before the commit");
        assertTrue(src.contains("bool traceSurface(vec3 origin, vec3 dir, float tmin, float tmax, bool skipTranslucent, out Surface s) {\n"
                + "    return traceSurface(origin, dir, tmin, tmax, skipTranslucent, false, s);"),
            "shadow, sky and secondary queries keep the avatar: the default must forward to include it");
    }

    @Test
    public void helperOnlyMatchesEntityTrianglesOfThisFrame() {
        String core = read(CORE);
        String helper = requireBlock(core, "bool isSelfAvatarPrim(uint customIndex, uint prim) {");
        assertTrue(helper.contains("isEntityHit(customIndex)"),
            "world sections use the same primitive numbering: the check must not match their triangles");
        assertTrue(helper.contains("selfAvatarTriCount > 0u"),
            "third person and a disabled option publish 0: nothing may be skipped then");
        assertTrue(helper.contains("prim < fb.selfAvatarTriStart + fb.selfAvatarTriCount"),
            "half-open range: the triangle right after the body is the next entity");
        assertFalse(helper.contains("rayQuery"), "the helper is a pure range test, called with the candidate's own indices");
        assertTrue(readJava(CONFIG).contains("System.getProperty(\"vkbridge.rt.selfAvatar\", \"true\")"),
            "the A/B switch stays on the capture, not on the skip");
    }

    private static String requireBlock(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block: " + header);
    }

    private static String read(String relative) {
        return readJava(relative);
    }

    private static String readJava(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
