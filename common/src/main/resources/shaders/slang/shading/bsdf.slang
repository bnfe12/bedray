// BSDF evaluation and sampling for Bedray 2.0.
//
// The shading model is intentionally small and explicit:
//
//   * a Lambert diffuse lobe for dielectric / rough surfaces
//   * a GGX specular lobe for metals and dielectric reflections
//   * a diffuse transmission lobe for thin translucent panes
//   * a Fresnel-selected specular transmission lobe for optical interfaces
//
// `bsdfEvaluate` returns f * cos(theta), the quantity a path tracer multiplies
// by incident radiance.  `bsdfPdf` returns the full reflection mixture density
// at a direction; `BSDFSample.pdf` is only the selected lobe's density, which
// is the density the per-lobe `weight` compensates for.  A delta lobe (perfect
// mirror or refraction) reports pdf = 1 and `delta = true`; a caller must not
// use it for MIS.

public module bsdf;

import "contracts/material.slang";
import "contracts/math.slang";
import "sampling.slang";
import "shading/microfacet.slang";

static const uint BSDF_LOBE_NONE         = 0u;
static const uint BSDF_LOBE_DIFFUSE      = 1u << 0u;
static const uint BSDF_LOBE_SPECULAR     = 1u << 1u;
static const uint BSDF_LOBE_TRANSMISSION = 1u << 2u;

struct BSDFSample {
    bool   valid;
    bool   delta;
    float3 direction;
    float3 weight;      // f * cos / pdf, already divided
    float  pdf;
    float  eta;
    uint   lobe;
};

BSDFSample invalidBSDFSample() {
    BSDFSample sample;
    sample.valid = false;
    sample.delta = false;
    sample.direction = float3(0.0, 1.0, 0.0);
    sample.weight = float3(0.0);
    sample.pdf = 0.0;
    sample.eta = 1.0;
    sample.lobe = BSDF_LOBE_NONE;
    return sample;
}

float3 bsdfReflectionAlbedo(MaterialSample material) {
    return material.albedo * (1.0 - material.metallic) *
        (1.0 - material.transmission);
}

// A metal reflects its base colour; a dielectric reflects its scalar f0.
// Keeping this in one helper prevents the specular lobes from disagreeing
// about which value is F0.
float3 bsdfF0(MaterialSample material) {
    return materialIsMetal(material) ? material.albedo : material.f0;
}

// Probability of choosing the specular reflection lobe.  Metals are pure
// specular; a dielectric uses its Fresnel reflectance at the view angle.
float bsdfSpecularProbability(MaterialSample material, float3 V, float3 N) {
    if (materialIsMetal(material)) return 1.0;

    float NdotV = max(dot(N, V), 0.0);
    float f0 = clamp(luminance(bsdfF0(material)), 0.0, 1.0);
    float fresnel = fresnelSchlickScalar(NdotV, f0);

    if (material.alphaMode == AlphaMode.Optical && material.transmission > 0.0) {
        return clamp(fresnel, 0.02, 0.98);
    }
    return clamp(fresnel, 0.04, 0.96);
}

// f * cos(theta) for reflection (diffuse + GGX).  Transmission is handled by
// bsdfSample; NEE only asks about directions on the receiver's side, so a
// light behind a pane cannot contribute direct radiance through this call.
float3 bsdfEvaluate(MaterialSample material, float3 V, float3 N, float3 L) {
    float NdotL = dot(N, L);
    if (NdotL <= 0.0) return float3(0.0);

    float NdotV = max(dot(N, V), 1.0e-4);
    float3 H = safeNormalize(V + L, N);
    float NdotH = max(dot(N, H), 0.0);
    float VdotH = max(dot(V, H), 0.0);
    float alpha = max(material.roughness * material.roughness, 1.0e-4);

    float3 f0 = bsdfF0(material);
    float3 fresnel = fresnelSchlick(VdotH, f0);
    float3 fresnelView = fresnelSchlick(NdotV, f0);
    // A delta optical transmission has no diffuse reflection lobe to evaluate;
    // otherwise NEE would light a lobe the sampler cannot produce.
    bool optical = material.alphaMode == AlphaMode.Optical && material.transmission > 0.0;
    float3 diffuse = optical ? float3(0.0)
        : bsdfReflectionAlbedo(material) * (float3(1.0) - fresnelView) * INV_PI;

    float distribution = ggxNDF(NdotH, alpha);
    float geometry = ggxSmithG2(NdotV, max(NdotL, 1.0e-4), alpha);
    float3 specular = distribution * geometry * fresnel /
        (4.0 * NdotV * max(NdotL, 1.0e-4));

    return (diffuse + specular) * NdotL;
}

// Solid-angle density of bsdfSample for direction L.  A delta optical
// transmission has no finite density and returns 0; the path tracer uses
// BSDFSample.delta instead of asking for its pdf.
float bsdfPdf(MaterialSample material, float3 V, float3 N, float3 L) {
    float NdotL = dot(N, L);
    float specularProbability = bsdfSpecularProbability(material, V, N);
    bool thinPane = material.alphaMode == AlphaMode.Translucent;
    bool optical = material.alphaMode == AlphaMode.Optical && material.transmission > 0.0;

    if (NdotL <= 0.0) {
        // A thin pane samples diffuse transmission through the back side; an
        // optical interface refracts as a delta lobe, which has no density.
        if (thinPane) {
            float transmission = clamp(material.transmission, 0.0, 1.0);
            return (1.0 - specularProbability) * transmission * cosinePdf(-N, L);
        }
        return 0.0;
    }

    float pdf = 0.0;
    if (!optical) {
        // An optical transmission sample is delta, so it contributes no diffuse
        // reflection lobe either; a thin pane splits the diffuse branch between
        // reflection and transmission by the same weight the sampler uses.
        float diffuseFraction = thinPane
            ? max(1.0 - material.transmission, 0.0) : 1.0;
        pdf += (1.0 - specularProbability) * diffuseFraction * cosinePdf(N, L);
    }
    if (specularProbability > 0.0) {
        pdf += specularProbability *
            ggxReflectionPdf(V, N, L, material.roughness);
    }
    return pdf;
}

