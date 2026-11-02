# Primary-lobe signal ownership

The shared integrator now records the suffix of the first selected primary
BSDF lobe in an internal signal module. A primary diffuse continuation followed
by a mirror remains diffuse; a primary specular continuation followed by diffuse
remains specular. Contributions retain the actual camera throughput and lobe
selection compensation. Raw path radiance is unchanged by this bookkeeping.

The signal contract is `raw = diffuse + specular + bypass`. Primary emission,
primary next-event lighting and all other exact residual terms remain in the
bypass. The first accepted secondary endpoint supplies the lobe distance;
cutout/pane advances count geometrically, later bounces cannot replace it and
camera-to-primary distance stays exclusively in the depth guide. Absent lobes
have distance zero and an escaped lobe uses the finite FP16 miss sentinel.

Radiance sums divide by every accepted sample, including zero-contribution sky
and absent lobes. Diffuse distances average only sampled diffuse edges; specular
uses the minimum positive sampled distance. Refused paths do not enter either
sum. The raw component clamp applies the same scale to suffix contributions.

Unsupported medium/transmission transport, finite-window RC and a PT reservoir
replacement do not invent a guide estimator. If any accepted subsample needs
this treatment, the whole pixel stores its exact raw mean in `gbDirect.rgb`
and sets `gbDirect.a=1`. NRD composition then ignores reconstructed neighboring
lobes for that pixel. This preserves energy and avoids double counting; it
does not claim that those pixels receive indirect-light denoising or PSR.

Pack and compose share symmetric diffuse/specular material factors. The specular
guide uses GGX environment reflectance with view and roughness dependence.
Diffuse albedo is zero for metals. The R10 normal encoding carries the existing
metal/nonmetal material ID and the SDK diffuse material boundary is enabled.
SNORM has no NRD material-ID channel. Compose adds binding 13, already present
in the borrowed 14-view table; no public struct size changes.

REBLUR and RELAX configure missing-hit-distance reconstruction with nonzero
prepasses, including when the host replaces the REBLUR settings. The additive
bridge signal-contract export prevents an older DLL from silently consuming
new sparse-lobe inputs without those settings. Mismatches log the contract and
required rebuild before NRD initialization. The original bridge API layouts
remain unchanged. The native CI builds the complete bridge and settings test
against pinned NRD 4.18.0 headers; it neither bundles nor runs NRD.dll.

Only NRD tracks per-path lobe bookkeeping. RR keeps total noisy HDR and the new
material reflectance guide. The ordinary path skips that bookkeeping, and the
new reflectance calculation runs only when guide output is requested.

Remaining acceptance and limitations:

- The current white-noise lobe selector does not guarantee a representative
  lobe in every reconstruction neighborhood. NRD recommends a coordinated Bayer
  selector and bounded probabilities. Changing that policy must also update
  all corresponding BSDF/MIS PDFs; simply replacing a random number is invalid.
- RR still has no connected specular-hit-distance/reflected-motion resource.
  The new reflectance guide does not complete that separate vendor contract.
- RC/PT/medium bypass, disocclusion, thin geometry, underwater transitions,
  SNORM material leakage and actual SDK output need GPU/image evaluation.
- No fix for the previously reported first-frame RR velocity rejection or a
  measured FPS improvement is claimed here.

The CPU harness executes the actual production Slang signal, mean and pack/
compose helpers through generated C++, covering 16 conservation, lobe-distance,
clamp, sky, refusal, bypass, material and RR cases. This is arithmetic evidence;
it does not replace a vendor/GPU/game run.

[NRD integration](https://github.com/NVIDIA-RTX/NRD#integration),
[NRD reconstruction settings](https://github.com/NVIDIA-RTX/NRD/blob/v4.18.0/Include/NRDSettings.h),
[RR integration](https://github.com/NVIDIAGameWorks/Streamline/blob/main/docs/ProgrammingGuideDLSS_RR.md).
