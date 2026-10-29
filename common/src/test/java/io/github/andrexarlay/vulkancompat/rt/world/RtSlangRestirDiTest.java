package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReSTIR DI for the Slang path tracer is a port of numbers the legacy GLSL path has run on GPUs for months, wired to a
 * host that owns a second reservoir ring. String guards cannot prove a merge is unbiased, but they can prove the three
 * things that break silently: the constants the owner tuned, the space a stored light point is written and read in, and
 * the fact that the frame being written never aliases the frame being read.
 */
class RtSlangRestirDiTest {

    private static final String REFERENCE_BACKEND = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/ReferencePathTraceBackend.java";
    private static final String DI = "src/main/resources/shaders/slang/restir/di_block_emitters.slang";
    private static final String RESERVOIR = "src/main/resources/shaders/slang/restir/restir_di.slang";
    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";
    private static final String PASS = "src/main/resources/shaders/slang/passes/path_trace.slang";
    private static final String ENTRY = "src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang";
    private static final String DISPLAY = "src/main/resources/shaders/slang/passes/path_display.slang";
    private static final String CONTRACT = "src/main/resources/shaders/slang/contracts/light.slang";
    private static final String LEGACY = "src/main/resources/shaders/glsl/rt_common_emitters.glsl";
    /** The gradle pin lives in the root project, one level above the {@code common} working directory of a test JVM. */
    private static final String GRADLE = "../gradle/slang.gradle";
    private static final String HOST_PASS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtPathTraceSlangPass.java";
    private static final String GAME_PASS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSlangPathPass.java";
    private static final String CONFIG = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtConfig.java";
    private static final String FRAME_PARAMS = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtFrameParams.java";

    /** The owner tunes these by eye against real scenes; a retune has to move both files or neither. */
    @Test
    void theMergeNumbersAreTheLegacyOnes() {
        String slang = read(RESERVOIR);
        String legacy = read(LEGACY);
        for (String[] pair : new String[][] {
            {"RESTIR_M_CAP = 20.0", "RESTIR_DI_M_CAP = 20.0"},
            {"RESTIR_TEMPORAL_RATIO_CAP = 8.0", "RESTIR_DI_TEMPORAL_RATIO_CAP = 8.0"},
            {"RESTIR_SPATIAL_RATIO_CAP = 5.0", "RESTIR_DI_SPATIAL_RATIO_CAP = 5.0"},
            {"RESTIR_OCCLUSION_DECAY = 0.25", "RESTIR_DI_OCCLUSION_DECAY = 0.25"} }) {
            assertTrue(legacy.contains(pair[0]), "the legacy path moved " + pair[0] + ": re-tune the port too");
            assertTrue(slang.contains(pair[1]), "the Slang port dropped " + pair[1]);
        }
        String gates = read(DI);
        assertTrue(gates.contains("static const float DI_DISTANCE_TOLERANCE_RELATIVE = 0.05;")
            && gates.contains("static const float DI_DISTANCE_TOLERANCE_ABSOLUTE = 0.1;"),
            "the legacy receiver-distance tolerance: 5 percent plus a tenth of a block");
        assertTrue(gates.contains("static const float DI_NORMAL_DOT_STATIC = 0.8;")
            && gates.contains("static const float DI_NORMAL_DOT_MOVING = 0.6;"),
            "a moving camera has to prove the surface match, a stationary one may keep a turned normal");
    }

    /** The 64-byte record is shared with {@code restir_di.slang}, so the host, the gradle pin and the shader must agree. */
    @Test
    void theRecordIsTheOneTheHostAllocates() {
        String record = block(read(RESERVOIR), "struct DIReservoir {");
        assertEquals(4, count(record, "float4 ") + count(record, "uint4 "),
            "sample, stats, source, receiver: four 16-byte lanes");
        String gradle = read(GRADLE);
        assertTrue(gradle.contains("DIReservoir: [size: 64"), "gradle pins DIReservoir at 64 bytes");
        for (String offset : new String[] {"sample: 0", "stats: 16", "source: 32", "receiver: 48"}) {
            assertTrue(gradle.contains(offset), "gradle pins the DIReservoir lane " + offset);
        }
        assertEquals(64, RtPathTraceSlangPass.RESERVOIR_BYTES, "the host allocates the same record");
        assertTrue(read(HOST_PASS).contains("requireElementBytes(manifest, \"reservoirs\", RESERVOIR_BYTES)"),
            "and re-checks it against the generated manifest before the first dispatch");
    }

