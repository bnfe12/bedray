// Ambient occlusion query: cosine-weighted rays within radius around s.ng
float traceAO(Surface s, float radius, uint samples, inout uint seed) {
    if (radius <= 0.001 || samples == 0u) {
        return s.mapAo;
    }
    vec3 o = offsetOrigin(s);
    vec3 tangent;
    vec3 bitangent;
    basisAround(s.ng, tangent, bitangent);
    float open = 0.0;
    for (uint i = 0u; i < samples; i++) {
        vec3 h = cosineHemisphere(vec2(rand01(seed), rand01(seed)));
        vec3 d = tangent * h.x + bitangent * h.y + s.ng * h.z;
        open += traceVisibility(o, d, radius);
    }
    return (open / float(samples)) * s.mapAo;
}

// Sky light by ray tracing. Replaces the baked vanilla sky light (vertex light .y, which made blocks darken with the distance
// to the player and inside rooms depending on the vertex data): `samples` cosine-weighted rays around the normal, each one
// either escapes (radiance of the sky in its direction, skyRadiance: the same dome the path tracer sees on a miss) or is blocked.
// The mean is the sky irradiance / PI, equal to hemisphereAmbient for an open surface. Under a roof, in a cave or in a corridor the
// sky is really occluded. A blocked ray towards the ground counts as the warm ground bounce (explicit ambient floor, the path
// tracer gets it from real bounces); there is no vertex light in it.
const float SKY_REACH = 160.0;

// Optional baked vanilla light fill (RtSettings baked_light_fill_pct / -Dvkrtx.rt.bakedLightFill), default 0 = fully traced light.
// Rays mode only: the vanilla vertex light of the surface is mixed back with this strength (path mode never reads it).
float bakedLightFill() {
    return pc.statsAddr == uvec2(0u) ? 0.0 : StatsBuf(pc.statsAddr).cameraForward.w;
}
// sky term x mix(1, vanilla sky light, fill)
vec3 applyBakedSky(vec3 skyLight, Surface s) {
    float f = bakedLightFill();
    return f <= 0.0 ? skyLight : skyLight * mix(1.0, max(s.light.y, 0.02), f);
}
// warm vanilla block light fill (fill x light.x), tinted by the real lightmap hue
vec3 bakedBlockFill(Surface s) {
    float f = bakedLightFill();
    return f <= 0.0 ? vec3(0.0) : blockLightColor() * s.light.x * f;
}
// How much of the block light of a surface has to come from the baked vanilla field instead of the emitter list. The list is
// ordered by importance FROM THE CAMERA (RtEmitters: emission * area / distance^2) and every candidate farther than
// EMITTER_RADIUS from the shaded point is rejected, so a receiver far from the player gets almost no usable candidate: the
// torches that stand around it are sampled ~1 / d^2 as often as the ones next to the camera and the rest of the time the
// estimate is exactly zero. That is why a village, a lit cave or a lamp on the far bank read as unlit, while the same lamp
// lights its surroundings as soon as the player walks up to it. The list itself only holds emitters within `listRange` of the
// camera, and an emitter stands at most EMITTER_RADIUS away from the surface it lights, so the list stops being complete
// already at `listRange - EMITTER_RADIUS`: a receiver there can be lit by a torch that is just outside the list. The handover
// is therefore the band `listRange -+ EMITTER_RADIUS`, and past it the vanilla block-light field (vertex light.x, the
// propagation the game itself shows for that same torch) is the whole term. Near the player the traced estimate stays the
// only source, so no baked light appears in the room around the camera.
float blockLightFarMix(float cameraDist, float listRange) {
    // The inner edge is floored: rays_emissive_distance can be as low as 8, and a negative edge would put baked light on the
    // surfaces around the player, which is the leak the traced emitters replaced.
    return smoothstep(max(listRange - EMITTER_RADIUS, 8.0), listRange + EMITTER_RADIUS, cameraDist);
}
// The vanilla lightmap is a display value, not radiance: its block-light texel is ~0.87 at level 13, while the traced estimate
// of that same torch on that same floor is ~0.05. The far field only has to carry the light at the scale the player already
// sees around the torch, so the field is divided by ~20. A single gain keeps the shape: light.x falls like the level (13 -> 1
// over 15 blocks) and the traced attenuation falls like d^-1.25 over the same span.
const float BLOCK_LIGHT_FIELD_GAIN = 0.05;
vec3 vanillaBlockIrradiance(Surface s) {
    return blockLightColor() * clamp(s.light.x, 0.0, 1.0) * BLOCK_LIGHT_FIELD_GAIN;
}
// traced: the NEE estimate with albedo excluded (sampleEmitters(s, vec3(1.0), ...))
vec3 distantBlockLight(vec3 traced, Surface s, float cameraDist, float listRange) {
    float far = blockLightFarMix(cameraDist, listRange);
    if (far <= 0.0) {
        return traced;
    }
    // mix, not max: the traced term is a 1..4 spp estimator that reads 0 on most pixels of the band, so max() would add the
    // whole field on top of an average that is already lower and leave a bright ring around the player.
    return mix(traced, vanillaBlockIrradiance(s), far);
}
const float GROUND_BOUNCE_BLOCKED = 0.72;
vec3 tracedSkyLight(Surface s, uint samples, inout uint seed) {
    vec3 o = offsetOrigin(s);
    vec3 tangent;
    vec3 bitangent;
    basisAround(s.ng, tangent, bitangent);
    vec3 sum = vec3(0.0);
    float skyFill = clamp(s.light.y, 0.0, 1.0);
    float canopyTrans = 0.32 * skyFill * skyFill;
    for (uint i = 0u; i < max(samples, 1u); i++) {
        vec3 h = cosineHemisphere(vec2(rand01(seed), rand01(seed)));
        vec3 d = tangent * h.x + bitangent * h.y + s.ng * h.z;
        if (traceVisibility(o, d, SKY_REACH) > 0.5) {
            sum += skyRadiance(d);
        } else if (d.y < 0.0) {
            // The ground bounce fades with the vanilla sky light: in a sealed room (sky level 0) blocked downward rays
            // must not add radiance, or interiors never get dark (the leak the user reported).
            sum += skyRadiance(d) * GROUND_BOUNCE_BLOCKED * skyFill;
        } else if (canopyTrans > 0.0) {
            sum += skyRadiance(d) * canopyTrans;
        }
    }
    return sum / float(max(samples, 1u));   // the caller applies AO / mapAo
}

