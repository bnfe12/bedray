package io.github.andrexarlay.vulkancompat.rt.material;

import java.util.Locale;

/**
 * Automatic PBR values for vanilla block textures that have no LabPBR {@code _s} map ("auto-PBR"). Pure Java, unit-tested.
 * The rules match the sprite path (for example {@code block/glowstone}) in this order: emissive, metal, smooth dielectric,
 * default. Values are a hand-made table, not measured data.
 * <ul>
 *   <li>{@link Emission#WHOLE}: the whole sprite glows (glowstone, sea lantern, lava, froglights, ...);</li>
 *   <li>{@link Emission#BRIGHT}: only the bright, saturated pixels glow (torch flames, lantern glass, furnace fire, ...),
 *       decided per pixel by {@link #brightPixelEmission};</li>
 *   <li>metal: metalness 1, the albedo is the specular colour;</li>
 *   <li>dielectric: reflectance f0 = 0.04 (water / ice / glass 0.02-0.05), roughness from the table.</li>
 * </ul>
 */
public final class AutoPbr {
    public enum Emission { NONE, WHOLE, BRIGHT }

    /** Material of one sprite: linear roughness 0..1, f0 0..1 (dielectric reflectance), metal flag, emission strength 0..1. */
    public record Material(float roughness, float f0, boolean metal, float emission, Emission emissionMode, String rule) {
    }

    public static final Material DEFAULT = new Material(0.9f, 0.04f, false, 0f, Emission.NONE, "default");

    private AutoPbr() {
    }

