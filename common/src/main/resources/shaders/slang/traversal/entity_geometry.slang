// Entity geometry for the hardware path: the ring of captured mob, item and
// armour-stand quads that RtEntityGeometryManager uploads every frame.
//
// The entity BLAS is one geometry of one TLAS instance whose custom index has
// the entity bit set, so a hit names itself: `primitiveId` is an index into the
// triangle list of this frame and nothing else.  The legacy path reaches the
// same records through device addresses in a statistics buffer
// (`rt_common_core.glsl`: entityTriangle, entityTexel, entitySlot); here they
// are five bound buffers, which is how the Slang pipeline passes every other
// per-frame list.
//
// Layout, per element, as written by RtEntityGeometryManager.uploadInterleaved:
//
//   positions  uint per float of a float3, 3 per vertex, 12 B - camera-relative,
//            the same space as the section geometry, because an entity model
//            comes from vanilla's camera-relative PoseStack.  No section origin,
//            no 1024 block wrap.
//   indices    uint per triangle vertex, 3 per primitive, numbering from 0
//   attrs      uint4 per vertex: colour (packed, low byte red), light
//            (block | atlas slot << 8 | sky << 16 | flags << 24), u as float
//            bits, v as float bits
//   rects      uint4 per atlas slot: x, y, w | other << 16, h | other << 16
//   texels     uint per texel of the square RT entity atlas
//
// Only the low halves of a rect's z and w carry the slot's size; a slot with a
// zero width or height has never been filled and reads as the fallback texel,
// which is what the legacy atlas returns for an untextured part.

public module entity_geometry;

import "contracts/geometry.slang";
import "contracts/hit.slang";
import "contracts/math.slang";

// The instance bit RtWorld writes into the entity TLAS instance; the low 23
// bits are the record index, always 0 while one BLAS holds the whole frame.
static const uint ENTITY_INSTANCE_TYPE_BIT = 0x800000u;

// Bits 24..31 of the per-vertex light word (CapturedVertexConsumer.setFlags).
// These are the two that change how a surface shades; the glowing and fire bits
// already reach the shader as the atlas texel's own emission code, and the End
// Portal flag drives a procedural starfield the Slang path does not have yet -
// such a part shades as its plain captured quad.
static const uint ENTITY_VERTEX_FLAG_OUTLINE = 0x08u;
static const uint ENTITY_VERTEX_FLAG_HURT    = 0x40u;

// The texel alpha bands of the RT entity atlas (EntityTextureAtlas).  A texel
// below the cutout edge is a hole in the model; one between the cutout edge and
// the first emission code is a real filter - stained glass, an ice block item;
// one at or above it is opaque and carries its emission in the low five bits of
// the same byte.
static const float ENTITY_ALPHA_HOLE = 0.1;
static const float ENTITY_ALPHA_EMISSION_CODE = 224.0 / 255.0;

// The fallback texel of an unfilled atlas slot: opaque white with emission code
// 0, so an untextured part still shades like vanilla's missing-texture quad.
static const float4 ENTITY_TEXEL_FALLBACK = float4(1.0, 1.0, 1.0, ENTITY_ALPHA_EMISSION_CODE);

// Vanilla writes block and sky light as 0..240 in one byte, not the 8..248 of a
// section vertex, so the divide is by 240 with no offset.
static const float ENTITY_LIGHT_SCALE = 1.0 / 240.0;

// The selection wireframe is drawn at a fixed brightness rather than lit, which
// is the only reason a part with no texture still shows up in a dark room.
static const float ENTITY_OUTLINE_EMISSION = 0.35;

// Vanilla draws a part whose vertex light is fullbright with its own pixels,
// unaffected by the room: the item of a glow item frame, a blaze, a magma cube.
static const float ENTITY_FULLBRIGHT_EMISSION = 0.4;

static const uint ENTITY_FLOATS_PER_VERTEX = 3u;
static const uint ENTITY_INDICES_PER_TRIANGLE = 3u;

// The RT entity atlas: the texel grid the captured parts wear, and the rect each
// slot occupies inside it.  Separate from the block atlas because a captured
// model has no miplevels and no sampler - the host packs one square ABGR grid
// (EntityTextureAtlas) and the texel address is integer arithmetic.
struct EntityAtlas {
    StructuredBuffer<uint4> rects;
    StructuredBuffer<uint> texels;
    uint atlasSize;   // texels per side; 0 while no atlas has been uploaded
};

