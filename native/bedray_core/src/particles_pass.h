#pragma once
#include "bedray_particles.h"
#include "core_internal.h"

namespace bedray::core {
// Stateless post pass: core recording uses already retain pipeline/descriptors.
BedrayStatus record_particles(BedrayCore*, PFN_vkCmdPipelineBarrier,
    const BedrayParticlesFrameDesc&, BedrayError*);
}
