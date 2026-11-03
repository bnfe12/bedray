package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Host integration guards; RecordingCompletion tests exercise the proof and retry state machine. */
class VelocityRecordingOwnerTest {
    @Test void aRejectedVendorRecordingIsDiscardedBeforeEndOrExecute() throws Exception {
        String owner = source("upscaler/VelocityRecordingOwner.java");
        int rejected = owner.indexOf("if (!commands.apply(command))");
        int end = owner.indexOf("VK10.vkEndCommandBuffer(command)");
        assertTrue(rejected >= 0 && rejected < end);
        String rejection = owner.substring(rejected, end);
        assertTrue(rejection.indexOf("completion.discard(ticket)") < rejection.indexOf("return false"));
        assertTrue(end < owner.indexOf("encoder.execute(command)"));
        assertTrue(owner.indexOf("completion.submissionAttempted(ticket)") < owner.indexOf("encoder.execute(command)"));
        assertTrue(owner.contains("if (!executeAttempted && !discardAttempted)"),
            "unknown submission outcomes cannot be treated as unsubmitted commands");
    }

    @Test void vendorDestructionUsesTheCapturedDeviceAndAnOutsideRecordingProof() throws Exception {
        String owner = source("upscaler/VelocityRecordingOwner.java");
        int drain = owner.indexOf("public void drain(VulkanDevice expectedDevice)");
        String checked = owner.substring(drain, owner.indexOf("public void drain()", drain));
        assertTrue(checked.indexOf("if (recording || draining)") < checked.indexOf("drain();"));
        assertTrue(checked.contains("VulkanCapabilityChecker.getVulkanDevice() != expectedDevice"));
        assertTrue(checked.contains("lease.runtime().hostDevice() != expectedDevice"));
        assertTrue(owner.indexOf("fence.awaitCompletion(") < owner.indexOf("completion.afterHostDrain()"));
        for (String name : new String[]{"dlss/DlssRuntime", "fsr/FsrRuntime", "xess/XessRuntime"}) {
            String runtime = source("upscaler/" + name + ".java");
            assertTrue(runtime.contains("UpscalerContextRetirement.release("), name);
            assertTrue(runtime.contains("drainTemporalRecordings("), name);
            assertFalse(runtime.contains("vkDeviceWaitIdle"), name);
        }
        String manager = source("WorldTargetManager.java");
        int cleanup = manager.indexOf("TemporalUpscaler.cleanup();");
        assertTrue(cleanup >= 0 && cleanup < manager.indexOf("velocityRecordings.close()"));
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rtx", relative));
    }
}
