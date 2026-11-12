package io.github.andrexarlay.vulkancompat.rtx.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-click texture sampling styles over the two runtime texture rows: point sampling (NEAREST magnification for crisp
 * voxel pixels) and the mipmap LOD bias offset (negative sharpens, positive softens). The mapping is pure so it is
 * unit-tested without a game ({@code TexturePresetTest}); the settings screen only stages it and Apply commits it.
 * The offset is the raw slider value in tenths (texture_lod_bias_offset divides by 10), kept within its -20..10 range.
 */
public enum TexturePreset {
    PIXEL_ART(true, -3),
    BALANCED(false, 0),
    SOFT(false, 3);

    public final boolean pointSampling;
    public final int lodBiasOffsetTenths;

    TexturePreset(boolean pointSampling, int lodBiasOffsetTenths) {
        this.pointSampling = pointSampling;
        this.lodBiasOffsetTenths = lodBiasOffsetTenths;
    }

    /** The runtime rows this style stages, keyed by option id. */
    public Map<String, Object> stagedValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("point_sampling_sharpness", pointSampling);
        values.put("texture_lod_bias_offset", lodBiasOffsetTenths);
        return values;
    }
}