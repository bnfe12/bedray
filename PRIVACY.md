# Privacy

Bedray does not collect, store or send personal data, and it has no telemetry. It makes **no network connections of its own**.

## What stays on your computer

* `config/bedray.toml`: your Bedray settings.
* `bedray/natives/`: vendor libraries you installed yourself.
* `bedray/reports/bedray-report-<time>.txt`: a diagnostics report, **only when you ask for one** (`/bedray report` on NeoForge, or the
  "Copy diagnostics report" button in Video Settings). The same text is copied to your clipboard.

## What a diagnostics report contains

Technical information for bug reports: Bedray, Minecraft, loader and Sodium versions; OS name and version; CPU model; GPU, driver, VRAM,
Vulkan version and enabled extensions; Bedray settings; the state of the renderer (mode, upscaler, denoiser, frame generation);
average FPS and GPU times; counters; names of native library files; the last warnings and errors of `latest.log`.

Before the text is written it is scrubbed: file paths (only the file name is kept), your account name, the computer name, your player
nick, UUIDs, e-mail addresses, IP addresses and values after `token` / `password` / `secret` / `api_key` / `Bearer` are replaced by
`<redacted>`. The session token and session id are never read.

## What is not done

Nothing is uploaded or sent, ever; you decide whether to attach a report to an issue. Scrubbing is automatic but not perfect: read the
report before you post it, and remove anything else you do not want to share (for example a custom instance name).
