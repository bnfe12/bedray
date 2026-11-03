// Native-scene binding adapter for the shared volumetric march/store.
// Binding 2 is bedray_scene's GeometrySection table, not LegacySection.
// This entry traces world geometry only (mask 0x01); it never reads unbound
// entity streams. The host must supply a world-only request. No alpha/material
// filtering is added: visibility retains the existing binary blocker contract.
public module volumetric_march_scene_pass;

import "contracts/frame.slang";
import "contracts/geometry.slang";
import "light/volumetric_march_entry.slang";
import "traversal/hardware_traversal.slang";

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::image_format("r32f")]] [[vk::binding(12, 0)]] RWTexture2D<float> outDepth;
[[vk::image_format("rgba32f")]] [[vk::binding(11, 0)]] RWTexture2D<float4> accumImage;
[[vk::binding(2, 0)]] StructuredBuffer<GeometrySection> sections;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;
[[vk::push_constant]] VolumetricMarchPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID)
{
    GeometryProvider geometry;
    geometry.sections = sections;
    HardwareTraversal traversal;
    traversal.tlas = tlas;
    traversal.geometry = geometry;
    traversal.instanceMask = HARDWARE_INSTANCE_MASK_WORLD;
    volumetricMarchEntry(traversal, dispatchThreadID, outDepth, accumImage, frame, pc);
}
