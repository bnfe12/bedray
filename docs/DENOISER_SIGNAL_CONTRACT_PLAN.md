# Denoiser signal ownership — remaining integration

Audit baseline: `78a4836`. This is a remaining implementation plan, not a claim
that the complete NRD/RR signal contract is already correct.

## Confirmed source findings

The guide writer puts the whole residual path signal into diffuse, writes zero
specular and supplies camera-to-primary `firstHitDistance` as diffuse hitT.
NRD instead requires a primary-lobe split and a subsequent in-lobe distance;
camera depth is a separate guide. The SDK also specifies handling for missing
probabilistically sampled lobes and primary emission.
[NRD integration](https://github.com/NVIDIA-RTX/NRD#noisy-inputs).

The current RR path supplies neither reflected motion nor specular-hit distance,
and its specular-albedo guide uses a roughness threshold. RR expects total noisy
colour and material guides; its reflection guide can use motion or distance
from the primary surface. The documentation gives a view/roughness-dependent
reflectance approximation.
[RR guide](https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuideDLSS_RR.md).

Three narrower arithmetic defects are repaired separately: exposure when tone
mapping is off, unattenuated primary direct after camera-medium transport, and
the different sample denominators for direct versus raw HDR at partial sky
coverage. These repairs do not finish the lobe/distance integration.

## Proposed internal contract

The following is the implementation design inferred from Bedray's current
dataflow. A small shader signal module should define and maintain
`rawHDR = bypass + diffuseContribution + specularContribution` at each handoff.
These are camera-weighted contributions, not material labels or unweighted
vertex-local radiance. The raw render should remain unchanged while guides are
repaired.

1. Capture the first selected primary BSDF lobe. A diffuse continuation followed
   by a mirror still belongs to primary diffuse. Keep BSDF sampling probability
   compensation exactly once. Track the first secondary segment independently
   from primary camera depth and from later accumulated path lengths.
2. Bypass camera-medium in-scatter, primary emission/direct and unsupported
   transmission cases explicitly. A complete PSR path is separate work; do not
   manufacture opaque reflection guides for refractive transport.
3. Preserve the actual camera throughput in every stored contribution. The
   current weighted-direct fix establishes this for primary NEE. Radiometric
   sample sums divide by all accepted samples, including zero-contribution sky
   or absent lobes. Geometric guide selection and distance reconstruction need
   their own policy rather than inheriting a radiance denominator blindly.
4. RC's current finite-window diffuse estimate has no in-lobe hit distance.
   Initially keep that contribution in an explicit cache bypass or provide a
   documented guide ray. ReSTIR PT temporal/spatial replacement must update its
   diffuse suffix contribution with the same estimator weight. A reservoir
   winner distance cannot silently substitute for an independent guide sample.
5. Demodulate/remodulate material factors once, symmetrically. The current pack
   divides all residual RGB by diffuse albedo. Replace that with the documented
   diffuse/specular factors and adapt compose. Configure reconstruction/prepass
   for missing sampled lobes. Apply finite/firefly limits consistently across
   the decomposition; independent clamps can break the sum.
6. RR keeps total noisy HDR rather than NRD-demodulated signals. Add an actual
   specular-distance resource or reflected motion, propagate it through host,
   optional additive native descriptor and vendor adapter, and replace the
   threshold reflectance guide with a documented approximation. Preserve old
   public ABI-v2 layouts and exact recording lifetime.

## Acceptance

CPU/reference tests must check sum conservation, primary emission, camera water,
partial sky coverage, dielectric/metal, mirror-to-sky, deterministic lobe
selection, RC and PT temporal/spatial first-frame identity, and common display
conversion. Reflected manifests, buffer/image ranges, flags and material
normalization must match the host/native/vendor contracts. Real NRD/RR frames
must then exercise camera motion, disocclusion, thin geometry and underwater
surfaces before claiming image quality or resolution of the owner's artifacts.
