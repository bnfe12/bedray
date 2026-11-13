# Runtime library downloads

[Installation](INSTALL.md) · [Every DLL](DLL_REFERENCE.md) · [GPU recommendations](GPU_GUIDE.md) · [Library licences](NATIVES_LICENSES.md)

Use the instructions for your **exact Bedray build**. The published `0.1.0-alpha.1` and development `0.2.0-alpha`
use different renderers. Development builds need a matching Bedray JAR and `bedray_core.dll`;
there is no published `0.2.0-alpha` package yet.

## Start with what you need

The renderer core supplies path tracing with accumulation. Extra vendor libraries enable optional features.
The Windows `-all-natives.jar` extracts the runtime from the JAR on launch. Regular release builds can include the reviewed
AMD FSR and Intel XeSS runtimes. Ordinary source builds omit them.
Check the release contents before downloading another copy.

| Feature | Official download | What Bedray needs |
| :--- | :--- | :--- |
| **FSR 3.1 / 2.x** | [FidelityFX SDK 1.1.4](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases/tag/v1.1.4) · [Windows archive](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases/download/v1.1.4/FidelityFX-SDK-v1.1.4.zip) | The unmodified `PrebuiltSignedDLL/amd_fidelityfx_vk.dll`; reviewed release builds can include it. |
| **XeSS** | [XeSS SDK 3.0.2](https://github.com/intel/xess/releases/tag/v3.0.2) · [Windows archive](https://github.com/intel/xess/releases/download/v3.0.2/XeSS_SDK_3.0.2.zip) | `bin/libxess.dll`; reviewed release builds can include it. |
| **DLSS Super Resolution / Ray Reconstruction** | [DLSS SDK 310.9.16](https://github.com/NVIDIA/DLSS/tree/9981c6350dc58c0ce21f024fd65e31e851c31689) · [Release DLL folder](https://github.com/NVIDIA/DLSS/tree/9981c6350dc58c0ce21f024fd65e31e851c31689/lib/Windows_x86_64/rel) | `nvngx_dlss.dll`, optionally `nvngx_dlssd.dll`, and a matching Bedray `vkb_dlss.dll` build. |
| **NVIDIA frame generation / Reflex** | [Streamline releases](https://github.com/NVIDIA-RTX/Streamline/releases) | Use the version selected by your Bedray build and matching `vkb_sl.dll`. Manual setup documents 2.14.1; the newer single-JAR packager pins 2.14.16. Keep a complete x64 set together; see [each file](DLL_REFERENCE.md). |
| **NRD denoising** | [NRD 4.18.0 sources](https://github.com/NVIDIA-RTX/NRD/tree/8f12a22fc90e6a3c7f5da4c9c3103633c842963a) | Build `NRD.dll` with SPIR-V support and the matching `vkb_nrd.dll`; see [building](BUILDING.md). This link is source code, not a ready DLL download. |
| **Experimental DLSS 5** | [Experimental setup](experiments/dlss5.md) | Separate SDK and wrapper requirements. Public DLSS files are not a substitute for any restricted SDK access required by your build. |

For **FSR 4, XeSS Frame Generation / XeLL, AMD Ray Regeneration and NVIDIA NRC**, check
[technology support](TECHNOLOGIES.md) before downloading. Their SDK files are listed individually in the
[DLL reference](DLL_REFERENCE.md); most of these paths still need Bedray integration, and some vendor APIs are DirectX 12 only.

The links point to vendor-controlled sources. Read the included licence before using a vendor package.
Use production/release binaries; a development or debug DLL with the same name may behave differently.

## Import into your instance

Development source includes a local ZIP/DLL importer in **Bedray Settings → Runtime libraries**.
If that screen is available in your build:

1. Download the official archive for the feature you want.
2. Open **Runtime libraries** and drop the `.zip` or recognised `.dll` files onto the screen.
3. Read the result, then restart Minecraft. **Open Folder** shows the destination if you prefer a manual installation.

The importer checks recognised names, file size and x64 DLL structure and rejects unsafe archive paths.
It does not establish the origin of an arbitrary downloaded file. Keep the original vendor licence and notices
with the package; import only files obtained from the official sources above.

Files normally go in `bedray/natives/`; Streamline files go in `bedray/natives/streamline/`.
When frame generation is enabled, the DLSS and Ray Reconstruction files are also needed next to the Streamline plugins.
The [installation guide](INSTALL.md) lists all required files and their destinations.

**A vendor DLL alone may be insufficient.** An ordinary source-build JAR needs matching project wrappers built with its SDKs;
the opt-in single-JAR packager can embed a matching set. Check the actual package contents and public-release policy.
A file being present does not mean the feature
has initialised successfully. Check the runtime status and [troubleshooting guide](TROUBLESHOOTING.md).

## How distribution will become simpler

The intended experience is to choose a feature, see what is included or missing, open its official download,
and import one archive. Reviewed redistributable files should arrive with Bedray whenever the applicable terms permit it.
The [delivery plan and licence review](NATIVES_LICENSES.md) records what can ship now and what still needs review.
An automatic downloader is not part of this documentation update.
