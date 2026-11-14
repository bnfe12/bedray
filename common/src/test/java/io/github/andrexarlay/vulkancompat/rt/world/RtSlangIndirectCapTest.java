package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the hue-preserving indirect cap of path_tracer.slang and ports its arithmetic. */
class RtSlangIndirectCapTest {
    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";
    private static final String ENTRY = "src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang";
    private static final String DENOISER = "src/main/resources/shaders/slang/pathtrace/denoiser_signal.slang";
    private static final double CAP = 10.0;
    private static final double[] REC709 = {0.2126, 0.7152, 0.0722};

    @Test
    void theCapIsAFixedSceneReferredConstantSetOnlyByTheEntry() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("static const float PATH_TRACE_INDIRECT_MAX_LUMINANCE = 10.0;"));
        String defaults = block(tracer, tracer.indexOf("PathTraceOptions defaultPathTraceOptions() {"));
        assertTrue(defaults.contains("options.indirectMaxLuminance = 0.0;"),
            "default-option callers (RC field gather) keep the uncapped transport");
        String entry = read(ENTRY);
        assertTrue(entry.contains("options.indirectMaxLuminance = PATH_TRACE_INDIRECT_MAX_LUMINANCE;"));
        assertFalse(entry.contains("indirectMaxLuminance = PATH_TRACE_INDIRECT_MAX_LUMINANCE * "),
            "the cap is not scaled by exposure or anything per frame");
    }

    @Test
    void theWindowOpensAfterTheFirstSurfacesOwnLightAndBeforeTheReuseSnapshots() {
        String tracer = read(TRACER);
        int emission = tracer.indexOf("result.radiance += throughput * surface.material.emission * emissionWeight;");
        int direct = tracer.indexOf("result.radiance += directContribution;");
        int open = tracer.indexOf("if (!indirectStarted && !bsdf.delta) {");
        int ptBefore = tracer.indexOf("result.pt.radianceBefore = result.radiance;");
        int denoiserBegin = tracer.indexOf("pathDenoiserBegin(denoiserContinuation, bsdf.lobe,");
        assertTrue(emission > 0 && direct > emission && open > direct, "emission and NEE of the vertex stay outside");
        assertTrue(open < ptBefore && open < denoiserBegin,
            "the window snapshot equals the ReSTIR PT and denoiser snapshots on the primary diffuse launch");
        assertTrue(open > tracer.indexOf("if (pathMaxComponent(throughput) > options.maxRadiance) {"),
            "a path killed by the throughput guard never opens the window");
        int rcValid = tracer.indexOf("if (incoming.status == DIFFUSE_FIELD_VALID) {");
        int rcSnap = tracer.indexOf("radianceAtFirstBounce = result.radiance;", rcValid);
        int rcAdd = tracer.indexOf("throughput * bsdf.weight * incoming.cosineMean", rcValid);
        assertTrue(rcValid > 0 && rcSnap > rcValid && rcSnap < rcAdd, "the RC incoming term is indirect too");
    }

    @Test
    void theCapRunsBeforeTheNanGuardTheSuffixAndTheClamp() {
        String tracer = read(TRACER);
        int cap = tracer.indexOf("if (indirectStarted && options.indirectMaxLuminance > 0.0 && isFinite(result.radiance)) {");
        int nan = tracer.indexOf("if (!isFinite(result.radiance)) {");
        int suffix = tracer.indexOf("float3 radianceSuffix =");
        int before = tracer.indexOf("float3 radianceBeforeClamp = result.radiance;");
        int clamp = tracer.indexOf("result.radiance = clampRadiance(result.radiance, options.maxRadiance);");
        assertTrue(cap > 0 && cap < nan && nan < suffix && suffix < before && before < clamp);
        String body = block(tracer, cap);
        assertTrue(body.contains("float indirectLuminance = luminance(indirect);"), "a luminance cap, not per component");
        assertTrue(body.contains("indirect * (options.indirectMaxLuminance / indirectLuminance)"), "one scale keeps hue");
        assertTrue(read(DENOISER).contains("return max(radiance - signal.diffuse - signal.specular, float3(0.0));"));
    }

    @Test
    void portedCapKeepsHueAndTheDenoiserSplitSumsToRadiance() {
        double[] direct = {0.8, 0.6, 0.4};
        double[] firefly = {60.0, 35.0, 10.0};
        double[] total = add(direct, firefly);
        double[] capped = cap(total, direct);
        double[] indirect = sub(capped, direct);
        assertEquals(CAP, lum(indirect), 1e-9, "the indirect part lands exactly on the ceiling");
        assertEquals(firefly[0] / firefly[1], indirect[0] / indirect[1], 1e-9, "hue survives");
        assertEquals(firefly[1] / firefly[2], indirect[1] / indirect[2], 1e-9);
        double[] small = add(direct, new double[] {1.0, 0.8, 0.5});
        double[] kept = cap(small, direct);
        for (int i = 0; i < 3; i++) assertEquals(small[i], kept[i], 0.0, "a path under the cap is untouched");
        // denoiser resolve on the capped value, then the 64x clamp; begin snapshot == window snapshot
        double[] clamped = new double[3];
        double[] diffuse = new double[3];
        for (int i = 0; i < 3; i++) {
            clamped[i] = Math.min(Math.max(capped[i], 0.0), 64.0);
            double scale = capped[i] > 0.0 ? clamped[i] / capped[i] : 0.0;
            diffuse[i] = Math.max(capped[i] - direct[i], 0.0) * scale;
            double bypass = Math.max(clamped[i] - diffuse[i], 0.0);
            assertEquals(clamped[i], diffuse[i] + bypass, 1e-9, "diffuse + bypass == radiance");
            assertEquals(indirect[i], capped[i] - direct[i], 1e-9, "ReSTIR PT suffix is the capped indirect");
        }
    }

    private static double[] cap(double[] total, double[] before) {
        double[] indirect = new double[3];
        for (int i = 0; i < 3; i++) indirect[i] = Math.max(total[i] - before[i], 0.0);
        double l = lum(indirect);
        if (!(l > CAP)) return total.clone();
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) out[i] = before[i] + indirect[i] * (CAP / l);
        return out;
    }

    private static double lum(double[] c) { return c[0] * REC709[0] + c[1] * REC709[1] + c[2] * REC709[2]; }
    private static double[] add(double[] a, double[] b) { return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    private static double[] sub(double[] a, double[] b) { return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }

    private static String block(String source, int at) {
        assertTrue(at >= 0, "anchor not found");
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(at, i + 1);
        }
        throw new AssertionError("unbalanced block at offset " + at);
    }
}
