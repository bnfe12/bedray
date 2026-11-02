#include "primary_lobe_settings.h"
#include <cmath>
#include <cstdio>

template<class Settings> bool verify() {
    Settings settings;
    settings.diffusePrepassBlurRadius = 0.0f;
    settings.specularPrepassBlurRadius = 0.0f;
    bedray::nrd_bridge::configure_primary_lobe_signals(settings);
    if (settings.hitDistanceReconstructionMode != nrd::HitDistanceReconstructionMode::AREA_3X3
        || settings.diffusePrepassBlurRadius != 1.0f || settings.specularPrepassBlurRadius != 1.0f
        || settings.minMaterialForDiffuse != 0.0f) return false;
    settings.diffusePrepassBlurRadius = 12.0f;
    settings.specularPrepassBlurRadius = 31.0f;
    bedray::nrd_bridge::configure_primary_lobe_signals(settings);
    return settings.diffusePrepassBlurRadius == 12.0f && settings.specularPrepassBlurRadius == 31.0f;
}

int main() {
    if (!verify<nrd::ReblurSettings>() || !verify<nrd::RelaxSettings>()) return 1;
    std::puts("REBLUR/RELAX primary-lobe settings passed (headers only, no GPU)");
    return 0;
}
