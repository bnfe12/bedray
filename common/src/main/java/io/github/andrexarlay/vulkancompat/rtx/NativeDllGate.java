package io.github.andrexarlay.vulkancompat.rtx;

import io.github.andrexarlay.vulkanbridge.core.NativeFiles;

import java.nio.file.Path;

/**
 * The bridge's natives policy (regular non-link x64 DLL of a sane size) applied at the loader boundary.
 * <p>
 * {@code NativeLibraries.resolve} already runs it, but the RT runtimes also accept a path straight from the game directory
 * (XeSS) or a developer override property (FSR), and handing an arbitrary file to the OS loader is the one step the
 * vk-bridge-native-libs protocol puts before any load.
 */
public final class NativeDllGate {
    private NativeDllGate() {}

    /** @return null when the file may be loaded, otherwise the reason it may not. */
    public static String reject(Path dll) {
        return dll == null ? "no path" : NativeFiles.problem(dll);
    }
}
