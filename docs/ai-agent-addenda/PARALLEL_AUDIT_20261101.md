# Parallel migration audit — 2026-11-01

Audit baseline: `e40109b` on `refactor/slang-pipeline`. The current upstream
renderer and its fixes take priority over the paused local preparation. Historical
GLSL ray-effects prototypes are not reintroduced into the renderer that replaced
them. Recovery snapshots remain outside the source tree.

Three independent reviews covered resource lifetime, module boundaries and
shader/ABI correctness. Confirmed findings and resulting changes:

| Finding | Repair |
|---|---|
| NRD/RR/output resize could free images referenced by host-queued command buffers | Preflight before command allocation; host submit/fence before replacement; guide-generation pins from first preparation; transactional output replacement |
| Partial Streamline tag registration could leave a borrowed view untracked | Small tag-ownership module marks possible references before the first native call and retains them on failed clear |
| A failed FG frame could stop Java updates while native interpolation remained enabled | Shared failure cleanup attempts disable/clear, preserves the original error and retains uncertain state/resources |
| FSR 3 menu/no-world disable failure was ignored | Check the SDK result before claiming OFF |
| RC NEE shadow origins used the normal map | Geometric face normal controls origin offset; shading normal still controls illumination |
| RC history blend copied current-only completion/opacity flags | Reuse the common weighted transport accumulator for values, confidence and classification |
| Concurrent FG implementations duplicated the C ABI wrapper/layout | One canonical `BedrayCoreNative` binding and `BedrayCoreLayouts` descriptor; separate validation/payload adapter |

Module boundaries remain explicit: `FrameGenProducerRecordings` handles proof
state, `FrameGenHostCommands` adapts the engine encoder/fence, tag ownership tracks
vendor references, and the failure-cleanup helper aggregates independent actions.
`DenoiserRecordingPins` guards a complete guide generation, including failures
before the pack pass. No new native submission or wait authority is introduced.

Runtime diagnostics are emitted through the existing application logger into the
game log. Error messages name the stage/provider/profile, frame or command buffer,
old/requested extent, image view or guide generation, native result and retained
resource state where relevant. Original exceptions remain causes; cleanup errors
remain suppressed exceptions. Resource changes can produce lifecycle entries;
unchanged successful frames do not gain new recurring log messages.

Verification results belong to `VERIFICATION_LOG.md` with the tested source
revision. CPU tests and AOT/native builds do not establish GPU, visual,
performance or owner acceptance.
