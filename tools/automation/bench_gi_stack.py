"""Orchestrate native_perf captures across GI-stack variants on the real game instance.

Launches FreesmLauncher, lets the autotest harness run one fixed-seed/fixed-camera
native_perf capture per variant, then analyzes the capture (strict native-1080p
acceptance plus median, which capture_analysis does not compute), parses latest.log
for backend/memory/BLAS evidence and writes one JSON per variant into docs/measurements.

Only the instance window size and JvmArgs are edited (backed up and restored). ReSTIR/backend
selection uses session overrides (-Dvkrtx.rt.<key>, -Dbedray.rt.*) injected through instance.cfg
JvmArgs (FreesmLauncher strips JAVA_TOOL_OPTIONS from the child JVM), never saved bedray.toml config.
Standard library only. The game must be closed before starting; each run exits itself.
"""
from __future__ import annotations

import argparse
import contextlib
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from statistics import fmean, median

from capture_analysis import InvalidCapture, analyze
from focus_hold import FocusHold

DEFAULT_INSTANCE = Path(
    "C:/Users/andre/AppData/Roaming/FreesmLauncher/instances/Vulkan Experimental RT 26.3")
DEFAULT_LAUNCHER = Path(
    "C:/Users/andre/AppData/Local/Programs/FreesmLauncher/freesmlauncher.exe")

# label -> extra JVM -D properties (session overrides; never written to bedray.toml)
VARIANTS: dict[str, dict[str, str]] = {
    "compute_pt_off": {"vkrtx.rt.path_restir_pt": "false", "vkrtx.rt.path_restir_pt_spatial": "false"},
    "compute_pt_on": {"vkrtx.rt.path_restir_pt": "true", "vkrtx.rt.path_restir_pt_spatial": "true"},
    "raygen_pt_off": {"bedray.rt.raygen": "true", "vkrtx.rt.path_restir_pt": "false",
                      "vkrtx.rt.path_restir_pt_spatial": "false"},
    "raygen_pt_on": {"bedray.rt.raygen": "true", "vkrtx.rt.path_restir_pt": "true",
                     "vkrtx.rt.path_restir_pt_spatial": "true"},
    "ser_pt_off": {"bedray.rt.ser": "true", "vkrtx.rt.path_restir_pt": "false",
                   "vkrtx.rt.path_restir_pt_spatial": "false"},
    "ser_pt_on": {"bedray.rt.ser": "true", "vkrtx.rt.path_restir_pt": "true",
                  "vkrtx.rt.path_restir_pt_spatial": "true"},
    # Denoiser A/B: identical PT-on GI stack, only the lit-view denoiser differs. NRD REBLUR denoises
    # diffuse+specular in one instance (vkb_nrd.cpp REBLUR_DIFFUSE_SPECULAR); DLSS-RR is the NGX Vulkan arm.
    "denoiser_rr": {"vkrtx.rt.path_restir_pt": "true", "vkrtx.rt.path_restir_pt_spatial": "true",
                    "vkrtx.rt.path_denoiser": "dlss_rr"},
    "denoiser_nrd": {"vkrtx.rt.path_restir_pt": "true", "vkrtx.rt.path_restir_pt_spatial": "true",
                     "vkrtx.rt.path_denoiser": "nrd"},
    # ReLAX arm: path_denoiser=nrd plus the -Dvkrtx.denoiser=nrd_relax expert override resolves to NRD_RELAX
    # (DenoiserResolution case NRD); native createDenoiser applies default RelaxSettings + primary-lobe config.
    "denoiser_nrd_relax": {"vkrtx.rt.path_restir_pt": "true", "vkrtx.rt.path_restir_pt_spatial": "true",
                           "vkrtx.rt.path_denoiser": "nrd", "vkrtx.denoiser": "nrd_relax"},
    # DLSS-RR render-preset A/B (item 4): identical PT-on RR stack, only the NGX render-preset hint differs.
    # -Dvkrtx.rt.dlss_rr_preset pins the session (RtSettings.dlssRrPreset); the default arm is denoiser_rr
    # (no override = Preset.DEFAULT, NGX param untouched). Default-off until a same-session A/B has numbers.
    "denoiser_rr_preset_d": {"vkrtx.rt.path_restir_pt": "true", "vkrtx.rt.path_restir_pt_spatial": "true",
                             "vkrtx.rt.path_denoiser": "dlss_rr", "vkrtx.rt.dlss_rr_preset": "d"},
    "denoiser_rr_preset_e": {"vkrtx.rt.path_restir_pt": "true", "vkrtx.rt.path_restir_pt_spatial": "true",
                             "vkrtx.rt.path_denoiser": "dlss_rr", "vkrtx.rt.dlss_rr_preset": "e"},
    "denoiser_rr_preset_f": {"vkrtx.rt.path_restir_pt": "true", "vkrtx.rt.path_restir_pt_spatial": "true",
                             "vkrtx.rt.path_denoiser": "dlss_rr", "vkrtx.rt.dlss_rr_preset": "f"},
}
BASELINE = ["compute_pt_off", "compute_pt_on", "raygen_pt_off", "raygen_pt_on"]
SER_AB = ["compute_pt_on", "raygen_pt_on", "ser_pt_on"]
DENOISER_AB = ["denoiser_rr", "denoiser_nrd", "denoiser_nrd_relax"]
RR_PRESET_AB = ["denoiser_rr", "denoiser_rr_preset_d", "denoiser_rr_preset_e", "denoiser_rr_preset_f"]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def merge_index(existing_doc: object, summary: list[dict]) -> dict:
    """Merge new capture summaries into the measurement index, preserving non-run metadata.

    Top-level keys other than ``runs`` (e.g. ``pending_captures`` documenting wired-but-GPU-gated arms)
    are preserved so a capture run never silently drops documentation. A ``runs`` entry whose label was
    re-captured this session is replaced, not duplicated. Pure: no filesystem, unit-testable.
    """
    doc = dict(existing_doc) if isinstance(existing_doc, dict) else {}
    recap = {s["label"] for s in summary}
    runs = [r for r in doc.get("runs", []) if r.get("label") not in recap]
    runs += [{"label": s["label"], "capture_status": s["capture_status"],
              "target_met_in_capture": s.get("acceptance", {}).get("target_met_in_capture"),
              "frames": s.get("acceptance", {}).get("frames"), "file": f"{s['label']}.json"} for s in summary]
    doc["schema"] = 1
    doc["runs"] = runs
    return doc


