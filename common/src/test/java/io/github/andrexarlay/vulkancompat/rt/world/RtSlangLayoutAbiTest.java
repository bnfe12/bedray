package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The std430 blocks the typed renderer hands between Java and Slang, parsed from the declarations with the rules the
 * compiler applies and compared with the host's own constants. The deleted {@code RtStatsLayoutTest} did this for the
 * GLSL {@code StatsBuf}, the push block and the reservoir/emitter strides; the typed passes have their own blocks, so
 * the same one-sided-edit failure is pinned here.
 *
 * <p>{@code gradle/slang.gradle} already reflects the compiled manifests and rejects a size drift at build time. This
 * file is the second, compiler-free half: it reads the source a checkout can see and pins the Java mirror beside it, so
 * a reorder that keeps the size (the dangerous kind) still fails a unit test.
 *
 * <p>The {@code StatsBuf} tail needs its own answer. Since the legacy pipeline was deleted no typed pass has read the
 * old 65-field frame slot: {@code passes/frame_stats.slang} binds exactly the four counter words at its head, and
 * {@code bindings.slang} is imported only by the two module surfaces. The Java tail in {@link RtStatsLayout} is
 * therefore host-side scratch, not an ABI a shader can misread; the reachability checks below prove that boundary
 * instead of pretending the dead declaration is still a live layout.
 */
class RtSlangLayoutAbiTest {

    private static final String SLANG = "src/main/resources/shaders/slang/";
    private static final String BINDINGS = SLANG + "bindings.slang";

    @Test
    void frameConstantsMatchesTheHostPacker() throws IOException {
        Layout frame = layout("contracts/frame.slang", "FrameConstants");
        assertEquals(RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES, frame.size,
            "the block the host allocates is the block the contract declares");
        assertEquals(RtPathTraceSlangPass.Frame.CAMERA, frame.offset("camera"));
        assertEquals(RtPathTraceSlangPass.Frame.SUN, frame.offset("sun"));
        assertEquals(RtPathTraceSlangPass.Frame.ATMOSPHERE, frame.offset("atmosphere"));
        assertEquals(RtPathTraceSlangPass.Frame.EXPOSURE, frame.offset("exposure"));
        assertEquals(RtPathTraceSlangPass.Frame.FRAME, frame.offset("frame"));
        assertEquals(416, frame.field("camera").size);
        assertEquals(64, frame.field("sun").size);
        assertEquals(80, frame.field("atmosphere").size);
        assertEquals(32, frame.field("exposure").size);
        assertEquals(16, frame.field("frame").size, "the frame word is one uint4");
    }

    @Test
    void pathTracePushIsThe112ByteHotLoopBlock() throws IOException {
        Layout push = layout("pathtrace/path_trace_entry.slang", "PathTracePush");
        assertEquals(RtPathTraceSlangPass.PUSH_BYTES, push.size,
            "a same-size reorder still moves a field the Java writer fills by offset");
        assertEquals(16, push.fields.size(), "the push block's member list");
        assertEquals(48, push.offset("emitterCameraRelative"));
        assertEquals(64, push.offset("diCameraDelta"));
        assertEquals(76, push.offset("diCameraDelta") + 12,
            "emitterListRange arrives in the float4's w, the offset the host writes");
        assertEquals(80, push.offset("diState"));
        assertEquals(96, push.offset("entityState"));
    }

    @Test
    void diReservoirIsFourVec4Records() throws IOException {
        Layout reservoir = layout("restir/restir_di.slang", "DIReservoir");
        assertEquals(RtPathTraceSlangPass.RESERVOIR_BYTES, reservoir.size,
            "the host allocates the ring with this stride");
        assertEquals(4, reservoir.fields.size());
        assertEquals(0, reservoir.offset("sample"));
        assertEquals(16, reservoir.offset("stats"));
        assertEquals(32, reservoir.offset("source"));
        assertEquals(48, reservoir.offset("receiver"));
    }

    @Test
    void blockEmitterRecordIsSixVec4() throws IOException {
        String emitters = read(SLANG + "light/block_emitters.slang");
        Layout record = parseLayout(emitters, "BlockEmitterRecord");
        assertEquals(RtPathTraceSlangPass.EMITTER_BYTES, record.size);
        assertEquals(RtEmitters.BYTES, record.size, "the Java record writer and the shader agree on the stride");
        assertEquals(6, record.fields.size(), "six vec4 per emitter");
        assertEquals(record.size / Float.BYTES, RtEmitters.FLOATS);
        assertTrue(emitters.contains("static const uint EMITTER_RECORD_FLOATS = 24u;"),
            "the module names the same stride in floats");
        assertEquals(24, record.size / Float.BYTES);
    }

