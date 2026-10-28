#include "bedray/frame_scheduler.hpp"

#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <utility>
#include <vector>

using namespace bedray;
using namespace bedray::graph;

namespace {

int failures = 0;

void check(bool condition, const char* what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

constexpr std::uint32_t layout_general = static_cast<std::uint32_t>(VK_IMAGE_LAYOUT_GENERAL);
constexpr std::uint32_t layout_shader_read =
    static_cast<std::uint32_t>(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

VkImage fake_image(std::uintptr_t value) {
    return reinterpret_cast<VkImage>(value);
}

VkCommandBuffer fake_command_buffer() {
    return reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(1));
}

VkImageSubresourceRange colour_range() {
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.baseMipLevel = 0;
    range.levelCount = 1;
    range.baseArrayLayer = 0;
    range.layerCount = 1;
    return range;
}

struct RecordLog {
    std::vector<PassId> order;
    int barriers = 0;
    PassId fail_on = external_pass;
};

RecordLog* active_log = nullptr;

void VKAPI_CALL fake_barrier(VkCommandBuffer command_buffer, VkPipelineStageFlags source_stage,
    VkPipelineStageFlags destination_stage, VkDependencyFlags dependency_flags,
    std::uint32_t memory_count, const VkMemoryBarrier* memory_barriers,
    std::uint32_t buffer_count, const VkBufferMemoryBarrier* buffer_barriers,
    std::uint32_t image_count, const VkImageMemoryBarrier* image_barriers) {
    (void)command_buffer;
    (void)source_stage;
    (void)destination_stage;
    (void)dependency_flags;
    (void)memory_count;
    (void)memory_barriers;
    (void)buffer_count;
    (void)buffer_barriers;
    (void)image_count;
    (void)image_barriers;
    if (active_log != nullptr) ++active_log->barriers;
}

bool record_pass(void* context, PassId pass, VkCommandBuffer) {
    auto* log = static_cast<RecordLog*>(context);
    log->order.push_back(pass);
    return pass != log->fail_on;
}

struct Fixture {
    RenderGraph graph;
    ResourceId colour = 0;
    PassId trace = 0;
    CompiledGraph compiled;
    VulkanRecordingPlan plan;
    VkImage image = fake_image(0x100);

    Fixture() {
        colour = graph.add_resource({"output.color", ResourceKind::image, 0, true});
        PassDesc desc;
        desc.name = "scene.trace";
        desc.uses = {{colour, Access::write, Stage::compute, layout_general}};
        trace = graph.add_pass(std::move(desc));

        Error error;
        check(graph.compile(0, compiled, error), "fixture graph compiles");
        std::vector<VulkanImageBinding> images = {
            {colour, image, VK_IMAGE_LAYOUT_GENERAL, colour_range()}};
        RecordingError recording = RecordingError::none;
        check(prepare_vulkan_recording(graph, compiled, 0, images, plan, recording),
              "fixture recording plan prepares");
    }
};

bool bind(FrameScheduler& scheduler, Fixture& fixture, FrameError& error) {
    return scheduler.bind_schedule(fixture.graph, fixture.compiled, 0, error);
}

void rejects_unbound_and_stale_schedules() {
    FrameScheduler unbound(2);
    FrameTicket ticket;
    FrameError error;
    check(!unbound.begin_frame(1, ticket, error) &&
              error.code == FrameErrorCode::schedule_unbound,
          "unbound schedule rejected");

    Fixture fixture;
    FrameScheduler scheduler(2);
    check(!scheduler.bind_schedule(fixture.graph, fixture.compiled, 1, error) &&
              error.code == FrameErrorCode::stale_schedule,
          "binding another capability mask is stale");

    CompiledGraph foreign;
    check(!scheduler.bind_schedule(fixture.graph, foreign, 0, error) &&
              error.code == FrameErrorCode::stale_schedule,
          "foreign compiled snapshot is stale");

    check(bind(scheduler, fixture, error), "valid schedule binds");
    check(scheduler.schedule_bound(), "schedule reports bound");

    PassDesc later;
    later.name = "later";
    (void)fixture.graph.add_pass(std::move(later));
    check(!scheduler.begin_frame(1, ticket, error) &&
              error.code == FrameErrorCode::stale_schedule,
          "graph mutation invalidates the bound schedule");
}

void leases_and_releases_transient_slots() {
    Fixture fixture;
    FrameError error;
    std::vector<std::uint64_t> released;
    FrameScheduler tracked(2, [&released](const FrameTicket& ticket) {
        released.push_back(ticket.recording);
    });
    check(bind(tracked, fixture, error), "tracked schedule binds");

    FrameTicket first;
    FrameTicket second;
    FrameTicket third;
    check(tracked.begin_frame(1, first, error), "first frame begins");
    check(tracked.begin_frame(2, second, error), "second frame begins");
    check(first.slot != second.slot, "frames lease distinct slots");
    check(!tracked.begin_frame(3, third, error) &&
              error.code == FrameErrorCode::slot_unavailable,
          "all leased slots refuse another frame");
    check(tracked.outstanding_frames() == 2, "two frames are outstanding");

    check(tracked.finish_frame(first, FrameSubmitState::discarded, 0, error),
          "first frame is discarded");
    check(released.size() == 1 && released[0] == first.recording,
          "discard releases the recording immediately");
    check(tracked.begin_frame(3, third, error), "discarded slot is reused");
    check(third.slot == first.slot && third.slot_generation != first.slot_generation,
          "reused slot has a new generation");
    check(!tracked.finish_frame(first, FrameSubmitState::discarded, 0, error) &&
              error.code == FrameErrorCode::invalid_ticket,
          "stale ticket is rejected");

    check(tracked.finish_frame(second, FrameSubmitState::discarded, 0, error),
          "second frame is discarded");
    check(tracked.finish_frame(third, FrameSubmitState::discarded, 0, error),
          "third frame is discarded");
    check(tracked.outstanding_frames() == 0, "all slots are free");
    check(released.size() == 3, "each recording is released exactly once");
}

void validates_frame_indices_and_tickets() {
    Fixture fixture;
    FrameScheduler scheduler(1);
    FrameError error;
    check(bind(scheduler, fixture, error), "schedule binds");

    FrameTicket ticket;
    check(!scheduler.begin_frame(0, ticket, error) &&
              error.code == FrameErrorCode::frame_index_invalid,
          "zero frame index is rejected");
    check(scheduler.begin_frame(5, ticket, error), "frame 5 begins");
    check(scheduler.finish_frame(ticket, FrameSubmitState::discarded, 0, error),
          "frame 5 discards");

    FrameTicket later;
    check(!scheduler.begin_frame(5, later, error) &&
              error.code == FrameErrorCode::frame_index_invalid,
          "repeated frame index is rejected");
    check(!scheduler.begin_frame(4, later, error) &&
              error.code == FrameErrorCode::frame_index_invalid,
          "regressing frame index is rejected");
    check(scheduler.begin_frame(6, later, error), "frame 6 begins after discard");

    FrameTicket forged = later;
    forged.slot_generation = 0;
    check(!scheduler.finish_frame(forged, FrameSubmitState::discarded, 0, error) &&
              error.code == FrameErrorCode::invalid_ticket,
          "zero generation ticket is rejected");
    forged = later;
    forged.recording = later.recording + 1;
    check(!scheduler.finish_frame(forged, FrameSubmitState::discarded, 0, error) &&
              error.code == FrameErrorCode::invalid_ticket,
          "mismatched recording id is rejected");

    check(scheduler.finish_frame(later, FrameSubmitState::discarded, 7, error) == false &&
              error.code == FrameErrorCode::serial_invalid,
          "discard requires a zero serial");
    check(!scheduler.finish_frame(later, static_cast<FrameSubmitState>(99), 0, error) &&
              error.code == FrameErrorCode::invalid_argument,
          "unknown submit state is rejected");
    check(scheduler.finish_frame(later, FrameSubmitState::discarded, 0, error),
          "valid discard still succeeds");
}

void records_and_waits_for_completion() {
    Fixture fixture;
    std::vector<std::uint64_t> released;
    FrameScheduler scheduler(2, [&released](const FrameTicket& ticket) {
        released.push_back(ticket.recording);
    });
    FrameError error;
    check(bind(scheduler, fixture, error), "schedule binds");

    FrameTicket ticket;
    check(scheduler.begin_frame(1, ticket, error), "frame begins");
    RecordLog log;
    active_log = &log;
    check(scheduler.record_frame(ticket, fixture.plan, fake_command_buffer(), fake_barrier,
                                 record_pass, &log, error),
          "frame records");
    check(log.order.size() == 1 && log.order[0] == fixture.trace, "pass callback ran");
    check(log.barriers >= 1, "recording adapter emitted a barrier");

    check(!scheduler.record_frame(ticket, fixture.plan, fake_command_buffer(), fake_barrier,
                                  record_pass, &log, error) &&
              error.code == FrameErrorCode::recording_already_done,
          "a ticket records once");

    check(!scheduler.finish_frame(ticket, FrameSubmitState::submitted, 0, error) &&
              error.code == FrameErrorCode::serial_invalid,
          "submission requires a nonzero serial");
    check(scheduler.finish_frame(ticket, FrameSubmitState::submitted, 10, error),
          "frame is submitted with serial 10");
    check(released.empty(), "nothing is released before completion");
    check(scheduler.outstanding_frames() == 1, "submitted frame stays leased");

    check(scheduler.complete(9, error), "completion high-water advances");
    check(released.empty(), "an earlier completion does not release the frame");
    check(scheduler.complete(10, error), "completion releases the frame");
    check(released.size() == 1 && released[0] == ticket.recording,
          "release callback runs once for the completed recording");
    check(scheduler.outstanding_frames() == 0, "completed frame frees its slot");
    check(!scheduler.complete(9, error) &&
              error.code == FrameErrorCode::completion_regressed,
          "regressing completion is rejected");

    FrameTicket next;
    check(scheduler.begin_frame(2, next, error), "next frame begins");
    check(!scheduler.finish_frame(next, FrameSubmitState::submitted, 9, error) &&
              error.code == FrameErrorCode::serial_invalid,
          "serial below the completion high-water is rejected");
    check(scheduler.finish_frame(next, FrameSubmitState::submitted, 11, error),
          "next submission uses a higher serial");
    active_log = nullptr;
}

void failed_recording_requires_discard() {
    Fixture fixture;
    std::vector<std::uint64_t> released;
    FrameScheduler scheduler(1, [&released](const FrameTicket& ticket) {
        released.push_back(ticket.recording);
    });
    FrameError error;
    check(bind(scheduler, fixture, error), "schedule binds");

    FrameTicket ticket;
    check(scheduler.begin_frame(1, ticket, error), "frame begins");
    RecordLog log;
    log.fail_on = fixture.trace;
    check(!scheduler.record_frame(ticket, fixture.plan, fake_command_buffer(), fake_barrier,
                                  record_pass, &log, error) &&
              error.code == FrameErrorCode::recording_failed &&
              error.recording == RecordingError::callback_failed,
          "failed pass fails the recording");
    check(scheduler.outstanding_frames() == 1, "failed ticket remains open");
    check(!scheduler.record_frame(ticket, fixture.plan, fake_command_buffer(), fake_barrier,
                                  record_pass, &log, error) &&
              error.code == FrameErrorCode::recording_already_done,
          "a failed recording cannot be retried");
    check(scheduler.finish_frame(ticket, FrameSubmitState::discarded, 0, error),
          "failed recording is discarded");
    check(released.size() == 1 && released[0] == ticket.recording,
          "discarded core recording is released");
}

void stale_plan_is_rejected_before_recording() {
    Fixture fixture;
    FrameScheduler scheduler(1);
    FrameError error;
    check(bind(scheduler, fixture, error), "schedule binds");

    FrameTicket ticket;
    check(scheduler.begin_frame(1, ticket, error), "frame begins");
    RecordLog log;

    VulkanRecordingPlan plan = fixture.plan;
    plan.enabled_capabilities = 3;
    check(!scheduler.record_frame(ticket, plan, fake_command_buffer(), fake_barrier,
                                  record_pass, &log, error) &&
              error.code == FrameErrorCode::stale_schedule,
          "plan for other capabilities is stale");
    check(log.order.empty() && log.barriers == 0, "stale plan records nothing");

    plan = fixture.plan;
    plan.owner = nullptr;
    check(!scheduler.record_frame(ticket, plan, fake_command_buffer(), fake_barrier,
                                  record_pass, &log, error) &&
              error.code == FrameErrorCode::stale_schedule,
          "plan without an owner is stale");

    Fixture foreign;
    check(!scheduler.record_frame(ticket, foreign.plan, fake_command_buffer(), fake_barrier,
                                  record_pass, &log, error) &&
              error.code == FrameErrorCode::stale_schedule,
          "plan from another graph is stale");
    check(log.order.empty() && log.barriers == 0, "no stale attempt recorded commands");

    check(scheduler.record_frame(ticket, fixture.plan, fake_command_buffer(), fake_barrier,
                                 record_pass, &log, error),
          "valid plan records after stale attempts");
    check(scheduler.finish_frame(ticket, FrameSubmitState::discarded, 0, error),
          "frame discards");
}

void gates_history_reads_on_producer_completion() {
    Fixture fixture;
    FrameScheduler scheduler(1);
    FrameError error;
    check(bind(scheduler, fixture, error), "schedule binds");

    HistoryRecording recording{};
    recording.read_slot = 0;
    recording.read_image = 0x200;
    recording.read_producer_queue = 0;
    recording.read_producer_serial = 4;

    QueueTimeline timeline{};
    timeline.device_generation = 1;
    timeline.completed[0] = 3;

    FrameTicket ticket;
    check(scheduler.begin_frame(1, ticket, error), "frame begins");
    RecordLog log;
    FrameHistoryReads reads{&recording, 1, &timeline, 1};
    check(!scheduler.record_frame(ticket, fixture.plan, reads, fake_command_buffer(),
                                  fake_barrier, record_pass, &log, error) &&
              error.code == FrameErrorCode::history_read_incomplete &&
              error.detail == 4,
          "unfinished history producer blocks recording");
    check(log.order.empty() && log.barriers == 0, "blocked history records nothing");

    FrameHistoryReads missing{nullptr, 1, &timeline, 1};
    check(!scheduler.record_frame(ticket, fixture.plan, missing, fake_command_buffer(),
                                  fake_barrier, record_pass, &log, error) &&
              error.code == FrameErrorCode::invalid_argument,
          "missing history array is rejected");

    timeline.completed[0] = 4;
    check(scheduler.record_frame(ticket, fixture.plan, reads, fake_command_buffer(),
                                 fake_barrier, record_pass, &log, error),
          "completed producer allows recording");
    check(log.order.size() == 1, "history-gated recording ran the pass");
    check(scheduler.finish_frame(ticket, FrameSubmitState::discarded, 0, error),
          "history-gated frame discards");

    FrameTicket reset_ticket;
    check(scheduler.begin_frame(2, reset_ticket, error), "reset frame begins");
    HistoryRecording reset{};
    reset.read_slot = -1;
    FrameHistoryReads reset_reads{&reset, 1, &timeline, 1};
    check(scheduler.record_frame(reset_ticket, fixture.plan, reset_reads, fake_command_buffer(),
                                 fake_barrier, record_pass, &log, error),
          "reset recording needs no producer completion");
    check(scheduler.finish_frame(reset_ticket, FrameSubmitState::discarded, 0, error),
          "reset frame discards");

    FrameTicket lost_ticket;
    check(scheduler.begin_frame(3, lost_ticket, error), "lost-device frame begins");
    QueueTimeline inactive{};
    FrameHistoryReads inactive_reads{&reset, 1, &inactive, 1};
    check(!scheduler.record_frame(lost_ticket, fixture.plan, inactive_reads,
                                  fake_command_buffer(), fake_barrier, record_pass, &log, error) &&
              error.code == FrameErrorCode::history_read_incomplete,
          "inactive history timeline blocks recording");
    check(scheduler.finish_frame(lost_ticket, FrameSubmitState::discarded, 0, error),
          "inactive-timeline frame discards");
}

void retains_unknown_frames_until_idle_or_loss() {
    Fixture fixture;
    std::vector<std::uint64_t> released;
    FrameScheduler scheduler(1, [&released](const FrameTicket& ticket) {
        released.push_back(ticket.recording);
    });
    FrameError error;
    check(bind(scheduler, fixture, error), "schedule binds");

    FrameTicket unknown;
    check(scheduler.begin_frame(1, unknown, error), "unknown frame begins");
    check(scheduler.finish_frame(unknown, FrameSubmitState::unknown, 20, error),
          "unknown submission is recorded");
    check(scheduler.complete(20, error), "completion high-water advances past unknown");
    check(scheduler.outstanding_frames() == 1 && released.empty(),
          "completion never releases an unknown submission");
    check(scheduler.device_idle(error), "idle device releases unknown work");
    check(scheduler.outstanding_frames() == 0 && released.size() == 1,
          "idle device releases the unknown recording");

    FrameTicket submitted;
    check(scheduler.begin_frame(2, submitted, error), "submitted frame begins");
    check(scheduler.finish_frame(submitted, FrameSubmitState::submitted, 21, error),
          "frame is submitted");
    check(scheduler.device_lost(error), "device loss releases in-flight work");
    check(scheduler.outstanding_frames() == 0 && released.size() == 2,
          "device loss releases the in-flight recording");

    FrameTicket open;
    check(scheduler.begin_frame(3, open, error), "open frame begins");
    check(scheduler.device_idle(error), "idle device leaves an open recording alone");
    check(scheduler.outstanding_frames() == 1, "open recording is still leased");
    check(scheduler.finish_frame(open, FrameSubmitState::discarded, 0, error),
          "open recording is discarded afterwards");
}

void refuses_rebinding_with_outstanding_frames() {
    Fixture fixture;
    FrameScheduler scheduler(1);
    FrameError error;
    check(bind(scheduler, fixture, error), "schedule binds");
    FrameTicket ticket;
    check(scheduler.begin_frame(1, ticket, error), "frame begins");
    check(!bind(scheduler, fixture, error) && error.code == FrameErrorCode::schedule_busy,
          "rebinding while a frame is open is rejected");
    check(scheduler.finish_frame(ticket, FrameSubmitState::discarded, 0, error),
          "frame discards");
    check(bind(scheduler, fixture, error), "rebinding after discard succeeds");
}

void reports_release_callback_failure_without_leaking() {
    Fixture fixture;
    int calls = 0;
    bool fail_release = true;
    FrameScheduler scheduler(1, [&calls, &fail_release](const FrameTicket&) {
        ++calls;
        if (fail_release) throw std::runtime_error("release failure");
    });
    FrameError error;
    check(bind(scheduler, fixture, error), "schedule binds");
    FrameTicket ticket, next;
    check(scheduler.begin_frame(1, ticket, error), "frame begins");
    check(!scheduler.finish_frame(ticket, FrameSubmitState::discarded, 0, error)
        && error.code == FrameErrorCode::release_failed, "release failure is reported");
    check(calls == 1 && scheduler.outstanding_frames() == 1, "failed release retains the lease");
    check(!scheduler.begin_frame(2, next, error) && error.code == FrameErrorCode::slot_unavailable,
        "failed release cannot recycle its slot");
    fail_release = false;
    check(scheduler.release_proven(ticket, error), "host proof retries retained release");
    check(!scheduler.release_proven(ticket, error) && error.code == FrameErrorCode::invalid_ticket,
        "a stale exact-ticket proof cannot release another generation");
    check(calls == 2 && scheduler.outstanding_frames() == 0, "successful retry frees the lease");
    check(scheduler.begin_frame(2, next, error), "next frame begins");
    check(scheduler.finish_frame(next, FrameSubmitState::submitted, 5, error), "next frame submits");
    fail_release = true;
    check(!scheduler.complete(5, error) && error.code == FrameErrorCode::release_failed,
        "completion reports release failure");
    check(calls == 3 && scheduler.outstanding_frames() == 1, "completion failure retains its lease");
    fail_release = false;
    check(scheduler.complete(5, error), "same completion proof retries release");
    check(calls == 4 && scheduler.outstanding_frames() == 0, "completion retry frees the lease");
}

void clamps_slot_count() {
    FrameScheduler tiny(0);
    check(tiny.slot_count() == 1, "zero slots clamp to one");
    FrameScheduler huge(1000);
    check(huge.slot_count() == max_frame_slots, "slot count clamps to the maximum");
}

} // namespace

int main() {
    rejects_unbound_and_stale_schedules();
    leases_and_releases_transient_slots();
    validates_frame_indices_and_tickets();
    records_and_waits_for_completion();
    failed_recording_requires_discard();
    stale_plan_is_rejected_before_recording();
    gates_history_reads_on_producer_completion();
    retains_unknown_frames_until_idle_or_loss();
    refuses_rebinding_with_outstanding_frames();
    reports_release_callback_failure_without_leaking();
    clamps_slot_count();

    if (failures != 0) {
        std::fprintf(stderr, "%d frame scheduler checks failed\n", failures);
        return 1;
    }
    std::printf("frame scheduler contract: PASS\n");
    return 0;
}