    /** {@code spritePath}: path of the sprite identifier, e.g. {@code block/glowstone} (namespace is ignored). */
    public static Material classify(String spritePath) {
        String p = spritePath.toLowerCase(Locale.ROOT);
        int namespaceSeparator = p.indexOf(':');
        if (namespaceSeparator >= 0) {
            p = p.substring(namespaceSeparator + 1);
        }
        int slash = p.lastIndexOf('/');
        String n = slash >= 0 ? p.substring(slash + 1) : p;

        // Luminous particle sprites (flames, lava, sparks, glow, portal, sculk, firework glitter, magic/trial/vault effects)
        // emit light into the scene, while smoke/dust/drip/bubble particles stay non-emissive.
        if (p.startsWith("particle/")) {
            if (n.equals("flame") || n.equals("soul_fire_flame") || n.equals("small_flame")
                || n.equals("copper_fire_flame") || n.equals("lava") || n.equals("flash")
                || n.equals("glow") || n.equals("end_rod") || n.equals("totem_of_undying")
                || n.equals("sculk_soul") || n.startsWith("sculk_charge") || n.equals("portal")
                || n.equals("reverse_portal") || n.equals("enchanted_hit") || n.equals("electric_spark")
                || n.startsWith("spark_") || n.startsWith("glitter_") || n.startsWith("spell_")
                || n.startsWith("instant_effect_") || n.startsWith("trial_") || n.startsWith("vault_")
                || n.equals("raid_omen") || n.equals("ominous_spawning") || n.equals("sonic_boom")) {
                return new Material(0.6f, 0.04f, false, 1.0f, Emission.BRIGHT, RULE_FLAME);
            }
            return DEFAULT;
        }

        // ---- chests ----
        if (p.contains("ender_chest") || (p.contains("chest") && p.contains("ender"))) {
            return new Material(0.15f, 0.05f, false, 0f, Emission.NONE, "smooth_dielectric");
        }
        if (p.contains("chest")) {
            return new Material(0.85f, 0.04f, false, 0f, Emission.NONE, "chest");
        }
        // ---- pumpkins ----
        if (n.equals("carved_pumpkin") || (p.contains("pumpkin") && !n.equals("jack_o_lantern"))) {
            return new Material(0.85f, 0.04f, false, 0f, Emission.NONE, "pumpkin");
        }

        // ---- entity, player skins, and armor: never emit by brightness ----
        if (p.contains("entity") || p.contains("skin") || p.contains("player") || p.contains("armor")
            || n.contains("steve") || n.contains("alex")) {
            if (n.equals("end_portal")) {
                return new Material(0.05f, 0.04f, false, 1.0f, Emission.WHOLE, "end_portal");
            }
            if (p.contains("trim") || n.contains("trim")) {
                if (n.contains("redstone")) {
                    return new Material(0.4f, 0.04f, false, 0.85f, Emission.BRIGHT, "trim_redstone_glow");
                }
                if (n.contains("amethyst")) {
                    return new Material(0.2f, 0.04f, false, 0.45f, Emission.BRIGHT, "trim_amethyst_glow");
                }
                if (n.contains("diamond")) {
                    return new Material(0.1f, 0.04f, false, 0.15f, Emission.NONE, "trim_diamond");
                }
            }
            if (n.contains("spider_eyes") || n.contains("cave_spider") || n.contains("enderman_eyes")
                || n.contains("glow_squid") || n.contains("blaze") || n.contains("magma_cube")
                || n.contains("phantom_eyes") || n.contains("allay") || n.contains("vex")
                || n.contains("warden_bioluminescent") || n.contains("breeze_eyes") || n.contains("creaking_eyes")) {
                return new Material(0.6f, 0.04f, false, 1.0f, Emission.BRIGHT, "glowing_mob_feature");
            }
            return DEFAULT;
        }

        // ---- mixed blocks: metal frame / chains + emissive pixels or dielectric glass ----
        if (n.startsWith("redstone_lamp_on")) {
            return new Material(0.35f, 0.04f, true, 1.0f, Emission.BRIGHT, "mixed_redstone_lamp");
        }
        if (n.equals("redstone_lamp")) {
            return new Material(0.35f, 0.04f, true, 0f, Emission.NONE, "mixed_redstone_lamp");
        }
        if (n.startsWith("copper_bulb_lit") || n.matches("(exposed|weathered|oxidized)_copper_bulb_lit.*")) {
            return new Material(hasCopperPatina(n) ? 0.62f : 0.35f, 0.04f, !hasCopperPatina(n), 1.0f, Emission.BRIGHT, "mixed_copper_bulb");
        }
        if (n.equals("copper_bulb") || n.matches("(exposed|weathered|oxidized)_copper_bulb.*")) {
            return new Material(hasCopperPatina(n) ? 0.62f : 0.35f, 0.04f, !hasCopperPatina(n), 0f, Emission.NONE, "mixed_copper_bulb");
        }
        if (n.equals("lantern")) {
            return new Material(0.35f, 0.04f, true, 1.0f, Emission.BRIGHT, "mixed_lantern");
        }
        // copper lanterns (all oxidation steps, waxed ones share the textures): copper casing, green flame
        if (n.matches("(exposed_|weathered_|oxidized_)?copper_lantern")) {
            return new Material(hasCopperPatina(n) ? 0.62f : 0.35f, 0.04f, !hasCopperPatina(n), 1.0f, Emission.BRIGHT, "mixed_copper_lantern");
        }
        // A small cap proxy lights the raster flame; generated atlases select its model UVs, never the wax sides.
        if (n.equals("candle_lit") || n.endsWith("_candle_lit")) {
            return new Material(0.7f, 0.04f, false, 1.0f, Emission.BRIGHT, RULE_CANDLE);
        }
        if (n.equals("copper_torch")) {
            return new Material(0.6f, 0.04f, false, 1.0f, Emission.BRIGHT, RULE_FLAME);
        }
        if (n.matches("respawn_anchor_side[1-4]")) {
            return new Material(0.6f, 0.04f, false, 0.6f, Emission.BRIGHT, RULE_ANCHOR);
        }
        if (n.equals("campfire_log_lit") || n.equals("soul_campfire_log_lit") || n.equals("firefly_bush_emissive")) {
            return new Material(0.7f, 0.04f, false, n.startsWith("firefly") ? 1.0f : 0.6f, Emission.BRIGHT, "emissive_bright");
        }
        if (n.equals("soul_lantern")) {
            return new Material(0.35f, 0.04f, true, 1.0f, Emission.BRIGHT, "mixed_soul_lantern");
        }

        // Nether growth is much darker than a flame. Select the coloured veins/spores instead of applying the
        // generic bright-pixel threshold, which misses every vanilla stem texel. Sawn wood never uses these rules.
        if (n.equals("crimson_stem") || n.equals("crimson_stem_top")) {
            return new Material(0.87f, 0.04f, false, 0.45f, Emission.BRIGHT, RULE_CRIMSON_VEINS);
        }
        if (n.equals("warped_stem") || n.equals("warped_stem_top")) {
            return new Material(0.87f, 0.04f, false, 0.45f, Emission.BRIGHT, RULE_WARPED_VEINS);
        }
        if (n.equals("weeping_vines") || n.equals("weeping_vines_plant")) {
            return new Material(0.9f, 0.04f, false, 0.4f, Emission.BRIGHT, RULE_CRIMSON_VEINS);
        }
        if (n.equals("crimson_roots") || n.equals("crimson_roots_pot")) {
            return new Material(0.9f, 0.04f, false, 0.25f, Emission.BRIGHT, RULE_CRIMSON_VEINS);
        }
        if (n.equals("twisting_vines") || n.equals("twisting_vines_plant")
            || n.equals("warped_roots") || n.equals("warped_roots_pot") || n.equals("nether_sprouts")) {
            return new Material(0.9f, 0.04f, false, 0.25f, Emission.BRIGHT, RULE_WARPED_VEINS);
        }
        if (n.equals("crimson_fungus") || n.equals("warped_fungus")) {
            return new Material(0.82f, 0.04f, false, 0.65f, Emission.BRIGHT, RULE_FUNGUS_SPORES);
        }
        if (n.equals("nether_wart_block") || n.equals("warped_wart_block")) {
            return new Material(0.95f, 0.04f, false, 0.5f, Emission.BRIGHT,
                n.equals("nether_wart_block") ? RULE_CRIMSON_SPORES : RULE_WARPED_SPORES);
        }
        if (n.equals("chorus_flower")) {
            return new Material(0.72f, 0.04f, false, 0.55f, Emission.BRIGHT, RULE_CHORUS_FLOWER);
        }
        if (n.equals("crying_obsidian")) {
            return new Material(0.22f, 0.05f, false, 0.65f, Emission.BRIGHT, RULE_PURPLE_VEINS);
        }
        if (n.equals("respawn_anchor_top_off")) {
            return new Material(0.6f, 0.04f, false, 0f, Emission.NONE, "uncharged_anchor");
        }
        if (n.equals("amethyst_cluster") || n.endsWith("_amethyst_bud") || n.equals("calibrated_sculk_sensor_amethyst")) {
            return new Material(0.24f, 0.05f, false, 0.35f, Emission.BRIGHT, RULE_CRYSTAL);
        }
        if (n.equals("sculk") || n.equals("sculk_vein") || n.startsWith("sculk_catalyst_")
            || n.startsWith("sculk_sensor_") || n.startsWith("sculk_shrieker_")) {
            float strength = n.contains("bloom") || n.endsWith("tendril_active") || n.contains("can_summon") ? 0.4f : 0.18f;
            return new Material(0.86f, 0.04f, false, strength, Emission.BRIGHT, RULE_SCULK);
        }
        if (n.equals("creaking_heart_awake") || n.equals("creaking_heart_top_awake")
            || (n.startsWith("trial_spawner_") && (n.contains("_active") || n.contains("ejecting_reward")))
            || (n.startsWith("vault_") && (n.contains("_on") || n.contains("ejecting")))) {
            return new Material(0.72f, 0.04f, false, 0.55f, Emission.BRIGHT, RULE_MAGIC_CORE);
        }

        // ---- emissive, whole sprite ----
        if (n.equals("glowstone") || n.equals("sea_lantern") || n.equals("beacon")
            || n.endsWith("froglight_top") || n.endsWith("froglight_side")
            || n.equals("fire_0") || n.equals("fire_1") || n.equals("soul_fire_0") || n.equals("soul_fire_1")) {
            return new Material(0.6f, 0.04f, false, 1.0f, Emission.WHOLE, "emissive_whole");
        }
        if (n.startsWith("lava_")) {
            return new Material(0.35f, 0.04f, false, 1.0f, Emission.WHOLE, "lava");
        }
        if (n.equals("shroomlight")) {
            return new Material(0.7f, 0.04f, false, 0.85f, Emission.WHOLE, "emissive_whole");
        }
        if (n.equals("nether_portal")) {
            return new Material(0.4f, 0.04f, false, 0.75f, Emission.WHOLE, "nether_portal");
        }
        if (n.equals("end_portal") || n.equals("end_gateway")) {
            return new Material(0.05f, 0.04f, false, 1.0f, Emission.WHOLE, "end_portal");
        }
        if (n.equals("end_portal_frame_eye") || n.equals("ender_eye")) {
            return new Material(0.15f, 0.08f, false, 0.90f, Emission.BRIGHT, RULE_END_EYE);
        }
        if (n.equals("glow_item_frame")) {
            return new Material(0.55f, 0.04f, false, 0.65f, Emission.WHOLE, "emissive_whole");
        }
        // ---- emissive flames: every bright pixel of the flame glows in its own colour (torch tip, campfire fire) ----
        if (n.equals("torch") || n.equals("soul_torch") || n.equals("redstone_torch")
            || n.startsWith("campfire_fire") || n.startsWith("soul_campfire_fire")) {
            return new Material(0.6f, 0.04f, false, 1.0f, Emission.BRIGHT, RULE_FLAME);
        }
        // ---- redstone wire / dust: emission proportional to signal power 0..15, strictly 0 for power 0 ----
        if (n.startsWith("redstone_dust") || n.equals("redstone_wire")) {
            return new Material(0.9f, 0.04f, false, 1.0f, Emission.BRIGHT, RULE_REDSTONE);
        }
        // ---- end rod: glowing smooth bulb (emission 1.0, roughness ~0.1), dark non-emissive base ----
        if (n.equals("end_rod")) {
            return new Material(0.1f, 0.04f, false, 1.0f, Emission.BRIGHT, RULE_END_ROD);
        }
        // ---- magma block: glowing lava cracks (emission 1.0, roughness 0.15), dark crust (roughness 0.85) ----
        if (n.equals("magma") || n.equals("magma_block")) {
            return new Material(0.85f, 0.04f, false, 1.0f, Emission.BRIGHT, RULE_MAGMA);
        }
        // ---- enchanting table: diamond cyan corners glow (~0.25 flux, roughness 0.08, f0 0.17) ----
        if (n.startsWith("enchanting_table")) {
            return new Material(0.85f, 0.04f, false, 0.25f, Emission.BRIGHT, RULE_ENCHANTING_TABLE);
        }
        // ---- emissive, bright pixels only ----
        if (n.equals("glow_lichen")) {
            return new Material(0.90f, 0.04f, false, 0.35f, Emission.WHOLE, "lichen_glow");
        }
        if (n.equals("open_eyeblossom_emissive")) {
            return new Material(0.90f, 0.04f, false, 0.65f, Emission.WHOLE, "eyeblossom_eyes");
        }
        if (n.equals("jack_o_lantern")) {
            return new Material(0.85f, 0.04f, false, 1f, Emission.BRIGHT, "pumpkin_windows");
        }
        if (n.equals("torch") || n.equals("soul_torch") || n.equals("redstone_torch")
            || n.equals("jack_o_lantern")
            || n.equals("glow_lichen") || n.equals("crying_obsidian") || n.equals("respawn_anchor_top")
            || n.startsWith("campfire_fire") || n.startsWith("soul_campfire_fire") || n.endsWith("_front_on")
            || n.equals("cave_vines_lit") || n.equals("cave_vines_plant_lit") || n.equals("amethyst_cluster")
            || n.equals("sea_pickle") || n.startsWith("candle_lit")
            // item sprites (hand / dropped items, EntityTextureAtlas): the same per-pixel rule
            || n.equals("lava_bucket") || n.equals("fire_charge") || n.equals("glowstone_dust") || n.equals("magma_cream")
            || n.equals("blaze_powder") || n.equals("blaze_rod") || n.equals("glow_ink_sac") || n.equals("nether_star")) {
            float strength = n.equals("glow_lichen") || n.equals("crying_obsidian") || n.equals("amethyst_cluster") ? 0.5f : 1.0f;
            return new Material(0.6f, 0.04f, false, strength, Emission.BRIGHT, "emissive_bright");
        }
        // ---- metals ----
        if (n.equals("iron_block") || n.equals("iron_bars") || n.startsWith("iron_door") || n.startsWith("iron_trapdoor")
            || n.equals("chain") || n.equals("iron_chain") || n.startsWith("anvil") || n.startsWith("chipped_anvil")
            || n.startsWith("damaged_anvil") || n.startsWith("cauldron") || n.startsWith("hopper") || n.equals("heavy_core")) {
            return new Material(0.35f, 0.04f, true, 0f, Emission.NONE, "metal_iron");
        }
        if (n.equals("gold_block")) {
            return new Material(0.25f, 0.04f, true, 0f, Emission.NONE, "metal_gold");
        }
        if (n.equals("netherite_block")) {
            return new Material(0.4f, 0.04f, true, 0f, Emission.NONE, "metal_netherite");
        }
        if (n.equals("bell") || n.startsWith("bell_")) {
            return new Material(0.30f, 0.04f, true, 0f, Emission.NONE, "metal_bell");
        }
        if (isRail(n) || n.equals("tripwire_hook") || n.equals("stonecutter_saw")) {
            return new Material(0.82f, 0.04f, false, 0f, Emission.NONE, "mixed_hardware");
        }
        if (n.equals("lightning_rod") || n.equals("lightning_rod_on")) {
            return new Material(0.35f, 0.04f, true, 0f, Emission.NONE, "metal_copper");
        }
        if (hasCopperPatina(n) && n.contains("lightning_rod")) {
            return new Material(0.62f, 0.04f, false, 0f, Emission.NONE, "copper_patina");
        }
        if (n.equals("netherrack") || n.equals("soul_sand") || n.equals("soul_soil")
            || n.equals("end_stone") || n.equals("end_stone_bricks") || n.endsWith("nylium") || n.endsWith("nylium_side")) {
            return new Material(0.96f, 0.04f, false, 0f, Emission.NONE, "porous_stone");
        }
        if (n.equals("gilded_blackstone") || n.equals("ancient_debris_side") || n.equals("ancient_debris_top")) {
            return new Material(0.82f, 0.04f, false, 0f, Emission.NONE, "mineral_stone");
        }
        if (n.contains("blackstone") || n.contains("basalt")) {
            return new Material(n.startsWith("polished_") || n.startsWith("chiseled_polished_") ? 0.48f : 0.86f,
                0.04f, false, 0f, Emission.NONE, "volcanic_stone");
        }
        if (n.equals("copper_block") || n.startsWith("cut_copper") || n.equals("chiseled_copper") || n.startsWith("copper_grate")
            || n.startsWith("copper_door") || n.startsWith("copper_trapdoor")) {
            return new Material(0.3f, 0.04f, true, 0f, Emission.NONE, "metal_copper");
        }
        if ((n.startsWith("exposed_") || n.startsWith("weathered_") || n.startsWith("oxidized_")) && n.contains("copper")) {
            return new Material(0.62f, 0.04f, false, 0f, Emission.NONE, "copper_patina");
        }
        if (n.equals("copper_bars") || n.equals("copper_chain")) {
            return new Material(0.35f, 0.04f, true, 0f, Emission.NONE, "metal_copper");
        }
        // ---- smooth dielectrics ----
        if (n.startsWith("water_")) {
            return new Material(0.02f, 0.02f, false, 0f, Emission.NONE, RULE_WATER);
        }
        if (n.equals("ice") || n.startsWith("frosted_ice")) {
            return new Material(0.05f, 0.02f, false, 0f, Emission.NONE, RULE_ICE);
        }
        // blue ice and packed ice are OPAQUE blocks in vanilla (only plain / frosted ice is translucent): a smooth, bluish dielectric
        // that the shader must not take for a glass-like ice sheet (f0 0.04 keeps it out of the ice test of fetchSurface)
        if (n.equals("packed_ice")) {
            return new Material(0.15f, 0.04f, false, 0f, Emission.NONE, RULE_ICE_OPAQUE);
        }
        if (n.equals("blue_ice")) {
            return new Material(0.08f, 0.04f, false, 0f, Emission.NONE, RULE_ICE_OPAQUE);
        }
        // Vanilla tinted_glass (amethyst glass) is see-through to the camera but blocks ALL light, unlike clear and
        // stained glass which transmit (tinted) light. Its own rule lets the baker tag it as a light blocker for the
        // visibility walk while the camera path keeps it a translucent pane.
        if (n.equals("tinted_glass")) {
            return new Material(0.02f, 0.04f, false, 0f, Emission.NONE, RULE_TINTED_GLASS);
        }
        if (n.equals("glass") || n.endsWith("_stained_glass") || n.startsWith("glass_pane")
            || n.endsWith("_stained_glass_pane_top")) {
            return new Material(0.02f, 0.04f, false, 0f, Emission.NONE, RULE_GLASS);
        }
        if (n.equals("diamond_block") || n.equals("emerald_block") || n.equals("amethyst_block") || n.equals("obsidian")
            || n.equals("honey_block_side") || n.equals("slime_block") || n.endsWith("_glazed_terracotta")) {
            return new Material(0.15f, 0.05f, false, 0f, Emission.NONE, "smooth_dielectric");
        }
        // Texture families keep their own surface character when no resource pack provides material maps.
        if (n.endsWith("_wool") || n.endsWith("_concrete_powder") || n.equals("sand") || n.equals("red_sand")
            || n.equals("gravel") || n.endsWith("_sponge") || n.equals("sponge")) {
            return new Material(0.98f, 0.04f, false, 0f, Emission.NONE, "porous_fibre_or_grain");
        }
        if (n.endsWith("_log") || n.endsWith("_log_top") || n.endsWith("_stem") || n.endsWith("_stem_top")) {
            return new Material(n.startsWith("stripped_") ? 0.76f : 0.93f, 0.04f, false, 0f, Emission.NONE, "wood_grain");
        }
        if (n.endsWith("_planks") || n.equals("bamboo_mosaic")) {
            return new Material(0.76f, 0.04f, false, 0f, Emission.NONE, "wood_planks");
        }
        if (n.endsWith("_leaves") || n.endsWith("_roots") || n.endsWith("_grass") || n.equals("moss_block")
            || n.equals("moss_carpet") || n.equals("pale_moss_block")) {
            return new Material(0.92f, 0.04f, false, 0f, Emission.NONE, "vegetation");
        }
        if (n.endsWith("_terracotta") || n.equals("terracotta") || n.endsWith("_concrete")) {
            return new Material(0.84f, 0.04f, false, 0f, Emission.NONE, "matte_mineral");
        }
        if (n.startsWith("quartz_block") || n.startsWith("polished_") || n.startsWith("smooth_") || n.equals("calcite")
            || n.startsWith("prismarine") || n.startsWith("dark_prismarine") || n.endsWith("_concrete") || n.equals("lapis_block")
            || n.equals("redstone_block") || n.startsWith("purpur")) {
            return new Material(0.45f, 0.04f, false, 0f, Emission.NONE, "polished");
        }
        return DEFAULT;
    }

