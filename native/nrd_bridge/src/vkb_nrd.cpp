/*
 * vkb_nrd.cpp - Vulkan driver for NVIDIA Real-Time Denoisers (NRD), flat C API in include/vkb_nrd.h.
 *
 * Derived from nvpro-samples/vk_denoise_nrd, RealtimeDenoiser/src/nrd_wrapper.{cpp,hpp}
 * (commit 67f28c7c6a7bd2495bd6382dac8cffbf85673858):
 *
 * Copyright (c) 2024-2025, NVIDIA CORPORATION.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: Copyright (c) 2024-2025 NVIDIA CORPORATION
 * SPDX-License-Identifier: Apache-2.0
 *
 * MODIFIED by the Bedray project (Apache-2.0 section 4(b)); the list of changes is in
 * third_party/vk_denoise_nrd/NOTICE: no nvvk/nvpro_core2, descriptor-set pools instead of push descriptors,
 * real inter-dispatch memory barriers, explicit error returns instead of assert(), flat C API,
 * NRD.dll is loaded at run time (nothing of NRD is linked or embedded), SIGMA_SHADOW support,
 * dynamic-offset constant buffer, A2B10G10R10_UINT_PACK32 for NRD R10_G10_B10_A2_UINT.
 *
 * This software contains source code provided by NVIDIA Corporation.
 */
#define VKB_NRD_BUILD
#define VK_NO_PROTOTYPES
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vulkan/vulkan.h>

#include "NRD.h"   // headers only: struct layouts. No NRD symbol is referenced, NRD.dll is loaded dynamically.
#include "vkb_nrd.h"

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <exception>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

// ---- ABI guards --------------------------------------------------------------------------------
static_assert(sizeof(VkbNrdLibraryInfo) == 44, "VkbNrdLibraryInfo layout");
static_assert(sizeof(VkbNrdResource) == 24, "VkbNrdResource layout");
static_assert(sizeof(VkbNrdCreateInfo) == 24, "VkbNrdCreateInfo layout");
static_assert(sizeof(VkbNrdFrameSettings) == 344, "VkbNrdFrameSettings layout");
static_assert(sizeof(VkbNrdReblurSettings) == 52, "VkbNrdReblurSettings layout");
static_assert(sizeof(VkbNrdSigmaSettings) == 24, "VkbNrdSigmaSettings layout");
static_assert(sizeof(VkbNrdDispatchStats) == 40, "VkbNrdDispatchStats layout");

#define CHECK_RES(name) static_assert(uint32_t(nrd::ResourceType::name) == VKB_NRD_RES_##name, "ResourceType " #name)
CHECK_RES(IN_MV); CHECK_RES(IN_NORMAL_ROUGHNESS); CHECK_RES(IN_VIEWZ); CHECK_RES(IN_DIFF_CONFIDENCE);
CHECK_RES(IN_SPEC_CONFIDENCE); CHECK_RES(IN_DISOCCLUSION_THRESHOLD_MIX); CHECK_RES(IN_DIFF_RADIANCE_HITDIST);
CHECK_RES(IN_SPEC_RADIANCE_HITDIST); CHECK_RES(IN_DIFF_HITDIST); CHECK_RES(IN_SPEC_HITDIST);
CHECK_RES(IN_DIFF_DIRECTION_HITDIST); CHECK_RES(IN_DIFF_SH0); CHECK_RES(IN_DIFF_SH1); CHECK_RES(IN_SPEC_SH0);
CHECK_RES(IN_SPEC_SH1); CHECK_RES(IN_PENUMBRA); CHECK_RES(IN_TRANSLUCENCY); CHECK_RES(IN_SIGNAL);
CHECK_RES(OUT_DIFF_RADIANCE_HITDIST); CHECK_RES(OUT_SPEC_RADIANCE_HITDIST); CHECK_RES(OUT_DIFF_SH0);
CHECK_RES(OUT_DIFF_SH1); CHECK_RES(OUT_SPEC_SH0); CHECK_RES(OUT_SPEC_SH1); CHECK_RES(OUT_DIFF_HITDIST);
CHECK_RES(OUT_SPEC_HITDIST); CHECK_RES(OUT_DIFF_DIRECTION_HITDIST); CHECK_RES(OUT_SHADOW_TRANSLUCENCY);
CHECK_RES(OUT_SIGNAL); CHECK_RES(OUT_VALIDATION);
static_assert(uint32_t(nrd::ResourceType::TRANSIENT_POOL) == 30 && uint32_t(nrd::ResourceType::PERMANENT_POOL) == 31,
              "pool slots");
static_assert(uint32_t(nrd::ResourceType::MAX_NUM) <= VKB_NRD_RES_COUNT, "VKB_NRD_RES_COUNT too small");

// ---- error reporting -----------------------------------------------------------------------------
static thread_local char g_lastError[768] = "";

static int fail(int code, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(g_lastError, sizeof(g_lastError), fmt, ap);
    va_end(ap);
    return code;
}

static int failVk(const char* what, VkResult r) {
    return fail(VKB_NRD_ERR_VULKAN, "%s failed: VkResult=%d", what, static_cast<int>(r));
}

#define VKC(call)                                  \
    do {                                           \
        VkResult vkc_r_ = (call);                  \
        if (vkc_r_ != VK_SUCCESS) return failVk(#call, vkc_r_); \
    } while (0)

#define API_TRY try {
#define API_CATCH(retval)                                                                     \
    }                                                                                         \
    catch (const std::exception& e_) {                                                        \
        return fail(VKB_NRD_ERR_UNSUPPORTED, "unexpected C++ exception: %s", e_.what());     \
    }                                                                                         \
    catch (...) {                                                                             \
        /* NRD and the vendor headers can throw outside std::exception; anything escaping an   \
           FFM downcall reaches Java as an unhandled native exception and kills the process. */\
        return fail(VKB_NRD_ERR_UNSUPPORTED, "unexpected non-std C++ exception");            \
    }

#define API_CATCH_VOID()                                                                      \
    }                                                                                         \
    catch (const std::exception& e_) {                                                        \
        fail(VKB_NRD_ERR_UNSUPPORTED, "unexpected C++ exception: %s", e_.what());            \
    }                                                                                         \
    catch (...) {                                                                             \
        fail(VKB_NRD_ERR_UNSUPPORTED, "unexpected non-std C++ exception");                   \
    }

// ---- tables (from nrd_wrapper.cpp, with the R10_G10_B10_A2_UINT / RGBA8_UINT fixes) ---------------
static const VkFormat kNrdFormatToVk[] = {
    VK_FORMAT_R8_UNORM, VK_FORMAT_R8_SNORM, VK_FORMAT_R8_UINT, VK_FORMAT_R8_SINT,
    VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8_SNORM, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8_SINT,
    VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SNORM, VK_FORMAT_R8G8B8A8_UINT, VK_FORMAT_R8G8B8A8_SINT,
    VK_FORMAT_R8G8B8A8_SRGB,
    VK_FORMAT_R16_UNORM, VK_FORMAT_R16_SNORM, VK_FORMAT_R16_UINT, VK_FORMAT_R16_SINT, VK_FORMAT_R16_SFLOAT,
    VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16_SNORM, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16_SINT,
    VK_FORMAT_R16G16_SFLOAT,
    VK_FORMAT_R16G16B16A16_UNORM, VK_FORMAT_R16G16B16A16_SNORM, VK_FORMAT_R16G16B16A16_UINT,
    VK_FORMAT_R16G16B16A16_SINT, VK_FORMAT_R16G16B16A16_SFLOAT,
    VK_FORMAT_R32_UINT, VK_FORMAT_R32_SINT, VK_FORMAT_R32_SFLOAT,
    VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32_SFLOAT,
    VK_FORMAT_R32G32B32_UINT, VK_FORMAT_R32G32B32_SINT, VK_FORMAT_R32G32B32_SFLOAT,
    VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SINT, VK_FORMAT_R32G32B32A32_SFLOAT,
    VK_FORMAT_A2B10G10R10_UNORM_PACK32, VK_FORMAT_A2B10G10R10_UINT_PACK32, VK_FORMAT_B10G11R11_UFLOAT_PACK32,
    VK_FORMAT_E5B9G9R9_UFLOAT_PACK32,
};
static_assert(sizeof(kNrdFormatToVk) / sizeof(kNrdFormatToVk[0]) == size_t(nrd::Format::MAX_NUM), "format table");

