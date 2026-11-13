# Runtime DLL reference

[Downloads](RUNTIME_LIBRARIES.md) · [Technologies](TECHNOLOGIES.md) · [GPU recommendations](GPU_GUIDE.md) · [Licences](NATIVES_LICENSES.md)

Updated **2026-11-13**. Scope: files named by Bedray's native catalogue and player packager, the reviewed newer AMD/Intel/NRC SDKs,
and relevant system dependencies. Choose the set for your exact build; this is not a request to install every file.
`natives/` below means `bedray/natives/` inside the game instance.

## Current renderer and vendor paths

The optional development `-all-natives.jar` can package matching runtime files and notices. Its existence does not approve a public
release under every SDK licence. Ordinary source-build JARs may need manual setup. Follow the release manifest and [licence policy](NATIVES_LICENSES.md).

| File | Purpose | Destination / source |
| :--- | :--- | :--- |
| `bedray_core.dll` | Bedray renderer and embedded GPU-scene implementation. | `natives/`; built from the same Bedray revision as the JAR. |
| `amd_fidelityfx_vk.dll` | Current FSR 2 / 3.1 Vulkan runtime. | `natives/`; reviewed FidelityFX SDK 1.1.4 binary or a matching Bedray package. |
| `libxess.dll` | XeSS Super Resolution, including the current Vulkan interface. | `natives/`; reviewed XeSS SDK 3.0.2 binary or a matching package. |
| `nvngx_dlss.dll` | DLSS Super Resolution / DLAA. | `natives/`; also `natives/streamline/` when using the Streamline SR path. |
| `nvngx_dlssd.dll` | DLSS Ray Reconstruction model/runtime. | Same locations as DLSS SR, according to the active path. |
| `vkb_dlss.dll` | Bedray's NGX integration, including the NR-capable variant when built that way. | `natives/`; matching Bedray/SDK wrapper build. |
| `NRD.dll` | NRD REBLUR / ReLAX runtime. | `natives/`; matching NRD build with SPIR-V support. |
| `vkb_nrd.dll` | Bedray's NRD integration. | `natives/`; matching Bedray wrapper build. |
| `vkb_sl.dll` | Bedray's Streamline integration. | `natives/streamline/`; matching wrapper build. |
| `sl.interposer.dll` | Streamline loader/interposer. | `natives/streamline/`; one consistent Streamline runtime set. |
| `sl.common.dll` | Streamline shared runtime. | `natives/streamline/`; same set. |
| `sl.dlss.dll` | Streamline Super Resolution plugin. | `natives/streamline/`; also needs `nvngx_dlss.dll` there. |
| `sl.dlss_d.dll` | Streamline Ray Reconstruction plugin. | `natives/streamline/`; also needs `nvngx_dlssd.dll` there. |
| `sl.dlss_g.dll` | Streamline frame-generation plugin. | `natives/streamline/`; matching frame-generation runtime. |
| `sl.reflex.dll` | Streamline Reflex plugin. | `natives/streamline/`; same set. |
| `sl.pcl.dll` | Streamline latency-marker component. | `natives/streamline/`; same set. |
| `nvngx_dlssg.dll` | NVIDIA frame-generation runtime/model. | `natives/streamline/`; matching Streamline package. |
| `NvLowLatencyVk.dll` | NVIDIA Vulkan low-latency component, when used by the selected runtime. | `natives/streamline/`; official matching package. The catalogue treats it as optional; the full packager includes it. |
| `nvngx_dlssnr.dll` | Experimental DLSS 5 Neural Rendering model. | `natives/`; exact SDK/model pair from the [DLSS 5 guide](experiments/dlss5.md), with applicable terms. |
| `sl.dlss_nr.dll` | Reserved Streamline Neural Rendering diagnostic slot. | No working Bedray Streamline-NR integration is claimed. Current experimental NR uses the direct NGX path. |

