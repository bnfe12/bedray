package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spatial ReSTIR DI for the Slang path tracer is a port of {@code rt_restir_spatial.comp}, and it is the reuse stage that
 * can silently make light wrong instead of noisy: a neighbour's sample re-resolved at the wrong surface, a pooled record
 * indexed against a resolution the ring no longer has, or a merged weight that survives into next frame's temporal
 * import. The guards below pin the numbers the owner tuned in the legacy kernel, the checker discipline that makes the
 * in-place update race-free, and the host contract that keeps the pass optional.
 */
class RtSlangRestirSpatialTest {

    private static final String SPATIAL = "src/main/resources/shaders/slang/restir/spatial_block_emitters.slang";
    private static final String PASS = "src/main/resources/shaders/slang/passes/restir_spatial.slang";
    private static final String DI = "src/main/resources/shaders/slang/restir/di_block_emitters.slang";
    private static final String RESERVOIR = "src/main/resources/shaders/slang/restir/restir_di.slang";
    private static final String LEGACY = "src/main/resources/shaders/glsl/rt_restir_spatial.comp";
    private static final String HOST = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSpatialReuseSlangPass.java";
    private static final String GAME_PASS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSlangPathPass.java";

    /**
     * The owner tunes these against real scenes; a retune has to move both files or neither. The legacy kernel hard-codes
     * its square as {@code round(u * 14.0) - 7}, so the radius is read out of that expression rather than out of the
     * unused {@code SPATIAL_RADIUS} declaration next to it.
     */
    @Test
    void thePoolNumbersAreTheLegacyOnes() {
        String slang = read(SPATIAL);
        String legacy = read(LEGACY);
        assertEquals(intNumber(legacy, "SPATIAL_DRAWS"), intNumber(slang, "SPATIAL_DRAWS"),
            "draws per pixel: the port must pool the same neighbourhood the legacy kernel proved out");
        assertTrue(slang.contains("static const int SPATIAL_OFFSET_RADIUS = 7;"),
            "the legacy square is plus or minus seven per axis (round(u * 14.0) - 7)");
        assertTrue(legacy.contains("ivec2 off = ivec2(round(u * 14.0)) - 7;"), "and the port reads 2 * 7 from that");
        assertTrue(slang.contains("static const float SPATIAL_NORMAL_DOT_MIN = 0.85;")
            && legacy.contains("if (dot(nQ, nP) < 0.85)"),
            "the same normal rejection: walls must not import each other's light");
        assertTrue(slang.contains("SPATIAL_PLANE_TOLERANCE_RELATIVE * max(length(position), 1.0)\n"
                + "            + SPATIAL_PLANE_TOLERANCE_ABSOLUTE;")
            && legacy.contains("float planeTol = 0.05 * max(own.meta.w, 1.0) + 0.1;"),
            "the same plane gate - five percent of the camera distance plus a tenth of a block");
        assertTrue(slang.contains("static const float SPATIAL_PLANE_TOLERANCE_RELATIVE = 0.05;")
            && slang.contains("static const float SPATIAL_PLANE_TOLERANCE_ABSOLUTE = 0.1;"),
            "and both halves of that tolerance are named constants of this module");
        assertTrue(slang.contains("pcgHash(uint(pixel.x) * 1973u + uint(pixel.y) * 9277u + frameIndex * 26699u + 7u)")
            && legacy.contains("pcgHash(uint(p.x) * 1973u + uint(p.y) * 9277u + pc.frame * 26699u + 7u)"),
            "the same seed hash, including its +7 stream offset, so a side-by-side frame can be compared per sample");
    }

