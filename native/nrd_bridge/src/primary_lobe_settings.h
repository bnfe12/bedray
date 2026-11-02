#pragma once
#include <algorithm>
#include <cstdint>
#include "NRD.h"

namespace bedray::nrd_bridge {
// A missing primary lobe is represented by hitT=0. Reconstruction needs a
// nonzero pre-pass, including after the host replaces the REBLUR settings.
template<class Settings>
void configure_primary_lobe_signals(Settings& settings) {
    settings.hitDistanceReconstructionMode = nrd::HitDistanceReconstructionMode::AREA_3X3;
    settings.diffusePrepassBlurRadius = std::max(1.0f, settings.diffusePrepassBlurRadius);
    settings.specularPrepassBlurRadius = std::max(1.0f, settings.specularPrepassBlurRadius);
    settings.minMaterialForDiffuse = 0.0f;
}
}
