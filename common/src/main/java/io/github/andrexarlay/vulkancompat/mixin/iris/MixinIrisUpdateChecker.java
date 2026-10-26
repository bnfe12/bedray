package io.github.andrexarlay.vulkancompat.mixin.iris;

import net.irisshaders.iris.UpdateChecker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Ensures Iris.getUpdateChecker() and Iris.getIrisConfig() never return null when running on Vulkan,
 * preventing NullPointerException in ShaderPackScreen.<init>().
 * Also ensures Iris.reload() initializes irisConfig and doesn't crash on Vulkan.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class MixinIrisUpdateChecker {
    private static final UpdateChecker DUMMY_CHECKER = new UpdateChecker("vulkan");
    private static Object cachedIrisConfig = null;

    private static void ensureIrisConfig(Class<?> irisClass) {
        try {
            Field configField = irisClass.getDeclaredField("irisConfig");
            configField.setAccessible(true);
            Object current = configField.get(null);
            if (current == null) {
                if (cachedIrisConfig == null) {
                    Class<?> configClass = Class.forName("net.irisshaders.iris.config.IrisConfig");
                    try {
                        cachedIrisConfig = configClass.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        for (var constructor : configClass.getDeclaredConstructors()) {
                            constructor.setAccessible(true);
                            Class<?>[] paramTypes = constructor.getParameterTypes();
                            Object[] args = new Object[paramTypes.length];
                            for (int i = 0; i < paramTypes.length; i++) {
                                if (paramTypes[i] == java.nio.file.Path.class) {
                                    args[i] = java.nio.file.Paths.get("config", "iris.properties");
                                } else if (paramTypes[i] == java.io.File.class) {
                                    args[i] = new java.io.File("config/iris.properties");
                                }
                            }
                            try {
                                cachedIrisConfig = constructor.newInstance(args);
                                break;
                            } catch (Exception ignored) {}
                        }
                    }
                }
                if (cachedIrisConfig != null) {
                    configField.set(null, cachedIrisConfig);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "getUpdateChecker", at = @At("HEAD"), cancellable = true)
    private static void vkb$safeUpdateChecker(CallbackInfoReturnable<UpdateChecker> cir) {
        cir.setReturnValue(DUMMY_CHECKER);
    }

    @Inject(method = "getIrisConfig", at = @At("HEAD"), cancellable = true)
    private static void vkb$safeIrisConfig(CallbackInfoReturnable<Object> cir) {
        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            ensureIrisConfig(irisClass);
            Field configField = irisClass.getDeclaredField("irisConfig");
            configField.setAccessible(true);
            Object cfg = configField.get(null);
            if (cfg != null) {
                cir.setReturnValue(cfg);
            } else if (cachedIrisConfig != null) {
                cir.setReturnValue(cachedIrisConfig);
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "reload", at = @At("HEAD"), cancellable = true)
    private static void vkb$safeReload(CallbackInfo ci) {
        try {
            Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
            ensureIrisConfig(irisClass);

            // Dirt RT status is owned by the shader host bootstrap (DirtRtRuntime); this reload hook no longer
            // overwrites it with a fixed "unavailable" state.
        } catch (Throwable ignored) {}
    }
}
