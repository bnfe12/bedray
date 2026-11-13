# Experimental DLSS 5 Neural Rendering

Bedray's initial Windows x64 path uses the direct Vulkan NGX session for
DLSS Super Resolution. With `-Dbedray.dlss5=true`, it can run Neural Rendering
on the raster world image after DLSS Super Resolution or DLAA and before the
HUD. This path requires frame generation and Ray Reconstruction to be off.
DLAA keeps the input at native resolution. Neural Rendering adds GPU work.

NVIDIA's general DLSS SDK channel currently distributes DLSS 4.5. NVIDIA
describes general DLSS 5 SDK access as partner-only. The official RTX Remix
build publishes the Vulkan Neural Rendering headers and runtime as a separate
Packman dependency under the NVIDIA RTX SDK license. Device capability,
driver support and application eligibility are determined by NGX at runtime.
[NVIDIA SDK access](https://forums.developer.nvidia.com/t/official-dlss-5-neural-rendering-plugin-access-for-unreal-engine-5-8-2/382731)
· [RTX Remix build dependency](https://github.com/NVIDIAGameWorks/dxvk-remix/blob/remix-1.6.1/packman-external.xml)

## Build a local wrapper

Install the tools from [Building Bedray](../BUILDING.md#6-native-wrappers-optional).
Use the [public DLSS SDK v310.9.16](https://github.com/NVIDIA/DLSS/tree/9981c6350dc58c0ce21f024fd65e31e851c31689)
for the base headers and NGX library. Get the RTX Remix Packman overlay from
NVIDIA's [configured download host](https://github.com/NVIDIAGameWorks/dxvk-remix/blob/remix-1.6.1/scripts-common/packman/external_config.packman.xml)
and extract it outside the Bedray checkout. Review the archive's `LICENSE.txt`.

```powershell
$nrArchive = 'C:\sdk\rtx-remix-ngx_sdk_dlnr@2.7z'
$nrSdk = 'C:\sdk\rtx-remix-ngx_sdk_dlnr-v2'
curl.exe --fail --location --output $nrArchive 'https://d1oqksizpb0e3d.cloudfront.net/rtx-remix-ngx_sdk_dlnr@2.7z'
if ($LASTEXITCODE -ne 0) { throw 'Neural Rendering SDK download failed' }
if ((Get-FileHash -LiteralPath $nrArchive -Algorithm SHA256).Hash -ne '4BB8651B3AC2180F23DF1F39422C489810A13D445A94DC836C876C18C93ECEFE') {
    throw 'Neural Rendering SDK archive checksum mismatch'
}
New-Item -ItemType Directory -Path $nrSdk | Out-Null
tar.exe -xf $nrArchive -C $nrSdk
if ($LASTEXITCODE -ne 0) { throw 'Neural Rendering SDK extraction failed' }

$env:VKB_NGX_SDK = 'C:\sdk\DLSS-310.9.16'
$env:VKB_NGX_NR_HEADERS = Join-Path $nrSdk 'include'
$env:VKB_VK_HEADERS = 'C:\sdk\Vulkan-Headers\include'
.\native\dlss_bridge\build.bat
if ($LASTEXITCODE -ne 0) { throw 'Bedray DLSS wrapper build failed' }
```

The wrapper uses the vendor's typed Vulkan API from the overlay. The NR
extension exports resource contract version 1. A build without
`VKB_NGX_NR_HEADERS` provides the standard DLSS wrapper. Keep SDK headers,
libraries and local wrapper builds outside version control.

## Install and verify in Minecraft

1. Close the game. Install `native/dlss_bridge/build/vkb_dlss.dll`, the public
   SDK's `nvngx_dlss.dll`, and the overlay's
   `lib/Windows_x86_64/rel/nvngx_dlssnr.dll` in `bedray/natives/` in the game
   folder. Use the x64 Neural Rendering DLL from the signed NVIDIA release.
   The RTX Remix 1.6.1 x64 manifest lists its SHA-256 as
   `2BC19BC09A7B89DD44F73E2AC0108FCC586E2154BDD0830F242F4F040E28789F`.
   [NVIDIA release checksum manifest](https://github.com/NVIDIAGameWorks/rtx-remix/releases/download/remix-1.6.1/rtx-remix-for-x64-games-release-remix-1.6.1-crc.txt)
2. Add `-Dbedray.dlss5=true` to the instance's JVM arguments. Select the raster
   renderer and DLSS Super Resolution or DLAA. Disable frame generation and
   Ray Reconstruction, then restart the game.
3. In `logs/latest.log`, check the Neural Rendering stages for wrapper ABI,
   NGX capability, feature creation and evaluation. Confirm successful
   evaluation in a loaded world. A file match or a compiled wrapper establishes
   only that the dependency is present. NGX can still report an unsupported
   device, driver, or application. Keep the NGX result and failure stage when
   reporting an issue.
4. Compare a fixed scene with the option off and on. Check motion, a world
   reload, a resize and the HUD, and record GPU timing and VRAM use. A build or
   CPU test does not validate output on RTX hardware.

NVIDIA's Vulkan reference feeds display-sized SDR color to Neural Rendering
and uses scene motion and depth from the active rendering path. Remix restores
HDR afterward for its downstream effects. The wrapper uses the official NGX
capability and evaluation calls. [RTX Remix render pass](https://github.com/NVIDIAGameWorks/dxvk-remix/blob/remix-1.6.1/src/dxvk/rtx_render/rtx_dlss_neural_rendering.cpp)
· [RTX Remix NGX wrapper](https://github.com/NVIDIAGameWorks/dxvk-remix/blob/remix-1.6.1/src/dxvk/rtx_render/rtx_ngx_wrapper.cpp)
