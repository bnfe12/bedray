package io.github.andrexarlay.vulkanbridge.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Describes one vendor native library (a set of shared-library files) the core knows how to find and check.
 * <p>
 * The file lists are an allowlist: the installer copies only files whose name is listed here, under that exact name.
 * File names were taken from the vendor SDK layouts under {@code <local decompiled sources>} (DLSS {@code lib/Windows_x86_64},
 * Streamline {@code bin/x64}, FidelityFX {@code PrebuiltSignedDLL}, NRD wrapper names from the earlier prototype
 * {@code native/nrd_bridge}); the exact versions are not pinned. Windows {@code .dll}, Linux {@code .so} and macOS
 * {@code .dylib} names are accepted in the allowlist; the structural check is still PE-only and therefore Windows-only.
 *
 * @param id             stable lower-case id ({@code [a-z0-9_]+}), e.g. {@code "dlss"}
 * @param displayName    human readable name
 * @param vendor         NVIDIA / Intel / AMD
 * @param requiredFiles  all of these must be present for the library to count as installed
 * @param optionalFiles  extra files that may be installed and are checked when present
 * @param licenseName    name of the license the vendor ships the binaries under
 * @param termsUrl       where to read and accept the terms; the core never downloads from it
 * @param knownSha256    lower-case hex SHA-256 values per file name (key is case-insensitive) that are known good.
 *                       Empty for the built-ins: no hash of a specific vendor release has been verified, so nothing
 *                       is {@link LibraryStatus.State#VERIFIED} yet.
 * @param minVersion     minimum acceptable PE file version ({@code a.b.c.d}) or {@code null} for no check
 * @param maxFileBytes   upper bound for one file (install and resolve both reject larger files)
 */
@Experimental
public record LibraryDescriptor(String id, String displayName, String vendor, List<String> requiredFiles,
                                List<String> optionalFiles, String licenseName, String termsUrl,
                                Map<String, Set<String>> knownSha256, String minVersion, long maxFileBytes) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9_.\\-]+\\.(?:dll|so|dylib)");
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+){0,3}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public static final long DEFAULT_MAX_FILE_BYTES = 512L * 1024 * 1024;

    public LibraryDescriptor {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("library id must match [a-z0-9_]+: " + id);
        }
        requiredFiles = List.copyOf(requiredFiles);
        optionalFiles = List.copyOf(optionalFiles);
        if (requiredFiles.isEmpty()) {
            throw new IllegalArgumentException("library " + id + " needs at least one required file");
        }
        for (String f : concat(requiredFiles, optionalFiles)) {
            if (!FILE.matcher(f).matches()) {
                throw new IllegalArgumentException("library " + id + ": file name must be a plain *.dll, *.so or *.dylib name: " + f);
            }
        }
        if (minVersion != null && !VERSION.matcher(minVersion).matches()) {
            throw new IllegalArgumentException("library " + id + ": bad minVersion " + minVersion);
        }
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        Map<String, Set<String>> hashes = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : knownSha256.entrySet()) {
            for (String h : e.getValue()) {
                if (!SHA256.matcher(h).matches()) {
                    throw new IllegalArgumentException("library " + id + ": sha256 must be 64 lower-case hex chars: " + h);
                }
            }
            hashes.put(e.getKey().toLowerCase(Locale.ROOT), Set.copyOf(e.getValue()));
        }
        knownSha256 = Map.copyOf(hashes);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    /** All file names this descriptor allows, required first. */
    public List<String> allFiles() {
        return concat(requiredFiles, optionalFiles);
    }

    private static LibraryDescriptor of(String id, String name, String vendor, List<String> req, List<String> opt,
                                        String license, String url) {
        return new LibraryDescriptor(id, name, vendor, req, opt, license, url, Map.of(), null, DEFAULT_MAX_FILE_BYTES);
    }

    private static final String NVIDIA_LICENSE = "NVIDIA RTX SDKs LICENSE";

    public static final LibraryDescriptor XESS = of("xess", "Intel XeSS", "Intel", List.of("libxess.dll"), List.of(),
            "Intel Simplified Software License", "https://github.com/intel/xess");
    public static final LibraryDescriptor DLSS = of("dlss", "NVIDIA DLSS Super Resolution", "NVIDIA",
            List.of("nvngx_dlss.dll"), List.of("vkb_dlss.dll"), NVIDIA_LICENSE, "https://github.com/NVIDIA/DLSS");
    public static final LibraryDescriptor DLSS_G = of("dlss_g", "NVIDIA DLSS Frame Generation", "NVIDIA",
            List.of("nvngx_dlssg.dll"), List.of(), NVIDIA_LICENSE, "https://github.com/NVIDIA/DLSS");
    public static final LibraryDescriptor DLSS_RR = of("dlss_rr", "NVIDIA DLSS Ray Reconstruction", "NVIDIA",
            List.of("nvngx_dlssd.dll"), List.of(), NVIDIA_LICENSE, "https://github.com/NVIDIA/DLSS");
    public static final LibraryDescriptor STREAMLINE = of("streamline", "NVIDIA Streamline", "NVIDIA",
            List.of("sl.interposer.dll", "sl.common.dll"),
            List.of("sl.dlss.dll", "sl.dlss_d.dll", "sl.dlss_g.dll", "sl.reflex.dll", "sl.pcl.dll", "NvLowLatencyVk.dll"),
            NVIDIA_LICENSE, "https://github.com/NVIDIAGameWorks/Streamline");
    public static final LibraryDescriptor NRD = of("nrd", "NVIDIA Real-Time Denoisers", "NVIDIA",
            List.of("NRD.dll"), List.of("vkb_nrd.dll"), NVIDIA_LICENSE, "https://github.com/NVIDIA-RTX/NRD");
    /** Vulkan build of FidelityFX only; the DX12 DLLs cannot be used with a Vulkan device. */
    public static final LibraryDescriptor FSR = of("fsr", "AMD FidelityFX (Vulkan)", "AMD",
            List.of("amd_fidelityfx_vk.dll"), List.of(), "AMD FidelityFX SDK license (not reviewed by the core)",
            "https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK");

    /** The descriptors registered by default. */
    public static List<LibraryDescriptor> builtIn() {
        return List.of(XESS, DLSS, DLSS_G, DLSS_RR, STREAMLINE, NRD, FSR);
    }
}