    /**
     * Per-pixel emission of an {@link Emission#BRIGHT} sprite: 0 for dark or grey pixels, up to 1 for bright saturated
     * (flame-like) ones. Inputs are 0..255 sRGB channel values. The brightness measure is the HSV value (largest channel),
     * not the luminance: a pure red or orange flame pixel is dim in luminance (the green channel dominates it) but fully
     * bright in value. Value 0.68..0.93 and saturation 0.10..0.35 are the ramps; a torch stick (value up to 0.62) or a
     * wooden frame stays at 0. Pure white or unsaturated grey pixels (player eyes, skins, bone, paper, armor) never emit
     * here; the white-hot core of a flame is covered by {@link #flameEmission}.
     */
    public static float brightPixelEmission(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float sat = max > 0f ? (max - min) / max : 0f;
        return smoothstep(0.68f, 0.93f, max) * smoothstep(0.10f, 0.35f, sat);
    }

    /**
     * Colour-only compatibility approximation. Atlas baking uses the UV-aware pixelEmission overload: wax colour alone
     * cannot locate the lit cap consistently across all seventeen candle colours.
     */
    public static float candleEmission(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float sat = max > 0f ? (max - min) / max : 0f;
        float lum = 0.299f * rf + 0.587f * gf + 0.114f * bf;
        return max >= 0.9f && (lum >= 0.92f || sat >= 0.55f) ? 1f : 0f;
    }

