// Typed Radiance Cascades trace: distance-band ownership proposal.
//
// This module is not used by the default trace entries and is not a
// cross-cascade resolve.  It gives the resolve owner one concrete,
// non-overlapping distance partition of the native ranges: retrace cascade c
// over (far(c-1), far(c)] so its near/far split lies wholly inside that band.
// Band 0 starts at the native near plane (0.5); the first half block is not
// covered by any band.  The terminal band ends at the native far distance (64);
// if the world boundary is farther, the host must raise that cascade's far
// override to the boundary distance for the environment policy to admit sky.
//
// Two caveats the helper cannot encode: trace windows are closed at both ends,
// so adjacent bands both contain a surface exactly on their shared boundary
// and the resolve must assign that boundary explicitly; and band distances are
// measured from each probe origin, not from the shading point, so the band
// label is not directly a shading-point distance.
//
// A caller that uses this proposal still owns the estimator, weights and
// environment handoff.  Probe lattices and ray sets differ across cascades, so
// records from different cascades cannot be zipped or composed by index.

public module rc_trace_ownership;

__exported import "radiance_cascades.slang";
__exported import "radiance_cascades/rc_trace_options.slang";

struct RCCascadeOwnershipBand {
    float nearDistance;
    float farDistance;
    float splitDistance;
};

bool rcCascadeOwnershipBand(uint cascade, out RCCascadeOwnershipBand band) {
    band.nearDistance = 0.0;
    band.farDistance = 0.0;
    band.splitDistance = 0.0;
    if (cascade >= RADIANCE_CASCADE_COUNT) return false;

    RadianceCascadeDesc desc = radianceCascade(cascade);
    float nearValue = cascade == 0u
        ? max(desc.nearDistance, 0.0)
        : radianceCascade(cascade - 1u).farDistance;
    if (!(desc.farDistance > nearValue)) return false;

    band.nearDistance = nearValue;
    band.farDistance = desc.farDistance;
    band.splitDistance = nearValue + 0.5 * (desc.farDistance - nearValue);
    return true;
}

RCTraceOptions rcApplyCascadeOwnershipBand(RCTraceOptions options, uint cascade) {
    RCCascadeOwnershipBand band;
    if (rcCascadeOwnershipBand(cascade, band)) {
        options.nearDistance = band.nearDistance;
        options.farDistance = band.farDistance;
    }
    return options;
}
