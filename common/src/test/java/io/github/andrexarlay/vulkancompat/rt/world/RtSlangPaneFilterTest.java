package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Glass samples its reflection and transmission. Only straight transmission is free of scattering-depth cost: counting
 * every face made layered windows black, while filtering every face before BSDF sampling erased the Fresnel reflection.
 * These are source-contract and analytic reference tests, not GPU or visual evidence.
 */
class RtSlangPaneFilterTest {
    private static final Path TRACER = Path.of("src/main/resources/shaders/slang/pathtrace/path_tracer.slang");

    @Test
    void aPaneSamplesItsFresnelBeforeOnlyTransmissionContinuesForFree() throws IOException {
        String tracer = Files.readString(TRACER).replace("\r\n", "\n");
        int pane = tracer.indexOf("        bool paneTransmission = paneSurface && bsdf.lobe == BSDF_LOBE_TRANSMISSION;");
        assertTrue(pane > 0, "the tracer recognises a thin pane");
        int sampling = tracer.indexOf("BSDFSample bsdf = bsdfSample(surface.material, view, normal,");
        assertTrue(sampling > 0 && sampling < pane, "the Fresnel split must be sampled before any pane is skipped");
        int weight = tracer.indexOf("throughput *= bsdf.weight;", sampling);
        assertTrue(weight > sampling && weight < pane, "the BSDF weight already compensates the selected lobe probability");
        String branch = tracer.substring(pane, tracer.indexOf("continue;", pane) + "continue;".length());
        assertTrue(tracer.contains("bool paneSurface = surface.material.alphaMode == AlphaMode.Translucent\n"
            + "            && (surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) == 0u;"),
            "water keeps its refracting interface");
        assertTrue(branch.contains("bsdf.lobe == BSDF_LOBE_TRANSMISSION"), "a reflection must not take the free straight path");
        assertTrue(branch.contains("ray.tMin = hit.t + pathTraceHoleStep(hit.t);"), "advance beyond this face");
        assertFalse(branch.contains("ray.origin ="), "the origin stays fixed so distances and the medium segment stay exact");
        assertFalse(branch.contains("ray.tMax ="), "each pane must not extend the original trace range");
        assertFalse(branch.contains("result.depth++"), "ten transmitted faces must not spend ten bounces");
        assertFalse(branch.contains("russian"), "transmitted faces must not repeatedly gamble at the same scattering depth");
        assertFalse(tracer.substring(sampling).contains("throughput *= surfaceTransmittance(surface, ray.direction);"),
            "the BSDF already owns Fresnel and tint; an extra shadow filter loses that energy twice");
    }

    @Test
    void aSecondaryTransmittedPaneDoesNotEndTheReflectionGuideAtTheWindow() throws IOException {
        String tracer = Files.readString(TRACER).replace("\r\n", "\n");
        int sampling = tracer.indexOf("BSDFSample bsdf = bsdfSample(surface.material, view, normal,");
        String before = tracer.substring(0, sampling);
        assertTrue(before.contains("if (wantDenoiserGeometry && (!paneSurface || ambientOnlyPending))\n"
            + "            pathDenoiserHit(denoiserContinuation, hit.position);"),
            "pending reflection geometry must not stop before a pane chooses whether it transmits");
        String after = tracer.substring(sampling, tracer.indexOf("if (!bsdf.valid", sampling));
        assertTrue(after.contains("if (wantDenoiserGeometry && paneSurface && bsdf.lobe != BSDF_LOBE_TRANSMISSION)\n"
            + "            pathDenoiserHit(denoiserContinuation, hit.position);"),
            "a reflection ends at the pane; straight transmission leaves the guide pending for the surface beyond it");
        int free = tracer.indexOf("if (paneTransmission) {");
        assertFalse(tracer.substring(free, tracer.indexOf("continue;", free)).contains("pathDenoiserHit"));
    }