    /** Spatial reuse is the temporal merge over a neighbour, so it must go through the same primitive and the same caps. */
    @Test
    void theMergeUsesTheSamePrimitiveAndCapsAsTheTemporalStage() {
        String spatial = block(read(SPATIAL), "void run() {");
        String reservoir = read(RESERVOIR);
        assertTrue(spatial.contains("merged = restirDiSpatial(emitters(), merged, neighbour, receiver,\n"
            + "                RESTIR_DI_M_CAP, RESTIR_DI_SPATIAL_RATIO_CAP, seed);"),
            "one merge primitive, the legacy spatial ratio cap and the shared M cap");
        assertTrue(reservoir.contains("static const float RESTIR_DI_SPATIAL_RATIO_CAP = 5.0;"),
            "the firefly clamp that bounds a receiver change");
        String usable = block(read(SPATIAL), "bool neighbourUsable(DIReservoir neighbour)");
        assertTrue(usable.contains("min(neighbour.stats.x, RESTIR_DI_M_CAP) > 0.0"),
            "an empty or NaN-count neighbour contributes nothing");
        assertTrue(usable.contains("neighbour.source.z == uint(LightKind.Emitter)"),
            "this adapter only pools block-emitter records");
        // The spatial merge must not age a sample: the age lane is what the temporal gate reports, and a neighbour's age
        // belongs to another pixel.
        assertFalse(spatial.contains("source.y"), "spatial reuse does not touch the age lane");
        assertTrue(block(read(RESERVOIR), "DIReservoir restirDiSpatial<S : ILightSampler>").contains("return mergeReservoirs(current, neighbor, evaluation.targetPdf,\n        ratioCap, mCap, seed);"),
            "and the shared helper keeps that property: only the temporal variant increments it");
        assertTrue(reservoir.contains("merged.source.y = min(merged.source.y + 1u, 0xFFFFu);"),
            "which is the temporal stage's line, not the spatial one");
    }

    /**
     * A neighbour is imported only after it has proved it is the same surface. Every rejection the legacy kernel applies
     * has to be there, because the cost of dropping one is a torch lighting the room behind the wall it stands in.
     */
    @Test
    void aNeighbourIsProvenAgainstTheSurfaceBeforeItsLightIsImported() {
        String spatial = block(read(SPATIAL), "void run() {");
        assertTrue(spatial.contains("if (!active() || !blockEmitterSpatialWriter(pixel, phase))"),
            "only the checker colour this dispatch owns writes");
        assertTrue(spatial.contains("if (!ownRecord(own))"),
            "a pixel with no record of this frame is skipped: its slot still holds an older frame's receiver");
        String own = block(read(SPATIAL), "bool ownRecord(DIReservoir own)");
        assertTrue(own.contains("min(own.stats.x, RESTIR_DI_M_CAP) > 0.0 && isFinite(own.sample.xyz)"),
            "and having a record means a positive capped count and a usable sample");
        assertTrue(spatial.contains("if (neighbourPixel.x < 0 || neighbourPixel.y < 0\n"
            + "                || neighbourPixel.x >= int(size.x) || neighbourPixel.y >= int(size.y))"),
            "the offset can leave the render extent");
        assertTrue(spatial.contains("if (neighbourSlot >= diReservoirCount(reservoirs))"),
            "and a host that bound a smaller ring must not be read past it");
        assertTrue(spatial.contains("if (dot(reservoirReceiverNormal(neighbour), normal) < SPATIAL_NORMAL_DOT_MIN)"),
            "the neighbour's normal is unpacked from its own record, not guessed");
        assertTrue(spatial.contains("if (abs(dot(neighbour.receiver.xyz - position, normal)) > planeTolerance)"),
            "and its receiver has to lie on this pixel's plane");
        String usable = block(read(SPATIAL), "bool neighbourUsable(DIReservoir neighbour)");
        assertTrue(usable.contains("isFinite(neighbour.sample.xyz) && isFinite(neighbour.receiver.xyz)"),
            "a NaN position would pass the gates as NaN and then poison the merged record");
    }

