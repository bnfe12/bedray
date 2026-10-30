# The ReSTIR GI change of measure

`experimental/restir_pt.slang` v4 reuses a stored path at a different receiver by
keeping the first scattered vertex and moving the anchor. This note derives the
density correction that shift needs, states exactly which paths the derivation
covers, and lists what the implementation deliberately does not model. The
module is still quarantined: it is imported by no root, has no pass or host and
has never run on a GPU. v3 gave reuse a proof and a CPU validation instead of an
open question; v4 makes the reservoir's $M$ the number of attempts the sampler
made, which is the other half of the same normalisation (§6).

## 1. What is reused

A stored path sample is

- an anchor $A$: the visible surface that owns the path,
- a first scattered vertex $v$ with shading normal $n_v$,
- a suffix: everything after $v$, summarised as the radiance $L(v,\omega_{in})$
  it delivers back along the edge $v \to A$,
- the sampling density $q_A$ of the direction $\omega_A$ that sampled $v$ from $A$,
- the reverse BSDF density at $v$ for the direction $v \to A$ (the target).

"Reuse at anchor $B$" means: keep $v$ and the suffix, replace the receiver-side
vertex with $B$, and shade at $B$ with $B$'s own BSDF. The question the reservoir
math must answer is the weight this candidate deserves at $B$.

## 2. The transport identity

Solid angle and area at the first vertex are related by

$$
d\omega = G(x)\,dA, \qquad G(x) = \frac{\lvert n_v \cdot (x - v)\rvert}{\lVert x - v \rVert^2},
$$

where $d\omega$ is solid angle as seen from anchor $x$ and $dA$ is area at $v$.
The same vertex sample $v$ therefore carries solid-angle densities related by

$$
q_B = q_A \cdot \frac{G(A)}{G(B)} .
$$

This is the whole change of measure for the fixed-first-vertex shift: the sample
was drawn in solid angle at $A$, and viewed from $B$ the same vertex occupies a
different solid-angle density. Note that $G$ is evaluated at the **first vertex's
normal**, not at the anchors' normals — the anchors' own BSDFs are applied at
resolve, not here.

## 3. The merge correction

Weighted reservoir sampling at $A$ stored the weight

$$
w_A = \frac{\hat p_A(v)}{q_A},
$$

where $\hat p$ is a target density (the reverse lobe density at $v$ times the
anchor cosine). At $B$ the same candidate deserves

$$
w_B = \frac{\hat p_B(v)}{q_B}
    = \frac{\hat p_B(v)}{\hat p_A(v)} \cdot w_A \cdot \frac{G(B)}{G(A)} .
$$

So the merge ratio applied to an incoming record is

$$
\frac{\hat p_B(v)}{\hat p_A(v)} \cdot J, \qquad J = \frac{G(B_{\text{new}})}{G(A_{\text{old}})} .
$$

This is `ptFirstEdgeShiftJacobian` returning `newGeometry / oldGeometry`, folded
into `ptMergeReservoir` as
`incomingTargetPdf / incoming.stats.z * shiftJacobian`. The target ratio alone —
what v2 applied — is the $J = 1$ special case and is wrong whenever the distance
or the angle at the first vertex changes.

## 4. Why the resolved estimate is unbiased

The resolve normalises with $\hat p_B$:

$$
W = \frac{\sum w}{M\,\hat p_B},
$$

so a reused candidate contributes $\frac{w_B}{M\hat p_B} = \frac{1}{M q_B}$ times
the receiver's BSDF and $L$. $\hat p$ cancels exactly and is a variance control
only. The estimator is therefore the ordinary Monte Carlo estimate of

$$
\int_{\Omega_B} f_B(\omega)\,L(v(\omega))\,d\omega
$$

with the correct sampling density, **provided** the suffix radiance and the
suffix sampling distribution do not depend on the incoming edge. That condition
is the certificate below; where it fails, the transported density is not the
density of the reused sample and no scalar correction can repair it.

## 5. The certified class

`RESTIR_PT_PATH_FLAG_DIFFUSE_FIRST` is set only when the first scattered vertex
is diffuse. For a Lambertian first vertex:

- the outgoing radiance towards $A$ is $\frac{\rho}{\pi}\int L_i \cos\theta\,d\omega$,
  which depends on $v$ and on the suffix, not on the direction the edge came from;
- the first vertex's lobe density used by the suffix sampler is the same
  direction-independent function of the vertex for every anchor.

Everything else is rejected for reuse by `ptPathReusable`, not approximated.
v4 still **admits** such a path as an attempt — it is a draw from the first-edge
density like any other — but it is never transported and never merges (§6):

