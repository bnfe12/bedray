// Resource-only adapter for the vendor HLSL macros. There is one combined
// sampler binding; the empty sampler token is not a Vulkan descriptor.
struct NisSamplerToken {};
static const NisSamplerToken samplerLinearClamp = {};
struct NisInputAdapter {
    float4 SampleLevel(NisSamplerToken ignored, float2 uv, float level) { return spatialInput.SampleLevel(uv, level); }
    float4 GatherRed(NisSamplerToken ignored, float2 uv) { return spatialInput.GatherRed(uv); }
    float4 GatherGreen(NisSamplerToken ignored, float2 uv) { return spatialInput.GatherGreen(uv); }
    float4 GatherBlue(NisSamplerToken ignored, float2 uv) { return spatialInput.GatherBlue(uv); }
};
struct NisScaleAdapter { __subscript(int2 p) -> float4 { get { return spatialNisScale[p.y * 2 + p.x]; } } }
struct NisUsmAdapter { __subscript(int2 p) -> float4 { get { return spatialNisUsm[p.y * 2 + p.x]; } } }
struct NisOutputAdapter {
    __subscript(uint2 p) -> float4 {
        set {
            uint2 size; spatialOutput.GetDimensions(size.x, size.y);
            if (all(p < size)) spatialOutput[p] = newValue;
        }
    }
};
static NisInputAdapter in_texture;
static NisScaleAdapter coef_scaler;
static NisUsmAdapter coef_usm;
static NisOutputAdapter out_texture;
