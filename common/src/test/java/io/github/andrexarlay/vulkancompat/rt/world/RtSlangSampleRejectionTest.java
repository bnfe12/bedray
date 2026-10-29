package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A path can end badly in two different ways, and the accumulation history treats them differently: a non-finite fault
 * is not a sample of the integrand and must never dilute a mean that lives for thousands of frames, while a path that
 * stopped short (no lobe left, an interval used up by cutout advances, the firefly guard) is a short but honest sample.
 * The split is invisible in a still frame, so these guards pin the three things that break silently: which fault sets
 * which flag, how the verdict crosses the image boundary, and that the accepted arithmetic still divides by N+1.
 */
class RtSlangSampleRejectionTest {

    private static final String REFERENCE_BACKEND = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/ReferencePathTraceBackend.java";
    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";
    private static final String BSDF = "src/main/resources/shaders/slang/shading/bsdf.slang";
    private static final String PASS = "src/main/resources/shaders/slang/passes/path_trace.slang";
    private static final String ENTRY = "src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang";
    private static final String DISPLAY = "src/main/resources/shaders/slang/passes/path_display.slang";
    private static final String CASCADER = "src/main/resources/shaders/slang/radiance_cascades/rc_trace.slang";
    private static final String HOST_PASS =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java";
    private static final String GAME_PASS =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java";

    private static final String INVALID = "PATH_TRACE_FLAG_INVALID_SAMPLE";
    private static final String TRUNCATED = "PATH_TRACE_FLAG_TRUNCATED";

    /** The radiance cascades already separate the two endings; the path tracer must not invent a third vocabulary. */
    @Test
    void theTwoBadEndingsAreDifferentBits() {
        String tracer = read(TRACER);
        assertEquals(1 << 3, flag(tracer, INVALID), "the bit that already meant an invalid sample");
        assertEquals(1 << 7, flag(tracer, TRUNCATED), "the new bit, above PRIMARY_HIT");
        List<String> bits = List.of("PATH_TRACE_FLAG_ENVIRONMENT", "PATH_TRACE_FLAG_RUSSIAN_ROULETTE",
            "PATH_TRACE_FLAG_DEPTH_LIMIT", INVALID, "PATH_TRACE_FLAG_SURFACE_LIMIT",
            "PATH_TRACE_FLAG_FILTERED_SHADOW", "PATH_TRACE_FLAG_PRIMARY_HIT", TRUNCATED).stream()
            .map(name -> name + "=" + flag(tracer, name))
            .collect(Collectors.toList());
        assertEquals(bits.size(), java.util.Set.copyOf(bits).size(), "every flag needs its own bit: " + bits);
        String cascader = read(CASCADER);
        assertTrue(cascader.contains("RC_SEGMENT_TRUNCATED") || cascader.contains("TRUNCATED"),
            "the shared vocabulary exists in the reference trace: " + TRUNCATED + " is that word, not a new one");
    }

    /**
     * The rule the whole batch rests on: only arithmetic that could not be trusted throws a sample away. Anything that
     * merely stops the path early keeps what it gathered.
     */
    @Test
    void everyRejectionIsANonFiniteFault() {
        String tracer = read(TRACER);
        int at = 0;
        int sites = 0;
        while (true) {
            int found = tracer.indexOf("result.flags |= " + INVALID + ";", at);
            if (found < 0) {
                break;
            }
            String guard = guardBefore(tracer, found);
            assertTrue(guard.contains("isFinite"),
                "a rejection that is not a non-finite fault throws away a legitimate sample: " + guard);
            sites++;
            at = found + 1;
        }
        assertEquals(8, sites, "eight arithmetic faults: primary ray, interval, hit parameter, two cutout advances, "
            + "primary advance, throughput and the final radiance");
        assertEquals(4, count(tracer, "result.flags |= " + TRUNCATED + ";"),
            "and four stops that only cut the path short");
    }

    /** Each of the four stops, named by the guard that decides it. */
    @Test
    void theStopsThatOnlyCutThePathShort() {
        String tracer = read(TRACER);
        assertEquals("truncated", flagSetBy(tracer, "if (!(ray.tMax > ray.tMin)) {"),
            "cutout advances used up the interval: the path ended, nothing went wrong");
        assertEquals("truncated", flagSetBy(tracer, "if (!surface.valid) {"),
            "a decoder that cannot name the hit stops the walk but corrupts no number");
        assertEquals("truncated", flagSetBy(tracer, "if (!bsdf.valid || bsdf.pdf <= 0.0) {"),
            "a lobe with nothing left to sample is an ordinary end of a path");
        assertEquals("truncated", flagSetBy(tracer, "if (pathMaxComponent(throughput) > options.maxRadiance) {"),
            "the firefly guard clamps, it does not invalidate");
        assertEquals("invalid", flagSetBy(tracer, "if (!isFinite(throughput)) {"),
            "but a throughput that is not a number ends the sample, not just the path");
        assertEquals("invalid", flagSetBy(tracer, "if (!isFinite(ray.tMax)) {"),
            "a non-finite interval is checked before the empty-interval stop, so NaN is not read as truncation");
    }

