"""Raw gameplay profiling and strict native acceptance. Standard library only."""
from __future__ import annotations

import csv
import json
import math
from pathlib import Path
from statistics import fmean

NATIVE_FLAGS = 127
PROGRAMS = {base + suffix for base in ("path_trace", "path_trace_scene", "rc_path_trace_scene")
            for suffix in ("", ".raygen", ".ser")}


class InvalidCapture(ValueError):
    """Missing evidence or inconsistent capture; never a zero-FPS result."""


def number(value: object, field: str, *, positive: bool = False) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError, OverflowError) as error:
        raise InvalidCapture(f"{field}: missing or nonnumeric value") from error
    if not math.isfinite(result) or (result <= 0 if positive else result < 0):
        raise InvalidCapture(f"{field}: invalid timing/value {value!r}")
    return result


def integer(value: object, field: str) -> int:
    if isinstance(value, bool):
        raise InvalidCapture(f"{field}: boolean instead of integer")
    try:
        result = int(str(value))
    except (TypeError, ValueError) as error:
        raise InvalidCapture(f"{field}: invalid integer {value!r}") from error
    return result


def timing_stats(values: list[float]) -> dict[str, float]:
    if not values:
        raise InvalidCapture("no samples")
    for value in values:
        number(value, "timing", positive=True)
    ordered = sorted(values)
    def percentile(fraction: float) -> float:
        return ordered[math.ceil(len(ordered) * fraction) - 1]  # nearest rank
    def low(fraction: float) -> float:
        count = max(1, math.ceil(len(ordered) * fraction))
        return 1000 / fmean(ordered[-count:])  # reciprocal mean of the slowest tail
    return {"mean_ms": fmean(ordered), "p95_ms": percentile(.95), "p99_ms": percentile(.99),
            "max_ms": ordered[-1], "average_fps": 1000 / fmean(ordered),
            "one_percent_low_fps": low(.01), "point_one_percent_low_fps": low(.001)}


def analyze(directory: Path, target_fps: float = 60) -> dict:
    """Strict native-1080p acceptance; a gameplay profile cannot bypass its requirements."""
    return _analyze(directory, target_fps, native=True)


def profile(directory: Path) -> dict:
    """Current-workload diagnostics, including QHD/DLSS. Makes no native acceptance decision."""
    return _analyze(directory, 60, native=False)


