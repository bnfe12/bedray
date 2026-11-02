#pragma once
#include <vulkan/vulkan.h>
#include <cstdint>
#include <vector>

namespace bedray::gpu {
enum class Status : std::uint32_t;

struct MemoryFunctions {
    PFN_vkAllocateMemory allocate = nullptr;
    PFN_vkFreeMemory free = nullptr;
    PFN_vkMapMemory map = nullptr;
    PFN_vkUnmapMemory unmap = nullptr;
    PFN_vkFlushMappedMemoryRanges flush = nullptr;
    PFN_vkInvalidateMappedMemoryRanges invalidate = nullptr;
    PFN_vkGetBufferMemoryRequirements2 buffer_requirements = nullptr;
    PFN_vkGetImageMemoryRequirements2 image_requirements = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties2 heap_properties = nullptr;
};
struct MemoryContext {
    VkDevice device = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties properties{};
    MemoryFunctions functions{};
    VkDeviceSize atom_size = 1, limit = 0, block_size = 0;
    std::uint32_t max_blocks = 0;
    bool heap_budget_enabled = false;
};
struct MemoryAllocation {
    std::uint64_t block = 0, serial = 0;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize offset = 0, size = 0, span = 0;
    void* mapped = nullptr; // already rebased to this allocation, never the entire block
};
struct MemoryStats {
    std::uint64_t limit = 0, reserved = 0, used = 0, peak_reserved = 0;
    std::uint64_t allocations = 0, blocks = 0, heap_budget = 0, heap_usage = 0, flags = 0;
};

// One externally synchronized service per core/device. Resource owners retain it through shared_ptr.
// It allocates memory only, never resources, command buffers, queues, barriers, submissions or waits.
// Its destructor deliberately makes no Vulkan calls: leaked/unknown uses cannot become implicit frees.
class MemoryAllocator final {
public:
    explicit MemoryAllocator(MemoryContext context) noexcept : context_(context) {}
    ~MemoryAllocator() = default;
    MemoryAllocator(const MemoryAllocator&) = delete;
    MemoryAllocator& operator=(const MemoryAllocator&) = delete;
    bool valid() const noexcept;
    VkDevice device() const noexcept { return context_.device; }
    bool lost() const noexcept { return lost_; }
    void device_lost() noexcept { lost_ = true; }
    Status buffer(VkBuffer, std::uint32_t memory_type, bool device_address, bool mapped,
                  MemoryAllocation&, VkResult&) noexcept;
    Status image(VkImage, std::uint32_t memory_type, MemoryAllocation&, VkResult&) noexcept;
    // Called only after the owning resource's exact completion/discard proof and resource destruction.
    void release(const MemoryAllocation&) noexcept;
    Status synchronize(const MemoryAllocation&, VkDeviceSize offset, VkDeviceSize size, bool write, VkResult&) noexcept;
    MemoryStats stats() const noexcept;
private:
    struct Span { std::uint64_t serial; VkDeviceSize offset, size, requested; };
    struct Block {
        std::uint64_t id = 0;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0;
        std::uint32_t type = 0;
        bool address = false, mapped = false, image = false, dedicated = false;
        void* base = nullptr;
        std::vector<Span> spans; // ordered by offset; erasing/freeing never allocates
    };
    Status allocate(const VkMemoryRequirements&, bool dedicated, VkBuffer, VkImage, std::uint32_t,
                    bool address, bool mapped, MemoryAllocation&, VkResult&) noexcept;
    bool room(std::uint32_t memory_type, VkDeviceSize bytes) const noexcept;
    const Span* span(const MemoryAllocation&, const Block*&) const noexcept;
    void publish(Block&, VkDeviceSize offset, VkDeviceSize occupied, VkDeviceSize requested, MemoryAllocation&);
    MemoryContext context_;
    std::vector<Block> blocks_;
    std::uint64_t next_block_ = 1, next_allocation_ = 1;
    VkDeviceSize reserved_ = 0, used_ = 0, peak_ = 0;
    std::uint64_t count_ = 0;
    bool lost_ = false;
};
}