    /**
     * Two halves, so the dispatch writing frame N never touches the records of frame N-1 that the same dispatch reads -
     * the writes of a compute dispatch are unordered across threads.
     */
    @Test
    void theRingNeverReadsTheHalfItWrites() {
        String di = read(DI);
        assertTrue(di.contains("return parity * size.x * size.y + uint(pixel.y) * size.x + uint(pixel.x);"),
            "the ring is parity-major, so one multiplication moves the whole frame");
        assertTrue(di.contains("uint slot = diReservoirSlot(historyPixel, size, parity ^ 1u);"),
            "history comes from the other half");
        assertTrue(di.contains("reservoirs[reservoirSlot()] = current;"),
            "the record of this frame goes to this frame's half");
        String game = read(GAME_PASS) + "\n" + read(REFERENCE_BACKEND);
        assertTrue(game.contains("long need = pixels * 2L * RtPathTraceSlangPass.RESERVOIR_BYTES;"),
            "the host reserves both halves");
        assertTrue(game.contains("int diParity = reservoirFrame & 1;"), "the host alternates the half");
        assertTrue(game.contains("reservoirFrame++;"),
            "and only advances it after a dispatch that really wrote records");
        assertTrue(game.contains("nativeBuffers.createDeviceLocal(need,"),
            "the Slang ring is its own buffer: the GLSL records have a different layout and a mode switch must not"
                + " cross-feed them");
    }

    /**
     * A reused sample must belong to the surface now shading this pixel. Every gate the legacy path uses has to survive
     * the port, or a torch seen through a window keeps lighting the wall behind it.
     */
    @Test
    void aHistoryRecordIsProvenAgainstTheSurfaceItBelongsTo() {
        String history = block(read(DI), "bool diLoadHistory(");
        assertTrue(history.contains("if (slot >= diReservoirCount(reservoirs))"),
            "a host that bound a dummy record must not be read past it");
        assertTrue(history.contains("min(stored.stats.x, RESTIR_DI_M_CAP) > 0.0"), "an empty reservoir carries no sample");
        assertTrue(history.contains("stored.source.z != uint(LightKind.Emitter)"),
            "this adapter only reuses block emitters");
        assertTrue(history.contains("abs(length(stored.receiver.xyz) - previousDistance) > tolerance")
            && history.contains("length(stored.receiver.xyz - previousReceiver) > tolerance"),
            "the distance and the position of the old receiver both have to match");
        assertTrue(history.contains("float normalDot = dot(reservoirReceiverNormal(stored), receiverNormal);"),
            "the normal travels packed in the record and is unpacked for the test");
        assertTrue(history.contains("normalDot < (cameraStatic ? DI_NORMAL_DOT_STATIC : DI_NORMAL_DOT_MOVING)"),
            "the cut-off depends on whether the camera can be trusted to have stayed put");
    }

