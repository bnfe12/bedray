# Privacy

Bedray has no background telemetry. It makes no HTTP requests of its own. The explicit **Prepare GitHub test report** action
opens the user's browser on the public Bedray issue form with a short scrubbed hardware/build/performance summary in the URL.
The browser sends that summary to GitHub; the user reviews the form, attaches a local ZIP and submits it through their own account.
Bedray never reads or stores GitHub credentials or submits an issue automatically.

## What stays on your computer

* `config/bedray.toml`: your Bedray settings.
* `bedray/natives/`: vendor libraries you installed yourself.
* `bedray/reports/bedray-report-<time>.txt`: a diagnostics report, **only when you ask for one** (`/bedray report` on NeoForge, or the
  "Copy diagnostics report" button in Video Settings). The same text is copied to your clipboard.
* `bedray/profiles/capture-*/`: an explicit 30 second gameplay capture, including application FPS, 1% low, frame intervals,
  per-frame rendering facts and available CPU/GPU timings. Generated display frames do not inflate these FPS statistics.
* `bedray/reports/bedray-test-*.zip`: a tester bundle prepared after a requested FPS capture or by `/bedray share`.
  It contains scrubbed diagnostics, at most the last 2 MiB of `latest.log`, and the last capture from the current session.
  It includes no world saves, account files, launcher credentials or screenshots.

## What a diagnostics report contains

Technical information for bug reports: Bedray, Minecraft, loader and Sodium versions; OS name and version; CPU model; GPU, driver, VRAM,
Vulkan version and enabled extensions; Bedray settings; the state of the renderer (mode, upscaler, denoiser, frame generation);
average FPS and GPU times; counters; names of native library files; the last warnings and errors of `latest.log`.

Before the text is written it is scrubbed: file paths (only the file name is kept), your account name, the computer name, your player
nick, UUIDs, e-mail addresses, IP addresses and values after `token` / `password` / `secret` / `api_key` / `Bearer` are replaced by
`<redacted>`. The session token and session id are never read.

## What is not done

No files are uploaded by Bedray. The GitHub action opens a review form and the reports folder; the user attaches and submits.
Scrubbing is automatic but not perfect: read the report before posting and remove anything else you do not want to share.

## Mosaic Studio

Mosaic Studio, the node editor app, makes **no network requests on its own**. It has no telemetry, no account and no update
check. Only the GitHub actions you start yourself (File > This project > Publish to / Update from GitHub, Clone from
GitHub on the start screen, `mosaic project clone / refresh`) reach the network: they run your own `git` and `gh`, and
Update reads the public description and topics of the project's repository (api.github.com) when `gh` is not installed.

* **Reads** your worlds when you pick one in Preview > World (region files of the save, read only).
* **Reads** (never changes): Minecraft launcher folders (official launcher, Prism / MultiMC / PolyMC, CurseForge, Modrinth App,
  ATLauncher, GDLauncher) and Gradle caches, to list game instances, client jars, resource packs and mods for the texture
  library. Only file names, versions and the textures you open are read.
* **Writes** only where you work: its settings (`~/.bedray/mosaic-studio.json`), your shader graphs and their `.studio/`
  folder, the `Mosaic Materials` resource pack and `bedray-studio/` of the game folder you chose (or `~/.bedray/studio/`).
* **The game side** writes small result frames, a status line and the list of Bedray add-ons into `.studio/` of the active
  shader pack only when the studio created that folder, and reads the studio's requests from it. Nothing leaves your computer.
* Planned features that will talk to services (AI assistants, Modrinth / CurseForge publishing, GitHub saving) will be
  opt-in, will ask before sending anything, and will keep keys only on your computer. This section will say exactly what
  each one sends before it ships.

