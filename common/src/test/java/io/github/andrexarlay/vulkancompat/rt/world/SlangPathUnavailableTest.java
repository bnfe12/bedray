package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkDevice;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A missing Vulkan device must not cause an implicit GLSL path selection. */
class SlangPathUnavailableTest {
    @Test
    void missingDeviceReportsUnavailableInsteadOfCreatingLegacyTracer() throws Exception {
        Method create = RtFrameRenderer.class.getDeclaredMethod("createPass", VkDevice.class, RtMode.class, boolean.class);
        create.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> create.invoke(null, null, RtMode.PATH_TRACED, false));
        RtFailure failure = assertInstanceOf(RtFailure.class, thrown.getCause());
        assertEquals("slang_path", failure.stage());
    }

    @Test
    void rayEffectsReportMissingSlangPassInsteadOfCreatingLegacyTracer() throws Exception {
        Method create = RtFrameRenderer.class.getDeclaredMethod("createPass", VkDevice.class, RtMode.class, boolean.class);
        create.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> create.invoke(null, null, RtMode.RAY_TRACED, false));
        RtFailure failure = assertInstanceOf(RtFailure.class, thrown.getCause());
        assertEquals("slang_ray", failure.stage());
    }
}
