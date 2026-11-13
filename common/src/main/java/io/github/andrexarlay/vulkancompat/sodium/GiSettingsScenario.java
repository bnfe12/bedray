package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkanbridge.autotest.AutotestContext;
import io.github.andrexarlay.vulkanbridge.autotest.AutotestScenario;
import io.github.andrexarlay.vulkancompat.rt.world.RtMode;
import io.github.andrexarlay.vulkancompat.rtx.config.GiStackPolicy;
import io.github.andrexarlay.vulkancompat.rtx.config.TexturePreset;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.GiDeviceProbe;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;

/**
 * {@code gi_settings}: L4 proof of the GI auto-policy and texture-style actions in a running game. Probes the live Vulkan
 * device, runs the real recommendation, stages it through the real {@link SettingsDraft} (the same path the Overview
 * buttons use), reads the staged option values back and asserts they match the plan, then opens the real
 * {@link BedrayConfigScreen} and shoots the Overview. Lives in the sodium package because SettingsDraft and its config are
 * package-private. PASS means the staging matched the plan on real hardware and the screen rendered; it is not an FPS claim.
 */
public final class GiSettingsScenario implements AutotestScenario {
    @Override public String name() { return "gi_settings"; }

    @Override public void run(AutotestContext ctx) {
        VulkanRtxConfig.rtMode = RtMode.PATH_TRACED;
        VulkanRtxConfig.enableHardwareRayTracing = true;
        ctx.log("scenario", name(), "event", "arming", "wait_frames", "200");
        ctx.waitFrames(200, () -> exercise(ctx));
    }

    private void exercise(AutotestContext ctx) {
        var mc = ctx.minecraft();
        var device = GiDeviceProbe.probe();
        if (device == null) {
            ctx.fail("gi_settings: no Vulkan device (RT did not initialize in the test world)", null);
            return;
        }
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        var plan = GiStackPolicy.recommend(device, w, h);
        ctx.log("scenario", name(), "event", "device", "memory_bytes", String.valueOf(device.memoryBytes()),
            "capability_mask", "0x" + Integer.toHexString(device.capabilityMask()), "window", w + "x" + h);
        ctx.log("scenario", name(), "event", "plan", "tier", plan.tier().name(), "describe", GiStackPolicy.describe(plan));

        SettingsDraft draft = new SettingsDraft();
        draft.applyGiPlan(plan);
        ctx.log("scenario", name(), "event", "gi_staged", "spp", String.valueOf(staged(draft, "path_spp")),
            "bounces", String.valueOf(staged(draft, "path_bounces")), "distance", String.valueOf(staged(draft, "path_distance")),
            "restir_di", String.valueOf(staged(draft, "path_restir_di")), "restir_pt", String.valueOf(staged(draft, "path_restir_pt")),
            "restir_pt_spatial", String.valueOf(staged(draft, "path_restir_pt_spatial")),
            "profile", String.valueOf(staged(draft, "path_quality_profile")));
        String giError = checkGi(draft, plan);
        if (giError != null) {
            ctx.fail("gi_settings: " + giError, null);
            return;
        }

        for (TexturePreset preset : TexturePreset.values()) {
            SettingsDraft td = new SettingsDraft();
            td.applyTexturePreset(preset);
            Object point = staged(td, "point_sampling_sharpness");
            Object offset = staged(td, "texture_lod_bias_offset");
            ctx.log("scenario", name(), "event", "texture_staged", "preset", preset.name(),
                "point_sampling", String.valueOf(point), "lod_bias_offset", String.valueOf(offset));
            if (!boolEq(point, preset.pointSampling) || !numEq(offset, preset.lodBiasOffsetTenths)) {
                ctx.fail("gi_settings: texture preset " + preset + " staged point=" + point + " offset=" + offset, null);
                return;
            }
        }

        mc.gui.setScreen(new BedrayConfigScreen(null));
        ctx.waitFrames(25, () -> ctx.shot("gi_settings_overview.png", () -> {
            ctx.log("scenario", name(), "event", "screen", "rendered", "true");
            ctx.finish();
        }));
    }

    private static String checkGi(SettingsDraft draft, GiStackPolicy.Plan plan) {
        if (!numEq(staged(draft, "path_spp"), plan.spp())) return "path_spp != plan.spp";
        if (!numEq(staged(draft, "path_bounces"), plan.bounces())) return "path_bounces != plan.bounces";
        if (!numEq(staged(draft, "path_distance"), plan.distance())) return "path_distance != plan.distance";
        if (!boolEq(staged(draft, "path_restir_di"), plan.restirDi())) return "path_restir_di != plan.restirDi";
        if (!boolEq(staged(draft, "path_restir_pt"), plan.restirPt())) return "path_restir_pt != plan.restirPt";
        if (!boolEq(staged(draft, "path_restir_pt_spatial"), plan.restirPtSpatial())) return "path_restir_pt_spatial != plan.restirPtSpatial";
        if (!VulkanSodiumConfig.QualityProfile.CUSTOM.equals(staged(draft, "path_quality_profile"))) return "path_quality_profile != CUSTOM";
        return null;
    }

    private static boolean numEq(Object staged, int expected) {
        return staged instanceof Number n && n.intValue() == expected;
    }

    private static boolean boolEq(Object staged, boolean expected) {
        return staged instanceof Boolean b && b == expected;
    }

    private static Object staged(SettingsDraft draft, String path) {
        var option = draft.config.getOption(SettingsDraft.id(path));
        return option instanceof StatefulOption<?> so ? so.getValidatedValue() : null;
    }
}