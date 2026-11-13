# Denoiser stack — NRD specular + DLSS RR audit and A/B (2026-11-12)

Stage #13 of the GI-stack plan. Live **NVIDIA GeForce RTX 5060 Ti** (8 GiB, mask `0x677`, driver 1.4.357 /
NVIDIA 621.14), native 1920x1080, RD16, fixed seed/camera, NeoForge instance "Vulkan Experimental RT 26.3",
revision `9282366d-abi2`. Both arms: ReSTIR DI+PT on, adaptive quality off, 4 GiB native budget, BILINEAR
request / no upscaler / no frame generation. Only the lit-view denoiser differs.

## Verdicts (read-only audit, verified against source)

- **NRD specular denoising — integrated end-to-end, Vulkan-only.** `native/nrd_bridge/src/vkb_nrd.cpp:256-258`
  maps the mode bits to `nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR` / `RELAX_DIFFUSE_SPECULAR`: one NRD instance
  denoises **both** the diffuse and specular lobes (there is no separate specular-only pass to build). Java
  `NrdDenoiser` allocates `gbSpecular/inSpecular/outSpecular` (RGBA16F) and binds `RES_IN/OUT_SPEC_RADIANCE_HITDIST`;
  the Slang pack shader writes the specular lane. The bridge is SPIR-V-only — `build_nrd.bat:47`
  `NRD_EMBEDS_DXIL_SHADERS=OFF`, `NRD_EMBEDS_DXBC_SHADERS=OFF`; no D3D12 code. **No DX12 work required.**
- **DLSS Ray Reconstruction — core integrated via NGX Vulkan; tuning partial.** `native/dlss_bridge/src/vkb_dlss.cpp`
  uses `NVSDK_NGX_VULKAN_Init_with_ProjectID` / `NGX_VULKAN_CREATE_DLSSD_EXT1` / `NGX_VULKAN_EVALUATE_DLSSD_EXT`.
  Wired tuning: quality mode, feature flags (HDR, depth-inverted, auto-exposure, MV jittered/low-res), roughness
  packed/unpacked, HW vs linear depth, MV scale, pre-exposure/exposure scale, jitter, matrices. **Vulkan-compatible.**

## L4 functional proof (this run)

Both denoisers ran in-game on the live device and finished cleanly:

| arm | requested | active | result | frames | denoiser column |
|-----|-----------|--------|--------|--------|-----------------|
| `denoiser_nrd` | NRD | `nrd_reblur` | PASS | 2583 (1064 timestamped) | stable `NRD` across all rows |
| `denoiser_rr`  | DLSS_RR | `dlss_rr` | PASS | 2231 (891 timestamped) | stable `DLSS_RR` across all rows |

The `denoiser` CSV column never changed in either arm — **no mid-capture fallback**, so the two arms are not
contaminated by each other. `native_scene` confirms `bedray_core.dll` ABI 2 in both.

## Measured GPU cost (indicative A/B)

Median over the timestamped frames (`gpu_*` exclude submit/present):

| metric | NRD REBLUR | DLSS RR | NRD vs RR |
|--------|-----------|---------|-----------|
| `gpu_total_ms` | 25.81 | 31.54 | **−18.2%** |
| `gpu_rt_ms`    | 25.14 | 30.80 | **−18.4%** |
| `interval_ms`  | 27.50 | 33.11 | −16.9% (~36.4 vs ~30.2 fps) |

At **native 1080p with no upscaling**, NRD's analytical ReBLUR is cheaper than running RR's full transformer
model in denoise-only mode. RR's advantage is when *upscaling* (render low, output high); that regime is not
measured here. This supports the cheapest-stack goal: NRD REBLUR is the lighter native-resolution denoiser.

### Acceptance caveat (honest)

The strict `capture_analysis.analyze` native gate reports `valid_native_capture=false` for **both** arms:
- NRD: `CSV line 112: PT/world/focus/screen/upscaler/FG proof failed`.
- RR: `CSV line 241: quality/program changed during capture`.

Root cause (traced in the CSVs, not guessed): the `flags` word drops **bit 6 = FOCUSED (64)** after the settle
period (127→63) in both arms — the game window loses focus during an automated run. Every *rendering-validity*
flag still holds throughout (WORLD=1, PATH_RECORDED=2, NATIVE_RESOLUTION=4, UPSCALER_OFF=8, FRAMEGEN_OFF=16,
SCREEN_CLOSED=32), and window focus does not change GPU rendering cost. RR additionally toggles `trace_flags`
bit 20 for ~43 frames. So the medians above are sound **indicative GPU-timestamp evidence**, but this is **not a
validated native-1080p FPS guarantee** — the same FOCUSED artifact affected the earlier SER/baseline captures.
No FPS claim is made beyond these measured medians.

## Tuning gaps — disposition (deferred, evidence-based)

Two RR tuning knobs are defined but not wired; both need native ABI work **and** GPU measurement to validate,
so they are deferred rather than risked mid-stack (default-off-until-numbers rule):

1. **RR sharpness** — `vkb_dlss.h:240` marks the RR eval `sharpness` field `/* reserved for API consistency (0.0) */`;
   the native RR evaluate never applies it (only the SR path does, `vkb_dlss.cpp:617`). This mirrors NVIDIA's RR
   API, which does not take a sharpness scalar like SR. **Disposition: not a bug; do not wire a no-op.** If a
   sharpening pass is wanted post-RR, it is a separate CAS/sharpen pass, not an RR parameter.
2. **RR render preset D/E/F** — `DlssRrParams.Preset` (`DlssRrParams.java:47-62`, DEFAULT/D/E/F → native 0/4/5/6)
   is defined but referenced nowhere; `CreateInfo` (`:88-110`) has no preset field and the native create struct
   carries none. **Disposition: deferred bounded feature.** Integration design: add an additive `preset` field to
   `VkbDlssRrCreateInfo` + `DlssRrParams.CreateInfo`, pass `NVSDK_NGX_RRayReconstruction_Hint_Render_Preset` at
   create, expose a `dlss_rr_preset` ADVANCED option, then A/B preset D/E/F on the GPU before defaulting. Additive
   ABI only; needs a quiet-GPU measurement pass.

NRD ReBLUR/ReLAX settings (`NrdReblurParams`, `NrdFrameSettings`, `specularPrepassBlurRadius`) are already wired;
further NRD tuning is a measurement task, not a missing integration.

## Settings exposure

The active denoiser is already a user setting: `path_denoiser` (OFF / NRD / DLSS Ray Reconstruction), ADVANCED
tier (`SettingsCatalog.java:306`, `VulkanSodiumConfig.java:642-653`), persisted default NRD (`RtSettings.java:196`),
session override `-Dvkrtx.rt.path_denoiser=nrd|dlss_rr|off`. `DenoiserResolution.resolve` falls back DLSS_RR →
NRD_REBLUR when RR is unusable or frame generation owns NGX. No new UI is required for the denoiser choice.

## Evidence

- `denoiser_nrd.json`, `denoiser_rr.json` — structured records (props, medians, log evidence, acceptance).
- Raw `frames.csv` / `manifest.json` live in the instance `autotest/native_perf/capture-*` dirs (sha256 in the JSON);
  raw logs are gitignored and kept in the bench scratch dir.