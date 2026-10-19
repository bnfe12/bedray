package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import io.github.andrexarlay.vulkanbridge.core.DeviceFeatureRegistryImpl;
import io.github.andrexarlay.vulkancompat.autotest.UpscalerSwitchScenario;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerReconfigure.Phase;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerReconfigure.Step;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrQuality;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrRequest;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrVersion;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessDeviceRequirements;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessQuality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java-level checks (L2) of the runtime upscaler switch: preregistration policy, restart decision, request transitions,
 * reconfigure planning, the Config API and the autotest step parser. No GPU, no game: this is NOT evidence that the switch
 * works in game (that is the {@code upscaler_switch} autotest scenario).
 */
public final class UpscalerSwitchChecks {
    private UpscalerSwitchChecks() {}

    public static void runAll() throws Exception {
        preregistrationPolicy();
        restartDecision();
        xessProviderDecisions();
        requestTransitions();
        reconfigurePlanning();
        configApi();
        scenarioStepParser();
        langKeys();
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void preregistrationPolicy() {
        check(UpscalerPreregistration.parseEnabled(null), "default: preregistration on");
        check(UpscalerPreregistration.parseEnabled(""), "blank: on");
        check(UpscalerPreregistration.parseEnabled("true"), "true: on");
        check(!UpscalerPreregistration.parseEnabled("false"), "false: off (emergency switch)");
        check(!UpscalerPreregistration.parseEnabled(" FALSE "), "case/space tolerant");
        boolean threw = false;
        try {
            UpscalerPreregistration.parseEnabled("maybe");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "an invalid value is an error, not a silent default");
        check(UpscalerPreregistration.decide(true, true) == UpscalerPreregistration.Decision.REGISTER_REQUESTED, "requested + prereg");
        check(UpscalerPreregistration.decide(true, false) == UpscalerPreregistration.Decision.REGISTER_REQUESTED,
            "requested is always registered, even with the emergency switch");
        check(UpscalerPreregistration.decide(false, true) == UpscalerPreregistration.Decision.REGISTER_PREREGISTERED, "not requested + prereg");
        check(UpscalerPreregistration.decide(false, false) == UpscalerPreregistration.Decision.SKIP, "not requested + switch off");
    }

    private static void restartDecision() {
        UpscalerPreregistration.resetForTests();
        check(!UpscalerPreregistration.requiresRestart(UpscalerBackend.BILINEAR), "BILINEAR never needs a restart");
        check(UpscalerPreregistration.requiresRestart(UpscalerBackend.FSR), "FSR before its provider ran: restart (conservative)");
        check(UpscalerPreregistration.requiresRestart(UpscalerBackend.XESS), "XESS before its provider ran: restart (conservative)");
        check(!UpscalerPreregistration.providersDecided(), "nothing decided yet");
        UpscalerPreregistration.record(UpscalerBackend.XESS, true);
        check(!UpscalerPreregistration.requiresRestart(UpscalerBackend.XESS), "XESS registered: live switch");
        check(!UpscalerPreregistration.providersDecided(), "DLSS provider not run yet");
        UpscalerPreregistration.record(UpscalerBackend.DLSS, false);
        check(!UpscalerPreregistration.providersDecided(), "FSR provider not run yet");
        UpscalerPreregistration.record(UpscalerBackend.FSR, true);
        check(UpscalerPreregistration.providersDecided(), "all three providers ran");
        check(!UpscalerPreregistration.requiresRestart(UpscalerBackend.FSR), "FSR registered: live switch");
        check(UpscalerPreregistration.requiresRestart(UpscalerBackend.DLSS), "DLSS not registered: restart");
        UpscalerPreregistration.resetForTests();
    }

    private static void xessProviderDecisions() throws IOException {
        Path withDll = Files.createTempDirectory("vkb_prereg_dll");
        Path without = Files.createTempDirectory("vkb_prereg_nodll");
        try {
            Path natives = withDll.resolve("bedray").resolve("natives");
            Files.createDirectories(natives);
            Files.write(natives.resolve("libxess.dll"), new byte[] {1});

            UpscalerPreregistration.resetForTests();
            DeviceFeatureRegistryImpl reg = new DeviceFeatureRegistryImpl();
            XessDeviceRequirements.register(reg, () -> withDll, false, true);
            Set<String> ids = reg.snapshot().stream().map(r -> r.id()).collect(Collectors.toSet());
            check(ids.equals(XessDeviceRequirements.IDS), "not requested + library present + prereg: both XeSS groups registered, got " + ids);
            check(reg.requestedInstanceApiVersion() == 0x403000, "XeSS preregistration raises the instance to 1.3");
            check(UpscalerPreregistration.isRegistered(UpscalerBackend.XESS), "recorded as registered");

            UpscalerPreregistration.resetForTests();
            reg = new DeviceFeatureRegistryImpl();
            XessDeviceRequirements.register(reg, () -> withDll, false, false);
            check(reg.snapshot().isEmpty() && reg.requestedInstanceApiVersion() == 0,
                "emergency switch: nothing registered, instance untouched");
            check(!UpscalerPreregistration.isRegistered(UpscalerBackend.XESS) && UpscalerPreregistration.requiresRestart(UpscalerBackend.XESS),
                "recorded as not registered -> restart required for XeSS");

            UpscalerPreregistration.resetForTests();
            reg = new DeviceFeatureRegistryImpl();
            XessDeviceRequirements.register(reg, () -> withDll, true, false);
            check(reg.snapshot().size() == 2, "requested + switch off: still registered (explicit request wins)");

            UpscalerPreregistration.resetForTests();
            reg = new DeviceFeatureRegistryImpl();
            XessDeviceRequirements.register(reg, () -> without, false, true);
            check(reg.snapshot().isEmpty() && !UpscalerPreregistration.isRegistered(UpscalerBackend.XESS),
                "library absent: nothing registered even with preregistration (device unchanged)");
        } finally {
            UpscalerPreregistration.resetForTests();
            deleteTree(withDll);
            deleteTree(without);
        }
    }

    private static void requestTransitions() {
        UpscalerRequest.resetForTests();
        FsrRequest fsrQ = new FsrRequest(FsrQuality.QUALITY, FsrVersion.AUTO);
        check(UpscalerRequest.fromEarly(null, null, null).backend() == UpscalerBackend.BILINEAR, "no early request: BILINEAR");
        check(UpscalerRequest.fromEarly(XessQuality.BALANCED, null, null).xessQuality() == XessQuality.BALANCED, "early XeSS");
        check(UpscalerRequest.fromEarly(null, DlssQuality.DLAA, null).dlssQuality() == DlssQuality.DLAA, "early DLSS");
        check(UpscalerRequest.fromEarly(null, null, fsrQ).fsrRequest().equals(fsrQ), "early FSR");
        check(UpscalerRequest.fromEarly(XessQuality.QUALITY, DlssQuality.QUALITY, fsrQ).backend() == UpscalerBackend.XESS, "XeSS wins over DLSS and FSR");
        check(UpscalerRequest.fromEarly(null, DlssQuality.QUALITY, fsrQ).backend() == UpscalerBackend.DLSS, "DLSS wins over FSR");

        UpscalerRequest.set(UpscalerBackend.BILINEAR, null, null, "test_normalise");
        check(!UpscalerRequest.set(UpscalerBackend.BILINEAR, UpscalerMode.BALANCED, UpscalerMode.BALANCED, "t"),
            "BILINEAR ignores modes: not a change");
        check(UpscalerRequest.set(UpscalerBackend.XESS, UpscalerMode.QUALITY, UpscalerMode.QUALITY, "t"), "BILINEAR -> XESS");
        check(UpscalerRequest.current().xessQuality() == XessQuality.QUALITY && UpscalerRequest.current().dlssQuality() == null,
            "only the selected technology carries a mode");
        check(!UpscalerRequest.set(UpscalerBackend.XESS, UpscalerMode.QUALITY, UpscalerMode.PERFORMANCE, "t"),
            "changing the DLSS mode while XESS is selected is not a reconfiguration");
        check(UpscalerRequest.set(UpscalerBackend.XESS, UpscalerMode.PERFORMANCE, null, "t"), "XESS mode change");
        check(UpscalerRequest.set(UpscalerBackend.DLSS, UpscalerMode.PERFORMANCE, UpscalerMode.DLAA, "t"), "XESS -> DLSS");
        check(UpscalerRequest.current().xessQuality() == null && UpscalerRequest.current().dlssQuality() == DlssQuality.DLAA,
            "DLSS selected with DLAA");
        check(UpscalerRequest.set(UpscalerBackend.BILINEAR, null, null, "t") && UpscalerRequest.current().backend() == UpscalerBackend.BILINEAR,
            "DLSS -> BILINEAR");
        check(UpscalerRequest.current().toString().equals("BILINEAR"), "toString");
        boolean threw = false;
        try {
            UpscalerRequest.set(UpscalerBackend.FSR, null, null, "t");
        } catch (NullPointerException expected) {
            threw = true;
        }
        check(threw, "FSR needs a mode and a version (no silent default)");
        check(UpscalerRequest.set(UpscalerBackend.FSR, null, null, UpscalerMode.QUALITY, FsrVersion.AUTO, "t"), "BILINEAR -> FSR");
        check(UpscalerRequest.current().fsrRequest().equals(fsrQ) && UpscalerRequest.current().xessQuality() == null,
            "FSR selected, only FSR carries a request");
        check(!UpscalerRequest.set(UpscalerBackend.FSR, UpscalerMode.PERFORMANCE, null, UpscalerMode.QUALITY, FsrVersion.AUTO, "t"),
            "XeSS mode while FSR is selected is not a reconfiguration");
        check(UpscalerRequest.set(UpscalerBackend.FSR, null, null, UpscalerMode.QUALITY, FsrVersion.FSR_2, "t"), "FSR version change re-creates");
        check(UpscalerRequest.current().toString().equals("FSR/QUALITY@FSR_2"), "toString FSR: " + UpscalerRequest.current());
        check(UpscalerReconfigure.plan(Phase.READY, UpscalerRequest.current().fsrRequest(), fsrQ, fsrQ, 1, 0) == Step.BEGIN_RETIRE,
            "READY FSR context with another version is retired");
        threw = false;
        try {
            UpscalerRequest.set(UpscalerBackend.FSR, null, null, UpscalerMode.ULTRA_QUALITY, FsrVersion.AUTO, "t");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "FSR has no Ultra Quality mode");
        UpscalerRequest.resetForTests();
    }

    private static void reconfigurePlanning() {
        long retireAt = 13;
        check(UpscalerReconfigure.plan(Phase.READY, XessQuality.QUALITY, XessQuality.QUALITY, XessQuality.QUALITY, 10, 0) == Step.NONE, "READY, same");
        check(UpscalerReconfigure.plan(Phase.READY, XessQuality.PERFORMANCE, XessQuality.QUALITY, XessQuality.QUALITY, 10, 0) == Step.BEGIN_RETIRE,
            "READY, other mode");
        check(UpscalerReconfigure.plan(Phase.READY, (XessQuality) null, XessQuality.QUALITY, XessQuality.QUALITY, 10, 0) == Step.BEGIN_RETIRE,
            "READY, backend switched away");
        check(UpscalerReconfigure.plan(Phase.RECONFIGURE_PENDING, XessQuality.PERFORMANCE, null, XessQuality.QUALITY, 12, retireAt) == Step.NONE,
            "PENDING before the retire frame");
        check(UpscalerReconfigure.plan(Phase.RECONFIGURE_PENDING, XessQuality.PERFORMANCE, null, XessQuality.QUALITY, 13, retireAt) == Step.DESTROY_RETIRED,
            "PENDING at the retire frame");
        check(UpscalerReconfigure.plan(Phase.IDLE, XessQuality.PERFORMANCE, null, null, 13, 0) == Step.CREATE, "IDLE, requested");
        check(UpscalerReconfigure.plan(Phase.IDLE, (XessQuality) null, null, null, 13, 0) == Step.NONE, "IDLE, not requested");
        check(UpscalerReconfigure.plan(Phase.FAILED, XessQuality.QUALITY, null, XessQuality.QUALITY, 13, 0) == Step.NONE,
            "FAILED for this very request: no hidden retry");
        check(UpscalerReconfigure.plan(Phase.FAILED, XessQuality.BALANCED, null, XessQuality.QUALITY, 13, 0) == Step.CLEAR_FAILED,
            "FAILED, request changed: explicit new request");
        check(UpscalerReconfigure.plan(Phase.FAILED, (XessQuality) null, null, XessQuality.QUALITY, 13, 0) == Step.CLEAR_FAILED,
            "FAILED, switched away: cleared so that a later re-selection retries");

        // frame-by-frame simulation of XESS QUALITY -> PERFORMANCE (the loop of XessRuntime.onFrameBegin)
        Phase phase = Phase.READY;
        XessQuality active = XessQuality.QUALITY;
        XessQuality desired = XessQuality.PERFORMANCE;
        XessQuality attempted = XessQuality.QUALITY;
        long retire = 0;
        long destroyedAt = -1;
        long createdAt = -1;
        for (long frame = 10; frame <= 20 && createdAt < 0; frame++) {
            for (int guard = 0; guard < 4; guard++) {
                Step step = UpscalerReconfigure.plan(phase, desired, active, attempted, frame, retire);
                if (step == Step.NONE) {
                    break;
                }
                switch (step) {
                    case BEGIN_RETIRE -> {
                        phase = Phase.RECONFIGURE_PENDING;
                        active = null;
                        retire = frame + UpscalerReconfigure.RETIRE_DELAY_FRAMES;
                    }
                    case DESTROY_RETIRED -> {
                        destroyedAt = frame;
                        phase = Phase.IDLE;
                    }
                    case CLEAR_FAILED -> phase = Phase.IDLE;
                    case CREATE -> {
                        createdAt = frame;
                        attempted = desired;
                        active = desired;
                        phase = Phase.READY;
                    }
                    default -> throw new AssertionError(step);
                }
                if (step == Step.CREATE) {
                    break;
                }
            }
        }
        check(destroyedAt == 10 + UpscalerReconfigure.RETIRE_DELAY_FRAMES, "old context destroyed " + UpscalerReconfigure.RETIRE_DELAY_FRAMES
            + " frame heads after it was retired, got " + destroyedAt);
        check(createdAt == destroyedAt, "the new context is created in the same frame head the old one is destroyed");
        check(phase == Phase.READY && active == XessQuality.PERFORMANCE, "ends READY in the new mode");
        check(UpscalerReconfigure.RETIRE_DELAY_FRAMES >= 3, "not shorter than RtWorld.DEFER_FRAMES (3): the engine keeps up to 2 submissions in flight");
        check(UpscalerReconfigure.describe("XESS", XessQuality.QUALITY).equals("XESS/QUALITY") && UpscalerReconfigure.describe("XESS", null).equals("none"),
            "log text");
    }

    private static void configApi() throws IOException {
        Path dir = Files.createTempDirectory("vkb_switch_cfg");
        Path savedGameDir = VulkanRtxConfig.gameDir();
        UpscalerBackend savedBackend = VulkanRtxConfig.activeBackend;
        UpscalerMode savedXess = VulkanRtxConfig.xessMode;
        try {
            Path natives = dir.resolve("bedray").resolve("natives");
            Files.createDirectories(natives);
            Files.write(natives.resolve("libxess.dll"), new byte[] {1});
            VulkanRtxConfig.setGameDirForTesting(dir);
            UpscalerRequest.resetForTests();
            UpscalerPreregistration.resetForTests();
            UpscalerRequest.set(UpscalerBackend.BILINEAR, null, null, "test_normalise");

            // XeSS library present but the device was created without the XeSS requirements: stored, not applied live
            UpscalerSwitch.Outcome o = VulkanRtxConfig.applyUpscaler(UpscalerBackend.XESS, UpscalerMode.BALANCED, null, "test");
            check(o == UpscalerSwitch.Outcome.DEFERRED_RESTART, "unregistered XeSS: DEFERRED_RESTART, got " + o);
            check(VulkanRtxConfig.activeBackend == UpscalerBackend.XESS && VulkanRtxConfig.xessMode == UpscalerMode.BALANCED,
                "the choice is stored (persisted by the page) for the next start");
            check(UpscalerRequest.current().backend() == UpscalerBackend.BILINEAR, "the live request did not change");

            // registered at startup: live
            UpscalerPreregistration.record(UpscalerBackend.XESS, true);
            o = VulkanRtxConfig.applyUpscaler(UpscalerBackend.XESS, UpscalerMode.BALANCED, null, "test");
            check(o == UpscalerSwitch.Outcome.APPLIED_LIVE, "registered XeSS: live, got " + o);
            check(UpscalerRequest.current().xessQuality() == XessQuality.BALANCED, "request is XESS/BALANCED");
            o = VulkanRtxConfig.applyUpscaler(UpscalerBackend.XESS, UpscalerMode.BALANCED, null, "test");
            check(o == UpscalerSwitch.Outcome.UNCHANGED, "same choice: UNCHANGED");
            o = VulkanRtxConfig.applyUpscaler(UpscalerBackend.XESS, UpscalerMode.PERFORMANCE, null, "test");
            check(o == UpscalerSwitch.Outcome.APPLIED_LIVE && UpscalerRequest.current().xessQuality() == XessQuality.PERFORMANCE,
                "mode change is live");
            o = VulkanRtxConfig.applyUpscaler(UpscalerBackend.BILINEAR, null, null, "test");
            check(o == UpscalerSwitch.Outcome.APPLIED_LIVE && UpscalerRequest.current().backend() == UpscalerBackend.BILINEAR,
                "back to BILINEAR is live");
            check(VulkanRtxConfig.upscalerType == VulkanRtxConfig.UpscalerType.OFF
                || VulkanRtxConfig.upscalerType == VulkanRtxConfig.UpscalerType.BILINEAR, "upscalerType stays derived from the preset");

            // a backend that cannot be made active (library gone) is rejected to BILINEAR, not stored
            VulkanRtxConfig.setGameDirForTesting(Files.createTempDirectory("vkb_switch_cfg_empty"));
            o = VulkanRtxConfig.applyUpscaler(UpscalerBackend.XESS, UpscalerMode.QUALITY, null, "test");
            check(VulkanRtxConfig.activeBackend == UpscalerBackend.BILINEAR, "library missing: BILINEAR with a WARN");
        } finally {
            VulkanRtxConfig.setGameDirForTesting(savedGameDir);
            VulkanRtxConfig.activeBackend = savedBackend;
            VulkanRtxConfig.xessMode = savedXess;
            UpscalerRequest.resetForTests();
            UpscalerPreregistration.resetForTests();
            deleteTree(dir);
        }
    }

    private static void scenarioStepParser() {
        List<UpscalerSwitchScenario.Step> steps = UpscalerSwitchScenario.parseSteps(UpscalerSwitchScenario.DEFAULT_STEPS);
        check(steps.size() == 6, "default sequence has 6 steps");
        check(steps.get(0).backend() == UpscalerBackend.BILINEAR && steps.get(0).mode() == null, "step 0 BILINEAR");
        check(steps.get(1).backend() == UpscalerBackend.XESS && steps.get(1).mode() == UpscalerMode.QUALITY, "step 1 XESS:QUALITY");
        check(steps.get(4).backend() == UpscalerBackend.DLSS && steps.get(4).mode() == UpscalerMode.PERFORMANCE, "step 4 DLSS:PERFORMANCE");
        check(UpscalerSwitchScenario.parseSteps("xess").get(0).mode() == UpscalerMode.QUALITY, "missing mode = default mode");
        List<UpscalerSwitchScenario.Step> fsr = UpscalerSwitchScenario.parseSteps("FSR,FSR:PERFORMANCE:2,FSR:NATIVE_AA:3.1");
        check(fsr.get(0).mode() == UpscalerMode.QUALITY && fsr.get(0).fsrVersion() == FsrVersion.AUTO, "FSR default step");
        check(fsr.get(1).mode() == UpscalerMode.PERFORMANCE && fsr.get(1).fsrVersion() == FsrVersion.FSR_2, "FSR:PERFORMANCE:2");
        check(fsr.get(2).fsrVersion() == FsrVersion.FSR_3_1, "FSR:NATIVE_AA:3.1");
        for (String bad : new String[] {"FSR:ULTRA_QUALITY", "FSR:QUALITY:4", "XESS:QUALITY:2", "XESS:DLAA", "BILINEAR:QUALITY", "NOPE", ""}) {
            boolean threw = false;
            try {
                UpscalerSwitchScenario.parseSteps(bad);
            } catch (IllegalArgumentException expected) {
                threw = true;
            }
            check(threw, "invalid steps rejected: '" + bad + "'");
        }
    }

    /** Both language files carry the keys of the live-switch texts, and no text still says XeSS/DLSS always needs a restart. */
    private static void langKeys() throws IOException {
        String[] keys = {
            "bedray.status.restart_required",
            "bedray.upscaler.tooltip.xess.available", "bedray.upscaler.tooltip.xess.restart_required",
            "bedray.upscaler.tooltip.dlss.available", "bedray.upscaler.tooltip.dlss.restart_required",
            "bedray.option.xess_mode.tooltip", "bedray.option.xess_mode.tooltip.restart",
            "bedray.option.dlss_mode.tooltip", "bedray.option.dlss_mode.tooltip.restart"
        };
        for (String lang : new String[] {"en_us", "ru_ru"}) {
            String resource = "/assets/bedray/lang/" + lang + ".json";
            String text;
            try (var in = UpscalerSwitchChecks.class.getResourceAsStream(resource)) {
                check(in != null, resource + " on the classpath");
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            check(text.isEmpty() || text.charAt(0) != '﻿', lang + ": UTF-8 without BOM");
            for (String k : keys) {
                int first = text.indexOf("\"" + k + "\"");
                check(first >= 0, lang + " has " + k);
                check(text.indexOf("\"" + k + "\"", first + 1) < 0, lang + " has no duplicate " + k);
            }
            check(!text.contains("Applied after a game restart: the Vulkan device is created with"), lang + ": old always-restart tooltip is gone");
            check(!text.contains("Применяется после перезапуска игры: устройство Vulkan создаётся"), lang + ": old always-restart tooltip is gone");
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