def game_running() -> bool:
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq javaw.exe", "/NH"],
                         capture_output=True, text=True).stdout
    return "javaw.exe" in out.lower()


def wait_game_exit(timeout_s: float = 90) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if not game_running():
            return True
        time.sleep(2)
    return not game_running()


def stop_launcher() -> None:
    """Terminate any persistent FreesmLauncher so the next --launch reads instance.cfg from disk.

    A running launcher caches instance settings in memory and forwards --launch over IPC, silently
    ignoring JvmArgs written to disk after it started; the native-memory override never reached the
    JVM that way (prepare_frame LIMIT disabled RT). The game itself must already be closed.
    """
    subprocess.run(["taskkill", "/F", "/T", "/IM", "freesmlauncher.exe"],
                   capture_output=True, text=True)
    time.sleep(2)


def set_window(instance: Path, width: int, height: int, render_distance: int) -> dict[str, str]:
    """Set windowed resolution and a controlled render distance; return original file contents.

    Render distance bounds the native scene size. RD32 native-1080p grows past 4 GiB (no eviction),
    which disables RT at the prepare_frame LIMIT before a capture can record; the stand uses a smaller
    RD that fits the budget and stabilizes, and RD32 is revisited once BLAS compaction lands.
    """
    backup: dict[str, str] = {}
    cfg = instance / "instance.cfg"
    text = cfg.read_text(encoding="utf-8")
    backup["instance.cfg"] = text
    text = re.sub(r"MinecraftWinWidth=\d+", f"MinecraftWinWidth={width}", text)
    text = re.sub(r"MinecraftWinHeight=\d+", f"MinecraftWinHeight={height}", text)
    cfg.write_text(text, encoding="utf-8")
    options = instance / "minecraft" / "options.txt"
    otext = options.read_text(encoding="utf-8")
    backup["options.txt"] = otext
    otext = re.sub(r"overrideWidth:\d+", f"overrideWidth:{width}", otext)
    otext = re.sub(r"overrideHeight:\d+", f"overrideHeight:{height}", otext)
    otext = re.sub(r"renderDistance:\d+", f"renderDistance:{render_distance}", otext)
    options.write_text(otext, encoding="utf-8")
    return backup


