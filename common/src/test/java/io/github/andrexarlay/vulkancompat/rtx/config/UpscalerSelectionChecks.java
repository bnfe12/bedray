package io.github.andrexarlay.vulkancompat.rtx.config;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessQuality;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Java-level checks (L2) of the upscaler selector logic: modes per technology, ratios, defaults, and that an
 * unavailable backend can never be stored as active. No GPU, no Minecraft: not evidence that the UI works in game.
 */
public final class UpscalerSelectionChecks {
    private UpscalerSelectionChecks() {}

    public static void runAll() throws Exception {
        modeListsPerBackend();
        ratiosMatchSdkTables();
        defaultsAreOffered();
        statusAndFallback();
        configRoundTripAndRejection();
        earlyReadFromToml();
        settingsFileMigration();
    }

    /** The old config/vulkan_rtx.toml is copied to config/bedray.toml once; an existing bedray.toml is never overwritten. */
    private static void settingsFileMigration() throws Exception {
        Path dir = Files.createTempDirectory("bedray_migrate");
        Path legacy = dir.resolve(ConfigFiles.LEGACY_NAME);
        Path current = dir.resolve(ConfigFiles.NAME);
        Files.writeString(legacy, "active_backend = \"XESS\"\n", StandardCharsets.UTF_8);
        check(!Files.exists(current), "no new settings file before migration");
        ConfigFiles.resolve(current);
        check(Files.isRegularFile(current), "legacy settings copied to bedray.toml");
        check(Files.isRegularFile(legacy), "legacy settings file left in place");
        check(Files.readString(current, StandardCharsets.UTF_8).contains("XESS"), "migrated content equals the old file");
        Files.writeString(legacy, "active_backend = \"DLSS\"\n", StandardCharsets.UTF_8);
        ConfigFiles.resolve(current);
        check(Files.readString(current, StandardCharsets.UTF_8).contains("XESS"), "existing bedray.toml is not overwritten");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void modeListsPerBackend() {
        check(UpscalerModes.modesFor(UpscalerBackend.BILINEAR).isEmpty(), "BILINEAR has no quality modes");
        check(UpscalerModes.modesFor(UpscalerBackend.DLSS).equals(List.of(UpscalerMode.DLAA, UpscalerMode.QUALITY,
            UpscalerMode.BALANCED, UpscalerMode.PERFORMANCE, UpscalerMode.ULTRA_PERFORMANCE, UpscalerMode.CUSTOM)), "DLSS modes");
        check(UpscalerModes.modesFor(UpscalerBackend.FSR).equals(List.of(UpscalerMode.NATIVE_AA,
            UpscalerMode.QUALITY, UpscalerMode.BALANCED, UpscalerMode.PERFORMANCE, UpscalerMode.ULTRA_PERFORMANCE, UpscalerMode.CUSTOM)),
            "FSR modes (ffx_upscale.h FfxApiUpscaleQualityMode: no Ultra Quality)");
        check(UpscalerModes.modesFor(UpscalerBackend.XESS).equals(List.of(UpscalerMode.ULTRA_QUALITY_PLUS, UpscalerMode.ULTRA_QUALITY,
            UpscalerMode.QUALITY, UpscalerMode.BALANCED, UpscalerMode.PERFORMANCE, UpscalerMode.ULTRA_PERFORMANCE, UpscalerMode.CUSTOM)), "XeSS modes");
        check(UpscalerModes.modesFor(UpscalerBackend.FSR1).equals(List.of(UpscalerMode.ULTRA_QUALITY, UpscalerMode.QUALITY,
            UpscalerMode.BALANCED, UpscalerMode.PERFORMANCE, UpscalerMode.CUSTOM)), "FSR 1 modes (GPUOpen FSR 1 table)");
        check(UpscalerModes.modesFor(UpscalerBackend.NIS).equals(List.of(UpscalerMode.CUSTOM)), "NIS: no published modes, custom only");
        for (UpscalerBackend b : UpscalerBackend.values()) {
            check(!UpscalerModes.modesFor(b).contains(UpscalerMode.AUTO), "AUTO is a STUB and must not be offered for " + b);
        }
        boolean threw = false;
        try {
            UpscalerModes.ratio(UpscalerBackend.XESS, UpscalerMode.DLAA);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "XeSS has no DLAA: ratio must throw, not invent a value");
    }

    private static void near(double expected, double actual, String msg) {
        check(Math.abs(expected - actual) < 1e-9, msg + " expected " + expected + " got " + actual);
    }

    private static void ratiosMatchSdkTables() {
        // XeSS 1.3+ fixed ratios: xess_sr_developer_guide_english.md "Resolution scaling (fixed)"
        near(1.3, UpscalerModes.ratio(UpscalerBackend.XESS, UpscalerMode.ULTRA_QUALITY_PLUS), "XeSS UQ+");
        near(1.5, UpscalerModes.ratio(UpscalerBackend.XESS, UpscalerMode.ULTRA_QUALITY), "XeSS UQ");
        near(1.7, UpscalerModes.ratio(UpscalerBackend.XESS, UpscalerMode.QUALITY), "XeSS Q");
        near(2.0, UpscalerModes.ratio(UpscalerBackend.XESS, UpscalerMode.BALANCED), "XeSS B");
        near(2.3, UpscalerModes.ratio(UpscalerBackend.XESS, UpscalerMode.PERFORMANCE), "XeSS P");
        near(3.0, UpscalerModes.ratio(UpscalerBackend.XESS, UpscalerMode.ULTRA_PERFORMANCE), "XeSS UP");
        near(1.0 / 1.7, UpscalerModes.scale(UpscalerBackend.XESS, UpscalerMode.QUALITY), "XeSS Q scale");
        // FSR 3.1 / 2 (ffx_upscale.h l.31-38, FidelityFX SDK v1.1.4)
        near(1.0, UpscalerModes.ratio(UpscalerBackend.FSR, UpscalerMode.NATIVE_AA), "FSR Native AA");
        near(1.5, UpscalerModes.ratio(UpscalerBackend.FSR, UpscalerMode.QUALITY), "FSR Q");
        near(1.7, UpscalerModes.ratio(UpscalerBackend.FSR, UpscalerMode.BALANCED), "FSR B");
        near(2.0, UpscalerModes.ratio(UpscalerBackend.FSR, UpscalerMode.PERFORMANCE), "FSR P");
        near(3.0, UpscalerModes.ratio(UpscalerBackend.FSR, UpscalerMode.ULTRA_PERFORMANCE), "FSR UP");
        check(UpscalerModes.source(UpscalerBackend.FSR, UpscalerMode.QUALITY) == UpscalerModes.Source.SDK_DOC, "FSR source: SDK header");
        check(!UpscalerModes.isOffered(UpscalerBackend.FSR, UpscalerMode.ULTRA_QUALITY), "FSR does not offer Ultra Quality");
        check(UpscalerModes.source(UpscalerBackend.DLSS, UpscalerMode.QUALITY) == UpscalerModes.Source.RUNTIME_QUERY,
            "DLSS ratios are queried from NGX at runtime (no table)");
        check(UpscalerModes.source(UpscalerBackend.XESS, UpscalerMode.QUALITY) == UpscalerModes.Source.SDK_DOC, "XeSS source");
        // every offered mode: ratio >= 1 and scale in (0,1]
        for (UpscalerBackend b : UpscalerBackend.values()) {
            if (b == UpscalerBackend.DLSS) {
                continue; // DLSS has no table: ratio() refuses (checked in DlssChecks)
            }
            for (UpscalerMode m : UpscalerModes.modesFor(b)) {
                if (m == UpscalerMode.CUSTOM) {
                    continue; // the user percent, no table ratio
                }
                check(UpscalerModes.ratio(b, m) >= 1.0, b + "/" + m + " ratio >= 1");
                double sc = UpscalerModes.scale(b, m);
                check(sc > 0.0 && sc <= 1.0, b + "/" + m + " scale in (0,1]");
            }
        }
        // XeSS modes map onto the library's quality enum by name
        check(UpscalerModes.toXessQuality(UpscalerMode.ULTRA_QUALITY_PLUS) == XessQuality.ULTRA_QUALITY_PLUS, "UQ+ mapping");
        check(UpscalerModes.toXessQuality(UpscalerMode.ULTRA_PERFORMANCE) == XessQuality.ULTRA_PERFORMANCE, "UP mapping");
    }

    private static void defaultsAreOffered() {
        check(UpscalerModes.defaultMode(UpscalerBackend.BILINEAR) == null, "BILINEAR has no default mode");
        for (UpscalerBackend b : new UpscalerBackend[] {UpscalerBackend.DLSS, UpscalerBackend.FSR, UpscalerBackend.XESS}) {
            check(UpscalerModes.isOffered(b, UpscalerModes.defaultMode(b)), "default mode of " + b + " is offered");
        }
    }

    private static void statusAndFallback() throws Exception {
        Path dir = Files.createTempDirectory("vkb_sel_test");
        check(UpscalerSelection.status(UpscalerBackend.BILINEAR, dir) == UpscalerSelection.Status.AVAILABLE, "BILINEAR available");
        check(UpscalerSelection.status(UpscalerBackend.DLSS, dir) == UpscalerSelection.Status.LIBRARY_MISSING,
            "DLSS: libraries are resolved by Bedray Core, outside the game they are not available");
        check(UpscalerSelection.status(UpscalerBackend.FSR, dir) == UpscalerSelection.Status.LIBRARY_MISSING,
            "FSR: the official DLL is resolved by Bedray Core, outside the game it is not available");
        check(UpscalerSelection.status(UpscalerBackend.XESS, dir) == UpscalerSelection.Status.LIBRARY_MISSING, "XeSS without dll");
        check(UpscalerSelection.resolve(UpscalerBackend.XESS, dir) == UpscalerBackend.BILINEAR, "XeSS without dll falls back");
        check(UpscalerSelection.resolve(UpscalerBackend.DLSS, dir) == UpscalerBackend.BILINEAR, "DLSS falls back");
        check(UpscalerSelection.resolve(UpscalerBackend.FSR, dir) == UpscalerBackend.BILINEAR, "FSR falls back");
        check(UpscalerSelection.resolve(null, dir) == UpscalerBackend.BILINEAR, "null falls back");

        Path natives = Files.createDirectories(dir.resolve("bedray").resolve("natives"));
        Files.createFile(natives.resolve("libxess.dll"));
        check(UpscalerSelection.status(UpscalerBackend.XESS, dir) == UpscalerSelection.Status.AVAILABLE, "XeSS with dll");
        check(UpscalerSelection.resolve(UpscalerBackend.XESS, dir) == UpscalerBackend.XESS, "XeSS with dll is kept");
    }

    private static void configRoundTripAndRejection() throws Exception {
        UpscalerBackend oldBackend = VulkanRtxConfig.activeBackend;
        UpscalerMode oldMode = VulkanRtxConfig.xessMode;
        Path dir = Files.createTempDirectory("vkb_sel_cfg");
        File cfg = dir.resolve("bedray.toml").toFile();
        try {
            VulkanRtxConfig.setConfigFileForTesting(cfg);
            VulkanRtxConfig.setGameDirForTesting(dir);

            // no dll: an XESS request must not become the active backend
            VulkanRtxConfig.activeBackend = UpscalerBackend.XESS;
            VulkanRtxConfig.enforceBackendAvailability();
            check(VulkanRtxConfig.activeBackend == UpscalerBackend.BILINEAR, "XESS without dll -> BILINEAR");
            VulkanRtxConfig.activeBackend = UpscalerBackend.DLSS;
            VulkanRtxConfig.enforceBackendAvailability();
            check(VulkanRtxConfig.activeBackend == UpscalerBackend.BILINEAR, "DLSS -> BILINEAR");

            // with the dll: XESS + mode survive a save/load round trip
            Files.createFile(Files.createDirectories(dir.resolve("bedray").resolve("natives")).resolve("libxess.dll"));
            VulkanRtxConfig.activeBackend = UpscalerBackend.XESS;
            VulkanRtxConfig.xessMode = UpscalerMode.PERFORMANCE;
            VulkanRtxConfig.save();
            String written = Files.readString(cfg.toPath(), StandardCharsets.UTF_8);
            check(written.contains("active_backend = \"XESS\"") && written.contains("xess_mode = \"PERFORMANCE\""), "toml content: " + written);
            check(written.contains("dlss_mode = \"QUALITY\"") && written.contains("fsr_mode = \"QUALITY\"")
                && written.contains("fsr_version = \"AUTO\"") && written.contains("fsr_sharpness = 0.0"), "dlss/fsr settings are persisted: " + written);
            VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;
            VulkanRtxConfig.xessMode = UpscalerMode.QUALITY;
            VulkanRtxConfig.load();
            check(VulkanRtxConfig.activeBackend == UpscalerBackend.XESS, "XESS loaded back");
            check(VulkanRtxConfig.xessMode == UpscalerMode.PERFORMANCE, "xess_mode loaded back");

            // a mode XeSS does not offer is refused, the previous one stays
            VulkanRtxConfig.applyKeyValue("xess_mode", "DLAA");
            check(VulkanRtxConfig.xessMode == UpscalerMode.PERFORMANCE, "DLAA is not a XeSS mode");
            VulkanRtxConfig.applyKeyValue("xess_mode", "garbage");
            check(VulkanRtxConfig.xessMode == UpscalerMode.PERFORMANCE, "garbage mode ignored");

            // the same file without the dll: loads as BILINEAR
            Files.delete(dir.resolve("bedray").resolve("natives").resolve("libxess.dll"));
            VulkanRtxConfig.load();
            check(VulkanRtxConfig.activeBackend == UpscalerBackend.BILINEAR, "XESS in toml but dll deleted -> BILINEAR");
        } finally {
            VulkanRtxConfig.activeBackend = oldBackend;
            VulkanRtxConfig.xessMode = oldMode;
            VulkanRtxConfig.setGameDirForTesting(Path.of("").toAbsolutePath().normalize());
        }
    }

    private static void earlyReadFromToml() throws Exception {
        Path dir = Files.createTempDirectory("vkb_sel_early");
        Path toml = dir.resolve("bedray.toml");
        check(UpscalerSelection.earlyXessQuality(toml, dir) == null, "no file -> no request");
        Files.writeString(toml, "[super_resolution]\nactive_backend = \"BILINEAR\"\nxess_mode = \"BALANCED\"\n", StandardCharsets.UTF_8);
        check(UpscalerSelection.earlyXessQuality(toml, dir) == null, "BILINEAR -> no XeSS request");
        Files.writeString(toml, "[super_resolution]\nactive_backend = \"XESS\"\nxess_mode = \"BALANCED\"\n", StandardCharsets.UTF_8);
        check(UpscalerSelection.earlyXessQuality(toml, dir) == null, "XESS without dll -> no request");
        Files.createFile(Files.createDirectories(dir.resolve("bedray").resolve("natives")).resolve("libxess.dll"));
        check(UpscalerSelection.earlyXessQuality(toml, dir) == XessQuality.BALANCED, "XESS + dll + BALANCED");
        Files.writeString(toml, "active_backend = \"XESS\"\n", StandardCharsets.UTF_8);
        check(UpscalerSelection.earlyXessQuality(toml, dir) == XessQuality.QUALITY, "missing xess_mode -> default QUALITY");
        Files.writeString(toml, "active_backend = \"XESS\"\nxess_mode = \"DLAA\"\n", StandardCharsets.UTF_8);
        check(UpscalerSelection.earlyXessQuality(toml, dir) == null, "invalid mode -> no request (logged)");
        Files.writeString(toml, "active_backend = \"DLSS\"\n", StandardCharsets.UTF_8);
        check(UpscalerSelection.earlyXessQuality(toml, dir) == null, "DLSS never requests XeSS");
    }
}