    @Test
    void frameStatsPushIsTwoWordsAndTheCountersAreTheHead() throws IOException {
        Layout push = layout("passes/frame_stats.slang", "FrameStatsPush");
        assertEquals(RtFrameStatsSlangPass.PUSH_BYTES, push.size);
        assertEquals(2, push.fields.size());
        assertEquals(0, push.offset("frameIndex"));
        assertEquals(4, push.offset("reserved"));

        String pass = read(SLANG + "passes/frame_stats.slang");
        assertTrue(pass.contains("RWStructuredBuffer<uint> stats;"),
            "the live counter buffer is four plain words, not the old struct");
        assertEquals(16, RtFrameStatsSlangPass.COUNTER_BYTES, "the host binds exactly the four words");
        for (int word = 0; word < 4; word++) {
            assertTrue(pass.contains("InterlockedAdd(stats[" + word + "]"),
                "counter word " + word + " is written by the pass");
        }

        Layout stats = parseLayout(read(BINDINGS), "FrameStats");
        assertEquals(RtStatsLayout.HITS, stats.offset("hits"));
        assertEquals(RtStatsLayout.SAMPLES, stats.offset("samples"));
        assertEquals(RtStatsLayout.LUM_LOG_SUM, stats.offset("lumLogSum"));
        assertEquals(RtStatsLayout.HIST_ACCEPTED, stats.offset("histAccepted"));
        assertEquals(12, stats.offset("histAccepted"), "the four metered words are contiguous");
        assertEquals(4, stats.field("samples").size, "a counter word");
        assertTrue(RtStatsLayout.SIZE <= RtStatsLayout.SLOT_STRIDE, "the slot fits its stride");
    }

