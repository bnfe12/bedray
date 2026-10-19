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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every option of the Vulkan page must have its own Identifier. Sodium loads only the first option of an Identifier; a second
 * one keeps modifiedValue null and the settings screen crashed with an NPE in TickBoxControl (dlss_nr_enabled was registered
 * twice, fixed in feat/next). The page is built against a recording ConfigBuilder (java.lang.reflect.Proxy: every builder
 * call returns the builder itself, create*Option(Identifier, ...) records the id), so the real registerConfigLate runs.
 * ConfigBuilder signatures: javap of libs/sodium-core.jar (create{Boolean,Integer,Enum,ExternalButton}Option(Identifier ...)).
 */
public class VulkanSodiumConfigIdsTest {

    @Test
    public void everyOptionIdentifierIsUnique() {
        List<Identifier> ids = new ArrayList<>();
        new VulkanSodiumConfig().registerConfigLate(recordingBuilder(ids));

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
        assertTrue(counts.containsKey(Identifier.fromNamespaceAndPath("bedray", "pt_preset")),
            "the PT Fast/Quality preset must be registered");
        assertFalse(counts.containsKey(Identifier.fromNamespaceAndPath("bedray", "dlss5_status")),
            "the not-implemented DLSS 5 entry is removed from the UI (docs/SETTINGS_AUDIT.md)");
    }

    private static ConfigBuilder recordingBuilder(List<Identifier> ids) {
        return (ConfigBuilder) fluent(ConfigBuilder.class, ids);
    }

    /** A proxy of {@code type} whose methods return a proxy of their (interface) return type, or a default value. */
    private static Object fluent(Class<?> type, List<Identifier> ids) {
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
            Class<?> ret = method.getReturnType();
            if (ret == void.class) {
                return null;
            }
            if (ret.isInstance(proxy)) {
                return proxy;
            }
            if (ret.isInterface()) {
                return fluent(ret, ids);
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
