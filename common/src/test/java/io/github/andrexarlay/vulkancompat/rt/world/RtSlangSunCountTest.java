package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sun and the moon are paid for once. Every accepted surface samples them by next-event estimation; a BSDF-sampled
 * continuation that then escapes towards the disc used to add the disc's radiance again (SunMoonSky.radiance on every
 * miss, no MIS), which doubled the direct sunlight of every bounce in expectation and delivered the second half as
 * fireflies. The discs are now seen only by a ray no NEE stood in for: the primary ray, and a continuation of a delta
 * lobe (a mirror, a refraction, total internal reflection), whose NEE contributes nothing. Every other escape reads the
 * body-free dome ({@code IEnvironmentSampler.skyRadiance}).
 */
class RtSlangSunCountTest {

    private static final Path SHADERS = Path.of("src/main/resources/shaders/slang");
    private static final Path SKY = SHADERS.resolve("light/sky.slang");
    private static final Path TRACER = SHADERS.resolve("pathtrace/path_tracer.slang");

    /**
     * A diffuse floor under a sun disc straight overhead: NEE alone, and NEE plus a cosine-sampled escape that also
     * counts the disc. The exact direct irradiance term is rho/pi * L * cos * Omega.
     */
    @Test
    void neeAloneIsTheDirectTermAndTheDiscOnEveryEscapeDoublesIt() {
        double rho = 0.6;
        double sun = 1000.0;
        double cosHalf = Math.cos(Math.toRadians(3.0));   // a large disc so the escape hits it often enough
        double omega = 2.0 * Math.PI * (1.0 - cosHalf);
        SplittableRandom random = new SplittableRandom(57);
        int samples = 4_000_000;
        double nee = 0.0;
        double escape = 0.0;
        for (int i = 0; i < samples; i++) {
            // NEE: a direction uniform in the disc's cone, pdf 1/omega; the floor's cosine is ~1 for a sun overhead.
            double cosTheta = 1.0 - random.nextDouble() * (1.0 - cosHalf);
            nee += rho / Math.PI * sun * cosTheta * omega;
            // BSDF: a cosine-weighted direction; its weight is rho, and it pays the disc if it lands inside the cone.
            double z = Math.sqrt(random.nextDouble());
            if (z >= cosHalf) escape += rho * sun;
        }
        nee /= samples;
        escape /= samples;
        double exact = rho / Math.PI * sun * omega * (1.0 + cosHalf) / 2.0;
        assertEquals(exact, nee, exact * 0.001, "NEE alone is the direct term");
        assertEquals(exact, escape, exact * 0.02, "the disc on a non-delta escape is the same term a second time");
    }

    @Test
    void theDiscsAreSeenOnlyWhereNoNeeStoodIn() throws IOException {
        String sky = read(SKY);
        assertTrue(sky.contains("    float3 skyRadiance(float3 direction);"),
            "the environment names its body-free part");
        assertTrue(sky.contains("    float3 skyRadiance(float3 direction) {\n        return dome.radiance(direction);\n    }"),
            "the sun-and-moon sky leaves its discs to NEE there");
        String tracer = read(TRACER);
        assertTrue(tracer.contains("    bool bodiesVisible = true;"), "the primary ray sees the discs");
        assertTrue(tracer.contains("float3 sky = bodiesVisible ? environment.radiance(escape) : environment.skyRadiance(escape);"),
            "an escape pays the discs only when no NEE stood in for them");
        assertTrue(tracer.contains("        bodiesVisible = bsdf.delta;"),
            "a delta continuation still sees them: its vertex's NEE added nothing");
        assertTrue(tracer.indexOf("        bodiesVisible = bsdf.delta;") > tracer.indexOf("BSDFSample bsdf = bsdfSample("),
            "decided by the lobe this vertex drew");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