def restore_files(instance: Path, backup: dict[str, str]) -> None:
    for name, text in backup.items():
        target = instance / name if name == "instance.cfg" else instance / "minecraft" / name
        target.write_text(text, encoding="utf-8")


# Prism's built-in default JvmArgs; preserved so overriding them does not drop the heap-dump/language flags.
PRISM_DEFAULT_JVM_ARGS = (
    "-Duser.language=en -XX:StackShadowPages=32 "
    "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump")


def set_jvm_args(instance: Path, props: dict[str, str]) -> None:
    """Inject session -D props through instance.cfg JvmArgs, which FreesmLauncher appends to the java command line.

    JAVA_TOOL_OPTIONS set on the launcher process is stripped before the JVM starts, so the props must travel
    through the instance's own Java-argument override (OverrideJavaArgs=true + JvmArgs=...), exactly as the UI sets it.
    """
    cfg = instance / "instance.cfg"
    text = cfg.read_text(encoding="utf-8")
    args = PRISM_DEFAULT_JVM_ARGS + " " + " ".join(f"-D{k}={v}" for k, v in props.items())
    lines = [ln for ln in text.splitlines()
             if not ln.startswith("JvmArgs=") and not ln.startswith("OverrideJavaArgs=")]
    idx = lines.index("[General]") + 1
    lines[idx:idx] = ["OverrideJavaArgs=true", f"JvmArgs={args}"]
    cfg.write_text("\n".join(lines) + "\n", encoding="utf-8")


def newest_capture(game: Path, since: float) -> Path | None:
    root = game / "autotest" / "native_perf"
    if not root.is_dir():
        return None
    candidates = [d for d in root.glob("capture-*") if (d / "manifest.json").is_file()
                  and d.stat().st_mtime >= since]
    return max(candidates, key=lambda d: d.stat().st_mtime) if candidates else None


def wait_capture(game: Path, since: float, timeout_s: float) -> tuple[Path | None, str]:
    """Wait for a complete manifest or a harness FAIL. Returns (capture_dir, status)."""
    log = game / "logs" / "latest.log"
    deadline = time.monotonic() + timeout_s
    seen_running = False
    while time.monotonic() < deadline:
        if game_running():
            seen_running = True
        capture = newest_capture(game, since)
        if capture is not None:
            try:
                manifest = json.loads((capture / "manifest.json").read_text(encoding="utf-8"))
                if manifest.get("capture_complete") is True:
                    return capture, "saved"
            except (OSError, ValueError):
                pass
        if log.is_file() and log.stat().st_mtime >= since:
            tail = log.read_text(encoding="utf-8", errors="replace")[-20000:]
            if re.search(r"\[VKB-AUTOTEST\].*result=FAIL", tail):
                return capture, "harness_fail"
            if re.search(r"\[VKB-AUTOTEST\].*result=PASS", tail):
                # PASS logged before manifest flush; give persistence a moment.
                time.sleep(3)
                capture = newest_capture(game, since)
                return capture, "saved" if capture else "pass_without_capture"
        if seen_running and not game_running():
            return newest_capture(game, since), "game_exited_early"
        time.sleep(3)
    return newest_capture(game, since), "timeout"


