// bedray.slang - curated public shader surface.
//
// Import this file when a pass wants the current, typed Bedray 2.0 API.
// Legacy compatibility is deliberately not exported here:
//
//   types.slang / bindings.slang / ray_backend.slang / backend_query.slang
//                                 legacy 1:1 scaffold and global bindings
//   LegacySectionTraversal        traversal/legacy_section_traversal.slang
//   float4 Radiance Cascades      radiance_cascades/radiance_cascades_store.slang
//
// A host that still needs a legacy path imports bedray_legacy.slang explicitly,
// or imports the concrete module.  This keeps a new pass from accidentally
// binding a legacy record or a global descriptor to a typed pass.
//
// Module responsibilities and dependency direction: ARCHITECTURE.md.
// Typed RC trace host contract: radiance_cascades/RC_TRACE_API.md.

public module bedray;

// ---- Foundations -----------------------------------------------------------
__exported import sampling;
__exported import tonemap_curve;
__exported import brdf;
__exported import "contracts/contracts.slang";
__exported import render_plan;

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
__exported import "restir/direct_light.slang";

// ---- Radiance Cascades (typed RCSegment atlas only) ------------------------
__exported import radiance_cascades;
__exported import "radiance_cascades/rc_atlas_layout.slang";
__exported import "radiance_cascades/interval_transport.slang";
__exported import "radiance_cascades/segment_store.slang";
__exported import "radiance_cascades/rc_trace.slang";
