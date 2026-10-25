uint emitterCount() {
    return StatsBuf(pc.statsAddr).emitterCount;
}

// Next event estimation of emissive surfaces: resampled importance sampling (RIS) over the emitter list, one shadow ray to
// the chosen point. Returns the reflected radiance of a Lambert surface (albedo / PI * Le * G), unbiased for p^ > 0.
// Ray mode adds one temporal reservoir per pixel (sampleEmittersTemporal); the path tracer keeps plain RIS.
// d2 is the squared receiver-to-y distance, which the candidate loop already needs for its own radius test: recomputing it here
// cost a subtraction and a dot product per candidate of every NEE call.
float emitterPhat(vec3 y, float d2, float texEm, vec3 en, bool openSource, Surface s, float maxDist) {
    vec3 d = y - s.pos;
    if (d2 > maxDist * maxDist || d2 < 1.0e-6) {
        return 0.0;
    }
    if (s.emission > 0.5 && d2 < 0.25) {
        return 0.0; // eliminate emitter self-shadowing (end rod, torch, etc.)
    }
    vec3 dn = d * inversesqrt(d2);
    float cosR = max(dot(s.ns, dn), 0.0);
    if (cosR <= 0.0) {
        return 0.0;
    }
    float cosEmitter = dot(en, -dn);
    // Two-sided / open sources (torches, lanterns, fire): emit in all directions with a floor so edge-on views don't cut off.
    // Solid block faces (ceiling lamps, glowstone, sea lanterns): emit into the outward hemisphere (cosEmitter > 0).
    float cosS = openSource ? max(abs(cosEmitter), 0.25) : max(cosEmitter, 0.0);
    if (cosS <= 0.0) {
        return 0.0;
    }
    // Light radius: a source reaches EMITTER_RADIUS blocks (the reach of the game's own block light), not the whole ray
    // distance, or a lava column 10 blocks away painted the walls orange in daylight.
    float lightR = min(maxDist, EMITTER_RADIUS);
    float distFactor = clamp(1.0 - (d2 / (lightR * lightR)), 0.0, 1.0);
    // Falloff of a source: d^-1.5 up to 2 blocks (a torch or a held item still reads across a small room), then d^-1.25,
    // continuous at the 2-block seam (0.84090 = 2^1.25 / (2 * sqrt(2))). The physical d^-2 tail lost 94% of the 2-block value by
    // 8 blocks, so a torch lit a 4-5 block puddle and the rest of a room stayed black (owner report 2026-10-23); this one is
    // ~2.8x brighter there and ~4x at 15 blocks, and the window above still ends it at EMITTER_RADIUS. Written with sqrt
    // instead of pow(d, 1.25): dd^-0.625 = inversesqrt(dd * sqrt(sqrt(dd))), one rsqrt lane instead of a log2/exp2 pair, eight
    // times per NEE call in the hottest loop of the emissive pass.
    float dd = max(d2, 0.25);
    float att = dd <= 4.0 ? inversesqrt(dd * sqrt(dd)) : 0.84090 * inversesqrt(dd * sqrt(sqrt(dd)));
    return texEm * cosS * cosR * distFactor * distFactor * att;
}

vec3 unpackReservoirNormal(float packed) {
    vec2 xy = unpackSnorm2x16(floatBitsToUint(packed));
    return vec3(xy, sqrt(max(0.0, 1.0 - dot(xy, xy))));
}

// Projects the receiver into the previous frame like temporalAccumulate: nearest history pixel, previous distance for the
// disocclusion test. The reservoir stores the receiver distance of the frame that wrote it.
bool reprojectHistoryPixel(ivec2 p, ivec2 size, vec3 pos, out ivec2 q, out float prevDist, out bool isStatic) {
    StatsBuf fb = StatsBuf(pc.statsAddr);
    isStatic = fb.camDelta.w == 0.0;
    q = p;
    prevDist = length(pos);
    if (isStatic) {
        return true;
    }
    vec3 pPrev = pos + fb.camDelta.xyz;
    vec4 clip = fb.prevViewProj * vec4(pPrev, 1.0);
    if (clip.w <= 1.0e-4) {
        return false;
    }
    vec2 ndc = clip.xy / clip.w;
    if (((pc.modeBits >> 16u) & 1u) != 0u) {
        ndc.y = -ndc.y;
    }
    q = ivec2(floor((ndc * 0.5 + 0.5) * vec2(size)));
    prevDist = length(pPrev);
    return q.x >= 0 && q.y >= 0 && q.x < size.x && q.y < size.y;
}

