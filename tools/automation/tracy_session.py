"""
Tracy Profiler session controller for Bedray 2.0.

Manages background capture of GPU/CPU profiling data via Tracy CLI (tracy-capture)
or directly connects to the Tracy network server protocol on TCP 8086.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


@dataclass
class TracyMetrics:
    """Aggregated metrics extracted from a Tracy profiling run."""
    total_frames: int = 0
    duration_seconds: float = 0.0
    avg_fps: float = 0.0
    p1_low_fps: float = 0.0
    p01_low_fps: float = 0.0
    avg_frametime_ms: float = 0.0
    gpu_zone_times_ms: dict[str, float] = field(default_factory=dict)
    trace_file: Optional[Path] = None


class TracyCaptureSession:
    """Controls a tracy-capture session over TCP."""

    DEFAULT_PORT = 8086

    def __init__(
        self,
        output_dir: Path,
        session_name: str,
        address: str = "127.0.0.1",
        port: int = DEFAULT_PORT,
    ):
        self.output_dir = output_dir
        self.session_name = session_name
        self.address = address
        self.port = port
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.trace_file = self.output_dir / f"{session_name}.tracy"
        self._process: Optional[subprocess.Popen] = None
        self._capture_binary = self._find_capture_binary()

    def _find_capture_binary(self) -> Optional[str]:
        """Locates tracy-capture in PATH or local tools cache."""
        bin_name = "tracy-capture.exe" if os.name == "nt" else "tracy-capture"
        found = shutil.which(bin_name)
        if found:
            return found
        # Check adjacent tools directories
        candidates = [
            Path.home() / ".tracy" / bin_name,
            Path("tools") / "tracy" / bin_name,
            Path("C:/Dev/tools/tracy") / bin_name,
        ]
        for c in candidates:
            if c.is_file():
                return str(c.resolve())
        return None

    def start(self) -> bool:
        """Starts tracy-capture in the background."""
        if not self._capture_binary:
            # Fallback: Capture binary not found, will rely on in-engine CSV/telemetry logs
            return False

        cmd = [
            self._capture_binary,
            "-a", self.address,
            "-p", str(self.port),
            "-o", str(self.trace_file),
            "-f",  # overwrite
        ]
        try:
            self._process = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            return True
        except Exception:
            self._process = None
            return False

    def stop(self, timeout_s: float = 10.0) -> Optional[Path]:
        """Stops capture and waits for the .tracy trace file to be finalized."""
        if self._process:
            try:
                # Tracy-capture terminates cleanly on SIGINT / Ctrl-C or process termination
                self._process.terminate()
                self._process.wait(timeout=timeout_s)
            except (subprocess.TimeoutExpired, Exception):
                self._process.kill()
            self._process = None

        if self.trace_file.exists() and self.trace_file.stat().st_size > 0:
            return self.trace_file
        return None

    @staticmethod
    def compute_percentiles(frametimes_ms: list[float]) -> tuple[float, float, float, float]:
        """Computes Avg, 1% Low, and 0.1% Low from raw frame times."""
        if not frametimes_ms:
            return 0.0, 0.0, 0.0, 0.0

        sorted_times = sorted(frametimes_ms)
        n = len(sorted_times)
        avg_ms = sum(sorted_times) / n
        avg_fps = 1000.0 / avg_ms if avg_ms > 0 else 0.0

        # 1% worst frames (99th percentile of frame time)
        idx_1p = max(0, int(n * 0.99) - 1)
        p1_time = sorted_times[idx_1p]
        p1_fps = 1000.0 / p1_time if p1_time > 0 else 0.0

        # 0.1% worst frames (99.9th percentile)
        idx_01p = max(0, int(n * 0.999) - 1)
        p01_time = sorted_times[idx_01p]
        p01_fps = 1000.0 / p01_time if p01_time > 0 else 0.0

        return avg_fps, p1_fps, p01_fps, avg_ms