    /**
     * P1-8: a VNDF reflection off a visible normal can land below the shading horizon. The audit read the resulting
     * stop as lost energy on silhouettes; the code says otherwise, and these are the facts that make it otherwise.
     * The sampler returns the zero sample it was handed, before it builds a light direction or writes a weight, so
     * nothing downstream can multiply by a half-filled record; and the tracer stops only after this vertex's own
     * emission and next-event terms are already in {@code result.radiance}, so the path ends short rather than
     * losing what it gathered. Continuing with a zero throughput would be the same radiance at the cost of a
     * bounce, and resampling until the direction is above the horizon would add energy instead of removing it.
     */
    @Test
    void theBelowHorizonVndfSampleIsAZeroAndNotABrokenPath() {
        String bsdf = block(read(BSDF),
            "BSDFSample bsdfSample(MaterialSample material, float3 V, float3 N, float incidentIor,");
        int horizon = bsdf.indexOf("if (lightLocal.z <= 0.0) return result;");
        assertTrue(horizon >= 0, "the sampler no longer rejects a below-horizon reflection before using it");
        assertTrue(bsdf.indexOf("result.valid = true") > horizon
                && bsdf.indexOf("result.weight =") > horizon
                && bsdf.indexOf("result.pdf =") > horizon,
            "the early return must precede every write, or the caller multiplies a half-built record");
        assertTrue(bsdf.indexOf("float3 L = safeNormalize(tangent * lightLocal.x +") > horizon,
            "no light direction may be built from a sample that was just refused");
        String invalid = block(read(BSDF), "BSDFSample invalidBSDFSample() {");
        assertTrue(invalid.contains("sample.valid = false;") && invalid.contains("sample.weight = float3(0.0);")
                && invalid.contains("sample.pdf = 0.0;"),
            "the refused sample has to be the zero the caller already tests for");

        String tracer = read(TRACER);
        int emission = tracer.indexOf("result.radiance += throughput * surface.material.emission;");
        int direct = tracer.indexOf("result.radiance += throughput * direct;");
        int sampled = tracer.indexOf("BSDFSample bsdf = bsdfSample(surface.material, view, normal,");
        assertTrue(emission >= 0 && direct > emission && sampled > direct,
            "emission and next-event estimation must both be accumulated before the lobe is drawn, or the stop "
                + "throws away light this vertex earned");
        assertEquals("truncated", flagSetBy(tracer, "if (!bsdf.valid || bsdf.pdf <= 0.0) {"),
            "a lobe with nothing left is a short path, not a rejected sample");
        assertTrue(tracer.indexOf("throughput *= bsdf.weight;") > sampled,
            "the path stops before the zero weight could be folded into the throughput");
    }

    /** The verdict leaves the tracer through the alpha lane, through the one helper that defines it. */
    @Test
    void theVerdictTravelsInTheAlphaLane() {
        String tracer = read(TRACER);
        String helper = block(tracer, "bool pathTraceSampleRejected(uint flags) {");
        assertTrue(helper.contains(INVALID), "the verdict is the non-finite fault");
        assertFalse(helper.contains(TRUNCATED), "a truncated path stays a sample");
        String pass = read(PASS);
        String entry = read(ENTRY);
        assertTrue(entry.contains("outImage[p] = float4(result.radiance, pathTraceSampleRejected(result.flags)"
                + " ? 0.0 : 1.0);"),
            "the pass publishes the helper's answer instead of inventing its own rule");
        assertFalse(entry.contains("float4(result.radiance, 1.0)"),
            "a hardcoded 1.0 would make the whole rejection chain dead code");
        assertTrue(pass.contains("//   1  outImage") && pass.contains("a = 0 for a"),
            "and the binding table says the lane carries the verdict, so a later consumer is not surprised");
    }

    /** A rejected sample must change neither the mean nor the count. */
    @Test
    void aRejectedSampleAddsNothingToTheMean() {
        String display = block(read(DISPLAY), "void main(uint3 tid : SV_DispatchThreadID) {");
        assertTrue(display.contains("float weight = radiance[p].a > 0.0 ? 1.0 : 0.0;"),
            "the lane is read exactly once, as a weight");
        assertTrue(display.contains("float3 mean = current * weight;")
                && display.contains("float count = weight;"),
            "with no history a rejected pixel stores zero radiance and zero samples, not trusted black");
        assertTrue(display.contains("mean = previous + (current - previous) * (weight / (history.a + weight));"),
            "a weight of zero leaves the static-camera mean at the history value");
        assertTrue(display.contains("count = min(history.a + weight, PATH_DISPLAY_MAX_COUNT);"),
            "and does not advance the count, so the next accepted sample still divides by N+1");
        assertTrue(display.contains("mean = sanitized + (current - sanitized) * (weight / (history.count + weight));")
                && display.contains("count = min(history.count + weight, PATH_DISPLAY_MAX_COUNT);"),
            "the reprojected branch is the same rule in the same two lines");
        assertEquals(0, count(display, "+ 1.0"),
            "the +1 comes from the weight: a second hardcoded one would silently count a rejected sample");
        assertFalse(display.contains("float3 mean = current;"),
            "the unweighted start would put a broken sample into the history");
    }

