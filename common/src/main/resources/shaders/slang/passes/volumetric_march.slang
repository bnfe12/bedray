// Legacy-section binding adapter for the shared volumetric march/store.
// Native GeometrySection tables must use volumetric_march_scene instead.
public module volumetric_march_pass;

import "contracts/frame.slang";
import "contracts/geometry.slang";
import "light/volumetric_march_entry.slang";
import "traversal/legacy_section_traversal.slang";

[[vk::binding(0, 0)]] RaytracingAccelerationStructure tlas;
[[vk::image_format("r32f")]] [[vk::binding(12, 0)]] RWTexture2D<float> outDepth;
[[vk::image_format("rgba32f")]] [[vk::binding(11, 0)]] RWTexture2D<float4> accumImage;
[[vk::binding(2, 0)]] StructuredBuffer<LegacySection> sections;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;
[[vk::push_constant]] VolumetricMarchPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchThreadID : SV_DispatchThreadID)
{
    LegacySectionTraversal traversal;
    traversal.tlas = tlas;
    traversal.sections = sections;
    traversal.instanceMask = pc.instanceMask;
    volumetricMarchEntry(traversal, dispatchThreadID, outDepth, accumImage, frame, pc);
}