struct EntityGeometry {
    StructuredBuffer<uint> positions;
    StructuredBuffer<uint> indices;
    StructuredBuffer<uint4> attrs;
    EntityAtlas atlas;
};

bool entityInstance(uint instanceId) {
    return (instanceId & ENTITY_INSTANCE_TYPE_BIT) != 0u;
}

// The index range bound for this frame is the frame's own triangles, so the
// bound length is the count and a pass that binds a one-element dummy reads
// zero.  Every entity primitive is a real triangle: the ring has no geometry
// groups, unlike the sections.
uint entityTriangleCount(EntityGeometry entities) {
    uint count;
    uint stride;
    entities.indices.GetDimensions(count, stride);
    return count / ENTITY_INDICES_PER_TRIANGLE;
}

uint3 entityTriangle(EntityGeometry entities, uint primitive) {
    uint base = primitive * ENTITY_INDICES_PER_TRIANGLE;
    return uint3(entities.indices[base], entities.indices[base + 1u], entities.indices[base + 2u]);
}

float3 entityPosition(EntityGeometry entities, uint vertex) {
    uint base = vertex * ENTITY_FLOATS_PER_VERTEX;
    return float3(asfloat(entities.positions[base]),
        asfloat(entities.positions[base + 1u]),
        asfloat(entities.positions[base + 2u]));
}

// Packed bytes, low first: the same order `unpackUnorm4x8` gives the legacy
// path, so a vertex colour and an atlas texel decode identically here.
float4 entityUnpackPacked(uint packed) {
    return float4(
        float(packed & 0xFFu),
        float((packed >> 8u) & 0xFFu),
        float((packed >> 16u) & 0xFFu),
        float((packed >> 24u) & 0xFFu)) / 255.0;
}

// The baked tint vanilla shaded the model with.  An outline part is drawn with
// no texture at all, so this is the only colour it has.
float4 entityVertexTint(EntityGeometry entities, uint3 vi, float3 barycentric) {
    float4 c0 = entityUnpackPacked(entities.attrs[vi.x].x);
    float4 c1 = entityUnpackPacked(entities.attrs[vi.y].x);
    float4 c2 = entityUnpackPacked(entities.attrs[vi.z].x);
    return c0 * barycentric.x + c1 * barycentric.y + c2 * barycentric.z;
}

uint entityAtlasSlot(EntityGeometry entities, uint3 vi) {
    return (entities.attrs[vi.x].y >> 8u) & 0xFFu;
}

uint entityVertexFlags(EntityGeometry entities, uint3 vi) {
    return (entities.attrs[vi.x].y >> 24u) & 0xFFu;
}

// Block and sky light of the first vertex.  Not interpolated: one face carries
// one light value and blending it across the triangle smears the edge between a
// lit and a shaded part of the model.
float2 entityLight(EntityGeometry entities, uint3 vi) {
    uint light = entities.attrs[vi.x].y;
    return clamp(float2(float(light & 0xFFu), float((light >> 16u) & 0xFFu)) * ENTITY_LIGHT_SCALE,
        float2(0.0), float2(1.0));
}

float4 entityTexel(EntityAtlas atlas, uint slot, float2 uv) {
    uint rectCount;
    uint rectStride;
    atlas.rects.GetDimensions(rectCount, rectStride);
    if (atlas.atlasSize == 0u || slot >= rectCount) return ENTITY_TEXEL_FALLBACK;

    uint4 rect = atlas.rects[slot];
    uint2 size = uint2(rect.z & 0xFFFFu, rect.w & 0xFFFFu);
    if (size.x == 0u || size.y == 0u) return ENTITY_TEXEL_FALLBACK;

    // The last texel of a row belongs to the next slot, so the coordinate
    // clamps below 1.0 rather than rounding up.
    uint2 texel = uint2(clamp(uv, float2(0.0), float2(0.99999)) * float2(size));
    uint index = (rect.y + texel.y) * atlas.atlasSize + rect.x + texel.x;
    return entityUnpackPacked(atlas.texels[index]);
}

// Authored PBR of one atlas slot, which is how a held iron sword comes out
// metallic and a dropped diamond comes out bright and smooth.  A slot without
// the authored bit is the matte dielectric vanilla draws with.
struct EntitySurfacePbr {
    float roughness;
    float metallic;
    float f0;
};