    /**
     * The in-place update is the reason for the two dispatches: a writer reads only cells of the opposite checker colour,
     * so no thread can read a record another thread of the same dispatch is rewriting. Losing that property is a heisenbug
     * that only appears at high resolutions.
     */
    @Test
    void theCheckerDisciplineIsWhatMakesTheInPlaceUpdateRaceFree() {
        String slang = read(SPATIAL);
        String legacy = read(LEGACY);
        assertTrue(slang.contains("return ((pixel.x + pixel.y) & 1) == int(phase);")
            && legacy.contains("if (((p.x + p.y) & 1) != phase)"),
            "the writer set is one checker colour");
        String offset = block(slang, "int2 blockEmitterSpatialOffset(inout uint seed, int radius)");
        assertTrue(offset.contains("if (((offset.x + offset.y) & 1) == 0)")
            && offset.contains("offset.x += offset.x >= 0 ? 1 : -1;"),
            "and the offset is pushed onto the opposite colour, so a read never hits a cell this dispatch writes");
        assertTrue(legacy.contains("if (((off.x + off.y) & 1) == 0)")
            && legacy.contains("off.x += off.x >= 0 ? 1 : -1;"), "as in the legacy kernel");
        assertEquals(2, RtSpatialReuseSlangPass.PHASES, "two colours, hence two dispatches per frame");
        assertTrue(slang.contains("for (uint draw = 0u; draw < SPATIAL_DRAWS; draw += 1u)"),
            "the draws are the legacy count, not a loop over a disk");
    }

    /** This stage pools distributions; visibility stays the shading pass's job. A stray shadow ray here would double the cost. */
    @Test
    void theSpatialStageTracesNoRays() {
        String spatial = read(SPATIAL);
        String pass = read(PASS);
        assertFalse(spatial.contains("ITraversal") || spatial.contains("traceVisibility")
            || spatial.contains("RayQuery"), "no traversal type may reach this module");
        assertFalse(pass.contains("accelerationStructure") && pass.contains("RaytracingAccelerationStructure"),
            "the entry pass binds no acceleration structure");
        assertFalse(spatial.contains("traversal"), "and does not import one");
        assertTrue(read(LEGACY).contains("No shadow rays here"),
            "the legacy kernel documents the same split, so this is a contract and not an accident");
    }

    /**
     * The merged record is read by next frame's temporal import, so the receiver lanes describe the surface that is going
     * to shade it and must survive the merge; and a non-finite winner must not be published at all.
     */
    @Test
    void aMergedRecordKeepsItsOwnReceiverAndRefusesANonFiniteWinner() {
        String spatial = block(read(SPATIAL), "void run() {");
        assertTrue(spatial.contains("merged.receiver = own.receiver;\n        merged.stats.w = own.stats.w;"),
            "the receiver position, packed normal and flags stay this pixel's: the merge primitive never writes them");
        assertTrue(spatial.contains("if (!won) {"), "nothing won means the pre-spatial record keeps its weight");
        assertTrue(spatial.contains("if (!diSamePosition(merged.sample.xyz, own.sample.xyz))"),
            "and winning is detected on the stored sample, not on a flag the merge would have to maintain");
        assertTrue(spatial.contains("if (!(merged.stats.y >= 0.0) || !(merged.stats.x > 0.0) || !(merged.sample.w > 0.0)\n"
            + "            || !isFinite(merged.sample.xyz) || !isFinite(merged.stats.xyz))"),
            "a NaN count passes a plain comparison, so every lane is tested before the store");
        assertTrue(read(LEGACY).contains("if (!(wsum >= 0.0) || !(count > 0.0) || !(winPhat > 0.0)"),
            "the legacy kernel rejects the same set");
        assertTrue(spatial.contains("reservoirs[ownSlot] = merged;"), "and only then writes the slot");
    }

    /**
     * The one field a later reader cannot recover from the surface: whether the receiver was itself emissive. Without the
     * bit, spatial reuse re-resolves a neighbour's sample at a glowing face through a sampler that has lost its self-quad
     * guard, and a wall of lamps lights itself.
     */
    @Test
    void theSelfEmissiveGuardTravelsInTheRecordForTheReader() {
        String reservoir = read(RESERVOIR);
        String writer = read(DI);
        String spatial = read(SPATIAL);
        assertTrue(reservoir.contains("static const uint DI_RECEIVER_SELF_EMISSIVE = 1u << 1u;")
            && reservoir.contains("static const uint DI_RECEIVER_NORMAL_DOWN = 1u << 0u;"),
            "two distinct bits of the receiver-flag lane");
        assertTrue(writer.contains("current.stats.w = (normal.z < 0.0 ? DI_RECEIVER_NORMAL_DOWN : 0u)\n"
            + "            | (receiver.emission >= 0.5 ? DI_RECEIVER_SELF_EMISSIVE : 0u);"),
            "the stage that has the material at hand publishes both");
        assertTrue(spatial.contains("float emission = (uint(own.stats.w + 0.5) & DI_RECEIVER_SELF_EMISSIVE) != 0u ? 1.0 : 0.0;")
            && spatial.contains("LightReceiver receiver = lightReceiver(position, normal, emission);"),
            "the reader restores them before building the receiver it re-evaluates through");
        assertTrue(spatial.contains("float3 normal = reservoirReceiverNormal(own);"),
            "including the normal, whose z sign comes from the other bit");
    }

