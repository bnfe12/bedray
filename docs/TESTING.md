# Help us test

Bedray 0.1.0-alpha.1 was developed and checked on **one** system: **NVIDIA GeForce RTX 5060 Ti (8 GB), Windows, NVIDIA driver 617.42**.
Other graphics cards have not been checked. Any report helps, whether the result is good or bad.

We want: bug reports, crashes, visual problems, test results, `latest.log`, screenshots and FPS numbers.

## How to report

Open an issue with one of the forms:

* **Bug report**: something is wrong.
* **GPU test report**: a plain test result of the checklist below, even if everything works.

Always include: GPU and driver version, Windows version, Bedray version and loader (NeoForge or Fabric), Minecraft / Sodium versions,
the tracing mode and upscaler, which vendor libraries you installed, `logs/latest.log`, a screenshot, and FPS if you measured
(read it from the Bedray block of the F3 screen and say the resolution and render distance).

## Diagnostics report (`/bedray report`)

Use it instead of posting a whole log. It never sends anything: it writes `<game folder>/bedray/reports/bedray-report-<UTC time>.txt` and
copies the same text to the clipboard. On NeoForge type `/bedray report` in the chat; on NeoForge and Fabric use **Copy diagnostics
report** (Video Settings, page *Advanced and Experimental*). Paste the text into the **Bug report** or **GPU test report** form.

What the report contains: Bedray, Minecraft, loader and Sodium versions; Java and the Bedray / memory JVM options; OS name and version,
CPU model, RAM; GPU, driver, VRAM, Vulkan version and the enabled device extensions (ray_query and the other ray tracing ones);
`bedray.toml` without comments; mode, upscaler, denoiser and frame generation state (requested, selected, runtime, fallback reason);
the F3 sections as text; average frame time and GPU milliseconds per phase of the last 30 s and 2 min; TLAS / BLAS / light counters;
names and found / missing state of the native library files (no paths); the last 40 WARN / ERROR entries of `latest.log`.

What is removed (replaced by `<redacted>`): all file paths (only the last file name stays), your Windows / Linux account name, the
computer name, your player nick, UUIDs, e-mail addresses, IP addresses (also IPv6), and values after `token`, `password`, `secret`,
`api_key` and `Bearer`. The session token, the session id and your profile are never read. It is technical information only, but read
it before you post it. Details: [PRIVACY.md](../PRIVACY.md).

## Sharing logs safely

Logs and crash reports contain your Windows user name and folder names. Before you post:

1. Open the file in a text editor and use search and replace for your user name (replace it with `USER`).
2. Check for any other personal names (instance names, folders) and remove them.
3. Never post passwords, tokens or account data; the mod never needs them.

If you do not want to post a whole log, paste the lines that contain `Bedray`, `VKB`, `ERROR` and `FAILED`, and the whole crash report.

## Looking for testers with AMD Radeon (RDNA 2+) and Intel Arc

Bedray has not been run on these cards. If you have one, please try the following on a **clean instance** (Minecraft 26.3, Sodium
0.9.x, Bedray) and use the "GPU test report" form.

| # | Check | Needs | What to report |
|---|---|---|---|
| 1 | The game starts with the Vulkan backend; the "Bedray ALPHA" pages appear in Video Settings | nothing | yes / no, log if no |
| 2 | Ray query: page **Ray Tracing**, **Tracing** offers Ray Tracing and Path Tracing; switch on Ray Tracing (Low) and enter a world | nothing | works / option missing / crash; screenshot; F3 Bedray block |
| 3 | Path Tracing (Fast) with accumulation (no denoiser library) | nothing | works / artifacts / crash; screenshot |
| 4 | Built-in upscalers: Bilinear, FSR 1, NIS at Performance | nothing | each works or not; screenshot |
| 5 | **FSR 3.1**: install the official AMD DLL ([INSTALL.md](INSTALL.md)), choose FSR, try Quality and Performance | AMD DLL | works / "library missing" / crash; log lines `[VKB-FSR]` |
| 6 | **XeSS**: install `libxess.dll`, choose XeSS, try Quality and Performance | Intel DLL | works / crash; log lines with `xess=` |
| 7 | **NRD**: Path Tracing with the NRD denoiser | self-built `NRD.dll` + `vkb_nrd.dll` | `denoiser_ready` in the log or the failure line |
| 8 | Stability: 10 minutes in a world with Ray Tracing Low and an upscaler | | crash or not, FPS, VRAM use |

(DLSS and frame generation are NVIDIA-only and can be skipped.)

## NVIDIA testers

Other NVIDIA cards (RTX 20/30/40/50) are welcome too. Useful checks: 1-4 above, then DLSS Super Resolution (if you built the wrapper),
DLSS Ray Reconstruction with Path Tracing, and frame generation on RTX 40/50.

## Screenshots wanted for the documentation

If you take nice, honest screenshots (settings pages, Ray Tracing, Path Tracing, with the F3 Bedray block visible), say in the issue
that we may use them in the README. Do not include your name or paths in the picture.

## Settings screen: screenshots and UI test

* `-Dvkbridge.autotest=settings_ui` (optional `-Dvkrtx.autotest.uiSizes=1280x720@3,1920x1080@2,...`): opens the Bedray settings at
  several window sizes and GUI scales, shoots them, checks that the right-hand panel ends above the Done / Apply / Undo buttons
  (log lines `event=panel ... bottom=`), uses the panel (compare chips, flip), option / group / page reset and the user presets
  (`bedray/presets`). Shots: `autotest/settings_ui/s<N>_<kind>_<WxH>_g<scale>.png`.
* `-Dvkbridge.autotest=settings_preview -Dvkbridge.rt.geometry=true`: builds five fixed scenes in "RTX Test World" (desert day, sunset,
  water, closed room, lava at night), switches each setting through the same fields as the page and saves one screenshot per value to
  `autotest/settings_preview/<option>_<value>.png` plus `manifest.properties` (captions). Values whose backend is missing on the
  machine are skipped (`event=skipped`). Then `python tools/make_preview_assets.py <that folder>` scales them to 640x360 into
  `common/src/main/resources/assets/bedray/textures/gui/preview/` (each file at most 400 KB, total at most 12 MB).
  `-Dvkrtx.autotest.previewOnly=bloom,water_style` captures only some sets.
