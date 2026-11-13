// bedray.slang - curated public shader surface.
//
// Import this file for the typed Bedray shader API and traversal contracts.
//
// Module responsibilities and dependency direction: ARCHITECTURE.md.
// Typed RC trace host contract: radiance_cascades/RC_TRACE_API.md.

public module bedray;

// ---- Foundations -----------------------------------------------------------
__exported import sampling;
__exported import tonemap_curve;
__exported import "contracts/contracts.slang";

// ---- Geometry and traversal ------------------------------------------------
__exported import "voxel/voxel.slang";
__exported import "traversal/traversal.slang";
__exported import "traversal/voxel_traversal.slang";
__exported import "traversal/software_tracer.slang";
__exported import "traversal/hardware_traversal.slang";
__exported import "traversal/hybrid_traversal.slang";

// ---- Materials, lights and shading -----------------------------------------
__exported import "light/emitters.slang";
__exported import "light/sun.slang";
__exported import "light/sky.slang";
__exported import "surface/surface.slang";
__exported import "surface/visibility.slang";
__exported import "shading/microfacet.slang";
__exported import "shading/bsdf.slang";

// ---- Path tracing and ReSTIR -----------------------------------------------
__exported import "pathtrace/path_tracer.slang";
__exported import "restir/restir_di.slang";

// ---- Radiance Cascades (typed RCSegment atlas only) ------------------------
__exported import radiance_cascades;
__exported import "radiance_cascades/rc_atlas_layout.slang";
__exported import "radiance_cascades/interval_transport.slang";
__exported import "radiance_cascades/rc_trace_walk.slang";
