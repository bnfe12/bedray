// Canonical Radiance Cascades atlas addressing.
//
// This module owns the one linear layout shared by every atlas flavour:
//
//   legacy float4 atlas   radiance_cascades/legacy_float4_store.slang
//   typed RCSegment atlas radiance_cascades/segment_record.slang
//
// It knows nothing about the payload.  A record module owns its element type,
// validity rules and resolve; this module only maps
// (cascade, probe, ray, interval) to a linear element index.
//
// Invariants
// ----------
//   * RC_INTERVALS_PER_RAY is 2.  Interval 0 is the near segment, interval 1
//     is the far segment; both record formats use the same ordering.
//   * baseOffset is cumulative over cascades.  totalRecords counts elements,
//     not bytes, and is the minimum capacity a typed store may accept.
//   * The host and the shader must both derive the layout from
//     radianceCascadeLayout().  Every canonical field is checked by
//     rcLayoutCanonical before a record is read or written.
//   * layout.flags is reserved and must be zero.  A non-zero value is rejected
//     instead of silently ignored, so a future host cannot enable semantics an
//     older shader does not implement.
//
// The layout is transport-neutral, so a future atlas format can reuse it
// without importing either the legacy or the typed store.

public module rc_atlas_layout;

import "radiance_cascades.slang";

static const uint RC_INTERVALS_PER_RAY = 2u;   // near, far
static const uint RC_INVALID_RECORD = 0xFFFFFFFFu;

// Named interval indices.  They belong to the atlas layout, not to the trace
// policy, so a record consumer can name them without importing the trace
// contract.  rc_trace_options.slang aliases them as RCT_INTERVAL_*.
static const uint RC_INTERVAL_NEAR = 0u;
static const uint RC_INTERVAL_FAR  = 1u;

// Host-side description of the atlas.  Arrays are indexed by cascade and are
// filled by radianceCascadeLayout(); the shader never recomputes offsets.
struct RadianceCascadeLayout {
    uint probeAxis[RADIANCE_CASCADE_COUNT];
    uint rayCount[RADIANCE_CASCADE_COUNT];
    uint probeCount[RADIANCE_CASCADE_COUNT];
    uint baseOffset[RADIANCE_CASCADE_COUNT];
    uint totalRecords;
    uint cascadeCount;
    uint intervalsPerRay;
    uint flags;
};

RadianceCascadeLayout radianceCascadeLayout() {
    RadianceCascadeLayout layout;
    uint offset = 0u;
    for (uint cascade = 0u; cascade < RADIANCE_CASCADE_COUNT; ++cascade) {
        RadianceCascadeDesc desc = radianceCascade(cascade);
        uint probes = probeCount(desc);
        layout.probeAxis[cascade] = desc.probeAxis;
        layout.rayCount[cascade] = desc.rayCount;
        layout.probeCount[cascade] = probes;
        layout.baseOffset[cascade] = offset;
        offset += probes * desc.rayCount * RC_INTERVALS_PER_RAY;
    }
    layout.totalRecords = offset;
    layout.cascadeCount = RADIANCE_CASCADE_COUNT;
    layout.intervalsPerRay = RC_INTERVALS_PER_RAY;
    layout.flags = 0u;
    return layout;
}

// True only when `layout` is exactly the canonical layout and `capacity` can
// hold every element.  This is the single validation point: a fabricated
// layout, a mismatched array entry, a non-zero reserved flag or a too-small
// descriptor range all fail here.
bool rcLayoutCanonical(RadianceCascadeLayout layout, uint capacity) {
    if (layout.cascadeCount != RADIANCE_CASCADE_COUNT) return false;
    if (layout.intervalsPerRay != RC_INTERVALS_PER_RAY) return false;
    if (layout.flags != 0u) return false;
    if (layout.totalRecords > capacity) return false;

    RadianceCascadeLayout expected = radianceCascadeLayout();
    if (layout.totalRecords != expected.totalRecords) return false;
    for (uint cascade = 0u; cascade < RADIANCE_CASCADE_COUNT; ++cascade) {
        if (layout.probeAxis[cascade] != expected.probeAxis[cascade] ||
            layout.rayCount[cascade] != expected.rayCount[cascade] ||
            layout.probeCount[cascade] != expected.probeCount[cascade] ||
            layout.baseOffset[cascade] != expected.baseOffset[cascade]) return false;
    }
    return true;
}

// Returns RC_INVALID_RECORD when any address component is outside the layout.
// This is address validity only: it says nothing about the record payload and
// it does NOT validate the layout itself.  New code must use rcSegmentIndex or
// rcLayoutCanonical; this entry exists for record modules that have already
// validated the layout once for a whole probe or ray.
uint rcUncheckedIntervalIndex(RadianceCascadeLayout layout, uint cascade, uint probeIndex,
        uint rayIndex, uint interval) {
    if (cascade >= RADIANCE_CASCADE_COUNT ||
        probeIndex >= layout.probeCount[cascade] ||
        rayIndex >= layout.rayCount[cascade] ||
        interval >= RC_INTERVALS_PER_RAY) {
        return RC_INVALID_RECORD;
    }
    return layout.baseOffset[cascade] +
        (probeIndex * layout.rayCount[cascade] + rayIndex) * RC_INTERVALS_PER_RAY + interval;
}
