public module rc_field_gather;
import "pathtrace/diffuse_field.slang";
__exported import "radiance_cascades/field_transport.slang";
import "radiance_cascades/rc_trace_options.slang";

struct RCPathDiffuseField : IPathDiffuseField {
    RCFieldConstants field;
    StructuredBuffer<RCSegment> tails;
    uint frameIndex;
    bool active() { return true; }

    DiffuseFieldSample evaluate<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
            T traversal, L lights, E environment, MaterialAtlas atlas, BlockMaterialTable table,
            float3 position, float3 normal, MaterialSamplingOptions materialOptions,
            float rayBias, float maxRadiance, inout uint seed) {
        DiffuseFieldSample result;
        result.cosineMean = float3(0.0); result.terminalKnown = false;
        result.status = DIFFUSE_FIELD_INVALID;
        if (!rcFieldMetadataValid(field) || field.tag.x != frameIndex
            || !all(isfinite(position)) || !all(isfinite(normal))
            || !(dot(normal,normal) > 0.0) || !isfinite(dot(normal,normal))
            || !isfinite(rayBias) || !(rayBias > 0.0)
            || !isfinite(maxRadiance) || !(maxRadiance > 0.0)) return result;
        int3 base; float3 fraction;
        // The finite field owns only its explicitly supported region. Outside
        // it the ordinary path continues, with a separate diagnostic flag.
        if (!rcFieldNeighbors(field, 0u, position, base, fraction)) {
            result.status = DIFFUSE_FIELD_OUTSIDE; return result;
        }
        normal = normalize(normal);
        float3 origin = position + normal * rayBias;
        if (!all(isfinite(origin))) return result;
        RCTraceOptions options = defaultRCTraceOptions();
        options.rayBias = rayBias; options.maxRadiance = maxRadiance;
        bool known = true;
        float3 irradiance = float3(0.0);
        for (uint n = 0u; n < 8u; ++n) {
            float spatialWeight = rcFieldNeighborWeight(fraction, n);
            if (spatialWeight == 0.0) continue;
            uint3 neighbor = rcFieldNeighbor(base, n);
            float3 q = rcFieldProbe(field, 0u, neighbor);
            // D0=1 endpoint mesh is an inscribed cube. Valid interpolation
            // offsets (<=1 per axis) lie strictly inside its 4/sqrt(3) inradius.
            if (any(abs(origin - q) >= float3(field.ranges[0].x / sqrt(3.0)))) return result;
            for (uint ray = 0u; ray < 6u; ++ray) {
                float3 corners[4]; rcFieldCorners(ray, 1u, corners);
                for (uint k = 0u; k < 4u; ++k)
                    corners[k] = normalize(q + field.ranges[0].x * corners[k] - origin);
                float projected = rcFieldProjectedArea(corners, normal);
                if (projected == 0.0) continue;
                float3 b = q + field.ranges[0].x * rcFieldDirection(ray, 1u);
                RCSegment front = rcFieldTrace(traversal, lights, environment, atlas, table,
                    origin, b, 0u, materialOptions, options, seed);
                RCSegment sample = rcFieldCompose(front, tails[rcFieldIndex(field, 0u, neighbor, ray)]);
                if (!rcSegmentValid(sample)) return result;
                irradiance += spatialWeight * projected * sample.radiance;
                known = known && ((sample.flags & RC_FIELD_TERMINAL_KNOWN) != 0u);
            }
        }
        if (!all(isfinite(irradiance)) || any(irradiance < float3(0.0))) return result;
        result.cosineMean = irradiance * (1.0 / 3.14159265358979323846);
        result.status = DIFFUSE_FIELD_VALID; result.terminalKnown = known;
        return result;
    }
}
