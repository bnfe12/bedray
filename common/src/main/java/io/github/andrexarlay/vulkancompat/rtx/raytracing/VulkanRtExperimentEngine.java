package io.github.andrexarlay.vulkancompat.rtx.raytracing;

/**
 * Concrete implementation and singleton accessor for {@link DefaultRtExperimentEngine}.
 */
public final class VulkanRtExperimentEngine extends DefaultRtExperimentEngine {
    private static final VulkanRtExperimentEngine INSTANCE = new VulkanRtExperimentEngine();

    public static VulkanRtExperimentEngine getInstance() {
        return INSTANCE;
    }
}
