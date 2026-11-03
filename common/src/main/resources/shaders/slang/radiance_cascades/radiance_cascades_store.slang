// Compatibility facade for the legacy float4 Radiance Cascades atlas.
//
// New code must import the typed path instead:
//
//   rc_atlas_layout.slang    shared atlas addressing
//   interval_transport.slang RCSegment and composition
//   segment_store.slang      typed record access and probe resolve
//
// This file exists so an older entry pass that still imports
// "radiance_cascades/radiance_cascades_store.slang" keeps compiling.  It
// exposes only the legacy float4 API.  The resolve functions are named
// rcLegacyProbeIrradiance / rcLegacyProbeComplete; the typed resolve keeps the
// unqualified rcResolveProbe / rcProbeComplete names in probe_resolve.slang.
//
// The default bedray.slang root does not export this module.  A host that
// deliberately keeps the old atlas imports bedray_legacy.slang or this path.

public module radiance_cascades_store;

__exported import "radiance_cascades/rc_atlas_layout.slang";
__exported import "radiance_cascades/legacy_float4_store.slang";
