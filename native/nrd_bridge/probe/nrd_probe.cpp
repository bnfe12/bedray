/*
 * nrd_probe.cpp - console probe for vkb_nrd.dll. No game involved.
 *   nrd_probe.exe [--nrd <path\NRD.dll>] [--frames N]
 * Creates its own Vulkan instance/device on the NVIDIA GPU (storage-image features enabled), loads NRD.dll through
 * vkb_nrd_init, creates a REBLUR_DIFFUSE_SPECULAR + SIGMA_SHADOW denoiser at 1280x720 and runs N frames of
 * vkb_nrd_dispatch on a synthetic scene: flat plane facing the camera (viewZ = 10, normal toward the camera,
 * roughness 1), zero motion vectors, static camera, white-noise diffuse/specular radiance (new noise every frame).
 * Metric N1: std of the denoised luminance (Y) vs the noisy input in the central region; criterion
 * std_out < std_in / 4 and |mean_out / mean_in - 1| <= 5%.
 * Output lines start with "NRD_PROBE". Exit code 0 only after "NRD_PROBE OK".
 */
#define VK_NO_PROTOTYPES
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vulkan/vulkan.h>

#include "vkb_nrd.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <random>
#include <string>
#include <vector>

static int failStage(const char* stage, long long result) {
    std::printf("NRD_PROBE FAILED stage=%s result=%lld\n", stage, result);
    const char* err = vkb_nrd_get_last_error_string();
    if (err && err[0]) std::printf("NRD_PROBE detail=%s\n", err);
    std::fflush(stdout);
    return 1;
}

// ---- float <-> half ---------------------------------------------------------------------------
static uint16_t f2h(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = int32_t((x >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = x & 0x7FFFFFu;
    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        mant |= 0x800000u;
        uint32_t shift = static_cast<uint32_t>(14 - exp);
        uint32_t h = mant >> shift;
        if ((mant >> (shift - 1)) & 1u) ++h;
        return static_cast<uint16_t>(sign | h);
    }
    if (exp >= 31) return static_cast<uint16_t>(sign | 0x7C00u);  // overflow -> inf (inputs stay far below)
    uint32_t h = sign | (uint32_t(exp) << 10) | (mant >> 13);
    if (mant & 0x1000u) ++h;  // round to nearest
    return static_cast<uint16_t>(h);
}

static float h2f(uint16_t h) {
    uint32_t sign = (h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x3FFu;
    uint32_t x;
    if (exp == 0) {
        if (mant == 0) x = sign;
        else {
            exp = 1;
            while (!(mant & 0x400u)) { mant <<= 1; --exp; }
            mant &= 0x3FFu;
            x = sign | ((exp + 127 - 15) << 23) | (mant << 13);
        }
    } else if (exp == 31) x = sign | 0x7F800000u | (mant << 13);
    else x = sign | ((exp + 127 - 15) << 23) | (mant << 13);
    float f;
    std::memcpy(&f, &x, 4);
    return f;
}

// ---- Vulkan function table -------------------------------------------------------------------
#define VKFN(n) PFN_vk##n n = nullptr
struct Vk {
    VKFN(GetInstanceProcAddr);
    VKFN(CreateInstance); VKFN(DestroyInstance); VKFN(EnumeratePhysicalDevices); VKFN(GetPhysicalDeviceProperties);
    VKFN(GetPhysicalDeviceFeatures); VKFN(GetPhysicalDeviceQueueFamilyProperties); VKFN(GetPhysicalDeviceMemoryProperties);
    VKFN(CreateDevice); VKFN(GetDeviceProcAddr);
    VKFN(DestroyDevice); VKFN(GetDeviceQueue); VKFN(CreateImage); VKFN(DestroyImage); VKFN(GetImageMemoryRequirements);
    VKFN(AllocateMemory); VKFN(FreeMemory); VKFN(BindImageMemory); VKFN(CreateImageView); VKFN(DestroyImageView);
    VKFN(CreateBuffer); VKFN(DestroyBuffer); VKFN(GetBufferMemoryRequirements); VKFN(BindBufferMemory); VKFN(MapMemory);
    VKFN(CreateCommandPool); VKFN(DestroyCommandPool); VKFN(AllocateCommandBuffers); VKFN(BeginCommandBuffer);
    VKFN(EndCommandBuffer); VKFN(QueueSubmit); VKFN(QueueWaitIdle); VKFN(DeviceWaitIdle); VKFN(CmdPipelineBarrier);
    VKFN(CmdCopyBufferToImage); VKFN(CmdCopyImageToBuffer); VKFN(CmdClearColorImage); VKFN(ResetCommandBuffer);
};
static Vk vk;

struct Img {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t w = 0, h = 0, bpp = 0;
};
struct Buf {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    uint8_t* map = nullptr;
    VkDeviceSize size = 0;
};

static VkDevice g_dev;
static VkPhysicalDeviceMemoryProperties g_mem;

static int memType(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < g_mem.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (g_mem.memoryTypes[i].propertyFlags & want) == want) return int(i);
    return -1;
}

static bool makeImage(Img& o, uint32_t w, uint32_t h, VkFormat fmt, uint32_t bpp) {
    o.w = w; o.h = h; o.format = fmt; o.bpp = bpp;
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D; ici.format = fmt; ici.extent = {w, h, 1}; ici.mipLevels = 1; ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT; ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE; ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk.CreateImage(g_dev, &ici, nullptr, &o.image) != VK_SUCCESS) return false;
    VkMemoryRequirements mr; vk.GetImageMemoryRequirements(g_dev, o.image, &mr);
    int mt = memType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mai.allocationSize = mr.size; mai.memoryTypeIndex = uint32_t(mt);
    if (vk.AllocateMemory(g_dev, &mai, nullptr, &o.mem) != VK_SUCCESS) return false;
    if (vk.BindImageMemory(g_dev, o.image, o.mem, 0) != VK_SUCCESS) return false;
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = o.image; vci.viewType = VK_IMAGE_VIEW_TYPE_2D; vci.format = fmt; vci.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return vk.CreateImageView(g_dev, &vci, nullptr, &o.view) == VK_SUCCESS;
}

