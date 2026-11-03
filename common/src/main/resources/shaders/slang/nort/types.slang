public module nort_types;

public static const uint FLAG_CUTOUT=1u, FLAG_TRANSLUCENT=2u, FLAG_CELL_TINT=4u, FLAG_FLUID=8u, FLAG_NO_MATERIAL=16u;
public static const uint MAX_EMITTERS=128u;
public static const float PI=3.14159265358979;
// Sun's mean angular radius, 0.2666 degrees (Moon within two percent).
public static const float KEY_ANGULAR_RADIUS=0.004653;
// A level-15 sphere contributes below 1/1000 of its one-block irradiance here.
public static const float EMITTER_RANGE=32.0;
// Visible-light water and crown-glass indices of refraction.
public static const float WATER_IOR=1.333, GLASS_IOR=1.5;

public struct Hit {
    bool found; int3 cell; uint material; float t; float3 normal;
    float3 albedo; uint flags; float emission; float alpha;
};
public struct CellLight { bool available; float block; float sky; int3 cell; };
public struct CubeFace { float4 originDu; float2 dv; uint tint; uint flags; };
public struct ModelQuad { float4 vertices[5]; uint4 metadata; };
public struct NoRtPush {
    column_major float4x4 inverseViewProjection;
    float4 cameraOrigin;
    uint4 control;
    float4 rayOriginOffset;
};
public struct NoRtEnvironment {
    float4 values[6];
    column_major float4x4 previousViewProjection;
    float4 previousCamera;
    float4 previousEye;
    uint4 info;
    float4 emitters[2*MAX_EMITTERS];
};