Sources: [Bedray catalogue](https://github.com/bnfe12/bedray/blob/main/core/src/main/java/io/github/andrexarlay/vulkanbridge/core/NativeCatalog.java),
[player packager](https://github.com/bnfe12/bedray/blob/main/tools/package_windows_jar.py), [installation file lists](INSTALL.md#exact-file-lists).
Manual documentation includes Streamline 2.14.1; the newer single-JAR packager pins 2.14.16. Keep the set selected by your build together.
DLSS and NRD use RTX SDK terms; Streamline core and other components have separate terms. See [the policy](NATIVES_LICENSES.md).

## New AMD FSR SDK 2.4 files

These are real files in AMD's reviewed `v2.4.0` signed-binary directory. **Bedray has not integrated this newer API.**
The current FidelityFX 1.1.4 DLL is not interchangeable with this loader/effect set.

| File | Vendor purpose | Bedray use today |
| :--- | :--- | :--- |
| `amd_fidelityfx_loader_vk.dll` | Vulkan API loader. | Install alongside the signed upscaler in the native directory. |
| `amd_fidelityfx_upscaler_vk.dll` | Vulkan upscaling effect, including FSR 4.1.2, 3.1.6 and 2.3.4. | Install with the SDK 2.4 loader or use a local single-JAR build that includes the pair. |
| `amd_fidelityfx_framegeneration_vk.dll` | Vulkan frame-generation effect. | Pending integration. |
| `amd_fidelityfx_loader_dx12.dll` | DirectX 12 API loader. | Not used by Bedray's native Vulkan integration. |
| `amd_fidelityfx_upscaler_dx12.dll` | DirectX 12 upscaling effect. | Not used by that integration. |
| `amd_fidelityfx_framegeneration_dx12.dll` | DirectX 12 frame-generation effect. | Not used by that integration. |
| `amd_fidelityfx_denoiser_dx12.dll` | AMD Ray Regeneration effect. | No reviewed Vulkan counterpart; not integrated. |
| `amd_fidelityfx_radiancecache_dx12.dll` | AMD radiance-cache effect. | No reviewed Vulkan counterpart; not integrated. |

[Exact AMD binary directory](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/tree/7539ae8b6568ad29ed78a753404351daf3b5cca7/Kits/FidelityFX/signedbin),
[SDK requirements](https://gpuopen.com/amd-fsr-sdk/),
[runtime licence](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v2.4.0/Kits/FidelityFX/docs/license.md).
The runtime's binary-distribution terms differ from the MIT licence on the older 1.1.4 component and selected sample sources.

## Intel files beyond current Super Resolution

| File | Purpose | Bedray use today |
| :--- | :--- | :--- |
| `libxess_fg.dll` | XeSS Frame Generation. | Catalogue slot only. Reviewed public interface requires DirectX 12 and XeLL; not integrated. |
| `libxell.dll` | Xe Low Latency. | Catalogue slot only; not integrated. |
| `libxess_dx11.dll` | XeSS DirectX 11 runtime. | Not required by the Vulkan integration. |

[Intel's exact 3.0.2 binaries](https://github.com/intel/xess/tree/v3.0.2/bin),
[FG requirements](https://github.com/intel/xess/blob/v3.0.2/doc/xess_fg_developer_guide_english.md),
[Intel licence](https://github.com/intel/xess/blob/v3.0.2/LICENSE.txt).
The existing reviewed bundle includes `libxess.dll`; it does not establish a reviewed hash or an integration for every other Intel file.

## NVIDIA NRC and its dependencies

**NRC is not integrated in Bedray.** The following x64 files are listed by NVIDIA NRC revision `7de30e4eb2a1a27118eb9a6acf5f715d757240c0`:

| File | Purpose | Bedray use today |
| :--- | :--- | :--- |
| `NRC_Vulkan.dll` | NRC's actual Vulkan runtime. | Future integration; not a substitute for a Bedray wrapper. |
| `NRC_D3D12.dll` | NRC's DirectX 12 runtime. | Not a Vulkan runtime dependency. |
| `cudart64_13.dll` | CUDA 13 runtime dependency in the reviewed NRC package. | Needed only for an eventual matching NRC integration. |
| `nvrtc64_130_0.dll` | CUDA runtime compilation dependency in that package. | Same SDK-specific dependency set. |
| `nvrtc-builtins64_134.dll` | NVRTC built-ins in that package. | Same SDK-specific dependency set. |

[Exact NRC x64 directory](https://github.com/NVIDIA-RTX/NRC/tree/7de30e4eb2a1a27118eb9a6acf5f715d757240c0/Bin/x64),
[NRC guide](https://github.com/NVIDIA-RTX/RTXGI/blob/main/Docs/NrcGuide.md),
[NRC licence](https://github.com/NVIDIA-RTX/NRC/blob/7de30e4eb2a1a27118eb9a6acf5f715d757240c0/License.md).
Preserve the package's dependency versions and review the [CUDA redistribution terms](https://docs.nvidia.com/cuda/eula/index.html) as well.
Some upstream binary entries use Git LFS; a small pointer file is not a DLL.

## System and development files

| File | Owner / handling |
| :--- | :--- |
| `vulkan-1.dll` | Vulkan loader supplied with the graphics runtime/driver installation. |
| `nvoglv64.dll` | NVIDIA driver component; install the vendor driver rather than copying it into the mod folder. |
| `nvapi64.dll` | NVIDIA driver/API component checked by Bedray's provider detection. It is not a Bedray download requirement. |
| `dxgi.dll` | Windows DirectX infrastructure. Use the system installation. |
| `d3d12.dll` | Windows DirectX runtime; detection does not turn the Vulkan renderer into a DirectX 12 integration. |
| `msvcp140.dll` | Microsoft C++ runtime; Intel's FG guide lists it as a dependency. |
| `vcruntime140.dll` | Microsoft C++ runtime; use the official matching redistributable. |
| `vcruntime140_1.dll` | Same Microsoft runtime family. |
| `bedray_scene.dll` | Standalone developer ABI target. The current player core embeds scene exports. |
| `bedray_gpu_resources_abi.dll` | Optional developer-only shared-ABI target, off by default. Player resource owners are linked into the core. |

Use Microsoft's [official Visual C++ Redistributable guidance](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist)
for runtime prerequisites. Build-tool SDKs and CPU fault-test DLLs belong to development/CI, not the player installation list.

## Existing catalogue placeholders

| Name found in the catalogue | What the reviewed sources establish |
| :--- | :--- |
| `ffx_fsr4_x64.dll` | Reserved name; it is not a filename in the reviewed AMD SDK 2.4 signed-binary directory. |
| `amd_fidelityfx_ai_vk.dll` | Same; the real reviewed Vulkan loader/upscaler filenames are listed above. |
| `nv_nrc.dll` | Reserved name; the reviewed NVIDIA Vulkan runtime is `NRC_Vulkan.dll`. |
| `directsr.dll` | Reserved DirectSR slot; no Bedray implementation is established. |

These placeholder strings are not instructions to find files on download sites. Their presence in the catalogue does not establish a working feature.
