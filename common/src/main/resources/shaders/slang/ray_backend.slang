// ray_backend.slang — Abstract interface for ray traversal backends.
// This is the core of the GPU-tier system: the path tracer and ray effects
// are written against this interface, and the correct backend is selected
// at compile time based on the target GPU.
//
// Tier 1/2 (all GPUs): backend_query.slang — uses VK_KHR_ray_query (inline RT)
// Tier 3 (RTX 40/50):  backend_ser.slang   — uses RT pipeline + SER (future)

public module ray_backend;

import types;

// Every ray backend must implement these four operations.
interface IRayBackend {
    // Returns 1.0 if nothing blocks the ray, 0.0 if something does.
    // Uses terminate-on-first-hit for shadow rays.
    float traceVisibility(float3 origin, float3 dir, float tmax);

    // Returns the distance to the nearest opaque/cutout hit, or tmax on miss.
    // Translucent surfaces are transparent to this query.
    float traceDist(float3 origin, float3 dir, float tmax);

    // Traces a ray and returns full hit information for shading.
    HitInfo traceClosest(float3 origin, float3 dir, float tmax);

    // Returns RGB transmittance along a shadow ray through transparent surfaces.
    float3 traceTransmittance(float3 origin, float3 dir, float tmax);
};