// Shading of a surface seen by a secondary ray (reflection / GI): sun with one hard shadow ray (when shadowRay), traced
// sky light, emitters (NEE with visibility rays), emission, PBR reflection and specular highlight.
vec3 shadeSimple(Surface s, vec3 dir, bool shadowRay) {
    vec3 o = offsetOrigin(s);
    vec3 L = pc.keyDir.xyz;
    float ndotl = max(dot(s.ns, L), 0.0);
    float ngdotl = dot(s.ng, L);
    vec3 sunT = vec3(0.0);
    // Light through a thin leaf card: sunT stayed zero for a back-lit plant, so the transmission term below multiplied by zero
    // (every plant on a slope lit by the same sun, seen by a reflection or a GI ray, came out black). The shadow ray starts on
    // the sun side of the card so the blade does not shadow itself.
    float leafBack = s.isFoliage ? max(dot(-s.ng, L), 0.0) : 0.0;
    if (keyLightOn() && ndotl > 0.0 && ngdotl > 0.0) {
        // Pixel shadows reach secondary shading too: a mirror or a bounce that lands on a shadowed floor must show the same
        // shadow edges the floor has when seen directly (rt_common_pixel_shadow.glsl). Built here, where the ray is really
        // traced: the geometry validation inside the origin is a ray of its own. Nothing on this path is refracted.
        vec3 shadowO = shadowRay && pixelShadowApplies(s, false) ? pixelShadowOrigin(s, o) : o;
        sunT = shadowRay ? traceTransmittance(shadowO, L, 2048.0) : vec3(1.0);
    } else if (keyLightOn() && leafBack > 0.0) {
        sunT = shadowRay ? traceTransmittance(leafShadowOrigin(s, L), L, 2048.0) : vec3(1.0);
    }
    float terminator = smoothstep(0.0, 0.08, ngdotl);

    // Contact ambient occlusion for reflections: stable seed from world position. Respect AO-off; otherwise every
    // secondary reflection silently pays for two visibility rays even when the user disabled AO.
    float aoRad = max(StatsBuf(pc.statsAddr).aoRadius, 0.0);
    uint aoSeed = pcgHash(uint(int(floor(s.worldPos.x * 8.0)) * 73856093 ^ int(floor(s.worldPos.y * 8.0)) * 19349663 ^ int(floor(s.worldPos.z * 8.0)) * 83492791));
    float ao = aoRad <= 0.001 ? s.mapAo : traceAO(s, clamp(aoRad, 1.0, 3.0), 2u, aoSeed);

    uint skySeed = aoSeed ^ 0x9E3779B9u;
    vec3 ambient = applyBakedSky(tracedSkyLight(s, 1u, skySeed), s) * ao;
    vec3 directSun = pc.keyColor.rgb * ndotl * sunT * terminator;
    if (leafBack > 0.0) {
        directSun += pc.keyColor.rgb * (leafBack * 0.42) * smoothstep(0.0, 0.08, leafBack) * sunT * vec3(1.05, 1.25, 0.75);
    }
    vec3 direct = directSun + bakedBlockFill(s) * ao;
    vec3 diffuseLight = direct + ambient;

    // Stratified emitter sampling with stable seed (3 samples) for reflection stability. These world-cell seeds (ao, sky, em)
    // belong to secondary reflection shading only: a camera-visible surface is shaded per pixel and per frame by the path
    // tracer / ray pass, where a quantised seed would draw a grid that the denoiser keeps (see primarySkyFill in rt_path.comp).
    if (hasFlag(FLAG_EMISSIVE) && emitterCount() > 0u) {
        uint emSeed = pcgHash(uint(int(floor(s.worldPos.x * 4.0)) * 73856093 ^ int(floor(s.worldPos.y * 4.0)) * 19349663 ^ int(floor(s.worldPos.z * 4.0)) * 83492791));
        const uint EM_SAMPLES = 3u;
        vec3 emLight = vec3(0.0);
        for (uint i = 0u; i < EM_SAMPLES; i++) {
            emLight += sampleEmitters(s, vec3(1.0), 32.0, emSeed);
        }
        diffuseLight += emLight / float(EM_SAMPLES);
    }
    vec3 diffuse = s.albedoLit * diffuseLight;

    // Specular / PBR reflection when incident ray direction dir is present
    if (dot(dir, dir) > 1.0e-4) {
        vec3 V = -dir;
        float cosV = max(dot(V, s.ns), 0.0);
        vec3 f0 = s.metal > 0.5 ? s.albedoTex : vec3(s.f0);
        vec3 F = fresnelEnergySplit(f0, cosV, s.roughness, s.metal > 0.5);
        // Energy split: metals have no diffuse exitance, a dielectric keeps (1 - F) of it.
        vec3 kD = s.metal > 0.5 ? vec3(0.0) : vec3(1.0) - F;
        bool mirror = s.roughness * s.roughness <= 1.0e-3 || cosV <= 1.0e-3;

        // Specular sun highlight with Cook-Torrance GGX microfacet BRDF. A mirror surface gets the sun/moon body from the
        // reflected sky (skyAt below); adding this lobe on top would count the key light twice.
        vec3 sunSpec = vec3(0.0);
        if (!mirror && keyLightOn() && ndotl > 0.0 && length(sunT) > 0.001) {
            vec3 specGGX = brdfSpecularGGX(s.ns, V, L, max(s.roughness, 0.04), f0);
            sunSpec = pc.keyColor.rgb * sunT * specGGX * ndotl;
        }

        // Environment reflection for reflective materials (metals and smooth dielectrics)
        if (s.metal > 0.5 || s.roughness < 0.6) {
            vec3 rdir = bendHorizon(reflect(dir, s.ns), s.ng);
            vec3 refl = (mirror ? skyAt(rdir) : skyEnvRadiance(rdir, s.roughness)) * F;
            return diffuse * kD + refl + sunSpec + emitted(s);
        }
        return diffuse * kD + sunSpec + emitted(s);
    }

    return diffuse * (s.metal > 0.5 ? vec3(0.0) : vec3(1.0)) + emitted(s);
}

