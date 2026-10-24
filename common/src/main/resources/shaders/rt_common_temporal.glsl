// ---- Pixelated shadows (look option, RtSettings.raysPixelShadows) ----------------------------------------------------------
// The sun visibility is evaluated once per world-space shadow texel instead of once per pixel: the shadow ray origin is
// snapped to the centre of a horizontal world cell, so one whole light cell shares one sample and the penumbra becomes
// deliberately blocky, exactly at the resolution of the block textures: the cell is measured in texture pixels
// (1/16 block), so size 1 = a 16x16 block-texture pixel. The grid lives in the world, so the pattern is stable while
// the camera moves and does not swim. shadowPixel = 0 (option off) keeps the exact per-pixel origin.
float shadowPixelSize() {
    // StatsBuf.shadowPixel carries texture pixels; one texture pixel is 1/16 of a block.
    return pc.statsAddr == uvec2(0u) ? 0.0 : StatsBuf(pc.statsAddr).shadowPixel * (1.0 / 16.0);
}

vec3 pixelShadowOrigin(Surface s, vec3 o) {
    float cell = shadowPixelSize();
    if (cell <= 0.0) {
        return o;
    }
    // Snap horizontally only: the grid stays parallel to the block grid at any sun height, so a low sun does not shove
    // the origin vertically into the ground.
    vec2 w = s.worldPos.xz;
    vec3 lateral = vec3((floor(w.x / cell) + 0.5) * cell - w.x, 0.0, (floor(w.y / cell) + 0.5) * cell - w.y);
    // Keep the displacement in the surface plane: a naive world snap pushes wall fragments sideways INTO the wall, and
    // the whole cell traces out of solid geometry and goes black.
    lateral -= s.ng * dot(lateral, s.ng);
    // A cell wider than one block spans steps and slabs: lift the shared origin enough to clear them, but not a ceiling.
    return o + lateral + s.ng * min(0.05 + 0.2 * cell, 0.4);
}

// Cell-space index for the sun-disk sample sequence: every pixel of a cell shares the same penumbra sample.
vec2 pixelShadowCell(vec3 worldPos) {
    float cell = shadowPixelSize();
    return cell > 0.0 ? floor(worldPos.xz / cell) : floor(worldPos.xz * 16.0);
}

bool keyLightOn() {
    return max(pc.keyColor.r, max(pc.keyColor.g, pc.keyColor.b)) > 0.001;
}

// Subpixel camera jitter offset in [-0.5, 0.5] pixel units (0 = pixel center).
// CameraJitterManager adds +currentJitterNdcX to projMatrix.m20 and +currentJitterNdcY to projMatrix.m21. With clip.w = -z
// that moves the rendered content by -jitter pixels, so this pixel holds the surface of the unjittered pixel p + jitter:
// the primary ray must sample the same offset, otherwise the colour drifts from the depth/motion vectors handed to the
// upscaler and the temporal reconstruction shakes on every Halton phase.
vec2 getCameraJitter() {
    if (pc.statsAddr == uvec2(0u)) {
        return vec2(0.0);
    }
    return StatsBuf(pc.statsAddr).cameraJitter;
}

vec3 cameraOrigin() {
    return pc.statsAddr == uvec2(0u) ? vec3(0.0) : StatsBuf(pc.statsAddr).cameraBobOrigin.xyz;
}

vec3 rawCameraForward() {
    return pc.statsAddr == uvec2(0u) ? vec3(0.0, 0.0, -1.0) : StatsBuf(pc.statsAddr).cameraForward.xyz;
}

// Camera forward derived from the current projection (including bob), matching the effective ray origin.
vec3 bobCameraForward() {
    vec4 centerW = pc.invViewProj * vec4(0.0, 0.0, 0.5, 1.0);
    return normalize(centerW.xyz / centerW.w - cameraOrigin());
}

// Camera ray of pixel p for the image size; sub-pixel offset jitter in 0..1.
vec3 cameraRay(ivec2 p, ivec2 size, vec2 jitter) {
    vec2 ndc = (vec2(p) + jitter) / vec2(size) * 2.0 - 1.0;
    if (((pc.modeBits >> 16u) & 1u) != 0u) {
        ndc.y = -ndc.y;
    }
    // Any depth strictly inside the frustum lies on the ray through the camera; 0.5 is inside for [0,1] and [-1,1] clip depth.
    vec4 w = pc.invViewProj * vec4(ndc, 0.5, 1.0);
    return normalize(w.xyz / w.w - cameraOrigin());
}

float gHistoryLimit = 1.0e9;   // longest temporal history for this pixel (frames): water keeps its animation alive

