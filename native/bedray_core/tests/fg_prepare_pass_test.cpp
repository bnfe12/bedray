#include "../src/fg_prepare_pass.h"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <vector>

namespace {
void check(bool value) { if (!value) std::abort(); }
struct Push { std::uint32_t width, height; };
struct Recorded { std::uint64_t command, program; std::uint32_t x,y,z; std::array<BedrayResource,4> resources; Push push; };
std::vector<Recorded> records;
std::vector<char> events;
VkImageBlit blit_region{};
VkImage source_image{}, destination_image{};
bool known=true, bindings_ok=true;
bedray::core::ProgramContract contract{8,4};
BedrayStatus dispatch_result=BEDRAY_OK;
VKAPI_ATTR void VKAPI_CALL barrier(VkCommandBuffer command,VkPipelineStageFlags source,VkPipelineStageFlags destination,
    VkDependencyFlags flags,std::uint32_t count,const VkMemoryBarrier* memory,std::uint32_t buffers,
    const VkBufferMemoryBarrier*,std::uint32_t images,const VkImageMemoryBarrier*) {
    check(command!=VK_NULL_HANDLE && source==VK_PIPELINE_STAGE_ALL_COMMANDS_BIT && destination==source);
    check(!flags && count==1 && !buffers && !images);
    check(memory->srcAccessMask==VK_ACCESS_MEMORY_WRITE_BIT);
    check(memory->dstAccessMask==(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT));
    events.push_back('b');
}
VKAPI_ATTR void VKAPI_CALL blit(VkCommandBuffer command,VkImage source,VkImageLayout source_layout,
    VkImage destination,VkImageLayout destination_layout,std::uint32_t count,const VkImageBlit* regions,VkFilter filter) {
    check(command!=VK_NULL_HANDLE && source_layout==VK_IMAGE_LAYOUT_GENERAL && destination_layout==VK_IMAGE_LAYOUT_GENERAL);
    check(count==1 && filter==VK_FILTER_NEAREST);
    blit_region=*regions; source_image=source; destination_image=destination; events.push_back('l');
}
}
namespace bedray::core {
bool program_contract(BedrayCore*,std::uint64_t handle,ProgramContract& out) noexcept { out=contract; return known && handle==17; }
bool program_has_binding(BedrayCore*,std::uint64_t,std::uint32_t binding,std::uint32_t type) noexcept {
    return bindings_ok && binding<4 && type==(binding==0?VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
}
}
BEDRAY_API BedrayStatus BEDRAY_CALL bedray_core_render_frame(BedrayCore*,const BedrayFrameDesc* frame,BedrayError*) {
    check(frame->resource_count==4 && frame->push_constant_bytes==sizeof(Push));
    Recorded copy{frame->command_buffer,frame->program,frame->group_count_x,frame->group_count_y,frame->group_count_z,{},{}};
    std::memcpy(copy.resources.data(),frame->resources,sizeof(copy.resources));
    std::memcpy(&copy.push,frame->push_constants,sizeof(copy.push)); records.push_back(copy); events.push_back('d');
    return dispatch_result;
}
int main() {
    auto* core=reinterpret_cast<BedrayCore*>(1);
    BedrayFgPrepareFrameDesc frame{BEDRAY_CORE_ABI_VERSION,sizeof(BedrayFgPrepareFrameDesc),11,17,19,21,22,23,24,25,26,101,51,19,9,0};
    BedrayError error{};
    auto render=[&](const auto& value) { return bedray::core::record_fg_prepare(core,barrier,blit,value,&error); };
    auto bad=[&](auto change,BedrayStatus expected=BEDRAY_ERROR_INVALID_ARGUMENT) {
        auto value=frame; change(value); check(render(value)==expected); check(events.empty() && records.empty());
    };
    bad([](auto& f){f.abi_version=0;},BEDRAY_ERROR_ABI_MISMATCH);
    bad([](auto& f){--f.struct_size;},BEDRAY_ERROR_ABI_MISMATCH);
    bad([](auto& f){f.command_buffer=0;}); bad([](auto& f){f.program=0;}); bad([](auto& f){f.sampler=0;});
    bad([](auto& f){f.source_color_image=0;}); bad([](auto& f){f.hudless_image=f.source_color_image;});
    bad([](auto& f){f.depth_view=0;}); bad([](auto& f){f.input_motion_view=0;});
    bad([](auto& f){f.output_depth_view=f.input_motion_view;}); bad([](auto& f){f.output_motion_view=f.depth_view;});
    bad([](auto& f){f.output_motion_view=f.output_depth_view;});
    bad([](auto& f){f.window_width=0;}); bad([](auto& f){f.render_height=0;}); bad([](auto& f){f.reserved=1;});
    bad([](auto& f){f.window_height=std::numeric_limits<std::uint32_t>::max();});
    known=false; check(render(frame)==BEDRAY_ERROR_INVALID_ARGUMENT); known=true;
    bindings_ok=false; check(render(frame)==BEDRAY_ERROR_INVALID_ARGUMENT); bindings_ok=true;
    contract.binding_count=3; check(render(frame)==BEDRAY_ERROR_INVALID_ARGUMENT); contract.binding_count=4;
    contract.push_constant_bytes=16; check(render(frame)==BEDRAY_ERROR_INVALID_ARGUMENT); contract.push_constant_bytes=8;
    check(events.empty() && records.empty());
    check(render(frame)==BEDRAY_OK && events==std::vector<char>({'b','l','d','b'}));
    check(source_image==reinterpret_cast<VkImage>(std::uintptr_t{21}) && destination_image==reinterpret_cast<VkImage>(std::uintptr_t{22}));
    check(blit_region.srcSubresource.aspectMask==VK_IMAGE_ASPECT_COLOR_BIT && blit_region.srcSubresource.layerCount==1);
    check(blit_region.srcOffsets[0].x==0 && blit_region.srcOffsets[0].y==0 && blit_region.srcOffsets[0].z==0);
    check(blit_region.srcOffsets[1].x==101 && blit_region.srcOffsets[1].y==51 && blit_region.srcOffsets[1].z==1);
    check(blit_region.dstOffsets[0].x==0 && blit_region.dstOffsets[0].y==51 && blit_region.dstOffsets[0].z==0);
    check(blit_region.dstOffsets[1].x==101 && blit_region.dstOffsets[1].y==0 && blit_region.dstOffsets[1].z==1);
    const auto& first=records.front();
    check(first.command==11 && first.program==17 && first.x==3 && first.y==2 && first.z==1);
    check(first.push.width==19 && first.push.height==9);
    for(std::uint32_t index=0;index<4;++index) {
        check(first.resources[index].binding==index && first.resources[index].handle==23+index);
        check(first.resources[index].image_layout==VK_IMAGE_LAYOUT_GENERAL);
        check(first.resources[index].sampler==(index==0?19u:0u));
    }
    events.clear(); frame.depth_view=33; check(render(frame)==BEDRAY_OK);
    check(records.front().resources[0].handle==23 && records.back().resources[0].handle==33);
    events.clear(); dispatch_result=BEDRAY_ERROR_OUT_OF_MEMORY;
    check(render(frame)==dispatch_result && events==std::vector<char>({'b','l','d'}));
    std::puts("FG producer ABI/blit/descriptor/barrier contracts passed (no GPU/vendor consumer)");
}
