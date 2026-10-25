package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the pixelated-shadow look ({@code RtSettings.raysPixelShadows}).
 *
 * <p>The option evaluates the sun/moon visibility once per world shadow cell instead of once per pixel, which makes the
 * shadow edge a staircase at block-texture resolution. That is legitimate only under three rules, and the tests below
 * pin each of them so a later edit cannot quietly turn the look back into the shortcut it used to be:
 * <ol>
 *   <li>the whole policy lives in one module ({@code rt_common_pixel_shadow.glsl}) and every shaded pass goes through it;</li>
 *   <li>it moves the receiver only, and a cell that crosses geometry keeps the exact origin instead of being lifted along
 *       the normal (the old {@code min(0.05 + 0.2 * cell, 0.4)} lift detached shadows from their casters);</li>
 *   <li>only the sun/moon term is quantised: ambient occlusion, traced sky light, block emitters and the deeper path
 *       vertices stay exact, and moving geometry (entities, foliage cards) never gets a world-locked origin.</li>
 * </ol>
 * The Java mirrors of the snap and of the cell index below are the numeric half: the source checks assert that the shader
 * still contains the formula the mirror evaluates, so the two cannot drift apart in silence.
 */
public class RtPixelShadowSamplingTest {
    private static final String MODULE = "/shaders/rt_common_pixel_shadow.glsl";
    private static final String SAMPLING = "/shaders/rt_common_sampling.glsl";
    private static final String TEMPORAL = "/shaders/rt_common_temporal.glsl";
    private static final String SHADE = "/shaders/rt_common_shade.glsl";
    private static final String EMITTERS = "/shaders/rt_common_emitters.glsl";
    private static final String PATH = "/shaders/rt_path.comp";
    private static final String RAYS = "/shaders/rt_rays.comp";
    private static final String CONFIG = "src/main/java/io/github/andrexarlay/vulkancompat/sodium/VulkanSodiumConfig.java";
    private static final String CATALOG = "src/main/java/io/github/andrexarlay/vulkancompat/sodium/SettingsCatalog.java";

    private static final String ORIGIN_SIG = "vec3 pixelShadowOrigin(Surface s, vec3 o)";
    private static final String APPLIES_SIG = "bool pixelShadowApplies(Surface s, bool refracted)";
    private static final String CELL_SIG = "ivec2 pixelShadowCellIndex(vec3 worldPos)";
    private static final String ROTATION_SIG = "ivec2 sunShadowRotation(Surface s, bool cellShadow, ivec2 pixel, bool jittered)";
    private static final String DISK_SIG =
        "vec3 sunShadowDirectionAt(vec3 d, float radius, ivec2 rotation, uint frame, uint i, uint perFrame)";

    @Test
    public void policyIsOwnedByOneModule() {
        String module = load(MODULE);
        assertTrue(module.contains("float shadowPixelSize()"), "the option value is read in one place");
        assertTrue(module.contains(APPLIES_SIG), "one gate decides whether a fragment is sampled per cell");
        assertTrue(module.contains(ORIGIN_SIG), "one function produces the shared origin");
        assertTrue(module.contains(CELL_SIG), "one function produces the shared sun-disk rotation");
        // The policy used to live in the temporal module, where nothing about it is temporal. A second copy would let the
        // passes drift apart again (that is how the path tracer ended up with a per-pixel sun sample).
        assertFalse(load(TEMPORAL).contains("pixelShadowOrigin"), "the policy must not be duplicated in rt_common_temporal.glsl");
        assertFalse(load(TEMPORAL).contains("shadowPixelSize()"), "the option accessor moved into the module");
        assertFalse(load(SAMPLING).contains("pixelShadowOrigin"), "the policy must not be duplicated in rt_common_sampling.glsl");
    }

    @Test
    public void cellsThatCrossGeometryKeepExactShadows() {
        String body = functionBody(load(MODULE), ORIGIN_SIG);
        // The shared origin is only used when it can see the fragment it replaces (blocks are convex, so a crossed step,
        // slab or wall shows up as a blocked short segment). Without this test the only way to hide the artefact is the
        // normal lift, which is what made shadows detach from their casters.
        assertTrue(body.contains("traceVisibility("), "the shared origin must be validated against the geometry it crosses");
        // Rejected sharing returns the caller's own origin, so a caller never has to know the difference.
        assertTrue(count(body, "return o;") >= 3, "every refusal path hands the exact origin back");
        assertFalse(body.contains("s.ng * min("), "no normal lift: it detached shadows from their casters");
        assertFalse(body.contains("0.2 * cell"), "no normal lift: it detached shadows from their casters");
        assertFalse(body.contains("0.4"), "no lift cap: a 0.4-block offset leaked light through slabs and ceilings");
    }