    /** Respawn anchor sides: the vivid purple / blue charge pixels glow (saturated, blue dominant, not the grey-violet stone). */
    public static float anchorEmission(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float sat = max > 0f ? (max - min) / max : 0f;
        boolean purpleCharge = bf >= 0.35f && bf >= max && sat >= 0.7f;
        boolean chargeIndicator = r >= 180 && g >= 140 && (b < g * 0.9f || Math.min(r, Math.min(g, b)) >= 235);
        return purpleCharge || chargeIndicator ? 1f : 0f;
    }

    /**
     * Per-pixel emission of a flame sprite ({@code emissive_flame}: torches, campfire fire): every bright pixel glows in its
     * own colour, white-hot ones included (the torch flame has a pure white pixel). Value 0.70..0.90, no saturation gate.
     */
    public static float flameEmission(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        return smoothstep(0.70f, 0.90f, Math.max(r, Math.max(g, b)) / 255f);
    }

    /** Sprite rule for water fluid. */
    public static final String RULE_WATER = "water";
    /** Sprite rule for ice. */
    public static final String RULE_ICE = "ice";
    /** Sprite rule for blue ice / packed ice (opaque, smooth). */
    public static final String RULE_ICE_OPAQUE = "ice_opaque";
    /** Sprite rule for lit candles (flame pixels only, see {@link #candleEmission}). */
    public static final String RULE_CANDLE = "candle_flame";
    /** Sprite rule for the glowing parts of respawn anchor sides (see {@link #anchorEmission}). */
    public static final String RULE_ANCHOR = "respawn_anchor_glow";
    /** Sprite rule for glass blocks and panes. */
    public static final String RULE_GLASS = "glass";
    /** Sprite rule for vanilla tinted_glass: see-through to the camera but blocks all light. */
    public static final String RULE_TINTED_GLASS = "tinted_glass";
    /** Sprite rule whose texels are decided by {@link #flameEmission}. */
    public static final String RULE_FLAME = "emissive_flame";
    /** Sprite rule for redstone dust/wire. */
    public static final String RULE_REDSTONE = "redstone_dust";
    /** Sprite rule for end rod. */
    public static final String RULE_END_ROD = "end_rod";
    /** Sprite rule for magma block. */
    public static final String RULE_MAGMA = "magma";
    /** Sprite rule for enchanting table. */
    public static final String RULE_ENCHANTING_TABLE = "enchanting_table";
    public static final String RULE_CRIMSON_VEINS = "crimson_veins";
    public static final String RULE_WARPED_VEINS = "warped_veins";
    public static final String RULE_CRIMSON_SPORES = "crimson_spores";
    public static final String RULE_WARPED_SPORES = "warped_spores";
    public static final String RULE_FUNGUS_SPORES = "fungus_spores";
    public static final String RULE_CHORUS_FLOWER = "chorus_flower";
    public static final String RULE_PURPLE_VEINS = "purple_veins";
    public static final String RULE_END_EYE = "end_eye";
    public static final String RULE_CRYSTAL = "crystal_tips";
    public static final String RULE_SCULK = "sculk_speckles";
    public static final String RULE_MAGIC_CORE = "magic_core";

