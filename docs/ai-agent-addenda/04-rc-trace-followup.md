# Follow-up for the Radiance Cascades trace agent

Continue Bedray alpha 0.2.0 in your existing isolated workspace
`C:\Users\andre\AppData\Local\Temp\opencode\agent-rc-trace`. Treat
`C:\BedRay` as read-only. Do not edit shared contracts, store, entry points,
Java, C++ or the other agent's workspace. Do not commit, push or deploy.

The user has suspended tests and builds until the complete migration is done.
Do not invoke `slangc`, build tools, test harnesses, Minecraft or a GPU. Existing
SPIR-V and logs are historical only and do not validate any subsequent edits.

## Required source corrections

1. `rcWalkRay` currently accumulates translucent emission in the near/far
   records, then replaces it on a miss or opaque hit (`radianceFar = ...`,
   `radianceNear = weighted`, etc.). Preserve each earlier contribution with
   its proper accumulated transmittance. Walk at least these source-level cases:
   emissive filter -> opaque hit, emissive filter -> miss, and filters in both
   intervals. Do not label a budget-exhausted unknown interval as a fully
   resolved zero merely because traversal stopped.
2. Clarify and implement the actual radiance-cascade interval composition:
   radiance and transmittance must compose in front-to-back order; a weighted
   average of near and far records is not in general equivalent. Do not silently
   fake this using the current `float4` format. If the store contract cannot
   represent the required information, report the exact missing fields and an
   integration proposal; do not edit the shared store.
3. A bounded segment that has no hit must not automatically inject environment
   radiance into every cascade. Specify the farthest-cascade/world-boundary
   policy needed to avoid repeatedly counting the same sky, and expose the
   caller-owned information needed to make that distinction.
4. `RCTraceResult.distance` must represent the actual hit distance when it
   claims a hit; define the miss/no-hit value explicitly. Remove neutral sampler
   fallbacks that silently turn unavailable resources into black results; make
   absence explicit in the interface or return a documented failure status.
5. `cascadeDirection` in the shared workspace now uses a wrapped rotation index;
   `rcTemporalBlend` now requires explicit history probe mapping, both rotation
   values, and a history-valid flag. Re-read the current files before adapting
   your caller design. Do not assume the same history index addresses the same
   world position or direction after clipmap scrolling or rotation.

Deliver the corrected module and updated English notes in your workspace. State
which parts remain proposals because shared store/resolve contracts are missing.
Provide line references, interface changes, known biases and exact evidence
level. Do not claim correctness, codegen, or live-game validation for the new
revision without a later authorized verification.