    /** The push block is the only contract between the host and this shader, so its bytes are pinned in both directions. */
    @Test
    void theHostPushBlockMatchesTheShaderStruct() {
        String pass = read(PASS);
        String host = read(HOST);
        structFieldsInOrder(pass);
        assertEquals(64, RtSpatialReuseSlangPass.PUSH_BYTES, "RestirSpatialPush is four words of state, one float4 and"
            + " a float plus its padding");
        assertTrue(host.contains("static final int PUSH_BYTES = 64;"), "and the host states it");
        assertTrue(host.contains("if (manifest.pushConstantBytes() != PUSH_BYTES)"),
            "refusing a shader that pushes a different block before the first dispatch");
        assertEquals(RtSpatialReuseSlangPass.SPATIAL_ENABLED, flag(pass, "RESTIR_SPATIAL_ENABLED"),
            "the enable bit the host promises to set");
        assertTrue(pass.contains("reuse.enabled = (pc.state.w & RESTIR_SPATIAL_ENABLED) != 0u ? 1u : 0u;"),
            "the branch is a flag, not a second shader");
        for (String[] lane : new String[][] {
            {"reuse.frameIndex = pc.state.x;", "0"},
            {"reuse.parity = pc.state.y;", "4"},
            {"reuse.phase = pc.state.z;", "8"}, }) {
            assertTrue(pass.contains(lane[0]), lane[0] + " must stay the lane the host writes at offset " + lane[1]);
        }
        ByteBuffer push = ByteBuffer.allocate(RtSpatialReuseSlangPass.PUSH_BYTES);
        for (int i = 0; i < push.capacity(); i++) {
            push.put(i, (byte) 0x5A);
        }
        new RtSpatialReuseSlangPass.Spatial(7, 1, 0, RtSpatialReuseSlangPass.SPATIAL_ENABLED,
            1.5f, -2.5f, 3.5f, 128f).writeTo(push);
        assertEquals(7, push.getInt(0), "frame index");
        assertEquals(1, push.getInt(4), "parity half the trace pass wrote");
        assertEquals(0, push.getInt(8), "checker phase");
        assertEquals(RtSpatialReuseSlangPass.SPATIAL_ENABLED, push.getInt(12), "flags");
        assertEquals(1.5f, push.getFloat(16), 0f, "camera-relative x");
        assertEquals(-2.5f, push.getFloat(20), 0f, "camera-relative y");
        assertEquals(3.5f, push.getFloat(24), 0f, "camera-relative z");
        assertEquals(128f, push.getFloat(32), 0f, "the trace pass's ray window");
        for (int i = 0; i < push.capacity(); i++) {
            assertTrue(push.get(i) != (byte) 0x5A, "offset " + i + " is never written: the host would push"
                + " uninitialised stack memory");
        }
    }

    private static void structFieldsInOrder(String pass) {
        String push = block(pass, "struct RestirSpatialPush {");
        int at = -1;
        for (String field : List.of("uint4  state", "float4 cameraRelative", "float  maxDistance", "float3 pad")) {
            int next = push.indexOf(field);
            assertTrue(next > at, "RestirSpatialPush must declare " + field + " in this order: " + push);
            at = next;
        }
    }

