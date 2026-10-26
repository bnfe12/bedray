# Follow-up for the ReSTIR PT agent

Continue Bedray alpha 0.2.0 in your existing isolated workspace
`C:\Users\andre\AppData\Local\Temp\opencode\ai-restir-pt`. Treat
`C:\BedRay` as read-only. Do not edit shared contracts, other agents' work,
the core, Java, or Gradle. Do not commit, push or deploy.

The user has suspended tests and builds until the complete migration is done.
Do not invoke `slangc`, build tools, harnesses, Minecraft or a GPU. Existing
SPIR-V, reflection and logs describe older revisions only.

## Required design and source corrections

1. Your notes explicitly state that reuse is biased, lacks suffix reconnection,
   skips environment visibility and can retain removed lights. The task requires
   actual path-space ReSTIR, not merely temporal/spatial copying of a suffix.
   Define the supported path classes, proposal distributions, target density,
   shift mapping, Jacobian, visibility and reservoir normalisation together.
   Implement only cases for which the estimator and support are justified;
   reject unsupported reuse instead of presenting it as unbiased.
2. Trace the first edge for reused environment paths. Environment radiance
   alone is not visibility evidence. Invalidate history when geometry, material
   or emitter revisions make its contribution stale; if required revision data
   is absent, document the exact contract addition instead of guessing.
3. Resolve DI/PT overlap by estimator ownership or a valid joint MIS strategy
   with densities evaluated for each sampled path. The current fallback sum
   explicitly double counts some shared contributions. Do not silently drop an
   entire PT suffix to fix a direct-light overlap. If the existing path record
   cannot separate the terms, report and specify the missing fields.
4. `SurfaceHit.worldPosition` currently wraps section origins at 1024 blocks in
   both backends. It is not a globally stable world-space identity. Do not use
   it as an unqualified reprojection or emitter identity; request an explicit
   unwrapped position/rebasing or stable identity contract from the owner.
5. Avoid a 304-byte per-pixel reservoir containing unused path vertices. Specify
   the minimum record required by the implemented shift mapping and measure its
   eventual reflection layout only when testing is authorized. Give the exact
   memory formula for resolution, buffers and frames in flight.
6. Do not rely on `HIT_FLAG_EMISSIVE` for hardware hits: traversal is currently
   material-agnostic. The owner must define emitter identity/emission after
   material decoding. Explain what is required from material and scene contracts.

Deliver revised Slang and notes in the same workspace. Mark any approximations
and blockers explicitly, include source-level worked examples for fresh/reused
DI overlap and environment occlusion, and report what remains unverified. Do
not run or update test artifacts and do not claim the revised source compiles.