void storeEmptyReservoir(uint slot) {
    Reservoir outR;
    outR.yTexEm = vec4(0.0);
    outR.enPhat = vec4(0.0);
    outR.leWsum = vec4(0.0);
    outR.meta = vec4(0.0);
    ReservoirBuf(StatsBuf(pc.statsAddr).reservoirAddr).r[slot] = outR;
}

vec3 sampleEmittersImpl(Surface s, vec3 albedo, float maxDist, inout uint seed, uint candidates, ivec2 p, ivec2 size,
                        bool temporal) {
    StatsBuf fb = StatsBuf(pc.statsAddr);
    bool useReservoir = temporal && fb.reservoirAddr.x != 0u;
    uint width = uint(size.x);
    uint pixels = uint(size.x) * uint(size.y);
    uint writeSlot = uint(pc.frame & 1u) * pixels + uint(p.y) * width + uint(p.x);
    uint n = fb.emitterCount;
    if (n == 0u) {
        if (useReservoir) {
            storeEmptyReservoir(writeSlot);   // no light reaches this pixel: a stale history must not resurrect one
        }
        return vec3(0.0);
    }
    EmitterBuf eb = EmitterBuf(fb.emitterAddr);
    vec3 camRel = fb.camRel.xyz;
    float wsum = 0.0;
    float chosenPhat = 0.0;
    vec3 chosenY = vec3(0.0);
    vec2 chosenUv = vec2(0.0);
    uint chosenSlot = 0u;     // entity atlas slot + 1 of the chosen emitter, 0 = block atlas
    float chosenEm = 0.0;
    bool chosenRedstone = false;
    vec4 chosenTint = vec4(1.0);
    bool chosenOpenSource = false;   // two-sided emitter (torch, lantern, fire): its own body may stand between it and the receiver
    vec3 chosenEn = vec3(0.0, 1.0, 0.0);
    // stratified points on the emitter quads: a per-call random shift of the R2 low-discrepancy sequence over the candidates, so the
    // few quads of a torch / candle are covered evenly instead of by independent random points (the flicker of a torch light)
    vec2 r2shift = vec2(rand01(seed), rand01(seed));
    // loop-invariant: the same light radius for every candidate of this call
    float lightR = min(maxDist, EMITTER_RADIUS);
    for (uint k = 0u; k < candidates; k++) {
        // the list is sorted by importance (RtEmitters): index = n * u^2 favours its front, P(i) = sqrt((i+1)/n) - sqrt(i/n)
        float u = rand01(seed);
        uint idx = min(uint(u * u * float(n)), n - 1u);
        // P(i) = sqrt((i+1)/n) - sqrt(i/n), one sqrt pair instead of two square roots of quotients
        float pIdx = (sqrt(float(idx + 1u)) - sqrt(float(idx))) * inversesqrt(float(n));
        Emitter e = eb.e[idx];
        vec2 ab = fract(r2shift + float(k) * vec2(0.7548776662, 0.5698402909));
        float a = ab.x;
        float b = ab.y;
        vec3 y = e.p0.xyz - camRel + e.e1.xyz * a + e.e2.xyz * b;
        // The two zero tests of emitterPhat (outside the light radius, inside the emitter's own body) hoisted in front of
        // the per-candidate texture fetches: such a candidate contributes phat = 0 and adds no weight, so rejecting it here
        // costs a subtraction and a dot product instead of a material-atlas (or entity-atlas) fetch. The random stream is
        // unchanged: the original path did not draw for it either.
        float candD2 = dot(y - s.pos, y - s.pos);
        if (candD2 > lightR * lightR || candD2 < (s.emission > 0.5 ? 0.25 : 1.0e-6)) {
            continue;
        }
        vec2 yuv = e.uvc.xy + e.duv.xy * a + e.duv.zw * b;
        // light leaves only the emissive texels: the emission of the texel at the sampled point (the emitter rectangle is the
        // bounding box of the emissive texels, RtEmitters.buildQuadRecord; texels in it that do not glow emit nothing)
        uint slotPlus1 = uint(e.uvc.w + 0.5);
        vec4 matSample = slotPlus1 == 0u ? textureLod(matAtlas, yuv, 0.0) : vec4(0.0);
        bool redstoneEmitter = slotPlus1 == 0u && e.e2.w > 1.5;
        bool redstoneTexel = redstoneEmitter && ((255u - uint(matSample.a * 255.0 + 0.5)) & 4u) != 0u;
        float texEm = slotPlus1 != 0u ? entityEmission(entityTexel(slotPlus1 - 1u, yuv)) * e.e1.w
                                      : (redstoneTexel ? e.uvc.z : matSample.b * e.e1.w);
        if (texEm <= 0.0) {
            continue;
        }
        vec3 crossE = cross(e.e1.xyz, e.e2.xyz);
        float crossLen = length(crossE);
        vec3 en = crossLen > 1.0e-6 ? crossE / crossLen : vec3(0.0, 1.0, 0.0);
        bool twoSided = e.e2.w > 0.5;
        float phat = emitterPhat(y, candD2, texEm, en, twoSided, s, maxDist);
        if (phat <= 0.0) {
            continue;
        }
        float w = phat * e.p0.w / pIdx;   // p^ / p, p = P(idx) / area
        wsum += w;
        if (w > 0.0 && rand01(seed) * wsum < w) {
            chosenPhat = phat;
            chosenY = y;
            chosenUv = yuv;
            chosenSlot = slotPlus1;
            chosenEm = texEm;
            chosenRedstone = redstoneTexel;
            chosenTint = slotPlus1 != 0u ? e.tint : vec4(1.0);
            chosenOpenSource = twoSided;
            chosenEn = en;
        }
    }
    float sampleCount = float(candidates);
    bool fromHistory = false;
    vec3 historyLe = vec3(0.0);
    if (useReservoir) {
        ivec2 q;
        float prevDist;
        bool isStatic;
        if (reprojectHistoryPixel(p, size, s.pos, q, prevDist, isStatic)) {
            Reservoir prev = ReservoirBuf(fb.reservoirAddr).r[uint((pc.frame & 1u) ^ 1u) * pixels
                + uint(q.y) * width + uint(q.x)];
            float mPrev = min(prev.meta.x, 20.0);   // clamps ghosting of a reservoir whose light was switched off
            vec3 nPrev = unpackReservoirNormal(prev.meta.z);
            if ((uint(prev.meta.y) & 2u) != 0u) {
                nPrev.z = -nPrev.z;   // receiver normal faced down (ceiling): the packed xy lost the sign of z
            }
            float normalDot = dot(nPrev, normalize(s.ns));
            float tol = 0.05 * max(prevDist, 1.0) + 0.1;
            if (mPrev > 0.0 && normalDot > (isStatic ? 0.8 : 0.6) && abs(prev.meta.w - prevDist) < tol) {
                // y is camera-relative; camDelta = current - previous camera, so the previous sample moves by -camDelta
                vec3 yNow = prev.yTexEm.xyz - (isStatic ? vec3(0.0) : fb.camDelta.xyz);
                bool open = (uint(prev.meta.y) & 1u) != 0u;
                float phatHist = emitterPhat(yNow, dot(yNow - s.pos, yNow - s.pos), prev.yTexEm.w, prev.enPhat.xyz, open, s,
                    maxDist);
                if (phatHist > 0.0) {
                    // ReSTIR merge: the stored cumulative weight is rescaled from the previous receiver to this one, and from
                    // meta.x samples down to the clamped mPrev that is added to sampleCount - otherwise the history weight
                    // never decays while M saturates and emissive light ramps up frame after frame.
                    float wHist = prev.leWsum.w * (mPrev / max(prev.meta.x, 1.0))
                        * (phatHist / max(prev.enPhat.w, 1.0e-8));
                    float accept = wHist / max(wsum + wHist, 1.0e-8);
                    wsum += wHist;
                    sampleCount += mPrev;
                    if (rand01(seed) < accept) {
                        chosenPhat = phatHist;
                        chosenY = yNow;
                        chosenEm = prev.yTexEm.w;
                        chosenEn = prev.enPhat.xyz;
                        chosenOpenSource = open;
                        historyLe = prev.leWsum.xyz;
                        fromHistory = true;
                    }
                }
            }
        }
    }
    if (chosenPhat <= 0.0) {
        if (useReservoir) {
            storeEmptyReservoir(writeSlot);
        }
        return vec3(0.0);
    }
    vec3 toEmitter = chosenY - s.pos;
    float dist = length(toEmitter);
    if (dist < 1.0e-5) {
        if (useReservoir) {
            storeEmptyReservoir(writeSlot);
        }
        return vec3(0.0);
    }
    vec3 dn = toEmitter / dist;
    vec3 leNoTrans;
    if (fromHistory) {
        leNoTrans = historyLe;   // radiance of the previous sample, no visibility of that frame
    } else {
        vec3 texRgb = chosenSlot != 0u ? entityTexel(chosenSlot - 1u, chosenUv).rgb
                                      : (chosenRedstone ? vec3(1.0, 0.04, 0.02) : textureLod(atlas, chosenUv, 0.0).rgb);
        texRgb *= chosenTint.rgb * chosenTint.a;
        if (!chosenRedstone) {
            texRgb = emitterLightColor(texRgb);
        }
        leNoTrans = texRgb * chosenEm * EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN;
    }
    if (useReservoir) {
        // Store before the visibility ray: the reservoir is a sample distribution, visibility is applied only to this frame's
        // shade, so an occluded frame does not throw the sample away for the next one.
        Reservoir outR;
        vec3 recvN = normalize(s.ns);
        outR.yTexEm = vec4(chosenY, chosenEm);
        outR.enPhat = vec4(chosenEn, chosenPhat);
        outR.leWsum = vec4(leNoTrans, wsum);
        // meta.y bit 0 = open source, bit 1 = receiver normal faced down
        outR.meta = vec4(sampleCount, float((chosenOpenSource ? 1 : 0) | (recvN.z < 0.0 ? 2 : 0)),
            uintBitsToFloat(packSnorm2x16(recvN.xy)), length(s.pos));
        ReservoirBuf(fb.reservoirAddr).r[writeSlot] = outR;
    }

    // Visibility test between receiver (s.pos) and sampled emitter point (chosenY).
    // Geometric normal s.ng prevents self-intersection / shadow acne on block boundaries and normal map seams.
    float endBias = clamp(0.02 * dist, 0.002, 0.03);
    // An open source (torch flame on its stick, lantern bulb in its cage, candle) is sampled on faces that may look away from the
    // receiver: the ray would then cross the body of the source itself and the stick would cast a shadow of its own light. The ray
    // ends outside that body (a stick is 2 / 16 block thick, 0.2 covers its diagonal); the surroundings (walls, floor) still block.
    if (chosenOpenSource) {
        endBias = max(endBias, min(0.2, 0.45 * dist));
    }
    vec3 shadowOrigin = s.pos + s.ng * 0.005;
    vec3 rayTarget = chosenY - dn * endBias;
    vec3 rayDir = rayTarget - shadowOrigin;
    float rayDist = length(rayDir);
    vec3 trans = vec3(1.0);
    if (rayDist > 0.002) {
        trans = traceTransmittance(shadowOrigin, rayDir / rayDist, rayDist);
    }
    if (max(trans.r, max(trans.g, trans.b)) <= 0.0) {
        return vec3(0.0);
    }
    vec3 le = leNoTrans * trans;
    // f * Le * G / p^(y) * (wsum / M); with p^ = em * G the geometry term cancels: albedo / PI * Le / em * wsum / M
    return albedo * INV_PI * le / max(chosenEm, 1.0e-4) * (wsum / sampleCount);
}

