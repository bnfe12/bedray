package io.github.andrexarlay.vulkancompat.rt.entity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local player's model has to be in the RT scene for a reflection to show it, and the raster pass never submits it under
 * a first-person camera. These checks cover the two ways that can silently fail: capture in third person (the game already
 * submitted the player, so the body would be in the entity geometry twice) and capture after the frame was published
 * ({@code endFrame()} closes the capture, so the submission would be dropped without a trace).
 */
public class RtSelfAvatarCaptureTest {

    private static final String FRAME_RENDERER = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java";
    private static final String COLLECTOR = "src/main/java/io/github/andrexarlay/vulkancompat/rt/entity/RtCaptureCollector.java";
    private static final String CONFIG = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtConfig.java";

    @Test
    public void gateIsFirstPersonOnly() {
        assertTrue(SelfAvatarGate.shouldCapture(true, true, true, true), "first person with a usable avatar must capture");
        assertFalse(SelfAvatarGate.shouldCapture(true, true, false, true),
            "third person already captures the player through EntityRenderDispatcher: a second copy duplicates the body");
        assertFalse(SelfAvatarGate.shouldCapture(false, true, true, true), "a closed capture drops the submission");
        assertFalse(SelfAvatarGate.shouldCapture(true, false, true, true), "the option must be able to turn the capture off");
        assertFalse(SelfAvatarGate.shouldCapture(true, true, true, false),
            "no avatar state, invisible or spectator: nothing to reflect");
    }

    @Test
    public void captureHappensWhileTheCaptureIsStillOpen() {
        String src = read(FRAME_RENDERER).replace("\r\n", "\n");
        int capture = src.indexOf("captureSelfAvatar(camera");
        assertTrue(capture >= 0, "RtFrameRenderer.render must submit the local player, otherwise a first-person reflection stays empty");
        int open = src.lastIndexOf("EntityCaptureManager.getInstance().isOpen()", capture);
        assertTrue(open >= 0, "the submission must be gated on the capture being open");
        int endFrame = src.indexOf("EntityCaptureManager.getInstance().endFrame()", capture);
        assertTrue(endFrame > capture, "endFrame() publishes the frame and closes the capture: submitting after it is a silent no-op");
        assertTrue(src.substring(open, capture).indexOf('}') < 0,
            "the call must stay inside the isOpen() block, not after it");
    }

    @Test
    public void collectorKeepsModelGeometryAndDropsTheRest() {
        String src = read(COLLECTOR);
        assertTrue(src.contains("implements SubmitNodeCollector"), "the game hands the collector to EntityRenderer.submit");
        assertEquals(1, occurrences(src, "captureModel("),
            "exactly one forward: the model path is the only geometry the collector may add");
        assertFalse(src.contains("captureItem(") || src.contains("captureText(") || src.contains("captureParticles("),
            "items, text and particles of the re-submission are not entity model geometry");
        assertTrue(src.contains("isWaterMask(renderType)"), "a null or water-mask render type has no geometry to capture");
    }

    @Test
    public void optionIsOnByDefault() {
        String src = read(CONFIG);
        assertTrue(src.contains("System.getProperty(\"vkbridge.rt.selfAvatar\", \"true\")"),
            "the feature ships on; -Dvkbridge.rt.selfAvatar=false is the A/B switch");
    }

    private static int occurrences(String source, String needle) {
        int n = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
