package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassDriverGate;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spatial stage of ReSTIR PT (restir/pt_spatial.slang behind passes/restir_pt_spatial*.slang). It runs after the
 * trace pass, pools the records the temporal stage just wrote and publishes the result into the other half of the ring,
 * which is the half the next frame's temporal import reads. Four invariants:
 *
 * <ol>
 *   <li><b>Read one half, write the other.</b> Every thread reads neighbours from the half the trace wrote and writes
 *   only its own slot of the other half, so one dispatch has no read-after-write race and needs no checker.</li>
 *   <li><b>A fixed, distinct neighbour set.</b> {@code ptSpatialReuse} cannot tell a duplicate draw from a second
 *   sample, so the four neighbours of a pixel must be four different pixels, none of them the pixel itself.</li>
 *   <li><b>Nothing is lost.</b> A pixel that is no receiver, or whose neighbours all fail, publishes its own record, so
 *   the next import sees at least what temporal-only reuse would have handed it.</li>
 *   <li><b>The host keeps the parity.</b> The spatial half is the newest record set, so the next trace dispatch must
 *   read it: the ring state counts the spatial publish as a write.</li>
 * </ol>
 */
class RestirPtSpatialStageTest {

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path STAGE = SHADERS.resolve("restir/pt_spatial.slang");
    private static final Path TEMPORAL = SHADERS.resolve("restir/pt_temporal.slang");
    private static final Path ROOT = SHADERS.resolve("passes/restir_pt_spatial.slang");
    private static final Path SCENE_ROOT = SHADERS.resolve("passes/restir_pt_spatial_scene.slang");
    private static final Path JAVA = Path.of("src/main/java/io/github/andrexarlay/vulkancompat");
    private static final Path BACKEND = JAVA.resolve("rt/world/ReferencePathTraceBackend.java");
    private static final Path TRACE_HOST = JAVA.resolve("rt/world/RtPathTraceSlangPass.java");

    // Mirrors of the shader constants, pinned below by source guards.
    private static final int NEIGHBOURS = 4;
    private static final double RADIUS_BASE = 2.5;
    private static final double RADIUS_STEP = 2.0;

    // ---- neighbour pattern ------------------------------------------------------------------------------------------

    @Test
    void theNeighbourPatternIsFourDistinctOtherPixelsForEveryRotation() throws IOException {
        String stage = read(STAGE);
        assertTrue(stage.contains("static const uint PT_SPATIAL_NEIGHBOURS = 4u;"), "four neighbours");
        assertTrue(stage.contains("static const float PT_SPATIAL_RADIUS_BASE = 2.5;"), "first ring radius");
        assertTrue(stage.contains("static const float PT_SPATIAL_RADIUS_STEP = 2.0;"), "radius step between neighbours");
        assertTrue(stage.contains("float radius = PT_SPATIAL_RADIUS_BASE + PT_SPATIAL_RADIUS_STEP * float(i);")
                && stage.contains("float angle = rotation + float(i) * (0.5 * PI);")
                && stage.contains("return int2(int(round(radius * cos(angle))), int(round(radius * sin(angle))));"),
            "the offset is a rotated cross with growing radius; the model below is that formula");

        // HLSL leaves the half-way case of round() to the implementation, so the proof holds for both conventions.
        DoubleUnaryOperator[] roundings = {Math::rint, x -> Math.floor(x + 0.5)};
        for (DoubleUnaryOperator rounding : roundings) {
            for (int step = 0; step < 200_000; step++) {
                double rotation = step * (2.0 * Math.PI / 200_000.0);
                Set<Long> seen = new HashSet<>();
                for (int i = 0; i < NEIGHBOURS; i++) {
                    double radius = RADIUS_BASE + RADIUS_STEP * i;
                    double angle = rotation + i * (0.5 * Math.PI);
                    long x = (long) rounding.applyAsDouble(radius * Math.cos(angle));
                    long y = (long) rounding.applyAsDouble(radius * Math.sin(angle));
                    assertFalse(x == 0 && y == 0, "a neighbour is never the pixel itself");
                    assertTrue(Math.max(Math.abs(x), Math.abs(y)) <= 9, "the kernel stays inside a 19x19 window");
                    assertTrue(seen.add(x * 1_000L + y), "rotation " + rotation + " repeats neighbour " + i);
                }
            }
        }
    }

    // ---- shader contract --------------------------------------------------------------------------------------------

