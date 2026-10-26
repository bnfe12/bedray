// bindings.slang — Vulkan descriptor set bindings for the Bedray RT pipeline.
// Ported 1:1 from rt_common_core.glsl (descriptor declarations).
// Uses Slang [[vk::binding(N, S)]] attributes instead of GLSL layout().

module bindings;

import types;

// ---- Set 0: Scene data ----

[[vk::binding(0, 0)]]
RaytracingAccelerationStructure tlas;

[[vk::binding(1, 0)]]
RWTexture2D<float4> outImage;

[[vk::binding(12, 0)]]
RWTexture2D<float> outDepth;

[[vk::binding(2, 0)]]
StructuredBuffer<Section> sections;

[[vk::binding(3, 0)]]
Sampler2D atlas;            // block texture atlas

[[vk::binding(9, 0)]]
Sampler2D matAtlas;          // LabPBR material atlas (_s): r=roughness, g=f0, b=emission, a=source

[[vk::binding(10, 0)]]
Sampler2D nrmAtlas;          // LabPBR normal atlas (_n): rg=normal XY, b=AO, a=height

[[vk::binding(15, 0)]]
Sampler2D lightmap;          // vanilla 16x16 lightmap (U=block, V=sky)

// ---- G-buffer for NRD denoiser (conditional) ----
// These are always declared but only written when the denoiser is active.
// The Java side sets DENOISER_ACTIVE via specialization constant.

[[vk::binding(4, 0)]]
RWTexture2D<float> gbViewZ;          // view-space Z (negative in front), sky = +1e6

[[vk::binding(5, 0)]]
RWTexture2D<float4> gbNormalRough;   // xyz world normal, w linear roughness

[[vk::binding(6, 0)]]
RWTexture2D<float4> gbDiffuse;       // rgb noisy diffuse radiance * albedo, a hit distance

[[vk::binding(7, 0)]]
RWTexture2D<float4> gbAlbedo;        // rgb diffuse albedo guide, a = 1 on geometry

[[vk::binding(8, 0)]]
RWTexture2D<float4> gbDirect;        // rgb noise-free direct term (added after denoiser)

[[vk::binding(13, 0)]]
RWTexture2D<float4> gbSpecular;      // rgb noisy specular radiance, a hit distance

[[vk::binding(14, 0)]]
RWTexture2D<float4> gbSpecAlbedo;    // rgb specular reflectance F0 guide

// ---- Accumulation image (path tracer) ----

[[vk::binding(11, 0)]]
RWTexture2D<float4> accumImage;      // rgb running mean, a sample count

// ---- Per-frame statistics buffer (push constant address) ----
// In GLSL this was a buffer_reference accessed via push constants.
// In Slang we pass it as a StructuredBuffer bound at a known slot,
// or via push constants with a pointer. Using a structured buffer here.

struct FrameStats {
    uint   hits;
    uint   samples;
    uint   lumLogSum;
    uint   histAccepted;
    float4 camRel;
    uint64_t emitterAddr;
    uint   emitterCount;
    float  exposure;
    float  giDist;
    float  emisDist;
    float  aoRadius;
    float  autoNormalStrength;
    float  sunRadius;
    float  historyCap;
    float4x4 prevViewProj;
    float4 camDelta;
    float  fogDensity;
    float  fogInscattering;
    float  fogDistance;
    uint   fogMode;
    float  fogDistanceScale;
    uint   entityTris;
    uint64_t entPosAddr;
    uint64_t entIdxAddr;
    uint64_t entAttrAddr;
    uint64_t entPrevAddr;
    uint64_t entAtlasAddr;
    uint64_t entRectAddr;
    uint64_t motionAddr;
    uint   entAtlasSize;
    float  skyCamY;
    float2 cameraJitter;
    // Sky state (offset 256)
    float4 skySunDir;
    float4 skyMoonDir;
    float4 skyFogColor;
    float4 skySunset;
    float4 skyCloudColor;
    float4 skyCloudPos;
    float4 skyMisc;
    uint64_t skyTexAddr;
    uint   skyTexFlags;
    float  skyTilt;
    float4 cameraBobOrigin;
    float4 cameraForward;
    // Fog state (offset 416) and reservoir addresses follow
    uint64_t reservoirAddr;
    uint64_t reservoirAuxAddr;
};

// Push constants — matches RtPipeline.PushConstants on the Java side byte-for-byte (128 bytes total).
struct PushConstants {
    float4x4 invViewProj; // 0..63: clip -> camera-relative world (no jitter)
    uint64_t statsAddr;   // 64..71: device address of FrameStats
    uint     modeBits;    // 72..75: view | aoSamples << 8 | flipY << 16 | tonemap << 17 | accumCount << 18
    uint     frame;       // 76..79: frame counter
    float4   keyDir;      // 80..95: xyz: towards sun/moon, w: day factor 0..1
    float4   keyColor;    // 96..111: rgb: key light colour; w: flags (uint bits as float)
    float4   skyColor;    // 112..127: rgb: zenith colour; w: params2 (uint bits as float)
};

[[vk::push_constant]]
PushConstants pc;

// ---- Flag bits (matches RtPipeline.FLAG_*) ----

static const uint FLAG_DENOISER     = 0x0001u;
static const uint FLAG_ACCUMULATE   = 0x0002u;
static const uint FLAG_SHADOWS      = 0x0004u;
static const uint FLAG_AO           = 0x0008u;
static const uint FLAG_EMISSIVE     = 0x0010u;
static const uint FLAG_GI           = 0x0020u;
static const uint FLAG_REFLECTIONS  = 0x0040u;
static const uint FLAG_AUTO_EXPOSE  = 0x0080u;
static const uint FLAG_BLOOM        = 0x0100u;
static const uint FLAG_FOG          = 0x0200u;
static const uint FLAG_CAUSTICS     = 0x0400u;

bool hasFlag(uint flag) {
    return (asuint(pc.keyColor.w) & flag) != 0u;
}

uint params2() {
    return asuint(pc.skyColor.w);
}

// ---- Utility: read FrameStats from device address ----
// The Java side writes FrameStats at the device address in pc.statsAddr.
// Slang accesses it via buffer_device_address (Vulkan extension).

extension FrameStats {
    static FrameStats load() {
        // Buffer device address access — compiled to SPIR-V OpAccessChain
        // with PhysicalStorageBuffer. The Java side ensures alignment.
        return *((FrameStats*)pc.statsAddr);
    }
}
