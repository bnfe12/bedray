package io.github.andrexarlay.vulkanbridge.api;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manager of user-provided vendor native libraries (DLSS, Streamline, NRD, XeSS, FidelityFX). Obtain it with
 * {@link VulkanBridge#libraries()}.
 * <p>
 * Rules:
 * <ul>
 *   <li>Nothing is downloaded. Libraries come from a file or archive the user points {@link #install} at, or are
 *       copied into {@code <gameDir>/bedray/natives} by hand. The legacy directory
 *       {@code <gameDir>/vulkan_rtx/natives} is read, never written.</li>
 *   <li>A DLL must never be loaded from a path that did not come out of {@link #resolve} (or {@link #load}).
 *       Mods must not scan directories or call {@code System.load} on their own paths.</li>
 *   <li>Checks are structural (allowed name, size, x64 PE DLL, version, SHA-256 against known hashes). A signature
 *       is not cryptographically validated; see docs/api/native-libraries.md.</li>
 * </ul>
 */
@Experimental
public interface NativeLibraries {
    /** Descriptors known to this manager (built-in ones plus {@link #register}ed). */
    List<LibraryDescriptor> descriptors();

    /** Registers an additional library. The id must be new; the allowlist of an existing id cannot be changed. */
    void register(LibraryDescriptor descriptor);

    /** Directory that {@link #install} writes to: {@code <gameDir>/bedray/natives}. Not created by this call. */
    Path nativesDirectory();

    /** Status of every known library (re-scans the disk; hashes are cached by size and modification time). */
    List<LibraryResolution> scan();

    /**
     * Checks the library on disk and returns paths or a clear reason. Throws {@link IllegalArgumentException} only
     * for an id that is not registered; every other state is reported in the result.
     */
    LibraryResolution resolve(String id);

    /**
     * Checks and copies a DLL, or the allowed DLLs inside a zip, into the natives directory. {@code source} is a
     * path chosen by the user (a single {@code .dll} or a {@code .zip}). Throws {@link NativeLibraryException} with
     * the reason on any failed check; in that case nothing is written.
     */
    InstallResult install(String id, Path source);

    /** Deletes the library's files from the natives directory (not from the legacy one). Returns the paths removed. */
    List<Path> remove(String id);

    /**
     * Loads every required file of the library with {@code System.load}, in descriptor order, using only the paths
     * that {@link #resolve} returns at this moment, and returns the first (primary) one. Optional files are not loaded. Throws {@link NativeLibraryException} when the library is not usable or the OS
     * refuses to load it (the {@link UnsatisfiedLinkError} is the cause, not swallowed).
     */
    Path load(String id);

    /** Listener for install/remove events. Returns an action that unregisters it. */
    Runnable addListener(Consumer<LibraryEvent> listener);
}
