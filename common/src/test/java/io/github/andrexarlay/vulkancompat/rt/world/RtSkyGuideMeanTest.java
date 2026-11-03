package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RtSkyGuideMeanTest {
    private record Sample(boolean accepted, boolean surface, boolean environment, double colour) {}
    private static double rrSky(List<Sample> samples) {
        long accepted = samples.stream().filter(Sample::accepted).count();
        if (accepted == 0 || samples.stream().anyMatch(s -> s.accepted && s.surface)) return 0;
        long sky = samples.stream().filter(s -> s.accepted && !s.surface && s.environment).count();
        return sky == accepted ? samples.stream().filter(Sample::accepted).mapToDouble(Sample::colour).sum() / accepted : 0;
    }

    @Test void trustedSkySurvivesTheMeanAndRefusedSamplesCannotChangeItsClassification() {
        assertEquals(3, rrSky(List.of(new Sample(true, false, true, 2), new Sample(true, false, true, 4))));
        assertEquals(2, rrSky(List.of(new Sample(true, false, true, 2), new Sample(false, false, true, 999))));
        assertEquals(0, rrSky(List.of(new Sample(false, false, true, 999))));
        assertEquals(0, rrSky(List.of(new Sample(true, false, false, 2), new Sample(false, false, true, 999))));
        assertEquals(0, rrSky(List.of(new Sample(true, true, true, 2))), "a surface whose secondary ray sees sky is not a primary miss");
    }

    @Test void liveDataflowCarriesAcceptedSkyThroughMeanGuidesAndRrPacking() throws Exception {
        Path root = Path.of("src/main/resources/shaders/slang");
        String entry = Files.readString(root.resolve("pathtrace/path_trace_entry.slang"));
        int sample = entry.indexOf("void accumulatePathTraceSample(");
        int refusal = entry.indexOf("if (pathTraceSampleRejected(result.flags))", sample);
        int accepted = entry.indexOf("mean.accepted += 1u;", sample);
        int miss = entry.indexOf("if (!result.primary.valid)", accepted);
        int marker = entry.indexOf("mean.sky += (result.flags & PATH_TRACE_FLAG_ENVIRONMENT) != 0u ? 1u : 0u;", miss);
        assertTrue(sample >= 0 && refusal > sample && accepted > refusal && miss > accepted && marker > miss);
        assertTrue(entry.contains("mean.sky = 0u;"));
        int mean = entry.indexOf("PathTraceResult pathTraceMeanResult(");
        int noSamples = entry.indexOf("if (mean.accepted == 0u)", mean);
        int noHits = entry.indexOf("if (mean.hits == 0u)", noSamples);
        int carry = entry.indexOf("if (mean.sky == mean.accepted) result.flags = PATH_TRACE_FLAG_ENVIRONMENT;", noHits);
        assertTrue(mean >= 0 && noSamples > mean && noHits > noSamples && carry > noHits);
        assertTrue(carry < entry.indexOf("return result;", noHits), "classify before returning the mean");
        assertFalse(entry.substring(mean, carry).contains("result.flags = mean.flags"), "refused flags never become a trusted verdict");
        assertTrue(entry.contains("bool sky = sampleTrusted && (result.flags & PATH_TRACE_FLAG_ENVIRONMENT) != 0u;"));
        assertTrue(entry.contains("outDiffuse[p] = sky ? float4(max(result.radiance, float3(0.0)), 0.0) : float4(0.0);"));
        String rr = Files.readString(root.resolve("denoise/nrd_pack_entry.slang"));
        assertTrue(rr.indexOf("input.diffuse = gbDiffuse[pixel];") < rr.indexOf("if (!nrdIgnored(input.viewZ, pc))"));
        assertTrue(Files.readString(root.resolve("denoise/nrd_pack.slang"))
            .contains("if (pc.denoiser == 2u && !nrdInvalid3(input.diffuse.rgb))"));
    }
}