    /**
     * The one convention that cannot be guessed twice: the reservoir and the running mean reproject the same surface, so
     * they must use the same space, the same delta and the same previous matrix.
     */
    @Test
    void theReuseReprojectsExactlyLikeTheDisplayPass() {
        String di = read(DI);
        String display = read(DISPLAY);
        assertTrue(di.contains("previous = receiverPosition + cameraDelta;"),
            "the world of a frame is anchored at that frame's camera, so the previous position is the current one plus"
                + " the camera delta");
        assertTrue(display.contains("float3 previous = position + cameraDelta + surfaceMotion;"),
            "the running mean reprojects the same way, plus one term the reservoir has no use for: the displacement of"
                + " the surface itself, which only the tracer's own geometry can name");
        assertTrue(di.contains("mul(previousViewProjection, float4(previous, 1.0))"),
            "and through the same matrix");
        assertTrue(display.contains("mul(f.camera.prevViewProjection, float4(previous, 1.0))"),
            "which is the frame constant both passes bind");
        assertTrue(di.contains("stored.sample.xyz -= cameraDelta;"),
            "a stored light point goes the other way: back into the space of the frame that reads it");
        assertTrue(di.contains("ndc.y = -ndc.y;"), "with the same flipY as the display pass");
        // ... and the one term the reservoir used to be missing. A mob part's own history lives at the pixel that part's
        // pixels were in, so merging without the shift would import whatever light stood there a frame ago - a bias,
        // not just noise. The tracer already knows the displacement, because the surface it committed is the receiver.
        assertTrue(di.contains("diReprojectReceiver(receiver.position + receiver.motion, cameraDelta,"),
            "the receiver is shifted by its own motion before the camera delta turns it into last frame's position");
        String tracer = read(TRACER);
        assertTrue(tracer.contains("receiver.motion = result.primaryMotion.xyz;"),
            "filled from the primary surface the tracer just committed, so no second convention is invented");
        assertTrue(tracer.contains("receiver.primary = result.surfaceHits == 1u;"),
            "and that is the same surface the record belongs to");
        String receiverStruct = block(read(CONTRACT), "struct LightReceiver {");
        assertTrue(receiverStruct.contains("float3 motion;"),
            "the carrier itself carries the term, so a receiver reaching a reuse pass has an answer to the question");
    }

    /**
     * The path tracer owns the shadow ray, so the reservoir is written before visibility is known and the decay happens
     * afterwards. Splitting the two is what keeps the record alive behind a new occluder.
     */
    @Test
    void anOccludedFrameFadesItsRecordInsteadOfDroppingIt() {
        String di = read(DI);
        String confirm = block(di, "void reuseConfirm(bool occluded) {");
        assertTrue(confirm.contains("if (!occluded || !reuseActive())"),
            "a visible sample leaves the published weight alone");
        assertTrue(confirm.contains("DIReservoir record = reservoirs[ownSlot];"),
            "and the feedback re-reads the slot, because nothing may be carried between the two calls");
        assertTrue(confirm.contains("record.stats.y *= RESTIR_DI_OCCLUSION_DECAY;"),
            "the weight sum decays, the sample stays: the legacy four-frame fade-out");
        String tracer = read(TRACER);
        assertTrue(tracer.contains("reuse.reuseConfirm(!reusedTraced || reusedOccluded);"),
            "a sample that could not even be traced is treated as occluded, so a record that lost its light fades too");
        assertFalse(di.contains("Traversal"),
            "the adapter must not trace: the shadow ray belongs to the path tracer");
    }

    /** Sky, miss and displaced pixels have no reservoir; leaving the old record standing would resurrect a gone light. */
    @Test
    void aPixelWithoutASurfacePublishesAnEmptyRecord() {
        String clear = block(read(DI), "void reuseClear() {");
        assertTrue(clear.contains("reservoirs[reservoirSlot()] = emptyReservoir();"),
            "the clear publishes an empty record of the current frame");
        String tracer = read(TRACER);
        assertTrue(tracer.contains("if (!reuseHandled && reuse.reuseActive()) {"),
            "the loop tail covers every path that never reached the primary NEE");
        assertTrue(tracer.contains("reuse.reuseClear();"), "and the early return covers the invalid primary ray");
        assertTrue(read(CONTRACT).contains("void reuseClear();"),
            "it is part of the contract, so a sampler cannot be wired without it");
    }

