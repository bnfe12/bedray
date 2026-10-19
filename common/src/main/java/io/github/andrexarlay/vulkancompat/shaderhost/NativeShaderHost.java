package io.github.andrexarlay.vulkancompat.shaderhost;

import java.util.Set;

/**
 * The built-in Vulkan-native RT renderer (rt/world: RtWorld, RtFrameRenderer, RtRayEffectsPass, RtPathTracerPass) seen as a
 * shader host. This is a wrapper only: it changes nothing in how that renderer is created, configured or run (rt_mode, RtSettings
 * and the RT device requirements stay where they are). Its shaders are compiled from the mod's own resources, so the host is
 * always available; GPU support for ray tracing is decided and reported by the RT core itself (RtFailure / "[VKB-RT]" lines),
 * not by the shader host.
 */
@Experimental("I0")
public final class NativeShaderHost implements ShaderHost {

    @Override
    public ShaderHostKind kind() {
        return ShaderHostKind.NATIVE;
    }

    @Override
    public ShaderHostStatus probe(ShaderHostContext ctx) {
        return ShaderHostStatus.of(ShaderHostKind.NATIVE, ShaderHostState.READY,
            "built-in rt/world renderer, shaders from mod resources; RT device support is reported by [VKB-RT]");
    }

    /** Nothing beyond RtDeviceRequirements (registered by the RT core, not by the host). */
    @Override
    public Set<String> extraDeviceExtensions() {
        return Set.of();
    }
}
