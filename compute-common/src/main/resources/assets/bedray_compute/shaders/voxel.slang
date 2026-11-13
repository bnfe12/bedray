// Vulkan 1.0 compute only; no ray queries, acceleration structures or device addresses.
// Slang port of voxel.comp. Build: slangc -target spirv -profile spirv_1_0 -emit-spirv-via-glsl.
// Slang emits row_major uniforms over the host's column-major bytes; GLSL M*v becomes mul(M,v).

struct Diagnostic {
    float4x4 inverseViewProjection;
    float4 cameraOrigin;
    // x: IDs=1, live=2, sRGB destination=4, world light=8, sRGB raster view=16, history parity=32.
    // y: debug view. z: frame counter (random sequence). w: reserved.
    uint4 control;
    float4 rayOriginOffset;
};
[[vk::push_constant]] ConstantBuffer<Diagnostic> diagnostic;

[[vk::binding(0,0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> outputImage;
// Live textured grids append one ARGB cell tint per cell after the occupancy cells (index + dimensions.w).
[[vk::binding(1,0)]] StructuredBuffer<uint> grid;
uint4 gridDimensions() { return uint4(grid[0], grid[1], grid[2], grid[3]); }
uint gridCell(uint i) { return grid[4u + i]; }

#ifdef TEXTURED_CUBES
// flags bit0: multiply by the cell tint (API minor 5).
struct CubeFace { float4 originDu; float2 dv; uint tint; uint flags; };
[[vk::binding(2,0)]] StructuredBuffer<CubeFace> palette;
uint paletteCount() { uint n, s; palette.GetDimensions(n, s); return n; }
[[vk::binding(3,0)]] Sampler2D<float4> blockAtlas;
// 96-byte ABI: twenty interleaved xyzuv floats, tint/flags/emission/reserved.
// flags: 1 cutout, 2 translucent, 4 cell tint, 8 fluid surface.
struct ModelQuad { float4 vertices[5]; uint4 metadata; };
// 27*128 headers occupy 55,296 bytes, followed by 27 fixed 8192-quad slabs.
[[vk::binding(4,0)]] ByteAddressBuffer models;
uint4 modelHeader(uint i) { return models.Load<uint4>(i * 16u); }
uint modelQuadCount() { uint n; models.GetDimensions(n); return (n - 55296u) / 96u; }
ModelQuad loadModelQuad(uint addr) {
    ModelQuad q;
    uint base = 55296u + addr * 96u;
    [unroll] for (int i = 0; i < 5; i++) q.vertices[i] = asfloat(models.Load<uint4>(base + uint(i) * 16u));
    q.metadata = models.Load<uint4>(base + 80u);
    return q;
}
static const uint MAX_EMITTERS = 128u;
// values: LightingFrame (key dir/enabled, key RGB/sky factor, sky RGB/ambient factor, block RGB/factor,
// ambient RGB/brightness, darkness/night vision/boss darkening). info: emitters, history valid, history cap, motion.
struct Environment {
    float4 values[6];
    float4x4 previousViewProjection;
    float4 previousCamera;
    float4 previousEye;
    uint4 info;
    float4 emitters[2u * MAX_EMITTERS]; // center xyz + equal-area radius; level, cell xyz
};
[[vk::binding(5,0)]] StructuredBuffer<Environment> environment;
#endif

#ifdef RASTER_INPUTS
[[vk::binding(6,0)]] Sampler2D<float4> rasterColor;
[[vk::binding(7,0)]] Sampler2D<float4> rasterDepth;
// Ping-pong history: half rgb radiance + sample count, hit distance, octahedral normal.
[[vk::binding(8,0)]] [[vk::image_format("rgba32ui")]] RWTexture2D<uint4> historyA;
[[vk::binding(9,0)]] [[vk::image_format("rgba32ui")]] RWTexture2D<uint4> historyB;
#endif

#ifdef GPU_COUNTERS
// Debug-only history counters (-Dbedray.compute.gpuCounters); control.w is this frame's slot offset.
// 0 accepted, 1 distance, 2 normal, 3 empty, 4 bounds, 5 invalid reprojection, 6 NaN radiance, 7 low weight.
[[vk::binding(10,0)]] RWStructuredBuffer<uint> historyCounters;
void countHistory(uint index) { InterlockedAdd(historyCounters[diagnostic.control.w + index], 1u); }
#endif

static const uint FLAG_CUTOUT = 1u;
static const uint FLAG_TRANSLUCENT = 2u;
static const uint FLAG_CELL_TINT = 4u;
static const uint FLAG_FLUID = 8u;
static const uint FLAG_NO_MATERIAL = 16u;

struct Hit {
    bool found; int3 cell; uint material; float t; float3 normal;
    float3 albedo; uint flags; float emission; float alpha;
};
Hit miss() { return Hit(false, int3(0,0,0), 0u, 0.0, float3(0), float3(0), 0u, 0.0, 0.0); }

uint cellIndex(int3 cell) {
    int3 size = int3(gridDimensions().xyz);
    if (any(cell < int3(0,0,0)) || any(cell >= size)) return 0xffffffffu;
    uint index = uint(cell.x + size.x*(cell.y + size.y*cell.z));
    if ((diagnostic.control.x & 2u) != 0u) {
        int3 tile = cell/16;
        int3 local = cell%16;
        index = uint(tile.x + 3*(tile.y + 3*tile.z))*4096u + uint(local.x + 16*(local.y + 16*local.z));
    }
    return index < gridDimensions().w ? index : 0xffffffffu;
}
uint cellValue(int3 cell) {
    uint index = cellIndex(cell);
    return index == 0xffffffffu ? 0xffffffffu : gridCell(index);
}

#ifdef TEXTURED_CUBES
static const float PI = 3.14159265358979;
// Mean angular radius of the Sun (0.2666 degrees); the Moon's is within two percent of it.
static const float KEY_ANGULAR_RADIUS = 0.004653;
// A level-15 sphere emitter at this distance contributes below 1/1000 of its 1-block irradiance.
static const float EMITTER_RANGE = 32.0;
// Water's refractive index at visible wavelengths; other translucent surfaces use crown glass.
static const float WATER_IOR = 1.333;
static const float GLASS_IOR = 1.5;
float3 srgbToLinear(float3 value) {
    return lerp(value/12.92, pow((value+0.055)/1.055, float3(2.4)), float3(value > float3(0.04045)));
}
float3 linearToSrgb(float3 value) {
    return lerp(value*12.92, 1.055*pow(value, float3(1.0/2.4))-0.055, float3(value > float3(0.0031308)));
}
float3 tintColor(uint tint) {
    return srgbToLinear(float3(float((tint>>16)&255u), float((tint>>8)&255u), float(tint&255u))/255.0);
}
float3 cellTint(int3 cell) {
    uint index = cellIndex(cell);
    if (index == 0xffffffffu) return float3(1);
    uint value = gridCell(gridDimensions().w + index);
    return value == 0u ? float3(1) : tintColor(value);
}
float quadComponent(ModelQuad quad, int index) { return quad.vertices[index/4][index%4]; }
float3 vertexPosition(ModelQuad quad, int vertex) {
    int i = vertex*5;
    return float3(quadComponent(quad,i), quadComponent(quad,i+1), quadComponent(quad,i+2));
}
float2 vertexUv(ModelQuad quad, int vertex) { return float2(quadComponent(quad,vertex*5+3), quadComponent(quad,vertex*5+4)); }
bool triangle(float3 origin, float3 ray, float3 a, float3 b, float3 c, float2 ta, float2 tb, float2 tc,
              float minT, float maxT, out float t, out float2 uv, out float3 normal) {
    float3 e1 = b-a, e2 = c-a, p = cross(ray,e2);
    float determinant = dot(e1,p);
    if (abs(determinant) < 1e-9) return false;
    float inverse = 1.0/determinant;
    float3 s = origin-a, q = cross(s,e1);
    float u = dot(s,p)*inverse, v = dot(ray,q)*inverse;
    if (u < -1e-6 || v < -1e-6 || u+v > 1.000001) return false;
    t = dot(e2,q)*inverse;
    // Preserve geometry exactly on a DDA cell boundary despite floating-point roundoff.
    if (t < max(0.0,minT-0.0002) || t > maxT+0.0002) return false;
    normal = cross(e1,e2);
    float square = dot(normal,normal);
    if (square < 1e-16) return false;
    normal *= rsqrt(square);
    if (dot(normal,ray) > 0.0) normal = -normal;
    uv = ta+(tb-ta)*u+(tc-ta)*v;
    return true;
}
Hit modelHit(float3 origin, float3 ray, int3 cell, uint material, float minT, float maxT) {
    uint reference = (material>>8)&255u;
    if (reference == 0u || reference > 128u) return miss();
    int3 tile = cell/16;
    uint slot = uint(tile.x + 3*(tile.y + 3*tile.z));
    uint4 header = modelHeader(slot*128u + reference - 1u);
    if (header.y == 0u || header.y > 64u || header.x + header.y > 8192u) return miss();
    Hit best = miss();
    float nearest = maxT;
    for (uint index = 0u; index < header.y; index++) {
        uint address = slot*8192u + header.x + index;
        if (address >= modelQuadCount()) return miss();
        ModelQuad quad = loadModelQuad(address);
        float3 a = vertexPosition(quad,0), b = vertexPosition(quad,1), c = vertexPosition(quad,2), d = vertexPosition(quad,3);
        float2 ta = vertexUv(quad,0), tb = vertexUv(quad,1), tc = vertexUv(quad,2), td = vertexUv(quad,3);
        float2 low = min(min(ta,tb), min(tc,td)), high = max(max(ta,tb), max(tc,td));
        uint aw, ah; blockAtlas.GetDimensions(aw, ah);
        float2 inset = min(float2(0.5)/float2(aw,ah), (high-low)*0.5);
        for (int part = 0; part < 2; part++) {
            float t; float2 uv; float3 normal;
            bool accepted = part == 0
                ? triangle(origin-float3(cell), ray, a, b, c, ta, tb, tc, minT, maxT, t, uv, normal)
                : triangle(origin-float3(cell), ray, c, d, a, tc, td, ta, minT, maxT, t, uv, normal);
            if (!accepted || (best.found && t > nearest+0.00001)) continue;
            float4 texel = blockAtlas.SampleLevel(clamp(uv, low+inset, high-inset), 0.0);
            // Test each triangle's alpha before nearest selection, including nonplanar quads.
            // Later accepted coplanar overlays win; transparent overlays leave the base intact.
            if (texel.a < 0.1) continue;
            float3 tint = tintColor(quad.metadata.x);
            if ((quad.metadata.y & FLAG_CELL_TINT) != 0u) tint *= cellTint(cell);
            best = Hit(true, cell, material, max(0.0,t), normal, srgbToLinear(texel.rgb)*tint,
                quad.metadata.y, float(quad.metadata.z)/15.0, texel.a);
            nearest = t;
        }
    }
    return best;
}
#endif

Hit trace(float3 origin, float3 direction, float minT, float maxT) {
    int3 size = int3(gridDimensions().xyz);
    float enter = -3.402823466e38, leave = maxT;
    float3 enterNormal = float3(0);
    for (int a = 0; a < 3; a++) {
        if (direction[a] == 0.0) {
            if (origin[a] < 0.0 || origin[a] >= float(size[a])) return miss();
        } else {
            float t0 = -origin[a]/direction[a], t1 = (float(size[a])-origin[a])/direction[a];
            float nearT = min(t0,t1), farT = max(t0,t1);
            if (nearT > enter) {
                enter = nearT; enterNormal = float3(0); enterNormal[a] = direction[a] > 0.0 ? -1.0 : 1.0;
            }
            leave = min(leave, farT);
        }
    }
    float t = max(minT, enter);
    if (t >= leave) return miss();
    float3 p = fma(float3(t), direction, origin);
    int3 cell = int3(floor(p));
    int3 stepDirection = int3(sign(direction));
    float3 nextT = float3(3.402823466e38);
    float3 delta = float3(3.402823466e38);
    for (int a = 0; a < 3; a++) {
        if (direction[a] < 0.0 && p[a] == floor(p[a])) cell[a]--;
        cell[a] = clamp(cell[a], 0, size[a]-1);
        if (direction[a] != 0.0) {
            nextT[a] = (float(cell[a] + (direction[a] > 0.0 ? 1 : 0)) - origin[a])/direction[a];
            delta[a] = abs(1.0/direction[a]);
        }
    }
    float3 normal = enter < minT ? float3(0) : enterNormal;
    int limit = size.x + size.y + size.z + 1;
    for (int iteration = 0; iteration < limit; iteration++) {
        if (any(cell < int3(0,0,0)) || any(cell >= size) || t >= leave) break;
        float boundary = min(nextT.x, min(nextT.y, nextT.z));
        uint material = cellValue(cell);
        bool live = (diagnostic.control.x & 2u) != 0u;
        if (live) {
            uint occupancy = material & 255u;
            if (diagnostic.control.y == 3u) {
                uint marker = material == 0xffffffffu ? 4u : occupancy;
                if (marker != 0u && t < min(boundary, leave)) return Hit(true, cell, marker, t, normal, float3(0), 0u, 0.0, 1.0);
            } else if (material != 0xffffffffu && occupancy == 1u && t < min(boundary, leave)) {
                // A cube without a palette entry is a real occluder but has no material to shade.
                uint flags = ((material>>8)&255u) == 0u ? FLAG_NO_MATERIAL : 0u;
                return Hit(true, cell, material, t, normal, float3(0.65), flags, float((material>>24)&15u)/15.0, 1.0);
#ifdef TEXTURED_CUBES
            } else if (material != 0xffffffffu && occupancy == 3u) {
                Hit candidate = modelHit(origin, direction, cell, material, t, min(boundary, leave));
                if (candidate.found) return candidate;
#endif
            }
        } else if (material != 0u && material != 0xffffffffu && t < min(boundary, leave))
            return Hit(true, cell, material, t, normal, float3(0), 0u, 0.0, 1.0);
        if (boundary >= leave) break;
        bool3 crossed = (nextT == float3(boundary));
        for (int a = 0; a < 3; a++) if (crossed[a]) { cell[a] += stepDirection[a]; nextT[a] += delta[a]; }
        // An exact edge/corner crossing enters through several faces at once. Report a face whose
        // neighbor outside the entered cell is not an opaque cube, so a floor seen exactly at a cell
        // edge keeps its top face instead of a hidden side face.
        normal = float3(0); int fallback = -1; bool selected = false;
        for (int a = 0; a < 3; a++) if (crossed[a]) {
            if (fallback < 0) fallback = a;
            int3 outside = cell; outside[a] -= stepDirection[a];
            uint neighbor = cellValue(outside);
            if (!selected && (neighbor == 0xffffffffu || (neighbor & 255u) != 1u)) { normal[a] = -float(stepDirection[a]); selected = true; }
        }
        if (!selected && fallback >= 0) normal[fallback] = -float(stepDirection[fallback]);
        t = max(t, boundary);
    }
    return miss();
}

float3 materialColor(uint id) {
    if (id == 1u) return float3(0.42,0.52,0.30);
    if (id == 2u) return float3(0.63,0.32,0.18);
    if (id == 3u) return float3(0.52,0.57,0.62);
    return float3(0.95,0.66,0.22);
}

#ifdef TEXTURED_CUBES
// ---- Sampling ----
uint pcg(uint value) {
    uint state = value*747796405u + 2891336453u;
    uint word = ((state>>((state>>28u)+4u))^state)*277803737u;
    return (word>>22u)^word;
}
float random(inout uint seed) { seed = pcg(seed); return float(seed>>8)*(1.0/16777216.0); }
void basis(float3 n, out float3 t, out float3 b) {
    float s = n.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0/(s+n.z), c = n.x*n.y*a;
    t = float3(1.0+s*n.x*n.x*a, s*c, -s*n.x);
    b = float3(c, s+n.y*n.y*a, -n.y);
}
// Cosine-weighted hemisphere direction; pdf = cos(theta)/pi.
float3 cosineSample(float3 n, inout uint seed) {
    float u = random(seed), v = random(seed);
    float r = sqrt(u), phi = 2.0*PI*v;
    float3 t, b; basis(n, t, b);
    return normalize(t*(r*cos(phi)) + b*(r*sin(phi)) + n*sqrt(max(0.0,1.0-u)));
}
// Uniform direction inside a cone; pdf = 1/(2*pi*(1-cosMax)).
float3 coneSample(float3 axis, float cosMax, inout uint seed) {
    float u = random(seed), v = random(seed);
    float c = 1.0-u*(1.0-cosMax), s = sqrt(max(0.0,1.0-c*c)), phi = 2.0*PI*v;
    float3 t, b; basis(axis, t, b);
    return normalize(t*(s*cos(phi)) + b*(s*sin(phi)) + axis*c);
}
// ---- Materials and environment ----
float3 cubeAlbedo(Hit hit, float3 point) {
    uint reference = (hit.material>>8)&255u;
    if (reference == 0u || reference > 128u || dot(hit.normal,hit.normal) < 0.5) return float3(0.65);
    float3 local = clamp(point-float3(hit.cell), float3(0), float3(1));
    uint face; float2 coordinates;
    if (hit.normal.y != 0.0) { face = hit.normal.y < 0.0 ? 0u : 1u; coordinates = local.xz; }
    else if (hit.normal.z != 0.0) { face = hit.normal.z < 0.0 ? 2u : 3u; coordinates = local.xy; }
    else { face = hit.normal.x < 0.0 ? 4u : 5u; coordinates = local.zy; }
    int3 tile = hit.cell/16;
    uint slot = uint(tile.x + 3*(tile.y + 3*tile.z));
    uint index = (slot*128u + reference - 1u)*6u + face;
    if (index >= paletteCount()) return float3(0.65);
    CubeFace surface = palette[index];
    float2 base = surface.originDu.xy, du = surface.originDu.zw;
    float2 low = min(min(base,base+du), min(base+surface.dv, base+du+surface.dv));
    float2 high = max(max(base,base+du), max(base+surface.dv, base+du+surface.dv));
    uint aw, ah; blockAtlas.GetDimensions(aw, ah);
    float2 inset = min(float2(0.5)/float2(aw,ah), (high-low)*0.5);
    float2 uv = clamp(base + du*coordinates.x + surface.dv*coordinates.y, low+inset, high-inset);
    float3 color = srgbToLinear(blockAtlas.SampleLevel(uv, 0.0).rgb)*tintColor(surface.tint);
    if ((surface.flags & 1u) != 0u) color *= cellTint(hit.cell);
    return color;
}
float3 hitAlbedo(Hit hit, float3 point) { return (hit.material & 255u) == 3u ? hit.albedo : cubeAlbedo(hit, point); }
// Vanilla's light-level curve: response(15)=1, response(0)=0.
float lightResponse(float level) { float value = clamp(level,0.0,15.0)/15.0; return value/(4.0-3.0*value); }
// Display sky color decoded to linear, used as uniform sky radiance (pi-normalized irradiance units).
float3 skyRadiance() { return environment[0].values[2].rgb*(environment[0].values[2].w*environment[0].values[1].w); }
float3 blockColor() { return environment[0].values[3].rgb*environment[0].values[3].w; }
struct CellLight { bool available; float block; float sky; int3 cell; };
// Incident light lies outside the visible surface, not inside an opaque cube.
CellLight lightOutside(float3 point, float3 normal) {
    int3 cell = int3(floor(point + normal*0.003));
    uint light = cellValue(cell);
    bool available = light != 0xffffffffu && (light & (1u<<28)) != 0u;
    return CellLight(available, available ? float((light>>16)&15u) : 0.0, available ? float((light>>20)&15u) : 0.0, cell);
}
uint emissionLevel(Hit hit) { return max(uint(hit.emission*15.0+0.5), (hit.material>>24)&15u); }
// Opaque blocker search; translucent layers filter by their coverage-weighted color.
Hit traceOpaque(float3 origin, float3 direction, float maxT, out float3 filterColor) {
    filterColor = float3(1);
    float nearT = 0.001;
    for (int layer = 0; layer < 8; layer++) {
        Hit blocker = trace(origin, direction, nearT, maxT);
        if (!blocker.found || (blocker.flags & FLAG_TRANSLUCENT) == 0u) return blocker;
        filterColor *= lerp(float3(1), blocker.albedo, blocker.alpha);
        nearT = max(nearT, blocker.t) + 0.0005;
    }
    filterColor = float3(0); // explicit layer budget: beyond it nothing is transmitted
    return miss();
}
// Direct Sun/Moon irradiance from one cone sample. Rays leaving the bounded grid are scaled by the
// vanilla sky-light response, which removes the key light in caves deeper than the grid.
float3 keyIrradiance(float3 point, float3 normal, float skyResponse, inout uint seed) {
    float3 key = environment[0].values[0].xyz;
    float square = dot(key,key);
    if (environment[0].values[0].w <= 0.0 || square < 1e-8 || skyResponse <= 0.0) return float3(0);
    key *= rsqrt(square);
    float3 direction = coneSample(key, cos(KEY_ANGULAR_RADIUS), seed);
    float cosine = dot(normal,direction);
    if (cosine <= 0.0) return float3(0);
    float3 filterColor;
    Hit blocker = traceOpaque(point + normal*0.003, direction, 100.0, filterColor);
    if (blocker.found) return float3(0);
    return environment[0].values[1].rgb*(environment[0].values[1].w*skyResponse*cosine)*filterColor;
}
// Irradiance at a secondary point without further tracing except one key-light shadow ray:
// vanilla sky and block light stand in for their own visibility there.
float3 secondaryIrradiance(float3 point, float3 normal, inout uint seed) {
    CellLight light = lightOutside(point, normal);
    float sky = lightResponse(light.sky);
    return environment[0].values[4].rgb + skyRadiance()*sky + blockColor()*lightResponse(light.block)
        + keyIrradiance(point, normal, sky, seed);
}
// Radiance arriving along a secondary ray (diffuse bounce, reflection, transmission).
float3 secondaryRadiance(float3 origin, float3 direction, float originSky, inout uint seed, out bool escaped, out float distance) {
    float3 filterColor;
    Hit hit = traceOpaque(origin, direction, 96.0, filterColor);
    escaped = !hit.found;
    distance = hit.found ? hit.t : 96.0;
    if (!hit.found) return skyRadiance()*originSky*filterColor;
    if ((hit.flags & FLAG_NO_MATERIAL) != 0u) return float3(0);
    float3 point = origin + hit.t*direction;
    float3 albedo = hitAlbedo(hit, point);
    float3 emitted = blockColor()*lightResponse(float(emissionLevel(hit)));
    return filterColor*albedo*(secondaryIrradiance(point, hit.normal, seed) + emitted);
}
// Block-light irradiance: next-event estimation over the bounded emitter list, sampled by reservoir
// in proportion to each sphere's unshadowed irradiance bound, plus vanilla light that no listed
// emitter can explain. Vanilla light from one source of level e at Manhattan distance d is at most
// e-d (open-air flood fill); if the vanilla level exceeds that bound for every listed emitter, an
// unlisted source (outside the grid or beyond the list) lights the point and its vanilla value is added.
// Radiance mapping: a level-e emitter delivers vanilla's response(e-1) at one block, i.e. a sphere of
// radius r has radiance response(e-1)/r^2 and the far-field irradiance falls off as 1/d^2.
float3 blockIrradiance(float3 point, float3 normal, CellLight light, int3 selfCell, inout uint seed) {
    uint count = min(environment[0].info.x, MAX_EMITTERS);
    float explained = 0.0, total = 0.0;
    int chosen = -1;
    float chosenCosine = 0.0;
    for (uint i = 0u; i < count; i++) {
        float4 sphere = environment[0].emitters[2u*i];
        float4 meta = environment[0].emitters[2u*i+1u];
        int3 cell = int3(meta.yzw);
        int3 manhattan = abs(cell - light.cell);
        explained = max(explained, meta.x - float(manhattan.x + manhattan.y + manhattan.z));
        if (all(cell == selfCell)) continue;
        float3 toward = sphere.xyz - point;
        float square = dot(toward,toward), radius = sphere.w;
        if (square > (EMITTER_RANGE+radius)*(EMITTER_RANGE+radius)) continue;
        float distance = sqrt(max(square,1e-8));
        float sinSquare = min(1.0, radius*radius/max(square,1e-8));
        float cosMax = sqrt(1.0-sinSquare);
        // Largest cosine toward any point of the sphere: the weight never under-covers the integrand.
        float bound = clamp(dot(normal,toward)/distance + sqrt(sinSquare), 0.0, 1.0);
        if (bound <= 0.0) continue;
        float omega = 2.0*PI*(1.0-cosMax);
        float weight = lightResponse(meta.x-1.0)/(radius*radius)*omega/PI*bound;
        total += weight;
        if (random(seed)*total < weight) { chosen = int(i); chosenCosine = bound; }
    }
    float3 result = float3(0);
    if (chosen >= 0 && total > 0.0) {
        float4 sphere = environment[0].emitters[2u*uint(chosen)];
        float4 meta = environment[0].emitters[2u*uint(chosen)+1u];
        float3 origin = point + normal*0.003, toward = sphere.xyz - origin;
        float square = dot(toward,toward), radius = sphere.w;
        float distance = sqrt(max(square,1e-8));
        float cosMax = sqrt(1.0-min(1.0, radius*radius/max(square,1e-8)));
        float3 direction = coneSample(toward/distance, cosMax, seed);
        float cosine = dot(normal,direction);
        if (cosine > 0.0) {
            float along = dot(direction,toward), disc = along*along - (square - radius*radius);
            float maxT = disc > 0.0 ? max(0.001, along-sqrt(disc)) : max(0.001, distance-radius);
            float3 filterColor;
            Hit blocker = traceOpaque(origin, direction, maxT, filterColor);
            bool visible = !blocker.found || all(blocker.cell == int3(meta.yzw));
            if (visible) result = blockColor()*filterColor*(total*cosine/chosenCosine);
        }
    }
    if (light.available && light.block > explained+0.5) result += blockColor()*lightResponse(light.block);
    return result;
}
// Full primary-surface radiance before presentation (linear, pi-normalized units):
// ambient + key light + one diffuse bounce/sky sample + block light, times albedo, plus emission.
float3 surfaceRadiance(Hit hit, float3 point, float3 albedo, inout uint seed) {
    CellLight light = lightOutside(point, hit.normal);
    float sky = lightResponse(light.sky);
    float3 irradiance = environment[0].values[4].rgb + keyIrradiance(point, hit.normal, sky, seed);
    // One cosine-sampled ray: an escape sees the sky (scaled by vanilla sky openness), a hit returns
    // that surface's outgoing radiance. This is ambient occlusion, sky light and one bounce at once.
    bool escaped; float distance;
    float3 bounceDirection = cosineSample(hit.normal, seed);
    irradiance += secondaryRadiance(point + hit.normal*0.003, bounceDirection, sky, seed, escaped, distance);
    irradiance += blockIrradiance(point, hit.normal, light, hit.cell, seed);
    float3 emitted = blockColor()*lightResponse(float(emissionLevel(hit)));
    return albedo*(irradiance + emitted);
}
// Water and other translucent surfaces: Schlick Fresnel, one reflection and one transmission ray.
// Water refracts (Snell) and attenuates with distance by Beer-Lambert. Its extinction is derived from
// the surface texel: one block of water transmits exactly vanilla's blend weight (1-a)+a*c, and the
// medium's equilibrium radiance is c times the irradiance at the surface. Other translucent quads are
// thin filters with the same per-layer transmittance and no bending. False = transmission unresolved.
bool translucentRadiance(Hit hit, float3 point, float3 ray, inout uint seed, out float3 radiance) {
    radiance = float3(0);
    bool fluid = (hit.flags & FLAG_FLUID) != 0u;
    float3 normal = hit.normal;
    float ior = fluid ? WATER_IOR : GLASS_IOR;
    float f0 = (ior-1.0)/(ior+1.0); f0 *= f0;
    float cosine = clamp(-dot(ray,normal), 0.0, 1.0);
    float fresnel = f0 + (1.0-f0)*pow(1.0-cosine, 5.0);
    CellLight light = lightOutside(point, normal);
    float sky = lightResponse(light.sky);
    bool escaped; float distance;
    float3 reflected = secondaryRadiance(point + normal*0.003, reflect(ray,normal), sky, seed, escaped, distance);
    float3 surface = hit.albedo, perBlock = clamp(float3(1.0-hit.alpha) + hit.alpha*surface, float3(0.001), float3(1));
    float3 direction = fluid ? refract(ray,normal,1.0/ior) : ray;
    if (dot(direction,direction) < 0.5) direction = ray;
    float3 origin = point - normal*0.003;
    float3 filterColor = fluid ? float3(1) : perBlock;
    Hit through = miss();
    float nearT = 0.001;
    for (int layer = 0; layer < 4; layer++) {
        through = trace(origin, direction, nearT, 96.0);
        if (!through.found || (through.flags & FLAG_TRANSLUCENT) == 0u) break;
        // Coplanar water faces inside one body are culled; anything else filters like a thin layer.
        if (!fluid || (through.flags & FLAG_FLUID) == 0u) filterColor *= lerp(float3(1), through.albedo, through.alpha);
        nearT = max(nearT, through.t) + 0.0005;
        through.found = false;
    }
    if (!through.found || (through.flags & FLAG_NO_MATERIAL) != 0u) return false;
    float3 behind = origin + through.t*direction;
    float3 transmitted = hitAlbedo(through, behind)*(secondaryIrradiance(behind, through.normal, seed)
        + blockColor()*lightResponse(float(emissionLevel(through))))*filterColor;
    if (fluid) {
        float3 extinction = -log(perBlock);
        float3 transmittance = exp(-extinction*through.t);
        float3 medium = surface*secondaryIrradiance(point, normal, seed);
        transmitted = transmitted*transmittance + medium*(float3(1)-transmittance);
    }
    radiance = fresnel*reflected + (1.0-fresnel)*transmitted;
    return true;
}
// Fixed LDR presentation; host ambient already includes the actual night-vision color.
float3 present(float3 color) {
    color *= (1.0+clamp(environment[0].values[4].w,0.0,1.0))*(1.0-clamp(environment[0].values[5].x,0.0,1.0));
    color = lerp(color, color*float3(0.7,0.6,0.6), clamp(environment[0].values[5].z,0.0,1.0));
    return color/(float3(1.0)+color);
}
#endif

#ifdef RASTER_INPUTS
uint packHalf(float2 v) { uint2 h = f32tof16(v); return (h.x & 0xffffu) | (h.y << 16); }
float2 unpackHalf(uint p) { return f16tof32(uint2(p & 0xffffu, p >> 16)); }
uint packSnorm(float2 v) { int2 i = int2(round(clamp(v, -1.0, 1.0)*32767.0)); return (uint(i.x) & 0xffffu) | (uint(i.y) << 16); }
float2 unpackSnorm(uint p) { int x = int(p << 16) >> 16; int y = int(p) >> 16; return clamp(float2(x, y)/32767.0, -1.0, 1.0); }
float2 octEncode(float3 n) {
    float sum = abs(n.x)+abs(n.y)+abs(n.z);
    if (sum < 1e-6) return float2(0);
    n /= sum;
    float2 e = n.xy;
    if (n.z < 0.0) e = (float2(1.0)-abs(n.yx))*float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return e;
}
float3 octDecode(float2 e) {
    float3 n = float3(e, 1.0-abs(e.x)-abs(e.y));
    float t = max(-n.z, 0.0);
    n.x += n.x >= 0.0 ? -t : t;
    n.y += n.y >= 0.0 ? -t : t;
    return normalize(n);
}
void storeHistory(int2 pixel, uint4 value) {
    if ((diagnostic.control.x & 32u) != 0u) historyA[pixel] = value; else historyB[pixel] = value;
}
uint4 loadHistory(int2 pixel) {
    return (diagnostic.control.x & 32u) != 0u ? historyB[pixel] : historyA[pixel];
}
// Bilinear reprojection; each tap is accepted only if its stored distance from the previous eye and
// its normal match this surface point (validation tolerances, not physical constants).
float3 accumulate(int2 pixel, int2 resolution, float3 point, float3 normal, float3 radiance, out float count) {
    count = 1.0;
    if (environment[0].info.y == 0u) {
#ifdef GPU_COUNTERS
        countHistory(5u);
#endif
        return radiance;
    }
    float4 clip = mul(environment[0].previousViewProjection, float4(point - environment[0].previousCamera.xyz, 1.0));
    if (clip.w <= 1e-6) {
#ifdef GPU_COUNTERS
        countHistory(5u);
#endif
        return radiance;
    }
    float2 position = (clip.xy/clip.w*0.5+0.5)*float2(resolution)-0.5;
    if (any(isnan(position)) || any(isinf(position))) {
#ifdef GPU_COUNTERS
        countHistory(5u);
#endif
        return radiance;
    }
    int2 base = int2(floor(position));
    float2 fraction = position - float2(base);
    float expected = length(point - environment[0].previousEye.xyz);
    float3 sum = float3(0); float weights = 0.0, counts = 0.0;
    for (int tap = 0; tap < 4; tap++) {
        int2 offset = int2(tap&1, tap>>1);
        int2 q = base + offset;
        if (any(q < int2(0,0)) || any(q >= resolution)) {
#ifdef GPU_COUNTERS
            countHistory(4u);
#endif
            continue;
        }
        uint4 stored = loadHistory(q);
        float2 rg = unpackHalf(stored.x);
        float2 bc = unpackHalf(stored.y);
        if (bc.y < 0.5) {
#ifdef GPU_COUNTERS
            countHistory(3u);
#endif
            continue;
        }
        float distance = asfloat(stored.z);
        if (abs(distance-expected) > 0.02*expected+0.05) {
#ifdef GPU_COUNTERS
            countHistory(1u);
#endif
            continue;
        }
        if (dot(octDecode(unpackSnorm(stored.w)), normal) < 0.9) {
#ifdef GPU_COUNTERS
            countHistory(2u);
#endif
            continue;
        }
        float w = (offset.x == 1 ? fraction.x : 1.0-fraction.x)*(offset.y == 1 ? fraction.y : 1.0-fraction.y);
        sum += w*float3(rg, bc.x); counts += w*bc.y; weights += w;
    }
    if (weights < 0.05) {
#ifdef GPU_COUNTERS
        countHistory(7u);
#endif
        return radiance;
    }
#ifdef GPU_COUNTERS
    countHistory(0u);
#endif
    count = min(counts/weights+1.0, float(max(environment[0].info.z, 1u)));
    return lerp(sum/weights, radiance, 1.0/count);
}
#endif

[shader("compute")]
[numthreads(8,8,1)]
void main(uint3 id : SV_DispatchThreadID) {
    int2 pixel = int2(id.xy);
    uint rw, rh; outputImage.GetDimensions(rw, rh);
    int2 resolution = int2(rw, rh);
    if (any(pixel >= resolution)) return;
    float2 uv = (float2(pixel)+0.5)/float2(resolution)*2.0-1.0;
    uv.x *= float(resolution.x)/float(resolution.y);
    float3 origin = float3(38,24,43), forward = normalize(float3(16,5,16)-origin);
    float3 right = normalize(cross(forward, float3(0,1,0))), up = cross(right, forward);
    float3 ray = normalize(forward + 0.62*uv.x*right - 0.62*uv.y*up);
    bool live = (diagnostic.control.x & 2u) != 0u;
    float2 ndc = (float2(pixel)+0.5)/float2(resolution)*2.0-1.0;
    if (live) {
        float4 point = mul(diagnostic.inverseViewProjection, float4(ndc, 0.5, 1.0));
        if (abs(point.w) < 1e-8 || any(isnan(point)) || any(isinf(point))) {
#ifdef RASTER_INPUTS
            storeHistory(pixel, uint4(0,0,0,0));
#endif
            outputImage[pixel] = float4(1,0,1,1); return;
        }
        origin = diagnostic.cameraOrigin.xyz + diagnostic.rayOriginOffset.xyz;
        float3 direction = point.xyz/point.w - diagnostic.rayOriginOffset.xyz;
        float square = dot(direction,direction);
        if (any(isnan(origin)) || any(isinf(origin)) || square < 1e-16 || isnan(square) || isinf(square)) {
#ifdef RASTER_INPUTS
            storeHistory(pixel, uint4(0,0,0,0));
#endif
            outputImage[pixel] = float4(1,0,1,1); return;
        }
        ray = direction*rsqrt(square);
    }
    Hit hit = trace(origin, ray, 0.0, 160.0);
    if ((diagnostic.control.x & 1u) != 0u) {
#ifdef RASTER_INPUTS
        storeHistory(pixel, uint4(0,0,0,0));
#endif
        uint id = live ? hit.material & 255u : hit.material;
        outputImage[pixel] = hit.found ? float4(float3(hit.cell), float(id))/255.0 : float4(0,0,0,0); return;
    }
    float3 color = lerp(float3(0.40,0.57,0.74), float3(0.09,0.16,0.28), clamp(ray.y*0.5+0.5,0.0,1.0));
    float3 p = origin + hit.t*ray;
#ifdef TEXTURED_CUBES
    bool worldLit = live && (diagnostic.control.x & 8u) != 0u;
    uint seed = pcg(uint(pixel.x) + pcg(uint(pixel.y) + pcg(diagnostic.control.z)));
    // A translucent primary seen from inside a model cell (e.g. the camera under water) keeps raster.
    uint cameraCell = cellValue(int3(floor(origin)));
    bool insideModel = cameraCell != 0xffffffffu && (cameraCell & 255u) == 3u;
    bool translucent = (hit.flags & FLAG_TRANSLUCENT) != 0u;
    bool shadeable = hit.found && (hit.flags & FLAG_NO_MATERIAL) == 0u && (!translucent || worldLit && !insideModel);
#endif
#ifdef RASTER_INPUTS
    // Decide composition first: shading is spent only on pixels whose raster surface it replaces.
    bool replace = false;
    float weight = 0.0;
    float3 raster = float3(0);
    if (live && diagnostic.control.y == 0u) {
        float3 original = rasterColor.Load(int3(pixel, 0)).rgb;
        raster = (diagnostic.control.x & 16u) != 0u ? original : srgbToLinear(original);
        float depth = rasterDepth.Load(int3(pixel, 0)).r;
        replace = shadeable && depth > 0.0 && depth <= 1.0;
        if (replace) {
            float4 rasterPoint = mul(diagnostic.inverseViewProjection, float4(ndc, depth, 1.0));
            replace = abs(rasterPoint.w) > 1e-8 && !any(isnan(rasterPoint)) && !any(isinf(rasterPoint));
            if (replace) {
                float distance = length(rasterPoint.xyz/rasterPoint.w - diagnostic.rayOriginOffset.xyz);
                // Require the same visible surface; preserve foreground entities, unsupported/stale
                // geometry and world beyond the resident grid instead of erasing them.
                replace = abs(distance-hit.t) <= max(0.025, 0.0015*distance);
            }
        }
        if (replace) {
            float3 edge = min(p, float3(gridDimensions().xyz)-p);
            weight = smoothstep(0.5, 2.5, min(edge.x, min(edge.y, edge.z)));
        }
    }
#endif
    if (hit.found && diagnostic.control.y == 0u) {
        float3 albedo = live ? float3(0.65) : materialColor(hit.material);
#ifdef TEXTURED_CUBES
        if (live) albedo = hitAlbedo(hit, p);
        if (worldLit) {
#ifdef RASTER_INPUTS
            if (replace) {
#else
            if (shadeable) {
#endif
                float3 radiance = float3(0);
                bool resolved = true;
                if (translucent) resolved = translucentRadiance(hit, p, ray, seed, radiance);
                else radiance = surfaceRadiance(hit, p, albedo, seed);
                if (any(isnan(radiance)) || any(isinf(radiance))) {
#ifdef GPU_COUNTERS
                    countHistory(6u);
#endif
                    resolved = false;
                }
#ifdef RASTER_INPUTS
                uint4 stored = uint4(0,0,0,0);
                if (resolved) {
                    float count;
                    radiance = accumulate(pixel, resolution, p, hit.normal, radiance, count);
                    stored = uint4(packHalf(radiance.rg), packHalf(float2(radiance.b, count)),
                        asuint(hit.t), packSnorm(octEncode(hit.normal)));
                } else weight = 0.0;
                storeHistory(pixel, stored);
#endif
                color = resolved ? present(radiance) : color;
            }
        } else {
#endif
            float3 sun = normalize(float3(-0.5,1.0,0.3));
            Hit shadow = trace(p + hit.normal*0.003, sun, 0.001, 100.0);
            float direct = shadow.found ? 0.0 : max(0.0, dot(hit.normal, sun));
            color = albedo*(0.22+0.78*direct);
#ifdef TEXTURED_CUBES
        }
#endif
    }
#ifdef RASTER_INPUTS
    if (!(replace && worldLit && hit.found && diagnostic.control.y == 0u)) storeHistory(pixel, uint4(0,0,0,0));
    if (live && diagnostic.control.y == 0u) {
        color = lerp(raster, color, weight);
        // Depth cannot identify translucent particles with depth writes disabled. This bounded
        // hybrid path does not provide complete transparent composition.
    }
#endif
    if (diagnostic.control.y == 1u) color = hit.found ? hit.normal*0.5+0.5 : float3(0);
    if (diagnostic.control.y == 2u) color = hit.found ? float3(clamp(hit.t/96.0,0.0,1.0)) : float3(0);
    if (diagnostic.control.y == 3u) color = !hit.found ? float3(0)
        : live && hit.material == 4u ? float3(0.08,0.24,0.85)
        : live && hit.material == 2u ? float3(0.75,0.10,0.65)
        : live && hit.material == 3u ? float3(0.15,0.8,0.35) : float3(1);
    color = clamp(color, 0.0, 1.0);
    if ((diagnostic.control.x & 4u) == 0u) {
#ifdef TEXTURED_CUBES
        color = linearToSrgb(color);
#else
        color = pow(color, float3(1.0/2.2));
#endif
    }
    outputImage[pixel] = float4(color, 1.0);
}