package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.autotest.RtModesScenario;
import io.github.andrexarlay.vulkancompat.rt.material.AutoPbr;
import io.github.andrexarlay.vulkancompat.rt.material.LabPbr;
import io.github.andrexarlay.vulkancompat.rt.material.MaterialRegion;
import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * L2 checks of the two RT modes (no GPU): LabPBR decoding, auto-PBR table, material atlas region fill, push-constant packing
 * of the new flags/params, accumulation reset logic, settings/toml keys, autotest step parsing, and shaderc compilation of
 * rt_rays.comp (plain + gbuffer) and rt_path.comp (accum).
 */
final class RtModesChecks {
    private RtModesChecks() {
    }

    static void runAll() {
        labPbrDecode();
        autoPbrTable();
        regionFill();
        paramsPacking();
        accumulation();
        settingsKeys();
        telemetry();
        scenarioSteps();
        shadersCompile();
        exposureAndEmitters();
        autoNormals();
        resetLevels();
        uiSettingsChecks();
        RtEmittersStabilityTest.runAll();
        RtShadingFixesTest.runAll();
        RtVolumetricFogTest.runAll();
        RtAtmosphereTest.runAll();
        RtIceGlassOpticsTest.runAll();
        RtWaterMediumTest.runAll();
        RtVanillaWaterLightTest.runAll();
        RtShaderNaNGuardTest.runAll();
    }

    private static void resetLevels() {
        RtSettings.applyPreset(RtSettings.Preset.HIGH);
        RtSettings.raysAoQuality = 7;
        RtSettings.raysAo = false;
        RtSettings.preset = RtSettings.Preset.HIGH;
        RtSettings.resetLevel(RtSettings.Level.EXPERT);
        check(RtSettings.raysAoQuality == 4 && !RtSettings.raysAo, "expert reset: numbers from HIGH, switches kept");
        RtSettings.preset = RtSettings.Preset.HIGH;
        RtSettings.raysAoQuality = 7;
        RtSettings.resetLevel(RtSettings.Level.ADVANCED);
        check(RtSettings.raysAo && RtSettings.raysAoQuality == 7, "advanced reset: switches from HIGH, numbers kept");
        RtSettings.resetLevel(RtSettings.Level.ALL);
        check(RtSettings.raysAoQuality == 2 && RtSettings.preset == RtSettings.Preset.MEDIUM, "all: Custom -> MEDIUM values");
        RtSettings.applyPathPreset(RtSettings.PathPreset.FAST);
        check(RtSettings.pathPreset == RtSettings.PathPreset.FAST && RtSettings.pathBounces == 2 && RtSettings.pathSpp == 1
            && RtSettings.pathDistance == 128 && !RtSettings.caustics && RtSettings.autoExposure(), "path preset FAST values");
        RtSettings.applyPathPreset(RtSettings.PathPreset.QUALITY);
        check(RtSettings.pathPreset == RtSettings.PathPreset.QUALITY && RtSettings.pathBounces == 4 && RtSettings.pathSpp == 2
            && RtSettings.pathDistance == 384 && RtSettings.caustics && RtSettings.autoExposure(), "path preset QUALITY values");
        check(RtSettings.PathPreset.FAST.autoExposure && RtSettings.PathPreset.QUALITY.autoExposure
            && RtSettings.PathPreset.CUSTOM.autoExposure, "all path presets have autoExposure enabled");
        RtSettings.applyPreset(RtSettings.Preset.MEDIUM);
    }

    private static void uiSettingsChecks() {
        new io.github.andrexarlay.vulkancompat.sodium.VulkanSodiumConfigIdsTest().everyOptionIdentifierIsUnique();
        java.io.File tmpCfg = null;
        try {
            tmpCfg = java.io.File.createTempFile("vkrtx_ui_test", ".toml");
            VulkanRtxConfig.setConfigFileForTesting(tmpCfg);
            RtSettings.showAdvanced = false;
            RtSettings.showExpert = false;
            check(!io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.isCollapsible("other"), "Non-registered group is not collapsible");
            check(io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.isCollapsible(io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.RT_ADVANCED),
                "RT_ADVANCED is collapsible");
            check(!io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.isExpanded(io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.RT_ADVANCED),
                "Advanced group collapsed by default");
            check(!io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.isExpanded(io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.RT_EXPERT),
                "Expert group collapsed by default");
            io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.toggle(io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.RT_ADVANCED, "test");
            check(io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.isExpanded(io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.RT_ADVANCED),
                "Toggling collapsed group opens it");
            check(io.github.andrexarlay.vulkancompat.sodium.CollapsibleGroups.consumeRebuildRequest(), "Toggle sets rebuild request");
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        } finally {
            RtSettings.showAdvanced = false;
            RtSettings.showExpert = false;
            VulkanRtxConfig.setConfigFileForTesting(new java.io.File("config/bedray.toml"));
            if (tmpCfg != null) {
                tmpCfg.delete();
            }
        }
        var e = io.github.andrexarlay.vulkancompat.sodium.PreviewPanel.lookup(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("bedray", "rays_shadows"), null);
        check(e != null && "rays_shadows".equals(e.path()) && !e.noImage().isEmpty(), "PreviewPanel lookup finds rays_shadows entry (honest no-image reason, no simulation)");
    }