    @Test
    public void snapIsTheCellCentreInTheSurfacePlane() {
        String body = functionBody(load(MODULE), ORIGIN_SIG);
        assertTrue(body.contains("(floor(w.x / cell) + 0.5) * cell - w.x"), "x snap formula (mirrored by the Java test below)");
        assertTrue(body.contains("(floor(w.y / cell) + 0.5) * cell - w.y"), "z snap formula (mirrored by the Java test below)");
        assertTrue(body.contains("lateral -= s.ng * dot(lateral, s.ng);"),
            "the displacement stays in the surface plane: a naive world snap pushes wall fragments into the wall");

        for (float cell : new float[] {1.0f / 16.0f, 0.25f, 1.0f}) {
            for (int i = 0; i < 4096; i++) {
                float w = (i * 7.31f) % 2000.0f - 1000.0f;
                float centre = snapCell(w, cell);
                assertTrue(Math.abs(centre - w) <= cell * 0.5f + 1.0e-4f, "a snap never moves more than half a cell: " + w);
                assertEquals(centre, snapCell(centre, cell), 1.0e-4f, "a cell centre is its own cell centre");
                assertEquals(cellIndex(w, cell), cellIndex(centre, cell), 1.0e-4f, "the snap stays inside its own cell");
            }
        }
        float cell = 1.0f / 16.0f;
        assertEquals(snapCell(10.001f, cell), snapCell(10.06f, cell), "every fragment of a cell shares the same origin");
        assertNotEquals(snapCell(10.001f, cell), snapCell(10.08f, cell), "the next cell gets its own origin");
    }

    @Test
    public void cellIndexIsWorldLockedAndNotTruncated() {
        String body = functionBody(load(MODULE), CELL_SIG);
        assertTrue(body.contains("vec2(floor(worldPos.xz / cell))"), "the index is the world cell, not the screen pixel");
        assertFalse(body.contains("1023"), "a 1023 mask would repeat the whole pattern every 1024 cells (64 blocks at size 1)");
        // Surface.worldPos is world-anchored modulo 1024 blocks, so the default 1/16-block cell index stays below 16384 and
        // is exact in float: the mask was protecting precision that the wrap already provides.
        assertEquals(16383.0f, cellIndex(1023.99f, 1.0f / 16.0f), "the largest wrapped cell index is exact in float");
        assertNotEquals(cellIndex(3.99f, 1.0f / 16.0f), cellIndex(4.01f, 1.0f / 16.0f), "a cell boundary moves the index");
    }

    @Test
    public void entitiesAndFoliageStayExact() {
        String body = functionBody(load(MODULE), APPLIES_SIG);
        assertTrue(body.contains("shadowPixelSize() > 0.0"), "the option gates everything");
        assertTrue(body.contains("!refracted"), "a refracted (under-water) shadow ray was already re-origined by the water beam");
        assertTrue(body.contains("!isEntityHit(s.slot)"),
            "an entity is not part of the block grid: a world-locked origin would leave its shadow behind while it walks");
        assertTrue(body.contains("!s.isFoliage"),
            "a foliage card's shadow ray has to start on its sun side; the cell centre cannot guarantee that");
    }

    @Test
    public void sunDiskSampleFollowsTheCell() {
        String body = functionBody(load(SAMPLING), DISK_SIG);
        assertTrue(body.contains("pixelOffset(rotation)"),
            "the disk point is a function of the rotation index: that is what gives a whole cell one penumbra sample");
        assertTrue(body.contains("(frame & 1023u) * perFrame + i"),
            "the frame is wrapped exactly like noise2D, so the sequence stays exact in a long session");
        for (String path : new String[] {PATH, RAYS, SAMPLING, MODULE}) {
            assertFalse(load(path).contains("gSunPixel"),
                path + ": the per-thread sample state is gone, the rotation is passed explicitly");
            assertFalse(load(path).contains("gSunIndex"),
                path + ": the per-thread sample state is gone, the rotation is passed explicitly");
        }
        assertTrue(load(PATH).contains("sunShadowRotation("), "rt_path.comp takes the rotation from the module");
        assertTrue(load(RAYS).contains("sunShadowRotation("), "rt_rays.comp takes the rotation from the module");
        // Off, and the camera has not moved: the sample is the pixel itself, exactly as every build before the option.
        String rotation = functionBody(load(MODULE), ROTATION_SIG);
        assertTrue(rotation.contains("(shadowPixelSize() > 0.0 || jittered) ? pixelShadowStableCell(s.worldPos) : pixel"),
            "switching the option off restores the per-pixel penumbra");
    }

