// Geometry contract for Bedray 2.0.
//
// Two independent representations share one module:
//
//  * VoxelSection   — Bitmask HDDA input.  A 16^3 Minecraft section is split
//                     into 64 macro cells of 4^3 voxels; one uint64_t mask per
//                     macro cell.  A block edit flips one bit and never
//                     rebuilds a mesh or a distance field.
//  * GeometrySection — triangle input for hardware/hybrid traversal.  Primitive
//                     ranges and vertex attributes are explicit instead of the
//                     legacy packed quadStart/origin.w encoding.
//
// Nothing here owns a descriptor or a device address.  Buffers are supplied by
// the traversal backend, so the same contract works for the voxel provider and
// for the main renderer.

public module geometry_contract;

// ---- Voxel geometry ----

static const int VOXEL_SECTION_EDGE = 16;
static const int VOXEL_MACRO_EDGE = 4;
static const int VOXEL_MACRO_AXIS = VOXEL_SECTION_EDGE / VOXEL_MACRO_EDGE;   // 4
static const int VOXEL_MACRO_COUNT = VOXEL_MACRO_AXIS * VOXEL_MACRO_AXIS * VOXEL_MACRO_AXIS; // 64
static const int VOXEL_BLOCK_COUNT = VOXEL_SECTION_EDGE * VOXEL_SECTION_EDGE * VOXEL_SECTION_EDGE;

static const uint SECTION_INDEX_NONE = 0xFFFFFFFFu;

// Flags describe how the host treats a section, not how traversal walks it.
static const uint VOXEL_SECTION_FLAG_RESIDENT     = 1u << 0u;
static const uint VOXEL_SECTION_FLAG_EMPTY        = 1u << 1u;
static const uint VOXEL_SECTION_FLAG_HAS_ENTITIES = 1u << 2u;
static const uint VOXEL_SECTION_FLAG_DIRTY        = 1u << 3u;

// Per-voxel shading record.  Occupancy says *whether* a voxel is solid; this
// record says *what* it is.  The host packs one record per resident section
// and appends it at `VoxelSection.meta.y`; a block edit updates occupancy and
// this record together, without touching a mesh.
struct VoxelBlock {
    uint materialId;
    uint flags;
    uint packedLight;   // x: block light 0..15, y: sky light 0..15
    uint packedTint;    // RGBA8, same packing as the raster vertex colour
};

static const uint VOXEL_BLOCK_FLAG_EMISSIVE = 1u << 0u;
static const uint VOXEL_BLOCK_FLAG_FOLIAGE  = 1u << 1u;
static const uint VOXEL_BLOCK_FLAG_FLUID    = 1u << 2u;

struct VoxelSection {
    // x + 4 * y + 16 * z selects a macro cell; within one mask the same
    // packing selects its voxel.  The upload path may therefore update a
    // single bit for an individual Minecraft block mutation.
    uint64_t occupancy[VOXEL_MACRO_COUNT];

    int4  origin;   // xyz: section origin in blocks, w: VOXEL_SECTION_FLAG_*
    uint4 meta;     // x: sectionId, y: block data offset in VoxelBlock units,
                    // z: occupied macro cells, w: revision
};

// One slot of the section hash directory.  A zero sectionIndex is *not* an
// empty marker: section 0 is valid, so SECTION_INDEX_NONE is used instead.
struct SectionLookupEntry {
    int3 key;           // section coordinate, not block coordinate
    uint sectionIndex;
};

// Sparse grid description.  A dense world sets hashMask = 0 and uses
// sectionIndex = x + dimX * (y + dimY * z); a streamed world sets a power-of-two
// hashMask and hashProbeCount > 0.
struct VoxelGrid {
    int4  originSection;   // xyz: minimum resident section, w: hashMask
    uint4 dimensions;      // xyz: resident section counts, w: hashProbeCount
    uint4 stats;           // x: resident sections, y: capacity,
                           // z: revision, w: flags
};

int voxelMacroIndex(int3 macro) {
    return macro.x + VOXEL_MACRO_AXIS * (macro.y + VOXEL_MACRO_AXIS * macro.z);
}

int voxelBitIndex(int3 local) {
    return local.x + VOXEL_MACRO_EDGE * (local.y + VOXEL_MACRO_EDGE * local.z);
}

int voxelLocalBlockIndex(int3 local) {
    return local.x + VOXEL_SECTION_EDGE * (local.y + VOXEL_SECTION_EDGE * local.z);
}

int voxelBlockIndex(VoxelSection section, int3 local) {
    return int(section.meta.y) + voxelLocalBlockIndex(local);
}

bool voxelSectionContains(VoxelSection section, int3 block) {
    int3 local = block - section.origin.xyz;
    return all(local >= int3(0)) && all(local < int3(VOXEL_SECTION_EDGE));
}

bool voxelOccupied(VoxelSection section, int3 localBlock) {
    int3 macro = localBlock / VOXEL_MACRO_EDGE;
    int3 local = localBlock - macro * VOXEL_MACRO_EDGE;
    uint64_t mask = section.occupancy[voxelMacroIndex(macro)];
    return (mask & (uint64_t(1) << uint64_t(voxelBitIndex(local)))) != uint64_t(0);
}

