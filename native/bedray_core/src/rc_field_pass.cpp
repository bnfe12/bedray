#include "rc_field_pass.h"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <limits>
#include <type_traits>

namespace bedray::core {
namespace {
BedrayStatus fail(BedrayError* e, BedrayStatus status, const char* message) noexcept {
    if (e) { e->status = status; std::snprintf(e->message,sizeof(e->message),"%s",message); }
    return status;
}
template<class T> std::uint64_t bits(T value) noexcept {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<std::uintptr_t>(value);
    else return static_cast<std::uint64_t>(value);
}
constexpr std::array<std::uint32_t,14> scene_bindings{0,2,3,9,10,16,17,18,20,21,22,23,24,25};
std::uint32_t type(std::uint32_t b) noexcept {
    if (b == 0) return VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
    if (b == 3 || b == 9 || b == 10) return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    if (b == 16 || b == 34) return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
}
struct BuildPush {
    std::uint32_t level, count, frame, materials;
    float ray_bias, alpha_cutoff, max_radiance, sun_probability;
    std::uint32_t emitters, max_depth; float emitter_probability; std::uint32_t reserved;
    float emitter_camera[4]; std::uint32_t entities[4];
};
static_assert(sizeof(BuildPush) == 80);
static_assert(offsetof(BuildPush, emitter_camera) == 48);
static_assert(offsetof(BuildPush, entities) == 64);
void barrier(const DeviceFns& f, VkCommandBuffer cmd) {
    VkMemoryBarrier m{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    m.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    m.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    f.cmd_pipeline_barrier(cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0,1,&m,0,nullptr,0,nullptr);
}
BedrayResource resource(std::uint32_t binding, const gpu::BufferView& view) noexcept {
    BedrayResource r{}; r.binding = binding; r.descriptor_type = type(binding);
    r.handle = bits(view.buffer); r.range = view.size; return r;
}
}
bool rc_field_metadata(std::uint32_t n, std::uint32_t frame, RCFieldMetadata& out) noexcept {
    if (n < 8 || n > 32 || (n & (n-1u))) return false;
    RCFieldMetadata f{}; f.header[0] = 2; f.header[1] = 5; f.header[2] = n; f.tag[0] = frame;
    std::uint64_t offset = 0;
    for (std::uint32_t c=0;c<5;++c) {
        const auto axis = std::max(1u,n>>c)+2u, side=1u<<c;
        const std::uint64_t probes = static_cast<std::uint64_t>(axis)*axis*axis;
        const auto count = probes*6u*side*side;
        if (offset+count > std::numeric_limits<std::uint32_t>::max()) return false;
        f.counts[c][0]=axis; f.counts[c][1]=side; f.counts[c][2]=static_cast<std::uint32_t>(probes);
        f.counts[c][3]=static_cast<std::uint32_t>(offset);
        const auto spacing=static_cast<float>(side), first=-0.5f*static_cast<float>(axis-1u)*spacing;
        f.origins[c][0]=f.origins[c][1]=f.origins[c][2]=first; f.origins[c][3]=spacing;
        f.ranges[c][0]=4*spacing; f.ranges[c][1]=8*spacing;
        offset+=count;
    }
    f.header[3]=static_cast<std::uint32_t>(offset); out=f; return true;
}
RCFieldPass::~RCFieldPass() { (void)destroy(); }
BedrayStatus RCFieldPass::gpu_status(gpu::Status s, VkResult vk, BedrayError* e) noexcept {
    if (s == gpu::Status::ok) return BEDRAY_OK;
    if (s == gpu::Status::device_lost || vk == VK_ERROR_DEVICE_LOST) {
        lost_=true; if (buffers_) buffers_->device_lost();
        return fail(e,BEDRAY_ERROR_DEVICE_LOST,"RC device lost; recordings retained until host proof");
    }
    if (s == gpu::Status::in_use) return fail(e,BEDRAY_ERROR_IN_USE,"RC recording still in use");
    if (s == gpu::Status::out_of_memory || s == gpu::Status::budget_exceeded || s == gpu::Status::exhausted)
        return fail(e,BEDRAY_ERROR_OUT_OF_MEMORY,"RC buffer budget/allocation exhausted");
    if (s == gpu::Status::unsupported || s == gpu::Status::unavailable)
        return fail(e,BEDRAY_ERROR_UNSUPPORTED,"RC buffer owner unavailable");
    return fail(e,BEDRAY_ERROR_VULKAN,"RC buffer operation failed");
}
BedrayStatus RCFieldPass::configure(BedrayCore* core,const DeviceFns& f,const BedrayRCDesc& d,
        std::uint32_t max_groups,BedrayError* e) {
    if (has_recordings()) return fail(e,BEDRAY_ERROR_IN_USE,"RC recordings pending");
    if (d.abi_version != BEDRAY_CORE_ABI_VERSION || d.struct_size != sizeof(d))
        return fail(e,BEDRAY_ERROR_ABI_MISMATCH,"RC descriptor ABI/size mismatch");
    RCFieldMetadata metadata{};
    if (!core || !f.cmd_pipeline_barrier || !d.program_build || d.reserved
        || !rc_field_metadata(d.base_axis,0,metadata))
        return fail(e,BEDRAY_ERROR_INVALID_ARGUMENT,"invalid RC configuration");
    for (const auto& c:metadata.counts)
        if (!max_groups || (static_cast<std::uint64_t>(c[2])*6u*c[1]*c[1]+63u)/64u > max_groups)
            return fail(e,BEDRAY_ERROR_UNSUPPORTED,"RC dispatch exceeds device group limit");
    ProgramContract contract{};
    if (!program_contract(core,d.program_build,contract) || contract.push_constant_bytes != sizeof(BuildPush)
        || contract.binding_count != 16)
        return fail(e,BEDRAY_ERROR_INVALID_ARGUMENT,"RC build program contract mismatch");
    for (auto b:scene_bindings) if (!program_has_binding(core,d.program_build,b,type(b)))
        return fail(e,BEDRAY_ERROR_INVALID_ARGUMENT,"RC build scene binding mismatch");
    for (auto b:{34u,35u}) if (!program_has_binding(core,d.program_build,b,type(b)))
        return fail(e,BEDRAY_ERROR_INVALID_ARGUMENT,"RC build field binding mismatch");
    gpu::DeviceContext context{};
    context.device=f.device; context.functions=f.buffer_functions; context.memory_properties=f.memory_properties;
    context.non_coherent_atom_size=f.non_coherent_atom_size; context.dedicated_allocation_enabled=true;
    context.generation=f.generation;
    auto next=std::make_unique<gpu::GpuBuffers>(context,d.buffer_budget_bytes ? d.buffer_budget_bytes : 192ull<<20);
    if (!next->valid()) return fail(e,BEDRAY_ERROR_UNSUPPORTED,"RC buffer functions unavailable");
    auto status=destroy(); if (status != BEDRAY_OK) return status;
    core_=core; fns_=f; desc_=d; buffers_=std::move(next); return BEDRAY_OK;
}
BedrayStatus RCFieldPass::allocate(std::uint64_t bytes,std::uint32_t usage,const void* data,
        gpu::UseId& pin,gpu::BufferView& view,BedrayError* e) {
    gpu::BufferDesc d{}; d.size=bytes; d.usage=usage;
    d.required=data ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
    d.mapped=data != nullptr;
    gpu::BufferId id{}; VkResult vk=VK_SUCCESS;
    auto s=buffers_->create(d,id,view,vk); if (s != gpu::Status::ok) return gpu_status(s,vk,e);
    if (data) s=buffers_->write(id,0,data,static_cast<std::size_t>(bytes),vk);
    if (s == gpu::Status::ok) s=buffers_->begin_use(id,pin);
    (void)buffers_->retire(id);
    if (s != gpu::Status::ok) { (void)buffers_->collect(); return gpu_status(s,vk,e); }
    return BEDRAY_OK;
}
BedrayStatus RCFieldPass::record(const BedrayPathTraceFrameDesc& frame,std::span<const BedrayResource> inputs,
        std::array<BedrayResource,2>& outputs,BedrayError* e) {
    if (!buffers_ || lost_) return fail(e,BEDRAY_ERROR_UNSUPPORTED,"RC owner unavailable");
    if (!frame.command_buffer || !(frame.flags & BEDRAY_PATH_TRACE_FRAME_NATIVE_SCENE))
        return fail(e,BEDRAY_ERROR_INVALID_ARGUMENT,"RC requires native scene recording");
    if (recordings_.contains(frame.command_buffer) || recordings_.size() >= 8)
        return fail(e,BEDRAY_ERROR_IN_USE,"RC recording/slots still in use");
    std::array<BedrayResource,16> resources{};
    for (std::size_t i=0;i<scene_bindings.size();++i) {
        const auto binding=scene_bindings[i];
        const auto found=std::find_if(inputs.begin(),inputs.end(),[&](const auto& r){return r.binding==binding;});
        if (found==inputs.end() || found->descriptor_type != type(binding) || !found->handle
            || ((type(binding)==VK_DESCRIPTOR_TYPE_STORAGE_BUFFER || type(binding)==VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                && (!found->range || found->offset > std::numeric_limits<std::uint64_t>::max()-found->range)))
            return fail(e,BEDRAY_ERROR_INVALID_ARGUMENT,"RC borrowed scene input mismatch");
        resources[i]=*found;
    }
    RCFieldMetadata metadata{}; (void)rc_field_metadata(desc_.base_axis,frame.frame_index,metadata);
    auto& pins=recordings_.try_emplace(frame.command_buffer).first->second;
    gpu::BufferView meta{},tails{};
    auto status=allocate(sizeof(metadata),VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,&metadata,pins.uses[0],meta,e);
    if (status != BEDRAY_OK) return status;
    status=allocate(static_cast<std::uint64_t>(metadata.header[3])*32u,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
        nullptr,pins.uses[1],tails,e);
    if (status != BEDRAY_OK) return status;
    outputs={resource(34,meta),resource(35,tails)};
    resources[14]=outputs[0]; resources[15]=outputs[1];
    const auto& p=frame.path;
    BuildPush push{0,0,frame.frame_index,p.material_count,p.ray_bias,p.alpha_cutoff,p.max_radiance,
        p.sun_probability,p.emitter_count,2,p.emitter_probability,0};
    std::memcpy(push.emitter_camera,p.emitter_camera_relative,sizeof(push.emitter_camera));
    std::memcpy(push.entities,p.entity_state,sizeof(push.entities));
    BedrayFrameDesc call{}; call.abi_version=BEDRAY_CORE_ABI_VERSION;
    call.command_buffer=frame.command_buffer; call.program=desc_.program_build;
    call.group_count_y=call.group_count_z=1; call.resources=resources.data(); call.resource_count=16;
    call.push_constants=&push; call.push_constant_bytes=sizeof(push);
    const auto cmd=reinterpret_cast<VkCommandBuffer>(static_cast<std::uintptr_t>(frame.command_buffer));
    barrier(fns_,cmd);
    for (int c=4;c>=0;--c) {
        push.level=static_cast<std::uint32_t>(c);
        push.count=metadata.counts[c][2]*6u*metadata.counts[c][1]*metadata.counts[c][1];
        call.group_count_x=(push.count+63u)/64u;
        status=bedray_core_render_frame(core_,&call,e);
        if (status != BEDRAY_OK) {
            if (status==BEDRAY_ERROR_DEVICE_LOST) { lost_=true; buffers_->device_lost(); }
            return status;
        }
        barrier(fns_,cmd);
    }
    return BEDRAY_OK;
}
BedrayStatus RCFieldPass::release_recording(std::uint64_t cmd) noexcept {
    const auto it=recordings_.find(cmd); if (it==recordings_.end()) return BEDRAY_OK;
    if (lost_) {
        if (recordings_.size()>1) { recordings_.erase(it); return BEDRAY_OK; }
        auto s=buffers_->release_after_device_loss();
        if (s != gpu::Status::ok) return gpu_status(s,VK_SUCCESS,nullptr);
        buffers_.reset(); recordings_.erase(it); return BEDRAY_OK;
    }
    for (auto& use:it->second.uses) if (use.serial) {
        auto s=buffers_->complete_use(use); if (s != gpu::Status::ok) return gpu_status(s,VK_SUCCESS,nullptr);
        use={};
    }
    auto s=buffers_->collect(); if (s != gpu::Status::ok) return gpu_status(s,VK_SUCCESS,nullptr);
    recordings_.erase(it); return BEDRAY_OK;
}
BedrayStatus RCFieldPass::destroy() noexcept {
    if (has_recordings()) return BEDRAY_ERROR_IN_USE;
    if (buffers_) {
        auto s=lost_ ? buffers_->release_after_device_loss() : buffers_->close();
        if (s != gpu::Status::ok) return gpu_status(s,VK_SUCCESS,nullptr);
        buffers_.reset();
    }
    core_=nullptr; fns_={}; desc_={}; lost_=false; return BEDRAY_OK;
}
}
