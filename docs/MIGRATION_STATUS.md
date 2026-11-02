# BedRay 2.0 migration status

On 2026-11-02 the owner expanded the task to all previously deferred stack
technologies/modes and requested a comparison with better approaches. The
requirement-level ledger, actual implementation gaps, corrected API assumptions
and execution order are in [STACK_IMPLEMENTATION_PLAN.md](STACK_IMPLEMENTATION_PLAN.md).
The migration backlog below is a subset of that expanded task; completing its
source transfers does not complete the full architecture manifesto.

Snapshot of 2026-11-01, branch `refactor/slang-pipeline`. Levels: L1 builds (slangc / javac / MSVC), L2 unit and
source tests, L3 the game starts and the feature runs (log line), L4 effect visible on a screenshot, L5 confirmed by the
owner. Evidence for every line is in [VERIFICATION_LOG.md](VERIFICATION_LOG.md) under the named commit.

## Done in the 5.x backlog

| step | what | commit | level |
|---|---|---|---|
| 5.1 | ReSTIR PT live temporal stage (`restir/pt_temporal.slang`, ring at binding 36, `path_restir_pt`, default off) | `b2a563a` | L3: programs register with 28 bindings; PT itself not switched on in a logged session |
| 5.2 | ReSTIR PT spatial pass (`restir_pt_spatial`, `restir_pt_spatial_scene`, `path_restir_pt_spatial`) | `93aa115` | L3: both programs register (14 bindings) in `26.3(1)/latest.log` 2026-10-31 01:45; never run with PT on |
| 5.3 | F3 split of rejects (`invalid_pct`, `trunc_pct`, `vis_overflow_pct`, binding 37) | `0a90bb6` | L3: numbers in `rt_pass` (invalid 0.0, trunc 2.8-10.1, overflow 0.0), 01:46-01:50 |
| 5.4 | Underwater refraction of emitter light and escaped rays (`waterBeamToPoint`) | `98b090b` | L2: ray-geometry tests; no underwater frame logged |
| 5.5 | `outDepth` contract written down, consumers checked; `path_display` history depth fixed (raw-camera distance) | `9ed9fcc` | L2 |
| 5.6 | RC v2 temporal reuse: each field blended with the previous consecutive one (builder binding 38, weight 0.8, 288-byte metadata) | `f215c35` | L2 + native CTest; RC is opt-in (`nativePathTrace` + `nativeScene` + `radianceCascades`) and has never run in a logged session |

Fixes found from the 2026-10-31 logs:

- `e5a68bb` + `2bed1f3` - the Bedray settings screen was empty: the 5.2 row read an undeclared option. Fixed, every broken
  option is now logged by name (`[VKB-SETTINGS] option=...`), and `SettingsDeclaredDependenciesTest` checks every row.
- `9595db0` - the `[VKB-RT] mode=path passes=...` line was logged 10-20 times a second (a per-frame counter in a
  log-on-change line).

## Left to reach 100 %

For the original migration backlog only; the expanded stack has additional
implementation work, not merely final GPU checks. The full NRD/RR signal split
and hit-distance contract is tracked in
[DENOISER_SIGNAL_CONTRACT_PLAN.md](DENOISER_SIGNAL_CONTRACT_PLAN.md).

1. **5.7 Final gates**
   - owner playtest of the checklist below, with `latest.log` and screenshots;
   - reconcile `SLANG_MIGRATION.md` (Big Four, Next steps), `AUDIT_DEFECTS_AND_ISSUES.md`, `VERIFICATION_LOG.md` and the
     module README with the results; everything without an owner-verified frame stays marked so.
2. **Code review** — three parallel reviews completed on 2026-11-01. Six confirmed
   lifetime/vendor/shader defects were fixed; this is a bounded audit, not proof
   that all code is defect-free. Findings and repairs are recorded in
   [PARALLEL_AUDIT_20261101.md](ai-agent-addenda/PARALLEL_AUDIT_20261101.md).
3. **ReSTIR PT `experimental/` exit** - `restir_pt.slang` moves back to `restir/` only after a GPU run with
   `path_restir_pt` on.
4. ~~Native backend parity~~ - done 2026-11-01 (`124e619`, `a754582`, L2): the core owns the PT ring (`NATIVE_PT`,
   spatial stage through `configure_path_trace_pt`) and binds the host's StatsBuf slot at 37
   (`render_path_trace_ext`). Not run in a logged session.

## Owner playtest checklist

