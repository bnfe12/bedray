// Private, versioned sky packet inside the already borrowed 576-byte frame slot.
// This module alone knows its word offsets. It does not import legacy declarations
// or change the public frame/push layouts or the descriptor set.
public module sky_display_contract;
import "contracts/math.slang";

static const uint SKY_DISPLAY_PACKET_BYTES = 576u;
static const uint SKY_DISPLAY_ATLAS_WORD_COUNT_BYTE = 72u;
static const uint SKY_DISPLAY_COLOR_DAY_BYTE = 488u;
static const uint SKY_DISPLAY_EXPOSURE_BYTE = 44u;   // RtStatsLayout.EXPOSURE (36 is the high word of the emitter address)
static const uint SKY_DISPLAY_SUN_BYTE = 256u;
static const uint SKY_DISPLAY_MOON_BYTE = 272u;
static const uint SKY_DISPLAY_FOG_BYTE = 288u;
static const uint SKY_DISPLAY_SUNSET_BYTE = 304u;
static const uint SKY_DISPLAY_CLOUD_COLOR_BYTE = 320u;
static const uint SKY_DISPLAY_CLOUD_POS_BYTE = 336u;
static const uint SKY_DISPLAY_MISC_BYTE = 352u;
static const uint SKY_DISPLAY_ATLAS_ADDRESS_BYTE = 368u;
static const uint SKY_DISPLAY_FLAGS_BYTE = 376u;
static const uint SKY_DISPLAY_TILT_BYTE = 380u;
static const uint SKY_DISPLAY_VERSION_MASK = 0xff000000u;
static const uint SKY_DISPLAY_VERSION = 0x01000000u;
static const uint SKY_DISPLAY_TONEMAPPED = 1u << 10u;
static const uint SKY_TEXTURE_SLOTS = 12u;
static const uint SKY_TEXTURE_HEADER_WORDS = SKY_TEXTURE_SLOTS * 4u;

struct SkyDisplayState {
    bool valid;
    uint64_t textureAddress;
    uint textureWords;
    uint skyTexFlags;
    float skyTilt;
    float exposure;
    float4 visibleColorDay;
    float4 skySunDir;
    float4 skyMoonDir;
    float4 skyFogColor;
    float4 skySunset;
    float4 skyCloudColor;
    float4 skyCloudPos;
    float4 skyMisc;
};

float4 skyPacketVector(RWStructuredBuffer<uint> slot, uint byteOffset) {
    uint at = byteOffset / 4u;
    return asfloat(uint4(slot[at], slot[at + 1u], slot[at + 2u], slot[at + 3u]));
}

SkyDisplayState readSkyDisplayState(RWStructuredBuffer<uint> slot) {
    SkyDisplayState sky = (SkyDisplayState)0;
    uint words, stride;
    slot.GetDimensions(words, stride);
    if (stride != 4u || words < SKY_DISPLAY_PACKET_BYTES / 4u) return sky;
    sky.skyTexFlags = slot[SKY_DISPLAY_FLAGS_BYTE / 4u];
    if ((sky.skyTexFlags & SKY_DISPLAY_VERSION_MASK) != SKY_DISPLAY_VERSION) return sky;
    uint addressWord = SKY_DISPLAY_ATLAS_ADDRESS_BYTE / 4u;
    sky.textureAddress = uint64_t(slot[addressWord]) | (uint64_t(slot[addressWord + 1u]) << 32u);
    sky.textureWords = slot[SKY_DISPLAY_ATLAS_WORD_COUNT_BYTE / 4u];
    if (sky.textureAddress == 0u || (sky.textureAddress & 3u) != 0u
        || sky.textureWords < SKY_TEXTURE_HEADER_WORDS) return sky;
    if (sky.textureAddress > ~uint64_t(0) - uint64_t(sky.textureWords) * 4u) return sky;
    sky.skyTilt = asfloat(slot[SKY_DISPLAY_TILT_BYTE / 4u]);
    sky.exposure = asfloat(slot[SKY_DISPLAY_EXPOSURE_BYTE / 4u]);
    sky.visibleColorDay = skyPacketVector(slot, SKY_DISPLAY_COLOR_DAY_BYTE);
    sky.skySunDir = skyPacketVector(slot, SKY_DISPLAY_SUN_BYTE);
    sky.skyMoonDir = skyPacketVector(slot, SKY_DISPLAY_MOON_BYTE);
    sky.skyFogColor = skyPacketVector(slot, SKY_DISPLAY_FOG_BYTE);
    sky.skySunset = skyPacketVector(slot, SKY_DISPLAY_SUNSET_BYTE);
    sky.skyCloudColor = skyPacketVector(slot, SKY_DISPLAY_CLOUD_COLOR_BYTE);
    sky.skyCloudPos = skyPacketVector(slot, SKY_DISPLAY_CLOUD_POS_BYTE);
    sky.skyMisc = skyPacketVector(slot, SKY_DISPLAY_MISC_BYTE);
    sky.valid = isFinite(sky.skyTilt) && isFinite(sky.visibleColorDay.xyz) && isFinite(sky.visibleColorDay.w)
        && isFinite(sky.skySunDir.xyz) && isFinite(sky.skySunDir.w)
        && isFinite(sky.skyMoonDir.xyz) && isFinite(sky.skyMoonDir.w)
        && isFinite(sky.skyFogColor.xyz) && isFinite(sky.skyFogColor.w)
        && isFinite(sky.skySunset.xyz) && isFinite(sky.skySunset.w)
        && isFinite(sky.skyCloudColor.xyz) && isFinite(sky.skyCloudColor.w)
        && isFinite(sky.skyCloudPos.xyz) && isFinite(sky.skyCloudPos.w)
        && isFinite(sky.skyMisc.xyz) && isFinite(sky.skyMisc.w);
    return sky;
}

uint skyFlags(SkyDisplayState sky) { return sky.skyTexFlags; }
bool skyHasSky(SkyDisplayState sky) { return (skyFlags(sky) & 16u) != 0u; }
bool skyIsEnd(SkyDisplayState sky) { return (skyFlags(sky) & 64u) != 0u; }
bool skyIsNether(SkyDisplayState sky) { return (skyFlags(sky) & 128u) != 0u; }
uint skyMoonPhase(SkyDisplayState sky) { return skyFlags(sky) & 7u; }
uint skyCloudStatus(SkyDisplayState sky) { return (skyFlags(sky) >> 8u) & 3u; }
