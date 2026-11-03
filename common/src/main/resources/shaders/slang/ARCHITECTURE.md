# Bedray Slang shader architecture

This document is the entry point for a developer who needs to add a pass, a
traversal backend, a light sampler or a Radiance Cascades consumer.  It
describes the current source layout, the allowed dependency direction and the
public contracts.  It does not claim runtime validation: see
[Verification](#verification) for the evidence levels used in this repository.

## Reading order

1. This file: module map and rules.
2. [`radiance_cascades/RC_TRACE_API.md`](radiance_cascades/RC_TRACE_API.md):
   typed Radiance Cascades host contract and entry points.
3. [`radiance_cascades/INTERVAL_TRANSPORT.md`](radiance_cascades/INTERVAL_TRANSPORT.md):
   segment-local transport math.
4. [`contracts/contracts.slang`](contracts/contracts.slang): the shared data
   contracts (`Ray`, `SurfaceHit`, `LightSample`, `MaterialSample`, ...).

## Import roots

| Root | Use | Contains |
|---|---|---|
| `bedray.slang` | new pass or test that wants the current typed API | contracts, current traversal backends, lights, materials, path tracing, ReSTIR, typed Radiance Cascades |
| `bedray_legacy.slang` | opt-in compatibility only | `bedray.slang` plus `types`/`bindings`/`ray_backend`/`backend_query`, `LegacySectionTraversal`, the float4 RC atlas |
| concrete module | a pass that wants an explicit, minimal dependency set | e.g. `radiance_cascades/rc_trace.slang` |

`passes/*.slang` is the AOT entry-point set.  A pass imports concrete modules
rather than the root when it can, because an explicit import list is the
dependency declaration a reviewer reads first.

Rules:

- `bedray.slang` must not export a legacy record format or a legacy global
  binding.  A new pass must not be able to bind a `StructuredBuffer<float4>`
  RC atlas by accident.
- `bedray_legacy.slang` is allowed to re-export both roots.  A pass that uses it
  is explicitly requesting legacy behavior, and that import is visible in the
  source.
- A module re-exports the types that appear in its public signatures with
  `__exported import`.  Example: `segment_store.slang` re-exports
  `RCSegment` and `RadianceCascadeLayout`, so a caller can declare a typed
  atlas binding by importing only the store.

## Dependency direction

```
                       +----------------------+
                       | contracts/           |
                       | math, hit, light,    |
                       | material, scene      |
                       +----------+-----------+
                                  |
          +-----------------------+-----------------------+
          |                       |                       |
   +------v------+        +-------v-------+       +-------v-------+
   | traversal/  |        | surface/      |       | light/        |
   | backends    |        | decode, visib.|       | sun, sky,     |
   +------+------+        +-------+-------+       | emitters      |
          |                       |               +-------+-------+
          +-----------+-----------+-----------------------+
                      |
          +-----------v------------+
          | shading/, pathtrace/,  |
          | restir/, radiance_     |
          | cascades/              |
          +------------------------+
```

The arrows are one-way.  A lower layer never imports a higher layer:

- `contracts/` imports only math/geometry contracts.
- `traversal/` imports `contracts/`, never `surface/`, `shading/` or a pass.
- `surface/` imports `contracts/` and `traversal/entity_geometry` (data only).
- `radiance_cascades.slang` is standalone addressing: it defines its own layout
  records and imports no traversal, surface or binding module.
- `rc_atlas_layout` <- `interval_transport` <- `segment_record` <-
  `probe_resolve`; `segment_store` is a facade over the last two.
- `rc_trace_*` follows `options <- shade <- walk <- dispatch <- history`;
  `ownership` depends only on `options`.

A module that needs a new dependency should add it at the lowest layer that
owns the concept, not import "upward" from a pass.

## Module map (current)

### Foundations

| Module | Responsibility | Must not own |
|---|---|---|
| `types.slang` | legacy 1:1 GPU structs from the GLSL pipeline | new typed APIs |
| `bindings.slang` | legacy global descriptor declarations | a new pass's bindings |
| `sampling.slang` | RNG, cosine/hemisphere sampling | material decisions |
| `tonemap.slang` | legacy tone-map helpers | display pass binding policy |
| `tonemap_curve.slang` | peak-channel tone curve | exposure history |
| `brdf.slang` | aggregate shading import | traversal |
| `render_plan.slang` | render modes and capability masks | pipeline creation |
| `contracts/math.slang` | finite checks, luminance, frames, safe normalize | transport policy |
| `contracts/hit.slang` | `Ray`, `SurfaceHit`, `VisibilityResult`, `PathVertex` | backend hit structs |
| `contracts/light.slang` | `LightSample`, `LightReceiver`, `ILightSampler` | reservoir math |
| `contracts/material.slang` | `MaterialSample`, alpha modes, source flags | atlas sampling |
| `contracts/scene_tracer.slang` | `ISceneTracer` | traversal implementation |
| `contracts/frame.slang` | camera, sun, atmosphere, exposure | descriptor bindings |

### Geometry, material and lighting

| Module | Responsibility |
|---|---|
| `traversal/traversal.slang` | aggregate `ISceneTracer` export |
| `traversal/voxel_traversal.slang` | world-level voxel section DDA |
| `traversal/software_tracer.slang` | software voxel backend |
| `traversal/hardware_traversal.slang` | TLAS / ray-query backend |
| `traversal/hybrid_traversal.slang` | voxel near + hardware far |
| `traversal/legacy_section_traversal.slang` | legacy 64-byte section table + device-address arena (opt-in) |
| `traversal/entity_geometry.slang` | entity ring decode and atlas data |
| `surface/surface.slang` | `MaterialAtlas`, `BlockMaterialTable`, `decodeSurface` |
| `surface/water.slang` | wave field, fluid classification, biome tint, volumetric medium, the beam a light sends into the body and its caustic focus |
| `surface/visibility.slang` | filtered shadow walk (plain, medium-aware, and the two-leg refracted beam) and proof predicate |
| `shading/microfacet.slang` | GGX/Fresnel math |
| `shading/bsdf.slang` | diffuse/GGX/transmission evaluation |
| `light/sun.slang` | sun/moon sampler |
| `light/sky.slang` | `IEnvironmentSampler`, gradient dome, celestial discs |
| `light/emitters.slang` | emitter records and uniform sampler |
| `light/block_emitters.slang` | block-emitter adapter |
| `pathtrace/path_tracer.slang` | iterative path loop, NEE, Russian roulette, water medium state |
| `restir/restir_di.slang` | DI reservoir storage and merges |
| `restir/direct_light.slang` | compatibility re-export of `light/emitters.slang` |

### Radiance Cascades

| Module | Responsibility |
|---|---|
| `radiance_cascades.slang` | cascade/probe/ray addressing, Fibonacci directions, dispatch decode |
| `radiance_cascades/rc_atlas_layout.slang` | shared linear atlas addressing; record-format neutral |
| `radiance_cascades/interval_transport.slang` | `RCSegment`, validity flags, front-to-back composition |
| `radiance_cascades/segment_record.slang` | typed whole-record read/write, sources, ray composition |
| `radiance_cascades/probe_resolve.slang` | typed probe radiance/completeness resolve |
| `radiance_cascades/segment_store.slang` | facade re-exporting `segment_record` + `probe_resolve` |
| `radiance_cascades/rc_trace_options.slang` | options, diagnostics, result, entry status, environment policy |
| `radiance_cascades/rc_trace_shade.slang` | first-surface emission + NEE |
| `radiance_cascades/rc_trace_walk.slang` | segment finalization and the interval walk |
| `radiance_cascades/rc_trace_dispatch.slang` | store, probe and dispatch trace entries |
| `radiance_cascades/rc_trace_history.slang` | provisional temporal history (opt-in) |
| `radiance_cascades/rc_trace_ownership.slang` | distance-band ownership proposal (opt-in) |
| `radiance_cascades/rc_trace.slang` | public facade over the six `rc_trace_*` modules |
| `radiance_cascades/legacy_float4_store.slang` | **legacy** float4 record, confidence-weighted resolve |
| `radiance_cascades/radiance_cascades_store.slang` | **legacy** compatibility facade over the float4 store |
| `radiance_cascades/rc_trace_segments.slang` | **obsolete** experiment; not exported, not in the compile set |

`segment_record.slang` is generic over `IRCSegmentSource`; the two concrete
sources are `RCReadOnlySegmentSource` and `RCReadWriteSegmentSource`, so the
read-only and writable buffer flavours share one implementation.  A pass that
owns the writable atlas need not declare a second read-only binding just to
compose a ray.

`RCSegment` is pinned in the Gradle `STRUCT_LAYOUTS` table at 32 bytes with
offsets 0/12/16/28.  The host still has to verify the reflected descriptor
stride; the table only fails the build when the shader declaration drifts.

The first typed-RC consumer is `passes/rc_probe_trace.slang`: one dispatch item
decodes to one probe ray and calls `traceCascadeDispatch`.  It is a compile-only
producer example, not a wired host pass.

The typed and legacy atlases are different formats.  A `float4` record is
`(radiance.rgb, confidence)`; an `RCSegment` is 32 bytes
`(radiance.rgb, confidence, transmittance.rgb, flags)`.  They must never be
bound to the same descriptor or passed to each other's resolve.

## Public API conventions

- One module owns one concept.  A module that grows a second responsibility
  should split, not add a mode switch.
- Public entry points state their contract in the comment above the function:
  what is written, what is not, and which failure leaves the caller's state
  untouched.
- A generic function takes backends by value (`T traversal`, `L lights`,
  `E environment`) and never reaches for a global binding.  The entry pass
  owns the bindings.
- A function that cannot produce a trusted value returns `valid = false` or an
  unresolved record.  It never fabricates black.
- A record's flags and a result's diagnostic bits are separate.  A stored
  `RCSegment` carries only `RCSegment.flags`; trace events live in
  `RCTraceResult.nearEvents/farEvents` and do not survive the store.
- `static const` flag vocabularies live with their owner
  (`interval_transport.slang` for segment flags, `rc_trace_options.slang` for
  trace events/options).  A consumer imports the owner instead of redefining a
  bit.

## Typed RC trace host contract

The full description is in
[`radiance_cascades/RC_TRACE_API.md`](radiance_cascades/RC_TRACE_API.md).  The
short version:

| Entry | Writes | Returns |
|---|---|---|
| `traceCascadeRay` | nothing | `RCTraceResult` with near/far/composed |
| `rcTraceAndStoreRay` | two `RCSegment` records | result plus `stored` |
| `traceCascadeProbe` | one probe's ray records | `RCTraceEntryStatus` counters |
| `traceCascadeDispatch` | one decoded dispatch item | `RCTraceEntryStatus` counters |
| `traceCascadeProbeBlended` | fresh records then provisional history blend | `RCTraceBlendedStatus` |
| `traceCascadeDispatchBlended` | fresh record then provisional history blend | `RCTraceBlendedStatus` |

`RCTraceEntryStatus.code` says whether the entry arguments were accepted;
`stored` counts address success, not ray validity.  `RCTraceBlendedStatus`
separates the fresh trace status from the temporal acceptance/rejection
buckets.  A zero store-failure count is not proof that the atlas is usable.

Host responsibilities that the shader cannot check:

1. Allocate at least `radianceCascadeLayout().totalRecords` `RCSegment`
   elements; the descriptor range count is `capacity`, not the allocation size.
2. Validate the reflected record stride (32 bytes) and every descriptor range
   before dispatch.
3. Bind a real `ILightSampler` and `IEnvironmentSampler`.  There is no neutral
   sampler that turns a missing binding into black.
4. Use `RCTraceOptions` with `rayBias > 0`, finite distances and
   `flags == 0`.
5. Environment is `Disabled` by default.  To use `TerminalCascade`, assert a
   positive `worldBoundaryDistance` and make the terminal cascade's far window
   reach it.
6. Insert a pass barrier between the trace write and any resolve read.  The two
   segment writes are not atomic as a pair.
7. For provisional history, bind distinct current/history buffers, supply the
   previous origin/rotation/scene revision, and treat the blend as a heuristic.

## Extension checklist

### Add a traversal backend

1. Implement `ISceneTracer` in `traversal/`.
2. Return `missHit(ray.tMax)` on a miss; never return a hit outside
   `[ray.tMin, ray.tMax]`.
3. Keep material alpha/cutout decisions out of the backend.
4. Add a compile harness that instantiates the backend with a mock pass.
5. Add it to `bedray.slang` only if it is a current, supported backend.

### Add a light or environment sampler

1. Implement `ILightSampler` or `IEnvironmentSampler`.
2. Make failure explicit: `valid = false` for "no sample"; malformed finite
   data must not become a zero-valued sample.
3. Export the concrete sampler module; do not add a global binding inside it.

### Add an RC consumer

1. Import `radiance_cascades/segment_store.slang` (typed) and
   `radiance_cascades/rc_atlas_layout.slang`.
2. Read whole records with `rcReadSegment` or `rcResolveRay`.
3. Compose only with `rcComposeSegments`.
4. Skip invalid records; do not average confidence.
5. Use `rcResolveProbe` when a probe value is needed, and check `complete` (or
   call `rcProbeComplete`) before treating it as trustworthy.

### Add a pass

1. Create one file under `passes/` with one `main` entry point.
2. Import concrete modules whose symbols it uses.  A plain `import` is not
   re-exported; rely only on a module's `__exported` surface or import the
   contract module that owns a type.
3. Declare every binding explicitly; do not rely on `bindings.slang`.
4. Add the file to the compile sweep and record the result in
   [`docs/VERIFICATION_LOG.md`](../../../../../../docs/VERIFICATION_LOG.md).

### Add or change a record format

1. Do not extend `RCSegment` in place if the reflected stride would change
   without the host.
2. Add a new record module and a new store module; keep the layout module
   record-neutral.
3. Mark the old resolve as legacy and keep its name distinct (`rcLegacy*`).
4. Add a typed and a legacy compile harness, and document the incompatible
   binding.

## Legacy policy

- Legacy code stays source-visible for compatibility and review, but it is not
  part of the default root and not part of the typed API.
- A legacy function that could be confused with a typed one is renamed with a
  `Legacy` prefix (`rcLegacyProbeIrradiance`, `rcLegacyProbeComplete`).
- `radiance_cascades_store.slang` is a compatibility facade, not an
  implementation.  New code imports `segment_store.slang`.
- `rc_trace_segments.slang` is obsolete and must not be revived without a new
  review; it is excluded from the compile sweep.

## Verification

Compile-only evidence is L1 in this repository.  A successful `slangc` run
proves that the modules, re-exports and generic constraints are coherent; it
does not prove a GPU dispatch, a reflected layout or a visual result.

```powershell
$slangc = "path\to\slangc.exe"
$root = "common/src/main/resources/shaders/slang"
Get-ChildItem "$root/passes" -Filter *.slang | ForEach-Object {
    & $slangc -target spirv -profile cs_6_5 -entry main `
        -matrix-layout-column-major -I $root -o "$env:TEMP\$($_.BaseName).spv" $_.FullName
}
Get-ChildItem "common/src/test/resources/shaders/slang" -Filter *.slang | ForEach-Object {
    & $slangc -target spirv -profile cs_6_5 -entry main `
        -matrix-layout-column-major -I $root -o "$env:TEMP\$($_.BaseName).spv" $_.FullName
}
```

Current harnesses:

| Harness | Checks |
|---|---|
| `verify_rc_typed.slang` | typed store, both source flavours, `rcResolveProbe`, layout without the legacy store |
| `verify_rc_environment.slang` | environment opt-in and zero-boundary rejection |
| `verify_rc_trace_api.slang` | every public RC trace entry, options, history, ownership |
| `verify_bedray_roots.slang` | both public roots compile together; typed and legacy resolve names stay distinct |
| `verify_scene_tracer.slang` | `HybridTraversal` and mock backends |
| `verify_water.slang` | both slope overloads, wave normal on top/underside/vertical faces, biome tint, fluid classification, medium extinction/in-scatter/boundary rules, the refracted beam and its focus bounds |

Evidence levels: L0 source review, L1 shader compile, L2 native unit/ABI
tests, L3 shader execution on a GPU, L4 live frame, L5 visual comparison.  This
document and the RC docs only claim L0/L1 unless a log says otherwise.
