# Light flicker / unstable image in path tracing — 2026-11-05

Owner report (translated from Russian):

> Where the light is in the picture, everything flickers and jitters and the
> picture is very unstable.

No crash happened in this run. The game was closed normally after about one
minute. The historical crash reports are included separately below.

## Build and environment

- Commit: `73f450a76feff457f39f2547e77328e307ecc173` (merge of PR #75),
  manifest `Git-Commit 73f450a...`, `Git-Dirty false`.
- Jar: `bedray-neoforge-mc26.3.0-0.2.0-alpha.jar`, 20,515,272 B,
  SHA-256 `E3A16C1635F14828F9B6BF264F3432BCD92ACC526A5ADF7FE62183ECE8BD21A2`.
- `bedray_core.dll`: 488,960 B, SHA-256
  `299C11F5D274CF746CD483A69C8D67CF202C08EB5BF1476298FEFDF84D412338`.
- `bedray_scene.dll`: 248,832 B, SHA-256
  `BC5069B997C1D957549D5AFE32199C7CFCEDA785175BDCD5878C31534FA82E58`.
- `vkb_dlss.dll` (rebuilt for PR #75): 973,824 B, SHA-256
  `0D249463B650921E15B9E9122846DEC25FCB9CC3FCF4A0AD804FBB59473943AE`.
- `vkb_sl.dll` (rebuilt for PR #75): 222,208 B, SHA-256
  `8856DA23348C7A523A6FA00609EDFE1BC56F4D33F068D34C575ACE18C51ECAC2`.
- GPU: NVIDIA GeForce RTX 5060 Ti 8 GB, driver 621.01 (32.0.16.2101),
  Windows 11 10.0.26100.
- Minecraft 26.3, NeoForge 26.3.0.76, Sodium 0.9.2, Iris 1.11.7.
- Instance: `<instance>`, world `<world>`.

## Settings from `config-bedray.toml`

- `rt_mode = "path"`, `path_denoiser = "DLSS_RR"`.
- `path_bounces = 2`, `path_spp = 1`, `path_distance = 256`.
- `path_accumulate = true`, `path_restir_di = true`,
  `path_restir_spatial = true`, `path_restir_pt = false`.
- `path_auto_exposure = true`, `path_caustics = true`.
- DLSS `PERFORMANCE`: 1280x720 render -> 2560x1440 output.
- `atm_godrays = 1.0`, `atm_sunglare = 1.0`, `atm_bloom = 1.0`.
- `sun_size_tenths = 5` (0.5 degrees), `rt_light_budget = 512`.
- Frame generation off.

## What the new run's log shows

The run is `latest.log` (started 00:22:00, closed 00:22:59).

The renderer repeatedly republishes the emitter table and changes which
emitters are selected. Within 25 seconds of entering the world:

```
[VKB-EMITTERS] revision=4   selected=196 retained=0   added=196 removed=91  topology_reused=false
[VKB-RT] emitters used=196 ... rebuild_ms=6702.94 stale=true
[VKB-EMITTERS] revision=5   selected=196 retained=149 added=47  removed=47  topology_reused=false
[VKB-RT] emitters used=196 ... rebuild_ms=6091.99 stale=true
[VKB-EMITTERS] revision=125 selected=196 retained=175 added=21  removed=21  topology_reused=false
[VKB-RT] emitters used=196 ... rebuild_ms=2.62 stale=false
[VKB-EMITTERS] revision=254 selected=196 retained=196 added=0   removed=0   topology_reused=true
```

`stale=true` with a 6.1-6.7 s `rebuild_ms` means the renderer kept the old
emitter table for several seconds while rebuilding, then swapped it. After the
swap the composition of the sampled lights changed (`added`/`removed` up to 47
of 196). This is the strongest candidate for visible light popping/flicker.

The same pattern exists in the previous run on the previous build
(`previous-run-2026-11-04-1c59ba4.log`): emitter revisions went from 0 to 1650
in about six minutes, with `retained`/`added`/`removed` changing repeatedly.
So the churn is not introduced by PR #75, but it is still present.

Other observations from `latest.log`:

- `sun_dir=(0.887,0.422,0.188)`, `sun=(0.89,0.42,0.19)x1.45`,
  `lights=196`, `sun_size_deg=0.50`.
- Auto exposure changes on the same scene: `exposure=1.10` at 00:22:28,
  `exposure=0.68` at 00:22:38. That can pulse brightness independently of the
  emitter table.
- `hit_pct` swings between 52.1 and 100.0 across consecutive frames while the
  camera turns; `trunc_pct` stays around 0.8-1.6.
- DLSS and RR initialize and run:
  `dlss=ready`, `denoiser requested=DLSS_RR active=dlss_rr`,
  `[DLSS-RR] feature_created`, `rr_output=true`,
  `sr_suspended reason=rr_output`.
- No `DEVICE_LOST`, no `hs_err`, no mixin crash. The only errors are the
  unrelated Realms/user-properties HTTP 401 lines.
- NGX logs one warning:
  `passed vkGetInstance/DeviceProcAddr to NGX SDK, but snippet is out of date...
  Falling back to older API.` It is not fatal, but it is in `ngx/nvngx.log`.

## Previous crashes (older builds, included for completeness)

The newest crash report is from 2026-10-26 (before the current changes):
`crashes/crash-2026-10-26_01.53.13-client.txt`:
`VK_ERROR_DEVICE_LOST: Failed to wait for semaphore`,
`TOP_OF_PIPE = END_RENDER_PASS Bedray RT particles`. The other four reports are
from 2026-10-24/25. They are not from `73f450a`.

## Files

- `latest.log` — the complete 73f450a run (00:22:00-00:22:59).
- `debug.log` — the same run with FML debug lines.
- `previous-run-2026-11-04-1c59ba4.log` — previous six-minute run on `1c59ba4`.
- `config-bedray.toml` — settings used for both runs.
- `ngx/` — NGX/DLSS logs written by the rebuilt bridge.
- `crashes/` — the last five client crash reports (24-26 October).

Personal paths, player, launcher, instance and world names were anonymized before sharing.