    /** The host binds the same views the trace pass binds, and the manifest has to agree before it dispatches them. */
    @Test
    void theHostSuppliesExactlyTheBindingsTheShaderDeclares() {
        String pass = read(PASS);
        String host = read(HOST);
        for (String binding : new String[] {"albedoAtlas", "materialAtlas", "normalAtlas", "frame", "emitters",
            "reservoirs"}) {
            assertTrue(bindingLine(pass, binding) >= 0, binding + " must be bound by number in the shader");
            assertTrue(host.contains("new Expected(\"" + binding + "\""), binding + " has no host expectation");
            assertTrue(host.contains("SlangProgram.Resource.sampled(\"" + binding + "\"")
                    || host.contains("SlangProgram.Resource.storageBuffer(\"" + binding + "\"")
                    || binding.equals("frame"),
                binding + " is expected but never supplied");
        }
        assertEquals(6, count(pass, "[[vk::binding("), "six bindings, and the manifest count is checked against them");
        assertTrue(host.contains("if (manifest.bindings().size() != EXPECTED.size())"),
            "a binding the host forgot to fill fails at load, not as a null descriptor");
        for (String[] pair : new String[][] {
            {"frame", "FRAME_CONSTANTS_BYTES"}, {"emitters", "EMITTER_BYTES"}, {"reservoirs", "RESERVOIR_BYTES"} }) {
            assertTrue(host.contains("requireElementBytes(manifest, \"" + pair[0] + "\", "
                + "RtPathTraceSlangPass." + pair[1] + ")"),
                pair[0] + " holds " + pair[1] + ": the stride the host allocated has to be the stride the shader reads");
        }
        assertTrue(pass.contains("RWStructuredBuffer<DIReservoir> reservoirs"),
            "the ring is the same 64-byte record the trace pass writes");
    }

    /**
     * The size of the render is read from the frame-constant block the trace pass itself uploaded, and the push carries no
     * extent at all: a pooled record must never be indexed against a resolution that no longer matches the ring.
     */
    @Test
    void theRenderComesFromTheFrameBlockTheTracePassUploaded() {
        String pass = read(PASS);
        String host = read(HOST);
        String game = read(GAME_PASS);
        assertTrue(pass.contains("uint2 size = f.camera.size.xy;"), "the shader takes the extent from FrameConstants");
        assertTrue(pass.contains("if (f.camera.size.x == 0u || f.camera.size.y == 0u)"),
            "and refuses an uninitialised block instead of computing a zero ring index");
        assertFalse(block(pass, "struct RestirSpatialPush {").contains("width")
                || block(pass, "struct RestirSpatialPush {").contains("height"),
            "the push carries no extent: there is only one source for the render size, so a pooled record cannot be"
                + " indexed against a resolution the ring no longer has");
        assertTrue(game.contains("spatialReuse.record(cmd, new RtPathTraceSlangPass.Atlases(views.atlas(), views.material(),\n"
            + "                    views.normal(), sampler), emitters, reservoirRange, trace.frameResource(), width, height,"),
            "the host binds the trace pass's own frame upload");
        assertTrue(host.contains("record(VkCommandBuffer cmd, RtPathTraceSlangPass.Atlases atlases,"),
            "and takes the resources from the trace pass's own records");
        assertTrue(host.contains("program.groups(width, 0), program.groups(height, 1), 1"),
            "dispatched with x from width and y from height");
        assertTrue(host.contains("VkBarriers.computeToCompute(cmd);"),
            "the stage orders itself: phase two must not read what phase one is still merging");
        assertEquals(2, count(block(host, "record(VkCommandBuffer cmd, RtPathTraceSlangPass.Atlases atlases,"),
            "VkBarriers.computeToCompute(cmd);"),
            "one barrier between the two phases and a trailing one, so the pooled records are available to the batch "
                + "that reads them next frame");
    }

