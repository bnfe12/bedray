package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class UpscalerQualityBindingTest {
    /** Exercise the real Sodium save callbacks: logging previously threw after applying the request. */
    @Test
    void everyQualityCanBeSavedAndNamedWithoutQueryingAnAbsentTableRatio() throws Exception {
        Map<String, Consumer<UpscalerMode>> setters = new HashMap<>();
        Map<String, Function<UpscalerMode, ?>> names = new HashMap<>();
        var oldBackend = VulkanRtxConfig.activeBackend;
        var oldDlss = VulkanRtxConfig.dlssMode;
        var oldXess = VulkanRtxConfig.xessMode;
        var oldFsr = VulkanRtxConfig.fsrMode;
        var oldType = VulkanRtxConfig.upscalerType;
        var requestField = UpscalerRequest.class.getDeclaredField("current");
        requestField.setAccessible(true);
        var oldRequest = requestField.get(null);
        try {
            // Store inactive quality settings without native libraries or a GPU.
            VulkanRtxConfig.activeBackend = UpscalerBackend.BILINEAR;
            new VulkanSodiumConfig().registerConfigLate(recorder(ConfigBuilder.class, null, setters, names));
            for (var backend : new UpscalerBackend[]{UpscalerBackend.DLSS, UpscalerBackend.XESS, UpscalerBackend.FSR}) {
                String path = backend.name().toLowerCase(Locale.ROOT) + "_mode";
                assertNotNull(setters.get(path), path);
                assertNotNull(names.get(path), path);
                for (var mode : UpscalerModes.modesFor(backend)) {
                    assertDoesNotThrow(() -> setters.get(path).accept(mode), path + " save " + mode);
                    assertEquals(mode, VulkanRtxConfig.modeOf(backend), path + " stores " + mode);
                    assertNotNull(assertDoesNotThrow(() -> names.get(path).apply(mode)), path + " names " + mode);
                }
            }
            assertEquals("runtime-ngx", VulkanSodiumConfig.qualityRatioForLog(UpscalerBackend.DLSS, UpscalerMode.QUALITY));
            assertEquals("runtime-ngx", VulkanSodiumConfig.qualityRatioForLog(UpscalerBackend.DLSS, UpscalerMode.CUSTOM));
            assertTrue(VulkanSodiumConfig.qualityRatioForLog(UpscalerBackend.FSR, UpscalerMode.CUSTOM).endsWith("-requested-custom"));
        } finally {
            VulkanRtxConfig.activeBackend = oldBackend;
            VulkanRtxConfig.dlssMode = oldDlss;
            VulkanRtxConfig.xessMode = oldXess;
            VulkanRtxConfig.fsrMode = oldFsr;
            VulkanRtxConfig.upscalerType = oldType;
            requestField.set(null, oldRequest);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T recorder(Class<T> type, String path, Map<String, Consumer<UpscalerMode>> setters,
                                   Map<String, Function<UpscalerMode, ?>> names) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> "quality-recorder";
            };
            String childPath = path;
            if (method.getName().startsWith("create") && args != null && args.length > 0 && args[0] instanceof Identifier id) {
                childPath = id.getPath();
            }
            if (path != null && path.matches("(dlss|xess|fsr)_mode")) {
                if (method.getName().equals("setBinding")) setters.put(path, (Consumer<UpscalerMode>) args[0]);
                if (method.getName().equals("setElementNameProvider")) names.put(path, (Function<UpscalerMode, ?>) args[0]);
            }
            Class<?> ret = method.getReturnType();
            if (ret == void.class) return null;
            if (ret.isInstance(proxy)) return proxy;
            if (ret.isInterface()) return recorder(ret, childPath, setters, names);
            if (ret == boolean.class) return false;
            if (ret.isPrimitive()) return 0;
            return null;
        });
    }
}
