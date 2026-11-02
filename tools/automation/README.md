# Native frame capture and offline acceptance

The renderer scenario `native_perf` writes raw application frame intervals,
CPU/GPU segments and effective rendering facts. The Python tools analyze an
existing capture; they do not launch Minecraft, kill processes or delete logs.
Python 3.12+ is sufficient; no third-party runtime dependencies.

Launch the built mod in your normal launcher instance, with a real **1920x1080
framebuffer**, Vulkan and hardware RT available. Add these to the **game JVM**
arguments (not the Gradle daemon):

```text
-Dvkbridge.autotest=native_perf
-Dvkrtx.rt.adaptive_quality=off
-Dvkrtx.autotest.captureLabel=scene_quality_backend
-Dvkrtx.autotest.revisionHint=YOUR_BUILD_COMMIT
```

The scenario uses the existing autotest world/camera harness; see
`AutotestHarness` for `vkbridge.autotest.x/y/z/yaw/pitch` overrides.
Keep the world, camera, render distance, quality and driver fixed for A/B.
Configure a useful uncapped frame limit/VSync setting in the launcher instance;
their initial values are recorded, not silently changed. For compute, ordinary
raygen and SER comparisons restart with the matching
`bedray.rt.raygen`/`bedray.rt.ser` arguments. The CSV names the program that
actually registered, including a fallback; requesting SER does not prove SER.

The scenario sets session-only path mode, native scale, bilinear copy and FG
off. It waits 20 seconds for loading, then requires 10 seconds of continuous
focused world rendering with no screen, followed by 30 seconds of capture.
Every subsequent frame is retained, including slow, unfocused, invalid-mode
or resized frames. The collector is bounded to 30,000 samples; overflow and
timeouts preserve an explicitly incomplete capture. Keep the window focused.
The scenario exits after persistence completes. Its harness PASS means **the
capture was saved**, not that the target FPS was met.

Evidence is saved to
`<gameDir>/autotest/native_perf/capture-<UUID>/frames.csv` and `manifest.json`.
The manifest is written after the CSV closes. Existing evidence is never
overwritten. `[VKB-BENCH]` logs armed/sealed/saved/write-failed events with the
directory, counts and failure reason. A process crash during collection can
still prevent export; an absent manifest is not a valid result.

```powershell
python tools/automation/run_bench.py C:/path/to/capture-UUID
python -B -m unittest discover -s tools/automation -p 'test_*.py' -v
```

The analyzer prints JSON. Exit codes: **0** = sampled target met, **1** = valid
capture below target, **2** = invalid/incomplete evidence. It requires native
1920x1080 render/output, actual PT dispatch, closed screen, focus, upscaler off,
proven FG-off state, consecutive frame IDs, fixed program/spp/bounces/trace
flags/denoiser request, complete duration and correlated GPU total/RT timings.
Missing timings remain empty, never invented zero.

Average FPS is 1000 divided by the mean raw interval. A 1% low is 1000 divided
by the mean of the slowest ceil(N*0.01) intervals; 0.1% uses ceil(N*0.001).
p95/p99 use nearest rank. The sampled target criterion is average and 1% low
at least 60 FPS. Maximum interval, number over 16.67 ms and whether **every**
frame met the budget are reported separately. A passing workload is not a
guarantee for every scene or future run.

CPU/GPU total spans renderer begin to pre-submit end; it excludes submission,
presentation and some host work. The wall interval between consecutive frame
heads includes the intervening application time. GPU queries are harvested
without WAIT and matched to their original frame IDs, not the current frame.
Unavailable queries invalidate GPU acceptance rather than triggering a wait.
The denoiser column records the requested setting, not independent proof of
successful reconstruction. Revision hints are explicitly caller supplied.

The disconnected launcher/log-parser/Tracy prototypes were removed. This
capture does not implement Tracy Vulkan zones, external presentation timing,
VRAM budget accounting or image-quality acceptance. Use the
[native-60 audit](../../docs/NATIVE_1080P_60_AUDIT.md) for remaining obligations.