    private static void autoNormals() {
        // 4x2 sprite at (2,0) in an 8-wide atlas: dark left half, bright right half -> normals tilt to -x at the edge
        int[] argb = {0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF};
        int[] nrm = new int[16];
        java.util.Arrays.fill(nrm, 0x12345678);
        io.github.andrexarlay.vulkancompat.rt.material.AutoNormals.fill(nrm, 8, 2, 0, 4, 2, new MaterialRegion.Image(4, 2, argb));
        check(nrm[0] == 0x12345678 && nrm[1] == 0x12345678 && nrm[6] == 0x12345678, "outside the sprite untouched");
        int edge = nrm[0 * 8 + 2 + 1];   // texel x=1: rising height to the right -> normal x < 0 -> red < 128
        check((edge & 255) < 128, "edge tilts away from the bright side: r=" + (edge & 255));
        int flatInside = nrm[0 * 8 + 2 + 3];   // texel x=3, clamped neighbours are bright -> only half a slope, still tilted
        check((flatInside & 255) <= 128, "clamped at the sprite border, no texel of the neighbour sprite");
        check(((edge >>> 16) & 255) == 255, "no AO in the blue channel");
        // uniform texture -> flat normal
        int[] uni = new int[4];
        java.util.Arrays.fill(uni, 0xFF808080);
        int[] n2 = new int[4];
        io.github.andrexarlay.vulkancompat.rt.material.AutoNormals.fill(n2, 2, 0, 0, 2, 2, new MaterialRegion.Image(2, 2, uni));
        check((n2[0] & 255) == 128 && ((n2[0] >>> 8) & 255) == 128, "uniform texture -> flat normal");
        // low-contrast 1-2/255 luminance noise (concrete/quartz wall) -> deadzone keeps flat normal (128, 128)
        int[] noisyWall = {0xFF808080, 0xFF828282, 0xFF818181, 0xFF7F7F7F};
        int[] nWall = new int[4];
        io.github.andrexarlay.vulkancompat.rt.material.AutoNormals.fill(nWall, 2, 0, 0, 2, 2, new MaterialRegion.Image(2, 2, noisyWall));
        check((nWall[0] & 255) == 128 && ((nWall[0] >>> 8) & 255) == 128, "low-contrast wall noise -> flat normal");
        // material alpha bit 1 marks LabPBR _n
        int[] mat = new int[4];
        int[] nn = new int[4];
        MaterialRegion.fill(mat, nn, 2, 0, 0, 2, 2, AutoPbr.DEFAULT, null, null, new MaterialRegion.Image(2, 2, uni));
        check(((mat[0] >>> 24) & 2) == 0, "LabPBR _n present -> alpha bit 1 cleared");
        MaterialRegion.fill(mat, nn, 2, 0, 0, 2, 2, AutoPbr.DEFAULT, null, null, null);
        check(((mat[0] >>> 24) & 2) != 0, "no _n -> alpha bit 1 set");
    }

