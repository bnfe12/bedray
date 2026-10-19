package io.github.andrexarlay.vulkanbridge.glbridge;

import org.lwjgl.system.JNI;
import org.lwjgl.system.windows.WindowsLibrary;

/**
 * CloseHandle through LWJGL's own JNI helper (no FFM, so no "restricted method" warning in the game).
 * Vulkan: vkGetMemoryWin32HandleKHR / vkGetSemaphoreWin32HandleKHR hand the application an NT handle that it must release
 * with CloseHandle (docs.vulkan.org refpages). GL: EXT_external_objects_win32 import does not take ownership of the
 * handle, the application closes it (registry.khronos.org/OpenGL/extensions/EXT/EXT_external_objects_win32.txt).
 */
final class Win32Handles {
    private static final long CLOSE_HANDLE;

    static {
        WindowsLibrary k32 = new WindowsLibrary("kernel32");
        CLOSE_HANDLE = k32.getFunctionAddress("CloseHandle");
        if (CLOSE_HANDLE == 0L) {
            throw new IllegalStateException("kernel32!CloseHandle not found");
        }
    }

    private Win32Handles() {}

    static void close(long handle) {
        if (JNI.invokePI(handle, CLOSE_HANDLE) == 0) {
            throw new IllegalStateException("CloseHandle(0x" + Long.toHexString(handle) + ") failed");
        }
    }
}
