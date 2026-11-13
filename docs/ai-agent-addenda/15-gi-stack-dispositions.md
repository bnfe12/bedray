# 15 — GI-stack dispositions and remaining-technology survey

Status (2026-11-12): deliverable for catalog steps "RR sharpness", "RC vs DDGI", "Path guiding"
and "Research overview". These are **dispositions and research**, not implementations. Every claim
below is either a verified file:line fact or explicitly labelled a proposal that needs a measurement.
No speculative code was written for any item here. Verification levels: L0 = code/design fact,
research = literature/feasibility only (no device number).

Hard rules honored: Vulkan-only (anything needing DirectX/DX12 work is out of scope); nothing is
"working" without L4; default off until numbers exist; the owner's forbidden-technology list is
respected literally.

---

## A. RR sharpness — do NOT wire (item 5, disposition)

**Verdict: not wired, by design. This is not a bug.**

Verified fact: `native/dlss_bridge/include/vkb_dlss.h:240` declares the Ray Reconstruction eval
sharpness field as

```c
float    sharpness;              /* reserved for API consistency (0.0) */
```

inside `VkbDlssRrEvalInfo` (sizeof == 968). The field exists only to keep the C ABI parallel with
the SuperSampling eval struct (`VkbDlssEvalInfo.sharpness`, `vkb_dlss.h:191`, which *is* live for
DLSS-SR). NVIDIA's Ray Reconstruction feature does not consume a sharpness scalar: RR is a trained
reconstruction model, and the NGX RR parameter block has no sharpness input. Passing a non-zero
value would be silently ignored — wiring a `dlss_rr_sharpness` slider would therefore be a control
that does nothing, which the settings honesty rule forbids.

**Disposition.** Leave `sharpness = 0.0` for RR (already the case). Do not add a settings row that
pretends to sharpen RR. If a sharpened look after RR is ever wanted, the correct mechanism is a
**separate post-reconstruction sharpen pass** (e.g. AMD CAS / RCAS, the same kernel already used for
`fsr_sharpness`, `SettingsCatalog.java:424`), applied to the RR output image. That pass is additive,
Vulkan-only (a single compute dispatch), and would be gated behind its own A/B measurement and its
own setting — never folded into the RR API. No such pass is implemented now because no measured need
exists; it is recorded here as the only legitimate route if the owner later asks for sharpening.

---

## B. RC vs DDGI and the forbidden cache family (item 7, disposition)

**Verdict: keep the existing finite radiance cache (RC) as the indirect-light estimator. The
owner-forbidden alternatives are not implemented and will not be.**

### What "RC" is here (verified)

RC is the current finite, camera-centred world-space indirect-light field. It returns
**irradiance / π** through the `IPathDiffuseField` contract
(`docs/ARCHITECTURE_REASSESSMENT.md:36`, `docs/NATIVE_1080P_60_AUDIT.md:27`). It is an irradiance
cache, not a directional radiance distribution and not a learned guide. ReSTIR DI/PT
(`RtSlangPathPass`, `GiStackPolicy`) is the sampling/reuse layer that sits on top of the traced
signal; RC is the cheap diffuse-bounce estimator. This is the GI approach the cheap stack uses.

### Owner-forbidden technologies (literal list, not implemented)

The owner has forbidden: **DDGI, NRC, SHaRC, CLAS, Mega Geometry, Brixelizer GI, surfel GI**.
None of these is implemented in this branch, and none will be added by this task. Reasons recorded
for the disposition (so the choice is auditable, not silent):

| Technology | Why it is out (besides the owner ban) |
|---|---|
| DDGI (dynamic diffuse GI probe volumes) | Greenfield probe-volume placement, border/overlap management, per-probe irradiance+visibility textures and a probe-update pass; large additive scene/memory contract for an unproven win on a voxel scene that already has a working irradiance field. |
| NRC (neural radiance caching) | Vendor/Tensor-Core candidate (`ARCHITECTURE_REASSESSMENT.md:49-50`); needs cooperative-matrix/tensor paths and a trained network — not a universal Vulkan 1.x baseline, and not the cheap stack. |
| SHaRC (spatial hash radiance cache) | Caches **outgoing radiance**, which cannot be substituted into the existing irradiance/π contract without redefining the sampled quantity and avoiding repeated material factors (`ARCHITECTURE_REASSESSMENT.md:36,46-48`). RTXGI vendor integration; ~160 MiB sizing example for 2^22 entries before other resources. |
| CLAS (cluster acceleration structures) | `VK_NV_cluster_acceleration_structure`, NV-only greenfield geometry representation; separate build/descriptor contracts. Not a GI estimator. |
| Mega Geometry | NV cluster/geometry pipeline, large additive ABI and driver-feature surface; not measured, not cheap. |
| Brixelizer GI | Vendor voxel-GI middleware; greenfield integration and memory model. |
| Surfel GI | Research surfel placement/importance pipeline; no production Vulkan baseline here. |

