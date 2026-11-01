// Preserved FAST plane and FANCY cloud-cell DDA from rt_sky.glsl.
// Cloud position and colour are the frame's vanilla render state.
public module sky_cloud_display;
__exported import "light/sky_textures.slang";

float4 skyCloudCell(SkyDisplayState sky, int2 cell, uint2 size) {
    uint2 t = uint2((cell % int2(size) + int2(size)) % int2(size));
    return skyTexelAt(sky, 9u, t);
}

// Vanilla clouds seen along d from the camera (display rgb, alpha). Space: cloud texture blocks, x/z wrapped as in
// CloudRenderer.render, y relative to the cloud bottom (LevelRenderState.cloudHeight); cells 12 x 4 x 12.
float4 skyClouds(SkyDisplayState sky, float3 d) {
    uint status = skyCloudStatus(sky);
    if (status == 0u || !skyTexLoaded(sky, 9u)) {
        return float4(0.0);
    }
    float4 cloudColor = sky.skyCloudColor;
    float3 o = sky.skyCloudPos.xyz;
    float fadeEnd = max(sky.skyCloudPos.w, 1.0);
    uint2 size = skyTexSize(sky, 9u);
    float top = status == 2u ? 4.0 : 0.0;
    // slab [0, top] (FAST: the plane y = 0)
    float t0;
    float t1;
    if (abs(d.y) < 1.0e-5) {
        if (o.y < 0.0 || o.y > top) {
            return float4(0.0);
        }
        t0 = 0.0;
        t1 = fadeEnd;
    } else {
        float ta = (0.0 - o.y) / d.y;
        float tb = (top - o.y) / d.y;
        t0 = max(min(ta, tb), 0.0);
        t1 = min(max(ta, tb), fadeEnd);
    }
    if (status == 1u) {
        t1 = t0;   // FAST: single plane
    }
    if (t0 > t1 + 1.0e-4 || t0 >= fadeEnd) {
        return float4(0.0);
    }
    // 2D DDA over 12 x 12 cells from t0 to t1
    float3 p = o + d * t0;
    int2 cell = int2(floor(p.xz / 12.0));
    float2 dxz = d.xz;
    int2 stepI = int2(dxz.x >= 0.0 ? 1 : -1, dxz.y >= 0.0 ? 1 : -1);
    float2 inv = float2(abs(dxz.x) > 1.0e-8 ? 1.0 / dxz.x : 1.0e30, abs(dxz.y) > 1.0e-8 ? 1.0 / dxz.y : 1.0e30);
    float2 nextB = (float2(cell) + float2((dxz >= float2(0.0)))) * 12.0;
    float2 tMax = t0 + (nextB - p.xz) * inv;
    float2 tDelta = abs(12.0 * inv);
    // face of the entry: 0 bottom, 1 top, 2 north/south (z), 3 west/east (x)
    uint face = (o.y < 0.0) ? 0u : (o.y > top ? 1u : 3u);
    float t = t0;
    for (int i = 0; i < 320; i++) {
        float4 c = skyCloudCell(sky, cell, size);
        if (c.a >= 10.0 / 255.0) {
            float shade = face == 0u ? 0.7 : (face == 1u ? 1.0 : (face == 2u ? 0.8 : 0.9));   // core/clouds.vsh faceColors
            if (status == 1u) {
                shade = 1.0;   // FAST draws the flat layer with the top colour
            }
            float fog = clamp(t / fadeEnd, 0.0, 1.0);   // core/clouds.fsh: alpha *= 1 - linear_fog_value(d, 0, FogCloudsEnd)
            return float4(c.rgb * shade * cloudColor.rgb, c.a * cloudColor.a * (1.0 - fog));
        }
        if (tMax.x < tMax.y) {
            t = tMax.x;
            tMax.x += tDelta.x;
            cell.x += stepI.x;
            face = 3u;
        } else {
            t = tMax.y;
            tMax.y += tDelta.y;
            cell.y += stepI.y;
            face = 2u;
        }
        if (t > t1) {
            break;
        }
    }
    return float4(0.0);
}
