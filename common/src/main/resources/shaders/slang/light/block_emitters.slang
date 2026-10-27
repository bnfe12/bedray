// Block-emitter next-event estimation for the Slang path tracer.
//
// The record is the one RtEmitters already uploads for the legacy rt_path.comp: six vec4 per emitter, positions relative to
// the block origin the list was rebuilt at. The host therefore binds the same buffer to both paths; the camera-relative
// correction the legacy shader reads from StatsBuf arrives as a push constant here.
//
// The transport kernel is the legacy one, not the physical inverse square: a d^-2 tail left a torch lighting a four-block
// puddle while the rest of the room stayed black (owner report 2026-10-23), so the falloff is d^-1.5 up to two blocks and
// d^-1.25 beyond, windowed at EMITTER_RADIUS. That kernel, and the RIS weight of the whole candidate batch, is folded into
// `radiance` because the legacy p-hat carries the receiver cosine inside the target density while bsdfEvaluate multiplies
// the cosine again; `sourcePdf` stays the honest solid-angle density of the chosen point so ReSTIR can reuse the sample.
//
// Entity-atlas emitters (uvc.w != 0) are skipped: the entity texture is not bound to this pass yet, and a candidate that
// cannot be evaluated must not take part in the weight sum.

public module block_emitters;

import "contracts/light.slang";
import "contracts/math.slang";
import "light/sun.slang";
import "sampling.slang";
import "surface/surface.slang";

static const float EMITTER_RADIUS = 24.0;
static const float EMISSIVE_GAIN = 3.0;
static const float EMISSIVE_FLUX_GAIN = 2.2;
static const uint EMITTER_RECORD_FLOATS = 24u;

struct BlockEmitterRecord {
    float4 corner;    // xyz corner of the emissive quad, w area in blocks^2
    float4 edge1;     // xyz p1 - p0, w power boost of a small source
    float4 edge2;     // xyz p3 - p0, w 0.5 = two-sided / open source, 1.5 = redstone
    float4 uvCorner;  // xy uv(p0), z mean texel emission, w entity atlas slot + 1 (0 = block atlas)
    float4 uvEdges;   // xy uv(p1) - uv(p0), zw uv(p3) - uv(p0)
    float4 tint;      // entity emitter tint multiplier; block emitters are white
};

// Falloff of a source: d^-1.5 up to 2 blocks, then d^-1.25, continuous at the 2-block seam (0.84090 = 2^1.25 / (2*sqrt(2))).
// Written with rsqrt instead of pow: eight of these per NEE call sit in the hottest loop of the emissive pass.
float emitterAttenuation(float d2) {
    float dd = max(d2, 0.25);
    return dd <= 4.0 ? rsqrt(dd * sqrt(dd)) : 0.84090 * rsqrt(dd * sqrt(sqrt(dd)));
}

// The game's own block-light window: a source reaches EMITTER_RADIUS blocks, not the whole ray distance, or a lava column
// ten blocks away painted the walls orange in daylight.
float emitterWindow(float d2, float lightRadius) {
    float window = clamp(1.0 - d2 / (lightRadius * lightRadius), 0.0, 1.0);
    return window * window;
}

float3 emitterAttenuationColor(float3 tex) {
    float m = max(tex.r, max(tex.g, tex.b));
    if (m <= 1.0e-4) {
        return float3(0.0);
    }
    float3 c = tex / m;

    // Category 1: pure redstone (torch, wire, lamp, repeater, ore).
    if (c.r > 0.65 && c.g < 0.22 && c.b < 0.22) {
        return float3(1.0, 0.05, 0.02) * lerp(m, 1.0, 0.35);
    }
    // Category 2: warm flames and firelight (torch, campfire, lantern, lava, candle).
    if (c.r >= 0.55 && c.r > c.b * 1.25 && c.g >= 0.18) {
        float warm = clamp((c.g - 0.20) / 0.60, 0.0, 1.0);
        float3 flame = lerp(float3(1.0, 0.52, 0.10), float3(1.0, 0.76, 0.26), warm);
        return flame * 1.35 * lerp(m, 1.0, 0.45);
    }
    // Category 3: soul fire.
    if (c.b > 0.45 && c.g > 0.35 && c.r < 0.45) {
        return float3(0.18, 0.85, 1.0) * 1.2 * lerp(m, 1.0, 0.4);
    }
    // Category 4: sea lantern and conduit.
    if (c.b > 0.65 && c.g > 0.65 && c.r > 0.35 && c.b > c.r * 1.05) {
        return float3(0.75, 0.94, 1.0) * 1.15 * lerp(m, 1.0, 0.4);
    }
    // Category 5: glowstone, shroomlight, ochre froglight.
    if (c.r > 0.7 && c.g > 0.65 && c.b < 0.45) {
        return float3(1.0, 0.85, 0.42) * 1.2 * lerp(m, 1.0, 0.4);
    }
    // Category 6: amethyst, crying obsidian, nether portal.
    if (c.r > 0.45 && c.b > 0.55 && c.g < 0.45) {
        return float3(0.85, 0.30, 1.0) * 1.2 * lerp(m, 1.0, 0.4);
    }
    // Category 7: end rod and purpur.
    if (c.r > 0.6 && c.b > 0.75 && c.g >= 0.45) {
        return float3(0.92, 0.85, 1.0) * 1.25 * lerp(m, 1.0, 0.4);
    }
    // Category 8: bioluminescence (glow lichen, cave vines, glow berries).
    if (c.g > 0.65 && c.b > 0.35 && c.r < 0.5) {
        return float3(0.45, 1.0, 0.75) * 1.15 * lerp(m, 1.0, 0.4);
    }
    // Default: keep the chromaticity without desaturating it hard.
    return lerp(c, float3(1.0), 0.08) * lerp(m, 1.0, 0.5);
}

