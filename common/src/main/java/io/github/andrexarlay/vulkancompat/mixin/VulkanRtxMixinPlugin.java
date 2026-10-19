package io.github.andrexarlay.vulkancompat.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mixin configuration plugin of the RTX mod.
 * <p>
 * Debug switch: {@code -Dvkrtx.disableMixins=<csv of simple mixin class names>}. A disabled mixin is not applied and the
 * log gets {@code [VKB-RTX] mixin_disabled=<name>}. Every mixin that is really applied is logged in {@link #postApply}
 * as {@code [VKB-RTX] mixin_applied=<name> target=<class>}. Without the property every mixin of the config is applied.
 */
public class VulkanRtxMixinPlugin implements IMixinConfigPlugin {
    /** JVM property with a comma separated list of simple mixin class names that must NOT be applied. */
    public static final String PROPERTY_DISABLE_MIXINS = "vkrtx.disableMixins";

    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/Mixins");

    private Set<String> disabled = Collections.emptySet();

    /**
     * Parses the csv value of {@link #PROPERTY_DISABLE_MIXINS}. Entries are trimmed; a package prefix and a trailing
     * {@code .class} are dropped; empty entries are ignored.
     */
    public static Set<String> parseDisabledMixins(String csv) {
        Set<String> result = new LinkedHashSet<>();
        if (csv == null) {
            return result;
        }
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (name.endsWith(".class")) {
                name = name.substring(0, name.length() - ".class".length());
            }
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                name = name.substring(dot + 1);
            }
            if (!name.isEmpty()) {
                result.add(name);
            }
        }
        return result;
    }

    static String simpleName(String mixinClassName) {
        int dot = mixinClassName.lastIndexOf('.');
        return dot >= 0 ? mixinClassName.substring(dot + 1) : mixinClassName;
    }

    @Override
    public void onLoad(String mixinPackage) {
        disabled = parseDisabledMixins(System.getProperty(PROPERTY_DISABLE_MIXINS));
        // Streamline must be the Vulkan library before LWJGL loads it (only when frame generation is requested and the binaries exist)
        io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenEarly.prepare();
        if (!disabled.isEmpty()) {
            LOGGER.warn("[VKB-RTX] {}={} (debug switch: these mixins will NOT be applied)", PROPERTY_DISABLE_MIXINS, disabled);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = simpleName(mixinClassName);
        if (disabled.contains(name)) {
            LOGGER.info("[VKB-RTX] mixin_disabled={}", name);
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        LOGGER.info("[VKB-RTX] mixin_applied={} target={}", simpleName(mixinClassName), targetClassName);
    }
}
