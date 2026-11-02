package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RtDenoiserRadiometryTest {
    private static String shader(String relative) throws Exception {
        return Files.readString(Path.of("src/main/resources/shaders/slang", relative));
    }

    @Test void cameraMediumAttenuationBelongsToTheDirectBypassAsWellAsRawHdr() throws Exception {
        for (double transmission : new double[]{0, 0.01, 0.2, 0.7, 1}) {
            double direct = 10, indirect = 0.3;
            double raw = transmission * (direct + indirect);
            double bypass = transmission * direct;
            double residual = Math.max(0, raw - bypass);
            assertEquals(raw, residual + bypass, 1e-12);
            if (transmission < 0.7) assertNotEquals(raw, Math.max(0, raw - direct) + direct, 1e-8,
                "the old unattenuated bypass makes dark underwater surfaces bright");
        }
        String tracer = shader("pathtrace/path_tracer.slang");
        assertTrue(tracer.contains("float3 directContribution = throughput * direct;"));
        assertTrue(tracer.contains("result.primary.direct = directContribution;"));
        assertTrue(tracer.contains("result.radiance += directContribution;"));
        assertFalse(tracer.contains("result.primary.direct = direct;"));
    }

    @Test void partialSkyCoverageUsesTheSameSampleDenominatorForEveryRadiometricTerm() throws Exception {
        for (int accepted = 2; accepted <= 8; accepted++) {
            double directSum = 2, raw = directSum / accepted;
            double bypass = directSum / accepted; // one primary hit and accepted sky samples
            assertEquals(raw, Math.max(0, raw - bypass) + bypass, 1e-12);
            assertNotEquals(raw, Math.max(0, raw - directSum) + directSum, 1e-12);
        }
        String entry = shader("pathtrace/path_trace_entry.slang");
        assertTrue(entry.contains("result.radiance = mean.acceptedRadiance / float(mean.accepted);"));
        assertTrue(entry.contains("result.primary.direct = mean.direct / float(mean.accepted);"));
        assertFalse(entry.contains("result.primary.direct = mean.direct / hits;"));
    }

    @Test void denoiserCompositionAppliesExposureExactlyOnceEvenWithoutToneMapping() throws Exception {
        String pack = shader("denoise/nrd_pack.slang");
        int compose = pack.indexOf("float3 nrdComposeColor(");
        String body = pack.substring(compose);
        int exposure = body.indexOf("color *= max(pc.exposure, 0.0);");
        assertTrue(exposure >= 0 && exposure < body.indexOf("if ((pc.flags & 1u) != 0u) color = tonemapColor(color);"));
        assertFalse(body.contains("tonemapColor(color * pc.exposure)"));
        assertTrue(shader("passes/path_display.slang").contains("float3 exposed = mean * max(pc.exposure, 0.0);"));
        for (float value : new float[]{0, 0.1f, 0.25f, 0.8f}) {
            for (float scale : new float[]{0, 0.2f, 1, 2, 8}) {
                float direct = value * 0.4f, residual = value - direct;
                float rawDisplay = Math.min(1, value * scale);
                float denoisedDisplay = Math.min(1, (residual + direct) * scale);
                assertEquals(rawDisplay, denoisedDisplay, 1e-7);
                assertArrayEquals(RtTonemap.apply(value * scale, value * scale, value * scale),
                    RtTonemap.apply((residual + direct) * scale, value * scale, value * scale), 1e-7f);
            }
        }
        assertEquals(0.5f, 0.25f * 2); // the reported none-mode regression
    }
}