// Occupancy test when the macro cell is already known.  This is the hot path of
// the inner DDA: it avoids recomputing the macro index from a block coordinate.
bool voxelOccupiedInMacro(VoxelSection section, int3 macro, int3 local) {
    uint64_t mask = section.occupancy[voxelMacroIndex(macro)];
    return (mask & (uint64_t(1) << uint64_t(voxelBitIndex(local)))) != uint64_t(0);
}

bool voxelMacroOccupied(VoxelSection section, int3 macro) {
    return section.occupancy[voxelMacroIndex(macro)] != uint64_t(0);
}

VoxelBlock voxelBlockAt(StructuredBuffer<VoxelBlock> blocks, VoxelSection section, int3 local) {
    return blocks[voxelBlockIndex(section, local)];
}

uint voxelBlockLight(VoxelBlock block) {
    return block.packedLight & 0xFFu;
}

uint voxelSkyLight(VoxelBlock block) {
    return (block.packedLight >> 8u) & 0xFFu;
}

float3 voxelTint(VoxelBlock block) {
    uint packed = block.packedTint;
    return float3(float(packed & 0xFFu), float((packed >> 8u) & 0xFFu),
                  float((packed >> 16u) & 0xFFu)) * (1.0 / 255.0);
}

// Stable 32-bit hash for section-coordinate lookup.  The same function is used
// by the host when it builds the directory, so a section never needs a search.
uint voxelSectionHash(int3 key) {
    uint h = uint(key.x) * 0x8da6b343u ^
             uint(key.y) * 0xd8163841u ^
             uint(key.z) * 0xcb1ab31fu;
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    return h;
}

bool voxelGridContains(VoxelGrid grid, int3 sectionCoordinate) {
    if (grid.dimensions.x == 0u || grid.dimensions.y == 0u || grid.dimensions.z == 0u) return true;
    int3 local = sectionCoordinate - grid.originSection.xyz;
    return all(local >= int3(0)) &&
           local.x < int(grid.dimensions.x) &&
           local.y < int(grid.dimensions.y) &&
           local.z < int(grid.dimensions.z);
}

// ---- Triangle geometry ----

enum GeometryPass : uint {
    Solid       = 0u,   // no alpha test
    Cutout      = 1u,   // alpha-tested foliage / plants
    Translucent = 2u,   // glass, water, ice
};

static const uint MAX_SECTION_GEOMETRIES = 3u;

// One vertex of the triangle stream.  Positions are section-local blocks; UVs
// point into the block atlas; tint and light are the values the raster path
// would have written to the vertex colour and light buffers.
struct GeometryVertex {
    float4 position;    // xyz: section-local position, w: reserved
    float4 uvLight;     // xy: atlas UV, zw: block light / sky light (0..1)
    float4 tint;        // rgb: biome tint * baked AO, a: reserved
    uint4  meta;        // x: materialId, y: flags, z: reserved, w: reserved
};

struct GeometryRange {
    uint firstQuad;     // first quad in the section's vertex stream
    uint quadCount;     // number of quads in this pass
    GeometryPass pass;
    uint flags;
};

struct GeometrySection {
    int4  origin;          // xyz: section origin in blocks, w: sectionId
    uint4 meta;            // x: vertexOffset, y: vertexCount,
                           // z: geometryCount, w: flags
    GeometryRange ranges[MAX_SECTION_GEOMETRIES];
};

// A TLAS instance points at one GeometrySection.  The mask is stored with the
// instance so hybrid traversal can exclude entity-only geometry from world
// queries without a separate list.
struct GeometryInstance {
    uint sectionIndex;
    uint instanceId;
    uint mask;
    uint flags;
};

// Returns -1 when the primitive does not belong to any declared range.  Ranges
// may be non-contiguous; the loop is three iterations at most.
int geometryRangeForPrimitive(GeometrySection section, uint primitive) {
    uint quad = primitive >> 1u;
    uint count = min(section.meta.z, MAX_SECTION_GEOMETRIES);
    for (uint g = 0u; g < count; ++g) {
        GeometryRange range = section.ranges[g];
        if (quad >= range.firstQuad && quad < range.firstQuad + range.quadCount) {
            return int(g);
        }
    }
    return -1;
}

uint geometryVertexBase(GeometrySection section, uint primitive) {
    int rangeIndex = geometryRangeForPrimitive(section, primitive);
    if (rangeIndex < 0) return 0u;
    GeometryRange range = section.ranges[uint(rangeIndex)];
    return section.meta.x + (range.firstQuad + (primitive >> 1u)) * 4u;
}

// Triangle `primitive` is one half of a quad.  The vertex order matches the
// legacy triangleVertices() helper so atlas UVs and winding are unchanged.
uint3 geometryTriangleVertices(GeometrySection section, uint primitive) {
    uint base = geometryVertexBase(section, primitive);
    bool second = (primitive & 1u) != 0u;
    return uint3(base + (second ? 2u : 0u),
                 base + (second ? 3u : 1u),
                 base + (second ? 0u : 2u));
}

GeometryVertex geometryVertexAt(StructuredBuffer<GeometryVertex> vertices,
        GeometrySection section, uint primitive, uint corner) {
    uint3 triangle = geometryTriangleVertices(section, primitive);
    uint index = corner == 0u ? triangle.x : (corner == 1u ? triangle.y : triangle.z);
    return vertices[index];
}