static bool makeHostBuffer(Buf& b, VkDeviceSize size) {
    b.size = size;
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = size; bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT; bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vk.CreateBuffer(g_dev, &bci, nullptr, &b.buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements mr; vk.GetBufferMemoryRequirements(g_dev, b.buffer, &mr);
    int mt = memType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mai.allocationSize = mr.size; mai.memoryTypeIndex = uint32_t(mt);
    if (vk.AllocateMemory(g_dev, &mai, nullptr, &b.mem) != VK_SUCCESS) return false;
    if (vk.BindBufferMemory(g_dev, b.buffer, b.mem, 0) != VK_SUCCESS) return false;
    void* p = nullptr;
    if (vk.MapMemory(g_dev, b.mem, 0, VK_WHOLE_SIZE, 0, &p) != VK_SUCCESS) return false;
    b.map = static_cast<uint8_t*>(p);
    return true;
}

static void memBarrier(VkCommandBuffer cmd, VkPipelineStageFlags s, VkAccessFlags sa, VkPipelineStageFlags d, VkAccessFlags da) {
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; mb.srcAccessMask = sa; mb.dstAccessMask = da;
    vk.CmdPipelineBarrier(cmd, s, d, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

static void copyToImage(VkCommandBuffer cmd, const Buf& staging, VkDeviceSize offset, const Img& img) {
    VkBufferImageCopy c{}; c.bufferOffset = offset; c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; c.imageExtent = {img.w, img.h, 1};
    vk.CmdCopyBufferToImage(cmd, staging.buffer, img.image, VK_IMAGE_LAYOUT_GENERAL, 1, &c);
}

static void copyFromImage(VkCommandBuffer cmd, const Img& img, const Buf& dst, VkDeviceSize offset) {
    VkBufferImageCopy c{}; c.bufferOffset = offset; c.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; c.imageExtent = {img.w, img.h, 1};
    vk.CmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_GENERAL, dst.buffer, 1, &c);
}

static void fillMatrixLH(float* m, float fovY, float aspect, float n, float f) {
    std::memset(m, 0, 16 * sizeof(float));
    const float t = std::tan(fovY * 0.5f);
    m[0] = 1.0f / (aspect * t);          // column-major: element (row r, col c) at [c*4 + r]
    m[5] = 1.0f / t;
    m[10] = f / (f - n);
    m[11] = 1.0f;                        // clip.w = view.z (left-handed)
    m[14] = -n * f / (f - n);
}

static void identity(float* m) {
    std::memset(m, 0, 16 * sizeof(float));
    m[0] = m[5] = m[10] = m[15] = 1.0f;
}

int main(int argc, char** argv) {
    std::string nrdPath;
    int frames = 8;
    for (int i = 1; i < argc; ++i) {
        if (!std::strcmp(argv[i], "--nrd") && i + 1 < argc) nrdPath = argv[++i];
        else if (!std::strcmp(argv[i], "--frames") && i + 1 < argc) frames = std::atoi(argv[++i]);
    }
    if (nrdPath.empty()) {
        char exe[MAX_PATH];
        GetModuleFileNameA(nullptr, exe, MAX_PATH);
        std::string dir = exe;
        dir = dir.substr(0, dir.find_last_of('\\'));
        nrdPath = dir + "\\NRD.dll";
    }
    std::printf("NRD_PROBE api_version=0x%06X nrd_dll=%s frames=%d\n", vkb_nrd_get_api_version(), nrdPath.c_str(), frames);

    HMODULE lib = LoadLibraryW(L"vulkan-1.dll");
    if (!lib) return failStage("load_vulkan-1.dll", GetLastError());
    vk.GetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(lib, "vkGetInstanceProcAddr"));
    if (!vk.GetInstanceProcAddr) return failStage("vkGetInstanceProcAddr", 0);
    vk.CreateInstance = reinterpret_cast<PFN_vkCreateInstance>(vk.GetInstanceProcAddr(nullptr, "vkCreateInstance"));

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "nrd_probe"; app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult vr = vk.CreateInstance(&ici, nullptr, &instance);
    if (vr != VK_SUCCESS) return failStage("vkCreateInstance", vr);
#define LI(n) vk.n = reinterpret_cast<PFN_vk##n>(vk.GetInstanceProcAddr(instance, "vk" #n))
    LI(DestroyInstance); LI(EnumeratePhysicalDevices); LI(GetPhysicalDeviceProperties); LI(GetPhysicalDeviceFeatures);
    LI(GetPhysicalDeviceQueueFamilyProperties); LI(GetPhysicalDeviceMemoryProperties); LI(CreateDevice); LI(GetDeviceProcAddr);

    uint32_t pdCount = 0;
    vk.EnumeratePhysicalDevices(instance, &pdCount, nullptr);
    std::vector<VkPhysicalDevice> pds(pdCount);
    vk.EnumeratePhysicalDevices(instance, &pdCount, pds.data());
    VkPhysicalDevice pd = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties props{};
    for (auto cand : pds) {
        VkPhysicalDeviceProperties p{};
        vk.GetPhysicalDeviceProperties(cand, &p);
        std::printf("NRD_PROBE gpu=\"%s\" vendor=0x%04X\n", p.deviceName, p.vendorID);
        if (p.vendorID == 0x10DE && !pd) { pd = cand; props = p; }
    }
    if (!pd) return failStage("no_nvidia_physical_device", pdCount);
    uint32_t dv = props.driverVersion;
    std::printf("NRD_PROBE selected=\"%s\" driver=%u.%u.%u api=%u.%u.%u\n", props.deviceName, (dv >> 22) & 0x3FF, (dv >> 14) & 0xFF,
                (dv >> 6) & 0xFF, VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion), VK_API_VERSION_PATCH(props.apiVersion));
    vk.GetPhysicalDeviceMemoryProperties(pd, &g_mem);

    // device with exactly the features vkb_nrd_get_required_features() asks for
    uint32_t need = vkb_nrd_get_required_features();
    VkPhysicalDeviceFeatures sup{};
    vk.GetPhysicalDeviceFeatures(pd, &sup);
    VkPhysicalDeviceFeatures en{};
    if (need & VKB_NRD_FEATURE_STORAGE_IMAGE_READ_WITHOUT_FORMAT) { if (!sup.shaderStorageImageReadWithoutFormat) return failStage("feature_shaderStorageImageReadWithoutFormat_unsupported", 0); en.shaderStorageImageReadWithoutFormat = VK_TRUE; }
    if (need & VKB_NRD_FEATURE_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT) { if (!sup.shaderStorageImageWriteWithoutFormat) return failStage("feature_shaderStorageImageWriteWithoutFormat_unsupported", 0); en.shaderStorageImageWriteWithoutFormat = VK_TRUE; }
    if (need & VKB_NRD_FEATURE_STORAGE_IMAGE_EXTENDED_FORMATS) { if (!sup.shaderStorageImageExtendedFormats) return failStage("feature_shaderStorageImageExtendedFormats_unsupported", 0); en.shaderStorageImageExtendedFormats = VK_TRUE; }

    uint32_t qfCount = 0;
    vk.GetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfs(qfCount);
    vk.GetPhysicalDeviceQueueFamilyProperties(pd, &qfCount, qfs.data());
    uint32_t qf = UINT32_MAX;
    for (uint32_t i = 0; i < qfCount; ++i)
        if ((qfs[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { qf = i; break; }
    if (qf == UINT32_MAX) return failStage("no_graphics_compute_queue", 0);
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueFamilyIndex = qf; qci.queueCount = 1; qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci; dci.pEnabledFeatures = &en;
    vr = vk.CreateDevice(pd, &dci, nullptr, &g_dev);
    if (vr != VK_SUCCESS) return failStage("vkCreateDevice", vr);
#define LD(n) vk.n = reinterpret_cast<PFN_vk##n>(vk.GetDeviceProcAddr(g_dev, "vk" #n))
    LD(DestroyDevice); LD(GetDeviceQueue); LD(CreateImage); LD(DestroyImage); LD(GetImageMemoryRequirements); LD(AllocateMemory);
    LD(FreeMemory); LD(BindImageMemory); LD(CreateImageView); LD(DestroyImageView); LD(CreateBuffer); LD(DestroyBuffer);
    LD(GetBufferMemoryRequirements); LD(BindBufferMemory); LD(MapMemory); LD(CreateCommandPool); LD(DestroyCommandPool);
    LD(AllocateCommandBuffers); LD(BeginCommandBuffer); LD(EndCommandBuffer); LD(QueueSubmit); LD(QueueWaitIdle); LD(DeviceWaitIdle);
    LD(CmdPipelineBarrier); LD(CmdCopyBufferToImage); LD(CmdCopyImageToBuffer); LD(CmdClearColorImage); LD(ResetCommandBuffer);
    VkQueue queue = VK_NULL_HANDLE;
    vk.GetDeviceQueue(g_dev, qf, 0, &queue);
    VkCommandPoolCreateInfo cpci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; cpci.queueFamilyIndex = qf;
    VkCommandPool cpool = VK_NULL_HANDLE;
    vk.CreateCommandPool(g_dev, &cpci, nullptr, &cpool);
    VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = cpool; cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cbai.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    vk.AllocateCommandBuffers(g_dev, &cbai, &cmd);
    std::printf("NRD_PROBE device_features read_without_format=%u write_without_format=%u extended_formats=%u\n",
                en.shaderStorageImageReadWithoutFormat, en.shaderStorageImageWriteWithoutFormat, en.shaderStorageImageExtendedFormats);

    // ---- vkb_nrd ----
    VkbNrdLibraryInfo info{};
    info.struct_size = sizeof(info);
    int32_t rc = vkb_nrd_init(reinterpret_cast<uint64_t>(instance), reinterpret_cast<uint64_t>(pd), reinterpret_cast<uint64_t>(g_dev),
                              reinterpret_cast<void*>(vk.GetInstanceProcAddr), reinterpret_cast<void*>(vk.GetDeviceProcAddr), nrdPath.c_str(), &info);
    if (rc != VKB_NRD_OK) return failStage("vkb_nrd_init", rc);
    std::printf("NRD_PROBE nrd_version=%u.%u.%u normal_encoding=%u roughness_encoding=%u normal_roughness_vk_format=%u "
                "spirv_offsets{sampler=%u texture=%u cb=%u storage=%u}\n",
                info.version_major, info.version_minor, info.version_build, info.normal_encoding, info.roughness_encoding,
                info.normal_roughness_vk_format, info.spirv_offset_sampler, info.spirv_offset_texture, info.spirv_offset_constant_buffer, info.spirv_offset_storage);
    if (info.normal_encoding != 2 && info.normal_encoding != 4) {
        std::printf("NRD_PROBE this probe only packs normal encodings 2 (R10G10B10A2) and 4 (RGBA16_SNORM)\n");
        return failStage("unsupported_normal_encoding", info.normal_encoding);
    }

    const uint32_t W = 1280, H = 720;
    VkbNrdCreateInfo cinfo{};
    cinfo.struct_size = sizeof(cinfo); cinfo.width = W; cinfo.height = H; cinfo.frames_in_flight = 2;
    cinfo.mode_mask = VKB_NRD_MODE_REBLUR_DIFFUSE_SPECULAR | VKB_NRD_MODE_SIGMA_SHADOW;
    uint64_t handle = 0;
    auto tc0 = std::chrono::steady_clock::now();
    rc = vkb_nrd_create_denoiser(&cinfo, &handle);
    double createMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tc0).count();
    if (rc != VKB_NRD_OK) return failStage("vkb_nrd_create_denoiser", rc);
    std::printf("NRD_PROBE created denoisers=REBLUR_DIFFUSE_SPECULAR+SIGMA_SHADOW size=%ux%u create_ms=%.1f\n", W, H, createMs);

    // ---- user resources ----
    Img mv, nr, viewz, diffIn, specIn, pen, diffOut, specOut, shadowOut;
    VkFormat nrFmt = static_cast<VkFormat>(info.normal_roughness_vk_format);
    uint32_t nrBpp = (info.normal_encoding == 4) ? 8 : 4;
    bool ok = makeImage(mv, W, H, VK_FORMAT_R16G16_SFLOAT, 4) && makeImage(nr, W, H, nrFmt, nrBpp) &&
              makeImage(viewz, W, H, VK_FORMAT_R32_SFLOAT, 4) && makeImage(diffIn, W, H, VK_FORMAT_R16G16B16A16_SFLOAT, 8) &&
              makeImage(specIn, W, H, VK_FORMAT_R16G16B16A16_SFLOAT, 8) && makeImage(pen, W, H, VK_FORMAT_R16_SFLOAT, 2) &&
              makeImage(diffOut, W, H, VK_FORMAT_R16G16B16A16_SFLOAT, 8) && makeImage(specOut, W, H, VK_FORMAT_R16G16B16A16_SFLOAT, 8) &&
              makeImage(shadowOut, W, H, VK_FORMAT_R8_UNORM, 1);
    if (!ok) return failStage("create_user_images", 0);
    Img* all[] = {&mv, &nr, &viewz, &diffIn, &specIn, &pen, &diffOut, &specOut, &shadowOut};

    // staging layout for inputs (static ones are uploaded each frame too: trivial cost, keeps the probe simple)
    const size_t px = size_t(W) * H;
    VkDeviceSize off = 0;
    VkDeviceSize offMv = off; off += px * 4;
    VkDeviceSize offNr = off; off += px * nrBpp;
    VkDeviceSize offViewz = off; off += px * 4;
    VkDeviceSize offDiff = off; off += px * 8;
    VkDeviceSize offSpec = off; off += px * 8;
    VkDeviceSize offPen = off; off += px * 2;
    Buf staging, readback;
    if (!makeHostBuffer(staging, off) || !makeHostBuffer(readback, px * 8 + px)) return failStage("create_host_buffers", 0);
    VkDeviceSize rbDiff = 0, rbShadow = px * 8;

    // static inputs
    {
        std::memset(staging.map + offMv, 0, px * 4);  // zero motion
        // normal toward the camera (camera looks along +z in a left-handed view space): n = (0,0,-1), roughness 1
        if (info.normal_encoding == 2) {
            // _NRD_EncodeNormalRoughness101010: x=y=0.5, z = (-roughness)*0.5+0.5 = 0, w = materialID = 0
            uint32_t packed = (512u) | (512u << 10) | (0u << 20) | (0u << 30);  // A2B10G10R10: R in bits 0..9
            uint32_t* p = reinterpret_cast<uint32_t*>(staging.map + offNr);
            for (size_t i = 0; i < px; ++i) p[i] = packed;
        } else {
            // enc 4 RGBA16_SNORM: xyz = n / max|n|, w = linear roughness
            int16_t* p = reinterpret_cast<int16_t*>(staging.map + offNr);
            for (size_t i = 0; i < px; ++i) { p[i * 4] = 0; p[i * 4 + 1] = 0; p[i * 4 + 2] = -32767; p[i * 4 + 3] = 32767; }
        }
        float* vz = reinterpret_cast<float*>(staging.map + offViewz);
        for (size_t i = 0; i < px; ++i) vz[i] = 10.0f;
        uint16_t* pe = reinterpret_cast<uint16_t*>(staging.map + offPen);
        // SIGMA input: left half = no occluder (NRD_FP16_MAX == lit), right half = hard shadow (tiny penumbra radius)
        const uint16_t lit = f2h(65504.0f), shadowed = f2h(0.01f);
        for (uint32_t y = 0; y < H; ++y)
            for (uint32_t x = 0; x < W; ++x) pe[size_t(y) * W + x] = (x < W / 2) ? lit : shadowed;
    }

    // initial layout: UNDEFINED -> GENERAL for all user images
    {
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vk.BeginCommandBuffer(cmd, &bi);
        std::vector<VkImageMemoryBarrier> bs;
        for (Img* im : all) {
            VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = im->image; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            bs.push_back(b);
        }
        vk.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, uint32_t(bs.size()), bs.data());
        vk.EndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
        vk.QueueSubmit(queue, 1, &si, VK_NULL_HANDLE);
        vk.QueueWaitIdle(queue);
    }

    VkbNrdResource res[VKB_NRD_RES_COUNT];
    std::memset(res, 0, sizeof(res));
    auto setRes = [&](int slot, const Img& im) {
        res[slot].image = reinterpret_cast<uint64_t>(im.image);
        res[slot].view = reinterpret_cast<uint64_t>(im.view);
        res[slot].vk_format = static_cast<uint32_t>(im.format);
    };
    setRes(VKB_NRD_RES_IN_MV, mv);
    setRes(VKB_NRD_RES_IN_NORMAL_ROUGHNESS, nr);
    setRes(VKB_NRD_RES_IN_VIEWZ, viewz);
    setRes(VKB_NRD_RES_IN_DIFF_RADIANCE_HITDIST, diffIn);
    setRes(VKB_NRD_RES_IN_SPEC_RADIANCE_HITDIST, specIn);
    setRes(VKB_NRD_RES_IN_PENUMBRA, pen);
    setRes(VKB_NRD_RES_OUT_DIFF_RADIANCE_HITDIST, diffOut);
    setRes(VKB_NRD_RES_OUT_SPEC_RADIANCE_HITDIST, specOut);
    setRes(VKB_NRD_RES_OUT_SHADOW_TRANSLUCENCY, shadowOut);

    // settings
    VkbNrdFrameSettings fs{};
    fs.struct_size = sizeof(fs);
    fillMatrixLH(fs.view_to_clip, 1.0471976f, float(W) / float(H), 0.1f, 1000.0f);
    std::memcpy(fs.view_to_clip_prev, fs.view_to_clip, sizeof(fs.view_to_clip));
    identity(fs.world_to_view); identity(fs.world_to_view_prev);
    fs.mv_scale[0] = fs.mv_scale[1] = 1.0f; fs.mv_scale[2] = 0.0f;
    fs.rect_width = fs.rect_width_prev = W; fs.rect_height = fs.rect_height_prev = H;
    fs.view_z_scale = 1.0f; fs.denoising_range = 500.0f; fs.disocclusion_threshold = 0.01f;
    fs.accumulation_mode = VKB_NRD_ACCUM_CONTINUE;
    fs.flags = 0;

    VkbNrdReblurSettings rs{};
    rs.struct_size = sizeof(rs); rs.max_accumulated_frame_num = 30; rs.max_fast_accumulated_frame_num = 6; rs.max_stabilized_frame_num = 63;
    rs.hit_dist_a = 3.0f; rs.hit_dist_b = 0.1f; rs.hit_dist_c = 20.0f; rs.min_blur_radius = 1.0f; rs.max_blur_radius = 30.0f;
    rs.diffuse_prepass_blur_radius = 30.0f; rs.specular_prepass_blur_radius = 50.0f; rs.plane_distance_sensitivity = 0.02f; rs.enable_anti_firefly = 1;
    rc = vkb_nrd_set_reblur_settings(handle, &rs);
    if (rc != VKB_NRD_OK) return failStage("set_reblur_settings", rc);
    VkbNrdSigmaSettings ss{};
    ss.struct_size = sizeof(ss); ss.light_direction[0] = 0.3f; ss.light_direction[1] = 0.8f; ss.light_direction[2] = -0.5f;
    ss.plane_distance_sensitivity = 0.02f; ss.max_stabilized_frame_num = 5;
    rc = vkb_nrd_set_sigma_settings(handle, &ss);
    if (rc != VKB_NRD_OK) return failStage("set_sigma_settings", rc);

    // ---- frames ----
    std::mt19937 rng(12345);
    std::uniform_real_distribution<float> uni(0.0f, 2.0f);  // radiance noise: mean 1, std 0.5774
    const float normHitDist = 0.5f;
    const uint32_t x0 = 128, x1 = W - 128, y0 = 128, y1 = H - 128;
    double stdIn = 0, stdOut = 0, meanIn = 0, meanOut = 0, shadowLit = 0, shadowDark = 0;
    uint32_t totalDispatch = 0, pipelineCount = 0, lastDispatch = 0, lastClear = 0, lastSets = 0;
    uint64_t poolBytes = 0;
    double sumMs = 0;
    bool frameFailed = false;

    for (int f = 0; f < frames; ++f) {
        // fresh white noise for diffuse and specular: YCoCg of a gray radiance c is (c, 0, 0), w = normHitDist
        uint16_t* di = reinterpret_cast<uint16_t*>(staging.map + offDiff);
        uint16_t* sp = reinterpret_cast<uint16_t*>(staging.map + offSpec);
        const uint16_t hw = f2h(normHitDist), zero = f2h(0.0f);
        double sIn = 0, sqIn = 0;
        size_t cnt = 0;
        for (uint32_t y = 0; y < H; ++y) {
            for (uint32_t x = 0; x < W; ++x) {
                size_t i = size_t(y) * W + x;
                uint16_t cH = f2h(uni(rng));
                di[i * 4 + 0] = cH; di[i * 4 + 1] = zero; di[i * 4 + 2] = zero; di[i * 4 + 3] = hw;
                sp[i * 4 + 0] = f2h(uni(rng)); sp[i * 4 + 1] = zero; sp[i * 4 + 2] = zero; sp[i * 4 + 3] = hw;
                if (x >= x0 && x < x1 && y >= y0 && y < y1) { double v = h2f(cH); sIn += v; sqIn += v * v; ++cnt; }
            }
        }
        meanIn = sIn / double(cnt);
        stdIn = std::sqrt(std::max(0.0, sqIn / double(cnt) - meanIn * meanIn));

        fs.frame_index = uint32_t(f);
        rc = vkb_nrd_set_frame_settings(handle, &fs);
        if (rc != VKB_NRD_OK) return failStage("set_frame_settings", rc);

        auto t0 = std::chrono::steady_clock::now();
        vk.ResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vk.BeginCommandBuffer(cmd, &bi);
        copyToImage(cmd, staging, offMv, mv);
        copyToImage(cmd, staging, offNr, nr);
        copyToImage(cmd, staging, offViewz, viewz);
        copyToImage(cmd, staging, offDiff, diffIn);
        copyToImage(cmd, staging, offSpec, specIn);
        copyToImage(cmd, staging, offPen, pen);
        memBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                   VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        VkbNrdDispatchStats st{};
        st.struct_size = sizeof(st);
        rc = vkb_nrd_dispatch(handle, reinterpret_cast<uint64_t>(cmd), cinfo.mode_mask, res, &st);
        if (rc != VKB_NRD_OK) { vk.EndCommandBuffer(cmd); return failStage("vkb_nrd_dispatch", rc); }
        memBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        copyFromImage(cmd, diffOut, readback, rbDiff);
        copyFromImage(cmd, shadowOut, readback, rbShadow);
        vk.EndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
        vr = vk.QueueSubmit(queue, 1, &si, VK_NULL_HANDLE);
        if (vr != VK_SUCCESS) return failStage("vkQueueSubmit", vr);
        vr = vk.QueueWaitIdle(queue);
        if (vr != VK_SUCCESS) { frameFailed = true; std::printf("NRD_PROBE frame=%d vkQueueWaitIdle=%d (VK_ERROR_DEVICE_LOST=%d)\n", f, vr, VK_ERROR_DEVICE_LOST); return failStage("vkQueueWaitIdle", vr); }
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        sumMs += ms;

        const uint16_t* o = reinterpret_cast<const uint16_t*>(readback.map + rbDiff);
        double sO = 0, sqO = 0, nanCount = 0;
        for (uint32_t y = y0; y < y1; ++y)
            for (uint32_t x = x0; x < x1; ++x) {
                float v = h2f(o[(size_t(y) * W + x) * 4]);
                if (!std::isfinite(v)) { nanCount += 1; continue; }
                sO += v; sqO += double(v) * v;
            }
        const double n2 = double(cnt) - nanCount;
        meanOut = sO / n2;
        stdOut = std::sqrt(std::max(0.0, sqO / n2 - meanOut * meanOut));
        const uint8_t* shp = readback.map + rbShadow;
        double shL = 0, shR = 0;
        size_t cL = 0, cR = 0;
        for (uint32_t y = y0; y < y1; ++y) {
            for (uint32_t x = x0; x < W / 2 - 64; ++x) { shL += shp[size_t(y) * W + x]; ++cL; }
            for (uint32_t x = W / 2 + 64; x < x1; ++x) { shR += shp[size_t(y) * W + x]; ++cR; }
        }
        shadowLit = shL / double(cL) / 255.0;
        shadowDark = shR / double(cR) / 255.0;

        totalDispatch += st.dispatch_count;
        pipelineCount = st.pipeline_count; lastDispatch = st.dispatch_count; lastClear = st.clear_dispatch_count; lastSets = st.descriptor_sets;
        poolBytes = st.pool_memory_bytes;
        std::printf("NRD_PROBE frame=%d dispatches=%u clear=%u sets=%u const_bytes=%u cpu_record_ms=%.3f frame_wall_ms=%.2f "
                    "in{mean=%.4f std=%.4f} out{mean=%.4f std=%.4f nonfinite=%.0f} shadow{lit_half=%.3f shadowed_half=%.3f}\n",
                    f, st.dispatch_count, st.clear_dispatch_count, st.descriptor_sets, st.constant_bytes, st.cpu_ms, ms, meanIn, stdIn,
                    meanOut, stdOut, nanCount, shadowLit, shadowDark);
    }
    (void)frameFailed;

    std::printf("NRD_PROBE summary pipelines=%u dispatches_total=%u last_frame_dispatches=%u (clear=%u) descriptor_sets_last=%u pool_memory_MiB=%.1f avg_frame_wall_ms=%.2f\n",
                pipelineCount, totalDispatch, lastDispatch, lastClear, lastSets, double(poolBytes) / (1024.0 * 1024.0), sumMs / std::max(1, frames));
    const double ratio = stdOut / stdIn;
    const double meanErr = std::fabs(meanOut / meanIn - 1.0);
    const bool stdOk = stdOut < stdIn / 4.0;
    const bool meanOk = meanErr <= 0.05;
    const bool sigmaOk = shadowLit > 0.9 && shadowDark < 0.1;
    std::printf("NRD_PROBE N1 frames=%d std_in=%.5f std_out=%.5f std_ratio_out_over_in=%.4f (criterion < 0.25: %s) mean_in=%.5f mean_out=%.5f mean_error=%.2f%% (criterion <= 5%%: %s)\n",
                frames, stdIn, stdOut, ratio, stdOk ? "met" : "NOT met", meanIn, meanOut, meanErr * 100.0, meanOk ? "met" : "NOT met");

    std::printf("NRD_PROBE SIGMA shadow_lit_half=%.3f (expect >0.9) shadow_shadowed_half=%.3f (expect <0.1): %s\n", shadowLit, shadowDark, sigmaOk ? "met" : "NOT met");
    vk.DeviceWaitIdle(g_dev);
    vkb_nrd_destroy(handle);
    vkb_nrd_shutdown();
    for (Img* im : all) {
        vk.DestroyImageView(g_dev, im->view, nullptr); vk.DestroyImage(g_dev, im->image, nullptr); vk.FreeMemory(g_dev, im->mem, nullptr);
    }
    vk.DestroyBuffer(g_dev, staging.buffer, nullptr); vk.FreeMemory(g_dev, staging.mem, nullptr);
    vk.DestroyBuffer(g_dev, readback.buffer, nullptr); vk.FreeMemory(g_dev, readback.mem, nullptr);
    vk.DestroyCommandPool(g_dev, cpool, nullptr);
    vk.DestroyDevice(g_dev, nullptr);
    vk.DestroyInstance(instance, nullptr);

    if (!stdOk || !meanOk || !sigmaOk) {
        std::printf("NRD_PROBE FAILED stage=metric (N1 and/or SIGMA check, see values above)\n");
        return 2;
    }
    std::printf("NRD_PROBE OK\n");
    return 0;
}
