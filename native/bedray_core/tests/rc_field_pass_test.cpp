#include "../src/rc_field_pass.h"
#include "fake_pass_device.h"
#include <algorithm>
#include <array>
#include <cstdio>
#include <cstdlib>
#include <limits>

struct BedrayCore {};
namespace {
constexpr std::array<std::uint32_t,17> bindings{0,2,3,9,10,16,17,18,20,21,22,23,24,25,34,35,38};
bool wrong_push = false, wrong_count = false, unknown_program = false;
std::uint32_t missing_binding = std::numeric_limits<std::uint32_t>::max();
std::vector<std::size_t> barrier_after_calls;
std::uint32_t descriptor_type(std::uint32_t binding) {
    if (binding == 0) return VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
    if (binding == 3 || binding == 9 || binding == 10) return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    if (binding == 16 || binding == 34) return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
}
}
namespace bedray::core {
bool program_contract(BedrayCore*, std::uint64_t handle, ProgramContract& out) noexcept {
    out = {wrong_push ? 79u : 80u, wrong_count ? 16u : 17u};
    return !unknown_program && handle == 1;
}
bool program_has_binding(BedrayCore*, std::uint64_t handle, std::uint32_t binding, std::uint32_t type) noexcept {
    return handle == 1 && binding != missing_binding && type == descriptor_type(binding)
        && std::find(bindings.begin(),bindings.end(),binding) != bindings.end();
}
}
namespace {
int failures = 0;
#define CHECK(expression) do { if (!(expression)) { std::fprintf(stderr,"FAIL %d: %s\n",__LINE__,#expression); ++failures; } } while (0)
template<class T> T get(const std::vector<unsigned char>& bytes, std::size_t offset) {
    T value{};
    if (offset + sizeof(value) > bytes.size()) { CHECK(false); return value; }
    std::memcpy(&value,bytes.data()+offset,sizeof(value)); return value;
}
VKAPI_ATTR void VKAPI_CALL barrier(VkCommandBuffer command,VkPipelineStageFlags src,VkPipelineStageFlags dst,
    VkDependencyFlags flags,std::uint32_t count,const VkMemoryBarrier* memory,
    std::uint32_t buffer_count,const VkBufferMemoryBarrier*,std::uint32_t image_count,const VkImageMemoryBarrier*) {
    CHECK(command != VK_NULL_HANDLE && src == VK_PIPELINE_STAGE_ALL_COMMANDS_BIT && dst == src);
    CHECK(flags == 0 && count == 1 && buffer_count == 0 && image_count == 0);
    CHECK(memory && memory->srcAccessMask == VK_ACCESS_MEMORY_WRITE_BIT);
    CHECK(memory && memory->dstAccessMask == (VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT));
    barrier_after_calls.push_back(fake::render_calls.size()); ++fake::barriers;
}
DeviceFns device() { auto fns=make_device_fns(); fns.cmd_pipeline_barrier=barrier; return fns; }
void reset() { fake::reset(); barrier_after_calls.clear(); wrong_push=wrong_count=unknown_program=false; missing_binding=UINT32_MAX; }
BedrayRCDesc configuration(std::uint32_t axis=8) { return {BEDRAY_CORE_ABI_VERSION,sizeof(BedrayRCDesc),1,axis,0,0}; }
std::array<BedrayResource,14> inputs() {
    std::array<BedrayResource,14> result{};
    for (std::size_t i=0;i<result.size();++i) {
        auto& r=result[i]; r.binding=bindings[i]; r.descriptor_type=descriptor_type(r.binding);
        r.handle=0x9000u+r.binding;
        if (r.descriptor_type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
            r.sampler=0xa000u+r.binding; r.image_layout=VK_IMAGE_LAYOUT_GENERAL;
        } else if (r.descriptor_type != VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR) {
            r.offset=16u*r.binding; r.range=1024u+16u*r.binding;
        }
    }
    return result;
}
BedrayPathTraceFrameDesc frame(std::uint64_t command=11,std::uint32_t index=7) {
    BedrayPathTraceFrameDesc f{}; f.abi_version=BEDRAY_CORE_ABI_VERSION;
    f.command_buffer=command; f.frame_index=index; f.flags=BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE;
    f.path.material_count=13; f.path.ray_bias=0.01f; f.path.alpha_cutoff=0.4f; f.path.max_radiance=31;
    f.path.sun_probability=0.2f; f.path.emitter_count=17; f.path.emitter_probability=0.3f;
    for (std::uint32_t i=0;i<4;++i) { f.path.emitter_camera_relative[i]=static_cast<float>(i)+0.25f; f.path.entity_state[i]=20u+i; }
    return f;
}
const BedrayResource& resource(const fake::RenderCall& call,std::uint32_t binding) {
    for (const auto& r:call.resources) if (r.binding == binding) return r;
    CHECK(false); static const BedrayResource missing{}; return missing;
}
std::vector<unsigned char> bytes(std::uint64_t buffer) {
    if (!fake::buffers.contains(buffer)) { CHECK(false); return {}; }
    return fake::memories.at(fake::buffers.at(buffer).memory).bytes;
}
void clean() {
    CHECK(fake::buffers.empty() && fake::memories.empty()); CHECK(fake::images.empty() && fake::views.empty());
    CHECK(fake::faults == 0);
}
void metadata_and_configuration() {
    const std::array<std::uint32_t,3> axes{8,16,32}, totals{69168,145776,653040};
    for (std::size_t n=0;n<axes.size();++n) {
        bedray::core::RCFieldMetadata meta{};
        CHECK(bedray::core::rc_field_metadata(axes[n],77,meta));
        CHECK(meta.header[0] == 2 && meta.header[1] == 5 && meta.header[2] == axes[n] && meta.header[3] == totals[n]);
        CHECK(meta.tag[0] == 77 && meta.tag[1] == 0 && meta.tag[2] == 0 && meta.tag[3] == 0);
        CHECK(meta.history[0] == 0 && meta.history[1] == 0 && meta.history[2] == 0 && meta.history[3] == 0);
        std::uint32_t offset=0;
        for (std::uint32_t c=0;c<5;++c) {
            const auto axis=std::max(1u,axes[n]>>c)+2u, side=1u<<c;
            CHECK(meta.counts[c][0] == axis && meta.counts[c][1] == side);
            CHECK(meta.counts[c][2] == axis*axis*axis && meta.counts[c][3] == offset);
            const auto first=-0.5f*static_cast<float>(axis-1u)*static_cast<float>(side);
            CHECK(meta.origins[c][0] == first && meta.origins[c][1] == first && meta.origins[c][2] == first);
            CHECK(meta.origins[c][3] == static_cast<float>(side));
            CHECK(meta.ranges[c][0] == 4.0f*side && meta.ranges[c][1] == 8.0f*side);
            CHECK(meta.ranges[c][2] == 0 && meta.ranges[c][3] == 0);
            offset+=axis*axis*axis*6u*side*side;
        }
        CHECK(offset == totals[n]);
    }
    for (auto axis:{0u,4u,9u,24u,64u}) { bedray::core::RCFieldMetadata out{}; CHECK(!bedray::core::rc_field_metadata(axis,0,out)); }
    reset(); BedrayCore core; BedrayError error{}; bedray::core::RCFieldPass pass;
    auto invalid=[&](auto mutate,BedrayStatus status=BEDRAY_ERROR_INVALID_ARGUMENT) {
        auto desc=configuration(); mutate(desc); CHECK(pass.configure(&core,device(),desc,65535,&error) == status);
        CHECK(!pass.has_recordings()); clean();
    };
    invalid([](auto& d){d.abi_version=0;},BEDRAY_ERROR_ABI_MISMATCH);
    invalid([](auto& d){--d.struct_size;},BEDRAY_ERROR_ABI_MISMATCH);
    invalid([](auto& d){d.program_build=0;}); invalid([](auto& d){d.base_axis=12;}); invalid([](auto& d){d.reserved=1;});
    CHECK(pass.configure(nullptr,device(),configuration(),65535,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    auto fns=device(); fns.cmd_pipeline_barrier=nullptr;
    CHECK(pass.configure(&core,fns,configuration(),65535,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    CHECK(pass.configure(&core,device(),configuration(),647,&error) == BEDRAY_ERROR_UNSUPPORTED);
    CHECK(pass.configure(&core,device(),configuration(),0,&error) == BEDRAY_ERROR_UNSUPPORTED);
    unknown_program=true; CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_ERROR_INVALID_ARGUMENT); unknown_program=false;
    wrong_push=true; CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_ERROR_INVALID_ARGUMENT); wrong_push=false;
    wrong_count=true; CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_ERROR_INVALID_ARGUMENT); wrong_count=false;
    for (auto binding:bindings) { missing_binding=binding; CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_ERROR_INVALID_ARGUMENT); }
    missing_binding=UINT32_MAX;
    CHECK(pass.configure(&core,device(),configuration(),648,&error) == BEDRAY_OK);
    CHECK(pass.uses_program(1) && !pass.uses_program(0) && !pass.uses_program(2));
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
void invalid_inputs_do_not_allocate_or_record() {
    reset(); BedrayCore core; BedrayError error{}; bedray::core::RCFieldPass pass;
    CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_OK);
    std::array<BedrayResource,2> outputs{};
    auto data=inputs(); auto f=frame(); f.command_buffer=0;
    CHECK(pass.record(f,data,outputs,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    f=frame(); f.flags=0; CHECK(pass.record(f,data,outputs,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    for (std::size_t i=0;i<data.size();++i) {
        auto bad=data; bad[i].handle=0;
        CHECK(pass.record(frame(),bad,outputs,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
        bad=data; bad[i].descriptor_type=UINT32_MAX;
        CHECK(pass.record(frame(),bad,outputs,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
        if (data[i].descriptor_type == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER || data[i].descriptor_type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
            bad=data; bad[i].range=0; CHECK(pass.record(frame(),bad,outputs,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
            bad=data; bad[i].offset=UINT64_MAX; CHECK(pass.record(frame(),bad,outputs,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
        }
    }
    CHECK(pass.record(frame(),std::span<const BedrayResource>(data).first(13),outputs,&error) == BEDRAY_ERROR_INVALID_ARGUMENT);
    CHECK(fake::render_calls.empty() && barrier_after_calls.empty() && !pass.has_recordings());
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
void dispatch_order_payloads_and_immutable_ranges() {
    reset(); BedrayCore core; BedrayError error{}; bedray::core::RCFieldPass pass;
    CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_OK);
    auto data=inputs(); auto f=frame(); std::array<BedrayResource,2> first{},second{};
    CHECK(pass.record(f,data,first,&error) == BEDRAY_OK);
    CHECK(fake::render_calls.size() == 5 && fake::barriers == 6);
    CHECK(barrier_after_calls == std::vector<std::size_t>({0,1,2,3,4,5}));
    CHECK(first[0].binding == 34 && first[0].descriptor_type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER && first[0].range == 288);
    CHECK(first[1].binding == 35 && first[1].descriptor_type == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER && first[1].range == 69168ull*32u);
    const auto upload=bytes(first[0].handle);
    CHECK(get<std::uint32_t>(upload,0) == 2 && get<std::uint32_t>(upload,12) == 69168);
    CHECK(get<std::uint32_t>(upload,256) == 7);
    bedray::core::RCFieldMetadata metadata{}; CHECK(bedray::core::rc_field_metadata(8,7,metadata));
    for (std::size_t index=0;index<5;++index) {
        const auto& call=fake::render_calls[index]; const auto level=4u-static_cast<std::uint32_t>(index);
        const auto count=metadata.counts[level][2]*6u*metadata.counts[level][1]*metadata.counts[level][1];
        CHECK(call.command_buffer == 11 && call.program == 1 && call.group_y == 1 && call.group_z == 1);
        CHECK(call.group_x == (count+63u)/64u && call.push_bytes == 80 && call.resources.size() == 17);
        CHECK(get<std::uint32_t>(call.push,0) == level && get<std::uint32_t>(call.push,4) == count);
        CHECK(get<std::uint32_t>(call.push,8) == 7 && get<std::uint32_t>(call.push,12) == 13);
        CHECK(get<float>(call.push,16) == 0.01f && get<float>(call.push,20) == 0.4f && get<float>(call.push,24) == 31);
        CHECK(get<float>(call.push,28) == 0.2f && get<std::uint32_t>(call.push,32) == 17);
        CHECK(get<std::uint32_t>(call.push,36) == 2 && get<float>(call.push,40) == 0.3f && get<std::uint32_t>(call.push,44) == 0);
        for (std::uint32_t lane=0;lane<4;++lane) {
            CHECK(get<float>(call.push,48+4u*lane) == f.path.emitter_camera_relative[lane]);
            CHECK(get<std::uint32_t>(call.push,64+4u*lane) == f.path.entity_state[lane]);
        }
        for (const auto& input:data) {
            const auto& copied=resource(call,input.binding);
            CHECK(copied.handle == input.handle && copied.offset == input.offset && copied.range == input.range);
            CHECK(copied.sampler == input.sampler && copied.image_layout == input.image_layout);
        }
        CHECK(resource(call,34).handle == first[0].handle && resource(call,35).handle == first[1].handle);
    }
    CHECK(pass.release_recording(999) == BEDRAY_OK && fake::buffers.size() == 2);
    CHECK(pass.record(f,data,second,&error) == BEDRAY_ERROR_IN_USE);
    CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE);
    CHECK(pass.configure(&core,device(),configuration(16),65535,&error) == BEDRAY_ERROR_IN_USE);
    CHECK(pass.record(frame(12,8),data,second,&error) == BEDRAY_OK && fake::buffers.size() == 4);
    CHECK(first[0].handle != second[0].handle && first[1].handle != second[1].handle);
    CHECK(bytes(first[0].handle) == upload && get<std::uint32_t>(bytes(second[0].handle),256) == 8);
    // Frame 8 follows frame 7, so recording 12 read recording 11's tails as history and pins them past 11's proof.
    CHECK(pass.release_recording(11) == BEDRAY_OK && fake::buffers.size() == 3);
    CHECK(fake::buffers.contains(second[0].handle) && fake::buffers.contains(second[1].handle));
    CHECK(fake::buffers.contains(first[1].handle));
    CHECK(pass.release_recording(12) == BEDRAY_OK && pass.destroy() == BEDRAY_OK); clean();
}
float history_lane(std::uint64_t meta, std::size_t lane) { return get<float>(bytes(meta),272+4u*lane); }
// Batch 5.6: the tails of the frame just before are the next recording's history at 38, with the host's camera move and
// the owner's weight in the metadata; a reset, a skipped frame or a failed recording starts the field over.
void history_chains_consecutive_frames_only() {
    reset(); BedrayCore core; BedrayError error{}; bedray::core::RCFieldPass pass;
    CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_OK);
    const auto data=inputs(); std::array<BedrayResource,2> a{},b{},c{},d{},x{};
    auto f=frame(21,40);
    CHECK(pass.record(f,data,a,&error) == BEDRAY_OK);
    CHECK(history_lane(a[0].handle,3) == 0.0f && resource(fake::render_calls.back(),38).handle == a[1].handle);
    f=frame(22,41); f.path.di_camera_delta[0]=0.25f; f.path.di_camera_delta[1]=-1.5f; f.path.di_camera_delta[2]=2.0f;
    const auto before=fake::render_calls.size();
    CHECK(pass.record(f,data,b,&error) == BEDRAY_OK);
    CHECK(history_lane(b[0].handle,0) == 0.25f && history_lane(b[0].handle,1) == -1.5f && history_lane(b[0].handle,2) == 2.0f);
    CHECK(history_lane(b[0].handle,3) == bedray::core::kRCFieldHistoryWeight);
    for (std::size_t i=before;i<fake::render_calls.size();++i) {
        const auto& call=fake::render_calls[i];
        CHECK(resource(call,38).handle == a[1].handle && resource(call,38).range == a[1].range);
        CHECK(resource(call,35).handle == b[1].handle);
    }
    f=frame(23,42); f.flags|=BEDRAY_PATH_TRACE_FRAME_RESET_HISTORY;
    CHECK(pass.record(f,data,c,&error) == BEDRAY_OK);
    CHECK(history_lane(c[0].handle,3) == 0.0f && resource(fake::render_calls.back(),38).handle == c[1].handle);
    CHECK(pass.record(frame(24,44),data,d,&error) == BEDRAY_OK);
    CHECK(history_lane(d[0].handle,3) == 0.0f);   // frame 43 was never recorded
    fake::fail_render_at(2,BEDRAY_ERROR_VULKAN);
    CHECK(pass.record(frame(25,45),data,x,&error) == BEDRAY_ERROR_VULKAN);
    CHECK(pass.record(frame(26,46),data,x,&error) == BEDRAY_OK);
    CHECK(history_lane(x[0].handle,3) == 0.0f);   // the failed field is nobody's history
    for (std::uint64_t command=21;command<=26;++command) CHECK(pass.release_recording(command) == BEDRAY_OK);
    CHECK(fake::buffers.size() == 1 && fake::buffers.contains(x[1].handle));   // the last field waits as history
    CHECK(pass.destroy() == BEDRAY_OK); clean();
}
void slots_and_partial_failures_require_exact_proof() {
    reset(); BedrayCore core; BedrayError error{}; bedray::core::RCFieldPass pass;
    CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_OK);
    const auto data=inputs(); std::array<BedrayResource,2> out{};
    for (std::uint64_t command=1;command<=8;++command) CHECK(pass.record(frame(command),data,out,&error) == BEDRAY_OK);
    CHECK(fake::buffers.size() == 16);
    CHECK(pass.record(frame(9),data,out,&error) == BEDRAY_ERROR_IN_USE);
    CHECK(pass.release_recording(99) == BEDRAY_OK && fake::buffers.size() == 16);
    CHECK(pass.release_recording(3) == BEDRAY_OK && fake::buffers.size() == 14);
    CHECK(pass.record(frame(9),data,out,&error) == BEDRAY_OK && fake::buffers.size() == 16);
    for (std::uint64_t command=1;command<=9;++command) CHECK(pass.release_recording(command) == BEDRAY_OK);
    CHECK(pass.destroy() == BEDRAY_OK); clean();
    for (int allocation=0;allocation<2;++allocation) {
        reset(); CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_OK);
        fake::fail_allocate_after=allocation;
        CHECK(pass.record(frame(11),data,out,&error) == BEDRAY_ERROR_OUT_OF_MEMORY);
        CHECK(pass.has_recordings() && fake::render_calls.empty() && fake::barriers == 0);
        CHECK(fake::buffers.size() == static_cast<std::size_t>(allocation));
        CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE && pass.release_recording(999) == BEDRAY_OK);
        CHECK(pass.record(frame(11),data,out,&error) == BEDRAY_ERROR_IN_USE);
        CHECK(pass.release_recording(11) == BEDRAY_OK && pass.destroy() == BEDRAY_OK); clean();
    }
    for (int dispatch=0;dispatch<5;++dispatch) {
        reset(); CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_OK);
        fake::fail_render_at(dispatch,BEDRAY_ERROR_VULKAN);
        CHECK(pass.record(frame(11),data,out,&error) == BEDRAY_ERROR_VULKAN);
        CHECK(fake::render_calls.size() == static_cast<std::size_t>(dispatch+1) && fake::barriers == static_cast<std::uint32_t>(dispatch+1));
        CHECK(pass.has_recordings() && fake::buffers.size() == 2 && pass.destroy() == BEDRAY_ERROR_IN_USE);
        CHECK(pass.release_recording(999) == BEDRAY_OK && fake::buffers.size() == 2);
        CHECK(pass.release_recording(11) == BEDRAY_OK && pass.destroy() == BEDRAY_OK); clean();
    }
    reset(); auto small=configuration(); small.buffer_budget_bytes=1024;
    CHECK(pass.configure(&core,device(),small,65535,&error) == BEDRAY_OK);
    CHECK(pass.record(frame(11),data,out,&error) == BEDRAY_ERROR_OUT_OF_MEMORY && fake::buffers.size() == 1);
    CHECK(pass.release_recording(11) == BEDRAY_OK && pass.destroy() == BEDRAY_OK); clean();
}
void device_loss_pins_all_recordings_until_every_proof() {
    for (bool allocation_loss:{false,true}) {
        reset(); BedrayCore core; BedrayError error{}; bedray::core::RCFieldPass pass;
        CHECK(pass.configure(&core,device(),configuration(),65535,&error) == BEDRAY_OK);
        const auto data=inputs(); std::array<BedrayResource,2> out{};
        CHECK(pass.record(frame(11),data,out,&error) == BEDRAY_OK);
        if (allocation_loss) { fake::fail_allocate_after=1; fake::fail_result=VK_ERROR_DEVICE_LOST; }
        else fake::fail_render_at(2,BEDRAY_ERROR_DEVICE_LOST);
        CHECK(pass.record(frame(12),data,out,&error) == BEDRAY_ERROR_DEVICE_LOST);
        const auto retained=fake::buffers.size(); CHECK(retained == (allocation_loss ? 3u : 4u));
        CHECK(pass.destroy() == BEDRAY_ERROR_IN_USE && pass.record(frame(13),data,out,&error) == BEDRAY_ERROR_UNSUPPORTED);
        CHECK(pass.release_recording(99) == BEDRAY_OK && fake::buffers.size() == retained);
        CHECK(pass.release_recording(11) == BEDRAY_OK && fake::buffers.size() == retained && pass.has_recordings());
        CHECK(pass.release_recording(11) == BEDRAY_OK && fake::buffers.size() == retained);
        CHECK(pass.release_recording(12) == BEDRAY_OK && !pass.has_recordings());
        CHECK(pass.destroy() == BEDRAY_OK); clean();
    }
}
}
int main() {
    metadata_and_configuration(); invalid_inputs_do_not_allocate_or_record();
    dispatch_order_payloads_and_immutable_ranges(); history_chains_consecutive_frames_only();
    slots_and_partial_failures_require_exact_proof();
    device_loss_pins_all_recordings_until_every_proof();
    if (failures) return EXIT_FAILURE;
    std::puts("RC v2 native field metadata/contracts/dispatch/pinning/failure checks passed (no GPU)");
}