| First edge | Why it is refused |
|---|---|
| sky (`ENVIRONMENT`) | the "vertex" is a direction-dependent environment lookup; its radiance towards $A$ is not the radiance towards $B$ |
| emitter (`EMISSIVE_END`) | emission is radiance per direction; a reused edge would carry the old direction's value |
| delta / specular | no finite density in the solid-angle measure; the reverse density is a Dirac at the old direction |
| glossy (even rough GGX) | outgoing radiance and the reverse lobe density both depend on the incoming direction; the transport identity still holds for the *vertex sample*, but the suffix is not invariant |

A black suffix is not a rejection reason: the target density does not contain
the radiance, so a black candidate still has a positive WRS weight.

## 6. Attempt accounting

The reservoir theorem has two halves. One is the change of measure above; the
other is that $M$ in $W = \sum w / (M\hat p)$ is the number of **attempts** drawn
from $q$, and does not depend on the sample values. v3 violated the second half:
`ptAddPath` returned early unless `ptPathReusable` held, so a draw that landed on
the sky, on an emitter, on a delta or on a glossy vertex never incremented the
sample count. $M$ was then the number of *recorded* reusable hits out of $N$
attempts, a random quantity, and the estimate was conditioned on a recording that
the estimator never asked for.

The size of the error follows from that conditioning. Let $K$ be the number of
recorded attempts out of $N$, each attempt recording with probability $p$, and
let $y = w\,f/\hat p$ for a recorded attempt. Since $K$ is a function of which
attempts recorded, condition on $K = k \ge 1$: the $k$ recorded draws are i.i.d.
from the recorded distribution, and $\mathbb{E}[y \mid \text{recorded}] = A/p$
where $A$ is the integral the unbiased estimator targets (the factor $1/p$ is
exactly the fraction of attempts the recording threw away). The v3 estimator
averaged over those $k$, so

$$
\mathbb{E}\left[\hat I_{v3}\right]
  = \Pr(K > 0)\cdot\frac{A}{p}
  = \left(1-(1-p)^N\right)\frac{A}{p}.
$$

With $N = 1$ this is $A$ — a single attempt is fine, because recording is the
only way its weight can be nonzero and both sides carry the same $1/p$. From
$N = 2$ on the factor is larger than one. In the half-sky wall scene of §9
($p = \tfrac12$, $A = 0.15$): $N = 2$ gives $0.225$ (+50%), $N = 4$ gives
$0.28125$ (+87.5%). Both were measured in the CPU model at
$0.224808 \pm 0.000333$ and $0.281228 \pm 0.000539$.

v4 splits the two questions. `ptAddPath` admits every path that passes
`ptPathValid` and carries `first.pdf > 0`; that is what makes $M$ the number of
attempts. `ptPathReusable` stays where a density is actually transported —
`ptMergeReservoir`, `ptTemporalReuse`, `ptSpatialReuse` — so a sky or emitter
record still never merges into a receiver's reservoir and no density is invented
for it.

Admitting a non-contributing attempt cannot corrupt the reservoir. Its WRS
weight is $\hat p_{\text{sky}}/q$ with the target at the `ptTargetPdf` floor
($\max(\text{bsdfPdf},10^{-8})\cdot\cos$), and a sky record carries
$\text{bsdfPdf} = 0$, so its weight is about $10^{-8}/q$: it can only win a
reservoir whose every other weight is smaller, which a recorded diffuse hit never
is. Its resolved contribution is correspondingly $\sim 10^{-8}$, and the term it
represents (a first edge straight to the environment) is the one the
environment/direct estimator owns.

The same floor meant, in v4, that `ptMergeReservoir`'s reset
`if (!ptReservoirHasSample(current)) current = ptEmptyReservoir();` could not
fire for a legitimate accumulator: every admitted attempt added a strictly
positive weight. **v5 superseded that argument** (§11): the live stage admits
attempts it does not own through `ptAddEmptyAttempt` (`M + 1`, no weight), so
an accumulator with `M > 0, wSum = 0` is legitimate, and the reset now fires only
for a corrupt record (`ptReservoirIsAccumulator`). With the v4 reset a pixel
whose attempt produced no candidate would merge history and keep `M = M_h`,
which is the `1/P(recorded)` defect moved into the merge.

## 7. Duplicate samples

v2 compared first-vertex positions to detect the same sample entering a
reservoir twice. Distinct samples can share a vertex, so that test dropped valid
contributions. v3 stores `sampleId` in the first vertex and compares identities;
an unknown id (0) never matches, which keeps reset reservoirs and untagged
callers safe. The id must be unique among the samples one pixel can merge in a
frame and must survive every copy of the record.

## 8. Documented approximations

These are estimator changes, not consequences of the derivation:

