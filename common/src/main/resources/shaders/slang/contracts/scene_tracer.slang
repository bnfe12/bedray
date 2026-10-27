// Bedray 2.0 — ISceneTracer Static Polymorphism Contract.
//
// Unifies the 3 rendering modes (Software Voxel, Hybrid RT, Full Path Tracing)
// into a zero-overhead generic interface. Specializations are compiled AOT by Slang
// without runtime dispatch branches or shader variant explosion.

public module scene_tracer;

import "contracts/math.slang";
import "contracts/hit.slang";

public struct ShadowHit {
    public bool blocked;
    public float hitDistance;
};

public struct ReflectionHit {
    public bool hit;
    public float3 position;
    public float3 normal;
    public float3 albedo;
    public float roughness;
    public float hitDistance;
};

public interface ISceneTracer {
    // 1. Directional / Point shadow test (returns true if light is occluded)
    bool traceShadowRay(Ray ray, float maxDist);

    // 2. Specular reflection query (water, glass, mirrors)
    ReflectionHit traceReflectionRay(Ray ray, float maxDist);

    // 3. Short-range ambient occlusion query
    float traceAmbientOcclusion(Ray ray, float radius);
};
