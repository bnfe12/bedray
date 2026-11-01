package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rt.lifetime.RecordingCompletion;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DenoiserGuidePreparationTest {
    @Test void nrdGuideTransitionsPrecedePrimaryWritesOnCreationAndResize() throws Exception {
        String renderer = body(source("world/RtFrameRenderer.java"), "private static void recordPrimaryRays(");
        int create = renderer.indexOf("createDenoiser(device, w, h)");
        int ensure = renderer.indexOf("owner.ensure(w, h, output.view)", create);
        int prepare = renderer.indexOf("owner.prepare(cmd)", ensure);
        int bind = renderer.indexOf("pass.bind(new RtFrameBindings.Views(", prepare);
        int primary = renderer.indexOf("pass.dispatch(cmd, params, w, h)", bind);
        int record = renderer.indexOf("owner.record(cmd, camera.projectionMatrix", primary);
        assertTrue(create >= 0 && ensure > create && prepare > ensure && bind > prepare && primary > bind && record > primary);
        assertFalse(renderer.substring(create, ensure).contains("else if (denoiser != null)"),
            "a just-created denoiser must prepare in the same frame");
        String nrd = source("denoise/NrdDenoiser.java");
        String preparation = body(nrd, "public void prepare(VkCommandBuffer cmd)");
        assertTrue(preparation.contains("if (!imagesReady)"));
        assertTrue(preparation.indexOf("imageInitRecording = cmd.address()") < preparation.indexOf("initImages(cmd)"));
        assertTrue(preparation.indexOf("initImages(cmd)") < preparation.indexOf("imagesReady = true"));
        assertTrue(body(nrd, "public void record(VkCommandBuffer cmd, Matrix4f projection, Matrix4f fullProjection")
            .contains("prepare(cmd)"), "standalone record callers retain an idempotent preparation fallback");
    }

    @Test void capturedInstanceProofExistsBeforeEitherDenoiserCanEmitInitializationCommands() throws Exception {
        String renderer = body(source("world/RtFrameRenderer.java"), "private static void recordPrimaryRays(");
        int rrOwner = renderer.indexOf("DlssRrDenoiser owner = dlssRrDenoiser;");
        int rrProof = renderer.indexOf("denoiserProof[0] = new Retirement", rrOwner);
        int rrPrepare = renderer.indexOf("owner.ensure(cmd, w, h, output)", rrProof);
        int nrdOwner = renderer.indexOf("NrdDenoiser owner = denoiser;");
        int nrdProof = renderer.indexOf("denoiserProof[0] = new Retirement", nrdOwner);
        int nrdEnsure = renderer.indexOf("owner.ensure(w, h, output.view)", nrdProof);
        int nrdPrepare = renderer.indexOf("owner.prepare(cmd)", nrdEnsure);
        assertTrue(rrOwner >= 0 && rrProof > rrOwner && rrPrepare > rrProof);
        assertTrue(nrdOwner >= 0 && nrdProof > nrdOwner && nrdEnsure > nrdProof && nrdPrepare > nrdEnsure);
        assertEquals(2, renderer.split("denoiserProof\\[0\\] = new Retirement", -1).length - 1,
            "each denoiser captures one instance before preparation, without a later replacement");
    }

    @Test void firstUseAbandonedBeforeDenoiserRecordStillRunsItsDiscardProof() {
        var completion = new RecordingCompletion(failure -> fail(failure));
        List<String> events = new ArrayList<>();
        RecordingCompletion.Retirement[] owner = {new RecordingCompletion.Retirement(() -> {}, () -> {})};
        var ticket = completion.beginWithDiscard(17, new RecordingCompletion.Retirement(
            () -> owner[0].completed().run(), () -> owner[0].discarded().run()));
        owner[0] = new RecordingCompletion.Retirement(() -> events.add("first-generation-completed"),
            () -> events.add("first-generation-discarded"));
        events.add("guide-layout-initialization");
        // Primary recording fails before the denoiser's record method is reached.
        completion.discard(ticket);
        assertEquals(List.of("guide-layout-initialization", "first-generation-discarded"), events);
        assertEquals(0, completion.pendingCount());
    }

    @Test void onlyTheInitializingRecordingCanRollBackItsLayoutOnDiscard() throws Exception {
        String nrd = source("denoise/NrdDenoiser.java");
        String discard = body(nrd, "public void discardRecording(long command)");
        String rollback = body(discard, "if (imageInitRecording == command)");
        assertTrue(rollback.contains("imageInitRecording = 0"));
        assertTrue(rollback.contains("imagesReady = false"));
        String completed = body(nrd, "public void releaseRecording(long command)");
        assertTrue(completed.contains("imageInitRecording == command"));
        assertFalse(completed.contains("imagesReady = false"));
        String rr = body(source("denoise/DlssRrDenoiser.java"), "public void discardRecording(long command)");
        assertTrue(body(rr, "if (imageInitRecording == command)").contains("imagesReady = false"));
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt", relative)).replace("\r\n", "\n");
    }
    private static String body(String source, String marker) {
        int start = source.indexOf(marker); assertTrue(start >= 0, marker);
        int open = source.indexOf('{', start), end = open + 1, depth = 1;
        while (end < source.length() && depth != 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++; else if (c == '}') depth--;
        }
        assertTrue(open >= 0 && depth == 0, marker);
        return source.substring(open + 1, end - 1);
    }
}