    private static void exposureAndEmitters() {
        RtExposure e = new RtExposure();
        near(RtExposure.MIN, e.value(), 0f, "starts at the ray-mode exposure");
        // dark image: mean luminance 2^-6 -> target 0.22 * 64 = 14 -> moves up
        long dark = (long) ((-6 + 16) * 64) * 100;
        for (int i = 0; i < 900; i++) {
            e.observe(dark, 100);
        }
        near(RtExposure.MAX, e.value(), 0.2f, "dark image converges to the cap MAX (KEY / L = 14 is above it)");
        long bright = (long) ((2 + 16) * 64) * 100;
        for (int i = 0; i < 600; i++) {
            e.observe(bright, 100);
        }
        near(RtExposure.MIN, e.value(), 0.1f, "bright image clamps at MIN (within the dead band)");
        // frame-rate independence: 1 s of 30 fps and 1 s of 120 fps move the exposure by (almost) the same amount
        RtExposure slow = new RtExposure();
        RtExposure fast = new RtExposure();
        for (int i = 0; i < 30; i++) {
            slow.observe(dark, 100, 1f / 30f);
        }
        for (int i = 0; i < 120; i++) {
            fast.observe(dark, 100, 1f / 120f);
        }
        near(slow.value(), fast.value(), 0.1f * slow.value(), "adaptation does not depend on the frame rate");
        // a noise-sized wobble of the measured luminance (below the dead band) never moves the exposure
        RtExposure steady = new RtExposure();
        long mid = (long) ((-3 + 16) * 64) * 100;
        for (int i = 0; i < 3000; i++) {
            steady.observe(mid, 100);
        }
        float settled = steady.value();
        for (int i = 0; i < 400; i++) {
            steady.observe(mid + (i % 2 == 0 ? 3L : -3L) * 100L, 100);
        }
        near(settled, steady.value(), 0.02f * settled, "dead band suppresses pulsing");
        float before = e.value();
        e.observe(0L, 100);
        check(e.value() == before, "no data (ray pass) keeps the value");

        RtExposure safeExp = new RtExposure();
        safeExp.observe(Long.MAX_VALUE, 100);
        check(!Float.isNaN(safeExp.value()) && !Float.isInfinite(safeExp.value()), "exposure never becomes NaN/Inf on extreme input");
        safeExp.reset();
        check(safeExp.value() == RtExposure.MIN, "reset returns value to MIN");

        float[] extreme = RtTonemap.apply(100000f, 100000f, 100000f);
        check(extreme[0] >= 0.99f && extreme[0] <= 1.0f && !Float.isNaN(extreme[0]), "tonemap handles extreme radiance smoothly <= 1.0");
        float[] nanInput = RtTonemap.apply(Float.NaN, Float.POSITIVE_INFINITY, -5f);
        check(!Float.isNaN(nanInput[0]) && !Float.isNaN(nanInput[1]) && !Float.isNaN(nanInput[2]), "tonemap eliminates NaN/Inf");

        // one 1x1 quad on the plane y=0 whose UV maps to an emissive texel, one quad on a dark texel
        int[] mat = new int[4 * 4];
        java.util.Arrays.fill(mat, LabPbr.pack(255, 0, 0, LabPbr.SOURCE_AUTO));
        mat[1 * 4 + 1] = LabPbr.pack(255, 0, 255, LabPbr.SOURCE_AUTO);   // texel (1,1) emission 1
        float[] pos = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1,   5, 0, 0, 6, 0, 0, 6, 0, 1, 5, 0, 1};
        float[] uv = {0.25f, 0.25f, 0.5f, 0.25f, 0.5f, 0.5f, 0.25f, 0.5f,   0.75f, 0.75f, 1f, 0.75f, 1f, 1f, 0.75f, 1f};
        float[] r = RtEmitters.extractQuads(pos, uv, 2, mat, 4, 4);
        check(r.length == RtEmitters.FLOATS, "one emitter: " + r.length);
        near(1f, r[3], 1e-5f, "area 1");
        near(1f, r[4], 1e-5f, "e1 = p1 - p0 (x)");
        near(1f, r[10], 1e-5f, "e2 = p3 - p0 (z)");
        near(1f, r[14], 1e-5f, "mean emission 1");
        check(RtEmitters.extractQuads(pos, uv, 0, mat, 4, 4).length == 0, "no quads, no emitters");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void near(float e, float a, float eps, String msg) {
        if (Math.abs(e - a) > eps) {
            throw new AssertionError(msg + ": expected " + e + " actual " + a);
        }
    }

    private static void labPbrDecode() {
        // LabPBR 1.3: roughness = (1 - smoothness)^2
        near(1f, LabPbr.roughnessFromSmoothness(0), 1e-6f, "smoothness 0 -> roughness 1");
        near(0f, LabPbr.roughnessFromSmoothness(255), 1e-6f, "smoothness 255 -> roughness 0");
        near(0.25f, LabPbr.roughnessFromSmoothness(128), 0.01f, "half smooth -> 0.25");
        check(!LabPbr.isMetal(229) && LabPbr.isMetal(230) && LabPbr.isMetal(237) && LabPbr.isMetal(255), "metal range 230..255");
        near(0f, LabPbr.emission(255), 0f, "alpha 255 = no emission");
        near(1f, LabPbr.emission(254), 1e-6f, "alpha 254 = full emission");
        near(0f, LabPbr.emission(0), 0f, "alpha 0 = no emission");
        int t = LabPbr.materialFromSpecular(255, 231, 0, 254);   // smooth gold, full emission
        check((t & 255) == 0 && ((t >>> 8) & 255) == LabPbr.METAL_G && ((t >>> 16) & 255) == 255 && (t >>> 24) == LabPbr.SOURCE_LABPBR,
            "specular texel -> material texel " + Integer.toHexString(t));
        int d = LabPbr.materialFromSpecular(0, 10, 0, 255);
        check((d & 255) == 255 && ((d >>> 8) & 255) == 10 && ((d >>> 16) & 255) == 0, "rough dielectric f0 10");
        float[] n = LabPbr.decodeNormal(128, 128);
        near(0f, n[0], 0.01f, "flat x"); near(0f, n[1], 0.01f, "flat y"); near(1f, n[2], 0.01f, "flat z");
        n = LabPbr.decodeNormal(128, 0);   // DirectX green 0 = up
        check(n[1] > 0.99f, "DirectX green 0 points up after the flip: " + n[1]);
        check(LabPbr.FLAT_NORMAL == (128 | 128 << 8 | 255 << 16 | 255 << 24), "flat normal packing");
    }