vec3 shadeSimple(Surface s, bool shadowRay) {
    return shadeSimple(s, vec3(0.0), shadowRay);
}

// Reflected hit: direct sunlight with one shadow ray, ONE contact-AO ray and ONE emitter sample (was: none, to avoid a cost of up
// to six extra traversals; the single samples keep it at about three), vanilla ambient/block light, material Fresnel and emission.
vec3 shadeReflectionHit(Surface s, vec3 dir, bool shadowRay) {
    vec3 o = offsetOrigin(s);
    vec3 L = pc.keyDir.xyz;
    float ndotl = max(dot(s.ns, L), 0.0);
    float ngdotl = dot(s.ng, L);
    vec3 sunT = vec3(0.0);
    // Same thin-card transmission as shadeSimple: a plant mirrored by water is seen from whichever side faces the camera, and
    // every back-lit one (sun behind the card) lost the key light completely. Sun-side shadow origin, no self-shadowing.
    float leafBack = s.isFoliage ? max(dot(-s.ng, L), 0.0) : 0.0;
    if (keyLightOn() && ndotl > 0.0 && ngdotl > 0.0) {
        // Same per-cell origin as shadeSimple: a reflected floor and the floor itself must not disagree about their shadows.
        vec3 shadowO = shadowRay && pixelShadowApplies(s, false) ? pixelShadowOrigin(s, o) : o;
        sunT = shadowRay ? traceTransmittance(shadowO, L, 2048.0) : vec3(1.0);
    } else if (keyLightOn() && leafBack > 0.0) {
        sunT = shadowRay ? traceTransmittance(leafShadowOrigin(s, L), L, 2048.0) : vec3(1.0);
    }
    float terminator = smoothstep(0.0, 0.08, ngdotl);
    // One contact-AO ray and one emitter sample per reflected hit, random per pixel and frame (the temporal accumulation of the
    // mirror signal resolves it): the pillars in a reflection get their contact shadows and the lamps light the mirror world.
    // The old stable-seed shortcut left reflected geometry flat lit and dark next to lamps (owner: "lamps missing in the mirror").
    uint rs = pcgHash(gl_GlobalInvocationID.x * 1973u + gl_GlobalInvocationID.y * 9277u + pc.frame * 26699u + 99u);
    float ao = traceAO(s, 1.6, 1u, rs);
    // sky light and block light are traced (sky rays, emitter NEE with visibility rays); no baked vertex light
    vec3 ambient = applyBakedSky(shadowRay ? tracedSkyLight(s, 1u, rs) : hemisphereAmbient(s.ns.y, pc.keyDir.w), s) * ao;
    vec3 emLight = vec3(0.0);
    if (hasFlag(FLAG_EMISSIVE) && emitterCount() > 0u) {
        emLight = sampleEmitters(s, vec3(1.0), 24.0, rs);
    }
    vec3 diffuse = s.albedoLit * (pc.keyColor.rgb * ndotl * sunT * terminator + pc.keyColor.rgb * (leafBack * 0.42)
        * smoothstep(0.0, 0.08, leafBack) * sunT * vec3(1.05, 1.25, 0.75) + ambient + emLight + bakedBlockFill(s) * ao);
    vec3 glow = emitted(s);

    if (dot(dir, dir) <= 1.0e-4) {
        return diffuse * (s.metal > 0.5 ? vec3(0.0) : vec3(1.0)) + glow;
    }
    vec3 V = -dir;
    float cosV = max(dot(V, s.ns), 0.0);
    vec3 f0 = s.metal > 0.5 ? s.albedoTex : vec3(s.f0);
    vec3 F = fresnelEnergySplit(f0, cosV, s.roughness, s.metal > 0.5);
    vec3 kD = s.metal > 0.5 ? vec3(0.0) : vec3(1.0) - F;
    bool mirror = s.roughness * s.roughness <= 1.0e-3 || cosV <= 1.0e-3;
    vec3 sunSpec = vec3(0.0);
    if (!mirror && keyLightOn() && ndotl > 0.0 && length(sunT) > 0.001) {
        sunSpec = pc.keyColor.rgb * sunT * brdfSpecularGGX(s.ns, V, L, max(s.roughness, 0.04), f0) * ndotl;
    }
    if (s.metal > 0.5 || s.roughness < 0.6) {
        vec3 rdir = bendHorizon(reflect(dir, s.ns), s.ng);
        return diffuse * kD + (mirror ? skyAt(rdir) : skyEnvRadiance(rdir, s.roughness)) * F + sunSpec + glow;
    }
    return diffuse * kD + sunSpec + glow;
}

