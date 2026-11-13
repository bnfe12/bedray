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
    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String TRAVERSAL = SHADERS + "/traversal/scene_traversal.slang";
    private static final String TRACER = SHADERS + "/pathtrace/path_tracer.slang";
    private static final String TRACER_CONTRACT = SHADERS + "/contracts/scene_tracer.slang";
    private static final String PASS = SHADERS + "/passes/path_trace_scene.slang";

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
    public void rangeIsPublishedInTheStatsSlotAndReadByTheTraversal() {
        assertEquals(RtStatsLayout.SKY_DISPLAY_COLOR_B_DAY + 12, RtStatsLayout.SELF_AVATAR_TRI_START,
            "the trailing uints retain their offsets after the private sky colour/day packet");
        assertEquals(RtStatsLayout.SKY_DISPLAY_COLOR_B_DAY + 16, RtStatsLayout.SELF_AVATAR_TRI_COUNT, "two separate uints, no uvec2 padding");
        assertTrue(RtStatsLayout.SIZE <= RtStatsLayout.SLOT_STRIDE, "the slot ring must still fit the struct");
        String renderer = readJava("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");
        assertTrue(renderer.contains("memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_START, entOk ? capture.selfAvatarTriStart() : 0)"),
            "the slot the shader reads this frame is written by the renderer before the dispatch");
        assertTrue(renderer.contains("memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_COUNT, entOk ? capture.selfAvatarTriCount() : 0)"),
            "a frame without an entity BLAS must publish 0: the primitive index would not exist");
        String pass = read(PASS);
        assertTrue(pass.contains("traversal.selfAvatarTriStart = pc.entityState.y;"),
            "the pass lifts the range out of the stats word the renderer just wrote");
        assertTrue(pass.contains("traversal.selfAvatarTriCount = pc.entityState.z & 0x3FFFFFFFu;"), "and its length (low 30 bits)");
        String traversal = read(TRAVERSAL);
        assertTrue(traversal.contains("uint selfAvatarTriStart;"), "the traversal state carries the range into the candidate loop");
        assertTrue(traversal.contains("uint selfAvatarTriCount;"), "and its length");
    }

    @Test
    public void selfShadowFollowsTheSettingAndMirrorsAlwaysSeeTheBody() {
        // 2026-11-07: with the body a full occluder, the floor under a first-person player rendered as a black blot - the
        // eye cannot see the feet that cover it. Removing the body shadow altogether was wrong (owner: the shadow stays);
        // the default mode skips the body only for rays that leave the floor inside its own column.
        String tracer = read(TRACER);
        assertTrue(tracer.contains("SurfaceHit hit = result.surfaceHits == 0u ? traversal.tracePrimary(ray)")
                && tracer.contains(": glossyEscape ? traversal.traceMirror(ray) : traversal.traceClosest(ray);"),
            "after the eye ray only a glossy or transmission ray may see the body: reflections of the player need it");
        String contract = read(TRACER_CONTRACT);
        assertTrue(contract.contains("SurfaceHit tracePrimary(Ray ray) {\n        return traceClosest(ray);")
                && contract.contains("SurfaceHit traceMirror(Ray ray) {\n        return traceClosest(ray);"),
            "a backend that has no first-person capture answers both hooks with the full scene");
        String traversal = read(TRAVERSAL);
        assertTrue(traversal.contains("override SurfaceHit tracePrimary(Ray ray) {\n        return traceNearest(ray, SELF_SHADOW_OFF);"),
            "the eye ray skips the body");
        assertTrue(traversal.contains("SurfaceHit traceClosest(Ray ray) {\n        return traceNearest(ray, selfShadowMode);"),
            "a diffuse bounce follows the self_shadow setting");
        assertTrue(traversal.contains("override SurfaceHit traceMirror(Ray ray) {\n        return traceNearest(ray, SELF_SHADOW_FULL);"),
            "a mirror ray always keeps the body");
        String visibility = block(traversal, "VisibilityResult traceVisibility(Ray ray) {");
        int skip = visibility.indexOf("selfOccluderSkipped(selfShadowMode, entityInstance(query.CandidateInstanceID())");
        int commit = visibility.indexOf("query.CommitNonOpaqueTriangleHit();");
        assertTrue(skip >= 0 && commit > skip, "a shadow ray applies the setting before it commits a blocker");
        String ser = read(SHADERS + "/traversal/reordering_traversal.slang");
        assertTrue(ser.contains("SurfaceHit traceClosest(Ray ray) {\n        TriangleIntersection hit = base.traceIntersection(ray, base.secondarySelfShadowMode());")
                && ser.contains("override SurfaceHit traceMirror(Ray ray) {\n        TriangleIntersection hit = base.traceIntersection(ray, SELF_SHADOW_FULL);"),
            "the SER wrapper keeps the same split, or SER-capable GPUs would trace a different shadow");
        String helper = block(read(SHADERS + "/contracts/triangle_intersection.slang"),
            "bool selfOccluderSkipped(uint mode, bool entity, uint primitive, uint start, uint count, float3 origin) {");
        assertTrue(helper.contains("if (mode == SELF_SHADOW_FULL) return false;")
                && helper.contains("if (mode == SELF_SHADOW_OFF) return true;")
                && helper.contains("return dot(origin.xz, origin.xz) < SELF_SHADOW_FOOTPRINT_RADIUS * SELF_SHADOW_FOOTPRINT_RADIUS;"),
            "the default mode skips the body only for rays that start inside its column (camera-relative xz = 0)");
        for (String pass : new String[] {"path_trace_scene", "rc_path_trace_scene",
                "restir_pt_spatial_scene", "rc_field_build_scene"}) {
            String source = read(SHADERS + "/passes/" + pass + ".slang");
            assertTrue(source.contains("traversal.selfAvatarTriCount = pc.entityState.z & 0x3FFFFFFFu;")
                    && source.contains("traversal.selfShadowMode = pc.entityState.z >> 30u;"),
                pass + " unpacks the count and the mode the host packed into entityState.z");
        }
        assertTrue(read(SHADERS + "/traversal/hybrid_traversal.slang").contains("hardware.traceMirror(ray)"),
            "the hybrid backend forwards mirror rays to the half that holds the body");
    }

    @Test
    public void theCandidateLoopSkipsBeforeItCommits() {
        String trace = read(SHADERS + "/traversal/triangle_query.slang");
        int skip = trace.indexOf("if (selfOccluderSkipped(selfShadowMode, entityInstance(");
        int commit = trace.indexOf("query.CommitNonOpaqueTriangleHit();");
        assertTrue(skip >= 0, "the candidate loop has to drop the avatar's triangles");
        assertTrue(commit >= 0, "the walk commits the terminal surface it really hits");
        assertTrue(skip < commit, "a confirmed candidate cannot be removed any more: the test must come first");
        assertTrue(trace.substring(skip, commit).contains("continue;"), "dropping means skipping the candidate, not confirming it");
        assertTrue(trace.contains("query.CandidatePrimitiveIndex()"),
            "the range is compared against the candidate primitive, read before the commit");
    }

    @Test
    public void helperOnlyMatchesEntityTrianglesOfThisFrame() {
        String helper = block(read(SHADERS + "/contracts/triangle_intersection.slang"),
            "bool excludedSelfAvatar(bool primary, bool entity, uint primitive, uint start, uint count) {");
        assertTrue(helper.contains("primary && entity"),
            "world sections use the same primitive numbering: the check must not match their triangles");
        assertTrue(helper.contains("count != 0u"),
            "third person and a disabled option publish 0: nothing may be skipped then");
        assertTrue(helper.contains("primitive >= start && primitive - start < count"),
            "half-open range: the triangle right after the body is the next entity");
        assertFalse(helper.contains("RayQuery"), "the helper is a pure range test, called with the candidate's own indices");
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

    private static String block(String source, String header) {
        return requireBlock(source, header);
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