    /** Resolves BRIGHT masks identically for block baking and entity/item atlases. Inputs are unlit sRGB texels. */
    public static float pixelEmission(Material material, int argb) {
        int a = (argb >>> 24) & 255;
        if (a < 128 || material.emission() <= 0f) return 0f;
        int r = (argb >>> 16) & 255;
        int g = (argb >>> 8) & 255;
        int b = argb & 255;
        float mask = switch (material.rule()) {
            case RULE_FLAME -> flameEmission(r, g, b, a);
            case RULE_REDSTONE -> 1f; // Signal power is supplied by the quad's vertex tint on the GPU.
            case RULE_CANDLE -> candleEmission(r, g, b, a);
            case "pumpkin_windows" -> r >= 200 && g >= 170 && g > b * 1.25f ? 1f : 0f;
            case RULE_ANCHOR -> anchorEmission(r, g, b, a);
            case RULE_END_ROD -> endRodEmission(r, g, b, a);
            case RULE_MAGMA -> magmaEmission(r, g, b, a);
            case RULE_ENCHANTING_TABLE -> enchantingTableEmission(r, g, b, a) / material.emission();
            // Pure red/cyan veins are disjoint from the purple/brown bark, including animated stem frames.
            case RULE_CRIMSON_VEINS -> smoothstep(90f, 175f, r) * smoothstep(0.40f, 0.70f, (r - Math.max(g, b)) / (float) Math.max(r, 1));
            case RULE_WARPED_VEINS -> smoothstep(75f, 165f, Math.max(g, b)) * smoothstep(0.65f, 0.80f, (Math.min(g, b) - r) / (float) Math.max(Math.min(g, b), 1));
            // Only the small bright spore clusters glow, rather than the entire wart canopy.
            case RULE_CRIMSON_SPORES -> smoothstep(130f, 172f, r) * smoothstep(0.45f, 0.70f, (r - Math.max(g, b)) / (float) Math.max(r, 1));
            case RULE_WARPED_SPORES -> smoothstep(140f, 180f, g) * smoothstep(0.4f, 0.7f, (Math.min(g, b) - r) / (float) Math.max(Math.max(g, b), 1));
            // Both fungi have warm orange spores, including the cyan warped cap. Stalks and cap flesh stay dark.
            case RULE_FUNGUS_SPORES -> r >= 180 && g >= 55 && g > b * 1.25f
                ? smoothstep(175f, 250f, r) : 0f;
            // Living chorus flowers have pale lilac centres; the purple shell and dead flower are non-emissive.
            case RULE_CHORUS_FLOWER -> b >= r * 0.9f && r >= g * 0.95f ? smoothstep(175f, 245f, g) : 0f;
            case RULE_PURPLE_VEINS -> smoothstep(85f, 225f, b) * smoothstep(0.50f, 0.80f, (b - g) / (float) Math.max(b, 1));
            case RULE_END_EYE -> g >= r && g >= b ? smoothstep(115f, 210f, g) : 0f;
            case RULE_CRYSTAL -> b >= g && r >= g ? smoothstep(145f, 235f, Math.min(r, b)) : 0f;
            case RULE_SCULK -> g > r * 2f && b > r * 2f
                ? smoothstep(45f, 130f, Math.max(g, b)) : 0f;
            case RULE_MAGIC_CORE -> (Math.min(r, Math.min(g, b)) >= 225
                || (r >= 180 && g >= 55 && r > g * 1.1f && g > b * 1.25f)
                || (Math.min(g, b) >= 100 && r < Math.min(g, b) * 0.6f))
                ? flameEmission(r, g, b, a) : 0f;
            default -> brightPixelEmission(r, g, b, a);
        };
        return material.emission() * mask;
    }

