# L5 reproduction package — cheap GI/perf stack

**Purpose.** Everything the owner needs to reproduce the measured results in `GI_STACK_REPORT.md` on the live
device and confirm them by playing (**L5**). This document is the *reproduction recipe*; it is not itself an L5
sign-off. **L5 is the owner's act** — it is recorded only when the owner confirms the effect in their own run.
Nothing here claims L5 has happened.

**Honest status of the visual package.** The before/after **screenshots are not yet captured**: they require a
quiet, exclusively-focused game window (the strict native acceptance gate needs `FOCUSED` bit 6 for the whole
record), and the GPU is currently shared with another session's autotests. The exact capture method is given in
§5 so the owner (or a later quiet-window run) can produce them. Every *number* in the report already has an L4
capture under `docs/measurements/`; the screenshots are the remaining visual evidence, not a missing measurement.

---

## 1. Prerequisites

| item | value used for the report |
|---|---|
| GPU | NVIDIA GeForce RTX 5060 Ti, 8 GiB (`memory_bytes=8274313216`) |
| Driver | NVIDIA 621.14, Vulkan 1.4.357 |
| Game | Minecraft 26.3, NeoForge loader |
| Test instance | `C:/Users/andre/AppData/Roaming/FreesmLauncher/instances/Vulkan Experimental RT 26.3` |
| Launcher | FreesmLauncher (`freesmlauncher.exe`) |
| Java | 25 (FFM), Gradle wrapper |
| Slang compiler | pinned SDK, e.g. `C:/Dev/Sky/tools/sdk/slang-2026.20/bin/slangc.exe` (`-Pslangc=...`) |
| Native toolchain | Visual Studio 2022 Build Tools (MSVC), CMake, Vulkan headers |
| Vendor SDKs (never committed) | DLSS/NGX (`VKB_NGX_SDK`), NRD source (`VKB_NRD_SRC`), Streamline (`VKB_SL_SDK`), Vulkan headers (`VKB_VK_HEADERS`) |

**Quiet-window rule (hard).** Before any launch or deploy, confirm **both** `javaw.exe` **and**
`freesmlauncher.exe` are gone (`tasklist /FI "IMAGENAME eq javaw.exe" /NH` and the same for
`freesmlauncher.exe`). Another session runs its own autotests on this GPU; do not contend with its game. The
bench itself refuses to start while `javaw.exe` is running (`game_running()`), and `deployTestbed` /
`syncDlssNatives` call `requireClosedGame`. Re-check the instance `mods/` folder immediately before deploying —
other sessions toggle the installed jar live.

---

## 2. Build and deploy (jar **and** natives)

A jar alone is **not** a working RT build: the loader jar must be paired with the matching `bedray_core.dll`
(native GPU scene ABI 3), and the denoiser arms additionally need the vendor wrapper DLLs. Deploy is therefore
**jar + core.dll + (DLSS pair / NRD wrapper)**.

### 2a. Renderer distribution (jar + bedray_core.dll)

```bat
set SLANG_DIR=C:\Dev\Sky\tools\sdk\slang-2026.20
gradlew neoforgeBedrayDistribution
```

This pairs the NeoForge loader jar with the matching `bedray_core.dll` (see
`native/bedray_core/DISTRIBUTION.md`). The core exports the native GPU scene ABI; ABI 2 and ABI 3 must never be
mixed in one artifact.

### 2b. Vendor wrappers (built from source; never committed)

```bat
:: DLSS/NGX wrapper -> vkb_dlss.dll
set VKB_NGX_SDK=C:\Dev\Sky\tools\sdk\DLSS
set VKB_VK_HEADERS=C:\Dev\Sky\tools\sdk\Vulkan-Headers\include
native\dlss_bridge\build.bat

:: NRD wrapper -> vkb_nrd.dll (SPIR-V-only; NRD_EMBEDS_DXIL/DXBC=OFF)
set VKB_NRD_SRC=<path to NRD source>
native\nrd_bridge\build.bat
```

Each `build.bat` prints what is missing if an env var is unset. Copy `nvngx_dlss.dll` from the SDK
(`C:/Dev/Sky/tools/sdk/DLSS/lib/Windows_x86_64/rel/nvngx_dlss.dll`) next to the built `vkb_dlss.dll` in a local
staging folder (`local_natives_dir`). Built DLLs are **never committed** (project rule).

### 2c. Deploy into the test instance

Set `testbed_dir` and `local_natives_dir` in your user Gradle home (`C:/Users/andre/.gradle/gradle.properties`),
or pass them with `-P`:

```bat
set TB=C:/Users/andre/AppData/Roaming/FreesmLauncher/instances/Vulkan Experimental RT 26.3/minecraft/mods
set LN=<local staging folder with vkb_dlss.dll + nvngx_dlss.dll>

gradlew deployTestbed   -Ptestbed_dir="%TB%"
gradlew syncDlssNatives -Ptestbed_dir="%TB%" -Plocal_natives_dir="%LN%"
```

