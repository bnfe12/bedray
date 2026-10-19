# Changelog

## 0.1.0-alpha.1 (2026-10-20, ALPHA)

First public alpha. Tested only on an NVIDIA GeForce RTX 5060 Ti (8 GB), Windows, driver 617.42.

### Name and files

* The project is called **Bedray**: mod id `bedray`, jars `bedray-neoforge-mc26.3.0-<version>.jar` and
  `bedray-fabric-mc26.3-<version>.jar`.
* Settings file is now `config/bedray.toml`. An existing `config/vulkan_rtx.toml` is copied once (the old file stays).
* One self-contained jar per loader: the platform layer is built in (directory `core/`), no other mod is needed besides Sodium.
* Native libraries live in `bedray/natives/` (and `bedray/natives/streamline/`); the folder name can be changed with
  `-Dbedray.nativesRoot=<name>`. Release jars include the exact AMD FidelityFX Vulkan and Intel XeSS redistributable DLLs;
  NVIDIA libraries, Streamline DLLs and native wrappers remain external. Folders of earlier test builds are still read, read-only.

### Features (see docs/FEATURE_MATRIX.md for status and evidence)

* Path Tracing (main quality mode) and Ray Tracing (fast, experimental) modes on the native Vulkan backend (ray query), with shadows, ambient occlusion, reflections,
  emissive block light, one-bounce GI, LabPBR and automatic materials. EXPERIMENTAL.
* Upscalers: DLSS Super Resolution, XeSS, FSR 3.1 / 2.x, built-in Bilinear, FSR 1 and NIS.
* Denoisers: NRD, DLSS Ray Reconstruction, accumulation.
* NVIDIA DLSS frame generation (2x, 3x, 4x) and Reflex through Streamline. BETA, tested only on an RTX 5060 Ti. With it on, DLSS
  Super Resolution and Ray Reconstruction use Streamline plugins.
* New F3 screen in sections, with real and generated FPS.
* Settings pages "Bedray ALPHA" in Sodium Video Settings, F3 information block, hotkeys.
* NeoForge and Fabric builds.

### Honesty changes

* The settings page and the F3 screen are marked ALPHA.
* The "DLSS 5" line is labelled "not implemented" and cannot be switched on.
* The F3 line of the unfinished Iris / "Dirt RT" host is hidden unless such a pack is selected.
* Removed a joke tooltip from the settings page.

### Not included

Iris / Dirt RT shader-pack host, DLSS 5, AMD frame generation, FSR 4, Linux and macOS. NVIDIA vendor binaries and native wrappers
are not shipped; see docs/INSTALL.md for the exact bundled runtime files and license notices.