- PT on/off: `path_restir_pt` off, then on; log `Slang ReSTIR PT reservoirs ... MiB`, `restir_pt=fresh` then
  `temporal+spatial`; the first frame after the switch looks the same.
- DI and PT separately: ReSTIR Direct Light alone, then with Path Reuse; Spatial Path Reuse off/on.
- Water above and below: a torch above the surface seen from under water; the sun under water unchanged; Snell window.
- Entities, denoiser (DLSS-RR / NRD), RC, volumetric light, frame generation: no regression against the previous build.
- F3: `invalid_pct`, `trunc_pct`, `vis_overflow_pct` are numbers on the default backend.
- Settings screen: every tab shows rows; no `[VKB-SETTINGS]` error lines.
- Walking past a wall at arm's length: the image does not turn noisy on every step (5.5 fix).
- Sky: sun/moon phases, stars, FAST/FANCY clouds and End display; resource reload with unchanged pack IDs,
  resize and world close; no atlas lifetime errors in the log.
- Temporal upscalers: switch modes, resize, disable and close the world; check exact host-fence retirement and
  absence of retained SDK context failures. Close once with ReSTIR PT still on.
- RC (only with `-Dvkbridge.rt.nativePathTrace=true -Dvkbridge.rt.nativeScene=true -Dvkbridge.rt.radianceCascades=true`):
  the mode line says `rc_reuse=temporal`; indirect light under the field is calmer when standing still and follows a
  moving camera without smearing more than about a block.

## Open owner report (2026-10-31 16:44)

One triangle of block faces see-through, murky water, no sky, lighting unlike the pre-migration build. A/B runs that
separate the causes, one change at a time, each with a screenshot of the same spot and `latest.log`:

1. Denoiser off (DLSS-RR -> none): a reconstruction artifact disappears, a geometry or shading one stays.
2. ReSTIR Path Reuse off.
3. The pre-5.x jar `bedray-fabric-mc26.3-0.2.0-alpha.jar.20261030-104721.bak`: if the triangle is there too, it predates
   the 5.x batches.

The source gap behind the missing textured sky is addressed by `d9b671c`: the typed `FrameSky` adapter now reads the
resource-pack atlas through bounded modules. Display equations come from the former GLSL path. L1/L2 checks pass;
the owner's visual report remains open until the new build is checked at the same location.

## Fixes of 2026-11-01 (L2, no owner-verified frame)

From `26.3(1)/latest.log` 01:09 and the review of the pre-existing code:

| commit | fix |
|---|---|
| `d1618ad` | RT survived no resize to 2560x1370: the reservoir buffer owner was capped at 1 GiB (DI 428 + PT 963 MiB); now a quarter of VRAM, and a PT ring that still does not fit is left out with `[VKB-RT] restir_pt=off reason=budget`. The Slang rays use the upscaler jitter DLSS-RR/NRD are told (they used their own Halton sequence). The velocity uniforms name a non-finite field before the native call. |
| `bc98fd5` | Sun glare flickered: `bloom_apply` read the final image it was writing; visibility is now measured in the prefilter. |
| `48333ed` | Caustics slid with the camera: the focus read waves at camera-relative positions, the surface at the world anchor. |
| `006fdda` | Volumetric shafts were marched through the vertically mirrored pixel (second `ndc.y` flip). |
| `4273eb2` | Six DLSS-NR options and their 721-line package did nothing; removed, `RtSettingsReachTest` guards new ones. |
| `82fd364` | 5 passes no host runs and 21 modules nothing imports (3,486 lines) removed; `SlangDeadCodeTest` guards. |
| `124e619`, `a754582` | Native backend parity (item 4 above). |

Still open from the 01:09 log: DLSS SR fails on the first frame (`invalid velocity uniform contract`); the next log names
the field. Its actual runtime cause has not been established by a new log.

## Ownership and modularity follow-up (2026-11-01, L1/L2)

The complete source in `2bd6cea` passes 1,067 tests (1,058 passed, nine skipped,
zero failures/errors) and all 29 AOT builds. Native CI on `13ead08` passes 27+1+1
tests. The code now has explicit owners for scene frame tokens, temporal
recordings/SDK contexts, output/depth/composite/motion generations and immutable
sky atlases. Their failure paths preserve retryable ownership and contextual
logs; four unused pre-integration upscaler stubs are deleted. The textured sky
source transfer is complete; game/visual acceptance is still outstanding.

The renderer remains an orchestration class; these changes do not claim every
large subsystem has been decomposed or that the bounded audits establish an
error-free engine. Details and exact evidence are in `VERIFICATION_LOG.md` and
the linked owner contracts.