- **Ratio caps** (8 temporal, 5 spatial) clamp the merge ratio after the
  Jacobian. They bias towards the current sample and exist to bound fireflies.
- **Minimum edge cosine** $10^{-3}$ rejects a candidate whose $G(A)$ or $G(B)$ is
  nearly zero instead of letting $J$ explode; the rejection keeps the current
  sample.
- **Target cosine floor** (0.05) keeps a grazing receiver from making the target
  ratio explode; the target cancels, so this is variance shaping, not a
  correction.
- **Occlusion decay** scales the weight sum without a resampling argument and is
  flagged sticky, so a decayed record never passes `ptCandidateIsFresh`.

Deliberately out of scope for this derivation, and required before a shipping
pass:

- participating media between anchor and first vertex (a coloured transmittance
  does not transport the density; the module applies the first-edge
  transmittance at resolve only),
- moving geometry or a changing suffix between frames (history validation
  covers the receiver, not the first vertex),
- correlation between reservoirs that reuse each other repeatedly (the
  canonical merge is pairwise; a spatial pass that merges already-reused
  neighbours needs the usual ReSTIR caveats about support and correlation),
- MIS between the reused estimator and other techniques.

## 9. CPU validation

`RestirPtChangeOfMeasureTest` pins the derivation in three ways:

1. **Algebra on one candidate.** With $q_{old} = 0.8/\pi$, reverse density
   $0.9/\pi$, wall at $x = 2$, anchors at $x = 0$ and $x = 1$, the resolved
   weight must equal $1/q_{new}$ exactly (1e-12), must not move when the target
   is scaled by 7.25, and must differ from the v2 weight by more than 50%.
2. **Monte Carlo against a closed form.** Two million cosine samples of a
   diffuse wall (albedo 0.5, environment radiance 1) from a source on the floor
   at $x = 0$, reused at a receiver at $x = 1$; the analytic indirect value is
   $0.6 \cdot 0.5 \cdot \tfrac12 = 0.15$. The corrected estimator measured
   $0.149937 \pm 0.000172$ (0.37 standard errors from the analytic value); the v2
   merge measured $0.170995$, about 14% high, which is the bias the Jacobian
   removes. The model intentionally omits the firefly caps and grazing rejection
   so it validates the change of measure in isolation.
3. **Source guards.** The shader must contain the certified flag, the two
   geometry functions with the exact old/new call sites, the Jacobian in the
   merge ratio, the terminal/delta/diffuse rejection lines and the `sampleId`
   duplicate test.

`RestirPtReservoirInvariantTest` closes the attempt-accounting gate of §6. It
mirrors the module's arithmetic in Java doubles — including the target's
`MIN_PDF` floor, which is what keeps a non-contributing attempt from winning —
and checks four things:

1. **The split in the source.** `ptAddPath` must admit on `ptPathValid` plus
   `first.pdf > 0` and must not mention `ptPathReusable`; the merge must still
   keep the certified-class gate.
2. **Fresh reservoirs, two attempts.** On the flatland scene above, the
   counting reservoir measures $0.149496 \pm 0.000330$ and the v3-gated twin
   $0.224808 \pm 0.000333$ against the conditional expectation
   $1.5 \cdot 0.15 = 0.225$ (isolation model, 400 000 trials; the unit test
   repeats the comparison at 300 000 trials and asserts $4\sigma$/1% and 2%).
   The pair is the sharpest statement of the defect because only the admission
   rule differs.
3. **Temporal reuse.** Transported-into-empty and merged-with-fresh both stay
   on $0.15$ ($0.150065 \pm 0.000296$ and $0.149831 \pm 0.000330$), so reuse
   neither re-introduces the conditioning nor breaks the Jacobian transport.
4. **The Jacobian is still applied.** A deterministic merge into an empty
   reservoir must satisfy $W_{\text{merged}} = W_{\text{source}} \cdot J$
   exactly (1e-12), with $J$ at least 5% away from one so the assertion cannot
   pass by accident.

A mutation proof backs the guard: restoring the v3 gate
(`if (!ptPathReusable(path)) return reservoir;`) turns exactly
`admissionCountsEveryAttemptWhileReuseStaysInTheMerge` red and nothing else;
the shader was restored by backup and verified by sha256
(`4c31aeed…461ef`).

The module also compiles cleanly through the pinned `slangc` (2026.20) with the
AOT flags, through a temporary probe root that calls every entry point,
including both generic reuse functions.

## 10. Integration gates that remain

The property test (`RestirPtReservoirInvariantTest`), the mathematical gates of
§4–§6, a pass and a manifest-checked host are closed: §11 describes the live
temporal stage. What remains is a GPU smoke run with the switch on, the spatial
stage, and an owner-verified frame. The module stays in `experimental/` until
the GPU gate closes (`experimental/README.md`).

