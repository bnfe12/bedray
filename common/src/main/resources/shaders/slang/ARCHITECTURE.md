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
| concrete module | a pass that wants an explicit, minimal dependency set | e.g. `radiance_cascades/rc_trace_walk.slang` |

`passes/*.slang` is the AOT entry-point set.  A pass imports concrete modules
rather than the root when it can, because an explicit import list is the
dependency declaration a reviewer reads first.

Rules:

- `bedray.slang` exports typed contracts. Each entry point declares its own descriptors.
- A module re-exports the types that appear in its public signatures with
  `__exported import`.

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
- The RC interval walk uses the options and first-surface shading modules.
  The field transport uses the interval walk and the cubemap layout.

A module that needs a new dependency should add it at the lowest layer that
owns the concept, not import "upward" from a pass.

## Module map (current)

### Foundations

| Module | Responsibility | Must not own |
|---|---|---|
| `sampling.slang` | RNG, cosine/hemisphere sampling | material decisions |
| `tonemap_curve.slang` | peak-channel tone curve | exposure history |
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
| `traversal/scene_traversal.slang` | scene TLAS, native 64-byte geometry sections, planar attributes and entity capture |
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

### Radiance Cascades

| Module | Responsibility |
|---|---|
| `radiance_cascades.slang` | cascade/probe/ray addressing, Fibonacci directions, dispatch decode |
| `radiance_cascades/rc_atlas_layout.slang` | shared linear atlas addressing; record-format neutral |
| `radiance_cascades/interval_transport.slang` | `RCSegment`, validity flags, front-to-back composition |
| `radiance_cascades/rc_trace_options.slang` | options, diagnostics, result, entry status, environment policy |
| `radiance_cascades/rc_trace_shade.slang` | first-surface emission + NEE |
| `radiance_cascades/rc_trace_walk.slang` | segment finalization and the interval walk |
| `radiance_cascades/field_mapping.slang` | finite-window cubemap field coordinates and addresses |
| `radiance_cascades/field_transport.slang` | trace, reconstruct, and blend field records |
| `radiance_cascades/field_gather.slang` | diffuse continuation gather for the path tracer |

`RCSegment` is pinned in the Gradle `STRUCT_LAYOUTS` table at 32 bytes with
offsets 0/12/16/28.  The host still has to verify the reflected descriptor
stride; the table only fails the build when the shader declaration drifts.

`passes/rc_field_build_scene.slang` builds the typed field.
`passes/rc_path_trace_scene.slang` samples it during primary diffuse continuation.

An `RCSegment` is 32 bytes and stores
`(radiance.rgb, confidence, transmittance.rgb, flags)`.

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

## RC field contract

`traceCascadeRay` returns near, far and composed transport records. It receives
the traversal, light and environment samplers explicitly. The field builder
owns the typed field writes. The native path-trace group owns the descriptor
ranges, level dispatches, barriers and previous-field lifetime.
See the [field reconstruction plan](../../../../../../docs/RC_RECONSTRUCTION_PLAN.md).

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

1. Use `field_mapping.slang` to validate field metadata and address records.
2. Read whole `RCSegment` records and check their validity.
3. Compose intervals with `rcComposeSegments`. Confidence is status metadata.

### Add a pass

1. Create one file under `passes/` with one `main` entry point.
2. Import concrete modules whose symbols it uses.  A plain `import` is not
   re-exported; rely only on a module's `__exported` surface or import the
   contract module that owns a type.
3. Declare every binding explicitly in the pass.
4. Add the file to the compile sweep and record the result in
   [`docs/VERIFICATION_LOG.md`](../../../../../../docs/VERIFICATION_LOG.md).

### Add or change a record format

1. Do not extend `RCSegment` in place if the reflected stride would change
   without the host.
2. Add a new record module and a new store module; keep the layout module
   record-neutral.
3. Add a compile harness for the new format and document its binding contract.

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
| `verify_bedray_roots.slang` | public `bedray.slang` import and typed RC transport |
| `verify_scene_tracer.slang` | `HybridTraversal` and mock backends |
| `verify_water.slang` | both slope overloads, wave normal on top/underside/vertical faces, biome tint, fluid classification, medium extinction/in-scatter/boundary rules, the refracted beam and its focus bounds |

Evidence levels: L0 source review, L1 shader compile, L2 native unit/ABI
tests, L3 shader execution on a GPU, L4 live frame, L5 visual comparison.  This
document and the RC docs only claim L0/L1 unless a log says otherwise.
