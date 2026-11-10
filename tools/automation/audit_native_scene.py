"""Check the two Alpha 2 player distributions; does not launch Minecraft."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
from pathlib import Path
from zipfile import ZipFile

LEGACY = re.compile(
    r"LegacySceneLifetime|ReferencePathTraceBackend|RtEntityBlas|RtInstanceWriter|"
    r"OpacityMicromapCache|RtExtensionProbe|BedraySodiumNative|SodiumDequantize\.class|EmitterCaptureBuffers|"
    r"legacy_section_traversal|bedray_legacy|"
    r"/(?:path_trace(?:\.raygen|\.ser)?|restir_pt_spatial|volumetric_march|sodium_dequantize)\.(?:json|spv|slang)$"
)
REQUIRED = (
    "path_trace_scene", "path_display", "frame_stats",
    "restir_spatial", "restir_pt_spatial_scene", "volumetric_march_scene",
)
BRIDGES = ("NativeSceneBridge", "NativePathTraceBackend", "BedraySceneNative")
OLD_FLAGS = (b"vkbridge.rt.nativeScene", b"vkbridge.rt.nativePathTrace")


def sha(path: Path) -> str:
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def inspect_archive(archive: ZipFile, name: str) -> None:
    for entry in archive.namelist():
        if LEGACY.search(entry):
            raise ValueError(f"{name}: legacy entry {entry}")
        if entry.endswith(".class") and any(flag in archive.read(entry) for flag in OLD_FLAGS):
            raise ValueError(f"{name}: removed route property in {entry}")
        if entry.endswith(".jar"):
            with ZipFile(io.BytesIO(archive.read(entry))) as nested:
                inspect_archive(nested, f"{name}!{entry}")


def inspect_distribution(path: Path, loader: str) -> dict:
    jar = path / f"bedray-{loader}.jar"
    core = path / "bedray_core.dll"
    with ZipFile(jar) as archive:
        names = set(archive.namelist())
        inspect_archive(archive, jar.name)
        for program in REQUIRED:
            for folder, extension in (("manifest", "json"), ("spv", "spv")):
                if f"shaders/slang/{folder}/{program}.{extension}" not in names:
                    raise ValueError(f"{jar}: missing {program}.{extension}")
        for bridge in BRIDGES:
            if not any(name.endswith(f"/{bridge}.class") for name in names):
                raise ValueError(f"{jar}: missing {bridge}")
        descriptor = "fabric.mod.json" if loader == "fabric" else "META-INF/neoforge.mods.toml"
        if descriptor not in names:
            raise ValueError(f"{jar}: missing {descriptor}")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        manifest = manifest.replace("\r\n ", "").replace("\n ", "")
        identity = {k: v for k, v in (line.split(": ", 1) for line in manifest.splitlines() if ": " in line)}
    if (path / "bedray_scene.dll").exists():
        raise ValueError(f"{path}: standalone scene DLL must not ship")
    return {"jar_sha256": sha(jar), "core_sha256": sha(core),
            "commit": identity.get("Git-Commit"), "dirty": identity.get("Git-Dirty"),
            "source": identity.get("Build-Source"), "scene_programs": list(REQUIRED)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("distribution_root", type=Path)
    args = parser.parse_args()
    report = {loader: inspect_distribution(args.distribution_root / loader, loader)
              for loader in ("fabric", "neoforge")}
    if report["fabric"]["core_sha256"] != report["neoforge"]["core_sha256"]:
        raise ValueError("loaders did not ship the same native core")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
