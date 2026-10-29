#include "frame_graph_owner.h"
#include <cstdio>
#include <cstdlib>

using bedray::core::FrameGraphOwner;
namespace graph = bedray::graph;
namespace {
void check(bool value) { if (!value) std::abort(); }
int barriers = 0;
VKAPI_ATTR void VKAPI_CALL barrier(VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags,
    VkDependencyFlags, std::uint32_t, const VkMemoryBarrier*, std::uint32_t,
    const VkBufferMemoryBarrier*, std::uint32_t, const VkImageMemoryBarrier*) { ++barriers; }
struct Context {
    FrameGraphOwner* owner = nullptr;
    int records = 0, releases = 0;
    std::uint64_t last_guide_view = 0;
    BedrayStatus record_status = BEDRAY_OK, release_status = BEDRAY_OK;
};
BedrayStatus record(void* pointer, const BedrayPathTraceFrameDesc&, const BedrayPathTraceGuides* guides, BedrayError* error) {
    auto& c = *static_cast<Context*>(pointer);
    ++c.records;
    c.last_guide_view = guides ? guides->view_z : 0;
    if (error) error->status = c.record_status;
    return c.record_status;
}
BedrayStatus release(void* pointer, std::uint64_t command) {
    auto& c = *static_cast<Context*>(pointer);
    check(c.owner->releasing(command));
    ++c.releases;
    return c.release_status;
}
constexpr std::uint32_t caps = BEDRAY_CAP_RAY_QUERY | BEDRAY_CAP_ACCELERATION_STRUCTURE
    | BEDRAY_CAP_BUFFER_DEVICE_ADDRESS | BEDRAY_CAP_SHADER_INT64;
BedrayPathTraceFrameDesc frame(std::uint64_t command) {
    BedrayPathTraceFrameDesc f{};
    f.abi_version = BEDRAY_CORE_ABI_VERSION;
    f.command_buffer = command;
    return f;
}
}

int main() {
    FrameGraphOwner owner(2);
    Context context{&owner};
    BedrayError error{};
    check(owner.configure(caps & ~BEDRAY_CAP_SHADER_INT64, 3, barrier, record, release, &context, &error)
        == BEDRAY_ERROR_UNSUPPORTED);
    check(!owner.has_recordings());
    check(owner.configure(caps, 3, barrier, record, release, &context, &error) == BEDRAY_OK);
    check(owner.record(frame(10), &error) == BEDRAY_OK);
    check(owner.record(frame(10), &error) == BEDRAY_ERROR_IN_USE);
    BedrayPathTraceGuides guides{BEDRAY_CORE_ABI_VERSION, sizeof(BedrayPathTraceGuides), 101,102,103,104,105,106,107};
    check(owner.record(frame(11), &error, &guides) == BEDRAY_OK);
    guides.view_z = 999;
    check(context.last_guide_view == 101); // Consumed synchronously; scheduler does not retain borrowed POD.
    check(owner.record(frame(12), &error) == BEDRAY_ERROR_IN_USE);
    check(context.records == 2 && barriers == 0); // pass is the only barrier authority
    check(owner.release_proven(10, &error) == BEDRAY_OK);
    check(owner.release_proven(10, &error) == BEDRAY_OK && context.releases == 1);
    check(owner.record(frame(12), &error) == BEDRAY_OK);
    context.release_status = BEDRAY_ERROR_VULKAN;
    check(owner.release_proven(11, &error) == BEDRAY_ERROR_VULKAN);
    check(owner.record(frame(13), &error) == BEDRAY_ERROR_IN_USE); // failed release retains its slot
    context.release_status = BEDRAY_OK;
    check(owner.release_proven(11, &error) == BEDRAY_OK);
    check(owner.release_proven(12, &error) == BEDRAY_OK && !owner.has_recordings());
    context.record_status = BEDRAY_ERROR_VULKAN;
    check(owner.record(frame(10), &error) == BEDRAY_ERROR_VULKAN);
    check(owner.has_recordings()); // partial recording failure requires host discard
    check(owner.record(frame(10), &error) == BEDRAY_ERROR_IN_USE);
    check(owner.release_proven(10, &error) == BEDRAY_OK && !owner.has_recordings());
    check(barriers == 0);

    graph::RenderGraph invalid;
    const auto resource = invalid.add_resource({"private.history", graph::ResourceKind::buffer, 1, true});
    graph::PassDesc node{};
    node.name = "opaque";
    node.recording_kind = graph::RecordingKind::opaque_group;
    node.uses.push_back({resource, graph::Access::read, graph::Stage::compute});
    invalid.add_pass(node);
    graph::CompiledGraph compiled;
    graph::Error graph_error;
    check(!invalid.compile(0, compiled, graph_error) && graph_error.code == graph::ErrorCode::invalid_usage);
    std::puts("native frame graph slot/callback/barrier ownership passed (no GPU)");
}
