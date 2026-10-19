package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.vulkan.VK10;

/**
 * A failure of the world ray tracing path. Carries the stage and the result code for the log line
 * {@code [VKB-RT] FAILED stage=<stage> result=<result>}; the frame renderer disables RT for the session, the game goes on.
 */
public final class RtFailure extends RuntimeException {
    private final String stage;
    private final String result;

    public RtFailure(String stage, String result) {
        super("stage=" + stage + " result=" + result);
        this.stage = stage;
        this.result = result;
    }

    public RtFailure(String stage, String result, Throwable cause) {
        super("stage=" + stage + " result=" + result, cause);
        this.stage = stage;
        this.result = result;
    }

    public String stage() {
        return stage;
    }

    public String result() {
        return result;
    }

    /** Throws when a Vulkan call did not return VK_SUCCESS. */
    public static void check(int vkResult, String stage) {
        if (vkResult != VK10.VK_SUCCESS) {
            throw new RtFailure(stage, "VkResult=" + vkResult);
        }
    }
}