### Why RC is the chosen default

* It is the **already-working** estimator that satisfies the existing irradiance contract — no new
  sampled-quantity definition, no material-factor double-counting risk.
* Bounded, camera-centred memory that fits the 8 GiB profile; no probe-volume or hash-table
  lifetime authority to add.
* Vulkan-only and vendor-neutral: no Tensor-Core/cooperative-matrix dependency, no NV-only geometry
  extension, runs on the universal ray-query/raygen path.
* The cheap-stack goal is served by the measured wins already landed (BLAS compaction, raygen
  −7.8% gpu_rt, NRD REBLUR −18.4% gpu_rt) rather than by swapping the GI estimator for a greenfield
  cache whose benefit on this workload is unmeasured.

**Disposition.** RC retained. The forbidden family is documented as out-of-scope by owner directive
*and* by the engineering reasons above. If the owner ever lifts the ban, the reassessment doc's rule
still applies: compare one estimator at a time on the same captures and pick a single default — never
enable several caches together by default (`ARCHITECTURE_REASSESSMENT.md:36`).

---

## C. Path guiding — research/feasibility, default off (item 8)

**Verdict: research only. Not implemented. Default off. Implement only on a measured win.**

### What path guiding would require

Path guiding builds a **directional proposal PDF** for indirect light and samples it with MIS
against the BSDF, so paths are steered toward bright directions instead of white-noise hemisphere
sampling. Production forms include spatial-directional trees (SPWI), Gaussian-mixture/vMF caches,
and neural guides (RCPG). The current RC is irradiance-only and has **no directional proposal/product
PDF** (`NATIVE_1080P_60_AUDIT.md:27`: "current RC is a finite camera-centred world field, not
SPWI/RCPG; no directional proposal/product PDF exists"). Guiding is therefore a greenfield addition,
not a parameter on the existing cache.

### Vulkan-only feasibility

Feasible in principle on this stack, all in Slang compute:

1. A directional distribution store (per-voxel or per-screen-tile vMF/Gaussian mixture, or a small
   spatial-directional tree) in a storage buffer — additive memory on top of the RC.
2. An online **learning pass** that accumulates incident radiance into the distribution each frame
   (temporal accumulation with a forgetting factor), plus a resolve/normalization pass.
3. A **sampling + MIS** path in the integrator that draws from the guide and weights against the
   BSDF PDF, preserving the existing RIS/ReSTIR weights and random-stream ownership
   (`ARCHITECTURE_REASSESSMENT.md:37`).

No DirectX/DX12 work is needed; no vendor Tensor-Core path is required for a non-neural guide. So it
passes the Vulkan-only rule. But it is a large, correctness-sensitive change (PDF normalization,
firefly clamping, MIS balance, animated-scene invalidation) for a benefit that is **unproven on
Minecraft's mostly-diffuse voxel lighting**, where the indirect field is low-frequency and the
existing ReSTIR reuse already settles it.

### Measurement gate (before any implementation)

A/B on the fixed-seed autotest world, same scene/quality/memory budget, guiding off vs on: record
`gpu_rt_ms` median/p95, the learning+resolve pass cost, added VRAM, and a converged-reference error
metric (the reassessment's opt-in reference integrator, `ARCHITECTURE_REASSESSMENT.md:39,58`). Enable
only if the variance reduction buys a real net frame-time win at equal error after the learning-pass
and memory cost. Otherwise keep off and record the negative.

**Disposition.** Default off, not implemented. Recorded as a feasible-but-unproven Vulkan-only
research direction; the cheap stack does not need it to hit its measured wins.

---

## D. Remaining Vulkan-compatible GI/perf technologies — survey (item 9)

**Verdict: research/recommendations only. No speculative code.** This surveys what is left after the
landed stack, separates owner-forbidden items, and ranks candidates by cheapness and evidence need.
Every "candidate" below is a proposal that needs feasibility verification and a measurement — none is
claimed as a measured win.

### Already landed / wired (do not redo)

BLAS compaction + transactional query slots; ReSTIR DI + PT (+ Enhanced); raygen PT execution
(−7.8% gpu_rt); SER wired and executing (perf pending a quiet-window A/B); NRD REBLUR/RELAX
(SPIR-V-only); DLSS RR via NGX Vulkan; regional in-place TLAS update; GI auto-policy + texture
presets. See `GI_STACK_REPORT.md`.

### Deferred with an explicit measurement gate (this task)

OMM 2-state (`13-omm-feasibility-design.md`), partitioned PTLAS (`14-ptlas-feasibility-design.md`),
DLSS RR preset D/E/F, SER steady-state perf, NRD ReBLUR/ReLAX tuning. Each is gated on a measured
net win; none is enabled by default.

### Owner-forbidden (section B) — excluded from the candidate list

DDGI, NRC, SHaRC, CLAS, Mega Geometry, Brixelizer GI, surfel GI.

### Remaining candidates worth considering (Vulkan-only, not forbidden)

Ranked by expected cheapness on this 8 GiB / RTX 5060 Ti profile. "Evidence" is what must be shown
before adoption; none is implemented here.

1. **Async-compute overlap of trace and denoise/post.** The frame is denoiser/CPU-bound at RD16
   (`GI_STACK_REPORT.md` measured numbers). Overlapping the RT pass with independent post work on a
   second queue family could hide latency. *Evidence:* real host submission/completion contract for
   a second queue (`ARCHITECTURE_REASSESSMENT.md:67`), GPU-timestamp overlap proof, no barrier
   hazards. Not a GI estimator change; pure scheduling.
2. **FP16 / packed reservoir storage.** The PT ring is 2×w×h×144 B = 569 MiB at 1080p
   (`GI_STACK_REPORT.md`). Half-precision or packed fields could cut that memory, enabling higher RD
   within budget. *Evidence:* numeric-stability A/B (firefly/variance at equal spp), reserved-VRAM
   delta, `gpu_rt` delta. Memory win first, perf second.
3. **Descriptor indexing / bindless material atlas.** Reduce per-pass descriptor churn and bind
   points. *Evidence:* `VK_EXT_descriptor_indexing` already core-1.2; capture CPU `prepareFrame`
   time and descriptor-update counts before/after. Perf, not GI.
4. **Spatiotemporal blue-noise / low-discrepancy sampling for the primary BSDF lobe.** Primary BSDF
   selection is still white noise (`ARCHITECTURE_REASSESSMENT.md:37`). A blue-noise mask could lower
   denoiser input variance at equal spp. *Evidence:* converged-reference error at equal spp, NRD
   output quality, no lobe-coverage regression in 3×3 neighborhoods.
5. **Receiver-local light proposals (ReGIR / light BVH).** Alternative to the current importance
   sorted emitter list (`ARCHITECTURE_REASSESSMENT.md:35`). *Evidence:* exact proposal PDF preserved,
   `gpu_rt` A/B on a many-light scene; neither grid nor tree is preselected.
6. **NRD ReLAX vs ReBLUR + tuning** (item 6). Already wired; only A/B-measured tuning is allowed.
7. **DLSS RR preset D/E/F** (item 4). Additive ABI + GPU A/B; default off until numbers.

### Explicitly not prioritized (greenfield / vendor / unproven)

Work graphs (`VK_AMDX_shader_enqueue` / `VK_NV_shader_enqueue`, experimental driver support),
mesh shaders (geometry, not GI), `VK_KHR_ray_tracing_position_fetch` (the BLAS is already
position-only, `13-omm-feasibility-design.md`), Split Radiance Cascades
(`ARCHITECTURE_REASSESSMENT.md:78`, research). These need a real workload and integration ownership
before any code; they are not cheap-stack wins.

### Recommendation

Spend the next perf effort on **scheduling and memory** (async overlap, FP16 reservoirs, descriptor
indexing) and **sampling quality at equal cost** (blue noise, NRD tuning), each behind a same-session
A/B — not on a new GI cache. The forbidden cache family stays out; RC stays the default indirect
estimator. No technology is adopted without an L4 measurement on this device.

---

## Provenance

All file:line facts above were read from this worktree at head `9c5744f8` (branch
`bedray-gi-stack-20261112`, ABI 2). Literature/extension names in section D are proposals to verify,
not measured results. The only real remote for this task is the owner's repository
`https://github.com/bnfe12/bedray.git`; no proxy or substitute address is used.