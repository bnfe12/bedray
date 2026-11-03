// bedray_legacy.slang - opt-in compatibility surface.
//
// Import this file only when a host still uses one of the legacy 2.0 paths:
//
//   * types.slang / bindings.slang / ray_backend.slang / backend_query.slang
//     are the 1:1 scaffold from the GLSL pipeline, including global descriptor
//     bindings.  They are kept as reference, not as a new-pass API.
//   * LegacySectionTraversal reads the 64-byte RtWorld section table and the
//     device-address geometry arena.  Current traversal backends live in
//     traversal/ and are exported by bedray.slang.
//   * The float4 Radiance Cascades atlas stores (radiance, confidence) and
//     resolves near/far by confidence.  It is not compatible with RCSegment,
//     rcComposeSegments or segment_store.slang.  Current typed RC code is
//     exported by bedray.slang through rc_trace.slang.
//
// Keeping this in a separate root makes the opt-in visible in the import list
// of a pass and keeps the default bedray.slang free of legacy record types.

public module bedray_legacy;

__exported import bedray;
__exported import types;
__exported import bindings;
__exported import ray_backend;
__exported import backend_query;
__exported import "traversal/legacy_section_traversal.slang";
__exported import "radiance_cascades/radiance_cascades_store.slang";
