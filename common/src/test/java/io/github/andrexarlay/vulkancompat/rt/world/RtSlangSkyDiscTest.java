package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Slang path tracer used to leave the world into a two-colour gradient: every mirror, every patch of metal and the
 * sky itself were smooth, while the body that lights the scene stayed invisible. The discs are drawn here now, and they
 * are the same two fields {@code sun_light.slang} uses for its shadow, so the picture cannot disagree with the light.
 * What these guards hold shut is the part that is easy to get wrong: where the numbers come from, how a sub-centiradian
 * edge is compared without losing it to rounding, and which of the two environment entries - the evaluated one or the
 * sampled one - is allowed to contain a disc.
 */
class RtSlangSkyDiscTest {

    private static final String SKY = "src/main/resources/shaders/slang/light/sky.slang";
    private static final String SUN = "src/main/resources/shaders/slang/light/sun.slang";
    private static final String PASS = "src/main/resources/shaders/slang/passes/path_trace.slang";
    private static final String ENTRY = "src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang";

    /** A disc is the key light seen from the outside: same direction, same radius, same radiance times intensity. */
    @Test
    void theDiscReadsTheFieldsTheKeyLightSamplerUses() {
        String sky = read(SKY);
        String disc = block(sky, "float3 celestialRadiance(SunState sun, float3 direction) {");
        assertTrue(disc.contains("sun.color.rgb * max(sun.color.w, 0.0)"),
            "the disc's radiance is the sun's radiance scaled by its intensity, not a fourth colour");
        assertTrue(disc.contains("sun.moonColor.rgb * max(sun.moonColor.w, 0.0)"),
            "and the moon's is the moon's, so one of the two can be dimmed by the host without touching the other");
        assertTrue(disc.contains("celestialDiscCoverage(sunAxis, sun.direction.w, direction)"),
            "the angular radius comes from direction.w, the field the sampler cone is built from");
        assertTrue(disc.contains("celestialDiscCoverage(moonAxis, sun.moonDirection.w, direction)"),
            "the same for the moon");

        String sampler = block(read(SUN), "struct SunMoonSampler : ILightSampler {");
        assertTrue(sampler.contains("sun.color.rgb * max(sun.color.w, 0.0)"),
            "both sides read the pair; if the sampler renames it, the disc test above and this one fail together");
        assertTrue(sky.contains("import \"light/sun.slang\";"),
            "the shared SUN_MIN_ANGULAR_RADIUS floor, not a second constant that can drift");
    }

    /**
     * The key light's angular radius is a few thousandths of a radian (the {@code sun_size_tenths} default is 0.0044).
     * Comparing the edge in cosines leaves about fifty float steps between the two limits at that magnitude, where a
     * sub-centiradian body needs a smooth limb - the rim becomes dither that moves with the frame's jitter. Sines keep
     * the same information two orders of magnitude away from that cliff, and the hemisphere guard is what pays for the
     * fact that sine cannot tell a small angle from one near pi.
     */
    @Test
    void theDiscEdgeIsMeasuredInSinesNotCosines() {
        String coverage = block(read(SKY),
            "float celestialDiscCoverage(float3 axis, float angularRadius, float3 direction) {");
        assertTrue(coverage.contains("float3 d = safeNormalize(direction, axis);"),
            "the helper normalizes what it is handed: cross() scales with the length, so a non-unit ray would "
                + "enlarge the disc rather than tilt it");
        assertTrue(coverage.contains("length(cross(d, axis))"),
            "the perpendicular distance is the sine of the angle to the body");
        assertTrue(coverage.contains("sin(max(angularRadius, SUN_MIN_ANGULAR_RADIUS))"),
            "the outer limit is the sine of the angular radius, floored by the sampler's own minimum");
        assertEquals(-1, coverage.indexOf("cos("),
            "no cosine comparison survives here: the two limits would round into each other");
        assertTrue(coverage.contains("if (dot(d, axis) <= 0.0) return 0.0;"),
            "a sine is symmetric about pi/2, so the far side of the sky has to be excluded by hand");
        assertTrue(coverage.contains("saturate((sinOuter - sinAngle)"),
            "the ramp runs from the rim inwards, so the centre of the disc is solid");
    }

    /**
     * The host keeps the key intensity at 1 through the night - it fades the key in the sky model, not in this field -
     * so a disc that only checks direction would hang a phantom sun below the horizon.
     */
    @Test
    void aSetBodyIsNotDrawn() {
        String sky = read(SKY);
        String horizon = block(sky, "float celestialHorizonFactor(float3 axis, float angularRadius) {");
        assertTrue(horizon.contains("saturate((axis.y + sinRadius) / max(2.0 * sinRadius, 1.0e-9))"),
            "the ramp spans the body's own diameter, which is what a half-set disc looks like");
        assertTrue(sky.contains("celestialHorizonFactor(sunAxis, sun.direction.w)"),
            "the sun's disc is gated on the horizon");
        assertTrue(sky.contains("celestialHorizonFactor(moonAxis, sun.moonDirection.w)"),
            "and so is the moon's");
    }

    /**
     * The environment is two different things: an evaluation on a ray that escaped, and a draw whose probability the
     * renderer already knows. Only the first may contain a disc - the sun and moon are discrete entries in the MIS list,
     * so folding their radiance into a cosine-dome draw would count each body twice and divide it by a probability that
     * never covers it.
     */
    @Test
    void onlyTheEvaluatedEnvironmentCarriesADisc() {
        String sky = read(SKY);
        String composite = block(sky, "struct SunMoonSky : IEnvironmentSampler {");
        assertTrue(composite.contains("dome.radiance(direction) + celestialRadiance(sun, direction)"),
            "the miss term pays for the dome plus the bodies");
        assertEquals(-1, composite.indexOf("celestialRadiance", composite.indexOf("SkySample sample(")),
            "the sampled draw stays disc-free: the discrete lights already carry that energy");
        assertTrue(composite.contains("return dome.sample(normal, seed);"),
            "it is the dome's own draw, whose pdf the dome's own radiance matches");
        assertTrue(composite.contains("return dome.pdf(normal, direction);"),
            "and the dome's pdf, not one that knows about the discs");
        String dome = block(sky, "struct GradientSky : IEnvironmentSampler {");
        assertEquals(-1, dome.indexOf("celestialRadiance"),
            "the gradient stays a gradient; a second consumer can still ask for the body-free dome");
    }

    /** The pass must actually build the disc-aware sampler, or all of the above is dead code. */
    @Test
    void thePassHandsTheSamplerItsSun() throws IOException {
        String entry = read(ENTRY);
        assertTrue(entry.contains("SunMoonSky environment;"),
            "the path-trace entry builds the dome-plus-disc sampler");
        assertTrue(entry.contains("environment.dome.atmosphere = f.atmosphere;"),
            "the dome comes from the frame's atmosphere");
        assertTrue(entry.contains("environment.sun = f.sun;"),
            "the bodies come from the frame's key light - the same SunState the shadow rays use");
        assertEquals(0, count(entry, "GradientSky environment;"),
            "no pass builds a bare gradient dome any more");

        try (var files = Files.walk(Path.of("src/main/resources/shaders/slang/passes"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".slang")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
                assertTrue(!source.contains("GradientSky environment")
                        || source.contains("SunMoonSky environment"),
                    path.getFileName() + " builds a disc-free dome for a traced environment");
            }
        }
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
