#pragma once

#include "bedray/gpu_buffers.hpp"

namespace bedray::gpu {

struct ImageId {
    std::uint64_t owner_generation = 0, serial = 0;
    friend bool operator==(ImageId, ImageId) = default;
};

struct ImageFunctions {
    PFN_vkCreateImage create_image = nullptr;
    PFN_vkDestroyImage destroy_image = nullptr;
    PFN_vkGetImageMemoryRequirements get_requirements = nullptr;
    PFN_vkAllocateMemory allocate_memory = nullptr;
    PFN_vkFreeMemory free_memory = nullptr;
    PFN_vkBindImageMemory bind_image_memory = nullptr;
    PFN_vkCreateImageView create_view = nullptr;
    PFN_vkDestroyImageView destroy_view = nullptr;
    PFN_vkGetPhysicalDeviceImageFormatProperties image_format_properties = nullptr;
};

struct ImageDeviceContext {
    VkPhysicalDevice physical_device = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    ImageFunctions functions{};
    VkPhysicalDeviceMemoryProperties memory_properties{};
    bool dedicated_allocation_enabled = false;
    std::uint64_t generation = 0;
};

// One mip, one layer, one sample, optimal 2D colour image. More general image
// types need explicit view/subresource contracts before extending this owner.
struct ImageDesc {
    std::uint32_t width = 0, height = 0;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkImageUsageFlags usage = 0;
    VkMemoryPropertyFlags required = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    VkMemoryPropertyFlags preferred = 0;
};

struct ImageView {
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    std::uint32_t width = 0, height = 0;
    VkDeviceSize allocation_bytes = 0;
};

// A device-local colour-image owner using the game's device and dispatch.
// It records no commands and tracks no image layouts: the graph must establish
// transitions from UNDEFINED and all visibility/queue-ownership dependencies.
// Reserve a use BEFORE recording and complete it only after proven discard or
// all GPU submissions completed and resubmission is impossible. Destruction
// without explicit cleanup deliberately leaks rather than freeing in-flight
// resources. Every call is externally serialized on the renderer thread.
class GpuImages final {
public:
    explicit GpuImages(ImageDeviceContext context, VkDeviceSize budget) noexcept;
    GpuImages(const GpuImages&) = delete;
    GpuImages& operator=(const GpuImages&) = delete;
    ~GpuImages() = default;

    [[nodiscard]] bool valid() const noexcept;
    [[nodiscard]] Status create(const ImageDesc&, ImageId&, ImageView&, VkResult& error) noexcept;
    [[nodiscard]] Status view(ImageId, ImageView&) const noexcept;
    [[nodiscard]] Status begin_use(ImageId, UseId&) noexcept;
    [[nodiscard]] Status complete_use(UseId) noexcept;
    [[nodiscard]] Status retire(ImageId) noexcept;
    [[nodiscard]] Status collect() noexcept;
    // Same whole-device proof as GpuBuffers: idle AND no future resubmissions.
    [[nodiscard]] Status device_idle() noexcept;
    void device_lost() noexcept { lost_ = true; }
    // Quiesced device-loss teardown, before the host destroys VkDevice.
    [[nodiscard]] Status release_after_device_loss() noexcept;
    [[nodiscard]] Status close() noexcept;
    [[nodiscard]] VkDeviceSize resident_bytes() const noexcept { return resident_bytes_; }

private:
    struct Image {
        ImageId id{};
        ImageView public_view{};
        VkDeviceMemory memory = VK_NULL_HANDLE;
        bool retired = false;
    };
    struct Use { UseId id; ImageId image; };
    [[nodiscard]] Image* find(ImageId) noexcept;
    [[nodiscard]] const Image* find(ImageId) const noexcept;
    [[nodiscard]] bool has_use(ImageId) const noexcept;
    void destroy(Image&) noexcept;

    ImageDeviceContext context_;
    VkDeviceSize budget_ = 0, resident_bytes_ = 0;
    std::vector<Image> images_;
    std::vector<Use> uses_;
    std::uint64_t next_image_ = 1, next_use_ = 1;
    bool lost_ = false, closed_ = false;
};

} // namespace bedray::gpu
