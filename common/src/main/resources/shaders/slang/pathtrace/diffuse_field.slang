// Optional finite-window diffuse continuation. The ordinary path uses no field.
public module path_diffuse_field;
__exported import "contracts/light.slang";
__exported import "light/sky.slang";
__exported import "surface/surface.slang";
__exported import "traversal/traversal.slang";

static const uint DIFFUSE_FIELD_OUTSIDE = 0u;
static const uint DIFFUSE_FIELD_VALID = 1u;
static const uint DIFFUSE_FIELD_INVALID = 2u;
struct DiffuseFieldSample {
    float3 cosineMean; // irradiance / pi; no albedo, BSDF branch weight or exposure
    uint status;
    bool terminalKnown;
};
interface IPathDiffuseField {
    bool active();
    DiffuseFieldSample evaluate<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment, MaterialAtlas atlas, BlockMaterialTable table,
        float3 position, float3 normal, MaterialSamplingOptions materialOptions,
        float rayBias, float maxRadiance, inout uint seed);
}
struct NoPathDiffuseField : IPathDiffuseField {
    bool active() { return false; }
    DiffuseFieldSample evaluate<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment, MaterialAtlas atlas, BlockMaterialTable table,
        float3 position, float3 normal, MaterialSamplingOptions materialOptions,
        float rayBias, float maxRadiance, inout uint seed) {
        DiffuseFieldSample result;
        result.cosineMean = float3(0.0); result.status = DIFFUSE_FIELD_OUTSIDE;
        result.terminalKnown = false; return result;
    }
}