vec3 sampleEmitters(Surface s, vec3 albedo, float maxDist, inout uint seed) {
    return sampleEmittersImpl(s, albedo, maxDist, seed, NEE_CANDIDATES, ivec2(0), ivec2(1), false);
}

// Ray mode: one reservoir per pixel and frame. candidates = emissiveRays() * NEE_CANDIDATES, so the history is merged once
// per pixel instead of once per RIS call.
vec3 sampleEmittersTemporal(Surface s, vec3 albedo, float maxDist, inout uint seed, ivec2 p, ivec2 size, uint candidates) {
    return sampleEmittersImpl(s, albedo, maxDist, seed, candidates, p, size, true);
}

vec3 sampleEmitters(Surface s, vec3 albedo, vec3 origin, float maxDist, inout uint seed) {
    return sampleEmitters(s, albedo, maxDist, seed);
}

vec3 sampleEmitters(vec3 pos, vec3 ns, vec3 albedo, vec3 origin, float maxDist, inout uint seed) {
    Surface dummy;
    dummy.pos = pos;
    dummy.ng = ns;
    dummy.ns = ns;
    return sampleEmitters(dummy, albedo, maxDist, seed);
}

// Ray origin just above the surface (RtLighting.shadowBias)
vec3 offsetOrigin(Surface s) {
    float bias = clamp(0.003 + 0.0001 * s.t, 0.003, 0.015);
    return s.pos + s.ng * bias;
}

