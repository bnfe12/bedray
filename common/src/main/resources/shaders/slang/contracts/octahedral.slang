// Bedray 2.0 — Octahedral Normal Encoding (Meyer et al. 2010 / Cigolle et al. 2014)
//
// Packs 3D unit normals into 4 bytes (snorm16x2) instead of 12 bytes (float3),
// saving 66% of G-buffer memory bandwidth with near-zero precision loss.

public module octahedral;

// Encodes a normalized 3D vector into 2D [-1, 1] octahedral coordinates
public float2 octEncode(float3 n) {
    float l1 = abs(n.x) + abs(n.y) + abs(n.z);
    float2 p = n.xy / (l1 > 1.0e-6 ? l1 : 1.0);
    if (n.z < 0.0) {
        p = (1.0 - abs(p.yx)) * float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    }
    return p;
}

// Decodes a 2D [-1, 1] octahedral coordinate back to a normalized 3D vector
public float3 octDecode(float2 e) {
    float3 v = float3(e.xy, 1.0 - abs(e.x) - abs(e.y));
    if (v.z < 0.0) {
        v.xy = (1.0 - abs(v.yx)) * float2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(v);
}

// Packs a 3D unit normal into a single 32-bit uint (two 16-bit signed shorts)
public uint octPackSnorm16x2(float3 n) {
    float2 enc = octEncode(n);
    int2 snorm = int2(clamp(enc * 32767.0, -32767.0, 32767.0));
    return (uint(snorm.x & 0xFFFF)) | (uint(snorm.y & 0xFFFF) << 16u);
}

// Unpacks a 3D unit normal from a single 32-bit uint (two 16-bit signed shorts)
public float3 octUnpackSnorm16x2(uint packed) {
    int sx = int(short(packed & 0xFFFFu));
    int sy = int(short(packed >> 16u));
    float2 enc = float2(float(sx) / 32767.0, float(sy) / 32767.0);
    return octDecode(enc);
}