    /**
     * The depth lane is how the next frame finds this pixel's history, so it has to keep describing the mean that is
     * actually stored - which, for a rejected sample, is still the old one.
     */
    @Test
    void reprojectionReadsOnlyImmutableHistoryImages() {
        String shader = read(DISPLAY);
        assertTrue(shader.contains("float stored = previousDepth[q];"));
        assertTrue(shader.contains("float4 sample = previousAccum[q];"));
        assertTrue(shader.contains("float4 history = previousAccum[p];"));
        assertFalse(shader.contains("= accum["), "a destination cannot also be a cross-pixel source");
        assertFalse(shader.contains("= historyDepth["));
        assertFalse(shader.contains("previousAccum[p] ="));
        assertFalse(shader.contains("previousDepth[p] ="));
    }

    @Test
    void aRejectedPixelKeepsTheDepthThatDescribesItsHistory() {
        String display = block(read(DISPLAY), "void main(uint3 tid : SV_DispatchThreadID) {");
        assertTrue(display.contains("if (rejected && !(nextDepth > 0.0 && nextDepth < PATH_DISPLAY_SKY_DEPTH))"),
            "a rejected pixel cannot publish unproven primary depth");
        assertTrue(display.contains("nextDepth = previousDepth[p];"),
            "a retained same-pixel mean keeps the immutable source depth that describes it");
        assertEquals(1, count(display, "historyDepth[p] = nextDepth;"), "every destination texel has one writer");
        assertTrue(display.contains("bool rejected = weight == 0.0;"),
            "the flag is the same decision the weight already made");
    }

    /** The radiance image has one consumer, so the lane is free; if that changes, the verdict needs a real channel. */
    @Test
    void nothingElseReadsTheLaneTheVerdictLivesIn() {
        String pass = read(PASS);
        String display = read(DISPLAY);
        assertTrue(display.contains("[[vk::image_format(\"rgba16f\")]] [[vk::binding(0, 0)]] RWTexture2D<float4>"
            + " radiance;"), "the display pass reads the image the trace pass wrote");
        assertTrue(display.contains("pathDisplaySanitize(radiance[p].rgb, limit)"),
            "and only its rgb for radiance, so the alpha lane is not double-used");
        String game = read(GAME_PASS) + "\n" + read(REFERENCE_BACKEND);
        assertTrue(game.contains("SlangProgram.Resource.storageImage(\"radiance\", trace.radianceView())"),
            "the host binds it to exactly one pass");
        assertEquals(2, javaFilesMentioning("radianceView"),
            "the producer's view and that binding: a second consumer would start reading a verdict as coverage");
        assertTrue(read(HOST_PASS).contains("radianceView()"), "declared by the trace pass itself");
    }

    /**
     * A non-finite radiance is still zeroed before it is stored, but now it arrives tagged: without the flag the
     * display pass would fold a fabricated black into the mean.
     */
    @Test
    void abrokenNumberNeverBecomesTrustedBlack() {
        String tracer = read(TRACER);
        assertEquals("invalid", flagSetBy(tracer, "if (!isFinite(result.radiance)) {"),
            "the tail clamps the value and tags the sample in the same breath");
        assertTrue(tracer.contains("result.radiance = clampRadiance(result.radiance, options.maxRadiance);"),
            "the clamp stays for accepted samples too, so a bright but finite path is still bounded");
        String display = read(DISPLAY);
        assertTrue(display.contains("float limit = pc.maxRadiance > 0.0 ? pc.maxRadiance : 65000.0;"),
            "the host's firefly bound is the one the mean is clipped to");
    }

    private static String flagSetBy(String source, String guard) {
        int at = source.indexOf(guard);
        assertTrue(at >= 0, "the tracer lost this guard: " + guard);
        String window = source.substring(at, Math.min(source.length(), at + 700));
        int invalid = window.indexOf(INVALID + ";");
        int truncated = window.indexOf(TRUNCATED + ";");
        if (invalid >= 0 && (truncated < 0 || invalid < truncated)) {
            return "invalid";
        }
        assertTrue(truncated >= 0, "no outcome flag follows: " + guard);
        return "truncated";
    }

    /** The nearest enclosing {@code if} text, from its opening to the statement at {@code at}. */
    private static String guardBefore(String source, int at) {
        int guard = source.lastIndexOf("if (", at);
        assertTrue(guard >= 0, "no guard before offset " + at);
        return source.substring(guard, at);
    }

    private static int javaFilesMentioning(String needle) {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            return (int) files.filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        return Files.readString(path, StandardCharsets.UTF_8).contains(needle);
                    } catch (IOException e) {
                        throw new AssertionError("cannot read " + path, e);
                    }
                })
                .count();
        } catch (IOException e) {
            throw new AssertionError("cannot walk src/main/java", e);
        }
    }

    private static int flag(String source, String name) {
        String line = lineContaining(source, "static const uint " + name);
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
                return line;
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
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