static const VkFilter kSamplerFilter[] = {VK_FILTER_NEAREST, VK_FILTER_LINEAR};
static_assert(sizeof(kSamplerFilter) / sizeof(kSamplerFilter[0]) == size_t(nrd::Sampler::MAX_NUM), "sampler table");

// ---- NRD.dll entry points (C ABI, loaded at run time) --------------------------------------------
struct NrdApi {
    HMODULE module = nullptr;
    nrd::Result (NRD_CALL* CreateInstance)(const nrd::InstanceCreationDesc&, nrd::Instance*&) = nullptr;
    void (NRD_CALL* DestroyInstance)(nrd::Instance&) = nullptr;
    const nrd::LibraryDesc* (NRD_CALL* GetLibraryDesc)() = nullptr;
    const nrd::InstanceDesc* (NRD_CALL* GetInstanceDesc)(const nrd::Instance&) = nullptr;
    nrd::Result (NRD_CALL* SetCommonSettings)(nrd::Instance&, const nrd::CommonSettings&) = nullptr;
    nrd::Result (NRD_CALL* SetDenoiserSettings)(nrd::Instance&, nrd::Identifier, const void*) = nullptr;
    nrd::Result (NRD_CALL* GetComputeDispatches)(nrd::Instance&, const nrd::Identifier*, uint32_t,
                                                 const nrd::DispatchDesc*&, uint32_t&) = nullptr;
    const char* (NRD_CALL* GetResourceTypeString)(nrd::ResourceType) = nullptr;
    const char* (NRD_CALL* GetDenoiserString)(nrd::Denoiser) = nullptr;
};

// ---- Vulkan entry points -------------------------------------------------------------------------
struct VkFns {
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateComputePipelines CreateComputePipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkResetDescriptorPool ResetDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDispatch CmdDispatch;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdClearColorImage CmdClearColorImage;
    PFN_vkCmdUpdateBuffer CmdUpdateBuffer;
};

struct PoolImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkDeviceSize bytes = 0;
};

struct PipelineObj {
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkDescriptorSetLayout resourceLayout = VK_NULL_HANDLE;
};

struct Denoiser {
    nrd::Instance* instance = nullptr;
    const nrd::InstanceDesc* idesc = nullptr;
    uint32_t width = 0, height = 0, modeMask = 0, frames = 1;

    std::vector<PoolImage> permanent, transient;
    VkSampler samplers[2] = {};
    uint32_t samplerCount = 0;
    VkDescriptorSetLayout cbSamplerLayout = VK_NULL_HANDLE;
    VkDescriptorPool cbSamplerPool = VK_NULL_HANDLE;
    VkDescriptorSet cbSamplerSet = VK_NULL_HANDLE;
    VkBuffer cbBuffer = VK_NULL_HANDLE;
    VkDeviceMemory cbMemory = VK_NULL_HANDLE;
    VkDeviceSize cbStride = 0;
    uint32_t cbSlots = 0;
    std::vector<PipelineObj> pipelines;
    std::vector<VkDescriptorPool> framePools;
    uint32_t ring = 0;

    bool needPoolInit = true;   // transition+clear pool images inside the next dispatch command buffer
    bool forceRestart = true;   // RESTART on the next set_frame_settings
    uint32_t prevWidth = 0, prevHeight = 0;
    nrd::CommonSettings common;
    bool commonSet = false;
    uint64_t poolBytes = 0;
};

struct Global {
    std::mutex mutex;
    bool inited = false;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkFns vk{};
    NrdApi nrd{};
    VkPhysicalDeviceMemoryProperties memProps{};
    VkDeviceSize uboAlign = 256;
    std::unordered_map<int, VkFormatFeatureFlags> formatFeatures;
    std::unordered_set<Denoiser*> live;
};

static Global g;

static nrd::Denoiser modeToDenoiser(uint32_t bit) {
    switch (bit) {
        case VKB_NRD_MODE_REBLUR_DIFFUSE_SPECULAR: return nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR;
        case VKB_NRD_MODE_RELAX_DIFFUSE_SPECULAR: return nrd::Denoiser::RELAX_DIFFUSE_SPECULAR;
        default: return nrd::Denoiser::SIGMA_SHADOW;
    }
}

static const uint32_t kModeBits[3] = {VKB_NRD_MODE_REBLUR_DIFFUSE_SPECULAR, VKB_NRD_MODE_RELAX_DIFFUSE_SPECULAR,
                                      VKB_NRD_MODE_SIGMA_SHADOW};

static VkFormatFeatureFlags formatFeatures(VkFormat f) {
    auto it = g.formatFeatures.find(static_cast<int>(f));
    if (it != g.formatFeatures.end()) return it->second;
    VkFormatProperties p{};
    g.vk.GetPhysicalDeviceFormatProperties(g.physical, f, &p);
    g.formatFeatures[static_cast<int>(f)] = p.optimalTilingFeatures;
    return p.optimalTilingFeatures;
}

static VkFormat expectedNormalRoughnessFormat(nrd::NormalEncoding e) {
    switch (e) {
        case nrd::NormalEncoding::RGBA8_UNORM: return VK_FORMAT_R8G8B8A8_UNORM;
        case nrd::NormalEncoding::RGBA8_SNORM: return VK_FORMAT_R8G8B8A8_SNORM;
        case nrd::NormalEncoding::R10_G10_B10_A2_UNORM: return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        case nrd::NormalEncoding::RGBA16_UNORM: return VK_FORMAT_R16G16B16A16_UNORM;
        case nrd::NormalEncoding::RGBA16_SNORM: return VK_FORMAT_R16G16B16A16_SNORM;
        default: return VK_FORMAT_UNDEFINED;
    }
}

static int findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g.memProps.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) && (g.memProps.memoryTypes[i].propertyFlags & want) == want) return static_cast<int>(i);
    }
    return -1;
}

