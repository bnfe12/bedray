#!/usr/bin/env python3
"""
Bedray 2.0 Benchmark & Automated Test Runner.

Usage via Astral uv:
    uv run run_bench.py --duration 30 --mode path_tracing --preset quality --tracy
"""

from __future__ import annotations

import argparse
import datetime
import os
import sys
from pathlib import Path

# Local module imports
from game_session import GameSession, SessionConfig
from tracy_session import TracyCaptureSession


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Bedray 2.0 Automated Benchmark & Tracy Profiler Session Runner"
    )
    parser.add_argument(
        "--duration",
        type=int,
        default=30,
        help="Benchmark measurement duration in seconds (default: 30)",
    )
    parser.add_argument(
        "--warmup",
        type=int,
        default=10,
        help="Warmup period before collecting metrics in seconds (default: 10)",
    )
    parser.add_argument(
        "--mode",
        choices=["voxel_vanilla", "voxel_rt", "path_tracing"],
        default="path_tracing",
        help="Bedray rendering mode (default: path_tracing)",
    )
    parser.add_argument(
        "--preset",
        choices=["fast", "quality", "expert"],
        default="quality",
        help="Bedray quality preset (default: quality)",
    )
    parser.add_argument(
        "--render-distance",
        type=int,
        default=12,
        help="Minecraft render distance in chunks (default: 12)",
    )
    parser.add_argument(
        "--tracy",
        action="store_true",
        default=True,
        help="Enable background Tracy Profiler capture",
    )
    parser.add_argument(
        "--no-tracy",
        dest="tracy",
        action="store_false",
        help="Disable Tracy Profiler capture",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("reports/benchmarks"),
        help="Directory to save benchmark reports and traces (default: reports/benchmarks)",
    )
    return parser.parse_args()


def generate_markdown_report(
    config: SessionConfig,
    avg_fps: float,
    p1_fps: float,
    p01_fps: float,
    avg_frametime: float,
    peak_rss: float,
    passes: dict[str, float],
    gpu_info: str,
    driver_info: str,
    vulkan_info: str,
    trace_path: Path | None,
    report_file: Path,
):
    """Writes a standardized markdown report for audit and CI/CD tracking."""
    report_file.parent.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

    content = f"""# Bedray 2.0 Benchmark Report — {timestamp}

## 1. System & Configuration
* **GPU:** `{gpu_info}`
* **Driver:** `{driver_info}`
* **Vulkan API:** `{vulkan_info}`
* **Render Mode:** `{config.mode}`
* **Quality Preset:** `{config.preset}`
* **Render Distance:** `{config.render_distance}` chunks
* **Measurement Window:** `{config.duration_seconds}s` (Warmup: `{config.warmup_seconds}s`)
* **Peak Host Memory (RSS):** `{peak_rss:.1f} MB`

---

## 2. Frame Rate & Percentiles

| Metric | Value | Frame Time Equivalent |
|---|:---:|:---:|
| **Average FPS** | **{avg_fps:.1f} FPS** | `{avg_frametime:.2f} ms` |
| **1% Low FPS** | **{p1_fps:.1f} FPS** | `{(1000.0 / p1_fps if p1_fps > 0 else 0):.2f} ms` |
| **0.1% Low FPS** | **{p01_fps:.1f} FPS** | `{(1000.0 / p01_fps if p01_fps > 0 else 0):.2f} ms` |

---

## 3. GPU Compute Pass Timings (Per Frame)

| GPU Pass Name | Average Execution Time | Share of Budget (16.6ms) |
|---|:---:|:---:|
"""
    if passes:
        for name, ms in passes.items():
            share = (ms / 16.666) * 100.0
            content += f"| `{name}` | `{ms:.3f} ms` | `{share:.1f}%` |\n"
    else:
        content += "| *No granular pass telemetry found* | — | — |\n"

    content += f"""
---

## 4. Tracy Profiler Trace
"""
    if trace_path and trace_path.exists():
        content += f"* **Trace File:** [`{trace_path.name}`]({trace_path.as_posix()}) (`{trace_path.stat().st_size / 1024 / 1024:.2f} MB`)\n"
    else:
        content += "* *Trace file was not generated or tracy-capture was not found in PATH.*\n"

    report_file.write_text(content, encoding="utf-8")
    print(f"\n[+] Benchmark report saved: {report_file}")


def main():
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent.parent

    session_cfg = SessionConfig(
        duration_seconds=args.duration,
        warmup_seconds=args.warmup,
        mode=args.mode,
        preset=args.preset,
        render_distance=args.render_distance,
        enable_tracy=args.tracy,
    )

    timestamp_str = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    session_name = f"bedray_{args.mode}_{args.preset}_{timestamp_str}"
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    tracy_session = None
    if args.tracy:
        tracy_session = TracyCaptureSession(output_dir, session_name)
        active = tracy_session.start()
        if active:
            print(f"[+] Tracy background capture initialized on TCP {tracy_session.port}")
        else:
            print("[-] Note: tracy-capture executable not found in PATH; running with in-engine telemetry only.")

    game = GameSession(session_cfg, repo_root)
    telemetry = game.run(tracy_active=bool(tracy_session))

    trace_file = None
    if tracy_session:
        trace_file = tracy_session.stop()
        if trace_file:
            print(f"[+] Tracy trace finalized: {trace_file}")

    # Compute percentiles
    avg_fps, p1_fps, p01_fps, avg_ms = TracyCaptureSession.compute_percentiles(telemetry.frametimes_ms)

    # Print summary to terminal
    print("\n" + "=" * 60)
    print("           BEDRAY 2.0 BENCHMARK SUMMARY")
    print("=" * 60)
    print(f" Mode:            {args.mode} ({args.preset})")
    print(f" GPU:             {telemetry.detected_gpu}")
    print(f" Average FPS:     {avg_fps:.1f} FPS ({avg_ms:.2f} ms)")
    print(f" 1% Low:          {p1_fps:.1f} FPS")
    print(f" 0.1% Low:        {p01_fps:.1f} FPS")
    print(f" Peak Host RSS:   {telemetry.peak_rss_mb:.1f} MB")
    print("=" * 60)

    # Generate persistent markdown report
    report_file = output_dir / f"report_{session_name}.md"
    generate_markdown_report(
        config=session_cfg,
        avg_fps=avg_fps,
        p1_fps=p1_fps,
        p01_fps=p01_fps,
        avg_frametime=avg_ms,
        peak_rss=telemetry.peak_rss_mb,
        passes=telemetry.gpu_passes_ms,
        gpu_info=telemetry.detected_gpu,
        driver_info=telemetry.driver_version,
        vulkan_info=telemetry.vulkan_version,
        trace_path=trace_file,
        report_file=report_file,
    )


if __name__ == "__main__":
    main()
