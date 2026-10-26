// bedray.slang — Root module. Re-exports all Bedray shader modules.
// Entry-point shaders (rt_path.slang, rt_rays.slang, etc.) import this
// single file to get access to everything.
//
// Bedray 2.0 modules live in subdirectories and are imported by explicit path.
// A bare `import voxel;` does not search subdirectories, so the quoted form is
// the contract that keeps the root buildable with one include path.

public module bedray;

__exported import types;
__exported import bindings;
__exported import sampling;
__exported import tonemap;
__exported import brdf;
__exported import ray_backend;
__exported import backend_query;
__exported import "contracts/contracts.slang";
__exported import "voxel/voxel.slang";
__exported import "traversal/traversal.slang";
__exported import "traversal/voxel_traversal.slang";
__exported import "traversal/hardware_traversal.slang";
__exported import "traversal/legacy_section_traversal.slang";
__exported import "traversal/hybrid_traversal.slang";
__exported import "restir/restir_di.slang";
__exported import "restir/direct_light.slang";
__exported import "radiance_cascades/radiance_cascades_store.slang";
__exported import radiance_cascades;
__exported import render_plan;
__exported import "light/emitters.slang";
__exported import "light/sun.slang";
__exported import "light/sky.slang";
__exported import "surface/surface.slang";
__exported import "surface/visibility.slang";
__exported import "shading/microfacet.slang";
__exported import "shading/bsdf.slang";
__exported import "pathtrace/path_tracer.slang";
// public import backend_ser;  // Tier 3 — uncomment when implemented

// Future modules (uncomment as they are ported):
// public import temporal;
// public import water;
// public import transparency;
// public import caustics;
// public import denoise_pack;
