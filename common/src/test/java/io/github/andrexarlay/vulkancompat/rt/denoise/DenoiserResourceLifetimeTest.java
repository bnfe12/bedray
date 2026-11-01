package io.github.andrexarlay.vulkancompat.rt.denoise;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DenoiserResourceLifetimeTest {
    @Test void preparationAlonePinsImagesBeforePackHasAnyDescriptors() {
        var pins = new DenoiserRecordingPins();
        pins.retain(17);
        assertThrows(IllegalStateException.class, () -> pins.requireUnreferenced());
        pins.release(17);
        assertDoesNotThrow(() -> pins.requireUnreferenced());
    }

    @Test void completingOneRecordingCannotReleaseAnotherGenerationUse() {
        var pins = new DenoiserRecordingPins();
        pins.retain(17);
        pins.retain(29);
        pins.release(17);
        pins.release(17); // a delayed repeated callback cannot affect recording 29
        assertThrows(IllegalStateException.class, () -> pins.requireUnreferenced());
        pins.release(29);
        assertDoesNotThrow(() -> pins.requireUnreferenced());
        assertThrows(IllegalArgumentException.class, () -> pins.retain(0));
    }

    @Test void aWholeHostDrainIsAnExplicitAlternativeProof() {
        var pins = new DenoiserRecordingPins();
        pins.retain(17);
        pins.retain(29);
        pins.clearAfterHostDrain();
        assertDoesNotThrow(() -> pins.requireUnreferenced());
    }

    @Test void aBlockedReplacementNamesItsOwnerAndExactPendingCommands() {
        var pins = new DenoiserRecordingPins();
        pins.retain(0x1a2b);
        pins.retain(-1L);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> pins.requireUnreferenced("NRD stage=ensure profile=reblur generation=3 extent=1280x720 requestedExtent=1920x1080"));
        assertTrue(failure.getMessage().contains("stage=ensure profile=reblur generation=3"));
        assertTrue(failure.getMessage().contains("extent=1280x720 requestedExtent=1920x1080"));
        assertTrue(failure.getMessage().contains("pendingCommands=[0x1a2b, 0xffffffffffffffff]"));
    }

    @Test void frameResourcesDrainBeforeReplacementAndBeforeAllocatingTheNextCommand() throws Exception {
        String renderer = source("world/RtFrameRenderer.java");
        String frame = body(renderer, "private static void frame(");
        assertTrue(frame.indexOf("prepareFrameResources(device, target.width, target.height)")
            < frame.indexOf("encoder.allocateAndBeginTransientCommandBuffer()"));
        String preflight = body(renderer, "private static void prepareFrameResources(");
        int drain = preflight.indexOf("drainHostSubmissions(device, \"frame_resources_drain\")");
        assertTrue(drain >= 0 && drain < preflight.indexOf("replaceOutputImages(w, h)"));
        assertTrue(drain < preflight.indexOf("dlssRrDenoiser.ensure(w, h, output)"));
        assertTrue(drain < preflight.indexOf("denoiser.ensure(w, h, output.view)"));
        assertTrue(drain < preflight.indexOf("ensureEntityMotionBuffer(w, h)"));
        String record = body(renderer, "private static void recordPrimaryRays(");
        assertFalse(record.contains(".ensure(w, h, output"));
        assertFalse(record.contains("vkDeviceWaitIdle"));
        assertFalse(record.contains("ensureEntityMotionBuffer("));
        assertTrue(record.contains("requireEntityMotionBuffer(w, h)"));
        String replace = body(renderer, "private static void replaceOutputImages(");
        assertTrue(replace.indexOf("nextComposite = memory.createImage") < replace.indexOf("memory.destroy(output)"));
        assertTrue(replace.indexOf("throw failure;") < replace.indexOf("memory.destroy(output)"),
            "allocation failure must preserve the old complete triplet");
    }

    @Test void neitherDenoiserCanFreeGuidesWhileOnlyPreparationReferencesThem() throws Exception {
        for (String file : new String[]{"NrdDenoiser.java", "DlssRrDenoiser.java"}) {
            String denoiser = source("denoise/" + file);
            String prepare = body(denoiser, "public void prepare(VkCommandBuffer cmd)");
            assertTrue(prepare.indexOf("recordingPins.retain(cmd.address())") < prepare.indexOf("initImages(cmd)"));
            String ensure = body(denoiser, file.startsWith("Nrd")
                ? "public void ensure(int w, int h, long outputView)"
                : "public void ensure(int w, int h, long outputImage, long outputView)");
            assertTrue(ensure.indexOf("recordingPins.requireUnreferenced(") < ensure.indexOf("destroyImages()"));
            String destroy = body(denoiser, "public void destroy()");
            assertTrue(destroy.indexOf("recordingPins.requireUnreferenced(")
                < destroy.indexOf(file.startsWith("Nrd") ? "destroyAll()" : "destroyImages()"));
            for (String method : new String[]{"releaseRecording", "discardRecording"}) {
                String release = body(denoiser, "public void " + method + "(long command)");
                assertTrue(release.indexOf("packPipeline." + method + "(command)")
                    < release.indexOf("recordingPins.release(command)"),
                    "a failed child release keeps the generation pinned for retry");
            }
        }
    }

    @Test void failuresKeepResourceContextAndTheOriginalThrowableWithoutHotLoopLogging() throws Exception {
        String renderer = source("world/RtFrameRenderer.java");
        String preflight = body(renderer, "private static void prepareFrameResources(");
        assertTrue(preflight.contains("new RtFailure(\"frame_resources_\" + stage"));
        for (String context : new String[]{"profile=", "previousExtent=", "requestedExtent=", "previousOutputView=0x", "currentOutputView=0x"})
            assertTrue(preflight.contains(context), context);
        assertTrue(preflight.contains("failure);"));
        assertFalse(preflight.contains("LOGGER."), "the existing frame failure boundary logs the contextual cause once");
        assertTrue(body(renderer, "private static void destroyFailedOutput(").contains("original.addSuppressed(cleanup)"));
        for (String file : new String[]{"NrdDenoiser.java", "DlssRrDenoiser.java"}) {
            String denoiser = source("denoise/" + file);
            String context = body(denoiser, "private String resourceContext(");
            for (String name : new String[]{"profile=", "generation=", "extent=", "requestedExtent=", "outputView=0x", "requestedView=0x", "command=0x"})
                assertTrue(context.contains(name), file + " " + name);
            for (String method : new String[]{"prepare(VkCommandBuffer cmd)", "releaseRecording(long command)", "discardRecording(long command)"}) {
                String operation = body(denoiser, "public void " + method);
                assertTrue(operation.contains("resourceContext("), file + " " + method);
                assertTrue(operation.contains(", failure)"), "the original native/host exception remains the cause");
            }
        }
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt", relative))
            .replace("\r\n", "\n");
    }

    private static String body(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int open = source.indexOf('{', start), end = open + 1, depth = 1;
        while (end < source.length() && depth != 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        assertTrue(open >= 0 && depth == 0, marker);
        return source.substring(open + 1, end - 1);
    }
}