    @Test
    void theStageReadsTheTemporalHalfAndPublishesTheOther() throws IOException {
        String stage = read(STAGE);
        assertTrue(stage.contains("uint readSlot = ptReservoirSlot(pixel, size, parity);"),
            "the pixel's own record is the one the trace pass just wrote");
        assertTrue(stage.contains("uint writeSlot = ptReservoirSlot(pixel, size, parity ^ 1u);"),
            "the result goes to the other half, the one the next frame's import reads");
        assertTrue(stage.contains("RestirPtReservoir neighbour = reservoirs[ptReservoirSlot(neighbourPixel, size, parity)];"),
            "neighbours come from the half the trace wrote, never from the half being written");
        assertFalse(stage.contains("reservoirs[readSlot] ="), "the trace half is read-only in this pass");
        assertEquals(2, count(stage, "reservoirs[writeSlot] = current;"),
            "two publishes: the untouched record of a non-receiver and the merged one");
    }

    @Test
    void aPixelThatIsNoReceiverPublishesItsOwnRecord() throws IOException {
        String stage = read(STAGE);
        int at = stage.indexOf("if (!ptSpatialReceiver(current)) {");
        assertTrue(at >= 0, "the receiver test comes first");
        String branch = stage.substring(at, stage.indexOf('}', at));
        assertTrue(branch.contains("reservoirs[writeSlot] = current;") && branch.contains("return;"),
            "a sky pixel or water keeps its empty record, so the next import does not resurrect the stale half");
        assertTrue(stage.contains("return isFinite(reservoir.stats.x) && reservoir.stats.x > 0.0"),
            "a receiver is any pixel the temporal stage counted an attempt for, with or without a sample");
    }

    @Test
    void everyNeighbourGoesThroughTheModuleMerge() throws IOException {
        String stage = read(STAGE);
        assertTrue(stage.contains("current = ptSpatialReuse(current, neighbour, receiver, traversal, atlas, blockMaterials,"
                + " bias, materialOptions, seed);"),
            "compatibility, first-edge visibility, the shift Jacobian and the ratio cap are the module's, not re-derived");
        assertTrue(stage.contains("for (uint i = 0u; i < PT_SPATIAL_NEIGHBOURS; ++i) {"), "every neighbour once");
        assertFalse(stage.contains("ptResolveFiltered(") || stage.contains("ptResolveDetailed(")
                || stage.contains("ptResolveVisible("),
            "the stage shades nothing: a spatial winner reaches the image through the next frame's ptResolveFiltered");
    }

    @Test
    void theTemporalStageStoresItsReceiverEvenWithoutASample() throws IOException {
        String temporal = read(TEMPORAL);
        assertTrue(temporal.contains("current = ptAnchorReceiver(current, record.anchorPosition, record.anchorNormal);\n"
                + "    reservoirs[slot] = current;"),
            "a pixel whose attempts all missed still has to be a receiver its neighbours can hand a path to");
        String body = block(temporal, "RestirPtReservoir ptAnchorReceiver(");
        assertTrue(body.contains("if (ptReservoirHasSample(reservoir)) return reservoir;"),
            "a record with a sample already carries this receiver as its anchor: it is never rewritten");
    }