    private static void autoPbrTable() {
        AutoPbr.Material glow = AutoPbr.classify("block/glowstone");
        check(glow.emissionMode() == AutoPbr.Emission.WHOLE && glow.emission() == 1f, "glowstone emits");
        check(AutoPbr.classify("block/lava_still").emission() > 0.9f, "lava emits");
        check(AutoPbr.classify("block/torch").emissionMode() == AutoPbr.Emission.BRIGHT, "torch: bright pixels");
        check(AutoPbr.classify("block/iron_block").metal() && AutoPbr.classify("block/gold_block").metal(), "iron/gold metal");
        check(AutoPbr.classify("block/copper_block").metal() && !AutoPbr.classify("block/oxidized_copper").metal(), "copper metal, oxidized not");
        check(AutoPbr.classify("block/water_still").roughness() < 0.05f, "water smooth");
        check(AutoPbr.classify("block/glass").roughness() <= 0.10f, "glass smooth");
        check(AutoPbr.classify("block/stone") == AutoPbr.DEFAULT && AutoPbr.classify("block/dirt") == AutoPbr.DEFAULT, "stone/dirt default");
        check(AutoPbr.classify("minecraft:block/polished_andesite").roughness() < 0.6f, "polished");
        check(AutoPbr.classify("block/redstone_lamp_on").emission() > 0f && AutoPbr.classify("block/redstone_lamp").emission() == 0f,
            "lamp on emits, off not");
        check(AutoPbr.brightPixelEmission(255, 235, 80, 255) > 0.5f, "flame pixel emits");
        check(AutoPbr.brightPixelEmission(90, 70, 40, 255) == 0f, "dark wood pixel does not emit");
        check(AutoPbr.brightPixelEmission(255, 255, 255, 0) == 0f, "transparent pixel does not emit");
        check(AutoPbr.brightPixelEmission(255, 255, 255, 255) == 0f, "pure white eye pixel does not emit");
        check(AutoPbr.classify("entity/player/wide/steve") == AutoPbr.DEFAULT, "steve skin is default non-emissive");
        check(AutoPbr.classify("textures/entity/spider/spider_eyes.png").emissionMode() == AutoPbr.Emission.BRIGHT, "spider eyes emit");
        check(AutoPbr.classify("entity/chest/normal").emissionMode() == AutoPbr.Emission.NONE, "chest is non-emissive");
        check(AutoPbr.classify("textures/entity/chest/normal.png").emissionMode() == AutoPbr.Emission.NONE, "chest png is non-emissive");
        check(AutoPbr.mixedKind("entity/chest/normal") == AutoPbr.MixedKind.CHEST, "chest is MixedKind.CHEST");
        check(AutoPbr.isMetalTexel(0xFFC0C0C0, AutoPbr.MixedKind.CHEST), "chest latch is metal");
        check(!AutoPbr.isMetalTexel(0xFF8A5020, AutoPbr.MixedKind.CHEST), "chest wood is not metal");
        check(!AutoPbr.isEmissiveTexel(0xFFC0C0C0, AutoPbr.MixedKind.CHEST), "chest latch does not emit");
        check(AutoPbr.classify("block/carved_pumpkin").emissionMode() == AutoPbr.Emission.NONE, "carved pumpkin does not emit");
        check(AutoPbr.classify("block/jack_o_lantern").emissionMode() == AutoPbr.Emission.BRIGHT, "jack o lantern emits");
        check(AutoPbr.redstoneEmission(0) == 0f && AutoPbr.redstoneEmission(15) == 1f, "redstone emission formula");
        check(AutoPbr.endRodEmission(235, 235, 255, 255) == 1f && AutoPbr.endRodEmission(64, 64, 64, 255) == 0f, "end rod bulb vs base emission");
        check(AutoPbr.magmaEmission(255, 120, 0, 255) == 1f && AutoPbr.magmaEmission(44, 22, 8, 255) == 0f, "magma crack vs crust emission");
        check(AutoPbr.enchantingTableEmission(51, 221, 170, 255) > 0.2f && AutoPbr.enchantingTableEmission(21, 16, 32, 255) == 0f, "enchanting table diamond vs obsidian emission");
        check(io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.ItemPbr.classify("item/iron_sword").metal() == 1f, "iron sword is metal");
        check(io.github.andrexarlay.vulkancompat.rt.entity.EntityTextureAtlas.ItemPbr.classify("item/diamond_sword").roughness() == 0.05f, "diamond sword smooth");
    }

