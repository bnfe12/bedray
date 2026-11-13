# gi_settings — L4 proof of the settings actions (2026-11-12)

Live run on **NVIDIA GeForce RTX 5060 Ti** (8 GiB, capability mask `0x677`, driver 1.4.357 / NVIDIA 621.14),
1920x1080, RD16, NeoForge instance "Vulkan Experimental RT 26.3". Scenario `gi_settings`, revision `81c06ffa`,
jar sha256 `f645f359…bf5d28`. **result=PASS** (666 frames, 29 s, 0 unexpected screens).

## What is proven

1. **GI auto-policy (`Auto-select GI quality`).** `GiDeviceProbe` read the live device, `GiStackPolicy.recommend`
   returned **BALANCED** — `spp=1 bounces=3 dist=512 di=true pt=true ser=false`, "Fits the 1972 MiB reservoir
   budget at 1920x1080". `SettingsDraft.applyGiPlan` staged it and the read-back matched exactly
   (`profile=CUSTOM`). `ser=false` is correct: mask `0x677` has neither bit 11 (RT pipeline) nor bit 12 (SER NV).

2. **Texture styles (`Texture style...`).** All three presets staged and read back correctly with **expert UI off**:
   - PIXEL_ART → `point_sampling=true lod_bias_offset=-3`
   - BALANCED → `point_sampling=false lod_bias_offset=0`
   - SOFT → `point_sampling=false lod_bias_offset=3`

3. **Screen renders.** The Bedray Settings Overview opened and drew (`gi_settings_overview.png`, 180718 bytes):
   categories, Hardware Path Tracing, Path Tracing Quality, and the `Auto-select GI quality (recommended)` action.
   The `Texture style...` action sits just below the scroll fold in this top-of-list capture.

## Regression fixed en route

The first L4 attempt failed: `texture preset PIXEL_ART staged point=true offset=null`. Root cause (traced, not
guessed): `texture_lod_bias_offset` lived only in the **expert-only** option group, so with expert UI off (the
default) `getOption` returned null and the preset staged into nothing. `point_sampling_sharpness` (always-registered
Image group) worked, which isolated the fault to registration gating. Fix (`81c06ffa`): move the row into the
always-registered Image group, drop the expert duplicate, retier EXPERT→ADVANCED, and add
`textureStyleRowsAreRegisteredWithoutExpertUi` so the gating cannot regress silently. L2: 39 tests pass.

## Evidence

- `gi_settings_l4.json` — structured record (device, plan, read-backs, presets, result); carries the key
  `[VKB-AUTOTEST]` log lines (`event=plan`, `gi_staged`, `texture_staged`, `result=PASS`).
- `gi_settings_overview.png` — the rendered Overview screen.
- The raw `latest.log` is gitignored (`*.log`) and kept in the bench scratch dir, not committed.