    @Test
    void theStatsSlotTailIsUnreachableFromEveryPass() throws IOException {
        // The 4-word head is the whole live ABI. Everything behind it is host-side scratch: if any pass could import the
        // legacy declarations, this boundary would need a field-by-field test again; while none can, it must not.
        List<String> importers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of(SLANG))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".slang")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (Pattern.compile("(?m)^\\s*(?:__exported\\s+)?import\\s+bindings\\s*;").matcher(source).find()) {
                    importers.add(Path.of(SLANG).relativize(file).toString().replace('\\', '/'));
                }
                if (file.toString().endsWith("bindings.slang")) {
                    continue;
                }
                assertFalse(Pattern.compile("\\bstatsAddr\\b").matcher(source).find(),
                    file + " reads the legacy stats address");
                assertFalse(Pattern.compile("\\bFrameStats\\b").matcher(source).find(),
                    file + " reaches the dead FrameStats declaration");
                assertFalse(Pattern.compile("\\bPushConstants\\b").matcher(source).find(),
                    file + " reaches the dead push block");
            }
        }
        importers.sort(String::compareTo);
        assertEquals(List.of("backend_query.slang", "bedray_legacy.slang"), importers,
            "only modules that read none of the dead declarations import bindings.slang");
        assertTrue(read(BINDINGS).contains("uint64_t statsAddr;"),
            "the declarations still exist on the module surface; they are just not an ABI of any pass");
    }

    @Test
    void theLegacyPushBlockStillMatchesRtFrameParams() throws IOException {
        Layout push = parseLayout(read(BINDINGS), "PushConstants");
        assertEquals(RtFrameParams.BYTES, push.size);
        int[] offsets = RtStatsLayout.std430Offsets(RtFrameParams.PC_TYPES);
        assertEquals(RtFrameParams.PC_TYPES.length, push.fields.size());
        for (int i = 0; i < offsets.length; i++) {
            assertEquals(offsets[i], push.fields.get(i).offset,
                "member " + i + " (" + RtFrameParams.PC_TYPES[i] + ") moved");
        }
        assertEquals(RtFrameBindings.PUSH_BYTES, push.size);
    }

    @Test
    void theCompiledManifestsAgreeWithTheHostStrides() {
        Assumptions.assumeTrue(SlangPassLibrary.available("path_trace"), "path_trace AOT entry was not compiled");
        var pathTrace = SlangPassLibrary.load("path_trace").orElseThrow().manifest();
        assertEquals(RtPathTraceSlangPass.PUSH_BYTES, pathTrace.pushConstantBytes());
        assertEquals(RtPathTraceSlangPass.FRAME_CONSTANTS_BYTES, pathTrace.binding("frame").elementBytes());
        assertEquals(RtPathTraceSlangPass.EMITTER_BYTES, pathTrace.binding("emitters").elementBytes());
        assertEquals(RtPathTraceSlangPass.RESERVOIR_BYTES, pathTrace.binding("reservoirs").elementBytes());

        Assumptions.assumeTrue(SlangPassLibrary.available("frame_stats"), "frame_stats AOT entry was not compiled");
        var stats = SlangPassLibrary.load("frame_stats").orElseThrow().manifest();
        assertEquals(RtFrameStatsSlangPass.PUSH_BYTES, stats.pushConstantBytes());
        assertNotNull(stats.binding("stats"));
        // The binding is one uint word; the host binds the four counter words as four elements of it.
        assertEquals(4, stats.binding("stats").elementBytes(), "one counter word per element");
        assertEquals(RtFrameStatsSlangPass.COUNTER_BYTES, 4 * stats.binding("stats").elementBytes(),
            "the host's four words are exactly the elements it binds");
    }

    // ---- std430 parsing ----

    private record Field(String name, String type, int offset, int size, int align) {}

    private record Layout(int size, int align, List<Field> fields) {
        Field field(String name) {
            return fields.stream().filter(f -> f.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no member " + name));
        }

        int offset(String name) {
            return field(name).offset;
        }
    }

    private record Type(int size, int align) {}

    private static Layout layout(String relative, String struct) throws IOException {
        return parseLayout(read(SLANG + relative), struct);
    }

    private static Layout parseLayout(String source, String struct) {
        Map<String, List<String[]>> definitions = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*(?:public\\s+)?struct\\s+(\\w+)\\s*\\{").matcher(source);
        while (matcher.find()) {
            int open = source.indexOf('{', matcher.start());
            int close = matchingBrace(source, open);
            List<String[]> members = new ArrayList<>();
            for (String raw : source.substring(open + 1, close).split("\n")) {
                String line = raw.replaceAll("//.*", "").trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.replaceAll(";$", "").trim().split("\\s+");
                assertEquals(2, parts.length, "unexpected member declaration in " + matcher.group(1) + ": " + line);
                members.add(parts);
            }
            definitions.put(matcher.group(1), members);
        }
        Layout layout = build(struct, definitions, new LinkedHashMap<>());
        assertNotNull(layout, struct + " was not declared");
        return layout;
    }

    private static Layout build(String name, Map<String, List<String[]>> definitions, Map<String, Layout> memo) {
        Layout cached = memo.get(name);
        if (cached != null) {
            return cached;
        }
        List<String[]> members = definitions.get(name);
        if (members == null) {
            throw new AssertionError("struct " + name + " not found");
        }
        List<Field> fields = new ArrayList<>();
        int cursor = 0;
        int align = 1;
        for (String[] member : members) {
            Type type = type(member[0], definitions, memo);
            cursor = (cursor + type.align - 1) & -type.align;
            fields.add(new Field(member[1], member[0], cursor, type.size, type.align));
            cursor += type.size;
            align = Math.max(align, type.align);
        }
        Layout layout = new Layout((cursor + align - 1) & -align, align, List.copyOf(fields));
        memo.put(name, layout);
        return layout;
    }

    private static Type type(String name, Map<String, List<String[]>> definitions, Map<String, Layout> memo) {
        switch (name) {
            case "float", "uint", "int", "bool":
                return new Type(4, 4);
            case "float2", "uint2", "int2":
                return new Type(8, 8);
            case "float3", "uint3", "int3":
                return new Type(16, 12);
            case "float4", "uint4", "int4":
                return new Type(16, 16);
            case "float4x4", "float3x3":
                return name.equals("float4x4") ? new Type(64, 16) : new Type(48, 16);
            case "uint64_t", "int64_t":
                return new Type(8, 8);
            default:
                Layout nested = build(name, definitions, memo);
                return new Type(nested.size, nested.align);
        }
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("unbalanced braces at offset " + open);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}