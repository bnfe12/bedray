# RR guide and PT reset repairs

The owner reported severe instability of blocks and light in Path Tracing /
Beautiful with DLSS Ray Reconstruction and all features enabled. This audit found
three concrete RR producer defects and an avoidable PT reset bandwidth cost.
These source repairs are not a claim of measured RTX visual/FPS acceptance.

RR consumed the per-pixel world-motion allocation while only NRD enabled its
writer. The host now binds the writer before either denoiser consumes the same
allocation, and clears consumer addresses when motion is disabled. An explicit
private RR guide flag (bit 22) separates RR's primary jitter and guide contract
from motion-write enablement. It survives both backends' PT flag rewriting and
does not change public native ABI-v2 layouts. NRD's lobe estimator remains
separate from RR's independent reflection geometry.

RR's supplied linear surface depth was negative while sky depth was positive.
RR now receives positive forward linear depth, with sky remaining far away.
NRD retains its existing signed convention. This follows the
[NVIDIA integration depth contract](https://github.com/nvpro-samples/vk_denoise_dlssrr#depth-values).
ReSTIR PT's unsupported NRD-signal bypass also erased an independently measured
RR reflection distance twice: once per sample and once in the SPP mean. Both
bypasses now preserve that distance; complete HDR recombination is unchanged.

PT history invalidation still follows lighting fingerprints. The reset no longer
fills both 144-byte records per pixel: every valid pixel's first sample writes a
complete current record, including misses, rejected and empty attempts; history
reads require the withheld HISTORY flag. Spatial reads follow the trace barrier
and overwrite the opposite half completely. Reset, re-layout and recording
discard continue invalidating history. At 1920x1080 each avoided fill is
597,196,800 bytes; this is a work-count reduction, not a measured speedup.

Verification includes production Slang CPU checks for jitter/motion independence,
RR depth ordering, mixed bypass reflection geometry, and poisoned PT records
across temporal outcomes, spatial publication and reset/re-layout. Host state
and native packet tests preserve history/parity and explicit RR flags.
`[VKB-RR-GUIDES]` logs guide mode, depth convention, coherent-primary choice and
the world-motion writer/range when that contract changes.

The concurrent direct-native-memory output push repair (4035f79) and its crash
evidence are preserved in this branch's ancestry. RR+frame-generation's older
two-reconstruction route and ReSTIR temporal-noise correlation remain separate
investigations; upstream documents the latter in
[RTXDI's RR decorrelation guidance](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/RestirPT.md#decorrelation-for-dlss-ray-reconstruction).
