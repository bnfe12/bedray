#pragma once

#include <vulkan/vulkan.h>
#include <cstdint>
#include <vector>

namespace bedray::gpu {

// Generation is unique per manager lifetime, even for a replacement manager
// on the same VkDevice. The host assigns it and never reuses it in-process.
struct BufferId {
    std::uint64_t owner_generation = 0, serial = 0;
    friend bool operator==(BufferId, BufferId) = default;
};
struct UseId {
    std::uint64_t owner_generation = 0, serial = 0;
    friend bool operator==(UseId, UseId) = default;
};

enum class Status : std::uint32_t {
    ok, invalid_argument, unavailable, unknown_buffer, unknown_use, in_use,
    retired, budget_exceeded, unsupported, exhausted, out_of_memory, device_lost, vulkan_error,
    unknown_image
};

// Supplied by the game-owned device. No loader, instance, queue, command pool,
// submission or wait is created by this module. All calls require external
// synchronization on the render thread and a live device unless documented.
struct BufferFunctions {
    PFN_vkCreateBuffer create_buffer = nullptr;
    PFN_vkDestroyBuffer destroy_buffer = nullptr;
    PFN_vkGetBufferMemoryRequirements get_requirements = nullptr;
    PFN_vkAllocateMemory allocate_memory = nullptr;
    PFN_vkFreeMemory free_memory = nullptr;
    PFN_vkBindBufferMemory bind_buffer_memory = nullptr;
    PFN_vkMapMemory map_memory = nullptr;
    PFN_vkUnmapMemory unmap_memory = nullptr;
    PFN_vkFlushMappedMemoryRanges flush = nullptr;
    PFN_vkInvalidateMappedMemoryRanges invalidate = nullptr;
    PFN_vkGetBufferDeviceAddress get_buffer_device_address = nullptr;
};

struct DeviceContext {
    VkDevice device = VK_NULL_HANDLE;
    BufferFunctions functions{};
    VkPhysicalDeviceMemoryProperties memory_properties{};
    VkDeviceSize non_coherent_atom_size = 0;
    // The host must prove Vulkan 1.1 or the corresponding dedicated-allocation
    // extension was enabled. Device address requires its own enabled feature.
    bool dedicated_allocation_enabled = false;
    bool buffer_device_address_enabled = false;
    std::uint64_t generation = 0;
};

struct BufferDesc {
    VkDeviceSize size = 0;
    VkBufferUsageFlags usage = 0;
    VkMemoryPropertyFlags required = 0;
    VkMemoryPropertyFlags preferred = 0;
    bool mapped = false;
};

struct BufferView {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
    VkMemoryPropertyFlags memory_flags = 0;
    VkDeviceAddress device_address = 0; // zero when not requested
};

// A use is reserved BEFORE recording references a buffer. It remains pinned
// until the owner proves the recording was discarded before submission OR all
// submissions of that recording completed and it cannot be resubmitted.
// Unknown outcomes deliberately retain the use. Uses are not Vulkan barriers.
// Methods do not call the driver while collect/close are waiting for proof.
class GpuBuffers final {
public:
    explicit GpuBuffers(DeviceContext context, VkDeviceSize budget_bytes) noexcept;
    ~GpuBuffers() = default; // never destroys in-flight objects implicitly
    GpuBuffers(const GpuBuffers&) = delete;
    GpuBuffers& operator=(const GpuBuffers&) = delete;

    [[nodiscard]] bool valid() const noexcept;
    [[nodiscard]] Status create(const BufferDesc& desc, BufferId& id, BufferView& view, VkResult& error) noexcept;
    [[nodiscard]] Status view(BufferId id, BufferView& out) const noexcept;
    [[nodiscard]] Status write(BufferId id, VkDeviceSize offset, const void* data, VkDeviceSize bytes, VkResult& error) noexcept;
    [[nodiscard]] Status read(BufferId id, VkDeviceSize offset, void* data, VkDeviceSize bytes, VkResult& error) noexcept;
    // Upload and reserve one read-only use atomically. Pool slots are reused
    // only after every previous use completed; no frame-count assumption.
    [[nodiscard]] Status upload(VkBufferUsageFlags usage, const void* data, VkDeviceSize bytes,
        BufferId& id, BufferView& view, UseId& use, VkResult& error) noexcept;
    [[nodiscard]] Status retire_uploads() noexcept;
    [[nodiscard]] Status begin_use(BufferId id, UseId& token) noexcept;
    // The host must have proven completed-or-discarded as described above;
    // completing an unknown token without such proof is unsafe.
    [[nodiscard]] Status complete_use(UseId token) noexcept;
    [[nodiscard]] Status retire(BufferId id) noexcept;
    [[nodiscard]] Status collect() noexcept;
    // Called only after the owner proves the WHOLE device idle AND all
    // recordings covered by the current uses cannot be submitted or resubmitted.
    [[nodiscard]] Status device_idle() noexcept;
    // Device loss does not imply that ordinary completion was observed.
    void device_lost() noexcept { lost_ = true; }
    // Host-only device-loss teardown: after it quiesces all Vulkan callers and
    // before VkDevice destruction. This releases even unknown pinned uses.
    [[nodiscard]] Status release_after_device_loss() noexcept;
    // Refuses while any live/retired buffer or use remains.
    [[nodiscard]] Status close() noexcept;
    [[nodiscard]] VkDeviceSize resident_bytes() const noexcept { return resident_bytes_; }

private:
    struct Buffer {
        BufferId id{};
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0, allocation_size = 0;
        VkMemoryPropertyFlags flags = 0;
        VkDeviceAddress device_address = 0;
        void* mapped = nullptr;
        bool retired = false;
        VkBufferUsageFlags usage = 0;
        bool upload_slot = false;
    };
    struct Use { UseId id; BufferId buffer; };
    [[nodiscard]] Buffer* find(BufferId id) noexcept;
    [[nodiscard]] const Buffer* find(BufferId id) const noexcept;
    [[nodiscard]] bool has_use(BufferId id) const noexcept;
    void destroy(Buffer& buffer) noexcept;
    [[nodiscard]] Status mapped_transfer(BufferId id, VkDeviceSize offset, void* data,
                                          VkDeviceSize bytes, bool is_write, VkResult& error) noexcept;

    DeviceContext context_;
    VkDeviceSize budget_ = 0, resident_bytes_ = 0;
    std::vector<Buffer> buffers_;
    std::vector<Use> uses_;
    std::uint64_t next_buffer_ = 1;
    std::uint64_t next_use_ = 1;
    bool lost_ = false, closed_ = false;
};

} // namespace bedray::gpu