// Resampled importance sampling over the emitter list: K stratified points on K importance-sorted quads, one winner.
// The list is sorted by importance, so idx = n * u^2 favours its front and P(i) = sqrt((i+1)/n) - sqrt(i/n).
struct BlockEmitterSampler : ILightSampler {
    StructuredBuffer<BlockEmitterRecord> records;
    MaterialAtlas atlas;
    uint count;
    uint candidates;
    float3 cameraRelative;    // camera position minus the origin the list was rebuilt at
    float maxDistance;        // ray window of the caller; the light radius is min(maxDistance, EMITTER_RADIUS)

    LightSample sample(LightReceiver receiver, inout uint seed) {
        LightSample result = invalidLightSample();
        if (count == 0u || candidates == 0u) {
            return result;
        }
        float lightRadius = min(maxDistance, EMITTER_RADIUS);
        // One per-call shift of the R2 sequence stratifies the quads of one torch evenly instead of by independent points.
        float2 r2shift = float2(rand01(seed), rand01(seed));
        float weightSum = 0.0;
        float chosenPhat = 0.0;
        float chosenProbability = 0.0;
        float chosenArea = 0.0;
        float chosenCosGeometry = 0.0;
        float chosenCosReceiver = 0.0;
        float chosenDistance = 0.0;
        float chosenEndBias = 0.0;
        float3 chosenPosition = float3(0.0);
        float3 chosenColor = float3(0.0);
        uint chosenIndex = 0u;
        float floatCount = float(count);

        for (uint k = 0u; k < candidates; ++k) {
            float u = rand01(seed);
            uint index = min(uint(u * u * floatCount), count - 1u);
            float pIndex = (sqrt(float(index + 1u)) - sqrt(float(index))) * rsqrt(floatCount);
            BlockEmitterRecord emitter = records[index];
            if (uint(emitter.uvCorner.w + 0.5) != 0u) {
                continue;   // entity-atlas emitter: not evaluable in this pass
            }
            float2 ab = frac(r2shift + float(k) * float2(0.7548776662, 0.5698402909));
            float3 point = emitter.corner.xyz - cameraRelative
                + emitter.edge1.xyz * ab.x + emitter.edge2.xyz * ab.y;
            float3 toPoint = point - receiver.position;
            float distance2 = dot(toPoint, toPoint);
            // Both zero tests of the legacy p-hat hoisted in front of the atlas fetch: such a candidate carries no weight.
            // The second one is the self-rejection: a face that is itself a light is not lit by its own quad.
            if (distance2 > lightRadius * lightRadius
                || distance2 < (receiver.emission > 0.5 ? 0.25 : 1.0e-6)) {
                continue;
            }
            float2 uv = emitter.uvCorner.xy + emitter.uvEdges.xy * ab.x + emitter.uvEdges.zw * ab.y;
            float4 materialTexel = atlas.material.SampleLevel(uv, 0.0);
            bool redstoneEmitter = emitter.edge2.w > 1.5;
            bool redstoneTexel = redstoneEmitter
                && ((255u - uint(materialTexel.a * 255.0 + 0.5)) & 4u) != 0u;
            float texelEmission = redstoneTexel ? emitter.uvCorner.z : materialTexel.b * emitter.edge1.w;
            if (texelEmission <= 0.0) {
                continue;
            }
            float3 crossEdge = cross(emitter.edge1.xyz, emitter.edge2.xyz);
            float crossLength = length(crossEdge);
            float3 emitterNormal = crossLength > 1.0e-6 ? crossEdge / crossLength : float3(0.0, 1.0, 0.0);
            bool twoSided = emitter.edge2.w > 0.5;
            float distanceTo = sqrt(distance2);
            float3 direction = toPoint / distanceTo;
            float cosReceiver = max(dot(receiver.normal, direction), 0.0);
            if (cosReceiver <= 0.0) {
                continue;
            }
            float cosEmitter = dot(emitterNormal, -direction);
            // The density uses the true projection; the look kernel keeps the legacy floor so an edge-on torch does not
            // switch off, and a one-sided face is rejected outright.
            float cosGeometry = twoSided ? abs(cosEmitter) : max(cosEmitter, 0.0);
            float cosKernel = twoSided ? max(abs(cosEmitter), 0.25) : max(cosEmitter, 0.0);
            if (cosGeometry <= 1.0e-5 || cosKernel <= 0.0) {
                continue;
            }
            float window = emitterWindow(distance2, lightRadius);
            float phat = texelEmission * cosKernel * cosReceiver * window * emitterAttenuation(distance2);
            if (phat <= 0.0) {
                continue;
            }
            float area = max(emitter.corner.w, 1.0e-6);
            float weight = phat * area / pIndex;
            weightSum += weight;
            if (weight > 0.0 && rand01(seed) * weightSum < weight) {
                float3 albedoTexel = atlas.albedo.SampleLevel(uv, 0.0).rgb;
                // An open source (a flame on its stick, a bulb in its cage) is sampled on faces that may look away from the
                // receiver: without this the ray crosses the body of the source and the stick shadows its own light.
                float endBias = clamp(0.02 * distanceTo, 0.002, 0.03);
                if (twoSided) {
                    endBias = max(endBias, min(0.2, 0.45 * distanceTo));
                }
                chosenPhat = phat;
                chosenProbability = pIndex;
                chosenArea = area;
                chosenCosGeometry = cosGeometry;
                chosenCosReceiver = cosReceiver;
                chosenDistance = distanceTo;
                chosenEndBias = endBias;
                chosenPosition = point;
                chosenColor = redstoneTexel ? float3(1.0, 0.04, 0.02) : emitterAttenuationColor(albedoTexel);
                chosenIndex = index;
            }
        }
        if (chosenPhat <= 0.0) {
            return result;
        }
        float solidAnglePdf = (chosenProbability / chosenArea) * chosenDistance * chosenDistance / chosenCosGeometry;
        if (solidAnglePdf <= 0.0) {
            return result;
        }
        // Legacy NEE returns albedo/PI * Le * transmittance * (W/K), with the receiver cosine already inside W. The BSDF
        // evaluation of this path multiplies the cosine again, so it comes back out here and the pair below reproduces the
        // legacy integrand exactly for K = 1 and its RIS average for K > 1.
        float risWeight = (weightSum / float(candidates)) / max(chosenCosReceiver, 1.0e-4);
        result.valid = true;
        result.kind = LightKind.Emitter;
        result.direction = safeNormalize(chosenPosition - receiver.position, float3(0.0, 1.0, 0.0));
        result.position = receiver.position + result.direction * max(chosenDistance - chosenEndBias, 0.001);
        result.distance = max(chosenDistance - chosenEndBias, 0.001);
        result.radiance = chosenColor * (EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN) * risWeight * solidAnglePdf;
        result.targetPdf = chosenPhat;
        result.sourcePdf = solidAnglePdf;
        result.lightIndex = chosenIndex;
        return result;
    }

