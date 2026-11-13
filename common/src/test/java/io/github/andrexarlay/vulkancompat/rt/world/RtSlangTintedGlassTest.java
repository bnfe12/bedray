package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.material.OpticalMaterials;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla tinted_glass is see-through to the camera but blocks ALL light. The baker tags it with a dedicated optical
 * profile id ({@link OpticalMaterials#TINTED_GLASS_PROFILE_ID}) in the high source-metadata bits the atlas already carries.
 * These guards pin the Slang chain that turns that id into a light blocker: the contract flag, the surface decode, and the
 * two visibility walks that stop sun/shadow rays on it. The camera pane filter must NOT read the flag, so the pane stays a
 * translucent filter on the view ray (no new GPU binding is needed for the sentinel).
 */
class RtSlangTintedGlassTest {
    private static final String MATERIAL = "src/main/resources/shaders/slang/contracts/material.slang";
    private static final String SURFACE = "src/main/resources/shaders/slang/surface/surface.slang";
    private static final String VISIBILITY = "src/main/resources/shaders/slang/surface/visibility.slang";
    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";

    @Test
    void theContractDefinesTheLightBlockerFlagAndSentinel() {
        String material = read(MATERIAL);
        assertTrue(material.contains("MATERIAL_FLAG_BLOCKS_LIGHT = 1u << 5u"),
            "a free flag bit above SUBSURFACE (1u << 4u): the pane blocks light without becoming opaque");
        assertTrue(material.contains("OPTICAL_PROFILE_TINTED_GLASS = " + OpticalMaterials.TINTED_GLASS_PROFILE_ID + "u"),
            "the shader sentinel must equal OpticalMaterials.TINTED_GLASS_PROFILE_ID, or the decode reads the wrong slot");
        assertTrue(material.contains("bool materialBlocksLight(MaterialSample material)"),
            "one shared predicate, like materialIsTranslucent, so every walk applies the same rule");
        assertTrue(material.contains("(material.flags & MATERIAL_FLAG_BLOCKS_LIGHT) != 0u"),
            "the predicate reads the flag bit");
    }

    @Test
    void surfaceDecodesTheBlockerProfileIntoTheFlag() {
        String surface = read(SURFACE);
        assertTrue(surface.contains("(legacySourceFlags >> 3u) == OPTICAL_PROFILE_TINTED_GLASS"),
            "the high source bits are the optical profile id; the tinted_glass sentinel selects the blocker");
        assertTrue(surface.contains("material.flags |= MATERIAL_FLAG_BLOCKS_LIGHT"),
            "the decode raises the flag the visibility walk reads");
    }

    @Test
    void bothVisibilityWalksStopLightOnTheFlag() {
        String visibility = read(VISIBILITY);
        assertEquals(2, count(visibility, "materialBlocksLight(surface.material)"),
            "both the plain and the medium-aware walk must block tinted_glass, not just one");
        assertTrue(visibility.contains("if (materialBlocksLight(surface.material)) {"),
            "the blocker is a hard stop, not a transmittance filter");
    }

    @Test
    void theCameraPaneFilterStaysSeeThrough() {
        String tracer = read(TRACER);
        assertFalse(tracer.contains("MATERIAL_FLAG_BLOCKS_LIGHT"),
            "the camera path must not block on the light flag: tinted_glass stays see-through to the eye");
        assertFalse(tracer.contains("materialBlocksLight"),
            "the light blocker belongs to the visibility walks only, never to the view ray");
    }

    private static int count(String source, String needle) {
        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf(needle, at);
            if (found < 0) return hits;
            hits++;
            at = found + needle.length();
        }
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}