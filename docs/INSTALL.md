# Install Bedray (ALPHA)

Bedray is an alpha for Minecraft 26.3 and was tested only on an **NVIDIA GeForce RTX 5060 Ti (8 GB), Windows, driver 617.42**.
Use a **clean test instance**, not your main world or a large modpack.

## 1. Check your system

* Windows, a GPU with Vulkan 1.3 or 1.4 and ray query support (NVIDIA RTX, AMD RDNA 2 or newer, Intel Arc), a current driver.
* Java 25 (the Minecraft 26.3 launcher normally provides it).
* Ray tracing needs several GB of VRAM. With 8 GB use a render distance of about 12 chunks or less and keep the default "RT Memory Budget".

## 2. Install the mods

Create an instance with **Minecraft 26.3** and **NeoForge 26.3.0.x** or **Fabric** (fabric-loader 0.19.5 or newer). Put these jars into the
instance's `mods` folder. Bedray is **one self-contained jar**; no other library mod is needed:

1. **Sodium 0.9.x** for your loader (tested with 0.9.2). Download it from Modrinth or CurseForge.
2. The **Bedray jar** for your loader: `bedray-neoforge-mc26.3.0-<version>.jar` or `bedray-fabric-mc26.3-<version>.jar`.
   Check the SHA-256 given in the release description (PowerShell: `Get-FileHash <file> -Algorithm SHA256`).

Do not install the NeoForge and the Fabric jar together. Start the game once. If everything is right, **Options, Video Settings**
shows the pages **Bedray ALPHA: Upscaling, Ray Tracing, Advanced and Experimental**, and the F3 screen has "Bedray" sections (overview, load, quality, libraries; two more can be enabled in the F3+F6 screen).
The first start creates `config/bedray.toml`. If you used a pre-release build, your old `config/vulkan_rtx.toml` is copied once
(the old file is left in place).

At this point the **built-in upscalers (Bilinear, FSR 1, NIS)** and ray / path tracing with accumulation already work. Everything
below is optional.

## 3. Included and optional native libraries

Release jars include the unmodified AMD FidelityFX Vulkan runtime and Intel XeSS runtime with their license notices. Bedray extracts
them into `bedray/natives/` on first start. The source repository and ordinary developer builds do not include these binaries.

NVIDIA libraries and native wrappers are **not included**. You download or build them as described below, follow the vendor's
license, and copy them into your instance's **game folder** (the folder that contains `mods`, `config`,
`logs`; in many launchers it is `<instance>/minecraft`). Create the folders if they do not exist. Only official vendor downloads;
never DLLs from forums, file-sharing sites or other games' folders.

| Feature | Files | Folder (inside the game folder) | Official source |
|---|---|---|---|
| DLSS Super Resolution | `nvngx_dlss.dll` + `vkb_dlss.dll` | `bedray/natives/` | NVIDIA DLSS SDK: https://github.com/NVIDIA/DLSS |
| DLSS Ray Reconstruction | additionally `nvngx_dlssd.dll` | `bedray/natives/` | same |
| FSR 3.1 / 2.x | `amd_fidelityfx_vk.dll` (signed Vulkan DLL of FidelityFX SDK **v1.1.4**) | Included in release jar and extracted to `bedray/natives/` | https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases/tag/v1.1.4 |
| XeSS | `libxess.dll` (XeSS SDK **3.0.2**) | Included in release jar and extracted to `bedray/natives/` | https://github.com/intel/xess/releases |
| NRD denoiser (path tracing) | `NRD.dll` + `vkb_nrd.dll` | `bedray/natives/` | NRD sources: https://github.com/NVIDIA-RTX/NRD (you build `NRD.dll`) |
| Frame generation, Reflex | Streamline files + `nvngx_dlssg.dll` + `vkb_sl.dll` (with frame generation on, DLSS Super Resolution and Ray Reconstruction go through Streamline and need four more files from the same release, in `bedray/natives/streamline/`: **`sl.dlss.dll`, `sl.dlss_d.dll`, `nvngx_dlss.dll`, `nvngx_dlssd.dll`**; without them Ray Reconstruction falls back to NRD) | `bedray/natives/streamline/` | https://github.com/NVIDIAGameWorks/Streamline/releases |

(`bedray/natives/` is the shared folder for native libraries, relative to the game folder; its name can be changed with `-Dbedray.nativesRoot=<name>`. Folders of earlier test builds are still read, read-only, so existing instances keep working; move the files to `bedray/natives/` when convenient.)

### Files you must build yourself

`vkb_dlss.dll`, `vkb_nrd.dll` and `vkb_sl.dll` are small wrappers written for this project. They are built from the sources in
`native/` of this repository (`native/dlss_bridge/build.bat`, `native/nrd_bridge/build.bat`, `native/sl_bridge/build.bat`) with
Visual Studio Build Tools and the matching vendor SDK. `vkb_dlss.dll` statically contains NVIDIA's NGX library, so it
**cannot be redistributed**; every user who wants DLSS builds it. This is a real hurdle in the alpha. See `docs/BUILDING.md`
and `native/*/build.bat` (the scripts read the SDK locations from environment variables).

### Exact file lists

* **DLSS:** `nvngx_dlss.dll` (Super Resolution), `nvngx_dlssd.dll` (Ray Reconstruction), `vkb_dlss.dll`. A restart is needed if the
  files appear after the game has started (the page says "restart required").
* **FSR:** the DLL must be the official build. The mod checks its SHA-256 against the known official file
  (`a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78` for `PrebuiltSignedDLL/amd_fidelityfx_vk.dll` of v1.1.4). Other builds
  are refused ("library missing" on the page). FSR 4 is not available on Vulkan.
* **XeSS:** `libxess.dll` only. Release jars include the approved SDK 3.0.2 binary and notices.
* **Frame generation:** `sl.interposer.dll`, `sl.common.dll`, `sl.dlss_g.dll`, `sl.reflex.dll`, `sl.pcl.dll`, `nvngx_dlssg.dll` (from the
  Streamline release; use a 2.14.x release, the mod was developed against 2.14.1) and `vkb_sl.dll`.
  With frame generation on, also put `sl.dlss.dll`, `sl.dlss_d.dll`, `nvngx_dlss.dll` and `nvngx_dlssd.dll` into the same folder, otherwise DLSS Ray
  Reconstruction falls back to NRD (the F3 line shows the fallback).
  Streamline is loaded when the game starts, so restart the game after adding the files. See `docs/FRAME_GENERATION.md`.
* **NRD:** `NRD.dll` (built from NRD 4.17.x or 4.18.x with SPIR-V shaders) and `vkb_nrd.dll`.

## 4. Check that a library was picked up

Open `logs/latest.log` after starting the game and search for these lines:

| Feature | Good sign | Bad sign |
|---|---|---|
| DLSS | `ngx_init code=0` | `[VKB-DLSS]` with `FAILED` or "library missing" |
| FSR | `[VKB-FSR] library usable=true` | `usable=false reason=...` |
| XeSS | `xess=lib_loaded` | `xess=FAILED ... dll_missing` |
| NRD | `[VKB-NRD] denoiser_ready` | `[VKB-NRD] status=disabled` |
| Frame generation | F3 line `[FRAME-GEN] Active ...` | `streamline_not_found`, `shim_not_found` |

The settings page also shows "library missing" next to a backend whose files are not found, and selecting it has no effect.

## Updating and removing

Replace the Bedray jar; delete `config/bedray.toml` if settings look wrong (it is recreated). To remove Bedray delete its jar, the
the folders you created above. Vendor DLLs are not removed automatically.
