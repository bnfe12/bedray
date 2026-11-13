package io.github.andrexarlay.vulkancompat.rt.material.bedrock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bedrock RTX texture set (colour + MERS + heightmap) to the LabPBR {@code _s} / {@code _n} pair Bedray already reads.
 * Pure and unit-tested; {@link BedrockRtxPackImporter} does the file and game work.
 *
 * <ul>
 *   <li>MERS: R metalness, G emissive, B perceptual roughness, A subsurface.</li>
 *   <li>{@code _s}: R = 1 - roughness (perceptual smoothness, decoded by {@code LabPbr.roughnessFromSmoothness}),
 *       G = 255 (albedo metal) at metalness 0.9+, else linear F0 = lerp(0.04, albedo luminance, metalness) capped at 229,
 *       B = 65 + 190 * subsurface where there is any, A = emissive * 254 or 255 (none). An emissive texel is no metal.</li>
 *   <li>{@code _n}: DirectX tangent normal (green = -Y) from central differences of the heightmap, per animation
 *       frame, height in alpha.</li>
 * </ul>
 * Water is never converted: Bedray traces it with its own analytic surface and medium, and a pack material hides the
 * fluid from that model (black pools, no waves - autotest rt_look P/W, 2026-11-11).
 */
public final class BedrockMaterialConverter {
    public static final float DEFAULT_NORMAL_STRENGTH = 1.0f;
    /**
     * Bedrock emissive values are authored for the Bedrock renderer's own emissive multiplier: Vanilla RTX lights a torch
     * flame at 0.25 and a glowstone at 0.13 on average, and both read as bright there. Taken 1:1 the flame of an imported
     * torch rendered dull brown in Bedray (autotest rt_look I, 2026-11-11), so the value is scaled into LabPBR's range.
     */
    public static final float EMISSIVE_SCALE = 3.0f;

    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final Map<String, String> LEGACY = Map.ofEntries(
        Map.entry("stonebrick", "stone_bricks"), Map.entry("stonebrick_mossy", "mossy_stone_bricks"),
        Map.entry("stonebrick_cracked", "cracked_stone_bricks"), Map.entry("stonebrick_carved", "chiseled_stone_bricks"),
        Map.entry("brick", "bricks"), Map.entry("grass_top", "grass_block_top"), Map.entry("grass_side_carried", "grass_block_side"),
        Map.entry("grass_side", "grass_block_side"), Map.entry("grass_side_snowed", "grass_block_snow"),
        Map.entry("dirt_podzol_top", "podzol_top"), Map.entry("dirt_podzol_side", "podzol_side"), Map.entry("torch_on", "torch"),
        Map.entry("redstone_torch_on", "redstone_torch"), Map.entry("redstone_lamp_off", "redstone_lamp"),
        Map.entry("stone_slab_top", "smooth_stone"), Map.entry("stone_slab_side", "smooth_stone_slab_side"),
        Map.entry("quartz_block_lines", "quartz_pillar"), Map.entry("quartz_block_lines_top", "quartz_pillar_top"),
        Map.entry("quartz_block_chiseled", "chiseled_quartz_block"), Map.entry("sandstone_normal", "sandstone"),
        Map.entry("sandstone_smooth", "cut_sandstone"), Map.entry("sandstone_carved", "chiseled_sandstone"),
        Map.entry("red_sandstone_normal", "red_sandstone"), Map.entry("red_sandstone_smooth", "cut_red_sandstone"),
        Map.entry("red_sandstone_carved", "chiseled_red_sandstone"), Map.entry("cobblestone_mossy", "mossy_cobblestone"),
        Map.entry("stone_andesite", "andesite"), Map.entry("stone_andesite_smooth", "polished_andesite"),
        Map.entry("stone_diorite", "diorite"), Map.entry("stone_diorite_smooth", "polished_diorite"),
        Map.entry("stone_granite", "granite"), Map.entry("stone_granite_smooth", "polished_granite"),
        Map.entry("nether_brick", "nether_bricks"), Map.entry("red_nether_brick", "red_nether_bricks"),
        Map.entry("end_bricks", "end_stone_bricks"), Map.entry("hardened_clay", "terracotta"),
        Map.entry("furnace_front_off", "furnace_front"), Map.entry("pumpkin_face_on", "jack_o_lantern"),
        Map.entry("ice_packed", "packed_ice"), Map.entry("sponge_wet", "wet_sponge"),
        Map.entry("mushroom_block_skin_red", "red_mushroom_block"), Map.entry("mushroom_block_skin_brown", "brown_mushroom_block"),
        Map.entry("mushroom_block_skin_stem", "mushroom_stem"), Map.entry("rail_normal", "rail"), Map.entry("trapdoor", "oak_trapdoor"),
        Map.entry("door_wood_lower", "oak_door_bottom"), Map.entry("door_wood_upper", "oak_door_top"),
        Map.entry("door_iron_lower", "iron_door_bottom"), Map.entry("door_iron_upper", "iron_door_top"),
        Map.entry("anvil_base", "anvil"), Map.entry("anvil_top_damaged_0", "anvil_top"), Map.entry("reeds", "sugar_cane"),
        Map.entry("deadbush", "dead_bush"), Map.entry("tallgrass", "short_grass"), Map.entry("waterlily", "lily_pad"),
        Map.entry("web", "cobweb"), Map.entry("mob_spawner", "spawner"), Map.entry("noteblock", "note_block"),
        Map.entry("slime", "slime_block"), Map.entry("prismarine_rough", "prismarine"), Map.entry("prismarine_dark", "dark_prismarine"),
        Map.entry("dried_kelp_side_a", "dried_kelp_side"), Map.entry("honeycomb", "honeycomb_block"),
        Map.entry("campfire", "campfire_fire"), Map.entry("chain1", "chain"), Map.entry("iron_chain1", "iron_chain"));
    private static final Pattern COLORED = Pattern.compile(
        "^(wool_colored|concrete_powder|concrete|glass|glass_pane_top|hardened_clay_stained|glazed_terracotta)_(\\w+)$");
    private static final Pattern WOOD = Pattern.compile(
        "^(log|planks|leaves|leaves_opaque|sapling|door|trapdoor)_(\\w+?)(_top|_lower|_upper)?$");

