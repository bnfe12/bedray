#pragma once
#include "bedray_fg_prepare.h"
#include "core_internal.h"
namespace bedray::core {
BedrayStatus record_fg_prepare(BedrayCore*, PFN_vkCmdPipelineBarrier, PFN_vkCmdBlitImage,
    const BedrayFgPrepareFrameDesc&, BedrayError*);
}