def parse_log(log: Path) -> dict:
    """Extract backend, memory, BLAS, scene and rt_pass evidence from one run's log."""
    text = log.read_text(encoding="utf-8", errors="replace")
    evidence: dict = {}
    raygen = re.findall(r"\[VKB-RAYGEN\] pass=(\S+) stage=(\S+) ser_requested=(\S+) "
                        r"reordering_hint=(\S+) enabled=(\S+)", text)
    evidence["raygen_selections"] = [
        {"pass": p, "stage": s, "ser_requested": sr, "reordering_hint": rh, "enabled": en}
        for p, s, sr, rh, en in raygen]
    scene = re.search(r"native scene ready: dll=(\S+) abi=(\d+) frames=(\d+) "
                      r"max_sections=(\d+) max_geometry_mb=(\d+) features=(\S+)", text)
    if scene:
        evidence["native_scene"] = {"dll": scene[1], "abi": int(scene[2]), "frames": int(scene[3]),
                                    "max_sections": int(scene[4]), "max_geometry_mb": int(scene[5]),
                                    "features": scene[6]}
    denoiser = re.search(r"denoiser requested=(\S+) active=(\S+)", text)
    if denoiser:
        evidence["denoiser"] = {"requested": denoiser[1], "active": denoiser[2]}
    memory = [{"reserved": int(m[1]), "used": int(m[2]), "peak_reserved": int(m[3]),
               "allocations": int(m[4]), "heap_usage": int(m[5])}
              for m in re.finditer(r"\[VKB-MEMORY\] event=sample limit_bytes=\d+ reserved_bytes=(\d+) "
                                   r"used_bytes=(\d+) peak_reserved_bytes=(\d+) allocations=(\d+) "
                                   r"blocks=\d+ heap_budget_bytes=\d+ heap_usage_bytes=(\d+)", text)]
    if memory:
        evidence["memory"] = {
            "samples": len(memory),
            "reserved_max": max(m["reserved"] for m in memory),
            "used_max": max(m["used"] for m in memory),
            "peak_reserved_max": max(m["peak_reserved"] for m in memory),
            "heap_usage_max": max(m["heap_usage"] for m in memory)}
    blas = [{"built": int(m[1]), "total": int(m[2]), "build_ms": float(m[3]),
             "tlas_instances": int(m[4]), "tlas_ms": float(m[5]), "vram_mb": int(m[6]),
             "veg_sections": int(m[7]), "veg_quads": int(m[8])}
            for m in re.finditer(r"\[VKB-RT\] blas built=(\d+) total=(\d+) build_ms=([\d.]+) "
                                 r"tlas instances=(\d+) tlas_ms=([\d.]+) vram_mb=(\d+) pending=\d+ "
                                 r"(?:failed=\d+ )?identical_skipped=\d+ veg_sections=(\d+) veg_quads=(\d+)", text)]
    if blas:
        evidence["blas"] = {"events": len(blas), "build_ms_total": round(fmean([b["build_ms"] for b in blas]), 3),
                            "build_ms_max": max(b["build_ms"] for b in blas),
                            "vram_mb_max": max(b["vram_mb"] for b in blas),
                            "tlas_instances_max": max(b["tlas_instances"] for b in blas),
                            "veg_quads_max": max(b["veg_quads"] for b in blas), "last": blas[-1]}
    sections = []
    for line in text.splitlines():
        if "final=true" in line:
            continue
        m = re.search(r"\[VKB-RT\] sections=(\d+) quads=(\d+) solid=(\d+) cutout=(\d+) translucent=(\d+)", line)
        if m and int(m[1]) > 0:
            sections.append(m.groups())
    if sections:
        last = sections[-1]
        evidence["scene_sections"] = {"sections": int(last[0]), "quads": int(last[1]), "solid": int(last[2]),
                                      "cutout": int(last[3]), "translucent": int(last[4])}
    rt_pass = []
    for line in text.splitlines():
        if "rt_pass ok" not in line:
            continue
        fields = dict(re.findall(r"(\w+)=([\d.]+|n/a|off)", line))
        if "work_ms" in fields and fields["work_ms"] != "n/a":
            rt_pass.append({"work_ms": float(fields["work_ms"]), "pass_ms": float(fields["pass_ms"]),
                            "hit_pct": fields.get("hit_pct", "n/a")})
    if rt_pass:
        work = [r["work_ms"] for r in rt_pass]
        evidence["rt_pass_gpu_ewma"] = {"samples": len(work), "work_ms_median": median(work),
                                        "work_ms_p95": sorted(work)[max(0, int(len(work) * 0.95) - 1)],
                                        "work_ms_mean": round(fmean(work), 3)}
    # "world" GPU span brackets RtWorld.update() (BLAS+TLAS+entity builds): a TLAS-inclusive upper bound; an
    # isolated TLAS stamp would need the native prepareFrame split (ABI change, owner-gated). See doc 14.
    gpu_span_lines = [ln for ln in text.splitlines() if "[VKB-RT-GPU] gpu_ms" in ln]
    if gpu_span_lines:
        spans = {name: float(val) for name, val in re.findall(r"(\w+)=([\d.]+)\(n=\d+\)", gpu_span_lines[-1])}
        evidence["gpu_spans"] = {"windows": len(gpu_span_lines), "last": spans}
        if "world" in spans:
            evidence["tlas_gpu_ms_upper_bound"] = spans["world"]
    autotest = re.findall(r"\[VKB-AUTOTEST\] (.*)", text)
    evidence["autotest_events"] = [line for line in autotest
                                   if any(k in line for k in ("event=world_ready", "result=", "event=capture_saved",
                                                              "event=armed", "event=installed"))][-8:]
    failed = re.findall(r"FAILED stage=(\S+)", text)
    if failed:
        evidence["rt_failed_stages"] = sorted(set(failed))
    return evidence


