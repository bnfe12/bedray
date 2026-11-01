// Read-only ABGR atlas uploaded by RtSkyTextures. One checked header owns every
// following texel access; arithmetic uses the borrowed allocation's word count.
public module sky_texture_access;
__exported import "contracts/sky_display.slang";

bool skyTextureHeader(SkyDisplayState sky, uint slot, out uint3 header) {
    header = uint3(0u);
    if (!sky.valid || sky.textureAddress == 0u || slot >= SKY_TEXTURE_SLOTS
        || sky.textureWords < SKY_TEXTURE_HEADER_WORDS) return false;
    uint* data = (uint*)sky.textureAddress;
    uint at = slot * 4u;
    uint offset = data[at], width = data[at + 1u], height = data[at + 2u];
    if (data[at + 3u] == 0u || width == 0u || height == 0u
        || offset < SKY_TEXTURE_HEADER_WORDS || offset > sky.textureWords
        || width > (sky.textureWords - offset) / height) return false;
    header = uint3(offset, width, height);
    return true;
}

bool skyTexLoaded(SkyDisplayState sky, uint slot) {
    uint3 header;
    return skyTextureHeader(sky, slot, header);
}
uint2 skyTexSize(SkyDisplayState sky, uint slot) {
    uint3 header;
    return skyTextureHeader(sky, slot, header) ? header.yz : uint2(0u);
}
float4 skyTexelAt(SkyDisplayState sky, uint slot, uint2 pixel) {
    uint3 header;
    if (!skyTextureHeader(sky, slot, header) || any(pixel >= header.yz)) return float4(0.0);
    uint* data = (uint*)sky.textureAddress;
    uint c = data[header.x + pixel.y * header.y + pixel.x];
    return float4(float(c & 255u), float((c >> 8u) & 255u), float((c >> 16u) & 255u), float(c >> 24u)) / 255.0;
}
float4 skyTexel(SkyDisplayState sky, uint slot, float2 uv) {
    uint2 size = skyTexSize(sky, slot);
    if (any(size == uint2(0u))) return float4(0.0);
    uint2 pixel = uint2(clamp(uv, float2(0.0), float2(0.99999)) * float2(size));
    return skyTexelAt(sky, slot, pixel);
}
