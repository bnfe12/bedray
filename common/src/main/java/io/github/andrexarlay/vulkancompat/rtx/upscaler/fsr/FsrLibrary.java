package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Whether the FidelityFX Vulkan library can be used. The DLL path comes only from the Bedray Core
 * {@code NativeLibraries.resolve("fsr")} (built-in descriptor {@code fsr}: {@code amd_fidelityfx_vk.dll}, "Vulkan build of
 * FidelityFX only; the DX12 DLLs cannot be used with a Vulkan device"). Nothing is loaded here.
 * <p>
 * Official build check: Bedray Core descriptor has no known hashes, so this class compares the SHA-256 of the resolved file
 * with the AMD-signed {@code PrebuiltSignedDLL/amd_fidelityfx_vk.dll} of the public FidelityFX SDK tag v1.1.4
 * (GPUOpen-LibrariesAndSDKs/FidelityFX-SDK; file version 1.0.1.41314, Authenticode signer "Advanced Micro Devices", checked
 * on 2026-10-11 against {@code local reference}). An unknown file (any other build, e.g. one that did
 * not come from AMD's public SDK) is refused unless {@code -Dvkrtx.fsr.allowUnknownDll=true}, which is meant for a newer
 * official SDK release whose hash is not listed yet; the log line then says {@code official=false}.
 */
public final class FsrLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FsrLibrary");

    public static final String LIBRARY_ID = "fsr";
    public static final String DLL = "amd_fidelityfx_vk.dll";
    public static final String ALLOW_UNKNOWN_PROPERTY = "vkrtx.fsr.allowUnknownDll";
    private static final String MANAGER_UNAVAILABLE = "native library manager unavailable: ";

    /** SHA-256 (lower-case hex) of official AMD FidelityFX Vulkan DLLs -> description. */
    public static final Map<String, String> OFFICIAL_SHA256 = Map.of(
        "a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78",
        "FidelityFX SDK v1.1.4 PrebuiltSignedDLL (FSR 3.1.4 upscaler, FSR 2.3.x, FSR 3.1 frame generation)");

    /** {@code dll} and {@code sha256} are set when the file was found; {@code usable} also needs the official check. */
    public record Library(boolean usable, String reason, Path dll, String sha256, String release, String fileVersion) {
        static Library unusable(String reason) {
            return new Library(false, reason, null, null, null, null);
        }
    }

    private static Supplier<LibraryResolution> resolver = () -> libraries().resolve(LIBRARY_ID);
    private static Library cached;
    private static boolean listenerRegistered;

    private FsrLibrary() {}

    private static NativeLibraries libraries() {
        NativeLibraries libs = VulkanBridge.libraries();
        if (!listenerRegistered) {
            listenerRegistered = true;
            libs.addListener(event -> invalidate());
        }
        return libs;
    }

    public static synchronized Library library() {
        if (cached == null) {
            Library lib = evaluate();
            if (lib.reason().startsWith(MANAGER_UNAVAILABLE)) {
                return lib; // too early (no game directory yet): not cached, asked again later
            }
            cached = lib;
            LOGGER.info("[VKB-FSR] library usable={} reason={} dll={} sha256={} official={} file_version={}",
                cached.usable(), cached.reason(), io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(cached.dll()), cached.sha256(), cached.release() != null, cached.fileVersion());
        }
        return cached;
    }

    public static boolean isUsable() {
        return library().usable();
    }

    private static Library evaluate() {
        LibraryResolution r;
        try {
            r = resolver.get();
        } catch (IllegalStateException e) {
            // VulkanBridge.libraries() throws outside the game (no game directory): reported, never treated as present.
            return Library.unusable(MANAGER_UNAVAILABLE + LogPaths.show(e.getMessage()));
        }
        if (!r.usable()) {
            return Library.unusable("fsr library " + r.status().state() + ": " + r.status().detail());
        }
        Path dll = r.file(DLL).orElse(null);
        if (dll == null) {
            return Library.unusable(DLL + " not resolved");
        }
        String sha;
        try {
            sha = sha256(dll);
        } catch (IOException e) {
            return Library.unusable(LogPaths.show("cannot read " + dll + ": " + e.getMessage()));
        }
        String version = r.versions().get(DLL);
        String release = OFFICIAL_SHA256.get(sha);
        if (release == null && !Boolean.getBoolean(ALLOW_UNKNOWN_PROPERTY)) {
            return new Library(false, "sha256 " + sha + " is not a known official AMD FidelityFX SDK Vulkan DLL (only public "
                + "GPUOpen releases are accepted; -D" + ALLOW_UNKNOWN_PROPERTY + "=true for a newer official release)",
                dll, sha, null, version);
        }
        return new Library(true, (release != null ? "ok official: " + release : "ok UNKNOWN build accepted by -D" + ALLOW_UNKNOWN_PROPERTY)
            + (r.legacy() ? " (legacy folder bedray/natives)" : ""), dll, sha, release, version);
    }

    static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available in this JVM", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static synchronized void invalidate() {
        cached = null;
    }

    /** Test hook: replaces {@code resolve("fsr")}; null restores Bedray Core resolver. */
    public static synchronized void setResolverForTesting(Supplier<LibraryResolution> replacement) {
        resolver = replacement != null ? replacement : () -> libraries().resolve(LIBRARY_ID);
        cached = null;
    }
}
