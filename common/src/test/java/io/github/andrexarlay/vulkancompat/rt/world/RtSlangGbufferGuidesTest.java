package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Slang path fills the DLSS-RR guide channels out of the primary vertex it already traced: view depth, normal +
 * roughness, the direct and residual radiance split, albedo and the specular-albedo guide. The denoiser, its input pack
 * and the compose are unchanged, so the guides have to stay in the legacy {@code rt_path.comp} layout.
 * <p>
 * Four ways this silently breaks: a guide recomputed from a second primary ray that disagrees with the shaded one, a
 * split radiance that no longer sums back to the colour input (a bright halo around every light), a sky pixel packed as
 * a surface (the denoiser smears the horizon), and a host that sets the push flag without binding the images, or binds
 * them with the flag clear (stale texels denoised as if they were this frame).
 */
class RtSlangGbufferGuidesTest {

    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String TRACER = SHADERS + "/pathtrace/path_tracer.slang";
    private static final String PASS = SHADERS + "/passes/path_trace.slang";
    private static final String SCENE_PASS = SHADERS + "/passes/path_trace_scene.slang";

    @Test
    void theGuideChannelsAreThePrimaryVertexThePathShaded() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("struct PathPrimaryGuide {"), "the guide is filled by the tracer, not re-derived by the pass");
        String capture = block(tracer, "if (receiver.primary) {");
        assertTrue(capture.contains("result.primary.position = hit.position;"), "the shaded vertex's own position");
        assertTrue(capture.contains("result.primary.normal = normal;"), "the view-facing normal the BSDF used");
        assertTrue(capture.contains("result.primary.albedo = clamp(surface.material.albedo, float3(0.02), float3(1.0));"),
            "the demodulation needs the same albedo the radiance was built from, floored as in rt_path.comp");
        assertTrue(capture.contains("result.primary.direct = direct;"), "the primary vertex's own NEE, not a re-trace");
        assertTrue(capture.contains("result.primary.roughness = surface.material.roughness;"), "the material roughness");
        assertTrue(capture.contains("result.primary.metallic = surface.material.metallic;"),
            "the specular-albedo guide rule needs the metal flag");
    }

    @Test
    void theSplitRadianceStillSumsToTheColourInput() {
        for (String pass : new String[] {PASS, SCENE_PASS}) {
            String source = read(pass);
            String guides = block(source, "void pathTraceWriteGuides(");
            assertTrue(guides.contains("outDiffuse[p] = float4(result.radiance - result.primary.direct, result.firstHitDistance);"),
                pass + ": the residual is the whole signal minus the direct term the pack adds back");
            assertTrue(guides.contains("outDirect[p] = float4(result.primary.direct, 0.0);"),
                pass + ": the direct term is the one the residual removed");
            assertTrue(guides.contains("outSpecular[p] = float4(0.0);"),
                pass + ": the path tracer has no split specular channel, exactly as rt_path.comp writes zeros");
            assertTrue(guides.contains("outAlbedo[p] = float4(result.primary.albedo, 1.0);"), pass + ": albedo guide");
            assertTrue(guides.contains("outNormalRough[p] = float4(n, clamp(result.primary.roughness, 0.0, 1.0));"),
                pass + ": normal + roughness");
            assertTrue(guides.contains("float viewZ = -dot(toSurface, f.camera.forward.xyz);"),
                pass + ": NRD's view depth is backwards along the view axis, as in rt_path.comp");
            assertTrue(guides.contains("result.primary.metallic > 0.5 || result.primary.roughness < 0.6"),
                pass + ": the legacy specular-albedo rule (metal or glossy only)");
        }
    }

    @Test
    void aSkyPixelLooksLikeSkyToTheDenoiser() {
        for (String pass : new String[] {PASS, SCENE_PASS}) {
            String source = read(pass);
            String guides = block(source, "void pathTraceWriteGuides(");
            assertTrue(guides.contains("if (!result.primary.valid) {"), pass + ": a miss has no surface to guide on");
            assertTrue(guides.contains("outViewZ[p] = 1.0e6;"), pass + ": the sky depth rt_path.comp writes");
            assertTrue(guides.contains("outNormalRough[p] = float4(0.0, 0.0, 1.0, 1.0);"), pass + ": the flat sky normal");
            assertTrue(guides.contains("outAlbedo[p] = float4(0.0);"), pass + ": zero albedo keeps sky out of the demodulation");
        }
    }

    @Test
    void theGuidesAreWrittenOnlyWhenTheHostAsksForThem() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("static const uint PATH_TRACE_OPTION_GBUFFER        = 1u << 0u;"),
            "the option bit is the host contract for the guides");
        for (String pass : new String[] {PASS, SCENE_PASS}) {
            String source = read(pass);
            assertTrue(source.contains("if ((options.flags & PATH_TRACE_OPTION_GBUFFER) != 0u) {"),
                pass + ": the guide stores are gated by the option bit");
            assertTrue(source.contains("pathTraceWriteGuides(p, f, result);"), pass + ": one write site");
        }
        // The option word is the push's fifth field, which the host leaves zero unless it binds the images.
        String host = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java");
        assertTrue(host.contains("int optionFlags = guides != null ? RtPathTraceSlangPass.OPTION_GBUFFER : 0;"),
            "the push flag follows the bound guides, never a setting");
        String record = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java");
        assertTrue(record.contains("static final int OPTION_GBUFFER = 1;"), "the Java mirror of the shader option bit");
        assertTrue(record.contains("if (wantsGuides != (boundGuides != null)) {"),
            "record refuses a flag without images and images without the flag");
    }

    @Test
    void aShortGuideArrayIsAHostBug() {
        assertThrows(IllegalArgumentException.class, () -> RtPathTraceSlangPass.Guides.of(new long[] {1L, 2L, 3L}),
            "six guides would leave one binding stale");
        assertTrue(RtPathTraceSlangPass.Guides.of(null) == null, "no G-buffer, no guides");
        RtPathTraceSlangPass.Guides seven = RtPathTraceSlangPass.Guides.of(new long[] {1L, 2L, 3L, 4L, 5L, 6L, 7L});
        assertTrue(seven.complete(), "seven non-zero views are a complete G-buffer");
        assertFalse(RtPathTraceSlangPass.Guides.of(new long[] {1L, 2L, 3L, 4L, 5L, 6L, 0L}).complete(),
            "a zero view is not a guide");
    }

    @Test
    void theGuideOrderIsTheDenoiserOrder() {
        // Guides.of reads views[0..6] as viewZ, normalRough, diffuse, albedo, direct, specular, specAlbedo. Both
        // denoisers hand the G-buffer over in that order, and swapping two would denoise against another channel.
        String record = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java");
        assertTrue(record.contains("viewZ != 0L && normalRough != 0L && diffuse != 0L && albedo != 0L && direct != 0L"),
            "the completeness check lists the seven channels in bind order");
        for (String denoiser : new String[] {"DlssRrDenoiser.java", "NrdDenoiser.java"}) {
            String source = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/denoise/" + denoiser);
            assertTrue(source.contains("new long[] {gbViewZ.view, gbNormalRough.view, gbDiffuse.view, gbAlbedo.view, gbDirect.view,"),
                denoiser + ": viewZ, normalRough, diffuse, albedo, direct...");
            assertTrue(source.contains("gbSpecular.view, gbSpecAlbedo.view};"),
                denoiser + "... then specular and the specular albedo");
        }
    }

    @Test
    void theRendererKeepsTheProvenPassUnlessRRIsAskedFor() {
        String renderer = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java");
        assertTrue(renderer.contains("denoiseMode != DenoiserMode.DLSS_RR"), "only the RR layout consumes these guides");
        assertTrue(renderer.contains("the \" + denoiseMode + \" layout needs the world-space motion buffer\""),
            "NRD is refused with its real blocker: the motion buffer");
        assertTrue(renderer.contains("RtSlangPathPass.supportProblem(runtime, gbuffer)"),
            "the native core must support the requested outputs before the pass is created");
        String backend = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/NativePathTraceBackend.java");
        assertTrue(backend.contains("guides && !runtime.library().hasNativeGuideOutputs()"),
            "a guide request needs the additive native feature and export");
        assertTrue(renderer.contains("AUTO keeps the GLSL path when a denoiser is selected"),
            "AUTO does not silently move a denoiser onto unproven guides");
        String pass = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java");
        String uses = block(pass, "public boolean usesGbuffer() {");
        assertTrue(uses.contains("return requestedGbuffer;"),
            "usesGbuffer reports what the renderer asked for, so the denoiser is created and the views reach bind()");
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