    /**
     * The pass is an extra stage, not a precondition: a machine whose AOT artifact is missing or whose manifest disagrees
     * has to keep running temporal reuse, which is what the legacy path did when its spatial kernel was compiled out.
     */
    @Test
    void aMissingSpatialKernelDegradesToTemporalOnly() {
        String host = read(HOST);
        String game = read(GAME_PASS);
        assertTrue(host.contains("static Optional<RtSpatialReuseSlangPass> create(SlangRuntime runtime, Logger log)"),
            "creation yields empty with the reason in the log");
        assertTrue(host.contains("log.info(\"[VKB-RT] spatial reuse off: {}\""),
            "as an info line: the renderer is still working");
        assertTrue(host.contains("if (program != null) {\n                program.close();"),
            "and a program that got registered and then failed the manifest check is closed again");
        assertTrue(game.contains("this.spatialReuse = RtSpatialReuseSlangPass.create(runtime, LOGGER).orElse(null);"),
            "assigned outside the try that builds the trace pass, so a missing stage cannot fail path tracing");
        assertTrue(game.contains("return spatialReuse != null && diActive() && RtSettings.pathRestirSpatial();"),
            "the mode log reports the stage only when it exists, is enabled and its parent is on");
        assertTrue(game.contains("return spatialRequested() && diFlags != 0 && emitterCount > 0;"),
            "a frame needs records of this convention and a list to pool before the stage runs");
        assertTrue(game.contains("if (spatialReuse != null && spatialActive(diFlags, emitterCount) && reservoirs != null)"),
            "and the dispatch re-checks the ring it is about to bind");
        assertTrue(game.contains("spatialReuse.releaseRecording(commandBuffer);")
            && game.contains("spatialReuse.close();"),
            "its recordings are released with the frame's, or the core keeps the descriptor pool alive");
    }

    /** Spatial reuse merges the reservoirs the trace pass writes, so it cannot be switched on without it. */
    @Test
    void theStageIsAChildOfTheReuseItPools() {
        String game = read(GAME_PASS);
        String spatial = read(SPATIAL);
        assertTrue(game.contains(": (reservoirHistoryWritten ? \"temporal\" : \"fresh\")"
            + " + (spatialRequested() ? \"+spatial\" : \"\");"),
            "the frame description only reports spatial on top of a reservoir stage that is running");
        assertTrue(spatial.contains("diReservoirSlot(pixel, size, parity) < diReservoirCount(reservoirs)"),
            "the spatial reader bounds the slot through the same helper the temporal stage uses");
        assertTrue(spatial.contains("uint ownSlot = diReservoirSlot(pixel, size, parity);"),
            "and addresses the half the trace pass wrote, not the other one");
        assertTrue(game.contains("params.frame, diParity, params.emitterCamRelX, params.emitterCamRelY,"),
            "and passes that parity, not its successor");
    }

    private static int bindingLine(String pass, String name) {
        for (String line : pass.split("\n")) {
            if (line.contains("]] ") && line.contains(" " + name + ";")) {
                String[] parts = line.split("\\[\\[vk::binding\\(");
                if (parts.length > 1) {
                    return Integer.parseInt(parts[1].substring(0, parts[1].indexOf(',')));
                }
            }
        }
        return -1;
    }

    private static int flag(String passSource, String name) {
        String line = lineContaining(passSource, "static const uint " + name);
        int shift = line.indexOf("<<");
        assertTrue(shift >= 0, name + " must be a bit: " + line);
        String after = line.substring(shift + 2).trim();
        int end = 0;
        while (end < after.length() && Character.isDigit(after.charAt(end))) {
            end++;
        }
        return 1 << Integer.parseInt(after.substring(0, end));
    }

    private static int intNumber(String source, String name) {
        int at = source.indexOf(name + " = ");
        assertTrue(at >= 0, "missing constant: " + name);
        String after = source.substring(at + name.length() + 3);
        int end = 0;
        while (end < after.length() && Character.isDigit(after.charAt(end))) {
            end++;
        }
        assertTrue(end > 0, name + " is not an integer literal: " + after);
        return Integer.parseInt(after.substring(0, end));
    }

    private static String lineContaining(String source, String needle) {
        for (String line : source.split("\n")) {
            if (line.contains(needle)) {
                return line.trim();
            }
        }
        throw new AssertionError("missing line: " + needle);
    }

    private static int count(String source, String needle) {
        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf(needle, at);
            if (found < 0) {
                return hits;
            }
            hits++;
            at = found + needle.length();
        }
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

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