    // Re-evaluation of one stored sample at another receiver: the position is projected back onto the quad for its texel.
    LightEvaluation evaluate(LightReceiver receiver, LightSample sample) {
        LightEvaluation result = invalidLightEvaluation();
        if (sample.kind != LightKind.Emitter || sample.lightIndex >= count) {
            return result;
        }
        BlockEmitterRecord emitter = records[sample.lightIndex];
        if (uint(emitter.uvCorner.w + 0.5) != 0u) {
            return result;
        }
        float3 toPoint = sample.position - receiver.position;
        float distance2 = dot(toPoint, toPoint);
        if (distance2 <= 1.0e-6) {
            return result;
        }
        float lightRadius = min(maxDistance, EMITTER_RADIUS);
        if (distance2 > lightRadius * lightRadius) {
            return result;
        }
        float3 direction = toPoint / sqrt(distance2);
        float cosReceiver = max(dot(receiver.normal, direction), 0.0);
        if (cosReceiver <= 0.0) {
            return result;
        }
        float3 crossEdge = cross(emitter.edge1.xyz, emitter.edge2.xyz);
        float crossLength = length(crossEdge);
        float3 emitterNormal = crossLength > 1.0e-6 ? crossEdge / crossLength : float3(0.0, 1.0, 0.0);
        bool twoSided = emitter.edge2.w > 0.5;
        float cosEmitter = dot(emitterNormal, -direction);
        float cosGeometry = twoSided ? abs(cosEmitter) : max(cosEmitter, 0.0);
        float cosKernel = twoSided ? max(abs(cosEmitter), 0.25) : max(cosEmitter, 0.0);
        if (cosGeometry <= 1.0e-5 || cosKernel <= 0.0) {
            return result;
        }
        // The quads of RtEmitters are axis-aligned, so the two edges are orthogonal and the projection is exact.
        float3 local = emitter.corner.xyz - cameraRelative;
        float3 offset = sample.position - local;
        float u = dot(offset, emitter.edge1.xyz) / max(dot(emitter.edge1.xyz, emitter.edge1.xyz), 1.0e-8);
        float v = dot(offset, emitter.edge2.xyz) / max(dot(emitter.edge2.xyz, emitter.edge2.xyz), 1.0e-8);
        float2 uv = emitter.uvCorner.xy + emitter.uvEdges.xy * u + emitter.uvEdges.zw * v;
        float4 materialTexel = atlas.material.SampleLevel(uv, 0.0);
        bool redstoneTexel = emitter.edge2.w > 1.5
            && ((255u - uint(materialTexel.a * 255.0 + 0.5)) & 4u) != 0u;
        float texelEmission = redstoneTexel ? emitter.uvCorner.z : materialTexel.b * emitter.edge1.w;
        if (texelEmission <= 0.0) {
            return result;
        }
        float window = emitterWindow(distance2, lightRadius);
        result.valid = true;
        result.radiance = (redstoneTexel ? float3(1.0, 0.04, 0.02)
            : emitterAttenuationColor(atlas.albedo.SampleLevel(uv, 0.0).rgb)) * (EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN);
        result.targetPdf = texelEmission * cosKernel * cosReceiver * window * emitterAttenuation(distance2);
        result.sourcePdf = sample.sourcePdf;
        return result;
    }
};