#ifdef VKB_ACCUM
// Temporal accumulation with reprojection (RtAccumImage layout, W x 9H: rows [0,H) / [H,2H) diffuse irradiance E + count of even /
// odd frames, [2H,3H) / [3H,4H) normal + hit distance, [4H,5H) albedo + filter flag of this frame, [5H,7H) scratch of rt_atrous.comp,
// [7H,8H) / [8H,9H) the rest R of even / odd frames). The final colour is albedo * E + R, accumulated as two signals:
//  - E: diffuse light (direct + indirect + emitter NEE, already times the fog transmittance), NOT multiplied by the albedo. It is
//    remodulated with the CURRENT albedo, so the point-sampled texture stays sharp and only the smooth lighting goes through the
//    bilinear history resample;
//  - R: everything that is not albedo * diffuse light: reflections, specular, emission, interface reflections, fog inscatter.
//    Nothing of it is divided by an albedo (no 1/albedo, no NaN on black textures), it keeps its own history.
// The current hit (camera-relative pos, normal, distance) is projected into the previous frame; the history there is used when its
// distance and normal agree (no disocclusion), its length capped by historyCap. Returns the new colour. Sky pixels keep no history.
vec3 temporalAccumulate(ivec2 p, ivec2 size, vec3 e, vec3 r, bool hit, vec3 pos, vec3 nrm, float dist, vec3 albedo, bool filterable) {
    int cur = int(pc.frame & 1u);
    int prv = cur ^ 1;
    float n = 0.0;
    vec3 histE = vec3(0.0);
    vec3 histR = vec3(0.0);
    // The stored and compared distance is |pos| (camera to the reprojected point). The caller's hit t is the length of the
    // LAST ray segment: behind water/glass it starts at the interface, so it never matched length(pPrev) of a moving camera
    // and every pixel seen through water or glass lost its history while walking (raw 1-spp noise, flicker).
    dist = hit ? length(pos) : 0.0;
    if (hasFlag(FLAG_ACCUMULATE) && accumCount() > 0u && hit) {
        StatsBuf fb = StatsBuf(pc.statsAddr);
        bool isStatic = (fb.camDelta.w == 0.0);
        vec2 pix = vec2(p);
        float prevDist = dist;
        bool valid = true;
        if (!isStatic) {
            // pos is relative to the raw current camera and includes this frame's bob origin. camDelta moves it to the prior
            // raw camera; prevViewProj includes the previous bob pose, so adding a separate bob delta here would double count it.
            vec3 pPrev = pos + fb.camDelta.xyz;
            vec4 clip = fb.prevViewProj * vec4(pPrev, 1.0);
            if (clip.w > 1.0e-4) {
                vec2 ndc = clip.xy / clip.w;
                if (((pc.modeBits >> 16u) & 1u) != 0u) {
                    ndc.y = -ndc.y;
                }
                pix = (ndc * 0.5 + 0.5) * vec2(size) - 0.5;   // continuous, pixel centres at integers
                prevDist = length(pPrev);
            } else {
                valid = false;
            }
        }
        if (valid) {
            // Distance tolerance of the disocclusion test: the stored distance belongs to the centre of the history pixel, up to
            // ~1 px away from the reprojected point. On a plane seen at incidence cos(i) one pixel of offset changes the distance
            // by about d * pixelAngle / cos(i): grazing floors failed the old fixed 3 % test while walking and lost their history
            // (noise crawling over the floor).
            vec4 c0 = pc.invViewProj * vec4(0.0, 0.0, 0.5, 1.0);
            vec4 c1 = pc.invViewProj * vec4(0.0, 2.0 / float(size.y), 0.5, 1.0);
            float pixAngle = length(normalize(c1.xyz / c1.w) - normalize(c0.xyz / c0.w));
            float cosInc = abs(dot(pos, nrm)) / max(dist, 1.0e-4);
            float tol = 0.02 * prevDist + 0.05 + 1.5 * prevDist * pixAngle / max(cosInc, 0.08);
            // bilinear history: 4 taps around the reprojected point, each validated (distance + normal), weights renormalised
            vec2 f = fract(pix);
            ivec2 base = ivec2(floor(pix));
            vec3 eSum = vec3(0.0);
            vec3 rSum = vec3(0.0);
            float nSum = 0.0;
            float wSum = 0.0;
            float normThr = isStatic ? 0.8 : 0.6;  // при движении нормали могут не совпадать идеально из-за субпиксельного смещения
            for (int i = 0; i < 4; i++) {
                ivec2 o = ivec2(i & 1, i >> 1);
                ivec2 q = base + o;
                if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) {
                    continue;
                }
                vec4 g = imageLoad(accumImage, ivec2(q.x, q.y + (2 + prv) * size.y));
                if (g.w > 0.0 && abs(g.w - prevDist) < tol && dot(g.xyz, nrm) > normThr) {
                    float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
                    vec4 h = imageLoad(accumImage, ivec2(q.x, q.y + prv * size.y));
                    eSum += h.rgb * w;
                    rSum += imageLoad(accumImage, ivec2(q.x, q.y + (7 + prv) * size.y)).rgb * w;
                    nSum += h.a * w;
                    wSum += w;
                }
            }
            if (wSum <= 1.0e-3) {
                // Silhouette fallback: the 4 bilinear taps all failed (jitter makes an edge pixel hit the other face, or the edge moved).
                // Take the best-matching tap of the 3x3 around the reprojected pixel (same distance / normal), with a short history so
                // the pixel is not left at 1 spp ("ants" along block outlines).
                ivec2 c = ivec2(floor(pix + 0.5));
                float bestScore = 1.0e9;
                ivec2 bq = c;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        ivec2 q = c + ivec2(i, j);
                        if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) {
                            continue;
                        }
                        vec4 g = imageLoad(accumImage, ivec2(q.x, q.y + (2 + prv) * size.y));
                        float dz = abs(g.w - prevDist);
                        if (g.w > 0.0 && dz < tol && dot(g.xyz, nrm) > 0.9) {
                            float sc = dz / tol + (1.0 - dot(g.xyz, nrm)) * 4.0 + 0.1 * float(abs(i) + abs(j));
                            if (sc < bestScore) {
                                bestScore = sc;
                                bq = q;
                            }
                        }
                    }
                }
                if (bestScore < 1.0e8) {
                    vec4 h = imageLoad(accumImage, ivec2(bq.x, bq.y + prv * size.y));
                    eSum = h.rgb;
                    rSum = imageLoad(accumImage, ivec2(bq.x, bq.y + (7 + prv) * size.y)).rgb;
                    nSum = min(h.a, 4.0);
                    wSum = 1.0;
                }
            }
            if (wSum > 1.0e-3) {
                histE = eSum / wSum;
                histR = rSum / wSum;
                n = min(min(nSum / wSum, fb.historyCap), gHistoryLimit);
            } else if (!isStatic) {
                // Relaxed reprojection fallback during camera motion to avoid 1 SPP noise flashing
                for (int i = 0; i < 4; i++) {
                    ivec2 o = ivec2(i & 1, i >> 1);
                    ivec2 q = base + o;
                    if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) {
                        continue;
                    }
                    vec4 g = imageLoad(accumImage, ivec2(q.x, q.y + (2 + prv) * size.y));
                    if (g.w > 0.0 && abs(g.w - prevDist) < tol * 3.0 && dot(g.xyz, nrm) > 0.3) {
                        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
                        vec4 h = imageLoad(accumImage, ivec2(q.x, q.y + prv * size.y));
                        eSum += h.rgb * w;
                        rSum += imageLoad(accumImage, ivec2(q.x, q.y + (7 + prv) * size.y)).rgb * w;
                        nSum += h.a * w;
                        wSum += w;
                    }
                }
                if (wSum > 1.0e-3) {
                    histE = eSum / wSum;
                    histR = rSum / wSum;
                    n = min(min(nSum / wSum, fb.historyCap), gHistoryLimit);
                }
            }
        }
        if (n > 0.0 && ((p.x | p.y) & 3) == 0) {
            atomicAdd(fb.histAccepted, 1u);    // history accepted (CPU: hist_pct of the counted hit pixels)
        }
    }
    vec3 meanE = (histE * n + e) / (n + 1.0);
    vec3 meanR = (histR * n + r) / (n + 1.0);
    if (isnan(meanE.r) || isinf(meanE.r)) meanE.r = e.r;
    if (isnan(meanE.g) || isinf(meanE.g)) meanE.g = e.g;
    if (isnan(meanE.b) || isinf(meanE.b)) meanE.b = e.b;
    if (isnan(meanR.r) || isinf(meanR.r)) meanR.r = r.r;
    if (isnan(meanR.g) || isinf(meanR.g)) meanR.g = r.g;
    if (isnan(meanR.b) || isinf(meanR.b)) meanR.b = r.b;
    meanE = max(meanE, vec3(0.0));
    meanR = max(meanR, vec3(0.0));
    imageStore(accumImage, ivec2(p.x, p.y + cur * size.y), vec4(meanE, n + 1.0));
    imageStore(accumImage, ivec2(p.x, p.y + (7 + cur) * size.y), vec4(meanR, 0.0));
    imageStore(accumImage, ivec2(p.x, p.y + (2 + cur) * size.y), vec4(nrm, hit ? dist : 0.0));
    imageStore(accumImage, ivec2(p.x, p.y + 4 * size.y), vec4(albedo, (hit && filterable) ? 1.0 : 0.0));
    return albedo * meanE + meanR;
}
#endif

void countSample(ivec2 p, bool hit) {
    if (((p.x | p.y) & 3) == 0) {
        StatsBuf st = StatsBuf(pc.statsAddr);
        atomicAdd(st.samples, 1u);
        if (hit) {
            atomicAdd(st.hits, 1u);
        }
    }
}