    /**
     * The whole point of the port: the pair (radiance, pdf) a vertex receives may not depend on whether the light came
     * from this frame's batch or from a reused record. Both go through the same estimator.
     */
    @Test
    void aReusedSampleIsEstimatedExactlyLikeAFreshDraw() {
        String reuse = block(read(DI), "LightSample reuseSample(LightReceiver receiver, inout uint seed) {");
        assertTrue(reuse.contains("LightEvaluation evaluation = emitters().evaluate(receiver, historySample);"),
            "the stored point is re-resolved through the plain sampler, so p-hat, pdf and Le are one code path");
        assertTrue(reuse.contains("if (evaluation.valid && evaluation.targetPdf > 0.0 && evaluation.sourcePdf > 0.0)"),
            "a record whose quad vanished from the list is dropped instead of being weighted by zero");
        assertTrue(reuse.contains("imported.solidAnglePdf = evaluation.sourcePdf;"),
            "the import fills the same estimate struct a winner fills");
        assertTrue(reuse.contains("estimate = blockEmitterEstimateFromWinner(winner, atlas);"),
            "and the fresh path fills it from the batch winner");
        assertTrue(reuse.contains("return blockEmitterEstimateSample(estimate, current.stats.y, current.stats.x,"
            + " receiver);"),
            "one estimator, fed with the merged weight sum and the merged M - reuse means reusing the accumulation too");
        assertTrue(reuse.contains("current = mergeReservoirs(current, history, evaluation.targetPdf,")
            && reuse.contains("RESTIR_DI_TEMPORAL_RATIO_CAP, RESTIR_DI_M_CAP, seed)"),
            "the import is capped by the legacy ratio, so one bright history cannot swallow this frame's draws");
        String entry = read(ENTRY);
        assertTrue(entry.contains("lights.emittersReusedAtPrimary = directReuse.enabled != 0u;"),
            "with the reservoir on, the composite stops drawing the emitter branch a second time at the primary vertex");
    }

    /** One shader, two branches: the switch is host state, not a second compiled program. */
    @Test
    void theBranchIsAFlagAndNotAShaderVariant() {
        String entry = read(ENTRY);
        assertTrue(entry.contains("static const uint PATH_TRACE_DI_ENABLED      = 1u << 0u;"),
            "the shader names the bit the host promises to set");
        assertTrue(entry.contains("directReuse.enabled = (pc.diState.x & PATH_TRACE_DI_ENABLED) != 0u ? 1u : 0u;"),
            "and the sampler is built either way");
        String game = read(GAME_PASS) + "\n" + read(REFERENCE_BACKEND);
        assertEquals(RtSlangPathPass.DI_FLAG_ENABLED, flag(entry, "PATH_TRACE_DI_ENABLED"), "enabled bit");
        assertEquals(RtSlangPathPass.DI_FLAG_HISTORY, flag(entry, "PATH_TRACE_DI_HISTORY"), "history bit");
        assertEquals(RtSlangPathPass.DI_FLAG_STATIC, flag(entry, "PATH_TRACE_DI_STATIC"), "static bit");
        assertEquals(RtSlangPathPass.DI_FLAG_FLIP_Y, flag(entry, "PATH_TRACE_DI_FLIP_Y"), "flipY bit");
        assertTrue(game.contains("diFlags = dispatch.diFlags() | (reservoirHistoryWritten ? DI_FLAG_HISTORY : 0)"),
            "the host never claims a history it did not write");
        assertTrue(game.contains("if ((dispatch.diFlags() & DI_FLAG_ENABLED) != 0 && emitterCount > 0 && reservoirs != null)"),
            "reuse needs the ring, the player's switch and a list to reuse: without emitters there is nothing to carry over");
        assertTrue(read(CONFIG).contains("\"vkbridge.rt.restirDi\", \"true\""),
            "the A/B switch is there for the owner, on by default");
        assertTrue(game.contains("return RtConfig.SLANG_BLOCK_EMITTERS && RtConfig.SLANG_RESTIR_DI;"),
            "turning the emitter list off turns the reuse off with it, in one place");
    }

    /** The host has to refuse a push that enables the branch without a ring, and bind a dummy when it does not. */
    @Test
    void theHostGuardsTheBindingItPromises() {
        String host = read(HOST_PASS);
        assertTrue(host.contains("the push enables ReSTIR DI but no reservoir ring is bound"),
            "a flag without a buffer fails at record time, not as a device-side out-of-bounds read");
        assertTrue(host.contains("new Expected(\"reservoirs\", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER)"),
            "the manifest is checked for the binding the host fills");
        assertTrue(host.contains("reservoirDummy = zeroedBuffer(RESERVOIR_BYTES);"),
            "with the branch off a one-record zeroed buffer still satisfies the descriptor");
        String game = read(GAME_PASS) + "\n" + read(REFERENCE_BACKEND);
        assertTrue(game.contains("RtPathTraceSlangPass.Reservoirs reservoirRange ="
            + " RtPathTraceSlangPass.Reservoirs.NONE;"),
            "and the game pass starts from that empty range every frame");
        assertTrue(game.contains("VK10.vkCmdFillBuffer(cmd, reservoirs.handle(), 0L, reservoirs.size(), 0)"),
            "a reset zeroes both halves, which is the only way to drop history a change of lights invalidates");
        assertTrue(read(FRAME_PARAMS).contains("public long emitterFingerprint;"),
            "the frame carries the identity of the light list the ring is keyed on");
    }

