package io.github.andrexarlay.vulkanbridge.core;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The files Bedray looks for and where. One table feeds the folder README, the natives manager screen and the installer, so they
 * cannot disagree. The layout mirrors what the loaders really read (checked in the code, not assumed):
 * <ul>
 *   <li>{@code <gameDir>/bedray/natives/}: FSR, XeSS, DLSS Super Resolution, NRD ({@code NativeLibrariesImpl} descriptors
 *       {@code fsr}, {@code xess}, {@code dlss}, {@code nrd});</li>
 *   <li>{@code <gameDir>/bedray/natives/streamline/}: everything of NVIDIA Streamline ({@code FrameGenEarly.slDir()}). With
 *       Streamline, DLSS Super Resolution and Ray Reconstruction run through its {@code sl.dlss.dll} / {@code sl.dlss_d.dll}
 *       plugins, which look for {@code nvngx_dlss.dll} / {@code nvngx_dlssd.dll} next to them. Hence these two NGX files have a slot
 *       in both folders and the installer puts a copy in each.</li>
 * </ul>
 * Wrapper libraries ({@code vkb_*.dll}) are not distributed with Bedray. They are listed as {@link Kind#WRAPPER} so that a user
 * who built one himself can drop it in; nothing ever requires them from the manager's point of view.
 * <p>
 * Pure Java, no Minecraft classes.
 */
public final class NativeCatalog {
    private NativeCatalog() {}

    /** Sub folder of the natives directory. */
    public enum Folder {
        NATIVES(""),
        STREAMLINE("streamline");

        private final String sub;

        Folder(String sub) {
            this.sub = sub;
        }

        public String subfolder() {
            return sub;
        }

        public Path resolve(Path nativesDir) {
            return sub.isEmpty() ? nativesDir : nativesDir.resolve(sub);
        }

        /** Folder name as shown to the user, relative to the game directory, e.g. {@code bedray/natives/streamline}. */
        public String display() {
            return sub.isEmpty() ? "bedray/natives" : "bedray/natives/" + sub;
        }
    }

    public enum Kind {
        /** A vendor DLL downloaded from the vendor's official site. */
        VENDOR,
        /** A Bedray wrapper (vkb_*.dll): not distributed, only used if the user has a build of his own. */
        WRAPPER,
        /** An upcoming technology slot: file can be placed while the engine integration is on the roadmap. */
        UPCOMING,
        /** Local file diagnostics for a feature that requires a partner SDK. The installer skips these files. */
        PARTNER_SDK
    }

    /**
     * One file in one folder.
     *
     * @param required true when the feature group counts as complete only with this file (never true for wrappers)
     */
    public record Slot(Folder folder, String file, boolean required, Kind kind, String bundledSha256) {
        /** True when Bedray ships this exact file inside its jar (see {@link NativeBundle}) and extracts it on start. */
        public boolean bundled() {
            return bundledSha256 != null;
        }

        public Slot {
            if (kind == Kind.WRAPPER && required) {
                throw new IllegalArgumentException("wrapper libraries are never required by the manager: " + file);
            }
        }
    }

    /** One line of the manager with feature files and a link to official downloads or documentation. */
    public record Group(String id, String title, String vendor, String purpose, String url, List<Slot> slots) {
        public Group {
            slots = List.copyOf(slots);
        }
    }

    private static Slot req(Folder f, String file) {
        return new Slot(f, file, true, Kind.VENDOR, null);
    }

    private static Slot opt(Folder f, String file) {
        return new Slot(f, file, false, Kind.VENDOR, null);
    }

    /** A file shipped inside the jar byte for byte (licence allows it, see docs/NATIVES_LICENSES.md); {@code sha256} is what it must hash to. */
    private static Slot bundled(Folder f, String file, String sha256) {
        return new Slot(f, file, true, Kind.VENDOR, sha256);
    }

    private static Slot wrapper(Folder f, String file) {
        return new Slot(f, file, false, Kind.WRAPPER, null);
    }

    private static Slot upcoming(Folder f, String file) {
        return new Slot(f, file, false, Kind.UPCOMING, null);
    }

    /** FidelityFX SDK v1.1.4 PrebuiltSignedDLL/amd_fidelityfx_vk.dll, 9 332 432 bytes, MIT (AMD). */
    public static final String SHA_FSR = "a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78";
    /** XeSS SDK 3.0.2 bin/libxess.dll, 77 795 704 bytes, Intel Simplified Software License. */
    public static final String SHA_XESS = "251659dd84a3e84de67c886a4186e01f3eca49b00641906fe38bb6b807e5d5b7";
    /** xessGetVersion from the SDK 3.0.2 Vulkan DLL above, which has no PE version resource. */
    static final String XESS_VULKAN_VERSION = "2.0.2";
    public static final String URL_FSR = "https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases/tag/v1.1.4";
    public static final String URL_XESS = "https://github.com/intel/xess/releases";
    public static final String URL_DLSS = "https://github.com/NVIDIA/DLSS";
    public static final String URL_STREAMLINE = "https://github.com/NVIDIAGameWorks/Streamline/releases";
    public static final String URL_NRD = "https://github.com/NVIDIA-RTX/NRD";
    public static final String URL_DLSS5 =
        "https://forums.developer.nvidia.com/t/official-dlss-5-neural-rendering-plugin-access-for-unreal-engine-5-8-2/382731/11";

    public static final List<Group> GROUPS = List.of(
        new Group("fsr", "AMD FidelityFX FSR 3 (Vulkan)", "AMD",
            "FSR upscaling and frame generation, any GPU. The Vulkan DLL of FidelityFX SDK v1.1.4.", URL_FSR,
            List.of(bundled(Folder.NATIVES, "amd_fidelityfx_vk.dll", SHA_FSR))),
        new Group("xess", "Intel XeSS", "Intel", "XeSS upscaling, any GPU.", URL_XESS,
            List.of(bundled(Folder.NATIVES, "libxess.dll", SHA_XESS))),
        new Group("dlss", "NVIDIA DLSS Super Resolution", "NVIDIA", "DLSS upscaling (GeForce RTX).", URL_DLSS,
            List.of(req(Folder.NATIVES, "nvngx_dlss.dll"))),
        new Group("dlss_rr", "NVIDIA DLSS Ray Reconstruction", "NVIDIA", "AI denoiser for path tracing (GeForce RTX).", URL_DLSS,
            List.of(req(Folder.NATIVES, LibraryDescriptor.DLSS_RR_DLL))),
        new Group("nrd", "NVIDIA Real-Time Denoisers (NRD)", "NVIDIA", "REBLUR / RELAX denoiser for path tracing.", URL_NRD,
            List.of(req(Folder.NATIVES, "NRD.dll"))),
        new Group("streamline", "NVIDIA Streamline (core)", "NVIDIA", "The loader every NVIDIA frame generation feature needs.", URL_STREAMLINE,
            List.of(req(Folder.STREAMLINE, "sl.interposer.dll"), req(Folder.STREAMLINE, "sl.common.dll"))),
        new Group("framegen", "DLSS Frame Generation + Reflex", "NVIDIA", "Generated frames and low latency (GeForce RTX).", URL_STREAMLINE,
            List.of(req(Folder.STREAMLINE, "sl.dlss_g.dll"), req(Folder.STREAMLINE, "sl.reflex.dll"), req(Folder.STREAMLINE, "sl.pcl.dll"),
                req(Folder.STREAMLINE, "nvngx_dlssg.dll"), opt(Folder.STREAMLINE, "NvLowLatencyVk.dll"))),
        new Group("sl_dlss", "DLSS + Ray Reconstruction through Streamline", "NVIDIA",
            "With frame generation on, DLSS Super Resolution and Ray Reconstruction run through these four files.", URL_STREAMLINE,
            List.of(req(Folder.STREAMLINE, "sl.dlss.dll"), req(Folder.STREAMLINE, "sl.dlss_d.dll"),
                req(Folder.STREAMLINE, "nvngx_dlss.dll"), req(Folder.STREAMLINE, LibraryDescriptor.DLSS_RR_DLL))),
        new Group("dlss5", "DLSS 5 Neural Rendering", "NVIDIA",
            "Experimental direct Vulkan NGX. The file check reports presence. A Neural Rendering wrapper build is required.", URL_DLSS5,
            List.of(new Slot(Folder.NATIVES, "nvngx_dlssnr.dll", false, Kind.PARTNER_SDK, null),
                new Slot(Folder.STREAMLINE, "sl.dlss_nr.dll", false, Kind.PARTNER_SDK, null))),
        new Group("fsr4", "AMD FSR Upscaling 4 (Vulkan)", "AMD",
            "FSR 4.1.2 on supported AMD GPUs, plus FSR 3.1.6 and 2.3.4. Install both signed DLLs from AMD FSR SDK 2.4.",
            LibraryDescriptor.FSR4_SDK_URL, List.of(req(Folder.NATIVES, LibraryDescriptor.FSR4_LOADER),
                req(Folder.NATIVES, LibraryDescriptor.FSR4_UPSCALER))),
        new Group("xess2", "Intel XeSS 2.0 & XeLL", "Intel",
            "Next-gen XeSS 2.0 AI upscaling with frame generation (planned in roadmap v0.3.0).", URL_XESS,
            List.of(upcoming(Folder.NATIVES, "libxess_fg.dll"), upcoming(Folder.NATIVES, "libxell.dll"))),
        new Group("nrc", "NVIDIA Neural Radiance Cache (NRC)", "NVIDIA",
            "Real-time AI path tracing radiance cache SDK (planned in roadmap v0.3.0).", URL_DLSS,
            List.of(upcoming(Folder.NATIVES, "nv_nrc.dll"))),
        new Group("directsr", "DirectSR / Vulkan Cross-Vendor SR", "Microsoft/Khronos",
            "Cross-vendor super-resolution abstraction API (planned in roadmap v0.3.0).", "",
            List.of(upcoming(Folder.NATIVES, "directsr.dll"))),
        new Group("wrappers", "Own wrapper builds (optional, not distributed)", "Bedray",
            "Only if you built them yourself. Bedray does not ship or download these.", "",
            List.of(wrapper(Folder.NATIVES, "vkb_dlss.dll"), wrapper(Folder.NATIVES, "vkb_nrd.dll"),
                wrapper(Folder.STREAMLINE, "vkb_sl.dll"))));

    /** Every slot whose file name equals {@code name} (case-insensitive); more than one for the NGX files. Empty when unknown. */
    public static List<Slot> slotsFor(String name) {
        List<Slot> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        for (Group g : GROUPS) {
            for (Slot s : g.slots()) {
                if (s.file().equalsIgnoreCase(name) && out.stream().noneMatch(o -> o.folder() == s.folder())) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** The spelling used in this table for {@code name}, or null when the file is not part of the catalog. */
    public static String canonicalName(String name) {
        if (name == null) {
            return null;
        }
        for (Group g : GROUPS) {
            for (Slot s : g.slots()) {
                if (s.file().equalsIgnoreCase(name)) {
                    return s.file();
                }
            }
        }
        return null;
    }

    static String installableName(String name) {
        return installableSlotsFor(name).stream().findFirst().map(Slot::file).orElse(null);
    }

    static List<Slot> installableSlotsFor(String name) {
        return slotsFor(name).stream().filter(slot -> slot.kind() != Kind.PARTNER_SDK).toList();
    }

    /** Distinct canonical file names of the whole catalog, in table order. */
    public static Set<String> allFileNames() {
        Set<String> out = new LinkedHashSet<>();
        for (Group g : GROUPS) {
            for (Slot s : g.slots()) {
                out.add(s.file());
            }
        }
        return out;
    }

    /** Lower-case name for map keys. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
