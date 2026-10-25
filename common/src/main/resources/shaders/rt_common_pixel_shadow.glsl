// ---- Pixelated sun and moon shadows (look option, RtSettings.raysPixelShadows) ----------------------------------------------
// The sun/moon visibility is evaluated once per world space shadow cell instead of once per pixel: the shadow-ray origin is
// snapped to the centre of the cell, so one whole cell shares one sample and the shadow edge becomes a staircase at the
// resolution of the block textures (the cell is measured in texture pixels, 1/16 block, so size 1 is one pixel of a 16x16
// block texture). Surface.worldPos is world-anchored modulo 1024 blocks, so the grid stands still while the camera moves.
//
// This module owns the whole policy; every shaded pass goes through it. Three rules keep the look from becoming a cheat:
//
//  1. It moves the RECEIVER, never the caster and never the result. It is a shadow map in the original sense: one texel has
//     one depth and the receiver is evaluated at the texel centre. The other light terms keep the exact per-pixel origin -
//     ambient occlusion, traced sky light, block-emitter light, the emitters' own shadow rays: quantising those would make
//     the LIGHT blocky, not the shadow. What does follow the option is any surface shaded WITH a sun shadow ray whose result
//     reaches the image: the primary hit in both passes, and in Fast mode also the reflection and GI hits, so a mirror shows
//     the same edges as the floor it reflects. The path tracer's deeper vertices stay exact: they are 1 spp, denoised, and
//     each would pay a validation ray for nothing.
//  2. A cell that crosses geometry keeps the exact shadow. The shared origin has to be able to see the fragment it
//     replaces; when the short segment between the two is blocked (a step, a slab, a wall corner inside the cell) this
//     fragment is simply not snapped. The alternative tried before - a fixed lift along the normal, up to 0.4 blocks - put
//     the whole cell above its own surface: shadows detached from their casters (peter-panning), light leaked through
//     slabs and ceilings, and a cell wider than a block floated entirely.
//  3. It never touches geometry that is not part of the block grid: an entity moves inside a world-locked cell, so its
//     snapped origin would stay behind while its shadow should follow it, and a foliage card's shadow ray has to start on
//     its own sun side (leafShadowOrigin), which a cell centre cannot guarantee.
float shadowPixelSize() {
    // StatsBuf.shadowPixel carries texture pixels; one texture pixel is 1/16 of a block.
    return pc.statsAddr == uvec2(0u) ? 0.0 : StatsBuf(pc.statsAddr).shadowPixel * (1.0 / 16.0);
}

// Whether this fragment's sun/moon shadow is sampled per world cell. `refracted`: the shadow ray was re-origined by the
// water beam (a camera or a path vertex under the surface), where the block cell above means nothing.
bool pixelShadowApplies(Surface s, bool refracted) {
    return shadowPixelSize() > 0.0 && !refracted && !isEntityHit(s.slot) && !s.isFoliage;
}

// Origin of the shared shadow ray of this fragment's cell. Returns `o` unchanged whenever sharing is not possible (the
// option is off, or the cell crosses geometry), so a caller can use the result unconditionally. Call it only where the
// shadow ray is actually traced: the validation below is a ray of its own.
vec3 pixelShadowOrigin(Surface s, vec3 o) {
    float cell = shadowPixelSize();
    if (cell <= 0.0) {
        return o;
    }
    // Snap horizontally only: the grid stays parallel to the block grid at any sun height, so a low sun does not shove
    // the origin vertically into the ground.
    vec2 w = s.worldPos.xz;
    vec3 lateral = vec3((floor(w.x / cell) + 0.5) * cell - w.x, 0.0, (floor(w.y / cell) + 0.5) * cell - w.y);
    // Keep the displacement in the surface plane: a naive world snap pushes wall fragments sideways INTO the wall.
    lateral -= s.ng * dot(lateral, s.ng);
    float len = length(lateral);
    if (len <= 1.0e-5) {
        return o;   // the fragment already sits on the cell centre
    }
    // Blocks are convex, so "the cell centre is inside solid geometry or behind a step" is exactly "the segment from the
    // fragment to the cell centre is blocked". Cheap (at most half a cell) and deterministic for fragments of one surface,
    // so a cell is either shared or exact per surface and never half-black.
    if (traceVisibility(o, lateral / len, len + 0.05) < 0.5) {
        return o;
    }
    return o + lateral;
}

// Cell index that rotates the sun/moon disk sequence: every fragment of a cell draws the same sample of the sun, so the
// penumbra of one cell is one value instead of per-pixel speckle. No mask: Surface.worldPos is world-anchored modulo 1024
// blocks, so the largest index (1024 blocks / a 1/16-block cell = 16384) is still exact in float.
ivec2 pixelShadowCellIndex(vec3 worldPos) {
    float cell = shadowPixelSize();
    return cell > 0.0 ? ivec2(floor(worldPos.xz / cell)) : ivec2(floor(worldPos.xz * 16.0));
}

// World-locked rotation for a fragment whose origin is not snapped: the penumbra rays stay still in the world instead of
// boiling with the accumulating sub-pixel camera jitter. The y shear decorrelates stacked surfaces, so a floor under a
// roof does not repeat the roof's sample pattern.
ivec2 pixelShadowStableCell(vec3 worldPos) {
    return ivec2(floor((worldPos.xz + vec2(worldPos.y * 0.5)) * 16.0));
}

// Rotation of the sun/moon sequence for one shaded fragment: its cell when the shadow is sampled per cell (this includes
// fragments whose origin the geometry check rejected - they keep a world-locked, cell-wide sample so their penumbra does
// not boil), a world-locked cell when the option is on but this geometry keeps the exact origin, and the pixel itself when
// nothing is quantised (option off, camera still). Unchanged from before the option existed, so switching it off restores
// the exact per-pixel penumbra of every earlier build.
//
// Foliage is the exception and is locked to its block: pixelShadowStableCell hashes a 1/16-block cell, and the accumulating
// sub-pixel camera jitter moves a leaf hit across those cell borders every frame, so the rotation index - and with it the
// whole sun-disk sequence - restarted frame to frame for a growing share of the canopy (about a fifth of the leaves at eight
// blocks, nearly all of them past thirty). Leaf transmission is a 0.6-per-layer staircase, so that restart flipped whole
// texels in ~40 % steps: the ripple the owner kept reporting. Freezing only the frame term (sunSampleFrame) cannot fix that,
// the rotation has to be stable too, and a leaf card never leaves its own block.
ivec2 sunShadowRotation(Surface s, bool cellShadow, ivec2 pixel, bool jittered) {
    if (cellShadow) {
        return pixelShadowCellIndex(s.worldPos);
    }
    if (s.isFoliage) {
        return pixelShadowStableCell(floor(s.worldPos));
    }
    return (shadowPixelSize() > 0.0 || jittered) ? pixelShadowStableCell(s.worldPos) : pixel;
}
