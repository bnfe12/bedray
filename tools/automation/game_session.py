"""
Game session manager for Bedray automated testing and benchmarking.

Supports launching Minecraft via:
1. Direct Fabric/NeoForge Gradle runClient task
2. Existing launcher instance (FreesmLauncher, Prism, Modrinth)
3. Direct Java command-line with injected Bedray and Tracy options.
"""

from __future__ import annotations

import os
import re
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import psutil


@dataclass
class SessionConfig:
    """Benchmark session configuration parameters."""
    duration_seconds: int = 30
    warmup_seconds: int = 10
    mode: str = "path_tracing"        # voxel_vanilla | voxel_rt | path_tracing
    preset: str = "quality"           # fast | quality | expert
    resolution: tuple[int, int] = (1920, 1080)
    render_distance: int = 12
    enable_tracy: bool = True
    tracy_port: int = 8086
    instance_path: Optional[Path] = None
    extra_jvm_args: list[str] = field(default_factory=list)


@dataclass
class TelemetryResult:
    """Parsed in-engine telemetry and performance statistics."""
    total_frames: int = 0
    frametimes_ms: list[float] = field(default_factory=list)
    gpu_passes_ms: dict[str, float] = field(default_factory=dict)
    peak_rss_mb: float = 0.0
    detected_gpu: str = "Unknown"
    driver_version: str = "Unknown"
    vulkan_version: str = "Unknown"
    validation_errors: list[str] = field(default_factory=list)


class GameSession:
    """Manages the lifecycle of a Minecraft benchmark execution."""

    DEFAULT_INSTANCE = Path.home() / "AppData/Roaming/FreesmLauncher/instances/Fabulously Optimized/minecraft"

    def __init__(self, config: SessionConfig, repo_root: Path):
        self.config = config
        self.repo_root = repo_root
        self.instance_path = config.instance_path or self.DEFAULT_INSTANCE
        self.log_file = self.instance_path / "logs" / "latest.log"
        self._process: Optional[subprocess.Popen] = None
        self._psutil_proc: Optional[psutil.Process] = None

    def build_jvm_args(self) -> list[str]:
        """Constructs all required system properties for Bedray and Tracy."""
        args = [
            f"-Dbedray.mode={self.config.mode}",
            f"-Dbedray.preset={self.config.preset}",
            f"-Dbedray.renderDistance={self.config.render_distance}",
            f"-Dbedray.benchmark.duration={self.config.duration_seconds}",
            f"-Dbedray.benchmark.warmup={self.config.warmup_seconds}",
        ]
        if self.config.enable_tracy:
            args.extend([
                "-Dbedray.tracy.enabled=true",
                f"-Dbedray.tracy.port={self.config.tracy_port}",
            ])
        args.extend(self.config.extra_jvm_args)
        return args

    def run(self, tracy_active: bool = False) -> TelemetryResult:
        """Executes the benchmark session, monitoring process health and telemetry."""
        result = TelemetryResult()
        print(f"[*] Starting Bedray benchmark: mode={self.config.mode}, preset={self.config.preset}")
        print(f"[*] Instance: {self.instance_path}")
        print(f"[*] Duration: {self.config.duration_seconds}s (Warmup: {self.config.warmup_seconds}s)")

        # Rotate or truncate old log file before session
        if self.log_file.exists():
            try:
                self.log_file.unlink()
            except OSError:
                pass

        # Launch client via gradle runClient if in development workspace
        gradle_cmd = "gradlew.bat" if os.name == "nt" else "./gradlew"
        gradle_path = self.repo_root / gradle_cmd

        cmd = [
            str(gradle_path),
            ":fabric-26.1:runClient",
            "--no-daemon",
        ]
        for jvm_arg in self.build_jvm_args():
            cmd.append(f"-Djvm.args={jvm_arg}")

        start_time = time.time()
        try:
            self._process = subprocess.Popen(
                cmd,
                cwd=str(self.repo_root),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            self._psutil_proc = psutil.Process(self._process.pid)

            # Monitor memory and collect output
            peak_rss = 0.0
            total_duration = self.config.warmup_seconds + self.config.duration_seconds

            while time.time() - start_time < total_duration:
                if self._process.poll() is not None:
                    # Premature exit
                    break

                try:
                    # Track RSS across all child processes (JVM, Tracy helper)
                    mem = self._psutil_proc.memory_info().rss
                    for child in self._psutil_proc.children(recursive=True):
                        mem += child.memory_info().rss
                    rss_mb = mem / (1024.0 * 1024.0)
                    if rss_mb > peak_rss:
                        peak_rss = rss_mb
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass

                time.sleep(1.0)

            result.peak_rss_mb = peak_rss

        finally:
            self._terminate_cleanly()

        # Parse telemetry from log
        self._parse_log_telemetry(result)
        return result

    def _terminate_cleanly(self):
        """Cleanly terminates the game session."""
        if self._process and self._process.poll() is None:
            print("[*] Benchmark duration reached. Stopping game session cleanly...")
            try:
                self._process.terminate()
                self._process.wait(timeout=10.0)
            except (subprocess.TimeoutExpired, Exception):
                self._process.kill()
            self._process = None

    def _parse_log_telemetry(self, result: TelemetryResult):
        """Scans the Minecraft log file for Bedray timing markers and Vulkan telemetry."""
        if not self.log_file.exists():
            return

        re_gpu = re.compile(r"\[VKB-RT\]\s+gpu='([^']+)'\s+driver='([^']+)'\s+api='([^']+)'")
        re_timing = re.compile(r"\[VKB-PASS\]\s+name=([a-zA-Z0-9_]+)\s+time_ms=([0-9\.]+)")
        re_frame = re.compile(r"\[VKB-FRAME\]\s+frame_time_ms=([0-9\.]+)")
        re_error = re.compile(r"(\[VKB-ERROR\]|Validation Error|VK_ERROR_[A-Z_]+)")

        try:
            with open(self.log_file, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    mg = re_gpu.search(line)
                    if mg:
                        result.detected_gpu = mg.group(1)
                        result.driver_version = mg.group(2)
                        result.vulkan_version = mg.group(3)

                    mt = re_timing.search(line)
                    if mt:
                        pass_name = mt.group(1)
                        time_ms = float(mt.group(2))
                        result.gpu_passes_ms[pass_name] = time_ms

                    mf = re_frame.search(line)
                    if mf:
                        result.frametimes_ms.append(float(mf.group(1)))

                    me = re_error.search(line)
                    if me:
                        result.validation_errors.append(line.strip())
        except Exception:
            pass

        result.total_frames = len(result.frametimes_ms)
