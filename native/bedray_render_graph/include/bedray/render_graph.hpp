#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace bedray::graph {

using ResourceId = std::uint32_t;
using PassId = std::uint32_t;
inline constexpr PassId external_pass = std::numeric_limits<PassId>::max();
inline constexpr std::uint32_t any_queue = std::numeric_limits<std::uint32_t>::max();
// Vulkan guarantees at least 128 bytes of push-constant storage.
inline constexpr std::uint32_t max_push_constant_bytes = 128;

enum class ResourceKind : std::uint8_t { buffer, image, acceleration_structure };
enum class Access : std::uint8_t { read, write, read_write };
enum class Stage : std::uint8_t { transfer, compute, acceleration_build, ray_tracing };

// Each logical resource has one identity for a compiled graph. If underlying
// memory aliases another resource, provide the same nonzero allocation_id so
// compilation fails until the graph gains explicit aliasing/lifetime support.
// Distinct image views of the same image must not be registered separately.
struct ResourceDesc {
    std::string semantic;
    ResourceKind kind;
    std::uint64_t allocation_id;
    bool initialized_on_entry;
};

struct ResourceUse {
    ResourceId resource;
    Access access;
    Stage stage;
    // VkImageLayout numeric value for images; 0 (UNDEFINED) is invalid for a
    // pass use. Other resource kinds must use 0. Transitions at graph entry and
    // exit require separate host contracts and are not inferred here.
    std::uint32_t image_layout = 0;
};

struct PushConstantRange {
    std::uint32_t offset = 0;
    std::uint32_t size = 0;
};

struct DispatchSize {
    std::uint32_t x = 1;
    std::uint32_t y = 1;
    std::uint32_t z = 1;
};

struct PassDesc {
    std::string name;
    std::vector<ResourceUse> uses;
    // Pass IDs which must complete before this pass. Dependencies must be
    // explicit; insertion order is never treated as synchronization.
    std::vector<PassId> after;
    // The host supplies actually enabled logical-device feature bits.
    std::uint64_t required_capabilities = 0;
    // any_queue means "the host's recording queue". Different explicit
    // families are rejected until cross-queue synchronization is modelled.
    std::uint32_t queue_family = any_queue;
    // Compute dispatch dimensions. Every pass in this planner is a dispatch.
    DispatchSize dispatch{};
    // Shader push-constant ranges in bytes; must match the AOT manifest.
    std::vector<PushConstantRange> push_constants;
};

// An ordering constraint, not a ready-to-emit Vulkan barrier. Multiple entries
// can describe transitive uses of one resource; the adapter must coalesce them
// and track the actual immediately preceding layout/access before recording.
// External producers/consumers require explicit host entry/exit contracts.
struct Hazard {
    ResourceId resource;
    PassId source;
    PassId destination;
    ResourceUse before;
    ResourceUse after;
};

// A minimal/maximal graph use: the host/Vulkan adapter must establish the
// external dependency for each entry or exit. Multiple unordered readers can
// appear at the same boundary. This is not a submission or barrier command.
struct BoundaryUse {
    ResourceId resource;
    PassId pass;
    ResourceUse use;
};

struct CompiledGraph {
    std::vector<PassId> order;
    std::vector<Hazard> hazards;
    std::vector<BoundaryUse> entries;
    std::vector<BoundaryUse> exits;
    // Non-owning identity: a plan must not outlive its source RenderGraph.
    const void* owner = nullptr;
    std::uint64_t revision = 0;
    std::uint64_t enabled_capabilities = 0;
    // Explicit family used by every pass, or any_queue when unspecified.
    std::uint32_t queue_family = any_queue;
};

enum class ErrorCode : std::uint8_t {
    none,
    empty_name,
    duplicate_name,
    invalid_resource,
    invalid_dependency,
    invalid_usage,
    duplicate_use,
    self_dependency,
    cycle,
    unsupported_alias,
    unordered_hazard,
    uninitialized_read,
    missing_capability,
    invalid_dispatch,
    invalid_push_constant,
    unsupported_multi_queue,
};

struct Error {
    ErrorCode code = ErrorCode::none;
    ResourceId resource = 0;
    PassId pass = 0;
    PassId other_pass = 0;
    std::uint64_t missing_capabilities = 0;
};

class RenderGraph final {
public:
    RenderGraph() = default;
    RenderGraph(const RenderGraph&) = delete;
    RenderGraph& operator=(const RenderGraph&) = delete;
    RenderGraph(RenderGraph&&) = delete;
    RenderGraph& operator=(RenderGraph&&) = delete;
    ResourceId add_resource(ResourceDesc resource);
    PassId add_pass(PassDesc pass);

    // Returned pointers are invalidated by subsequent additions. Compiled
    // plans are snapshots and must be rebuilt after any graph mutation.
    [[nodiscard]] const ResourceDesc* resource(ResourceId id) const noexcept;
    [[nodiscard]] const PassDesc* pass(PassId id) const noexcept;
    [[nodiscard]] std::size_t resource_count() const noexcept { return resources_.size(); }
    [[nodiscard]] std::size_t pass_count() const noexcept { return passes_.size(); }
    [[nodiscard]] bool is_current(const CompiledGraph& plan, std::uint64_t enabled_capabilities) const noexcept {
        return plan.owner == this && plan.revision == revision_ &&
               plan.enabled_capabilities == enabled_capabilities;
    }

    // Pure CPU planning; does not allocate Vulkan objects or record commands.
    // On failure `out` remains unchanged. Objects must be externally
    // synchronized by their owner. Resource/Pass IDs are graph-local.
    [[nodiscard]] bool compile(std::uint64_t enabled_capabilities, CompiledGraph& out, Error& error) const;

private:
    std::vector<ResourceDesc> resources_;
    std::vector<PassDesc> passes_;
    std::uint64_t revision_ = 0;
};

} // namespace bedray::graph
