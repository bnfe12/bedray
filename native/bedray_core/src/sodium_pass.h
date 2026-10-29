#pragma once
#include "bedray_sodium.h"
#include "core_internal.h"

namespace bedray::core {
// Stateless format conversion. Core recording uses retain its pipeline.
BedrayStatus record_sodium(BedrayCore*, PFN_vkCmdPipelineBarrier,
    std::uint32_t max_groups_x, const BedraySodiumDecodeDesc&, BedrayError*);
}
