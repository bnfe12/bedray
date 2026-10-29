#pragma once
#include "bedray_nrd_pack.h"
#include "core_internal.h"

namespace bedray::core {
BedrayStatus record_nrd_pack(BedrayCore*, PFN_vkCmdPipelineBarrier,
    const BedrayNrdPackFrameDesc&, BedrayError*);
}
