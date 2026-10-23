# Third-party notices

Bedray is licensed under **LGPL-3.0-or-later** (`LICENSE`, which refers to the GPL-3.0 text in `COPYING`). This file lists third-party code that is part of the repository
or the released jars, and third-party software the mod uses but does **not** ship.

## Source components

The components below are in the source repository. Source files are compiled into the mod jars where noted; the Gradle wrapper is
build tooling only.

| Component | Where | Licence | Notes |
|---|---|---|---|
| Iris shader option/profile format rules and configurable const-name table, IrisShaders/Iris commit `671a5c87747d33983d7ed659461a64f46dfcb1f0` (reviewed 2026-10-21) | `common/.../shaderhost/iris/IrisPackOptions.java` | LGPL-3.0, Copyright the Iris contributors | Adapted rules from `OptionAnnotatedSource`, `MutableOptionValues`, `ProfileSet` and `Profile`: source option discovery/editing, sibling `.txt` settings, profile inheritance/matching and disabled programs. Bedray's implementation is independent, bounded, and deliberately stricter for ambiguous/unsupported values. It does not include or load the Iris renderer. Upstream sources: https://github.com/IrisShaders/Iris/tree/671a5c87747d33983d7ed659461a64f46dfcb1f0/common/src/main/java/net/irisshaders/iris/shaderpack/option . Licence text: `LICENSE` and `COPYING`. |
| AMD FidelityFX Super Resolution 1 shader header `ffx_fsr1.h` (FidelityFX SDK) | `common/src/main/resources/shaders/third_party/fsr1/` | MIT, Copyright Advanced Micro Devices, Inc. | Unmodified. Used by the built-in "FSR 1" upscaler together with our own GLSL wrapper (`shaders/spatial/`). The licence text is kept in the file header. |
| NVIDIA Image Scaling (NIS) SDK v1.0.3 headers `NIS_Scaler.h`, `NIS_Config.h` | `common/src/main/resources/shaders/third_party/nis/` | MIT, Copyright NVIDIA CORPORATION & AFFILIATES | Three small in-memory patches are applied at build time of the shader source (see `SpatialShaderSource`). The licence text is kept in the file headers. Used by the built-in "NIS" upscaler. |
| `nrd_wrapper.cpp`, `nrd_wrapper.hpp` from nvpro-samples/vk_denoise_nrd (commit 67f28c7c6a7bd2495bd6382dac8cffbf85673858) | `native/nrd_bridge/third_party/vk_denoise_nrd/` | Apache License 2.0, Copyright (c) 2024-2025 NVIDIA CORPORATION | Unmodified copies kept for attribution, not compiled. `native/nrd_bridge/src/vkb_nrd.cpp` is a derived work; its changes are listed in `native/nrd_bridge/third_party/vk_denoise_nrd/NOTICE` (Apache-2.0 section 4(b)). It does not contain NRD code. |

| Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) | repository root, `gradle/wrapper/` | Apache License 2.0, Copyright Gradle Inc. | Unmodified; build tooling only, not part of the mod jars. |

## Included in release jars only

These vendor binaries are not in the source repository. Release builds include only the exact unmodified DLLs listed below.
Their license files are stored in each JAR under `META-INF/licenses/natives/<dll>/` and copied to
`<game>/bedray/natives/licenses/<dll>/` when the DLL is extracted.

| Component | Version and SHA-256 | Licence and required notices |
|---|---|---|
| `amd_fidelityfx_vk.dll` (Vulkan runtime) | FidelityFX SDK v1.1.4; `a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78` | AMD FidelityFX SDK license (`LICENSE.txt`); Advanced Micro Devices, Inc. copyright and warranty terms. |
| `libxess.dll` (XeSS runtime) | XeSS SDK 3.0.2; `251659dd84a3e84de67c886a4186e01f3eca49b00641906fe38bb6b807e5d5b7` | Intel Simplified Software License (`LICENSE.txt`) and Intel SDK `third-party-programs.txt`. |

## Used as dependencies, not included

| Component | Licence | Notes |
|---|---|---|
| Minecraft (Mojang Studios) | Minecraft EULA | Never included. The build uses a local copy of the game jar. |
| NeoForge, Fabric Loader | LGPL-2.1 / Apache-2.0 (see their projects) | Compile-time only, never packaged. |
| Sodium | Its own licence, see the Sodium project | The mod adds a settings page and mixins into Sodium at run time. No Sodium file is included or patched on disk. |
| LWJGL (incl. shaderc), SLF4J, Mixin | BSD-3-Clause, MIT, MIT | Provided by the game / loader at run time. |
| Bedray Core (internal platform layer, directory `core/`) | LGPL-3.0-or-later | Same author, same licence as Bedray. Packaged inside the Bedray jar; its source is in this repository. |

## Ideas and references (no code copied)

* Radiance (GPL-3.0), Vulkanite (LGPL-3.0), Iris (LGPL-3.0), Vitrail Shaders: read as references for the ray tracing pipeline,
  the Sodium mesh decoding approach and the shader-pack host design. No source or resource from them is included.
* LabPBR material standard (https://shaderlabs.org/wiki/LabPBR_Material_Standard): the `_s` / `_n` decoding follows the public
  specification.

## Vendor SDKs and libraries not shipped

The following are not in the repository or release jars. Download them from the vendor and follow the vendor's license
(see `docs/INSTALL.md`).

| Library | Vendor | Licence |
|---|---|---|
| `nvngx_dlss.dll`, `nvngx_dlssd.dll`, `nvngx_dlssg.dll` (DLSS Super Resolution, Ray Reconstruction, Frame Generation) | NVIDIA | NVIDIA RTX SDKs licence (DLSS SDK) |
| Streamline (`sl.interposer.dll`, `sl.common.dll`, `sl.dlss_g.dll`, `sl.reflex.dll`, `sl.pcl.dll`) | NVIDIA | NVIDIA RTX SDKs licence / Streamline licence |
| NVIDIA Real-Time Denoisers (`NRD.dll`, built from the NRD sources) | NVIDIA | NVIDIA RTX SDKs licence |

Our own wrappers `vkb_dlss.dll`, `vkb_nrd.dll` and `vkb_sl.dll` are built from the sources in `native/`. `vkb_dlss.dll`
statically links NVIDIA's `nvsdk_ngx_s.lib`, so it is not redistributed either: you build it yourself with the NVIDIA SDK.