## 11. Live temporal stage (v5)

`restir/pt_temporal.slang` is the adapter the path entries call once per pixel,
after the first ray (`s == 0`) of `pathTraceEntryWithField`. The tracer records
that ray's first edge under `PATH_TRACE_OPTION_RESTIR_PT` (bit 17) in a
`PathPtRecord`: the anchor, the diffuse continuation's direction and density
(`bsdf.pdf`, lobe probability included), the throughput it left with, the
radiance before it left, the first vertex (position, view-facing normal,
`bsdfPdf(material_v, view_v, n_v, view_v)` as the reverse density) and the
suffix `L = (total - before) / throughput`.

**Ownership.** The reservoir owns exactly one term: the diffuse continuation of
an eligible primary vertex whose first scattered vertex is certified diffuse and
does not emit. An admitted candidate's contribution `throughput * L` is
subtracted from the path and the resolve adds
`(rho_d / pi) * cos * L * W`, where `rho_d = bsdfDiffuseReflectanceAt` is the
same reflectance the sampler's diffuse weight is built from. Every other term
stays in the path: sky and emitter first edges (§5 refuses to transport them,
so the path that traced them owns them), uncertified first vertices, specular
and transmission lobes, and DI's next-event estimate at the primary vertex,
which the stage never touches. Nothing is counted twice and nothing is dropped.
The DI/PT ownership question of addendum 05 therefore has a concrete answer in
the live stage: the overlapping first-edge emitter term is never in the
reservoir.

**Eligibility** is decided on the receiver before the lobe is drawn: not in water
(the first edge would carry medium attenuation), not an RC field continuation,
not an optical interface (its sampler has no diffuse branch), not fluid, and not
a ray profile without a full diffuse bounce. An ineligible receiver publishes an
empty record and adds nothing.

**Attempts.** Every draw of an eligible receiver is one attempt: a candidate
enters through `ptAddPath`, anything else through `ptAddEmptyAttempt`. Because
`W = wSum/(M p-hat)` divides by attempts, the per-pixel estimate of the owned
term is the RIS estimate over the receiver's diffuse branch with the non-owned
outcomes as zero-target draws.

**First-frame identity.** With no history `M = 1`, `wSum = p-hat/q` and
`W = 1/q`. A fresh winner is shaded along its sampled direction (the
anchor-to-vertex edge starts one ray bias off the face and differs at a short
edge), so `(rho_d/pi) cos(w) L / q = (rho_d / P) L = throughput * L`: the image
with the switch on equals the image with it off. `RestirPtTemporalStageTest`
checks this on 200,000 random cases to 1e-9 and counts the cases the firefly caps
touch (`MAX_CANDIDATE_WEIGHT` 64, `MAX_RESOLVED_WEIGHT` 1e4), which must stay rare.

**History.** One record per pixel and parity half, laid out like the DI ring.
The stored anchor is the stored receiver: a pixel reprojects
`anchor + motion + cameraDelta` through `prevViewProjection` (the DI
reprojection) and validates the record with `historyValidate` against that
camera-relative position (`RestirPtReceiver.historyPosition`), with DI's bounds
(5 % depth band, normal 0.8 still / 0.6 moving, age 32). The stored first vertex
is rebased by `-cameraDelta`. `SurfaceHit.worldPosition` is never a key (P1-6).
The ring has its own parity and history bits (18/19) and its own camera bits
(20/21), because it does not depend on emitters while DI only writes its state
when it runs.

**Record layout, measured.** The std430 layout the reflection reports is
first vertex 64 B, path 112 B, reservoir 144 B. The 52/80/112 B figures up to v4
were packed payload sizes, never a measured layout; no field order reaches 112 B
under std430 because a nested struct with a `float3` rounds to 16 B. The ring is
`2 * 144 * w * h` bytes: 597.2 MB at 1080p.

**Approximations specific to the live stage** (on top of §8):

- the certificate (`pathPtCertifiedDiffuse`: opaque, non-fluid, non-foliage,
  metallic <= 0.5, roughness >= 0.6) reads a rough dielectric's residual GGX lobe
  as Lambertian;
- the suffix stored with a vertex was shaded with the old edge's view (the
  `(1 - F(N.V))` factor and the residual specular share at the first vertex);
- a first vertex on a moving entity is rebased with the camera delta only;
- a history whose stored winner fails `ptPathReusable` is rejected as a whole,
  which conditions the merge on the winner's class; in the live stage every
  stored winner is a certified candidate, so this only fires on corrupt data;
- the occlusion decay and the ratio/weight caps are lag and firefly guards.

