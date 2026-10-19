package io.github.andrexarlay.vulkanbridge.neoforge.mixin;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin of the NeoForge module: gates {@link MixinClientHooks} by bytecode inspection.
 * <p>
 * The workaround is needed only while BOTH hold: {@code ClientHooks.initClientHooks} calls
 * {@code RenderPipelines.registerCustomPipelines()} AND the (patched) client class {@code RenderPipelines} has no such
 * method (NeoForge 26.3.0.57-beta). Evidence for the fix (javap, 2026-10-12):
 * minecraft-client-patched-26.3.0.76.jar {@code RenderPipelines.registerCustomPipelines()V} exists and posts
 * {@code RegisterRenderPipelinesEvent}, while neoforge-26.3.0.76-universal.jar ClientHooks still calls it (offset 131).
 * A gate on "the call is present" (the old gate) is therefore wrong: on 0.76 the redirect would apply and
 * silently drop {@code RegisterRenderPipelinesEvent} for every mod. Here the method itself is checked.
 * <p>
 * If the bytecode cannot be read, the mixin is NOT applied and a WARN line says so: on a fixed NeoForge that is
 * correct, on 0.57-beta the game then fails loudly with NoSuchMethodError (no silent loss of the event).
 */
public class NeoForgeMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("BedrayCore/Mixins");

    private static final String CLIENT_HOOKS_MIXIN = "MixinClientHooks";
    private static final String CLIENT_HOOKS_INTERNAL = "net/neoforged/neoforge/client/ClientHooks";
    private static final String PIPELINES_INTERNAL = "net/minecraft/client/renderer/RenderPipelines";
    private static final String METHOD_NAME = "registerCustomPipelines";
    private static final String METHOD_DESC = "()V";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (CLIENT_HOOKS_MIXIN.equals(name)) {
            return clientHooksWorkaroundNeeded();
        }
        return true;
    }

    /** True only if ClientHooks.initClientHooks calls RenderPipelines.registerCustomPipelines()V and that method is absent. */
    static boolean clientHooksWorkaroundNeeded() {
        boolean callPresent;
        boolean methodPresent;
        try {
            ClassNode hooks = MixinService.getService().getBytecodeProvider().getClassNode(CLIENT_HOOKS_INTERNAL);
            callPresent = callsRegisterCustomPipelines(hooks);
            ClassNode pipelines = MixinService.getService().getBytecodeProvider().getClassNode(PIPELINES_INTERNAL);
            methodPresent = hasMethod(pipelines);
        } catch (Exception | LinkageError e) {
            LOGGER.warn("[VKB-COMPAT] mixin_skipped={} reason=cannot_inspect_bytecode ({}); if this NeoForge still lacks "
                    + "RenderPipelines.registerCustomPipelines the game will fail with NoSuchMethodError", CLIENT_HOOKS_MIXIN, e.toString());
            return false;
        }
        if (callPresent && !methodPresent) {
            LOGGER.info("[VKB-COMPAT] clienthooks_gate=apply call_present=true method_present=false (NeoForge 26.3.0.57-beta bug)");
            return true;
        }
        LOGGER.info("[VKB-COMPAT] mixin_skipped={} call_present={} method_present={} reason=not_needed_in_this_NeoForge",
                CLIENT_HOOKS_MIXIN, callPresent, methodPresent);
        return false;
    }

    static boolean callsRegisterCustomPipelines(ClassNode hooks) {
        for (MethodNode method : hooks.methods) {
            if (!"initClientHooks".equals(method.name) || method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode m
                        && PIPELINES_INTERNAL.equals(m.owner) && METHOD_NAME.equals(m.name) && METHOD_DESC.equals(m.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasMethod(ClassNode pipelines) {
        for (MethodNode method : pipelines.methods) {
            if (METHOD_NAME.equals(method.name) && METHOD_DESC.equals(method.desc)) {
                return true;
            }
        }
        return false;
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
        LOGGER.info("[VKB-COMPAT] mixin_applied={} target={}", mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1), targetClassName);
    }
}
