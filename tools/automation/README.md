# Benchmark automation prototype

This directory is a prototype and is not connected to the renderer's real
launch/configuration/telemetry contracts. It must not be used as evidence for
native 1080p/60 FPS, percentile lows, Tracy integration or GPU performance.
The earlier description of a complete zero-configuration runner was inaccurate.

Confirmed gaps in `game_session.py` and `run_bench.py`:

- The client task `:fabric-26.1:runClient` does not exist in root settings.
- Generated `bedray.mode`, `bedray.preset`, `bedray.benchmark.*` and
  `bedray.tracy.*` properties have no corresponding renderer consumers.
- Expected `[VKB-FRAME]`/`[VKB-PASS]` markers are not emitted. The renderer uses
  `[VKB-PERF]` window averages and separate RT `gpu_ms` span logs.
- Startup/build time is included in the nominal interval, stdout is not
  drained while the child runs, and an existing game log may be deleted.
- No effective resolution/upscaler/frame-generation acceptance check exists.
  Missing samples can become a zero-valued report instead of a failed run.
- `tracy_session.py` launches a capture executable; it does not implement a
  Tracy protocol client or Bedray raw-Vulkan GPU zones.

`pyproject.toml` requires Python 3.12+ and lists psutil/rich. Installing those
dependencies does not resolve these protocol gaps. Do not launch this
prototype against an existing game instance until its lifecycle is repaired.

The actual in-game diagnostic path is `PerfScenario`, `PerfMonitor`,
`DebugInfo` and `[VKB-PERF]`. The scenario's default comparison includes
upscalers with RT off; configure it explicitly for native PT. Its window
averages cannot produce honest per-frame 1%/0.1% lows.

The owner target is native 1920x1080 at stable 60 real FPS without upscaling,
reduced/dynamic internal resolution or generated-frame substitution. The
implementation gaps, external measurement tools and required evidence are
recorded in [the native-60 audit](../../docs/NATIVE_1080P_60_AUDIT.md).
