# Troubleshooting

Bedray is an **alpha**, tested only on an **NVIDIA GeForce RTX 5060 Ti (8 GB), Windows, driver 617.42**. On other GPUs a problem
may be a missing feature of the card or a bug that nobody has seen yet; please report it ([TESTING.md](TESTING.md)).

First: reproduce on a clean instance with only Sodium and Bedray. Keep `logs/latest.log`.

| Problem | What to check |
|---|---|
| The game does not start, loader error about a missing mod | Sodium 0.9.x must be installed, and the Bedray jar must be the one for your loader (NeoForge or Fabric). Remove any older separate library jar of earlier test builds from `mods/`; Bedray refuses to start next to it. |
| Crash at start, "Java 25" or class version error | Use Java 25. |
| No "Bedray ALPHA" pages in Video Settings | Sodium missing or wrong version; the Bedray jar of the other loader; mod loading errors in the log (search for `Bedray`). |
| The graphics backend is not Vulkan | Bedray writes `options.txt` at start; check that the game uses the Vulkan backend (F3 shows "Backend"). Update your GPU driver. |
| "Tracing" only offers Off | The GPU or driver does not report ray query. Update the driver. Check the log for `ray_query`. |
| An upscaler shows "library missing" | The vendor file is not found or is not the right file. Folders and names: [INSTALL.md](INSTALL.md). FSR also refuses a DLL that is not the official v1.1.4 build. Check the log lines listed in INSTALL.md section 4. |
| "restart required" next to an upscaler | The library appeared after the game started. Restart the game. |
| DLSS is listed as "GPU not supported" | DLSS needs an NVIDIA RTX GPU and a current driver. |
| Frame generation unavailable | See the table in [FRAME_GENERATION.md](FRAME_GENERATION.md). |
| Path Tracing looks grainy | Normal while moving. Install NRD or use DLSS Ray Reconstruction (NVIDIA), or keep accumulation on and stand still. |
| Out of memory, stutter, device lost | Lower the render distance and "RT Memory Budget", use a lower RT Quality, render the world smaller with an upscaler. |
| Very low FPS | Use a lower RT Quality or Off; ray and path tracing are expensive. Check the F3 Bedray block for the render resolution and the active upscaler. |
| Image flipped, offset or wrong size after changing the upscaler | Restart; report with a screenshot and log. |
| Black or white screen in Path Tracing | Report with the "RT Debug Buffer" screenshots (Page 3) and the log. |
| Settings are broken | Close the game, delete `config/bedray.toml`, start again. |

## Reading the log

`logs/latest.log` lines of Bedray start with tags like `[VKB-FSR]`, `[VKB-DLSS]`, `[VKB-RT]`, `[VKB-NRD]`, `[VKB-FG]`. Search for
`ERROR` and `FAILED`. A line `upscaler=rejected requested=... status=...` means the chosen backend could not be used and Bilinear
stays active.

## Sharing logs safely

Logs contain your Windows user name in file paths. Before you post a log, use search and replace in a text editor to change your
user name to `USER` everywhere, and remove anything else personal. Never post tokens or passwords. See [TESTING.md](TESTING.md).