static void globalBarrier(VkCommandBuffer cmd, VkPipelineStageFlags srcStage, VkAccessFlags srcAccess,
                          VkPipelineStageFlags dstStage, VkAccessFlags dstAccess) {
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = srcAccess;
    mb.dstAccessMask = dstAccess;
    g.vk.CmdPipelineBarrier(cmd, srcStage, dstStage, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

// ---- Denoiser lifetime ---------------------------------------------------------------------------
static void destroyPoolImages(std::vector<PoolImage>& v) {
    for (auto& p : v) {
        if (p.view) g.vk.DestroyImageView(g.device, p.view, nullptr);
        if (p.image) g.vk.DestroyImage(g.device, p.image, nullptr);
        if (p.memory) g.vk.FreeMemory(g.device, p.memory, nullptr);
    }
    v.clear();
}

static void destroyDenoiser(Denoiser* d) {
    if (!d) return;
    destroyPoolImages(d->permanent);
    destroyPoolImages(d->transient);
    for (auto p : d->framePools) g.vk.DestroyDescriptorPool(g.device, p, nullptr);
    for (auto& p : d->pipelines) {
        if (p.pipeline) g.vk.DestroyPipeline(g.device, p.pipeline, nullptr);
        if (p.layout) g.vk.DestroyPipelineLayout(g.device, p.layout, nullptr);
        if (p.resourceLayout) g.vk.DestroyDescriptorSetLayout(g.device, p.resourceLayout, nullptr);
    }
    if (d->cbSamplerPool) g.vk.DestroyDescriptorPool(g.device, d->cbSamplerPool, nullptr);
    if (d->cbSamplerLayout) g.vk.DestroyDescriptorSetLayout(g.device, d->cbSamplerLayout, nullptr);
    for (uint32_t i = 0; i < d->samplerCount; ++i) g.vk.DestroySampler(g.device, d->samplers[i], nullptr);
    if (d->cbBuffer) g.vk.DestroyBuffer(g.device, d->cbBuffer, nullptr);
    if (d->cbMemory) g.vk.FreeMemory(g.device, d->cbMemory, nullptr);
    if (d->instance) g.nrd.DestroyInstance(*d->instance);
    g.live.erase(d);
    delete d;
}

static int createPoolImage(Denoiser* d, const nrd::TextureDesc& td, std::vector<PoolImage>& out) {
    if (size_t(td.format) >= size_t(nrd::Format::MAX_NUM)) return fail(VKB_NRD_ERR_NRD, "NRD pool texture has invalid format %u", unsigned(td.format));
    PoolImage pi;
    pi.format = kNrdFormatToVk[size_t(td.format)];
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                      VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    if ((formatFeatures(pi.format) & need) != need)
        return fail(VKB_NRD_ERR_VULKAN, "device does not support sampled+storage+transfer-dst for VkFormat %d needed by NRD pools "
                    "(missing shaderStorageImageExtendedFormats?)", static_cast<int>(pi.format));
    const uint32_t div = std::max<uint32_t>(td.downsampleFactor, 1);
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = pi.format;
    ici.extent = {(d->width + div - 1) / div, (d->height + div - 1) / div, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    out.push_back(pi);  // push first: destroyDenoiser() frees whatever was created before a failure
    PoolImage& ref = out.back();
    VKC(g.vk.CreateImage(g.device, &ici, nullptr, &ref.image));
    VkMemoryRequirements mr{};
    g.vk.GetImageMemoryRequirements(g.device, ref.image, &mr);
    const int mt = findMemoryType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mt < 0) return fail(VKB_NRD_ERR_VULKAN, "no DEVICE_LOCAL memory type for NRD pool image");
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = mr.size;
    mai.memoryTypeIndex = static_cast<uint32_t>(mt);
    VKC(g.vk.AllocateMemory(g.device, &mai, nullptr, &ref.memory));
    VKC(g.vk.BindImageMemory(g.device, ref.image, ref.memory, 0));
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = ref.image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = ref.format;
    vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VKC(g.vk.CreateImageView(g.device, &vci, nullptr, &ref.view));
    ref.bytes = mr.size;
    d->poolBytes += mr.size;
    return VKB_NRD_OK;
}

static int createPools(Denoiser* d) {
    d->poolBytes = 0;
    for (uint32_t i = 0; i < d->idesc->permanentPoolSize; ++i) {
        int rc = createPoolImage(d, d->idesc->permanentPool[i], d->permanent);
        if (rc) return rc;
    }
    for (uint32_t i = 0; i < d->idesc->transientPoolSize; ++i) {
        int rc = createPoolImage(d, d->idesc->transientPool[i], d->transient);
        if (rc) return rc;
    }
    d->needPoolInit = true;
    d->forceRestart = true;
    d->prevWidth = d->width;
    d->prevHeight = d->height;
    return VKB_NRD_OK;
}

static int createPipelinesAndSets(Denoiser* d) {
    const nrd::InstanceDesc* id = d->idesc;
    const nrd::LibraryDesc* ld = g.nrd.GetLibraryDesc();
    if (id->constantBufferAndSamplersSpaceIndex != 1 || id->resourcesSpaceIndex != 0)
        return fail(VKB_NRD_ERR_UNSUPPORTED, "NRD register spaces changed (cb/samplers=%u resources=%u), expected 1/0",
                    id->constantBufferAndSamplersSpaceIndex, id->resourcesSpaceIndex);
    if (id->samplersNum != uint32_t(nrd::Sampler::MAX_NUM) || id->samplersNum > 2)
        return fail(VKB_NRD_ERR_UNSUPPORTED, "unexpected NRD sampler count %u", id->samplersNum);
    if (!id->shaderEntryPoint) return fail(VKB_NRD_ERR_NRD, "NRD gave no shader entry point");

    // samplers (immutable, shared)
    for (uint32_t s = 0; s < id->samplersNum; ++s) {
        VkSamplerCreateInfo sci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        sci.magFilter = sci.minFilter = kSamplerFilter[size_t(id->samplers[s])];
        sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        sci.maxLod = 0.0f;
        VKC(g.vk.CreateSampler(g.device, &sci, nullptr, &d->samplers[s]));
        d->samplerCount = s + 1;
    }

    // constant buffer: dynamic uniform buffer with one slot per possible dispatch
    d->cbSlots = std::max<uint32_t>(id->descriptorPoolDesc.setsMaxNum, 1);
    d->cbStride = (VkDeviceSize(id->constantBufferMaxDataSize) + g.uboAlign - 1) / g.uboAlign * g.uboAlign;
    {
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = d->cbStride * d->cbSlots;
        bci.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VKC(g.vk.CreateBuffer(g.device, &bci, nullptr, &d->cbBuffer));
        VkMemoryRequirements mr{};
        g.vk.GetBufferMemoryRequirements(g.device, d->cbBuffer, &mr);
        const int mt = findMemoryType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (mt < 0) return fail(VKB_NRD_ERR_VULKAN, "no DEVICE_LOCAL memory type for the NRD constant buffer");
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = mr.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(mt);
        VKC(g.vk.AllocateMemory(g.device, &mai, nullptr, &d->cbMemory));
        VKC(g.vk.BindBufferMemory(g.device, d->cbBuffer, d->cbMemory, 0));
    }

    const uint32_t cbBinding = ld->spirvBindingOffsets.constantBufferOffset + id->constantBufferRegisterIndex;
    {
        VkDescriptorSetLayoutBinding b[3]{};
        for (uint32_t s = 0; s < id->samplersNum; ++s) {
            b[s].binding = ld->spirvBindingOffsets.samplerOffset + id->samplersBaseRegisterIndex + s;
            b[s].descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER;
            b[s].descriptorCount = 1;
            b[s].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            b[s].pImmutableSamplers = &d->samplers[s];
        }
        b[id->samplersNum].binding = cbBinding;
        b[id->samplersNum].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
        b[id->samplersNum].descriptorCount = 1;
        b[id->samplersNum].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutCreateInfo lci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        lci.bindingCount = id->samplersNum + 1;
        lci.pBindings = b;
        VKC(g.vk.CreateDescriptorSetLayout(g.device, &lci, nullptr, &d->cbSamplerLayout));

        VkDescriptorPoolSize ps[2] = {{VK_DESCRIPTOR_TYPE_SAMPLER, id->samplersNum},
                                      {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1}};
        VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pci.maxSets = 1;
        pci.poolSizeCount = 2;
        pci.pPoolSizes = ps;
        VKC(g.vk.CreateDescriptorPool(g.device, &pci, nullptr, &d->cbSamplerPool));
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        ai.descriptorPool = d->cbSamplerPool;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &d->cbSamplerLayout;
        VKC(g.vk.AllocateDescriptorSets(g.device, &ai, &d->cbSamplerSet));

        VkDescriptorBufferInfo bi{d->cbBuffer, 0, id->constantBufferMaxDataSize};
        VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w.dstSet = d->cbSamplerSet;
        w.dstBinding = cbBinding;
        w.descriptorCount = 1;
        w.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
        w.pBufferInfo = &bi;
        g.vk.UpdateDescriptorSets(g.device, 1, &w, 0, nullptr);
    }

    // pipelines: set 0 = this pipeline's textures/storage images, set 1 = shared samplers + constants
    d->pipelines.resize(id->pipelinesNum);
    for (uint32_t p = 0; p < id->pipelinesNum; ++p) {
        const nrd::PipelineDesc& pd = id->pipelines[p];
        PipelineObj& po = d->pipelines[p];
        std::vector<VkDescriptorSetLayoutBinding> bindings;
        for (uint32_t r = 0; r < pd.resourceRangesNum; ++r) {
            const nrd::ResourceRangeDesc& range = pd.resourceRanges[r];
            const bool storage = range.descriptorType == nrd::DescriptorType::STORAGE_TEXTURE;
            const uint32_t base = storage ? ld->spirvBindingOffsets.storageTextureAndBufferOffset : ld->spirvBindingOffsets.textureOffset;
            for (uint32_t b = 0; b < range.descriptorsNum; ++b) {
                VkDescriptorSetLayoutBinding lb{};
                lb.binding = base + id->resourcesBaseRegisterIndex + b;
                lb.descriptorType = storage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                lb.descriptorCount = 1;
                lb.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
                bindings.push_back(lb);
            }
        }
        VkDescriptorSetLayoutCreateInfo lci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        lci.bindingCount = static_cast<uint32_t>(bindings.size());
        lci.pBindings = bindings.data();
        VKC(g.vk.CreateDescriptorSetLayout(g.device, &lci, nullptr, &po.resourceLayout));
        VkDescriptorSetLayout sets[2] = {po.resourceLayout, d->cbSamplerLayout};
        VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        plci.setLayoutCount = 2;
        plci.pSetLayouts = sets;
        VKC(g.vk.CreatePipelineLayout(g.device, &plci, nullptr, &po.layout));

        if (!pd.computeShaderSPIRV.bytecode || pd.computeShaderSPIRV.size == 0 || (pd.computeShaderSPIRV.size & 3))
            return fail(VKB_NRD_ERR_NRD, "NRD pipeline %u has no valid SPIR-V (was NRD built with NRD_EMBEDS_SPIRV_SHADERS=ON?)", p);
        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = static_cast<size_t>(pd.computeShaderSPIRV.size);
        smci.pCode = static_cast<const uint32_t*>(pd.computeShaderSPIRV.bytecode);
        VkShaderModule sm = VK_NULL_HANDLE;
        VKC(g.vk.CreateShaderModule(g.device, &smci, nullptr, &sm));
        VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cpci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, sm,
                      id->shaderEntryPoint, nullptr};
        cpci.layout = po.layout;
        VkResult r = g.vk.CreateComputePipelines(g.device, VK_NULL_HANDLE, 1, &cpci, nullptr, &po.pipeline);
        g.vk.DestroyShaderModule(g.device, sm, nullptr);
        if (r != VK_SUCCESS) return failVk("vkCreateComputePipelines", r);
    }

    // per-frame descriptor pools for set 0
    const nrd::DescriptorPoolDesc& dp = id->descriptorPoolDesc;
    if (dp.setsMaxNum == 0 || (dp.perSetTexturesMaxNum == 0 && dp.perSetStorageTexturesMaxNum == 0))
        return fail(VKB_NRD_ERR_NRD, "NRD reported an empty descriptor pool description");
    for (uint32_t f = 0; f < d->frames; ++f) {
        VkDescriptorPoolSize ps[2] = {{VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, dp.setsMaxNum * std::max(dp.perSetTexturesMaxNum, 1u)},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, dp.setsMaxNum * std::max(dp.perSetStorageTexturesMaxNum, 1u)}};
        VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pci.maxSets = dp.setsMaxNum;
        pci.poolSizeCount = 2;
        pci.pPoolSizes = ps;
        VkDescriptorPool pool = VK_NULL_HANDLE;
        VKC(g.vk.CreateDescriptorPool(g.device, &pci, nullptr, &pool));
        d->framePools.push_back(pool);
    }
    return VKB_NRD_OK;
}

