# The ReSTIR GI change of measure

`experimental/restir_pt.slang` v3 reuses a stored path at a different receiver by
keeping the first scattered vertex and moving the anchor. This note derives the
density correction that shift needs, states exactly which paths the derivation
covers, and lists what the implementation deliberately does not model. The
module is still quarantined: it is imported by no root, has no pass or host and
has never run on a GPU. What changed in v3 is that reuse now has a proof and a
CPU validation instead of an open question.

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

Everything else is rejected by `ptPathReusable`, not approximated:

| First edge | Why it is refused |
|---|---|
| sky (`ENVIRONMENT`) | the "vertex" is a direction-dependent environment lookup; its radiance towards $A$ is not the radiance towards $B$ |
| emitter (`EMISSIVE_END`) | emission is radiance per direction; a reused edge would carry the old direction's value |
| delta / specular | no finite density in the solid-angle measure; the reverse density is a Dirac at the old direction |
| glossy (even rough GGX) | outgoing radiance and the reverse lobe density both depend on the incoming direction; the transport identity still holds for the *vertex sample*, but the suffix is not invariant |

A black suffix is not a rejection reason: the target density does not contain
the radiance, so a black candidate still has a positive WRS weight.

## 6. Duplicate samples

v2 compared first-vertex positions to detect the same sample entering a
reservoir twice. Distinct samples can share a vertex, so that test dropped valid
contributions. v3 stores `sampleId` in the first vertex and compares identities;
an unknown id (0) never matches, which keeps reset reservoirs and untagged
callers safe. The id must be unique among the samples one pixel can merge in a
frame and must survive every copy of the record.

## 7. Documented approximations

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

## 8. CPU validation

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

The module also compiles cleanly through the pinned `slangc` (2026.20) with the
AOT flags, through a temporary probe root that calls every entry point,
including both generic reuse functions.

## 9. Integration gates that remain

Per `experimental/README.md`, the module stays out of the AOT set until it has a
pass, a manifest-checked host, a reservoir property test and at least one GPU
smoke run. This derivation closes the mathematical gate only; there is still no
owner-verified frame.
