# BedRay 2.0 migration status

Snapshot of 2026-10-31, branch `refactor/slang-pipeline`. Levels: L1 builds (slangc / javac / MSVC), L2 unit and
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

1. **5.7 Final gates**
   - owner playtest of the checklist below, with `latest.log` and screenshots;
   - reconcile `SLANG_MIGRATION.md` (Big Four, Next steps), `AUDIT_DEFECTS_AND_ISSUES.md`, `VERIFICATION_LOG.md` and the
     module README with the results; everything without an owner-verified frame stays marked so.
2. **Code review of the pre-existing code** (correctness, dead code, duplication) - started 2026-10-31, interrupted before
   results; to be repeated.
3. **ReSTIR PT `experimental/` exit** - `restir_pt.slang` moves back to `restir/` only after a GPU run with
   `path_restir_pt` on.
4. **Native backend parity** - with `-Dvkbridge.rt.nativePathTrace=true` the core binds stand-ins at 36 and 37: no
   ReSTIR PT and an n/a F3 split until the core owns a PT ring and lends the trace the stats slot.

## Owner playtest checklist

- PT on/off: `path_restir_pt` off, then on; log `Slang ReSTIR PT reservoirs ... MiB`, `restir_pt=fresh` then
  `temporal+spatial`; the first frame after the switch looks the same.
- DI and PT separately: ReSTIR Direct Light alone, then with Path Reuse; Spatial Path Reuse off/on.
- Water above and below: a torch above the surface seen from under water; the sun under water unchanged; Snell window.
- Entities, denoiser (DLSS-RR / NRD), RC, volumetric light, frame generation: no regression against the previous build.
- F3: `invalid_pct`, `trunc_pct`, `vis_overflow_pct` are numbers on the default backend.
- Settings screen: every tab shows rows; no `[VKB-SETTINGS]` error lines.
- Walking past a wall at arm's length: the image does not turn noisy on every step (5.5 fix).
- RC (only with `-Dvkbridge.rt.nativePathTrace=true -Dvkbridge.rt.nativeScene=true -Dvkbridge.rt.radianceCascades=true`):
  the mode line says `rc_reuse=temporal`; indirect light under the field is calmer when standing still and follows a
  moving camera without smearing more than about a block.