    private static void regionFill() {
        int atlasW = 8;
        int[] mat = new int[64];
        int[] nrm = new int[64];
        // 2x2 sprite at (4,2), auto glowstone: all four texels emissive
        int e = MaterialRegion.fill(mat, nrm, atlasW, 4, 2, 2, 2, AutoPbr.classify("block/glowstone"), null, null, null);
        check(e == 4, "4 emissive texels: " + e);
        check(((mat[2 * 8 + 4] >>> 16) & 255) == 255 && nrm[2 * 8 + 4] == LabPbr.FLAT_NORMAL, "texel (4,2) emission and flat normal");
        check(mat[0] == 0, "outside the region untouched");
        // LabPBR _s at twice the resolution: sampled nearest
        int[] spec = new int[16];
        for (int i = 0; i < 16; i++) {
            spec[i] = 0xFF << 24 | 255 << 16 | 0 << 8 | 0;   // a=255 (no emission), r=255 smooth, g=0, b=0
        }
        MaterialRegion.fill(mat, nrm, atlasW, 0, 0, 2, 2, AutoPbr.DEFAULT, null, new MaterialRegion.Image(4, 4, spec), null);
        check((mat[0] & 255) == 0 && (mat[0] >>> 24) == LabPbr.SOURCE_LABPBR, "LabPBR smooth texel -> roughness 0");
        // torch-like BRIGHT sprite: only the bright pixel emits
        int[] alb = {0xFF5A4628, 0xFFFFE050};
        int em = MaterialRegion.fill(mat, nrm, atlasW, 0, 4, 2, 1, AutoPbr.classify("block/torch"), new MaterialRegion.Image(2, 1, alb), null, null);
        check(em == 1, "one bright emissive texel: " + em);
        // glowstone-like WHOLE sprite: bright texels emit more than dark ones (relative mask with a 15% floor)
        int[] glow = {0xFF503010, 0xFFFFF0C0};
        int[] gm = new int[2];
        MaterialRegion.fill(gm, new int[2], 2, 0, 0, 2, 1, AutoPbr.classify("block/glowstone"), new MaterialRegion.Image(2, 1, glow), null, null);
        int darkE = (gm[0] >>> 16) & 255;
        int brightE = (gm[1] >>> 16) & 255;
        check(brightE == 255 && darkE > 0 && darkE < 80, "glowstone mask dark=" + darkE + " bright=" + brightE);
    }

