# Bedray 2.0 Automation & Benchmarking (Python 3.15+ & uv)

Modern, zero-configuration benchmark runner and Tracy Profiler controller for Bedray 2.0.

## 1. Quick Start with Astral `uv`

Astral `uv` is an extremely fast, standalone Python project and package manager that manages Python versions (including Python 3.15+) and isolated virtual environments automatically.

### Install `uv` (one-time setup)
* **Windows (PowerShell):**
  ```powershell
  irm https://astral.sh/uv/install.ps1 | iex
  ```
* **macOS / Linux:**
  ```bash
  curl -LsSf https://astral.sh/uv/install.sh | sh
  ```

### Run Benchmark
From the repository root or `tools/automation/`, run with `uv`:

```bash
# Run 30-second Path Tracing benchmark with Quality preset and Tracy profiling
uv run tools/automation/run_bench.py --duration 30 --mode path_tracing --preset quality --tracy
```

`uv` will automatically:
1. Download and pin the required Python version (Python 3.12 - 3.15+) without touching your system Python.
2. Install isolated dependencies (`psutil`, `rich`).
3. Connect to Tracy Profiler on TCP port 8086.
4. Launch the game session and record frametimes and pass breakdowns.
5. Generate a Markdown report in `reports/benchmarks/`.

---

## 2. Tracy Profiler Integration

Bedray 2.0 natively integrates the **Tracy Profiler** client (`VK_EXT_calibrated_timestamps` and `tracy-client` Vulkan GPU zones).

### Live Viewing
To view live zones and GPU execution timelines:
1. Download the [Tracy Profiler GUI](https://github.com/wolfpld/tracy/releases) (`Tracy.exe` on Windows, `Tracy` on macOS/Linux).
2. Launch `Tracy.exe` and click **Connect** (default port `8086`).
3. Start the game or benchmark script — Tracy will immediately stream frame data and compute pass timelines.

### Headless Capture (`tracy-capture`)
For CI/CD and automated regression testing:
* Place `tracy-capture` in your PATH or under `tools/tracy/`.
* `run_bench.py` will automatically start `tracy-capture` in the background and output a standalone `.tracy` trace file in `reports/benchmarks/` alongside the Markdown summary.

---

## 3. CLI Reference

```
usage: run_bench.py [-h] [--duration DURATION] [--warmup WARMUP]
                    [--mode {voxel_vanilla,voxel_rt,path_tracing}]
                    [--preset {fast,quality,expert}]
                    [--render-distance RENDER_DISTANCE] [--tracy] [--no-tracy]
                    [--output-dir OUTPUT_DIR]

Options:
  --duration DURATION   Measurement window in seconds (default: 30)
  --warmup WARMUP       Warmup period before collecting stats (default: 10)
  --mode MODE           Rendering mode: voxel_vanilla | voxel_rt | path_tracing
  --preset PRESET       Quality preset: fast | quality | expert
  --render-distance N   Render distance in chunks (default: 12)
  --tracy / --no-tracy  Enable/disable background Tracy capture (default: True)
  --output-dir PATH     Output folder for reports and traces
```