// Samples one lobe and returns the full throughput multiplier.  The caller
// multiplies its path throughput by `weight`; no separate 1/pdf is needed.
BSDFSample bsdfSample(MaterialSample material, float3 V, float3 N, inout uint seed) {
    BSDFSample result = invalidBSDFSample();

    float NdotV = max(dot(N, V), 1.0e-4);
    float specularProbability = bsdfSpecularProbability(material, V, N);
    bool chooseSpecular = rand01(seed) < specularProbability;

    if (chooseSpecular) {
        float alpha = max(material.roughness * material.roughness, 1.0e-4);
        float3 tangent;
        float3 bitangent;
        basisAround(N, tangent, bitangent);

        float3 viewLocal = float3(dot(V, tangent), dot(V, bitangent), dot(V, N));
        float2 u = float2(rand01(seed), rand01(seed));
        float3 halfLocal = sampleGGXVNDF(viewLocal, alpha, u);
        float3 lightLocal = reflect(-viewLocal, halfLocal);
        // A visible-normal reflection can land below the shading horizon.  The
        // single-scattering BRDF is zero there, so this is a legitimate
        // zero-energy sample, not a broken one: the caller keeps the emission
        // and NEE it already accumulated and stops the path.  Do not resample
        // until the direction is above the horizon without reweighting by the
        // conditional density; that would add energy instead of removing it.
        if (lightLocal.z <= 0.0) return result;

        float3 L = safeNormalize(tangent * lightLocal.x +
            bitangent * lightLocal.y + N * lightLocal.z, N);

        float VdotH = max(dot(viewLocal, halfLocal), 0.0);
        float NdotL = max(lightLocal.z, 1.0e-4);
        float3 f0 = bsdfF0(material);
        float3 fresnel = fresnelSchlick(VdotH, f0);
        float geometry2 = ggxSmithG2(NdotV, NdotL, alpha);
        float geometry1 = ggxSmithG1(NdotV, alpha);

        result.valid = true;
        result.delta = material.roughness <= 1.0e-3;
        result.direction = L;
        // Each branch carries only its own lobe, so `weight` is that lobe's
        // f*cos over its sampling density: the selection probability times the
        // conditional GGX density.  Both have to be divided out.
        result.weight = fresnel * (geometry2 / max(geometry1, 1.0e-6))
            / max(specularProbability, 1.0e-6);
        result.pdf = specularProbability *
            ggxReflectionPdf(V, N, L, material.roughness);
        result.eta = 1.0;
        result.lobe = BSDF_LOBE_SPECULAR;
        return result;
    }

    float transmissionProbability = max(1.0 - specularProbability, 1.0e-6);
    if (material.alphaMode == AlphaMode.Optical && material.transmission > 0.0) {
        float eta = 1.0 / max(material.ior, 1.0);
        float3 refracted = refract(-V, N, eta);
        float3 fresnel = fresnelSchlick(NdotV, bsdfF0(material));

        if (dot(refracted, refracted) <= 1.0e-8) {
            // Total internal reflection: the interface becomes a mirror.
            result.valid = true;
            result.delta = true;
            result.direction = safeNormalize(reflect(-V, N), N);
            result.weight = float3(1.0) / transmissionProbability;
            result.pdf = 1.0;
            result.eta = 1.0;
            result.lobe = BSDF_LOBE_SPECULAR;
            return result;
        }

        result.valid = true;
        result.delta = true;
        result.direction = safeNormalize(refracted, -N);
        result.weight = material.albedo * material.transmission *
            (float3(1.0) - fresnel) * (eta * eta) / transmissionProbability;
        result.pdf = 1.0;
        result.eta = eta;
        result.lobe = BSDF_LOBE_TRANSMISSION;
        return result;
    }

    // Diffuse reflection, or diffuse transmission for a thin pane.  The pane
    // splits its diffuse energy by transmission, so the density and weight
    // carry the branch probability exactly as bsdfPdf reports it.
    bool thinPane = material.alphaMode == AlphaMode.Translucent;
    float transmission = thinPane ? clamp(material.transmission, 0.0, 1.0) : 0.0;
    bool transmit = thinPane && (transmission >= 1.0 || rand01(seed) < transmission);
    float3 lobeNormal = transmit ? -N : N;
    float3 L = cosineAround(lobeNormal, seed);
    float cosTheta = max(dot(lobeNormal, L), 0.0);
    if (cosTheta <= 0.0) return result;

    float3 fresnelView = fresnelSchlick(NdotV, bsdfF0(material));
    float branchProbability = transmissionProbability;
    float3 albedo;
    if (transmit) {
        albedo = material.albedo * (1.0 - material.metallic) * transmission *
            (float3(1.0) - fresnelView);
        branchProbability *= transmission;
    } else {
        albedo = bsdfReflectionAlbedo(material) * (float3(1.0) - fresnelView);
        if (thinPane) branchProbability *= max(1.0 - transmission, 0.0);
    }

    result.valid = true;
    result.delta = false;
    result.direction = L;
    result.weight = albedo / max(branchProbability, 1.0e-6);
    result.pdf = branchProbability * cosTheta * INV_PI;
    result.eta = 1.0;
    result.lobe = transmit ? BSDF_LOBE_TRANSMISSION : BSDF_LOBE_DIFFUSE;
    return result;
}