    @Test
    public void everyShadedPassUsesTheModule() {
        for (String path : new String[] {PATH, RAYS, SHADE}) {
            String src = load(path);
            assertTrue(src.contains("pixelShadowApplies("), path + " must use the module gate");
            assertTrue(src.contains("pixelShadowOrigin("), path + " must use the module origin");
            assertFalse(src.contains("shadowPixelSize() > 0.0"),
                path + " must not re-implement the gate: the trimmed copy is how entity and foliage geometry got snapped");
        }
        assertTrue(load(PATH).contains("pixelShadowApplies(s, med.inside)"), "the path tracer is refracted under water");
        assertTrue(load(RAYS).contains("pixelShadowApplies(s, underwater)"), "the ray pass is refracted when it takes the water beam");
        assertTrue(load(SHADE).contains("pixelShadowApplies(s, false)"),
            "secondary shading never refracts its shadow ray through the water beam, so its shadow ray is not refracted and "
                + "takes the cell origin: a mirror must show the same shadow edges as the floor it reflects");

        String rays = load(RAYS);
        String path = load(PATH);
        String shade = load(SHADE);
        // One construction per shadow ray: the shared origin is not a value to be passed around, and the validation ray
        // inside it must not run twice for one fragment.
        assertEquals(1, count(rays, "pixelShadowOrigin("), "rt_rays.comp builds the shadow origin once");
        assertEquals(1, count(path, "pixelShadowOrigin("), "rt_path.comp builds the shadow origin once");
        assertEquals(2, count(shade, "pixelShadowOrigin("), "both secondary shaders build the shadow origin once each");
        // The exact origin stays the origin of every ray that is not the sun/moon visibility ray.
        assertFalse(rays.contains("traceTransmittance(o,"), "no shadow path kept the unsnapped origin");
        assertFalse(path.contains("traceTransmittance(so,"), "the path tracer traces NEE from the shared origin");
        assertTrue(rays.contains("traceDist(o, d, radius)"), "AO keeps the exact origin");
        assertTrue(rays.contains("traceVisibility(o, d, radius)"), "AO keeps the exact origin");
        assertTrue(rays.contains("traceSecondary(o, "), "GI and reflection rays keep the exact origin");
        assertTrue(rays.contains("leafShadowOrigin(s, L)"), "a foliage shadow ray starts on the leaf's sun side, not on a cell");
    }

    @Test
    public void blockEmittersAreNotQuantized() {
        String emitters = load(EMITTERS);
        assertFalse(emitters.contains("pixelShadow"), "block-emitter light keeps the exact per-pixel origin");
        assertTrue(emitters.contains("s.pos + s.ng * 0.005"), "emitter shadows start on the surface, snapped by nobody");
    }

    @Test
    public void optionLabelsMatchTheRealCost() throws IOException {
        String config = read(CONFIG);
        // "builder, \"id\"" is the option registration itself; the bare id also appears in the Identifier declarations.
        String shadow = optionBlock(config, "builder, \"rays_pixel_shadows\"");
        assertTrue(shadow.contains("OptionImpact.LOW"),
            "the option adds one validation ray of at most half a cell per shaded hit, not a MEDIUM/HIGH cost");
        assertFalse(shadow.contains("OptionImpact.HIGH"), "HIGH was a false claim for a look option");
        String size = optionBlock(config, "builder, \"rays_pixel_shadow_size\"");
        assertTrue(size.contains("OptionImpact.LOW"), "the cell size changes the look, not the ray count");
        assertFalse(size.contains("OptionImpact.HIGH"), "the size cost nothing and was labelled HIGH");

        String catalog = read(CATALOG);
        assertTrue(catalog.contains("the sun and moon shadow is sampled once per world cell"),
            "the description must say what is quantised");
        assertTrue(catalog.contains("light from glowing blocks stay exact"),
            "the description must say what is not quantised");
        assertTrue(catalog.contains("cells that cross geometry keep exact shadows"),
            "the description must not promise blocky edges that the shader refuses to produce");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static float cellIndex(float world, float cell) {
        return (float) Math.floor(world / cell);
    }

    /** Java mirror of the GLSL snap: the centre of the world cell that contains {@code world}. */
    private static float snapCell(float world, float cell) {
        return cell <= 0.0f ? world : (cellIndex(world, cell) + 0.5f) * cell;
    }

    private static String load(String resource) {
        String src = RtShaderCompiler.loadSource(resource);
        assertNotNull(src, "shader source missing: " + resource);
        return src;
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    /** Body of a GLSL function (from the opening brace to its matching close). */
    private static String functionBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "not found, the check would pass on nothing: " + signature);
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body for " + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("unbalanced body for " + signature);
    }

    /** One {@code look.addOption(...)} call of the Sodium config, from the option id to the next call. */
    private static String optionBlock(String source, String id) {
        int at = source.indexOf(id);
        assertTrue(at >= 0, "option not registered: " + id);
        int next = source.indexOf("look.addOption(", at + id.length());
        return source.substring(at, next < 0 ? source.length() : next);
    }
}
