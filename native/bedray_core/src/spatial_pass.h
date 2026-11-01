#pragma once
#include "bedray_spatial.h"
#include "core_internal.h"
namespace bedray::core {
// Core owns immutable per-dispatch descriptors. This stateless pass validates
// the complete chain before recording its first barrier or dispatch.
BedrayStatus record_spatial(BedrayCore*, PFN_vkCmdPipelineBarrier, const BedraySpatialFrameDesc&, BedrayError*);
}
