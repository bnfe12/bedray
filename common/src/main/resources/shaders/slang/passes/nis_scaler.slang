// NIS v1.0.3 fp32 AOT host. Vendor NVScaler implementation is included unchanged.
#define NIS_HLSL 1
#define NIS_HLSL_6_2 0
#define NIS_GLSL 0
#define NIS_SCALER 1
#define NIS_USE_HALF_PRECISION 0
#define NIS_BLOCK_WIDTH 32
#define NIS_BLOCK_HEIGHT 24
#define NIS_THREAD_GROUP_SIZE 128
[[vk::push_constant]] cbuffer SpatialNisPush {
    float kDetectRatio;
    float kDetectThres;
    float kMinContrastRatio;
    float kRatioNorm;

    float kContrastBoost;
    float kEps;
    float kSharpStartY;
    float kSharpScaleY;

    float kSharpStrengthMin;
    float kSharpStrengthScale;
    float kSharpLimitMin;
    float kSharpLimitScale;

    float kScaleX;
    float kScaleY;

    float kDstNormX;
    float kDstNormY;
    float kSrcNormX;
    float kSrcNormY;

    uint kInputViewportOriginX;
    uint kInputViewportOriginY;
    uint kInputViewportWidth;
    uint kInputViewportHeight;

    uint kOutputViewportOriginX;
    uint kOutputViewportOriginY;
    uint kOutputViewportWidth;
    uint kOutputViewportHeight;

    float reserved0;
    float reserved1;
};
[[vk::binding(0, 0)]] Sampler2D spatialInput;
[[vk::image_format("rgba8")]] [[vk::binding(1, 0)]] RWTexture2D<float4> spatialOutput;
#include "../spatial/nis_coefficients.slang"
#include "../spatial/nis_combined_adapter.slang"
#include "../../third_party/nis/NIS_Scaler.h"
[numthreads(128, 1, 1)]
void main(uint3 group : SV_GroupID, uint thread : SV_GroupIndex) {
    NVScaler(group.xy, thread);
}
