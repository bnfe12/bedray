package io.github.andrexarlay.vulkancompat.sodium;

import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every option Bedray registers must have its own Identifier and may only depend on identifiers that it registers.
 * Sodium keeps one option per id (a second one is never loaded and its control later dereferences a null value - the
 * 2026-10-12 NPE in TickBoxControl was bedray:dlss_nr_enabled registered twice) and Config.validateDependencies crashes the
 * settings screen when an enabled-provider names an id that does not exist.
 * The options live in the Bedray screen config ({@code registerSettings}, reached through {@code createScreenConfig}); the
 * Sodium page itself only holds the entry point that opens that screen. This test drives the real registration against a
 * recording ConfigBuilder (java.lang.reflect.Proxy: every builder call returns the builder itself, create*Option(Identifier,
 * ...) records the id), so the invariants are checked on the shipped option set. ConfigBuilder signatures: javap of
 * libs/sodium-core.jar.
 */
public class VulkanSodiumConfigIdsTest {

    @Test
    public void everyOptionIdentifierIsUnique() {
        List<Identifier> ids = new ArrayList<>();
        List<Identifier> dependencies = new ArrayList<>();
        new VulkanSodiumConfig().registerSettings(recordingBuilder(ids, dependencies));

        assertFalse(ids.isEmpty(), "no option was created");
        Map<Identifier, Integer> counts = new HashMap<>();
        for (Identifier id : ids) {
            counts.merge(id, 1, Integer::sum);
        }
        List<String> duplicates = new ArrayList<>();
        counts.forEach((id, n) -> {
            if (n > 1) {
                duplicates.add(id + " x" + n);
            }
        });
        assertTrue(duplicates.isEmpty(), "duplicate option identifiers on the Vulkan page: " + duplicates);

        List<String> dangling = dependencies.stream().filter(d -> !counts.containsKey(d)).distinct()
            .map(Object::toString).sorted().toList();
        assertTrue(dangling.isEmpty(),
            "options depend on ids that are not registered (Sodium crashes with Config.validateDependencies): " + dangling);

        List<String> required = List.of(
            "renderer", "renderer_status", "rt_mode", "path_bounces", "path_auto_exposure", "water_style", "atm_godrays",
            "upscaler_backend", "quality_preset", "dlss_mode", "xess_mode", "fsr_mode", "frame_generation_provider",
            "pt_preset", "path_spp", "path_distance", "path_denoiser", "path_caustics", "adaptive_quality",
            "atm_bloom", "atm_sunglare", "rt_fog", "rt_fog_density", "auto_normals", "debug_overlay", "reset_all_defaults",
            "presets_open", "natives_manager_open", "reset_page_main", "reset_group_adv_pt",
            "rays_pixel_shadows", "rays_pixel_shadow_size",
            "voxel_quality_profile", "path_quality_profile");
        for (String req : required) {
            assertTrue(counts.containsKey(Identifier.fromNamespaceAndPath("bedray", req)),
                "missing required option id across the 2 pages: " + req);
        }
        assertFalse(counts.containsKey(Identifier.fromNamespaceAndPath("bedray", "dlss5_status")),
            "the not-implemented DLSS 5 entry is removed from the UI (docs/SETTINGS_AUDIT.md)");

        // The Bedray screen builds its rows by walking SettingsCatalog, so an option without a catalog row is invisible to
        // the player (no name, no tooltip, never listed). Buttons carry their own label and need no catalog entry.
        List<String> invisible = counts.keySet().stream().map(Identifier::getPath)
            .filter(p -> !p.startsWith("reset_") && !p.endsWith("_open") && !p.equals("copy_diagnostics"))
            .filter(p -> SettingsCatalog.get(p) == null).sorted().toList();
        assertTrue(invisible.isEmpty(), "registered options with no catalog row (unreachable in the Bedray screen): " + invisible);
    }

    private static ConfigBuilder recordingBuilder(List<Identifier> ids, List<Identifier> dependencies) {
        return (ConfigBuilder) fluent(ConfigBuilder.class, ids, dependencies);
    }

    /** A proxy of {@code type} whose methods return a proxy of their (interface) return type, or a default value. */
    private static Object fluent(Class<?> type, List<Identifier> ids, List<Identifier> dependencies) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> type.getSimpleName() + "@recording";
                };
            }
            if (method.getName().startsWith("create") && method.getName().endsWith("Option") && args != null && args.length > 0
                && args[0] instanceof Identifier id) {
                ids.add(id);
            }
            if ("setEnabledProvider".equals(method.getName()) && args != null && args.length == 2
                && args[1] instanceof Identifier[] deps) {
                dependencies.addAll(List.of(deps));
            }
            Class<?> ret = method.getReturnType();
            if (ret == void.class) {
                return null;
            }
            if (ret.isInstance(proxy)) {
                return proxy;
            }
            if (ret.isInterface()) {
                return fluent(ret, ids, dependencies);
            }
            if (ret == boolean.class) {
                return false;
            }
            if (ret.isPrimitive()) {
                return 0;
            }
            return null;
        };
        return Proxy.newProxyInstance(VulkanSodiumConfigIdsTest.class.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
