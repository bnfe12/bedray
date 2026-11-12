# SER A/B — RD16, ReSTIR PT on (2026-11-12)

Stage 1 of the GI/perf plan: does NV Shader Execution Reordering (SER) pay for itself on the path tracer, and does the
raygen pipeline beat compute RayQuery? Measured on the RTX 5060 Ti (8 GiB), native 1920x1080, render distance 16,
BILINEAR upscaler, frame generation off, ReSTIR DI + PT on, fixed autotest world/pose (seed 1234567890123,
-41.5/89/2.5, yaw 135 pitch 12). 10 s warmup + 30 s record, ~893 contiguous validated frames per arm.

## Valid captures

| arm | pass | enabled mask | file | valid |
|---|---|---|---|---|
| compute RayQuery | `path_trace_scene` (compute) | `0x677` | `../restir_pt_rd16/compute_pt_on.json` | yes |
| raygen pipeline | `path_trace_scene.raygen` | `0xe77` | `raygen_pt_on.json` | yes |
| raygen + SER | `path_trace_scene.ser` | `0x1e77` | `ser_pt_on.json` | **no — see below** |

The compute arm is reused from the ReSTIR PT bench (`restir_pt_rd16`), which is the same configuration. The
`compute_pt_on.json` in *this* directory was invalidated by a one-frame focus blip (CSV line 448, flags 127→63) and is
kept only as evidence of the attempt; the valid compute numbers come from `restir_pt_rd16`.

## Compute vs raygen (both valid, PT on)

| metric | compute RayQuery | raygen pipeline | delta |
|---|---|---|---|
| gpu_rt median | 28.188 ms | 25.999 ms | **−2.189 ms (−7.8%)** |
| gpu_rt p95 | 30.336 ms | 28.142 ms | −2.194 ms (−7.2%) |
| gpu_total median | 28.858 ms | 26.709 ms | −2.149 ms (−7.4%) |
| cpu_total median | 12.966 ms | 10.647 ms | −2.319 ms |
| frame interval median | 33.680 ms | 33.422 ms | −0.258 ms |
| approx FPS | 29.69 | 29.92 | +0.23 (~0) |
| reserved VRAM max | 2317 MiB | 2317 MiB | same |

The raygen pipeline traces ~7.8% faster on the GPU and records ~2.3 ms less CPU per frame, but the presented FPS is
unchanged: at RD16 the frame is denoiser/CPU-bound, so the GPU saving does not reach the frame interval. Memory is
identical — the PT ring (569 MiB at 1080p) is the same in both arms. The raygen path is the better default for the PT
backend on this hardware; the win is real on the GPU timer but modest at the frame level until the denoiser cost falls.

## SER: engagement proven, clean perf capture blocked

SER engagement is confirmed at L4 (a live game frame): the log records
`[VKB-RAYGEN] pass=path_trace_scene.ser stage=raygeneration ser_requested=true reordering_hint=reorder enabled=0x1e77`,
the `VK_NV_ray_tracing_invocation_reorder` feature is enabled, the SlangProgram `path_trace_scene.ser` is registered, and
the native scene renders with the SER pass active (`native_scene active`, `rt=on`, gpu_rt measured). The mask `0x1e77`
adds bit 12 (`CAP_INVOCATION_REORDER_NV`) over the raygen `0xe77`.

A *validated* SER perf capture could not be obtained in this session. Root cause (traced, not guessed): the capture
(`FrameCapture.end`) only leaves `WAITING` once a frame carries `WORLD | PATH_RECORDED | SCREEN_CLOSED | FOCUSED`, then
needs 10 s warmup + 30 s record of contiguous focused frames. The SER runs render correctly, but the shared desktop is
contested — another session's game instance cycles the foreground window, so the `FOCUSED` flag (bit 6) never holds for
the full window and the capture times out (`scenario_timeout_waiting_for_world_or_capture`, frames=0). Four quiet-window
attempts with a PID-targeted focus holder all failed this way. This is an environment limitation, not a SER defect.

No SER FPS number is claimed. The honest result: SER is wired, enabled and executing on the raygen PT path; its
steady-state perf delta vs plain raygen is **unmeasured** pending a quiet, exclusively-focused GPU window.

## Disposition

- Default remains compute RayQuery / raygen without SER until a clean SER number exists (plan rule: off by default until measured).
- The raygen pipeline is a measured ~7.8% GPU-time improvement over compute for PT; it is the recommended PT execution path.
- SER stays an opt-in experimental switch (`bedray.rt.ser`), engagement proven, perf pending.