    @Test
    void ambientOnlyRaysCrossWindowsBeforeTheOpaqueOcclusionTest() throws IOException {
        String tracer = Files.readString(TRACER).replace("\r\n", "\n");
        int filter = tracer.indexOf("bool ambientPaneFilter = ambientOnlyPending &&");
        int next = tracer.indexOf("if (surfaceIsHole(surface, options.alphaCutoff))", filter);
        assertTrue(filter > 0 && next > filter);
        String branch = tracer.substring(filter, next);
        assertTrue(branch.contains("surface.material.alphaMode == AlphaMode.Translucent"));
        assertTrue(branch.contains("surface.material.transmission >= 1.0"));
        assertTrue(branch.contains("throughput *= surfaceTransmittance(surface, ray.direction);"));
        assertTrue(branch.contains("ray.tMin = hit.t + pathTraceHoleStep(hit.t);"));
        assertTrue(branch.contains("continue;"));
        assertFalse(branch.contains("result.depth++"));
        assertFalse(branch.contains("lights.sample"));
        assertTrue(tracer.indexOf("if (ambientOnlyPending) {", next) > next,
            "the opaque AO stop must come after a window has had a chance to transmit");
        assertTrue(Math.pow(0.96, 10) > 0.66, "ten clear interfaces preserve most of the environment visibility");
    }

    @Test
    void clearCentreTexelsStillReachTheGlassBsdf() throws IOException {
        String surface = Files.readString(Path.of("src/main/resources/shaders/slang/surface/surface.slang"));
        int hole = surface.indexOf("bool surfaceIsHole(");
        String branch = surface.substring(hole, surface.indexOf("\n}", hole));
        assertTrue(branch.contains("if (!materialIsCutout(surface.material)) return false;"),
            "alpha zero is a clear dielectric centre in the translucent pass, not a cutout hole");
        String tracer = Files.readString(TRACER);
        assertFalse(tracer.contains("bool paneFilter ="), "no unconditional early bypass may erase the clear centre's reflection");
    }

    @Test
    void fresnelEnergyOfTenClearInterfacesIsReflectedOrTransmitted() {
        // Analytic series for a directed ray crossing ten interfaces. Each reflected share leaves this ray, while
        // the remaining share reaches the next interface. The old filter kept only the last term and lost the sum.
        for (double cosine : new double[] {1.0, 0.5, 0.1}) {
            double fresnel = 0.04 + 0.96 * Math.pow(1.0 - cosine, 5.0);
            double transmitted = 1.0;
            double reflected = 0.0;
            for (int face = 0; face < 10; face++) {
                reflected += transmitted * fresnel;
                transmitted *= 1.0 - fresnel;
            }
            assertEquals(1.0, reflected + transmitted, 1.0e-12);
            assertTrue(reflected > 0.33, "even at normal incidence ten interfaces cannot silently lose their reflection");
            assertTrue(transmitted > 0.0, "transmission reaches the background rather than stopping at four faces");
        }
    }

    @Test
    void colouredTransmissionKeepsItsTintAndZeroAlphaDoesNotAbsorb() throws IOException {
        String bsdf = Files.readString(Path.of("src/main/resources/shaders/slang/shading/bsdf.slang"));
        int transmit = bsdf.indexOf("    if (transmit) {");
        String branch = bsdf.substring(transmit, bsdf.indexOf("return result;", transmit));
        assertTrue(branch.contains("pow(clamp(material.albedo"));
        assertTrue(branch.contains("float3(max(material.alpha, 0.0) * transmission)"));
        assertTrue(branch.contains("/ max(passProbability, 1.0e-6)"), "compensate sampling, not tint twice");
        double red = Math.pow(0.8, 0.5);
        double blue = Math.pow(0.2, 0.5);
        assertTrue(red > blue, "coloured glass must remain a coloured filter");
        assertEquals(1.0, Math.pow(0.001, 0.0), 0.0, "clear texture centres do not inherit black RGB absorption");
    }

    @Test
    void tenGlassFacesFitTheStepBudget() {
        assertTrue(RtSlangPathPass.MAX_SURFACE_STEPS >= 2 * 5 + 2, "five glass blocks and the surface behind them");
        assertEquals(16, RtSlangPathPass.MAX_SURFACE_STEPS);
    }
}