def _analyze(directory: Path, target_fps: float, *, native: bool) -> dict:
    number(target_fps, "target_fps", positive=True)
    with (directory / "manifest.json").open(encoding="utf-8") as stream:
        manifest = json.load(stream)
    if not isinstance(manifest, dict) or type(manifest.get("schema")) is not int or manifest["schema"] != 1:
        raise InvalidCapture("unsupported capture schema")
    if manifest.get("capture_complete") is not True:
        raise InvalidCapture(f"incomplete capture: {manifest.get('reason', 'unknown')}")
    if manifest.get("native_required_flags") != NATIVE_FLAGS:
        raise InvalidCapture("unknown native-evidence contract")
    if manifest.get("interval_source") != "application_render_begin_to_next_render_begin":
        raise InvalidCapture("interval source is not raw application frame heads")
    if manifest.get("gpu_source") != "available_engine_timestamp_queries_correlated_by_application_frame":
        raise InvalidCapture("uncorrelated GPU timing source")
    minimum_warmup = 10e9 if native else 5e9
    if number(manifest.get("requested_warmup_ns"), "warmup") < minimum_warmup:
        raise InvalidCapture(f"requires at least {minimum_warmup / 1e9:g} seconds warmup")
    duration = number(manifest.get("requested_duration_ns"), "duration", positive=True)
    if duration < 30e9:
        raise InvalidCapture("native acceptance requires at least 30 seconds collection")
    intervals, cpu, gpu, rt = [], [], [], []
    previous, quality, rendering = None, None, None
    with (directory / "frames.csv").open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        required = {"frame", "interval_ms", "flags", "render_width", "render_height", "output_width", "output_height",
                    "mode", "program", "spp", "bounces", "ray_distance", "trace_flags", "upscaler_request", "upscaler_active",
                    "framegen_state", "denoiser", "gpu_resolved", "cpu_total_ms", "gpu_total_ms", "gpu_rt_ms"}
        if not reader.fieldnames or len(reader.fieldnames) != len(set(reader.fieldnames)) or not required <= set(reader.fieldnames):
            raise InvalidCapture("missing or duplicate CSV columns")
        for line, row in enumerate(reader, 2):
            where = f"CSV line {line}"
            if None in row or any(value is None for value in row.values()):
                raise InvalidCapture(f"{where}: wrong column count")
            frame = integer(row["frame"], where)
            if frame < 0 or (previous is not None and frame != previous + 1):
                raise InvalidCapture(f"{where}: noncontiguous application frames")
            previous = frame
            required_flags = NATIVE_FLAGS if native else 99  # world + actual PT + screen closed + focused
            if integer(row["flags"], where) & required_flags != required_flags:
                raise InvalidCapture(f"{where}: PT/world/focus/screen/upscaler/FG proof failed")
            dimensions = tuple(integer(row[key], where) for key in
                               ("render_width", "render_height", "output_width", "output_height"))
            if any(value <= 0 for value in dimensions):
                raise InvalidCapture(f"{where}: invalid dimensions {dimensions}")
            if native and dimensions != (1920, 1080, 1920, 1080):
                raise InvalidCapture(f"{where}: effective dimensions {dimensions}, expected native 1080p")
            if (row["mode"] != "path" or row["program"] not in PROGRAMS
                    or (native and (row["upscaler_request"] != "BILINEAR" or row["upscaler_active"] != "none"
                                    or row["framegen_state"] not in ("READY", "UNAVAILABLE")))):
                raise InvalidCapture(f"{where}: incompatible effective rendering mode")
            current_rendering = dimensions + (row["upscaler_request"], row["upscaler_active"], row["framegen_state"])
            if rendering is not None and current_rendering != rendering:
                raise InvalidCapture(f"{where}: resolution/upscaler/FG changed during capture")
            rendering = current_rendering
            spp, bounces = integer(row["spp"], where), integer(row["bounces"], where)
            if spp < 1 or bounces < 1:
                raise InvalidCapture(f"{where}: invalid quality")
            current = (row["program"], spp, bounces, number(row["ray_distance"], where, positive=True),
                       integer(row["trace_flags"], where), row["denoiser"])
            if quality is not None and current != quality:
                raise InvalidCapture(f"{where}: quality/program changed during capture; use fixed settings for A/B")
            quality = current
            if row["gpu_resolved"] != "1":
                raise InvalidCapture(f"{where}: GPU queries were not resolved")
            intervals.append(number(row["interval_ms"], where, positive=True))
            cpu.append(number(row["cpu_total_ms"], f"{where} CPU total"))
            gpu.append(number(row["gpu_total_ms"], f"{where} GPU total", positive=True))
            rt.append(number(row["gpu_rt_ms"], f"{where} GPU RT", positive=True))
            if len(intervals) > 120_000:
                raise InvalidCapture("capture exceeds bounded sample capacity")
    if not intervals or len(intervals) != integer(manifest.get("rows"), "manifest rows"):
        raise InvalidCapture("no samples or row count mismatch")
    measured = math.fsum(intervals) * 1e6
    if measured < duration - 1 or not math.isclose(measured, number(manifest.get("measured_interval_ns"), "measured duration"),
                                                 rel_tol=1e-9, abs_tol=1):
        raise InvalidCapture("truncated or inconsistent frame intervals")
    stats = timing_stats(intervals)
    budget = 1000 / target_fps
    if not native:
        return {"schema": 1, "valid_gameplay_profile": True, "native_acceptance": False,
                "frames": len(intervals), "duration_seconds": measured / 1e9, "frame_intervals": stats,
                "mean_cpu_total_ms": fmean(cpu), "mean_gpu_total_ms": fmean(gpu), "mean_gpu_rt_ms": fmean(rt),
                "quality": dict(zip(("program", "spp", "bounces", "ray_distance", "trace_flags", "denoiser_request"), quality)),
                "rendering": dict(zip(("render_width", "render_height", "output_width", "output_height",
                                       "upscaler_request", "upscaler_active", "framegen_state"), rendering)),
                "context": manifest.get("context", {}),
                "scope": "Actual application frames; includes all slow frames, excludes generated-frame FPS. "
                         "GPU totals exclude submit/present. Not native-1080p acceptance or an FPS guarantee."}
    return {"schema": 1, "valid_native_capture": True, "frames": len(intervals), "duration_seconds": measured / 1e9,
            "target_fps": target_fps, "frame_budget_ms": budget,
            "criterion": "average_fps >= target and slowest_1_percent_mean_fps >= target",
            "target_met_in_capture": stats["average_fps"] >= target_fps and stats["one_percent_low_fps"] >= target_fps,
            "all_frames_within_budget": max(intervals) <= budget,
            "frames_over_budget": sum(value > budget for value in intervals), "frame_intervals": stats,
            "mean_cpu_total_ms": fmean(cpu), "mean_gpu_total_ms": fmean(gpu), "mean_gpu_rt_ms": fmean(rt),
            "quality": dict(zip(("program", "spp", "bounces", "ray_distance", "trace_flags", "denoiser_request"), quality)),
            "context": manifest.get("context", {}),
            "scope": "One recorded workload; CPU/GPU totals exclude submit/present. No hardware FPS guarantee."}
