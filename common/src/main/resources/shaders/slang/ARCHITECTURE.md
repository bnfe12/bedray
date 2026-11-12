# Bedray Slang shader architecture

This is a source map, not a test-results log. Paths below are relative to this
shader directory unless stated otherwise. Import concrete modules; the pass's
imports and bindings are its dependency and resource declarations.

## Entry points and contracts

- `passes/*.slang` contains AOT entry points named `main`. Compute passes and
  `.raygen.slang` / `.ser.slang` variants have different compile requirements.
- `contracts/contracts.slang` re-exports shared math, frame, material, geometry,
  hit, history, light, scene-tracer and octahedral contracts.
- `contracts/scene_tracer.slang` owns `ISceneTracer`; `traversal/traversal.slang`
  re-exports that interface, not a bundle of concrete backends.
- `contracts/light.slang` owns `ILightSampler`; `light/sky.slang` owns
  `IEnvironmentSampler`. Resource-bearing implementations are supplied by callers.
- Plain `import` does not re-export a dependency. Import a symbol's owner or a
  module that explicitly exposes it with `__exported import`.

## Module map

| Area | Current responsibilities |
|---|---|
| `contracts/` | Shared records, interfaces, numerical helpers and triangle-intersection contracts |
| `traversal/hardware_traversal.slang` | Geometry provider and hardware ray-query traversal |
| `traversal/scene_traversal.slang` | Native scene traversal over terrain and entities |
| `traversal/legacy_section_traversal.slang` | **Live** section-table/device-address backend used directly by `passes/path_trace.slang` |
| `traversal/entity_geometry.slang`, `traversal/triangle_query.slang` | Entity decoding and common triangle-query helpers |
| `traversal/reordering_traversal.slang` | Reordering adapter for SER variants |
| `surface/surface.slang` | Material atlas/table, surface decoding and alpha/filter decisions |
| `surface/visibility.slang`, `surface/water.slang` | Filtered shadows, water surface/medium and refracted-light helpers |
| `shading/` | BSDF and microfacet math |
| `light/` | Sun, sky, block-emitter sampling and volumetrics; emitter storage/selection are separate modules |
| `pathtrace/` | Shared pass entry, path loop, denoiser signals and `IPathDiffuseField` |
| `restir/` | DI reservoirs, block-emitter temporal/spatial reuse and PT reuse stages |
| `experimental/restir_pt.slang` | PT reservoir implementation imported by live reuse code; directory name does not imply dead code |
| `radiance_cascades/` | Interval transport, single-ray walk, field mapping/reconstruction/history/gather |
| `nort/` | Non-ray-tracing geometry, materials, traversal, lighting, history and composition |
| `denoise/`, `history/`, `bloom/`, `post/` | Denoiser packing/depth, live velocity, bloom and output resolve |
| `sampling.slang`, `tonemap_curve.slang` | Sampling and tone-curve helpers |
| `spatial/` | Vendor-adapter/reference modules also retained by Java source tests |

The section-table backend is not dead compatibility scaffolding: keep it while
its passes use it. Backend selection belongs to the entry pass and host, not to
material decoding or a catch-all import root.

## Dependency boundaries

- Contracts depend on other contracts, not on renderer implementations.
- Traversal depends on contracts and traversal helpers. Material decoding and
  visibility are separate: `surface/surface.slang` uses entity data, while
  `surface/visibility.slang` uses the tracer interface.
- Shading, lighting, path tracing and reuse compose these lower-level pieces.
  Do not import a pass to reuse an algorithm; put that algorithm in its owner module.
- Passes declare descriptors and assemble backend/sampler objects. Generic
  algorithms receive those objects and policy as arguments.
- Keep invalid/unresolved observations distinct from valid zero radiance.
  Segment flags, trace-event diagnostics and path-result flags have different scopes.

## Radiance Cascades: actual producer and consumer

The live field path is:

```text
passes/rc_field_build_scene.slang
  -> radiance_cascades/field_transport.slang
     -> field_mapping.slang + interval_transport.slang + rc_trace_walk.slang
        -> rc_trace_shade.slang -> rc_trace_options.slang
passes/rc_path_trace_scene.slang
  -> radiance_cascades/field_gather.slang -> field_transport.slang
  -> pathtrace/path_trace_entry.slang -> pathtrace/path_tracer.slang
```

`rc_trace_walk.slang` exposes `traceCascadeRay`, returning near/far/composed
`RCSegment` values without storing them. The field builder writes one composed
tail per probe/angular cell, using `RCFieldConstants` and `rcFieldIndex`.
`RCPathDiffuseField` supplies the selected primary diffuse continuation.

`radiance_cascades.slang` still supplies `RadianceRay` and cascade helpers;
`radiance_cascades/rc_atlas_layout.slang` supplies two-interval addressing helpers.
That layout is **not** the field's cubemap-cell layout. Current field storage
uses direct typed buffer accesses, not a generic store/dispatch facade.

See [RC trace API](radiance_cascades/RC_TRACE_API.md) for callable entries,
bindings and host ownership, and [interval transport](radiance_cascades/INTERVAL_TRANSPORT.md)
for composition, reconstruction and validity rules.

## Verification entry points (not results)

Repository-root `gradle/slang.gradle` defines `compileSlang`, emits SPIR-V and
reflection manifests, and pins shared ABI records in `STRUCT_LAYOUTS`. It uses
`cs_6_5` for compute by default, `lib_6_5` plus ray-generation stage/direct SPIR-V
for ray-generation variants, and the invocation-reorder capability for SER.
A compute-only wildcard command is not a valid replacement for this selection.

Under `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/`:

- `slang/SlangDeadCodeTest.java`: host registration and pass/test import reachability.
- `slang/SlangPassLibraryTest.java`: generated AOT artifacts and manifests.
- `world/RtSlangRcFieldTest.java`: field source, host ordering and manifest contracts.
- `world/RtSlangRadianceTransportTest.java`, `world/RtRcFieldHistoryTest.java`:
  transport and history regressions; `rc/*Test.java`: CPU reference math.

The existing standalone entries in `common/src/test/resources/shaders/slang/`
are `verify_water.slang`, `verify_denoiser_signals.slang` and
`verify_triangle_intersection.slang`. Compilation alone does not execute their
checks or prove GPU behavior. This documentation update used source inspection;
no build, tests, GPU dispatch or visual validation was run.