EntitySurfacePbr entitySurfacePbr(EntityAtlas atlas, uint slot) {
    EntitySurfacePbr pbr;
    pbr.roughness = 0.85;
    pbr.metallic = 0.0;
    pbr.f0 = 0.04;

    uint rectCount;
    uint rectStride;
    atlas.rects.GetDimensions(rectCount, rectStride);
    if (atlas.atlasSize == 0u || slot >= rectCount) return pbr;

    uint4 rect = atlas.rects[slot];
    if (((rect.w >> 24u) & 1u) != 0u) {
        pbr.roughness = float((rect.z >> 16u) & 0xFFu) / 255.0;
        pbr.metallic = float((rect.z >> 24u) & 0xFFu) / 255.0;
        pbr.f0 = float((rect.w >> 16u) & 0xFFu) / 255.0;
    }
    return pbr;
}

// Five bits of the alpha byte, in the range the atlas writes.
float entityTexelEmission(float4 texel) {
    float byteValue = texel.a * 255.0 + 0.5;
    return byteValue >= 224.0 ? float(uint(byteValue) & 31u) / 31.0 : 0.0;
}

bool entityTexelTranslucent(float4 texel) {
    float byteValue = texel.a * 255.0 + 0.5;
    return byteValue >= 26.0 && byteValue < 224.0;
}

// Decodes one committed entity triangle.  This is geometry, not shading: the
// texel stays unread and the surface layer decides whether it is a hole, a
// filter or an opaque part, exactly as it does for a section quad.  What the
// ring does add is a baked vertex colour and the two captured part flags, both
// of which are statements about the model rather than about the light.
//
// `positions` are camera-relative, so the world position is the hit plus the
// anchor the ring was written around.  It is a procedural anchor only: entity
// geometry has no section identity and must not key history or emitter identity
// through it.
SurfaceHit entityHitToSurface(EntityGeometry entities, float3 cameraAnchor, Ray ray,
        uint instanceId, uint primitive, float2 bary, float t) {
    SurfaceHit result = missHit(ray.tMax);
    if (primitive >= entityTriangleCount(entities)) return result;

    uint3 vi = entityTriangle(entities, primitive);
    float3 p0 = entityPosition(entities, vi.x);
    float3 p1 = entityPosition(entities, vi.y);
    float3 p2 = entityPosition(entities, vi.z);
    float4 a0 = asfloat(entities.attrs[vi.x]);
    float4 a1 = asfloat(entities.attrs[vi.y]);
    float4 a2 = asfloat(entities.attrs[vi.z]);

    float3 weights = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
    float3 geometricNormal = safeNormalize(cross(p1 - p0, p2 - p0), -ray.direction);
    // A captured model is not consistently wound and is built without culling,
    // so the face the ray came from is the surface the player saw.
    if (dot(geometricNormal, ray.direction) > 0.0) geometricNormal = -geometricNormal;

    float2 uv = a0.zw * weights.x + a1.zw * weights.y + a2.zw * weights.z;
    float3 tangent;
    float3 bitangent;
    triangleTangentFrame(p0, p1, p2, a0.zw, a1.zw, a2.zw, geometricNormal, tangent, bitangent);

    uint vertexFlags = entityVertexFlags(entities, vi);

    result.hit = true;
    result.kind = HitKind.Entity;
    result.t = t;
    result.position = rayAt(ray, t);
    result.worldPosition = result.position + cameraAnchor;
    result.geometricNormal = geometricNormal;
    result.shadingNormal = geometricNormal;
    result.tangent = tangent;
    result.bitangent = bitangent;
    result.uv = uv;
    result.barycentrics = bary;
    result.light = entityLight(entities, vi);
    result.tint = entityVertexTint(entities, vi, weights).rgb;
    result.instanceId = instanceId;
    result.primitiveId = primitive;
    // One instance holds the frame, so there is no stable per-section identity;
    // the primitive is the only address a consumer can key on.
    result.sectionId = SECTION_INDEX_NONE;
    // The atlas slot is what a texel lookup needs, and it is the closest thing
    // an entity part has to a material id.
    result.materialId = entityAtlasSlot(entities, vi);
    result.flags = HIT_FLAG_ENTITY | HIT_FLAG_TANGENT_VALID;
    if ((vertexFlags & ENTITY_VERTEX_FLAG_OUTLINE) != 0u) result.flags |= HIT_FLAG_ENTITY_OUTLINE;
    if ((vertexFlags & ENTITY_VERTEX_FLAG_HURT) != 0u) result.flags |= HIT_FLAG_ENTITY_HURT;
    return result;
}
