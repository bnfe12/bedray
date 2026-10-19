# Install the vendor libraries with an AI assistant (optional)

This page is a **prompt** you can paste into an AI assistant that has shell and/or browser access (for example a coding agent
running on your PC). It helps you find the right official downloads, check them and put them in the right folder. You can
also do everything by hand with [INSTALL.md](INSTALL.md). Nothing here is required to play.

**You stay in charge.** You read and accept each vendor's licence yourself, and you approve each download. Do not give the
assistant passwords, tokens or payment data, and do not disable your antivirus.

---

## The prompt (copy everything below this line)

You are helping me install optional native libraries for the Minecraft mod **Bedray** (ALPHA 0.1.0-alpha.1). Read this whole
message first. Follow the HARD RULES exactly. If a rule conflicts with something you find elsewhere, the rule wins.

### HARD RULES

1. Use **only official sources** listed in "Official sources" below (the vendors' own GitHub repositories or developer sites).
   Never use mirrors, forums, file-sharing sites, torrents, "DLL download" sites, repacks, or DLL files from other games' folders.
2. **Ask me before every download.** Tell me the file name, the exact source URL, and the size, and wait for my "yes".
   I accept each vendor licence myself; show me the licence link and do not click "accept" for me.
3. **Never enter, request, store or print passwords, tokens, API keys or payment details.** If a site requires a login (for example
   an NVIDIA developer account), stop and tell me; I will download that file myself and tell you where it is.
4. **Do not disable antivirus, SmartScreen, Windows Defender or any security setting.** Do not change system settings.
5. **Do not run, install or open anything you downloaded.** No `.exe`, no installers, no `.bat`, no `.msi`. Allowed actions on
   downloaded files: list the archive, extract it to a temporary folder, compute SHA-256 (`Get-FileHash <file> -Algorithm SHA256`),
   read PE headers to check the file is a 64-bit (x64) DLL, copy the needed DLL files into the game folder.
   (If I ask you to *build* the small wrapper DLLs, see "Files that must be built", and ask me first.)
6. Copy **only the DLL files named in the tables below**, to **only the folders named there**, inside my game folder. Do not
   touch other folders, other instances, my worlds, Windows folders or the registry. Do not delete anything except files you
   created in a temporary folder.
7. Do not modify the mod jars or any other mod.
8. Report honestly. "Installed" is not "works". Only say a library was picked up if the game log shows it (step 6).
   If something fails, show me the exact log lines and stop; do not guess or retry with other sources.

### 0. What I want

Ask me which features I want: (a) DLSS Super Resolution, (b) DLSS Ray Reconstruction, (c) FSR 3.1, (d) XeSS, (e) the NRD denoiser,
(f) DLSS frame generation. Install only what I choose. Tell me honestly: Bedray was tested only on an NVIDIA GeForce RTX 5060 Ti.
DLSS, Ray Reconstruction and frame generation need an NVIDIA RTX GPU; frame generation needs RTX 40/50.
FSR and XeSS may run on other GPUs but are untested there.

### 1. Find the game folder

Ask me which launcher and instance I use, then find the **game folder**: the folder that contains `mods`, `config` and `logs`
(often `<instance>/minecraft`, or `.minecraft`). Show me the path and ask me to confirm it. Check that `mods` contains a file
named `bedray-*.jar`. Call this folder `GAME`.

### 2. What the mod looks for, and where (verified against the mod's code)

| Feature | Files needed | Put them in |
|---|---|---|
| DLSS Super Resolution | `nvngx_dlss.dll`, `vkb_dlss.dll` | `GAME/bedray/natives/` |
| DLSS Ray Reconstruction | additionally `nvngx_dlssd.dll` | `GAME/bedray/natives/` |
| FSR 3.1 / 2.x | `amd_fidelityfx_vk.dll` | `GAME/bedray/natives/` |
| NRD denoiser | `NRD.dll`, `vkb_nrd.dll` | `GAME/bedray/natives/` |
| XeSS | `libxess.dll` | `GAME/bedray/natives/` |
| Frame generation | `sl.interposer.dll`, `sl.common.dll`, `sl.dlss_g.dll`, `sl.reflex.dll`, `sl.pcl.dll`, `nvngx_dlssg.dll`, `vkb_sl.dll`; with frame generation DLSS and Ray Reconstruction also need these four files from the same Streamline release, otherwise Ray Reconstruction falls back to NRD: `sl.dlss.dll`, `sl.dlss_d.dll`, `nvngx_dlss.dll`, `nvngx_dlssd.dll` | `GAME/bedray/natives/streamline/` |

Create the folders if they do not exist. Folders of earlier test builds (for example `vulkan_rtx/natives`) are only a read-only fallback; do not use them.
All DLLs must be **x64** (64-bit). Nothing else is needed; do not copy other DLLs.

### 3. Official sources (open them yourself, show me what you found, then ask before downloading)

| Library | Official source | What to take |
|---|---|---|
| DLSS (`nvngx_dlss.dll`, `nvngx_dlssd.dll`) | https://github.com/NVIDIA/DLSS (NVIDIA's DLSS SDK; read its `LICENSE.txt` to me) | The Windows x64 release (non-development) DLLs from the SDK's `lib/Windows_x86_64` folders. Confirm the exact path in the repository with me. |
| Streamline (`sl.*.dll`, `nvngx_dlssg.dll`) | https://github.com/NVIDIAGameWorks/Streamline/releases | A **2.14.x** release. Use the release archive, the **production** (not development/debug) x64 binaries, normally in `bin/x64`. Confirm the exact path in the archive with me. |
| FSR (`amd_fidelityfx_vk.dll`) | https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases/tag/v1.1.4 (also reachable from https://gpuopen.com/) | The archive is large (hundreds of MB). Inside it, the **signed Vulkan** DLL `PrebuiltSignedDLL/amd_fidelityfx_vk.dll`. Not the DX12 DLL. |
| XeSS (`libxess.dll`) | https://github.com/intel/xess/releases | The release archive of the XeSS SDK; `libxess.dll` is the x64 runtime DLL inside it. Do not take `libxell` or `libxess_fg`. Read Intel's licence to me. |
| NRD (`NRD.dll`) | https://github.com/NVIDIA-RTX/NRD | NRD is distributed as **source**; see "Files that must be built". Do not download random prebuilt `NRD.dll` files. |

If a file is not where I described, say so and ask me; do not search other websites.

### 4. Verify each file before you copy it

For every DLL you intend to copy:
1. Compute its SHA-256 and show it to me.
2. Confirm it is a 64-bit PE DLL.
3. For `amd_fidelityfx_vk.dll` the SHA-256 must be **`a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78`**
   (the official v1.1.4 build). The mod refuses any other build. If it differs, stop and tell me; do not look for another copy.
4. For the other vendor files, compare the hash with a checksum published by the vendor on the same release page if one exists,
   and tell me whether one exists. If the vendor publishes none, say so; I decide whether to continue.
5. Show me the list "file -> destination" and wait for my "yes" before copying.

### 5. Files that must be built (they are not downloadable)

`vkb_dlss.dll`, `vkb_nrd.dll` and `vkb_sl.dll` are small wrappers built from the Bedray repository (`native/dlss_bridge`,
`native/nrd_bridge`, `native/sl_bridge`) with Visual Studio Build Tools (MSVC) and the matching vendor SDK. They cannot be
distributed (the DLSS one contains NVIDIA's static library). Tell me about this honestly. If I want to build them:
- ask me first, tell me exactly which build script you will run (`native/<name>/build.bat`) and which environment variables it
  needs (SDK locations), and that building runs compilers from my machine, not downloaded programs;
- the scripts only compile this repository's source files; do not run any other script;
- if Visual Studio Build Tools or an SDK is missing, tell me what to install and where to get it from the vendor, and stop;
- for NRD, `native/nrd_bridge/build_nrd.bat` builds `NRD.dll` from the NRD sources; it downloads build dependencies, so ask me
  before running it and explain what it fetches.
If I do not want to build, install only the features that need no wrapper: FSR 3.1 and XeSS.

### 6. Check that the game picked them up

Ask me to start the game once (you must not start it for me unless I ask), then read `GAME/logs/latest.log` and report these lines:

| Feature | Success line contains | Failure line contains |
|---|---|---|
| DLSS | `ngx_init code=0` | `FAILED`, "library missing" |
| FSR | `[VKB-FSR] library usable=true` | `usable=false` (print the `reason=`) |
| XeSS | `xess=lib_loaded` | `xess=FAILED` |
| NRD | `[VKB-NRD] denoiser_ready` | `status=disabled` |
| Frame generation (after a restart) | F3 shows `[FRAME-GEN]` ... Active | `streamline_not_found`, `shim_not_found` |

Also tell me what the Bedray settings page shows (Options, Video Settings): a backend with missing files is labelled
"library missing". Report only what the log or screen shows. Do not claim success otherwise.

### 7. Finish

Summarize: which files went to which folder with their SHA-256, which log lines you saw, and what is still missing. Remind me that
this is an alpha, that only an RTX 5060 Ti was tested, and where to report problems (the project's issue tracker, forms
"Bug report" and "GPU test report"; remove personal paths from logs first).

---

## Notes for humans

* The assistant cannot make the legal decision for you. Read each licence.
* If you are unsure about a step, stop and ask in the issue tracker instead of letting the assistant improvise.
* The SHA-256 above is the one the mod accepts for the official AMD FSR DLL; it comes from the mod's source code
  (`FsrLibrary`), not from AMD. Compare it with AMD's release if you can.