// One branch of the key light, one of the emitters: the selection probability belongs to the composite because the
// estimator has to divide by the density of the branch that produced the sample.
struct SunAndBlockEmitterSampler : ILightSampler {
    SunMoonSampler keyLight;
    BlockEmitterSampler emitters;
    float emitterProbability;

    LightSample sample(LightReceiver receiver, inout uint seed) {
        bool hasEmitters = emitters.count > 0u && emitters.candidates > 0u && emitterProbability > 0.0;
        float sunIntensity = luminance(keyLight.sun.color.rgb) * max(keyLight.sun.color.w, 0.0)
            + luminance(keyLight.sun.moonColor.rgb) * max(keyLight.sun.moonColor.w, 0.0);
        if (!hasEmitters || sunIntensity <= 0.0) {
            // One branch only: its density is already the whole density, so nothing to renormalise.
            if (hasEmitters && sunIntensity <= 0.0) {
                LightSample emitterSample = emitters.sample(receiver, seed);
                if (emitterSample.valid && emitterProbability < 1.0) {
                    emitterSample.sourcePdf /= emitterProbability;
                }
                return emitterSample;
            }
            return keyLight.sample(receiver, seed);
        }
        if (rand01(seed) < emitterProbability) {
            LightSample emitterSample = emitters.sample(receiver, seed);
            if (emitterSample.valid) {
                emitterSample.sourcePdf /= emitterProbability;
            }
            return emitterSample;
        }
        LightSample sunSample = keyLight.sample(receiver, seed);
        if (sunSample.valid) {
            sunSample.sourcePdf /= 1.0 - emitterProbability;
        }
        return sunSample;
    }

    LightEvaluation evaluate(LightReceiver receiver, LightSample sample) {
        if (sample.kind == LightKind.Emitter) {
            return emitters.evaluate(receiver, sample);
        }
        return keyLight.evaluate(receiver, sample);
    }
};
