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
 * Every option of the Vulkan page must have its own Identifier. Sodium keeps one option per id: a second option with the
 * same id is never loaded and its control later dereferences a null value (user crash 2026-10-12_07.47.13: NPE in
 * TickBoxControl, BooleanOption.getValidatedValue() == null, id bedray:dlss_nr_enabled registered twice).
 * The page is built against a recording ConfigBuilder (java.lang.reflect.Proxy over the Sodium API interfaces of
 * libs/sodium-core.jar); every create*Option(Identifier ...) call is collected.
 */
class SodiumPageOptionIdsTest {

    @Test
    void everyOptionIdOnTheVulkanPageIsUnique() {
        List<Identifier> created = new ArrayList<>();
        ConfigBuilder builder = recorder(ConfigBuilder.class, created);
        new VulkanSodiumConfig().registerConfigLate(builder);

        assertFalse(created.isEmpty(), "the recording builder saw no create*Option(Identifier) calls");
        Map<Identifier, Long> counts = created.stream().collect(Collectors.groupingBy(i -> i, HashMap::new, Collectors.counting()));
        List<String> dups = counts.entrySet().stream().filter(e -> e.getValue() > 1)
            .map(e -> e.getKey() + " x" + e.getValue()).sorted().toList();
        assertTrue(dups.isEmpty(), "duplicate Sodium option ids (Sodium drops the second option -> NPE in its control): " + dups);

        List<String> required = List.of(
            "upscaler_backend", "quality_preset", "dlss_mode", "xess_mode", "fsr_mode", "frame_generation_mode",
            "rt_mode", "path_bounces", "path_auto_exposure", "water_style", "atm_godrays",
            "pt_preset", "path_spp", "path_distance", "path_denoiser", "path_caustics",
            "atm_bloom", "atm_sunglare", "rt_fog", "rt_fog_density", "auto_normals", "debug_overlay", "reset_all_defaults",
            "presets_open", "natives_manager_open", "reset_page_main", "reset_group_adv_pt"
        );
        for (String req : required) {
            assertTrue(counts.containsKey(Identifier.fromNamespaceAndPath("bedray", req)),
                "missing required option id across the 2 pages: " + req);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T recorder(Class<T> type, List<Identifier> created) {
        InvocationHandler h = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> type.getSimpleName() + "$recorder";
                };
            }
            if (method.getName().startsWith("create") && args != null && args.length > 0 && args[0] instanceof Identifier id) {
                created.add(id);
            }
            Class<?> rt = method.getReturnType();
            if (rt == void.class) return null;
            if (rt == boolean.class) return false;
            if (rt == int.class || rt == long.class || rt == short.class || rt == byte.class) return 0;
            if (rt == float.class) return 0f;
            if (rt == double.class) return 0d;
            if (rt.isInstance(proxy)) return proxy;
            if (rt.isInterface()) return recorder(rt, created);
            return null;
        };
        return (T) Proxy.newProxyInstance(SodiumPageOptionIdsTest.class.getClassLoader(), new Class<?>[] {type}, h);
    }
}
