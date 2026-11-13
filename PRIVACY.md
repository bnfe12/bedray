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
