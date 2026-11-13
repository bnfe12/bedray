"use strict";

// Static, official-source links. This page does not fetch or rehost DLLs.
const dlssRoot = "https://raw.githubusercontent.com/NVIDIA/DLSS/9981c6350dc58c0ce21f024fd65e31e851c31689/lib/Windows_x86_64/rel/";
const dlssLicense = "https://github.com/NVIDIA/DLSS/blob/9981c6350dc58c0ce21f024fd65e31e851c31689/LICENSE.txt";
const xessZip = "https://github.com/intel/xess/releases/download/v3.0.2/XeSS_SDK_3.0.2.zip";
const amdSdk = "https://gpuopen.com/amd-fsr-sdk/";

const features = {
  dlss: {
    title: "DLSS Super Resolution / DLAA", status: "NVIDIA RTX · alpha integration",
    description: "Download NVIDIA's release DLL directly. You do not need to download the whole DLSS SDK just to obtain this file.",
    actions: [["Download nvngx_dlss.dll", dlssRoot + "nvngx_dlss.dll"]],
    files: ["nvngx_dlss.dll → bedray/natives/", "vkb_dlss.dll → matching Bedray package or wrapper build"],
    note: "Needs a matching vkb_dlss.dll. When frame generation uses Streamline, its complete runtime set is needed instead.",
    licenses: [["NVIDIA licence", dlssLicense]],
  },
  rr: {
    title: "DLSS Ray Reconstruction", status: "NVIDIA RTX · experimental",
    description: "This is NVIDIA's denoising runtime for traced lighting. Download the Ray Reconstruction DLL and keep the Super Resolution runtime alongside it.",
    actions: [["Download nvngx_dlssd.dll", dlssRoot + "nvngx_dlssd.dll"], ["Also get nvngx_dlss.dll", dlssRoot + "nvngx_dlss.dll"]],
    files: ["nvngx_dlssd.dll → bedray/natives/", "nvngx_dlss.dll → bedray/natives/", "vkb_dlss.dll → matching Bedray package or wrapper build"],
    note: "If you use frame generation, select its Streamline download below and keep that package's SR/RR files together.",
    licenses: [["NVIDIA licence", dlssLicense]],
  },
  framegen: {
    title: "DLSS Frame Generation + Reflex", status: "Supported RTX GPU required · experimental",
    description: "Download the official Windows x64 Streamline ZIP once. The local importer can place its recognised DLLs in the Streamline folder.",
    actions: [["Download Streamline x64 ZIP", "https://github.com/NVIDIA-RTX/Streamline/releases/download/v2.14.16/streamline-sdk-v2.14.16.zip"]],
    files: ["sl.interposer.dll", "sl.common.dll", "sl.dlss.dll + nvngx_dlss.dll", "sl.dlss_d.dll + nvngx_dlssd.dll", "sl.dlss_g.dll + nvngx_dlssg.dll", "sl.reflex.dll + sl.pcl.dll", "NvLowLatencyVk.dll when required by the package", "vkb_sl.dll → matching Bedray wrapper; all files go in bedray/natives/streamline/"],
    note: "This link matches the newer single-JAR packager's 2.14.16 input. Older manual builds document 2.14.1: use the release selected by your build, and restart after installation.",
    licenses: [["Streamline releases / other versions", "https://github.com/NVIDIA-RTX/Streamline/releases"], ["Component licence policy", "NATIVES_LICENSES.html"]],
  },
  fsr: {
    title: "FSR 3.1 / 2.x upscaling", status: "Vulkan · reviewed runtime",
    description: "Get the FidelityFX 1.1.4 Vulkan DLL for FSR 2/3.1 and the existing AMD frame generation path.",
    actions: [["Download amd_fidelityfx_vk.dll", "https://raw.githubusercontent.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/v1.1.4/PrebuiltSignedDLL/amd_fidelityfx_vk.dll"], ["SDK ZIP with notices", "https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases/download/v1.1.4/FidelityFX-SDK-v1.1.4.zip"]],
    files: ["amd_fidelityfx_vk.dll → bedray/natives/"],
    note: "This runtime also serves as an upscaling fallback for GPUs that cannot use AMD FSR SDK 2.4.",
    licenses: [["AMD MIT licence", "https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v1.1.4/LICENSE.txt"]],
  },
  xess: {
    title: "XeSS Super Resolution", status: "Vulkan · reviewed runtime",
    description: "Download Intel's exact XeSS SDK 3.0.2 runtime directly. The full archive is available if you also want its documentation and notice files.",
    actions: [["Download libxess.dll", "https://raw.githubusercontent.com/intel/xess/v3.0.2/bin/libxess.dll"], ["SDK ZIP with notices", xessZip]],
    files: ["libxess.dll → bedray/natives/"],
    note: "Often already included in a matching package. XeSS Super Resolution is separate from Intel's frame generation and XeLL.",
    licenses: [["Intel licence", "https://github.com/intel/xess/blob/v3.0.2/LICENSE.txt"], ["Intel third-party notices", "https://github.com/intel/xess/blob/v3.0.2/third-party-programs.txt"]],
  },
  nrd: {
    title: "NRD denoising", status: "Matching runtime build required", pending: true,
    description: "The reviewed official NRD 4.18 release supplies source, without a prebuilt DLL asset. Use the runtime in a matching Bedray package or build it with the Bedray wrapper.",
    actions: [["NRD build instructions", "BUILDING.html"], ["Official NRD 4.18 sources", "https://github.com/NVIDIA-RTX/NRD/tree/8f12a22fc90e6a3c7f5da4c9c3103633c842963a"]],
    files: ["NRD.dll → bedray/natives/", "vkb_nrd.dll → bedray/natives/"],
    note: "There is no direct player-ready NRD DLL download on this page. A similarly named file from an unrelated build may be incompatible.",
    licenses: [["NVIDIA NRD licence", "https://github.com/NVIDIA-RTX/NRD/blob/8f12a22fc90e6a3c7f5da4c9c3103633c842963a/LICENSE.txt"]],
  },
  dlss5: {
    title: "DLSS 5 Neural Rendering", status: "Experimental SDK/model setup", pending: true,
    description: "Use the exact signed model and NR-capable wrapper described by the experimental guide. Ordinary Super Resolution files do not replace this pair.",
    actions: [["Open DLSS 5 setup", "experiments/dlss5.html"]],
    files: ["nvngx_dlssnr.dll", "An NR-capable vkb_dlss.dll from the matching SDK build"],
    note: "This is separate from DLSS Ray Reconstruction. Check the guide's hardware, runtime and public-release restrictions.",
    licenses: [["Licence and delivery policy", "NATIVES_LICENSES.html"]],
  },
  builtin: {
    title: "No extra DLL needed", status: "Included with Bedray",
    description: "Bilinear, FSR 1 and the built-in NIS shader implementation arrive with Bedray. There is no separate DLL to download for these scalers.",
    actions: [["Open upscaler settings guide", "UPSCALERS.html"]], files: ["No additional vendor DLL"],
    note: "An upscaler does not add missing hardware ray-tracing capabilities.", licenses: [],
  },
  fsr4: {
    title: "FSR 4 / 3.1 / 2.x upscaling", status: "Vulkan В· experimental in Bedray 0.2.0-alpha",
    description: "Get AMD FSR SDK 2.4 and import both signed Vulkan DLLs from Kits/FidelityFX/signedbin/. The SDK supplies FSR 4.1.2, 3.1.6 and 2.3.4.",
    actions: [["Download AMD FSR SDK 2.4", "https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases/tag/v2.4.0"], ["View AMD GPU and driver requirements", amdSdk]],
    files: ["amd_fidelityfx_loader_vk.dll в†’ bedray/natives/", "amd_fidelityfx_upscaler_vk.dll в†’ bedray/natives/"],
    note: "Restart after importing. Select FSR Auto to let Bedray choose a compatible provider, or select a version listed for your GPU in the Upscaler menu.",
    licenses: [["New AMD runtime licence", "https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/v2.4.0/Kits/FidelityFX/docs/license.md"]],
  },
  xessfg: {
    title: "XeSS Frame Generation + XeLL", status: "Not integrated · public DirectX 12 API", pending: true,
    description: "Intel supplies these two files, but the reviewed public API requires DirectX 12. Bedray's Vulkan renderer does not implement that path.",
    actions: [["View Intel FG requirements", "https://github.com/intel/xess/blob/v3.0.2/doc/xess_fg_developer_guide_english.md"]],
    files: ["libxess_fg.dll", "libxell.dll"], note: "Their presence in the library catalogue does not make frame generation available.",
    licenses: [["Intel licence", "https://github.com/intel/xess/blob/v3.0.2/LICENSE.txt"]],
  },
  amdrr: {
    title: "AMD Ray Regeneration", status: "Not integrated · DirectX 12 only in reviewed SDK", pending: true,
    description: "AMD's reconstruction technology is separate from FSR upscaling. The current public SDK has no Vulkan Ray Regeneration effect.",
    actions: [["View AMD requirements", amdSdk]], files: ["amd_fidelityfx_denoiser_dx12.dll → DirectX 12 SDK, not a Bedray Vulkan dependency"],
    note: "There is no DLL that can activate this feature in the current Bedray build.", licenses: [["AMD runtime terms", "NATIVES_LICENSES.html"]],
  },
  nrc: {
    title: "NVIDIA Neural Radiance Cache", status: "Not integrated in Bedray", pending: true,
    description: "NVIDIA has a Vulkan SDK, but NRC needs a path-tracer integration and matching CUDA dependencies. It is not the driver's shader cache.",
    actions: [["View official NRC SDK", "https://github.com/NVIDIA-RTX/NRC"]],
    files: ["NRC_Vulkan.dll", "cudart64_13.dll", "nvrtc64_130_0.dll", "nvrtc-builtins64_134.dll"],
    note: "Developer reference only. The catalogue's nv_nrc.dll name is a placeholder, not the reviewed SDK runtime filename.",
    licenses: [["NRC and CUDA licence review", "NATIVES_LICENSES.html"]],
  },
  amdcache: {
    title: "AMD Radiance Caching", status: "Not integrated · vendor technical preview", pending: true,
    description: "The reviewed AMD preview exposes DirectX 12 radiance caching. A Vulkan Bedray integration is not available.",
    actions: [["View AMD SDK status", amdSdk]], files: ["amd_fidelityfx_radiancecache_dx12.dll"],
    note: "Download is not required for current Bedray.", licenses: [["AMD runtime terms", "NATIVES_LICENSES.html"]],
  },
};

function renderFeature() {
  const selected = features[document.getElementById("feature").value];
  const status = document.getElementById("feature-status");
  status.textContent = selected.status;
  status.classList.toggle("pending", Boolean(selected.pending));
  document.getElementById("feature-title").textContent = selected.title;
  document.getElementById("feature-description").textContent = selected.description;
  document.getElementById("feature-note").textContent = selected.note;
  const actions = document.getElementById("download-actions");
  actions.replaceChildren();
  selected.actions.forEach(([label, url], index) => {
    const link = document.createElement("a");
    link.className = "button " + (index === 0 && !selected.pending ? "primary" : "secondary");
    link.href = url;
    link.textContent = label;
    actions.append(link);
  });
  const files = document.getElementById("required-files");
  files.replaceChildren();
  selected.files.forEach(name => { const item = document.createElement("li"); item.textContent = name; files.append(item); });
  const licenses = document.getElementById("license-links");
  licenses.replaceChildren();
  selected.licenses.forEach(([label, url]) => { const link = document.createElement("a"); link.href = url; link.textContent = label; licenses.append(link); });
}

document.getElementById("feature").addEventListener("change", renderFeature);
renderFeature();