    private BedrockMaterialConverter() {
    }

    public static boolean isWater(String bedrockName) {
        return bedrockName.startsWith("water_") || bedrockName.startsWith("flowing_water") || bedrockName.startsWith("still_water");
    }

    /** Java block texture names a Bedrock texture may stand for, most likely first. */
    public static List<String> javaCandidates(String name) {
        Set<String> out = new LinkedHashSet<>();
        out.add(name);
        String legacy = LEGACY.get(name);
        if (legacy != null) out.add(legacy);
        Matcher m = COLORED.matcher(name);
        if (m.matches()) {
            String c = m.group(2).equals("silver") ? "light_gray" : m.group(2);
            out.add(switch (m.group(1)) {
                case "wool_colored" -> c + "_wool";
                case "concrete_powder" -> c + "_concrete_powder";
                case "concrete" -> c + "_concrete";
                case "glass" -> c + "_stained_glass";
                case "glass_pane_top" -> c + "_stained_glass_pane_top";
                case "hardened_clay_stained" -> c + "_terracotta";
                default -> c + "_glazed_terracotta";
            });
        }
        m = WOOD.matcher(name);
        if (m.matches()) {
            String wood = m.group(2).equals("big_oak") ? "dark_oak" : m.group(2);
            String suffix = m.group(3) == null ? "" : m.group(3);
            switch (m.group(1)) {
                case "log" -> out.add(wood + "_log" + (suffix.equals("_top") ? "_top" : ""));
                case "planks" -> out.add(wood + "_planks");
                case "leaves", "leaves_opaque" -> out.add(wood + "_leaves");
                case "sapling" -> out.add(wood + "_sapling");
                case "door" -> out.add(wood + "_door_" + (suffix.equals("_lower") ? "bottom" : "top"));
                default -> out.add(wood + "_trapdoor");
            }
        }
        if (name.contains("silver")) out.add(name.replace("silver", "light_gray"));
        if (name.endsWith("_on")) out.add(name.substring(0, name.length() - 3));
        return new ArrayList<>(out);
    }

