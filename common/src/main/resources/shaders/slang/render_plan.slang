// Declarative render-plan contract shared by all Bedray 2.0 compute passes.
// The host turns a device capability survey plus a player preset into this
// compact value.  Passes consume capabilities, not vendor names or ad-hoc
// preprocessor switches, so features can be added without multiplying shader
// variants.

public module render_plan;

enum RenderWorldMode : uint {
    VoxelVanilla = 0u,
    VoxelHybrid = 1u,
    FullHardware = 2u,
};

enum RenderProfile : uint {
    Fast = 0u,
    Quality = 1u,
};

enum LightingAlgorithm : uint {
    Vanilla = 0u,
    ReSTIR_DI = 1u,
    ReSTIR_PT = 2u,
    PathTracing = 3u,
    RadianceCascades = 4u,
    ScreenSpace = 5u,
};

enum DenoiserMode : uint {
    Disabled = 0u,
    ATrous = 1u,
    SVGF = 2u,
    NRDDiffuse = 3u,
    NRDDiffuseSpecular = 4u,
    DLSSRayReconstruction = 5u,
};

enum TraversalMode : uint {
    BitmaskHDDA = 0u,
    Hybrid = 1u,
    HardwareTLAS = 2u,
};

static const uint CAPABILITY_HARDWARE_RT = 1u << 0u;
static const uint CAPABILITY_SHADER_EXECUTION_REORDERING = 1u << 1u;
static const uint CAPABILITY_OPACITY_MICROMAPS = 1u << 2u;
static const uint CAPABILITY_DLSS_RR = 1u << 3u;
static const uint CAPABILITY_FRAME_GENERATION = 1u << 4u;

struct RenderPlan {
    RenderWorldMode worldMode;
    RenderProfile profile;
    LightingAlgorithm direct;
    LightingAlgorithm indirect;
    LightingAlgorithm reflections;
    LightingAlgorithm transmission;
    TraversalMode traversal;
    DenoiserMode denoiser;
    uint capabilityMask;
    uint maxPathBounces;
    bool temporalReuse;
};

bool hasCapability(RenderPlan plan, uint capability) {
    return (plan.capabilityMask & capability) != 0u;
}

RenderPlan defaultRenderPlan(RenderWorldMode mode, RenderProfile profile, uint capabilities) {
    RenderPlan plan;
    plan.worldMode = mode;
    plan.profile = profile;
    plan.capabilityMask = capabilities;
    plan.temporalReuse = true;
    plan.direct = LightingAlgorithm.ReSTIR_DI;
    plan.indirect = LightingAlgorithm.RadianceCascades;
    plan.reflections = LightingAlgorithm.PathTracing;
    plan.transmission = LightingAlgorithm.PathTracing;
    plan.maxPathBounces = profile == RenderProfile.Fast ? 1u : 2u;
    plan.denoiser = DenoiserMode.NRDDiffuse;
    plan.traversal = TraversalMode.BitmaskHDDA;

    if (mode == RenderWorldMode.VoxelVanilla) {
        plan.direct = profile == RenderProfile.Fast ? LightingAlgorithm.Vanilla : LightingAlgorithm.ReSTIR_DI;
        plan.reflections = profile == RenderProfile.Fast ? LightingAlgorithm.ScreenSpace : LightingAlgorithm.PathTracing;
        plan.traversal = TraversalMode.BitmaskHDDA;
        plan.denoiser = profile == RenderProfile.Fast ? DenoiserMode.Disabled : DenoiserMode.ATrous;
        return plan;
    }

    if (mode == RenderWorldMode.VoxelHybrid) {
        plan.traversal = TraversalMode.Hybrid;
        plan.reflections = profile == RenderProfile.Fast ? LightingAlgorithm.PathTracing : LightingAlgorithm.ReSTIR_PT;
        plan.denoiser = profile == RenderProfile.Fast ? DenoiserMode.NRDDiffuse : DenoiserMode.NRDDiffuseSpecular;
        return plan;
    }

    plan.traversal = TraversalMode.HardwareTLAS;
    plan.maxPathBounces = profile == RenderProfile.Fast ? 3u : 6u;
    if (profile == RenderProfile.Quality &&
        hasCapability(plan, CAPABILITY_SHADER_EXECUTION_REORDERING) &&
        hasCapability(plan, CAPABILITY_OPACITY_MICROMAPS)) {
        plan.indirect = LightingAlgorithm.ReSTIR_PT;
        plan.reflections = LightingAlgorithm.ReSTIR_PT;
        plan.denoiser = hasCapability(plan, CAPABILITY_DLSS_RR)
            ? DenoiserMode.DLSSRayReconstruction : DenoiserMode.NRDDiffuseSpecular;
    } else {
        plan.denoiser = DenoiserMode.NRDDiffuseSpecular;
    }
    return plan;
}
