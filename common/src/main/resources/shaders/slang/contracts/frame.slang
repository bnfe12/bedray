// Frame contract for Bedray 2.0.
//
// This module is deliberately free of descriptor bindings and of the legacy
// GLSL ABI.  A host fills FrameConstants once per frame; every pass receives
// the same immutable value.  Vectors are stored as float4 groups so the Java
// mirror can be generated from Slang reflection without guessing padding.

public module frame_contract;

// Camera state in camera-relative world space.  The renderer keeps the camera
// at the origin for precision; `origin` is only used to rebuild absolute world
// positions for reprojection and debug overlays.
struct CameraState {
    float4x4 view;
    float4x4 projection;
    float4x4 viewProjection;
    float4x4 invViewProjection;
    float4x4 prevViewProjection;

    float4 origin;          // xyz: camera origin, w: frame time in seconds
    float4 forward;         // xyz: forward axis, w: tan(half vertical fov)
    float4 right;           // xyz: right axis,   w: aspect ratio
    float4 up;              // xyz: up axis,      w: reserved
    float4 jitter;          // xy: pixel jitter,  zw: 1 / render size
    uint4  size;            // x: render width, y: render height,
                            // z: display width, w: display height
};

// Key light.  Directions point *towards* the light, so a shadow ray uses
// `direction` unchanged.
struct SunState {
    float4 direction;       // xyz: towards sun,  w: angular radius (radians)
    float4 color;           // rgb: radiance,     w: intensity
    float4 moonDirection;   // xyz: towards moon, w: angular radius (radians)
    float4 moonColor;       // rgb: radiance,     w: intensity
};

struct AtmosphereState {
    float4 zenithColor;     // rgb: zenith radiance, w: intensity
    float4 horizonColor;    // rgb: horizon radiance, w: intensity
    float4 fogColor;        // rgb: fog in-scattering, w: reserved
    float4 fog;             // x: density, y: distance, z: height falloff, w: mode
    float4 cloud;           // x: coverage, y: speed, z: base height, w: reserved
};

struct ExposureState {
    float4 values;          // x: exposure, y: bloom intensity,
                            // z: auto-exposure speed, w: delta time
    uint4  mode;            // x: auto exposure, y: tonemap operator,
                            // z: bloom enabled, w: reserved
};

// Bits shared by every pass.  Keep this list additive: passes must ignore
// unknown bits instead of branching on a global mode enum.
static const uint FRAME_FLAG_HISTORY_VALID      = 1u << 0u;
static const uint FRAME_FLAG_CAMERA_CUT         = 1u << 1u;
static const uint FRAME_FLAG_RESET_TEMPORAL     = 1u << 2u;
static const uint FRAME_FLAG_FIRST_PERSON       = 1u << 3u;
static const uint FRAME_FLAG_DEBUG_VIEW         = 1u << 4u;
// The eye's own block is water.  No surface crossing produced it, so a path that starts here starts inside the medium and
// cannot learn it from geometry: the water surface above can be hidden by a block submerged in the same body.
static const uint FRAME_FLAG_CAMERA_IN_WATER    = 1u << 5u;
// The player's two water switches, as the host read them from the settings screen.  Both are off unless the host says
// otherwise, so a frame from an older host traces water the way a flat sheet is traced: straight light, no focus.
// STYLE: realistic water models the body as a volume the light bends through; vanilla keeps the straight sight line.
static const uint FRAME_FLAG_WATER_REALISTIC    = 1u << 6u;
// CAUSTICS: the wave field gathers the bent beam onto the floor and ceiling of the body.  Only ever read with the beam,
// so a vanilla-style frame cannot focus light it never bent.
static const uint FRAME_FLAG_WATER_CAUSTICS     = 1u << 7u;

struct FrameConstants {
    CameraState     camera;
    SunState        sun;
    AtmosphereState atmosphere;
    ExposureState   exposure;
    uint4           frame;  // x: frame index, y: sample index within the frame,
                            // z: samples per frame, w: FRAME_FLAG_* bits
};

// A frame is allowed to request a temporal reset without changing the plan.
bool frameWantsHistory(FrameConstants frame) {
    return (frame.frame.w & FRAME_FLAG_HISTORY_VALID) != 0u &&
           (frame.frame.w & FRAME_FLAG_RESET_TEMPORAL) == 0u;
}

float frameDeltaTime(FrameConstants frame) {
    return max(frame.exposure.values.w, 0.0);
}

// Whether the host put the eye inside a water body this frame.
bool frameCameraInWater(FrameConstants frame) {
    return (frame.frame.w & FRAME_FLAG_CAMERA_IN_WATER) != 0u;
}

// Whether the player asked for water modelled as a volume, and whether the waves are allowed to focus the light that
// passes through them.  The path tracer consults these instead of reading the bits itself, so the meaning of a switch
// lives in one place and a renamed bit cannot be missed by a pass that started reading the frame word directly.
bool frameWaterVolumeStyle(FrameConstants frame) {
    return (frame.frame.w & FRAME_FLAG_WATER_REALISTIC) != 0u;
}

bool frameWaterCaustics(FrameConstants frame) {
    return (frame.frame.w & FRAME_FLAG_WATER_CAUSTICS) != 0u;
}