    /**
     * The normal of a record travels in one float lane, so two mistakes are silent and total: packing with a numeric
     * conversion instead of a bitcast, and reading the signed 16-bit halves back as unsigned. Both leave the reservoir
     * readable and the merge "working" - only the temporal gate has stopped comparing anything. A wall facing -x is half
     * of all surfaces, so the second one is not an edge case.
     */
    @Test
    void thePackedNormalRoundTripsThroughTheFloatLane() {
        String reservoir = read(RESERVOIR);
        String pack = block(reservoir, "float reservoirPackNormalXY(float2 xy) {");
        assertTrue(pack.contains("return asfloat(bits);"),
            "the only bitcast into a float lane is asfloat: asuint of a uint is the numeric int-to-float conversion, so"
                + " a packer using it stores a real number, not the bits the unpacker reads");
        assertFalse(pack.contains("return asuint("), "the packer must not convert the packed word numerically");
        String unpack = block(reservoir, "float2 reservoirUnpackNormalXY(float packed) {");
        assertTrue(unpack.contains("asuint(packed)"), "reading the lane back is the inverse bitcast");
        assertTrue(unpack.contains("reservoirUnpackSnorm16(bits & 0xFFFFu)")
                && unpack.contains("reservoirUnpackSnorm16(bits >> 16u)"),
            "both halves go through the sign extension, not through a plain unsigned float conversion");
        String sign = block(reservoir, "float reservoirUnpackSnorm16(uint half) {");
        assertTrue(sign.contains("signedHalf >= 32768 ? signedHalf - 65536 : signedHalf"),
            "0xFFFF is -1: the snorm pair is signed");

        // The same arithmetic the shader performs, checked on the directions the reservoir actually stores.
        for (float[] normal : new float[][] { { 0.0f, 0.0f }, { 1.0f, 0.0f }, { -1.0f, 0.0f }, { 0.0f, -1.0f },
            { -0.7071068f, -0.7071068f }, { 0.5f, -0.25f }, { 0.9999999f, -0.0000001f } }) {
            float packed = packNormalXy(normal[0], normal[1]);
            float x = unpackSnorm16(packed, 0);
            float y = unpackSnorm16(packed, 16);
            assertEquals(normal[0], x, 1.0f / 32767.0f + 1.0e-6f, "packed x of " + java.util.Arrays.toString(normal));
            assertEquals(normal[1], y, 1.0f / 32767.0f + 1.0e-6f, "packed y of " + java.util.Arrays.toString(normal));
            assertTrue(Math.abs(x) <= 1.0001f && Math.abs(y) <= 1.0001f,
                "a negative component must not decode above one, or the reconstructed z becomes imaginary");
        }
    }

    /** The mirror of {@code reservoirPackNormalXY}: two's-complement halves in one word, moved into a float by bits. */
    private static float packNormalXy(float x, float y) {
        int quantisedX = (int) Math.floor(Math.max(-1.0f, Math.min(1.0f, x)) * 32767.0f + 0.5f);
        int quantisedY = (int) Math.floor(Math.max(-1.0f, Math.min(1.0f, y)) * 32767.0f + 0.5f);
        int bits = ((quantisedY & 0xFFFF) << 16) | (quantisedX & 0xFFFF);
        return Float.intBitsToFloat(bits);
    }

    private static float unpackSnorm16(float packed, int shift) {
        int half = (Float.floatToRawIntBits(packed) >>> shift) & 0xFFFF;
        return (half >= 32768 ? half - 65536 : half) / 32767.0f;
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