    @Test
    void bothRootsShareTheTraceBindingsAndPushBlock() throws IOException {
        for (Path root : new Path[] {ROOT, SCENE_ROOT}) {
            String source = read(root);
            assertTrue(source.contains("[[vk::binding(36, 0)]] RWStructuredBuffer<RestirPtReservoir> ptReservoirs;"),
                root + ": the ring at the trace pass's own binding");
            assertTrue(source.contains("[[vk::push_constant]] PathTracePush pc;"),
                root + ": the trace pass's own push block, so the host pushes the same 112 bytes twice");
            assertTrue(source.contains("pathTracePtSpatialEntry(traversal, entities, tid,"),
                root + ": the shared body");
        }
        String entry = read(SHADERS.resolve("pathtrace/path_trace_entry.slang"));
        assertTrue(entry.contains("ptSpatialStage(traversal, atlas, table, materialOptions, max(pc.rayBias, 0.0), ptReservoirs,"),
            "the entry hands the stage the trace's own bias and material options");
        assertEquals(2, count(entry, "MaterialSamplingOptions materialOptions = pathTraceMaterialOptions(f, pc);"),
            "one definition of the filtered walk's options for the trace and the spatial stage");
        Assumptions.assumeTrue(SlangPassLibrary.available("restir_pt_spatial")
            && SlangPassLibrary.available("restir_pt_spatial_scene")
            && SlangPassLibrary.available("path_trace"), "the spatial passes were not compiled");
        SlangPassManifest trace = SlangPassLibrary.load("path_trace").orElseThrow().manifest();
        SlangPassManifest traceScene = SlangPassLibrary.load("path_trace_scene").orElseThrow().manifest();
        for (String[] pair : new String[][] {{"restir_pt_spatial", "legacy"}, {"restir_pt_spatial_scene", "scene"}}) {
            SlangPassManifest manifest = SlangPassLibrary.load(pair[0]).orElseThrow().manifest();
            SlangPassManifest twin = pair[1].equals("scene") ? traceScene : trace;
            assertEquals(14, manifest.bindings().size(), pair[0] + ": binding count");
            assertEquals(twin.pushConstantBytes(), manifest.pushConstantBytes(), pair[0] + ": push block");
            for (SlangPassManifest.Binding binding : manifest.bindings()) {
                SlangPassManifest.Binding same = twin.binding(binding.name());
                assertNotNull(same, pair[0] + ": '" + binding.name() + "' is not a trace binding");
                assertEquals(same.binding(), binding.binding(), pair[0] + ": binding of " + binding.name());
                assertEquals(same.descriptorType(), binding.descriptorType(), pair[0] + ": type of " + binding.name());
                assertEquals(same.elementBytes(), binding.elementBytes(), pair[0] + ": element of " + binding.name());
            }
            RtPathTraceSlangPass.requirePtSpatialManifest(manifest);
            assertTrue(SlangPassDriverGate.permits(pair[0], SlangPassLibrary.load(pair[0]).orElseThrow().spirv()),
                pair[0] + ": no descriptor handle may be an OpUndef pointer, or the NVIDIA driver faults on the pipeline");
        }
    }

    // ---- host ---------------------------------------------------------------------------------------------------------

    @Test
    void theRingCountsTheSpatialPublishAsAWrite() {
        RestirDiState pt = new RestirDiState(false);
        pt.ringCleared();
        assertEquals(0, pt.parity());
        pt.afterDispatch(true);
        pt.spatialPublished();
        assertTrue(pt.historyWritten(), "the spatial half holds records");
        assertEquals(0, pt.parity(),
            "the trace wrote half 0 and the spatial stage half 1, so the next trace writes 0 again and imports 1");
        assertEquals(RestirDiState.FLAG_ENABLED | RestirDiState.FLAG_HISTORY, pt.flags(false, false));
        pt.afterDispatch(true);
        assertEquals(1, pt.parity(), "a frame without the spatial stage alternates as before");

        RestirDiState nativeRing = new RestirDiState(true);
        nativeRing.afterDispatch(true);
        nativeRing.spatialPublished();
        assertEquals(0, nativeRing.parity(), "a native ring's parity is the core's, never a host mirror");
        assertEquals("temporal+spatial", pt.mode(true, true, true));
    }

    @Test
    void theBackendRunsTheSpatialStageOnlyBehindTheTemporalOne() throws IOException {
        String backend = read(BACKEND);
        assertTrue(backend.contains("boolean ptSpatialRan = ptFlags != 0 && input.ptSpatial() && trace.ptSpatialAvailable(scene);"),
            "no ring, no temporal stage, no spatial stage: it only ever pools what this frame's trace wrote");
        assertTrue(backend.contains("trace.recordPtSpatial(cmd, scene, atlases, ptRange, capturedEntities, frame, dispatch);"),
            "the same atlases, ring, entities, frame block and push word the trace dispatch used");
        int after = backend.indexOf("pt.afterDispatch(ptFlags != 0);");
        int published = backend.indexOf("if (ptSpatialRan) pt.spatialPublished();");
        assertTrue(after >= 0 && published > after, "the spatial publish is counted after the trace write");
        assertTrue(backend.contains("return pt.mode(true, ptReservoirs != null, ptSpatialLast);"),
            "the mode log says whether the stage ran");
        String host = read(TRACE_HOST);
        assertTrue(host.contains("static final String PT_SPATIAL_PASS_NAME = \"restir_pt_spatial\";")
                && host.contains("static final String PT_SPATIAL_SCENE_PASS_NAME = \"restir_pt_spatial_scene\";"),
            "one program per traversal backend, like the trace pass");
        String spatial = block(host, "void recordPtSpatial(");
        assertTrue(spatial.contains("dispatch.writeTo(push);"), "the trace pass's own push bytes");
        assertTrue(spatial.contains("VkBarriers.fullMemoryBarrier(cmd);"),
            "the next frame's import, and the display, see the records this dispatch wrote");
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static String block(String source, String header) {
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

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