    /**
     * A Bedrock map resampled to the Java texture's size: identical sizes pass through, an animation strip whose frame count
     * differs takes the Bedrock first frame on every Java frame, nearest-neighbour throughout (pixel art).
     */
    public static int[] toJavaLayout(int[] argb, int w, int h, int jw, int jh) {
        if (w == jw && h == jh) return argb;
        int frameH = h > w && h % w == 0 ? w : h;
        int[] out = new int[jw * jh];
        int jFrame = jh > jw && jh % jw == 0 ? jw : jh;
        for (int y = 0; y < jh; y++) {
            int sy = Math.min(frameH - 1, (y % jFrame) * frameH / jFrame);
            for (int x = 0; x < jw; x++) {
                int sx = Math.min(w - 1, x * w / jw);
                out[y * jw + x] = argb[sy * w + sx];
            }
        }
        return out;
    }

    /** LabPBR {@code _s} texels (ARGB ints, A in the top byte) from MERS and the Java albedo of the same layout. */
    public static int[] specular(int[] mers, int[] albedo) {
        int[] out = new int[mers.length];
        for (int i = 0; i < mers.length; i++) {
            int m = mers[i];
            float metal = ((m >>> 16) & 255) / 255f;
            int emissive = (m >>> 8) & 255;
            float rough = (m & 255) / 255f;
            int sss = (m >>> 24) & 255;
            if (emissive > 0) metal = 0f;
            int c = albedo[i];
            float lum = (0.2126f * ((c >>> 16) & 255) + 0.7152f * ((c >>> 8) & 255) + 0.0722f * (c & 255)) / 255f;
            int g = metal >= 0.9f ? 255 : Math.min(229, Math.round(Math.max(0f, Math.min(1f, 0.04f + (lum - 0.04f) * metal)) * 255f));
            int r = Math.round((1f - rough) * 255f);
            int b = sss > 0 ? Math.round(65f + 190f * sss / 255f) : 0;
            int a = emissive > 0 ? Math.max(1, Math.round(Math.min(1f, emissive / 255f * EMISSIVE_SCALE) * 254f)) : 255;
            out[i] = a << 24 | r << 16 | g << 8 | b;
        }
        return out;
    }

    /** LabPBR {@code _n} texels (DirectX green) from the red channel of a heightmap, frame by frame. */
    public static int[] normals(int[] height, int w, int h, float strength) {
        int[] out = new int[w * h];
        int frame = h > w && h % w == 0 ? w : h;
        for (int y = 0; y < h; y++) {
            int y0 = (y / frame) * frame;
            int yu = y0 + Math.floorMod(y - y0 - 1, frame);
            int yd = y0 + Math.floorMod(y - y0 + 1, frame);
            for (int x = 0; x < w; x++) {
                float hl = red(height[y * w + Math.floorMod(x - 1, w)]);
                float hr = red(height[y * w + Math.floorMod(x + 1, w)]);
                float hu = red(height[yu * w + x]);
                float hd = red(height[yd * w + x]);
                float nx = -(hr - hl) * 0.5f * strength;
                float nyUp = (hd - hu) * 0.5f * strength;   // image rows grow downwards
                float inv = (float) (1.0 / Math.sqrt(nx * nx + nyUp * nyUp + 1.0));
                int r = Math.round((nx * inv * 0.5f + 0.5f) * 255f);
                int g = Math.round((-nyUp * inv * 0.5f + 0.5f) * 255f);   // DirectX: green stores -Y
                int a = Math.round(red(height[y * w + x]) * 255f);
                out[y * w + x] = a << 24 | r << 16 | g << 8 | 255;
            }
        }
        return out;
    }

    private static float red(int argb) {
        return ((argb >>> 16) & 255) / 255f;
    }
}
