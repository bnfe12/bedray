package io.github.andrexarlay.vulkancompat.rtx.raytracing;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import java.util.List;
import java.util.Set;

/** Independent optional group: NoRT devices can expose budgets without acceleration structures. */
public final class MemoryBudgetDeviceRequirements {
    private MemoryBudgetDeviceRequirements() {}
    public static DeviceRequirement requirement() {
        return new DeviceRequirement("Bedray Native Memory Budget", Set.of("VK_EXT_memory_budget"), List.of(), 0, true, null);
    }
}
