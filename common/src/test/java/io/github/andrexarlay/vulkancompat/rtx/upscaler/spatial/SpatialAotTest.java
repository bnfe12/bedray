package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassLibrary;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class SpatialAotTest {
    private static String source(String path) throws IOException {
        String root = path.startsWith("spatial/") ? "src/test/resources/shaders/" : "src/main/resources/shaders/";
        return Files.readString(Path.of(root + path));
    }
    private static String canonical(String text) { return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "").replaceAll("\\s+", ""); }

    @Test void fsrShimPreservesEveryExistingFp32ApproximationAndVendorBodyIsIncludedUnchanged() throws IOException {
        String expected = source("spatial/ffx_glsl_shim.glsl");
        String[][] aliases = {{"uvec2","uint2"},{"uvec3","uint3"},{"uvec4","uint4"},{"ivec2","int2"},
            {"vec2","float2"},{"vec3","float3"},{"vec4","float4"},{"uintBitsToFloat","asfloat"},{"floatBitsToUint","asuint"},{"fract(x)","frac(x)"}};
        for (var pair : aliases) expected = expected.replace(pair[0],pair[1]);
        expected = expected.replace("#define ffxPackHalf2x16(x) packHalf2x16(x)",
            "uint ffxPackHalf2x16(float2 value) { return spirv_asm { result: $$uint = OpExtInst glsl450 PackHalf2x16 $value }; }");
        assertEquals(canonical(expected),canonical(source("slang/spatial/ffx_slang_shim.slang")));
        for (String name : new String[]{"fsr1_easu","fsr1_rcas"}) {
            String entry = source("slang/passes/"+name+".slang");
            assertTrue(entry.contains("#include \"../../third_party/fsr1/ffx_fsr1.h\""));
            assertTrue(entry.contains("vk::image_format(\"rgba8\")"));
        }
        String easu = source("slang/passes/fsr1_easu.slang");
        assertTrue(easu.contains("spatialInput.GatherRed(p)")); assertTrue(easu.contains("spatialInput.GatherGreen(p)")); assertTrue(easu.contains("spatialInput.GatherBlue(p)"));
        assertTrue(source("slang/passes/fsr1_rcas.slang").contains("clamp(p, int2(0), int2(pc.size.xy) - int2(1))"));
    }
    @Test void all1024NisCoefficientFloatsMatchTheCurrentVendorTablesBitForBit() throws IOException {
        String table = source("slang/spatial/nis_coefficients.slang");
        String config = source("third_party/nis/NIS_Config.h");
        for (var names : new String[][]{{"coef_scale","spatialNisScale"},{"coef_usm","spatialNisUsm"}}) {
            float[] expected = SpatialShaderSource.parseCoefficients(config,names[0]);
            String block = table.substring(table.indexOf(names[1]+"[128] = {")); block = block.substring(0,block.indexOf("};"));
            var rows = Pattern.compile("float4\\(([^)]+)\\)").matcher(block);
            List<Float> actual = new ArrayList<>();
            while (rows.find()) for (String value : rows.group(1).split(",")) actual.add(Float.parseFloat(value.trim()));
            assertEquals(512,actual.size());
            for (int i = 0; i < 512; i++) assertEquals(Float.floatToRawIntBits(expected[i]),Float.floatToRawIntBits(actual.get(i)),names[0]+"["+i+"]");
        }
    }
    @Test void nisIncludesTheVendorAlgorithmAndAdaptsOnlyResourceCallsAndItsExistingEdgeGuard() throws IOException {
        String entry = source("slang/passes/nis_scaler.slang"), adapter = source("slang/spatial/nis_combined_adapter.slang");
        assertTrue(entry.contains("#include \"../../third_party/nis/NIS_Scaler.h\""));
        assertTrue(entry.contains("NIS_USE_HALF_PRECISION 0")); assertTrue(entry.contains("NIS_BLOCK_WIDTH 32"));
        assertTrue(entry.contains("NIS_BLOCK_HEIGHT 24")); assertTrue(entry.contains("NIS_THREAD_GROUP_SIZE 128"));
        assertTrue(entry.contains("NVScaler(group.xy, thread)")); assertTrue(entry.contains("Sampler2D spatialInput"));
        assertFalse(entry.contains("SamplerState")); assertFalse(entry.contains("Texture2D<float4> spatialInput"));
        assertTrue(adapter.contains("return spatialInput.SampleLevel(uv, level)"));
        assertTrue(adapter.contains("spatialNisScale[p.y * 2 + p.x]")); assertTrue(adapter.contains("spatialNisUsm[p.y * 2 + p.x]"));
        assertTrue(adapter.contains("if (all(p < size)) spatialOutput[p] = newValue"));
        String legacy = source("spatial/nis_scaler.comp");
        String oldFields = legacy.substring(legacy.indexOf("uniform NisConstants {"),legacy.indexOf("};"));
        String newFields = entry.substring(entry.indexOf("cbuffer SpatialNisPush {"),entry.indexOf("};"));
        var field = Pattern.compile("\\b(float|uint)\\s+(\\w+)\\s*;");
        var oldMatcher = field.matcher(oldFields); var newMatcher = field.matcher(newFields);
        while (oldMatcher.find()) { assertTrue(newMatcher.find()); assertEquals(oldMatcher.group(),newMatcher.group()); }
        assertFalse(newMatcher.find());
    }
    @Test void aotManifestsNeedOnlyTwoImageBindingsAndKeepExactPushAndWorkgroupContracts() {
        for (var stage : SpatialComputePass.Stage.values()) {
            Assumptions.assumeTrue(SlangPassLibrary.available(stage.programName),stage.programName+" AOT entry was not compiled");
            var manifest = SlangPassLibrary.load(stage.programName).orElseThrow().manifest();
            assertEquals(stage.pushBytes,manifest.pushConstantBytes()); assertEquals(2,manifest.bindings().size());
            assertArrayEquals(new int[]{stage.threadsX,stage.threadsY,1},manifest.threadGroupSize());
            assertEquals(0,manifest.requiredCapabilities());
            assertEquals(0,manifest.binding("spatialInput").binding());
            assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,manifest.binding("spatialInput").descriptorType());
            assertEquals(1,manifest.binding("spatialOutput").binding());
            assertEquals(BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE,manifest.binding("spatialOutput").descriptorType());
        }
    }
    @Test void spatialFailuresAbandonTheWholeChainAndCleanupRequiresSubmittedFenceProof() throws IOException {
        String host = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rtx/WorldTargetManager.java"));
        int begin = host.indexOf("spatialCompletion.beginWithDiscard"); int record = host.indexOf("SpatialUpscaler.record(",begin);
        int attempted = host.indexOf("spatialCompletion.submissionAttempted(ticket)",record); int execute = host.indexOf("encoder.execute(cmd)",attempted);
        assertTrue(begin >= 0 && record > begin && attempted > record && execute > attempted);
        assertTrue(host.indexOf("spatialCompletion.submitted(ticket, action -> encoder.queueForDestroy(action::run))",execute) > execute);
        assertTrue(host.indexOf("spatialCompletion.discard(ticket)",execute) > execute);
        int drain = host.indexOf("private static void drainSpatialRecordings()"); int submit = host.indexOf("encoder.submit()",drain);
        int fence = host.indexOf("completion.awaitCompletion(",submit); int release = host.indexOf("spatialCompletion.afterHostDrain()",fence);
        assertTrue(drain >= 0 && submit > drain && fence > submit && release > fence);
        int targets = host.indexOf("private static void releaseTargets()");
        assertTrue(host.indexOf("drainBorrowedWorldTargetRecordings()",targets) < host.indexOf("SpatialUpscaler.cleanup()",targets));
    }
    @Test void resizingBorrowedViewsRequiresProducerProofBeforeAnyOldImageIsDestroyed() throws IOException {
        String host = Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rtx/WorldTargetManager.java"));
        String world = method(host,"public static void ensureTargets(");
        precedes(world,"drainBorrowedWorldTargetRecordings()","worldTarget.resize(");
        String temporal = method(host,"private static void ensureTemporalTargets(");
        for (String target : new String[]{"depthCopy.resize(","motionVectorsTarget.resize("}) {
            precedes(temporal,"FrameGenRuntime.drainProducerRecordings()",target);
            precedes(temporal,"drainVelocityRecordings()",target);
        }
        precedes(method(host,"private static void ensureXessOutput("),"drainSpatialRecordings()","xessOutput.resize(");
        precedes(method(host,"private static boolean runSpatialPass("),"drainSpatialRecordings()","spatialTmp.resize(");
        String release = method(host,"private static void releaseTargets()");
        precedes(release,"drainBorrowedWorldTargetRecordings()","worldTarget.destroyBuffers()");
        String borrowed = method(host,"private static void drainBorrowedWorldTargetRecordings()");
        assertTrue(borrowed.contains("FrameGenRuntime.drainProducerRecordings()"));
        assertTrue(borrowed.contains("drainVelocityRecordings()"));
        assertTrue(borrowed.contains("drainSpatialRecordings()"));
        String drain = method(host,"private static void drainSpatialRecordings()");
        precedes(drain,"throw new IllegalStateException(\"spatial host submission completion timed out; resources retained\")",
            "spatialCompletion.afterHostDrain()");
    }

    private static void precedes(String source, String before, String after) {
        int first = source.indexOf(before), second = source.indexOf(after);
        assertTrue(first >= 0 && second > first,before+" must precede "+after);
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0,"missing method "+signature);
        int open = source.indexOf('{',start), depth = 1, end = open + 1;
        for (; end < source.length() && depth != 0; end++) {
            char c = source.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        assertEquals(0,depth,"unterminated method "+signature);
        return source.substring(open,end);
    }
}