    /** Vanilla candle top UV [0,6,2,8] is a small warm cap proxy for its raster flame; wax sides never emit.
     * Layout is from models/block/template_candle.json, scaled for higher-resolution textures. */
    public static float pixelEmission(Material material, int argb, int x, int y, int width, int height) {
        if (!RULE_CANDLE.equals(material.rule())) return pixelEmission(material, argb);
        if (width <= 0 || height <= 0 || (argb >>> 24) < 128) return 0f;
        float u = (x + 0.5f) * 16f / width;
        float v = (y + 0.5f) * 16f / height;
        return u < 2f && v >= 6f && v < 8f ? material.emission() : 0f;
    }

    private static boolean hasCopperPatina(String n) {
        return n.startsWith("exposed_") || n.startsWith("weathered_") || n.startsWith("oxidized_");
    }

    private static boolean isRail(String n) {
        return n.equals("rail") || n.equals("rail_corner") || n.startsWith("powered_rail")
            || n.startsWith("detector_rail") || n.startsWith("activator_rail");
    }

    /**
     * Redstone wire emission proportional to signal power (0..15). For power 0, emission is strictly 0.
     */
    public static float redstoneEmission(int power) {
        if (power <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(15, power)) / 15.0f;
    }

    /**
     * Redstone emission from an sRGB texel tint: power 0 is dark red (~0.302 in float),
     * while power 15 is bright red (~1.0). Unpowered redstone (power 0) has strictly 0 emission.
     */
    public static float redstoneEmissionFromColor(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        float rf = r / 255f;
        if (rf <= 0.35f) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (rf - 0.35f) / 0.65f));
    }

    /**
     * End rod per-pixel emission: bulb/shaft shines (emission 1.0, low roughness ~0.1),
     * while the dark base does not emit (emission 0.0).
     */
    public static float endRodEmission(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        float lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        return lum > 0.75f ? 1.0f : 0.0f;
    }

    /**
     * Magma per-pixel emission: glowing lava cracks emit (emission 1.0, roughness 0.15),
     * dark stone crust is matte and does not emit (emission 0.0, roughness 0.85).
     */
    public static float magmaEmission(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        return (r > 165 && g > 65 && r > b * 2) ? 1.0f : 0.0f;
    }

    /**
     * Enchanting table per-pixel emission: diamond cyan corners glow slightly (~0.25 flux, roughness 0.08, f0 = 0.17).
     */
    public static float enchantingTableEmission(int r, int g, int b, int a) {
        if (a < 128) {
            return 0f;
        }
        return (b >= 170 && g >= 200) ? 0.25f : 0.0f;
    }

    /** Ores: METAL (iron/gold/copper: mineral pixels are metal), GEM (diamond, emerald, lapis, redstone, quartz: smooth), COAL. */
    public enum OreKind { NONE, METAL, GEM, COAL }

    public static OreKind oreKind(String spritePath) {
        if (spritePath == null) {
            return OreKind.NONE;
        }
        String p = spritePath.toLowerCase(Locale.ROOT);
        if (p.endsWith("/gilded_blackstone") || p.equals("gilded_blackstone")) {
            return OreKind.METAL;
        }
        if (!p.contains("_ore")) {
            return OreKind.NONE;
        }
        if (p.contains("iron_ore") || p.contains("gold_ore") || p.contains("copper_ore")) {
            return OreKind.METAL;
        }
        if (p.contains("coal_ore")) {
            return OreKind.COAL;
        }
        return OreKind.GEM;
    }

    /** Mixed blocks: items/blocks with metallic frames/chains and emissive or dielectric parts. */
    public enum MixedKind {
        NONE,
        LANTERN,
        SOUL_LANTERN,
        COPPER_BULB,
        REDSTONE_LAMP,
        CAULDRON,
        ANVIL,
        HOPPER,
        IRON_BARS,
        CHAIN,
        CHEST,
        COPPER_LANTERN,
        COPPER_PATINA,
        RAIL,
        IRON_HARDWARE
    }

    public static MixedKind mixedKind(String spritePath) {
        if (spritePath == null) {
            return MixedKind.NONE;
        }
        String p = spritePath.toLowerCase(Locale.ROOT);
        int slash = p.lastIndexOf('/');
        String n = slash >= 0 ? p.substring(slash + 1) : p;

        if (p.contains("chest") && !p.contains("ender")) {
            return MixedKind.CHEST;
        }
        if (n.equals("soul_lantern") || n.contains("soul_lantern")) {
            return MixedKind.SOUL_LANTERN;
        }
        if (n.contains("copper_lantern")) {
            return MixedKind.COPPER_LANTERN;
        }
        if (n.equals("lantern") || (n.contains("lantern") && !n.contains("jack_o_lantern") && !n.contains("sea_lantern"))) {
            return MixedKind.LANTERN;
        }
        if (n.contains("copper_bulb")) {
            return MixedKind.COPPER_BULB;
        }
        if (n.contains("redstone_lamp")) {
            return MixedKind.REDSTONE_LAMP;
        }
        if (hasCopperPatina(n) && (n.contains("copper") || n.contains("lightning_rod"))) {
            return MixedKind.COPPER_PATINA;
        }
        if (n.startsWith("cauldron")) {
            return MixedKind.CAULDRON;
        }
        if (n.contains("anvil")) {
            return MixedKind.ANVIL;
        }
        if (n.startsWith("hopper")) {
            return MixedKind.HOPPER;
        }
        if (n.equals("iron_bars")) {
            return MixedKind.IRON_BARS;
        }
        if (n.equals("chain") || n.equals("iron_chain")) {
            return MixedKind.CHAIN;
        }
        if (isRail(n)) return MixedKind.RAIL;
        if (n.equals("tripwire_hook") || n.equals("stonecutter_saw")) return MixedKind.IRON_HARDWARE;
        return MixedKind.NONE;
    }

    /**
     * Identifies metallic texels in mixed blocks (neutral grey / dark metallic iron, copper casing).
     */
    public static boolean isMetalTexel(int argb, MixedKind kind) {
        if (((argb >>> 24) & 255) < 128) {
            return false;
        }
        float r = ((argb >>> 16) & 255) / 255f;
        float g = ((argb >>> 8) & 255) / 255f;
        float b = (argb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float sat = max > 0f ? (max - min) / max : 0f;
        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;

        switch (kind) {
            case CHEST:
                // Chest body is wood. Latch is metallic iron (neutral silver/grey: sat < 0.25, lum in 0.35..0.98)
                return sat < 0.25f && lum > 0.35f;
            case LANTERN:
            case SOUL_LANTERN:
                // Frame and chains are neutral grey / dark metallic (sat < 0.25, lum < 0.75)
                return sat < 0.25f && lum < 0.75f;
            case COPPER_LANTERN:
                return !isEmissiveTexel(argb, kind) && r > g * 1.12f && g > b * 1.05f && sat > 0.18f;
            case COPPER_PATINA:
                // Bare reddish copper reflects as metal; green/blue corrosion is a rough dielectric coating.
                return r > g * 1.12f && g > b * 1.05f && sat > 0.18f;
            case COPPER_BULB:
                // Warm exposed copper and neutral dark hardware are metallic; blue-green corrosion is dielectric.
                boolean isCopper = r > g * 1.12f && g > b * 1.05f && sat > 0.18f;
                boolean isDarkMetal = sat < 0.25f && lum < 0.55f;
                return isCopper || isDarkMetal;
            case RAIL:
                return sat < 0.20f || (r > 0.45f && g > r * 0.78f && b < g * 0.40f);
            case IRON_HARDWARE:
                return sat < 0.25f;
            case REDSTONE_LAMP:
                // Border frame is dark metallic rim / casing
                return lum < 0.45f && sat < 0.35f;
            case CAULDRON:
            case ANVIL:
            case HOPPER:
            case IRON_BARS:
            case CHAIN:
                // Iron parts: neutral grey or dark metallic
                return sat < 0.35f;
            default:
                return false;
        }
    }

    /** The bulb's four glass apertures do not become metal when their lit texture turns orange. */
    public static boolean isMetalTexel(int argb, MixedKind kind, int x, int y, int w, int h) {
        return !(kind == MixedKind.COPPER_BULB && bulbWindow(x, y, w, h)) && isMetalTexel(argb, kind);
    }

    public static boolean isEmissiveTexel(int argb, MixedKind kind, int x, int y, int w, int h) {
        return (kind != MixedKind.COPPER_BULB || bulbWindow(x, y, w, h)) && isEmissiveTexel(argb, kind);
    }

    static boolean bulbWindow(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return false;
        int u = (int) ((x + .5f) * 16 / w), v = (int) ((y + .5f) * 16 / h);
        // Vanilla copper_bulb's four apertures, shared by all oxidation/power variants and scaled with the sprite.
        if ((v >= 1 && v <= 3) || (v >= 12 && v <= 14)) return (u >= 4 && u <= 6) || (u >= 9 && u <= 11);
        if ((v >= 4 && v <= 6) || (v >= 9 && v <= 11)) return (u >= 1 && u <= 6) || (u >= 9 && u <= 14);
        return false;
    }

    /**
     * Identifies emissive texels (fire, soul fire, glowing bulb, lit lamp glass) in mixed blocks.
     */
    public static boolean isEmissiveTexel(int argb, MixedKind kind) {
        if (((argb >>> 24) & 255) < 128) {
            return false;
        }
        float r = ((argb >>> 16) & 255) / 255f;
        float g = ((argb >>> 8) & 255) / 255f;
        float b = (argb & 255) / 255f;
        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;

        switch (kind) {
            case LANTERN:
                return brightPixelEmission((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) & 255) > 0.05f
                    || (lum > 0.55f && r > 0.65f && g > 0.45f);
            case SOUL_LANTERN:
                return brightPixelEmission((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) & 255) > 0.05f
                    || (lum > 0.48f && b > 0.45f && g > 0.38f);
            case COPPER_LANTERN: {
                // green copper flame: bright (value >= 0.68) and not red-dominant; the casing is orange / brown / oxidized turquoise (value < 0.65)
                float mx = Math.max(r, Math.max(g, b));
                return mx >= 0.68f && g >= r * 0.98f;
            }
            case COPPER_BULB:
                return r >= 200f / 255f && g >= 160f / 255f && r > b * 1.25f && g > b * 1.12f && g <= r * 1.05f;
            case REDSTONE_LAMP:
                return brightPixelEmission((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) & 255) > 0.05f
                    || (lum > 0.48f && r > 0.55f);
            default:
                return false;
        }
    }

    /**
     * Per-pixel roughness: the category base, rougher for texels darker than the sprite mean (cracks, seams) and for
     * high local contrast, smoother for bright texels; {@code contrast} scales the variation (0 = constant base).
     */
    public static float pixelRoughness(float base, float lum, float meanLum, float localDev, float contrast) {
        float r = base + contrast * (0.3f * (meanLum - lum) + 0.2f * localDev);
        return Math.max(0.02f, Math.min(1f, r));
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