* `deployTestbed` depends on `:neoforge:combinedJar`, verifies the provider is nested, backs up the previous jar,
  and re-checks the closed game immediately before swapping files. `-PdryRun=true` lists only.
* `syncDlssNatives` copies `vkb_dlss.dll` + `nvngx_dlss.dll` into `<instance>/bedray/natives/`, where Bedray Core's
  `NativeLibraries` resolves them. Place `bedray_core.dll` (from §2a) and `vkb_nrd.dll` (from §2b) in the same
  `<instance>/bedray/natives/` folder.
* The NRD arm needs `NRD.dll` + `vkb_nrd.dll`; the DLSS-RR arm needs `vkb_dlss.dll` + `nvngx_dlss.dll`. The
  settings page reports missing vendor libraries, and `DenoiserResolution` falls back / refuses with an explicit
  reason rather than silently failing.

---

## 3. Reproduce each measured result (bench stand)

All numbers come from `tools/automation/bench_gi_stack.py` + `capture_analysis.py`. The bench launches the real
instance, injects session overrides through `instance.cfg` JvmArgs (never the saved `bedray.toml`), runs one
fixed-seed/fixed-camera `native_perf` capture per variant, analyzes strict native-1080p acceptance plus medians,
parses `latest.log` for backend/memory/BLAS/GPU-span evidence, and writes one JSON per variant.

**Fixed workload (every arm):** seed `1234567890123`, pose `-41.5 / 89 / 2.5`, yaw `135`, pitch `12`, native
`1920x1080`, RD16 unless noted, BILINEAR request / no upscaler / no frame generation, 10 s warmup + 30 s record.