    private static void paramsPacking() {
        RtFrameParams p = new RtFrameParams();
        p.mode = RtConfig.MODE_LIT;
        p.flags = RtFrameParams.FLAG_SHADOWS | RtFrameParams.FLAG_EMISSIVE | RtFrameParams.FLAG_MATERIALS;
        p.quality = 3;
        p.bounces = 5;
        p.spp = 2;
        p.rayDistance = 256;
        p.accumCount = 1000;
        ByteBuffer b = ByteBuffer.allocateDirect(RtFrameParams.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        p.write(b);
        check(b.getInt(108) == p.flags, "flags in keyColor.w");
        int p2 = b.getInt(124);
        check((p2 & 255) == 3 && ((p2 >>> 8) & 255) == 5 && ((p2 >>> 16) & 255) == 2 && ((p2 >>> 24) & 255) * 4 == 256, "params2 " + Integer.toHexString(p2));
        p.filterIter = 2;
        p.filterIters = 3;
        int filteredPathParams = p.params2();
        check((filteredPathParams & 0xFFFF) == ((p.quality & 0xFF) | ((p.bounces & 0xFF) << 8))
            && ((filteredPathParams >>> 16) & 15) == 2 && ((filteredPathParams >>> 20) & 15) == 3
            && ((filteredPathParams >>> 24) & 255) * 4 == 256, "path A-Trous iteration packing preserves mode and distance");
        p.filterIter = 1;
        p.filterIters = 2;
        p.rayQualities = 0x4321;
        int filteredRayParams = p.params2();
        check((filteredRayParams & 0xFFFF) == 0x4321 && ((filteredRayParams >>> 16) & 15) == 1
            && ((filteredRayParams >>> 20) & 15) == 2, "ray A-Trous iteration packing preserves ray qualities");
        p.filterIter = 0;
        p.filterIters = 0;
        p.rayQualities = -1;
        check((b.getInt(72) >>> 18) == 1000 && (b.getInt(72) & 255) == RtConfig.MODE_LIT, "accumCount in modeBits");
        p.accumCount = 1 << 20;
        check((p.modeBits() >>> 18) == RtFrameParams.MAX_ACCUM, "accumCount saturates");
        check(RtConfig.parseMode("gi") == RtConfig.MODE_GI && RtConfig.parseMode("bounces") == RtConfig.MODE_BOUNCES
            && RtConfig.parseMode("emission") == RtConfig.MODE_EMISSION && RtConfig.parseMode("material") == RtConfig.MODE_MATERIAL
            && RtConfig.parseMode("reflection") == RtConfig.MODE_REFLECTION, "new debug views");
        RtConfig.setDebugView("gi");
        check(RtConfig.effectiveMode() == RtConfig.MODE_GI && RtConfig.displayed(), "runtime debug override");
        RtConfig.setDebugView(null);
        check(RtConfig.effectiveMode() == RtConfig.MODE_LIT || RtConfig.DEBUG_MODE != RtConfig.MODE_NONE, "override cleared");
        boolean threw = false;
        try {
            RtConfig.setDebugView("bogus");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "unknown runtime debug view rejected");
    }

    private static void accumulation() {
        RtAccumulation a = new RtAccumulation();
        Matrix4f m = new Matrix4f().perspective(1f, 1.7f, 0.05f, 1000f).invert();
        float[] sun = {0.5f, 0.8f, 0f};
        check(a.next(m, 1, 2, 3, sun, false, 7, 100, 50, true) == 0 && a.lastResetReason().equals("first_frame"), "first frame resets");
        check(a.next(m, 1, 2, 3, sun, false, 7, 100, 50, true) == 1, "static: 1 earlier frame");
        check(a.next(m, 1, 2, 3, sun, false, 7, 100, 50, true) == 2, "static: 2");
        check(a.cap() == RtFrameParams.MAX_ACCUM, "static: unlimited history");
        check(a.next(m, 1.01, 2, 3, sun, false, 7, 100, 50, true) == 3 && a.cap() == RtAccumulation.MOVING_CAP, "move keeps the reprojected history, capped");
        check(a.next(m, 1.01, 2, 3, sun, false, 7, 100, 50, true) == 4 && a.cap() == RtFrameParams.MAX_ACCUM, "standing again: no cap");
        Matrix4f turned = new Matrix4f(m).rotateY(0.01f);
        check(a.next(turned, 1.01, 2, 3, sun, false, 7, 100, 50, true) > 0 && a.cap() == RtAccumulation.MOVING_CAP, "turn caps");
        check(a.next(turned, 1.01, 2, 3, sun, true, 7, 100, 50, true) > 0 && a.cap() == RtAccumulation.CHANGE_CAP, "nearby rebuild caps short");
        check(a.next(turned, 1.01, 2, 3, sun, false, 8, 100, 50, true) == 0 && a.lastResetReason().equals("settings"), "settings reset");
        check(a.next(turned, 1.01, 2, 3, new float[] {0.5f, 0.9f, 0f}, false, 8, 100, 50, true) == 1 && a.cap() == RtAccumulation.CHANGE_CAP, "sun move caps");
        check(a.next(turned, 1.01, 2, 3, new float[] {0.5f, 0.9f, 0f}, false, 8, 120, 50, true) == 0 && a.lastResetReason().equals("resize"), "resize resets");
        check(a.next(turned, 1.01, 2, 3, sun, false, 8, 100, 50, false) == 0, "disabled: always 0");
    }

    /**
     * The lines the owner reads must not contradict each other: a path-traced session printed {@code status mode=path} and
     * {@code rt_pass ... mode=lit} in the same second (owner log 2026-10-25), and {@code hist_pct=0.0} in a session that has
     * no accumulation at all.
     */
    private static void telemetry() {
        RtMode oldMode = VulkanRtxConfig.rtMode;
        boolean oldRt = VulkanRtxConfig.enableHardwareRayTracing;
        String oldDebug = RtConfig.currentDebugView();
        try {
            // the field directly, not setRtMode: the latter would also persist the mode and unlock the experimental ones
            VulkanRtxConfig.rtMode = RtMode.PATH_TRACED;
            check("path".equals(RtConfig.effectiveModeName()), "path session reports 'path', not 'lit'");
            RtConfig.setDebugView("normals");
            check("normals".equals(RtConfig.effectiveModeName()), "a debug view still wins over the mode name");
            RtConfig.setDebugView("");
            VulkanRtxConfig.rtMode = RtMode.AO;
            check("ao".equals(RtConfig.effectiveModeName()), "ao session keeps its name");
            VulkanRtxConfig.rtMode = RtMode.DIRECT;
            check("shadow".equals(RtConfig.effectiveModeName()), "direct session keeps its name");
            VulkanRtxConfig.rtMode = RtMode.RAY_TRACED;
            check("lit".equals(RtConfig.effectiveModeName()), "the ray mode (one lit pass) keeps its name");
        } finally {
            RtConfig.setDebugView(oldDebug);
            VulkanRtxConfig.rtMode = oldMode;
            VulkanRtxConfig.enableHardwareRayTracing = oldRt;
        }
        check(RtAccumulation.acceptedPct(0, 100, false) < 0.0, "no accumulation: history share is n/a, not 0%");
        check(RtAccumulation.acceptedPct(0, 0, true) < 0.0, "no counted hits: history share is n/a");
        check(Math.abs(RtAccumulation.acceptedPct(25, 200, true) - 12.5) < 1.0e-9, "accepted share of the hit pixels");
        Matrix4f m = new Matrix4f().perspective(1f, 1.7f, 0.05f, 1000f).invert();
        float[] sun = {0.5f, 0.8f, 0f};
        RtAccumulation t = new RtAccumulation();
        t.next(m, 0, 0, 0, sun, false, 7, 100, 50, true);      // first_frame
        t.next(m, 0.25, 0, 0, sun, false, 7, 100, 50, true);   // cap_moving
        check(t.lastDetail().startsWith("d=(0.2500,0.0000,0.0000)"), "camera delta formatted on demand: " + t.lastDetail());
        t.next(m, 0.25, 0, 0, sun, true, 7, 100, 50, true);    // cap_scene
        t.next(m, 0.25, 0, 0, sun, false, 9, 100, 50, true);   // settings
        check(t.lastDetail().isEmpty(), "a reset drops the camera delta of an older move");
        RtAccumulation turned = new RtAccumulation();
        turned.next(m, 0, 0, 0, sun, false, 7, 100, 50, true);
        turned.next(new Matrix4f(m).rotateY(0.01f), 0, 0, 0, sun, false, 7, 100, 50, true);
        check(turned.lastDetail().startsWith("d=(0.0000"), "a turn moves no metre but still reports the delta");
        String window = t.drainResetCounts();
        check(window.equals("cap_moving:1,cap_scene:1,first_frame:1,settings:1"),
            "per-window counters stay in one order: " + window);
        check(t.drainResetCounts().equals("none"), "draining starts the window over");
    }

    private static void settingsKeys() {
        int oldB = RtSettings.pathBounces;
        boolean oldRefl = RtSettings.raysReflections;
        try {
            check(RtSettings.applyKeyValue("path_bounces", "5") && RtSettings.pathBounces == 5, "path_bounces");
            check(RtSettings.applyKeyValue("path_bounces", "99") && RtSettings.pathBounces == 5, "out of range kept");
            check(RtSettings.applyKeyValue("rays_reflections", "true") && RtSettings.raysReflections, "rays_reflections");
            check(!RtSettings.applyKeyValue("not_a_key", "1"), "unknown key");
            check(RtSettings.bounces() == 5, "effective value without override");
            java.io.StringWriter w = new java.io.StringWriter();
            RtSettings.write(w);
            check(w.toString().contains("[path_tracing]") && w.toString().contains("path_bounces = 5"), "toml section written");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            RtSettings.pathBounces = oldB;
            RtSettings.raysReflections = oldRefl;
        }
        RtSettings.PathDenoiser oldDen = RtSettings.pathDenoiser;
        try {
            check(RtSettings.applyKeyValue("path_denoiser", "off") && RtSettings.pathDenoiser == RtSettings.PathDenoiser.OFF, "path_denoiser off");
            check(RtSettings.applyKeyValue("path_denoiser", "dlss_rr") && RtSettings.pathDenoiser == RtSettings.PathDenoiser.DLSS_RR, "path_denoiser dlss_rr");
            check(RtSettings.applyKeyValue("path_denoiser", "nrd") && RtSettings.pathDenoiser == RtSettings.PathDenoiser.NRD, "path_denoiser nrd");
        } finally {
            RtSettings.pathDenoiser = oldDen;
        }
        RtMode oldMode = VulkanRtxConfig.rtMode;
        boolean oldRt = VulkanRtxConfig.enableHardwareRayTracing;
        try {
            VulkanRtxConfig.applyKeyValue("rt_mode", "path");
            check(VulkanRtxConfig.rtMode == RtMode.PATH_TRACED && VulkanRtxConfig.enableHardwareRayTracing, "rt_mode=path");
            VulkanRtxConfig.applyKeyValue("enable_hardware_ray_tracing", "false");
            check(VulkanRtxConfig.rtMode == RtMode.PATH_TRACED, "legacy key ignored after rt_mode");
            VulkanRtxConfig.setRtMode(RtMode.OFF);
            VulkanRtxConfig.enableHardwareRayTracing = true;
            check(VulkanRtxConfig.effectiveRtMode() == RtMode.RAY_TRACED, "legacy boolean write means the ray mode");

            // Test F3 debug provider formatting for PATH_TRACED denoiser line
            io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.setForTests(true);
            try {
                VulkanRtxConfig.setRtMode(RtMode.PATH_TRACED);
                java.util.List<String> f3Lines = new java.util.ArrayList<>();
                RtModesDebug.appendLines(f3Lines);
                // the denoiser line moved to the Bedray F3 overview section (F3Sections, built from live runtime state, not unit-testable
                // headless); RtModesDebug keeps the RT-HERMETIC / material diagnostics, which must still be produced for PATH_TRACED
                check(f3Lines.size() >= 2, "F3 produces at least 2 diagnostics lines for PATH_TRACED");
                check(f3Lines.stream().anyMatch(l -> l.contains("[RT-HERMETIC]")),
                    "F3 diagnostics contain the RT-HERMETIC line: " + f3Lines);
            } finally {
                io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryGate.setForTests(false);
            }
        } finally {
            VulkanRtxConfig.rtMode = oldMode;
            VulkanRtxConfig.enableHardwareRayTracing = oldRt;
        }
    }

    private static void scenarioSteps() {
        var steps = RtModesScenario.parseSteps(RtModesScenario.DEFAULT_STEPS);
        check(steps.size() >= 5 && steps.get(0).mode() == RtMode.OFF, "default steps parse");
        var s = RtModesScenario.parseSteps("B:path+b2+s4+f500+dbg=gi").get(0);
        check(s.scene().equals("B") && s.mode() == RtMode.PATH_TRACED && s.mods().contains("b2") && s.mods().contains("dbg=gi"), "step mods");
        var dStep = RtModesScenario.parseSteps("D:rays+norefl+water=vanilla,A:path+pt=fast+den=nrd");
        check(dStep.size() == 2 && dStep.get(0).scene().equals("D") && dStep.get(0).mods().contains("norefl")
            && dStep.get(1).mods().contains("pt=fast") && dStep.get(1).mods().contains("den=nrd"), "scene D and pt/den/water/norefl modifiers parse");
        for (String bad : new String[] {"G:rays", "A:raster", "A:rays+bogus", "A:path+dbg=nope", ""}) {
            boolean threw = false;
            try {
                RtModesScenario.parseSteps(bad);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "bad step rejected: '" + bad + "'");
        }
    }

    private static void shadersCompile() {
        ByteBuffer rays = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp");
        ByteBuffer raysGb = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp+gbuffer", RtPipeline.GBUFFER_DEFINE);
        ByteBuffer path = RtShaderCompiler.compileResource(RtPipeline.PATH_SHADER_PATH, "rt_path.comp", RtPathTracerPass.ACCUM_DEFINE);
        ByteBuffer pathGb = RtShaderCompiler.compileResource(RtPipeline.PATH_SHADER_PATH, "rt_path.comp+gbuffer",
            RtPipeline.GBUFFER_DEFINE + RtPathTracerPass.ACCUM_DEFINE);
        // These two passes are compiled from their own files: naming rt_path.comp in the log line while compiling it made the
        // check pass for shaders that had never been compiled at all.
        ByteBuffer atrous = RtShaderCompiler.compileResource("/shaders/rt_atrous.comp", "rt_atrous.comp",
            RtRayEffectsPass.ACCUM_DEFINE);
        ByteBuffer restir = RtShaderCompiler.compileResource("/shaders/rt_restir_spatial.comp", "rt_restir_spatial.comp",
            RtRayEffectsPass.ACCUM_DEFINE);
        // The spatial pass runs under a denoiser too (it merges reservoir records, not pixels), so both binding layouts are
        // compiled here: a gbuffer-only layout error would otherwise only surface when a player enables DLSS-RR.
        ByteBuffer restirGb = RtShaderCompiler.compileResource("/shaders/rt_restir_spatial.comp", "rt_restir_spatial.comp+gbuffer",
            RtPipeline.GBUFFER_DEFINE + RtRayEffectsPass.ACCUM_DEFINE);
        // GPU dequantization of the Sodium mesh capture: standalone (no umbrella include), pure buffer_reference + BDA.
        ByteBuffer dequant = RtShaderCompiler.compileResource("/shaders/sodium_dequantize.comp", "sodium_dequantize.comp");
        for (ByteBuffer b : new ByteBuffer[] {rays, raysGb, path, pathGb, atrous, restir, restirGb, dequant}) {
            check(b.remaining() > 1000 && b.getInt(0) == 0x07230203, "SPIR-V magic / size " + b.remaining());
        }
    }
}
