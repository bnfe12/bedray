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
    private static final String PASS = SHADERS + "/passes/path_trace.slang";

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
        assertEquals(RtStatsLayout.RESERVOIR_AUX_ADDR + 12, RtStatsLayout.SELF_AVATAR_TRI_START,
            "the trailing uints follow the reservoir stats block directly");
        assertEquals(RtStatsLayout.RESERVOIR_AUX_ADDR + 16, RtStatsLayout.SELF_AVATAR_TRI_COUNT, "two separate uints, no uvec2 padding");
        assertTrue(RtStatsLayout.SIZE <= RtStatsLayout.SLOT_STRIDE, "the slot ring must still fit the struct");
        String renderer = readJava("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");
        assertTrue(renderer.contains("memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_START, entOk ? capture.selfAvatarTriStart() : 0)"),
            "the slot the shader reads this frame is written by the renderer before the dispatch");
        assertTrue(renderer.contains("memPutInt(hdr + RtStatsLayout.SELF_AVATAR_TRI_COUNT, entOk ? capture.selfAvatarTriCount() : 0)"),
            "a frame without an entity BLAS must publish 0: the primitive index would not exist");
        String pass = read(PASS);
        assertTrue(pass.contains("traversal.selfAvatarTriStart = pc.entityState.y;"),
            "the pass lifts the range out of the stats word the renderer just wrote");
        assertTrue(pass.contains("traversal.selfAvatarTriCount = pc.entityState.z;"), "and its length");
        String traversal = read(TRAVERSAL);
        assertTrue(traversal.contains("uint selfAvatarTriStart;"), "the traversal state carries the range into the candidate loop");
        assertTrue(traversal.contains("uint selfAvatarTriCount;"), "and its length");
    }

    @Test
    public void onlyPrimaryRaysSkipTheAvatar() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("SurfaceHit hit = result.surfaceHits == 0u ? traversal.tracePrimary(ray)")
                && tracer.contains(": traversal.traceClosest(ray);"),
            "the path tracer must skip the avatar on the primary hit only: reflections of the player need it");
        assertTrue(read(TRACER_CONTRACT).contains("SurfaceHit tracePrimary(Ray ray) {\n        return traceClosest(ray);"),
            "a backend that has no first-person capture answers the primary ray with the full scene, so the hook cannot"
                + " silently drop geometry somewhere else");
        String traversal = read(TRAVERSAL);
        int primary = traversal.indexOf("override SurfaceHit tracePrimary(Ray ray) {\n        return traceNearest(ray, true);");
        int closest = traversal.indexOf("SurfaceHit traceClosest(Ray ray) {\n        return traceNearest(ray, false);");
        assertTrue(primary >= 0, "the scene traversal opts into the exclusion on the eye ray only");
        assertTrue(closest >= 0, "and keeps the body for every other closest query");
        String visibility = block(traversal, "VisibilityResult traceVisibility(Ray ray) {");
        assertFalse(visibility.contains("selfAvatar"),
            "shadow and visibility rays keep the body: the exclusion belongs to the camera ray, not to the blocker test");
        assertTrue(visibility.contains("RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH"),
            "the shadow ray takes the first blocker, so the avatar shades the ground under the player");
    }

    @Test
    public void theCandidateLoopSkipsBeforeItCommits() {
        String traversal = read(TRAVERSAL);
        String trace = block(traversal, "SurfaceHit traceNearest(Ray ray, bool excludeSelfAvatar) {");
        int skip = trace.indexOf("if (excludeSelfAvatar && selfAvatarPrimitive(");
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
        String helper = block(read(TRAVERSAL), "bool selfAvatarPrimitive(uint instanceId, uint primitive) {");
        assertTrue(helper.contains("!entityInstance(instanceId)"),
            "world sections use the same primitive numbering: the check must not match their triangles");
        assertTrue(helper.contains("selfAvatarTriCount == 0u"),
            "third person and a disabled option publish 0: nothing may be skipped then");
        assertTrue(helper.contains("primitive < selfAvatarTriStart + selfAvatarTriCount"),
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