Run from `tools/automation/` (standard library only; Python 3). `--hold-focus` keeps the window focused so the
strict `FOCUSED` gate survives a desktop that would steal focus. Pass `--revision` to label the capture with the
deployed build (the report's head is `1cc5729b`, native core ABI 3).

```bat
cd tools\automation

:: ReSTIR PT off -> on  (+24.7% gpu_rt, +569 MiB)
python bench_gi_stack.py --out ..\..\docs\measurements --scratch <scratch> ^
    --variants compute_pt_on raygen_pt_on --hold-focus --render-distance 16 --revision 1cc5729b-abi3

:: compute -> raygen -> SER  (raygen -7.8% gpu_rt; SER steady-state 2.14x slower)
python bench_gi_stack.py --out ..\..\docs\measurements --scratch <scratch> ^
    --variants ser_ab --hold-focus --render-distance 16 --revision 1cc5729b-abi3

:: DLSS RR -> NRD REBLUR -> NRD ReLAX  (REBLUR -18.4% gpu_rt vs RR; ReLAX arm added item 6)
python bench_gi_stack.py --out ..\..\docs\measurements --scratch <scratch> ^
    --variants denoiser_ab --hold-focus --render-distance 16 --revision 1cc5729b-abi3

:: BLAS compaction RD32  (reserved ~3607 MiB fits 4096 MiB)
python bench_gi_stack.py --out ..\..\docs\measurements --scratch <scratch> ^
    --variants compute_pt_off --hold-focus --render-distance 32 --revision 1cc5729b-abi3
```

Variant groups: `baseline` = compute/raygen × PT off/on; `ser_ab` = `compute_pt_on`, `raygen_pt_on`, `ser_pt_on`;
`denoiser_ab` = `denoiser_rr`, `denoiser_nrd`, `denoiser_nrd_relax`; `all` = every variant. `--dry-run` prints the
label→properties map without launching. The ReLAX arm resolves through `path_denoiser=nrd` + the expert override
`-Dvkrtx.denoiser=nrd_relax` (`DenoiserResolution` case NRD → `NRD_RELAX`).

**A/B discipline.** Compare only **same-session pairs** (the bench runs the arms back-to-back in one session).
Cross-session deltas are not compared: the same DLSS_RR+PT config measured 28.19 ms in the PT bench and 30.80 ms
in the denoiser bench. Each run writes `<label>.json` and updates `docs/measurements/index.json`.

**Reading the result.** Each JSON carries `acceptance.target_met_in_capture`, `acceptance.frames`,
`valid_native_capture`, GPU-timestamp medians (`gpu_rt`, `gpu_total`, frame interval), peak `reserved`/`used`
VRAM, and log evidence (`enabled_mask`, `active` denoiser, BLAS compaction, `gpu_spans`,
`tlas_gpu_ms_upper_bound`). `target_met=false` is expected at RD16 (the frame is denoiser/CPU-bound); the report
quotes GPU timers, not an FPS guarantee.

---

## 4. Reproduce the settings UX

**L4 (in-game).** `gi_settings` is an autotest *scenario* (`GiSettingsScenario`, registered in `RtxAutotest`),
not a JUnit test. It runs in the test world via the harness selector `-Dvkbridge.autotest=gi_settings`: it probes
the live Vulkan device, runs the real `GiStackPolicy.recommend`, stages the plan through the real `SettingsDraft`
(the same path the Overview buttons use), reads the staged option values back and asserts they match, stages all
three texture presets, then opens `BedrayConfigScreen` and shoots `gi_settings_overview.png` into
`<gameDir>/autotest/gi_settings/`. `result=PASS` means the staging matched the plan on real hardware and the
screen rendered — it is not an FPS claim. The captured proof is `gi_settings_l4/`. To reproduce, inject
`-Dvkbridge.autotest=gi_settings` through `instance.cfg` JvmArgs (the bench does this for `native_perf`; the
`gi_settings` scenario is selected the same way) and launch the deployed instance in the quiet window.

**L2 (unit tests, no GPU).** The pure resolution/status tables are unit-tested:

```bat
gradlew :common:test --tests "*DenoiserResolutionTest" --tests "*GiExecutionStatusTest" ^
    --tests "*VulkanSodiumConfigIdsTest" -Pslangc=C:/Dev/Sky/tools/sdk/slang-2026.20/bin/slangc.exe
```

In-game, the **Auto-select GI quality** action picks a tier from device memory + features (on this device it
returned BALANCED: spp=1, bounces=3, dist=512, di=true, pt=true, **ser=false** — the correct default given the
measured SER negative). The honest **GI execution-tech status** row (`gi_execution_status`) reports each
technology's supported/enabled/implemented state from the live enabled mask; the SER note carries the measured
2.14x-slower negative. `dlss_rr_preset` (ADVANCED, gated on DLSS RR) selects the NGX render-preset hint D/E/F;
default 0 leaves the NGX param untouched (exact prior behavior). The L4 capture is `gi_settings_l4/`.

---

## 5. Before/after screenshots (visual package — pending quiet window)

**Not yet captured.** Method, for the owner or a later quiet-window run:

1. Deploy as in §2 and confirm the quiet-window rule (§1).
2. Load the fixed-seed world (seed `1234567890123`) and teleport to the report pose `-41.5 / 89 / 2.5`, yaw `135`,
   pitch `12`, RD16, native 1920x1080.
3. **Before/after denoiser:** run with `-Dvkrtx.denoiser.view=split` to render the raw vs denoised split view, or
   capture two full-frame screenshots at the same pose — one with `path_denoiser=dlss_rr`, one with
   `path_denoiser=nrd` (REBLUR). Keep the pose, seed, RD and resolution identical between the pair.
4. **PT off/on:** capture the same pose with `path_restir_pt=false` then `true` to show the indirect-lighting
   quality difference (PT is a quality tier, +25% RT / +569 MiB).
5. **SER:** capture raygen vs raygen+SER (`-Dbedray.rt.ser=true`) at the same pose to illustrate the measured
   steady-state cost (the number, not the look, is the reason it stays off).
6. Save the pairs under `docs/measurements/<arm>/` and reference them from the report. Label each with the
   revision, denoiser/PT/SER state, and `valid_native_capture` so the visual evidence is tied to a validated run.

Screenshots are **indicative visual evidence**; the authoritative perf claims remain the GPU-timestamp medians
from §3. Do not present a screenshot as an FPS measurement.

---

## 6. What L5 means here, and what remains owner-only

* **L0–L4 are established by this branch** (code, build, tests, game-start, effect-in-a-real-run). The report
  claims L4 only where a live capture proves it, and says so plainly where a result is only L2 or unmeasured.
* **L5 (owner confirmation) is pending for every stage.** It is the owner playing the deployed build and
  confirming the effect/quality themselves. This document provides the recipe; it does not record L5.
* **Owner-only / gated remainders** (cannot be closed autonomously):
  - L5 sign-off on each stage.
  - Before/after screenshots (§5) — need a quiet, exclusively-focused window.
  - Item 4 DLSS-RR preset D/E/F **A/B numbers** — wired additively, default-off until measured (GPU-gated).
  - Item 6 NRD ReLAX-vs-ReBLUR-vs-RR **A/B numbers** — bench arm added, capture GPU-gated.
  - Item 3 isolated `tlas_ms` on block-edit/teleport — needs a native `prepareFrame` split (additive scene ABI
    change, owner-gated) and an edit/teleport scenario the static bench does not yet drive.
  - Any ABI-layout change (owner permission required); the C++ agent zone is `native/**` only.

**Remote.** Push only to the verified `https://github.com/bnfe12/bedray.git`, branch
`bedray-gi-stack-20261112`, explicit-path `git add` only; never force-push, `reset --hard`, or merge into `main`
without an explicit request.