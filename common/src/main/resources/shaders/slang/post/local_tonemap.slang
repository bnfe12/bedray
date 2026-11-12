// Local tone mapping (module): shared constants and maths of passes/local_tonemap_lum.slang and _apply.slang.
//
// The global curve (tonemap_curve.slang) maps every pixel by its own brightness. Around a torch the whole pool sits
// in the curve's shoulder, where neighbouring texels of different albedo land on nearly the same display value: the
// floor became one flat orange plate ("a colour fill, not a torch", owner 2026-11-12). A local tone mapper first
// moves the *local base* (the blurred log luminance) towards a pivot and keeps each pixel's ratio to that base, so the
// texture survives inside the pool while the pool as a whole stays brighter than its surroundings. RTX Remix
// Minecraft ships one (packaging/rtx.conf: rtx.localtonemap.shadows = 1.4, highlights = 2.5).

public module local_tonemap;

__exported import tonemap_curve;

public static const uint LOCAL_TONEMAP_BLOCK = 16u;
public static const float LOCAL_TONEMAP_EPS = 1.0e-5;

public struct LocalTonemapPush {
    public uint2 size;          // presented image
    public uint2 lowSize;       // block image
    public float exposure;      // the frame's exposure, the same factor the compose pass applies
    public float highlights;    // 0 = off, 1 = the default compression of bright local bases
    public float shadows;       // 0 = off, 1 = the default lift of dark local bases
    public float pivotLog2;     // log2 of the exposed luminance left unchanged
};

// The ray distance the output resolve wrote; a primary miss is 999999 (path_trace.slang), any far value is sky.
public bool localTonemapIsSky(float distance) {
    return !(distance < 1.0e5);
}

public float localTonemapLuminance(float3 hdr, float exposure) {
    float3 c = max(hdr, float3(0.0)) * max(exposure, 0.0);
    if (any(isnan(c)) || any(isinf(c))) {
        return LOCAL_TONEMAP_EPS;
    }
    return dot(c, TM_LUMA);
}

// Gain in stops for a local base `base` (log2 exposed luminance). Above the pivot the base is pulled towards it by up
// to HIGHLIGHT_PULL of its distance, below it is lifted by up to SHADOW_LIFT; both scaled by the player's strengths
// and capped so no region is changed by more than MAX_STOPS.
static const float HIGHLIGHT_PULL = 0.55;
static const float SHADOW_LIFT = 0.20;
static const float MAX_STOPS = 2.5;

public float localTonemapGainStops(float base, LocalTonemapPush pc) {
    float d = base - pc.pivotLog2;
    float stops = d > 0.0 ? -d * HIGHLIGHT_PULL * pc.highlights : -d * SHADOW_LIFT * pc.shadows;
    return clamp(stops, -MAX_STOPS, MAX_STOPS);
}
