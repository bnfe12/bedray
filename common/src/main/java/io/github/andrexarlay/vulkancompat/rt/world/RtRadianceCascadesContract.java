package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import java.util.Arrays;

/** Reflected v2 field contract, distinct from the v1 two-segment atlas. */
final class RtRadianceCascadesContract {
    static final String BUILD = "rc_field_build_scene", TRACE = "rc_path_trace_scene";
    private RtRadianceCascadesContract() {}
    static void requireFieldBindings(SlangPassManifest manifest) {
        require(manifest,"rcField",34,BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER,272);
        require(manifest,"rcTails",35,BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER,32);
    }
    static void requireBuilder(SlangPassManifest manifest) {
        if (manifest.pushConstantBytes()!=80 || manifest.bindings().size()!=16
            || !Arrays.equals(manifest.threadGroupSize(),new int[]{64,1,1}))
            throw new IllegalStateException("RC builder needs push80, 16 bindings and 64x1x1 threads");
        requireFieldBindings(manifest);
        require(manifest,"frame",16,BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER,608);
        require(manifest,"sections",2,BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER,64);
    }
    private static void require(SlangPassManifest m,String name,int binding,int type,int bytes) {
        var b=m.binding(name);
        if (b==null || b.binding()!=binding || b.descriptorType()!=type || b.elementBytes()!=bytes)
            throw new IllegalStateException(m.name()+": incompatible RC binding "+name);
    }
}