// Deterministic radiance arriving along a mirror direction: sky with clouds and the sun/moon disk on a miss, else the lit hit.
// Ensures off-screen reflection support via hermetic TLAS traversal (mask 0x03: MASK_WORLD | MASK_ENTITIES).
// m: water body the mirror ray starts in (camera under water looking at the surface from below): the ray is attenuated and
// in-scatters like every other ray in the water.
vec3 mirrorRadianceM(vec3 origin, vec3 dir, float reach, bool shadowRay, WaterMedium m) {
    Surface r;
    vec3 f;
    vec3 ins;
    if (traceThroughInterfacesM(origin, dir, reach, m, r, f, ins)) {
        return f * shadeReflectionHit(r, dir, shadowRay) + ins;
    }
    vec3 sky = skyAt(dir);
    if (dir.y <= 0.0) {
        sky = skyRadiance(dir);   // no geometry below the horizon in range: the same ground dome the path tracer sees on a miss
    }
    return f * sky + ins;
}

vec3 mirrorRadiance(vec3 origin, vec3 dir, float reach, bool shadowRay) {
    return mirrorRadianceM(origin, dir, reach, shadowRay, noWater());
}

// Reflection of an interface: at F < 5 % of a water face (steep views) the reflected radiance is only the sky (plus the sun glint),
// no scene traversal; from under the surface such a reflection is dark water and is dropped. ONE mirrorRadianceM call site only
// (every inlined copy of the walk and the reflected-hit shading costs the whole shader performance).
vec3 interfaceMirror(bool skyOnly, vec3 origin, vec3 rdir, float reach, bool shadow, WaterMedium m) {
    if (skyOnly) {
        return m.inside ? vec3(0.0) : skyAt(rdir);
    }
    return mirrorRadianceM(origin, rdir, reach, shadow, m);
}