def capture_medians(capture: Path) -> dict:
    columns = {"interval_ms": [], "cpu_total_ms": [], "gpu_total_ms": [], "gpu_rt_ms": []}
    with (capture / "frames.csv").open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            for key in columns:
                value = row.get(key, "")
                if value not in ("", "n/a"):
                    columns[key].append(float(value))
    result = {}
    for key, values in columns.items():
        if values:
            ordered = sorted(values)
            result[key] = {"median": median(values), "p95": ordered[max(0, int(len(ordered) * 0.95) - 1)],
                           "mean": round(fmean(values), 4), "n": len(values)}
    return result


def run_variant(label: str, props: dict[str, str], instance: Path, launcher: Path,
                out_dir: Path, scratch: Path, revision: str, timeout_s: float,
                render_distance: int, hold_focus: bool = False) -> dict:
    game = instance / "minecraft"
    started = time.time()
    full_props = {
        "vkbridge.autotest": "native_perf",
        "vkrtx.rt.adaptive_quality": "off",
        "vkrtx.autotest.captureLabel": label,
        "vkrtx.autotest.revisionHint": revision,
        "vkrtx.rt.path_denoiser": "dlss_rr",
        # ReSTIR PT is a child of ReSTIR DI: RtSlangPathPass.ptActive() requires diActive(), so forcing DI off here
        # silently disabled PT in every *_pt_on variant (the enabled mask never carried bit 17). Keep DI on — the game
        # default — in both arms so pt_off/pt_on isolates PT alone. A daylight world has an empty emitter list, so DI
        # dispatches nothing but diActive() stays true and PT still runs on indirect light.
        "vkrtx.rt.path_restir_di": "true",
        # RD32 native-1080p in the fixed-seed world exhausts the default core budget (deviceLocal/3 ~= 2630 MiB),
        # which disables RT for the session (prepare_frame LIMIT) and yields a 0-frame capture. Provision 4 GiB
        # (heap budget ~6.95 GiB on the 8 GB card); identical for every variant so A/B stays fair.
        "bedray.rt.nativeMemoryMiB": "4096",
        **props,
    }
    env = dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = " ".join(f"-D{k}={v}" for k, v in full_props.items())
    stop_launcher()
    # Rotate the previous run's log so wait_capture cannot read a stale FAIL/PASS verdict.
    prev_log = game / "logs" / "latest.log"
    if prev_log.is_file():
        shutil.move(str(prev_log), str(scratch / f"prev-latest-{label}.log"))
    set_jvm_args(instance, full_props)
    print(f"[bench] {label}: launching ({len(full_props)} props via JvmArgs)", flush=True)
    launcher_log = scratch / f"launcher-{label}.log"
    # Opt-in: hold the real window focus so the strict FOCUSED gate (bit 6) survives the whole capture on a
    # desktop that would otherwise steal focus. Only runs while this bench owns the desktop (game_running gate).
    with FocusHold("Minecraft") if hold_focus else contextlib.nullcontext() as focus:
        with launcher_log.open("w", encoding="utf-8") as stream:
            subprocess.Popen([str(launcher), "--launch", instance.name], env=env,
                             stdout=stream, stderr=subprocess.STDOUT)
        capture, status = wait_capture(game, started, timeout_s)
    if hold_focus:
        print(f"[bench] {label}: focus-hold refocused {focus.refocus_count} time(s)", flush=True)
    exited = wait_game_exit()
    log_copy = scratch / f"latest-{label}.log"
    if (game / "logs" / "latest.log").is_file():
        shutil.copyfile(game / "logs" / "latest.log", log_copy)
    record: dict = {
        "schema": 1, "label": label, "variant_props": full_props, "revision_hint": revision,
        "render_distance": render_distance,
        "started_utc": datetime.fromtimestamp(started, timezone.utc).isoformat(),
        "capture_status": status, "game_exited_cleanly": exited,
        "instance": str(instance), "log_copy": str(log_copy),
    }
    if capture is not None:
        record["capture_dir"] = str(capture)
        record["capture_sha256"] = {name: sha256(capture / name)
                                    for name in ("frames.csv", "manifest.json") if (capture / name).is_file()}
        try:
            record["acceptance"] = analyze(capture)
        except (InvalidCapture, OSError, ValueError) as error:
            record["acceptance"] = {"valid_native_capture": False, "error": str(error)}
        try:
            record["medians"] = capture_medians(capture)
        except (OSError, ValueError, KeyError) as error:
            record["medians"] = {"error": str(error)}
    if log_copy.is_file():
        record["log_evidence"] = parse_log(log_copy)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / f"{label}.json"
    out_file.write_text(json.dumps(record, indent=2, ensure_ascii=False, allow_nan=False), encoding="utf-8")
    acceptance = record.get("acceptance", {})
    met = acceptance.get("target_met_in_capture")
    frames = acceptance.get("frames")
    print(f"[bench] {label}: status={status} frames={frames} target_met={met} -> {out_file}", flush=True)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, default=DEFAULT_INSTANCE)
    parser.add_argument("--launcher", type=Path, default=DEFAULT_LAUNCHER)
    parser.add_argument("--out", type=Path, required=True, help="docs/measurements directory")
    parser.add_argument("--scratch", type=Path, required=True, help="working dir for launcher/log copies")
    parser.add_argument("--revision", default="cf3f5f97-abi2")
    parser.add_argument("--variants", nargs="*", default=BASELINE,
                        help="variant labels, or 'baseline' / 'ser_ab' / 'denoiser_ab' / 'rr_preset_ab' / 'all'")
    parser.add_argument("--timeout", type=float, default=480)
    parser.add_argument("--render-distance", type=int, default=16,
                        help="controlled render distance for a stable scene that fits the native budget (RD32 needs BLAS compaction)")
    parser.add_argument("--hold-focus", action="store_true",
                        help="keep the Minecraft window focused during each capture so the strict FOCUSED gate survives "
                             "a desktop that would steal focus (Windows only; the bench already refuses to run beside another game)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    labels: list[str] = []
    for name in args.variants:
        if name == "baseline":
            labels += BASELINE
        elif name == "ser_ab":
            labels += SER_AB
        elif name == "denoiser_ab":
            labels += DENOISER_AB
        elif name == "rr_preset_ab":
            labels += RR_PRESET_AB
        elif name == "all":
            labels += list(VARIANTS)
        elif name in VARIANTS:
            labels.append(name)
        else:
            parser.error(f"unknown variant {name}; known: {sorted(VARIANTS)}")
    labels = list(dict.fromkeys(labels))

    if args.dry_run:
        for label in labels:
            print(label, json.dumps(VARIANTS[label]))
        return 0
    if game_running():
        print("[bench] javaw.exe is running; close the game first", file=sys.stderr)
        return 2
    if not args.launcher.is_file():
        print(f"[bench] launcher not found: {args.launcher}", file=sys.stderr)
        return 2
    args.scratch.mkdir(parents=True, exist_ok=True)
    backup = set_window(args.instance, 1920, 1080, args.render_distance)
    (args.scratch / "window-backup.json").write_text(json.dumps(backup), encoding="utf-8")
    summary = []
    try:
        for label in labels:
            if not wait_game_exit():
                print("[bench] previous game did not exit; aborting", file=sys.stderr)
                break
            summary.append(run_variant(label, VARIANTS[label], args.instance, args.launcher,
                                       args.out, args.scratch, args.revision, args.timeout,
                                       args.render_distance, args.hold_focus))
    finally:
        stop_launcher()
        restore_files(args.instance, backup)
    index = args.out / "index.json"
    existing_doc: object = {}
    if index.is_file():
        try:
            existing_doc = json.loads(index.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            existing_doc = {}
    index.write_text(json.dumps(merge_index(existing_doc, summary), indent=2, ensure_ascii=False),
                     encoding="utf-8")
    return 0 if all(s["capture_status"] == "saved" for s in summary) else 1


if __name__ == "__main__":
    sys.exit(main())