// ---- API: info -----------------------------------------------------------------------------------
extern "C" {

VKB_NRD_API uint32_t vkb_nrd_get_api_version(void) { return VKB_NRD_API_VERSION; }
VKB_NRD_API const char* vkb_nrd_get_last_error_string(void) { return g_lastError; }
VKB_NRD_API uint32_t vkb_nrd_get_required_features(void) {
    return VKB_NRD_FEATURE_STORAGE_IMAGE_READ_WITHOUT_FORMAT | VKB_NRD_FEATURE_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT |
           VKB_NRD_FEATURE_STORAGE_IMAGE_EXTENDED_FORMATS;
}

}  // extern "C" (C++ helpers)

static std::wstring utf8ToWide(const char* s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 0) return std::wstring();
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, &w[0], n);
    w.resize(static_cast<size_t>(n) - 1);
    return w;
}

static void unloadNrd() {
    if (g.nrd.module) FreeLibrary(g.nrd.module);
    g.nrd = NrdApi{};
}

extern "C" {

VKB_NRD_API int32_t vkb_nrd_init(uint64_t instance, uint64_t physical_device, uint64_t device, void* gipa, void* gdpa,
                                 const char* nrd_dll_path, VkbNrdLibraryInfo* out_info) {
    API_TRY
    std::lock_guard<std::mutex> lock(g.mutex);
    if (g.inited) return fail(VKB_NRD_ERR_ALREADY_INIT, "vkb_nrd_init called twice");
    if (!instance || !physical_device || !device || !gipa || !gdpa || !nrd_dll_path || !out_info || out_info->struct_size != sizeof(VkbNrdLibraryInfo))
        return fail(VKB_NRD_ERR_INVALID_ARG, "vkb_nrd_init: null argument or bad struct_size");

    // 1. NRD.dll
    const std::wstring wpath = utf8ToWide(nrd_dll_path);
    g.nrd.module = LoadLibraryExW(wpath.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!g.nrd.module) return fail(VKB_NRD_ERR_LOAD, "cannot load NRD.dll from '%s' (GetLastError=%lu)", nrd_dll_path, GetLastError());
#define LOAD_NRD(n)                                                                                 \
    g.nrd.n = reinterpret_cast<decltype(g.nrd.n)>(GetProcAddress(g.nrd.module, #n));              \
    if (!g.nrd.n) {                                                                                 \
        unloadNrd();                                                                                \
        return fail(VKB_NRD_ERR_LOAD, "NRD.dll has no export '%s' (not an NRD build?)", #n);       \
    }
    LOAD_NRD(CreateInstance) LOAD_NRD(DestroyInstance) LOAD_NRD(GetLibraryDesc) LOAD_NRD(GetInstanceDesc)
    LOAD_NRD(SetCommonSettings) LOAD_NRD(SetDenoiserSettings) LOAD_NRD(GetComputeDispatches)
    LOAD_NRD(GetResourceTypeString) LOAD_NRD(GetDenoiserString)
#undef LOAD_NRD
    const nrd::LibraryDesc* ld = g.nrd.GetLibraryDesc();
    if (!ld) { unloadNrd(); return fail(VKB_NRD_ERR_LOAD, "NRD GetLibraryDesc returned null"); }
    if (ld->versionMajor != NRD_VERSION_MAJOR || ld->versionMinor != NRD_VERSION_MINOR) {
        const int a = ld->versionMajor, b = ld->versionMinor, c = ld->versionBuild;
        unloadNrd();
        return fail(VKB_NRD_ERR_LOAD, "NRD.dll is v%d.%d.%d but vkb_nrd was built against v%d.%d.x (struct layouts differ)", a, b, c,
                    NRD_VERSION_MAJOR, NRD_VERSION_MINOR);
    }
    if (size_t(ld->normalEncoding) >= size_t(nrd::NormalEncoding::MAX_NUM)) {
        unloadNrd();
        return fail(VKB_NRD_ERR_LOAD, "NRD.dll reports an unknown normal encoding %u", unsigned(ld->normalEncoding));
    }

    // 2. Vulkan entry points
    auto getInstanceProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa);
    auto getDeviceProc = reinterpret_cast<PFN_vkGetDeviceProcAddr>(gdpa);
    g.instance = reinterpret_cast<VkInstance>(instance);
    g.physical = reinterpret_cast<VkPhysicalDevice>(physical_device);
    g.device = reinterpret_cast<VkDevice>(device);
#define LOAD_I(n)                                                                                   \
    g.vk.n = reinterpret_cast<PFN_vk##n>(getInstanceProc(g.instance, "vk" #n));                    \
    if (!g.vk.n) { unloadNrd(); return fail(VKB_NRD_ERR_VULKAN, "vkGetInstanceProcAddr(vk%s) returned null", #n); }
#define LOAD_D(n)                                                                                   \
    g.vk.n = reinterpret_cast<PFN_vk##n>(getDeviceProc(g.device, "vk" #n));                        \
    if (!g.vk.n) { unloadNrd(); return fail(VKB_NRD_ERR_VULKAN, "vkGetDeviceProcAddr(vk%s) returned null", #n); }
    LOAD_I(GetPhysicalDeviceMemoryProperties) LOAD_I(GetPhysicalDeviceFormatProperties) LOAD_I(GetPhysicalDeviceProperties)
    LOAD_D(CreateImage) LOAD_D(DestroyImage) LOAD_D(GetImageMemoryRequirements) LOAD_D(AllocateMemory) LOAD_D(FreeMemory)
    LOAD_D(BindImageMemory) LOAD_D(CreateImageView) LOAD_D(DestroyImageView) LOAD_D(CreateBuffer) LOAD_D(DestroyBuffer)
    LOAD_D(GetBufferMemoryRequirements) LOAD_D(BindBufferMemory) LOAD_D(CreateSampler) LOAD_D(DestroySampler)
    LOAD_D(CreateDescriptorSetLayout) LOAD_D(DestroyDescriptorSetLayout) LOAD_D(CreatePipelineLayout)
    LOAD_D(DestroyPipelineLayout) LOAD_D(CreateShaderModule) LOAD_D(DestroyShaderModule) LOAD_D(CreateComputePipelines)
    LOAD_D(DestroyPipeline) LOAD_D(CreateDescriptorPool) LOAD_D(DestroyDescriptorPool) LOAD_D(ResetDescriptorPool)
    LOAD_D(AllocateDescriptorSets) LOAD_D(UpdateDescriptorSets) LOAD_D(CmdBindPipeline) LOAD_D(CmdBindDescriptorSets)
    LOAD_D(CmdDispatch) LOAD_D(CmdPipelineBarrier) LOAD_D(CmdClearColorImage) LOAD_D(CmdUpdateBuffer)
#undef LOAD_I
#undef LOAD_D
    g.vk.GetPhysicalDeviceMemoryProperties(g.physical, &g.memProps);
    VkPhysicalDeviceProperties props{};
    g.vk.GetPhysicalDeviceProperties(g.physical, &props);
    g.uboAlign = std::max<VkDeviceSize>(props.limits.minUniformBufferOffsetAlignment, 4);
    g.formatFeatures.clear();

    VkFormat nrFmt = expectedNormalRoughnessFormat(ld->normalEncoding);
    std::memset(out_info, 0, sizeof(*out_info));
    out_info->struct_size = sizeof(VkbNrdLibraryInfo);
    out_info->version_major = ld->versionMajor;
    out_info->version_minor = ld->versionMinor;
    out_info->version_build = ld->versionBuild;
    out_info->normal_encoding = static_cast<uint32_t>(ld->normalEncoding);
    out_info->roughness_encoding = static_cast<uint32_t>(ld->roughnessEncoding);
    out_info->normal_roughness_vk_format = static_cast<uint32_t>(nrFmt);
    out_info->spirv_offset_sampler = ld->spirvBindingOffsets.samplerOffset;
    out_info->spirv_offset_texture = ld->spirvBindingOffsets.textureOffset;
    out_info->spirv_offset_constant_buffer = ld->spirvBindingOffsets.constantBufferOffset;
    out_info->spirv_offset_storage = ld->spirvBindingOffsets.storageTextureAndBufferOffset;
    g.inited = true;
    return VKB_NRD_OK;
    API_CATCH(0)
}

VKB_NRD_API int32_t vkb_nrd_create_denoiser(const VkbNrdCreateInfo* info, uint64_t* out_handle) {
    API_TRY
    std::lock_guard<std::mutex> lock(g.mutex);
    if (!g.inited) return fail(VKB_NRD_ERR_NOT_INITIALIZED, "vkb_nrd_init was not called");
    if (!info || !out_handle || info->struct_size != sizeof(VkbNrdCreateInfo))
        return fail(VKB_NRD_ERR_INVALID_ARG, "vkb_nrd_create_denoiser: null argument or bad struct_size");
    if (info->width == 0 || info->height == 0 || info->width > 16384 || info->height > 16384)
        return fail(VKB_NRD_ERR_INVALID_ARG, "bad size %ux%u", info->width, info->height);
    if (info->mode_mask == 0 || (info->mode_mask & ~VKB_NRD_MODE_ALL_MASK))
        return fail(VKB_NRD_ERR_INVALID_ARG, "bad mode_mask 0x%X", info->mode_mask);
    if (info->frames_in_flight < 1 || info->frames_in_flight > 8)
        return fail(VKB_NRD_ERR_INVALID_ARG, "frames_in_flight must be 1..8, got %u", info->frames_in_flight);

    auto* d = new Denoiser();
    g.live.insert(d);
    d->width = info->width;
    d->height = info->height;
    d->modeMask = info->mode_mask;
    d->frames = info->frames_in_flight;

    nrd::DenoiserDesc descs[3];
    uint32_t n = 0;
    for (uint32_t bit : kModeBits) {
        if (info->mode_mask & bit) {
            nrd::Denoiser dn = modeToDenoiser(bit);
            descs[n++] = {nrd::Identifier(dn), dn};
        }
    }
    nrd::InstanceCreationDesc icd{};
    icd.denoisers = descs;
    icd.denoisersNum = n;
    nrd::Result nr = g.nrd.CreateInstance(icd, d->instance);
    if (nr != nrd::Result::SUCCESS) {
        d->instance = nullptr;
        destroyDenoiser(d);
        return fail(VKB_NRD_ERR_NRD, "NRD CreateInstance failed: nrd::Result=%u (requested mode mask 0x%X)", unsigned(nr), info->mode_mask);
    }
    d->idesc = g.nrd.GetInstanceDesc(*d->instance);
    if (!d->idesc) { destroyDenoiser(d); return fail(VKB_NRD_ERR_NRD, "NRD GetInstanceDesc returned null"); }

    int rc = createPools(d);
    if (!rc) rc = createPipelinesAndSets(d);
    if (rc) { destroyDenoiser(d); return rc; }
    *out_handle = reinterpret_cast<uint64_t>(d);
    return VKB_NRD_OK;
    API_CATCH(0)
}

static Denoiser* lookup(uint64_t h) {
    auto* d = reinterpret_cast<Denoiser*>(h);
    return (d && g.live.count(d)) ? d : nullptr;
}

VKB_NRD_API int32_t vkb_nrd_resize(uint64_t handle, uint32_t width, uint32_t height) {
    API_TRY
    std::lock_guard<std::mutex> lock(g.mutex);
    Denoiser* d = lookup(handle);
    if (!d) return fail(VKB_NRD_ERR_INVALID_ARG, "vkb_nrd_resize: invalid handle");
    if (width == 0 || height == 0 || width > 16384 || height > 16384) return fail(VKB_NRD_ERR_INVALID_ARG, "bad size %ux%u", width, height);
    destroyPoolImages(d->permanent);
    destroyPoolImages(d->transient);
    d->width = width;
    d->height = height;
    return createPools(d);
    API_CATCH(0)
}

VKB_NRD_API int32_t vkb_nrd_set_frame_settings(uint64_t handle, const VkbNrdFrameSettings* s) {
    API_TRY
    std::lock_guard<std::mutex> lock(g.mutex);
    Denoiser* d = lookup(handle);
    if (!d || !s || s->struct_size != sizeof(VkbNrdFrameSettings)) return fail(VKB_NRD_ERR_INVALID_ARG, "vkb_nrd_set_frame_settings: bad handle/struct");
    if (s->rect_width == 0 || s->rect_height == 0 || s->rect_width > d->width || s->rect_height > d->height ||
        s->rect_width_prev > d->width || s->rect_height_prev > d->height || s->accumulation_mode > VKB_NRD_ACCUM_CLEAR_AND_RESTART)
        return fail(VKB_NRD_ERR_INVALID_ARG, "frame settings: rect %ux%u (prev %ux%u) must fit the pool %ux%u, accumulation_mode<=2",
                    s->rect_width, s->rect_height, s->rect_width_prev, s->rect_height_prev, d->width, d->height);
    nrd::CommonSettings cs;  // NRD defaults for everything not exposed
    std::memcpy(cs.viewToClipMatrix, s->view_to_clip, sizeof(cs.viewToClipMatrix));
    std::memcpy(cs.viewToClipMatrixPrev, s->view_to_clip_prev, sizeof(cs.viewToClipMatrixPrev));
    std::memcpy(cs.worldToViewMatrix, s->world_to_view, sizeof(cs.worldToViewMatrix));
    std::memcpy(cs.worldToViewMatrixPrev, s->world_to_view_prev, sizeof(cs.worldToViewMatrixPrev));
    std::memcpy(cs.motionVectorScale, s->mv_scale, sizeof(cs.motionVectorScale));
#if NRD_VERSION_MINOR >= 18
    // NRD 4.18 added motionVectorBias to CommonSettings. The renderer currently
    // supplies zero motion vectors, so 4.17's implicit zero bias is equivalent.
    std::memcpy(cs.motionVectorBias, s->mv_bias, sizeof(cs.motionVectorBias));
#endif
    std::memcpy(cs.cameraJitter, s->jitter, sizeof(cs.cameraJitter));
    std::memcpy(cs.cameraJitterPrev, s->jitter_prev, sizeof(cs.cameraJitterPrev));
    cs.resourceSize[0] = static_cast<uint16_t>(d->width);
    cs.resourceSize[1] = static_cast<uint16_t>(d->height);
    cs.resourceSizePrev[0] = static_cast<uint16_t>(d->prevWidth);
    cs.resourceSizePrev[1] = static_cast<uint16_t>(d->prevHeight);
    cs.rectSize[0] = static_cast<uint16_t>(s->rect_width);
    cs.rectSize[1] = static_cast<uint16_t>(s->rect_height);
    cs.rectSizePrev[0] = static_cast<uint16_t>(s->rect_width_prev);
    cs.rectSizePrev[1] = static_cast<uint16_t>(s->rect_height_prev);
    cs.viewZScale = s->view_z_scale;
    cs.timeDeltaBetweenFrames = s->time_delta_ms;
    cs.denoisingRange = s->denoising_range;
    cs.disocclusionThreshold = s->disocclusion_threshold;
    cs.frameIndex = s->frame_index;
    uint32_t acc = s->accumulation_mode;
    if (d->forceRestart && acc == VKB_NRD_ACCUM_CONTINUE) acc = VKB_NRD_ACCUM_RESTART;
    cs.accumulationMode = static_cast<nrd::AccumulationMode>(acc);
    cs.isMotionVectorInWorldSpace = (s->flags & VKB_NRD_FRAME_MV_WORLD_SPACE) != 0;
    cs.isHistoryConfidenceAvailable = (s->flags & VKB_NRD_FRAME_HISTORY_CONFIDENCE) != 0;
    cs.enableValidation = (s->flags & VKB_NRD_FRAME_ENABLE_VALIDATION) != 0;
    nrd::Result nr = g.nrd.SetCommonSettings(*d->instance, cs);
    if (nr != nrd::Result::SUCCESS) return fail(VKB_NRD_ERR_NRD, "NRD SetCommonSettings failed: nrd::Result=%u", unsigned(nr));
    d->common = cs;
    d->commonSet = true;
    d->forceRestart = false;
    d->prevWidth = d->width;
    d->prevHeight = d->height;
    return VKB_NRD_OK;
    API_CATCH(0)
}

VKB_NRD_API int32_t vkb_nrd_set_reblur_settings(uint64_t handle, const VkbNrdReblurSettings* s) {
    API_TRY
    std::lock_guard<std::mutex> lock(g.mutex);
    Denoiser* d = lookup(handle);
    if (!d || !s || s->struct_size != sizeof(VkbNrdReblurSettings)) return fail(VKB_NRD_ERR_INVALID_ARG, "vkb_nrd_set_reblur_settings: bad handle/struct");
    if (!(d->modeMask & VKB_NRD_MODE_REBLUR_DIFFUSE_SPECULAR)) return fail(VKB_NRD_ERR_INVALID_ARG, "denoiser was not created with REBLUR");
    nrd::ReblurSettings rs;
    rs.maxAccumulatedFrameNum = s->max_accumulated_frame_num;
    rs.maxFastAccumulatedFrameNum = s->max_fast_accumulated_frame_num;
    rs.maxStabilizedFrameNum = s->max_stabilized_frame_num;
    rs.hitDistanceParameters.A = s->hit_dist_a;
    rs.hitDistanceParameters.B = s->hit_dist_b;
    rs.hitDistanceParameters.C = s->hit_dist_c;
    rs.minBlurRadius = s->min_blur_radius;
    rs.maxBlurRadius = s->max_blur_radius;
    rs.diffusePrepassBlurRadius = s->diffuse_prepass_blur_radius;
    rs.specularPrepassBlurRadius = s->specular_prepass_blur_radius;
    rs.planeDistanceSensitivity = s->plane_distance_sensitivity;
    rs.enableAntiFirefly = s->enable_anti_firefly != 0;
    nrd::Result nr = g.nrd.SetDenoiserSettings(*d->instance, nrd::Identifier(nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR), &rs);
    if (nr != nrd::Result::SUCCESS) return fail(VKB_NRD_ERR_NRD, "NRD SetDenoiserSettings(REBLUR) failed: nrd::Result=%u", unsigned(nr));
    return VKB_NRD_OK;
    API_CATCH(0)
}

VKB_NRD_API int32_t vkb_nrd_set_sigma_settings(uint64_t handle, const VkbNrdSigmaSettings* s) {
    API_TRY
    std::lock_guard<std::mutex> lock(g.mutex);
    Denoiser* d = lookup(handle);
    if (!d || !s || s->struct_size != sizeof(VkbNrdSigmaSettings)) return fail(VKB_NRD_ERR_INVALID_ARG, "vkb_nrd_set_sigma_settings: bad handle/struct");
    if (!(d->modeMask & VKB_NRD_MODE_SIGMA_SHADOW)) return fail(VKB_NRD_ERR_INVALID_ARG, "denoiser was not created with SIGMA_SHADOW");
    nrd::SigmaSettings ss;
    std::memcpy(ss.lightDirection, s->light_direction, sizeof(ss.lightDirection));
    ss.planeDistanceSensitivity = s->plane_distance_sensitivity;
    ss.maxStabilizedFrameNum = s->max_stabilized_frame_num;
    nrd::Result nr = g.nrd.SetDenoiserSettings(*d->instance, nrd::Identifier(nrd::Denoiser::SIGMA_SHADOW), &ss);
    if (nr != nrd::Result::SUCCESS) return fail(VKB_NRD_ERR_NRD, "NRD SetDenoiserSettings(SIGMA) failed: nrd::Result=%u", unsigned(nr));
    return VKB_NRD_OK;
    API_CATCH(0)
}

// checks one user resource slot against what the dispatch binds
static int checkUserResource(const nrd::ResourceDesc& rd, const VkbNrdResource* resources, const nrd::LibraryDesc* ld) {
    const uint32_t t = static_cast<uint32_t>(rd.type);
    if (t >= VKB_NRD_RES_COUNT || rd.type == nrd::ResourceType::TRANSIENT_POOL || rd.type == nrd::ResourceType::PERMANENT_POOL)
        return fail(VKB_NRD_ERR_NRD, "dispatch references invalid resource type %u", t);
    const VkbNrdResource& r = resources[t];
    const char* name = g.nrd.GetResourceTypeString(rd.type);
    if (!r.view || r.vk_format == 0) return fail(VKB_NRD_ERR_INVALID_ARG, "required NRD resource %s (slot %u) is missing (view/format empty)", name, t);
    const bool storage = rd.descriptorType == nrd::DescriptorType::STORAGE_TEXTURE;
    const VkFormatFeatureFlags need = storage ? VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT : VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    if ((formatFeatures(static_cast<VkFormat>(r.vk_format)) & need) != need)
        return fail(VKB_NRD_ERR_INVALID_ARG, "resource %s: VkFormat %u does not support %s on this device", name, r.vk_format,
                    storage ? "storage image" : "sampled image");
    if (rd.type == nrd::ResourceType::IN_NORMAL_ROUGHNESS) {
        const VkFormat exp = expectedNormalRoughnessFormat(ld->normalEncoding);
        const bool floatOk = ld->normalEncoding == nrd::NormalEncoding::RGBA16_SNORM && r.vk_format == VK_FORMAT_R16G16B16A16_SFLOAT;
        if (r.vk_format != static_cast<uint32_t>(exp) && !floatOk)
            return fail(VKB_NRD_ERR_INVALID_ARG, "IN_NORMAL_ROUGHNESS must be VkFormat %d for NRD normal encoding %u, got %u",
                        static_cast<int>(exp), unsigned(ld->normalEncoding), r.vk_format);
    }
    return VKB_NRD_OK;
}

VKB_NRD_API int32_t vkb_nrd_dispatch(uint64_t handle, uint64_t command_buffer, uint32_t mode_mask,
                                     const VkbNrdResource* resources, VkbNrdDispatchStats* out_stats) {
    API_TRY
    LARGE_INTEGER t0, t1, freq;
    QueryPerformanceCounter(&t0);
    std::lock_guard<std::mutex> lock(g.mutex);
    Denoiser* d = lookup(handle);
    if (!d || !command_buffer || !resources) return fail(VKB_NRD_ERR_INVALID_ARG, "vkb_nrd_dispatch: bad handle/command buffer/resources");
    if (out_stats && out_stats->struct_size != sizeof(VkbNrdDispatchStats)) return fail(VKB_NRD_ERR_INVALID_ARG, "bad VkbNrdDispatchStats.struct_size");
    if (mode_mask == 0 || (mode_mask & ~d->modeMask)) return fail(VKB_NRD_ERR_INVALID_ARG, "mode_mask 0x%X not a subset of the created modes 0x%X", mode_mask, d->modeMask);
    if (!d->commonSet) return fail(VKB_NRD_ERR_INVALID_ARG, "vkb_nrd_set_frame_settings must be called before the first dispatch");
    if (resources[30].view || resources[31].view) return fail(VKB_NRD_ERR_INVALID_ARG, "resource slots 30/31 (internal pools) must be empty");
    const nrd::LibraryDesc* ld = g.nrd.GetLibraryDesc();
    VkCommandBuffer cmd = reinterpret_cast<VkCommandBuffer>(command_buffer);

    nrd::Identifier ids[3];
    uint32_t idN = 0;
    for (uint32_t bit : kModeBits)
        if (mode_mask & bit) ids[idN++] = nrd::Identifier(modeToDenoiser(bit));
    const nrd::DispatchDesc* dispatches = nullptr;
    uint32_t dispatchN = 0;
    nrd::Result nr = g.nrd.GetComputeDispatches(*d->instance, ids, idN, dispatches, dispatchN);
    if (nr != nrd::Result::SUCCESS) return fail(VKB_NRD_ERR_NRD, "NRD GetComputeDispatches failed: nrd::Result=%u", unsigned(nr));
    if (dispatchN > d->cbSlots) return fail(VKB_NRD_ERR_UNSUPPORTED, "NRD returned %u dispatches, constant/descriptor capacity is %u", dispatchN, d->cbSlots);

    // validate everything before recording the first command
    for (uint32_t i = 0; i < dispatchN; ++i) {
        const nrd::DispatchDesc& dd = dispatches[i];
        if (dd.pipelineIndex >= d->pipelines.size()) return fail(VKB_NRD_ERR_NRD, "dispatch %u: pipeline index %u out of range", i, dd.pipelineIndex);
        for (uint32_t r = 0; r < dd.resourcesNum; ++r) {
            const nrd::ResourceDesc& rd = dd.resources[r];
            if (rd.type == nrd::ResourceType::TRANSIENT_POOL) {
                if (rd.indexInPool >= d->transient.size()) return fail(VKB_NRD_ERR_NRD, "dispatch %u: transient index %u out of range", i, rd.indexInPool);
            } else if (rd.type == nrd::ResourceType::PERMANENT_POOL) {
                if (rd.indexInPool >= d->permanent.size()) return fail(VKB_NRD_ERR_NRD, "dispatch %u: permanent index %u out of range", i, rd.indexInPool);
            } else {
                int rc = checkUserResource(rd, resources, ld);
                if (rc) return rc;
            }
        }
    }

    // lazily initialise NRD's pool images inside this command buffer: UNDEFINED -> GENERAL + clear
    if (d->needPoolInit) {
        std::vector<VkImageMemoryBarrier> toTransfer;
        auto addAll = [&](std::vector<PoolImage>& v) {
            for (auto& p : v) {
                VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
                b.srcAccessMask = 0;
                b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
                b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                b.image = p.image;
                b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                toTransfer.push_back(b);
            }
        };
        addAll(d->permanent);
        addAll(d->transient);
        g.vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr,
                                static_cast<uint32_t>(toTransfer.size()), toTransfer.data());
        VkClearColorValue zero{};
        for (auto& b : toTransfer) {
            VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            g.vk.CmdClearColorImage(cmd, b.image, VK_IMAGE_LAYOUT_GENERAL, &zero, 1, &range);
        }
        globalBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                      VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        d->needPoolInit = false;
    }

    // constants: all uploads first (previous frame's reads -> transfer write -> compute read)
    uint32_t constantBytes = 0;
    std::vector<uint32_t> cbOffset(dispatchN, 0);
    {
        bool any = false;
        for (uint32_t i = 0; i < dispatchN; ++i) any = any || (d->idesc->pipelines[dispatches[i].pipelineIndex].hasConstantData);
        if (any) {
            globalBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          VK_ACCESS_TRANSFER_WRITE_BIT);
            uint32_t lastOffset = 0, nextSlot = 0;
            std::vector<uint8_t> padded(d->idesc->constantBufferMaxDataSize + 4);
            for (uint32_t i = 0; i < dispatchN; ++i) {
                const nrd::DispatchDesc& dd = dispatches[i];
                if (!d->idesc->pipelines[dd.pipelineIndex].hasConstantData) continue;
                if (dd.constantBufferDataMatchesPreviousDispatch && i > 0) {
                    cbOffset[i] = lastOffset;
                    continue;
                }
                if (dd.constantBufferDataSize > d->idesc->constantBufferMaxDataSize || !dd.constantBufferData)
                    return fail(VKB_NRD_ERR_NRD, "dispatch %u: constant data size %u exceeds max %u", i, dd.constantBufferDataSize, d->idesc->constantBufferMaxDataSize);
                const uint32_t size4 = (dd.constantBufferDataSize + 3u) & ~3u;
                const void* src = dd.constantBufferData;
                if (size4 != dd.constantBufferDataSize) {
                    std::memset(padded.data(), 0, padded.size());
                    std::memcpy(padded.data(), dd.constantBufferData, dd.constantBufferDataSize);
                    src = padded.data();
                }
                lastOffset = static_cast<uint32_t>(d->cbStride * nextSlot++);
                cbOffset[i] = lastOffset;
                g.vk.CmdUpdateBuffer(cmd, d->cbBuffer, lastOffset, size4, src);
                constantBytes += size4;
            }
            globalBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                          VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
        }
    }

    // descriptor pool of this ring slot
    VkDescriptorPool pool = d->framePools[d->ring];
    d->ring = (d->ring + 1) % d->frames;
    VKC(g.vk.ResetDescriptorPool(g.device, pool, 0));

    uint32_t clearCount = 0, sets = 0;
    for (uint32_t i = 0; i < dispatchN; ++i) {
        const nrd::DispatchDesc& dd = dispatches[i];
        const nrd::PipelineDesc& pd = d->idesc->pipelines[dd.pipelineIndex];
        const PipelineObj& po = d->pipelines[dd.pipelineIndex];

        VkDescriptorSet set = VK_NULL_HANDLE;
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        ai.descriptorPool = pool;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &po.resourceLayout;
        VKC(g.vk.AllocateDescriptorSets(g.device, &ai, &set));
        ++sets;

        std::vector<VkWriteDescriptorSet> writes;
        std::vector<VkDescriptorImageInfo> infos(dd.resourcesNum);
        writes.reserve(dd.resourcesNum);
        uint32_t k = 0;
        for (uint32_t r = 0; r < pd.resourceRangesNum; ++r) {
            const nrd::ResourceRangeDesc& range = pd.resourceRanges[r];
            const bool storage = range.descriptorType == nrd::DescriptorType::STORAGE_TEXTURE;
            const uint32_t base = storage ? ld->spirvBindingOffsets.storageTextureAndBufferOffset : ld->spirvBindingOffsets.textureOffset;
            for (uint32_t b = 0; b < range.descriptorsNum; ++b, ++k) {
                if (k >= dd.resourcesNum) return fail(VKB_NRD_ERR_NRD, "dispatch %u: fewer resources (%u) than pipeline descriptors", i, dd.resourcesNum);
                const nrd::ResourceDesc& rd = dd.resources[k];
                if (rd.descriptorType != range.descriptorType)
                    return fail(VKB_NRD_ERR_NRD, "dispatch %u resource %u: descriptor type differs from the pipeline range", i, k);
                VkImageView view = VK_NULL_HANDLE;
                if (rd.type == nrd::ResourceType::TRANSIENT_POOL) view = d->transient[rd.indexInPool].view;
                else if (rd.type == nrd::ResourceType::PERMANENT_POOL) view = d->permanent[rd.indexInPool].view;
                else view = reinterpret_cast<VkImageView>(resources[static_cast<uint32_t>(rd.type)].view);
                infos[k] = {VK_NULL_HANDLE, view, VK_IMAGE_LAYOUT_GENERAL};
                VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                w.dstSet = set;
                w.dstBinding = base + d->idesc->resourcesBaseRegisterIndex + b;
                w.descriptorCount = 1;
                w.descriptorType = storage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                w.pImageInfo = &infos[k];
                writes.push_back(w);
            }
        }
        if (k != dd.resourcesNum) return fail(VKB_NRD_ERR_NRD, "dispatch %u: %u resources but pipeline expects %u", i, dd.resourcesNum, k);
        g.vk.UpdateDescriptorSets(g.device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

        g.vk.CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, po.pipeline);
        g.vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, po.layout, d->idesc->resourcesSpaceIndex, 1, &set, 0, nullptr);
        g.vk.CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, po.layout, d->idesc->constantBufferAndSamplersSpaceIndex, 1,
                                   &d->cbSamplerSet, 1, &cbOffset[i]);
        g.vk.CmdDispatch(cmd, dd.gridWidth, dd.gridHeight, 1);
        if (dd.name && std::strncmp(dd.name, "Clear", 5) == 0) ++clearCount;
        // make this dispatch's storage writes visible to every following dispatch (and WAR ordering)
        if (i + 1 < dispatchN)
            globalBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    if (out_stats) {
        QueryPerformanceCounter(&t1);
        QueryPerformanceFrequency(&freq);
        std::memset(out_stats, 0, sizeof(*out_stats));
        out_stats->struct_size = sizeof(VkbNrdDispatchStats);
        out_stats->dispatch_count = dispatchN;
        out_stats->clear_dispatch_count = clearCount;
        out_stats->descriptor_sets = sets;
        out_stats->constant_bytes = constantBytes;
        out_stats->pipeline_count = static_cast<uint32_t>(d->pipelines.size());
        out_stats->pool_memory_bytes = d->poolBytes;
        out_stats->cpu_ms = static_cast<float>(1000.0 * double(t1.QuadPart - t0.QuadPart) / double(freq.QuadPart));
    }
    return VKB_NRD_OK;
    API_CATCH(0)
}

VKB_NRD_API void vkb_nrd_destroy(uint64_t handle) {
    API_TRY
    std::lock_guard<std::mutex> lock(g.mutex);
    Denoiser* d = lookup(handle);
    if (d) destroyDenoiser(d);
    API_CATCH_VOID()
}

VKB_NRD_API void vkb_nrd_shutdown(void) {
    API_TRY
    std::lock_guard<std::mutex> lock(g.mutex);
    if (!g.inited) return;
    while (!g.live.empty()) destroyDenoiser(*g.live.begin());
    unloadNrd();
    g.inited = false;
    API_CATCH_VOID()
}

}  // extern "C"
