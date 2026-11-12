#!/usr/bin/env python3
"""Reproducible Sodium compatibility check for Bedray (NeoForge 26.3 line).

For every Sodium version locked in tools/sodium-matrix.json, verifies that the
classes/fields/methods Bedray hooks (Config API entry point, mixin targets and
internal classes used directly) exist with the exact descriptors recorded in
tools/sodium-compat-contract.json. Mixin targets are strings at compile time,
so compilation alone cannot prove they still resolve; this check can.

Usage:
  python tools/sodium_compat_check.py --jars-dir DIR      check official outer jars in DIR
  python tools/sodium_compat_check.py --generate-contract REF_JAR
                                                          rebuild the contract from a reference jar (0.9.2)
  python tools/sodium_compat_check.py --download DIR      optional fetch, cdn.modrinth.com only

Provenance: each jar's sha512 must equal the lock entry before inspection. The
lock was fetched from the official https://api.modrinth.com/v2/project/sodium/version
endpoint; --download refuses any host other than cdn.modrinth.com. NeoForge jars
nest their classes in a *-mod.jar entry, extracted transparently.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parent.parent
LOCK = ROOT / "tools" / "sodium-matrix.json"
CONTRACT = ROOT / "tools" / "sodium-compat-contract.json"

# Binary class name -> [(kind, name, descriptor or None to resolve from the reference jar)].
# None descriptors are resolved uniquely by --generate-contract; ambiguous names fail generation.
SPEC = {
    "net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint": [
        ("method", "registerConfigLate", "(Lnet/caffeinemc/mods/sodium/api/config/structure/ConfigBuilder;)V"),
    ],
    "net.caffeinemc.mods.sodium.client.config.ConfigManager": [
        ("field", "CONFIG", None),
    ],
    "net.caffeinemc.mods.sodium.client.config.ConfigManager$ModMetadata": [
        ("method", "<init>", "(Ljava/lang/String;Ljava/lang/String;)V"),
    ],
    "net.caffeinemc.mods.sodium.client.config.structure.ModOptions": [
        ("method", "configId", "()Ljava/lang/String;"),
        ("method", "name", "()Ljava/lang/String;"),
        ("method", "version", "()Ljava/lang/String;"),
    ],
    "net.caffeinemc.mods.sodium.client.config.builder.ConfigBuilderImpl": [
        ("method", "<init>", "(Ljava/util/function/Function;Ljava/lang/String;)V"),
        ("method", "build", "()Ljava/util/Collection;"),
    ],
    "net.caffeinemc.mods.sodium.client.config.structure.Config": [
        ("method", "<init>", "(Ljava/util/List;)V"),
        ("method", "getOption", "(Lnet/minecraft/resources/Identifier;)Lnet/caffeinemc/mods/sodium/client/config/structure/Option;"),
        ("method", "getModOptions", "()Ljava/util/List;"),
        ("method", "applyAllOptions", "()V"),
        ("method", "resetAllOptionsFromBindings", "()V"),
    ],
    "net.caffeinemc.mods.sodium.client.config.structure.Option": [
        ("field", "id", None),
        ("method", "isEnabled", "()Z"),
        ("method", "resetToDefault", "()V"),
    ],
    "net.caffeinemc.mods.sodium.client.config.structure.OptionGroup": [
        ("method", "name", "()Lnet/minecraft/network/chat/Component;"),
        ("method", "options", "()Ljava/util/List;"),
    ],
    "net.caffeinemc.mods.sodium.client.config.structure.EnumOption": [
        ("method", "getElementName", "(Ljava/lang/Enum;)Lnet/minecraft/network/chat/Component;"),
    ],
    "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen": [
        ("field", "prevScreen", None),
        ("field", "optionList", None),
        ("method", "updateControls", None),
        ("method", "mouseClicked", None),
        ("method", "keyPressed", None),
        ("method", "createScreen", "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/gui/screens/Screen;"),
    ],
    "net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget": [
        ("method", "rebuild", None),
        ("method", "renderAllPages", None),
        ("method", "extractRenderState", None),
    ],
    "net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget$HeaderWidget": [
        ("field", "title", None),
        ("method", "mouseClicked", None),
        ("method", "extractRenderState", None),
    ],
    "net.caffeinemc.mods.sodium.client.gui.widgets.ScrollableTooltip": [
        ("field", "hoveredElement", None),
        ("field", "tooltipArea", None),
        ("field", "font", None),
        ("field", "reservedArea", None),
        ("field", "contentSize", None),
        ("field", "visibleDim", None),
        ("field", "overlayMode", None),
        ("method", "positionTooltip", None),
        ("method", "<init>", "(Lnet/caffeinemc/mods/sodium/client/gui/widgets/ScrollableTooltip$TooltipParent;)V"),
        ("method", "render", None),
        ("method", "mouseScrolled", None),
    ],
    "net.caffeinemc.mods.sodium.client.gui.widgets.AbstractWidget": [
        ("method", "playClickSound", "()V"),
    ],
    "net.caffeinemc.mods.sodium.client.gui.options.control.AbstractScrollable": [
        ("field", "scrollbar", None),
    ],
    "net.caffeinemc.mods.sodium.client.gui.options.control.CyclingControl$CyclingControlElement": [
        ("field", "option", None),
        ("method", "extractRenderState", None),
    ],
    "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer": [
        ("method", "instanceNullable", None),
        ("method", "scheduleRebuildForChunks", None),
    ],
    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection": [
        ("method", "delete", "()V"),
        ("method", "getChunkX", "()I"),
        ("method", "getChunkY", "()I"),
        ("method", "getChunkZ", "()I"),
    ],
    "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput": [
        ("method", "destroy", "()V"),
    ],
    "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask": [
        ("method", "execute",
         "(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
         "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
         "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;"),
    ],
    "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager": [
        ("method", "uploadResults", "(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V"),
    ],
    "net.caffeinemc.mods.sodium.neoforge.config.ConfigLoaderForge": [],
}


def classes_jar(path: Path, tmp: Path) -> Path:
    with zipfile.ZipFile(path) as z:
        inner = [n for n in z.namelist() if n.endswith("-mod.jar")]
        if inner:
            out = tmp / "classes.jar"
            out.write_bytes(z.read(inner[0]))
            return out
    return path


def javap_members(jar: Path, classes):
    proc = subprocess.run(["javap", "-p", "-s", "-classpath", str(jar), *classes],
                          capture_output=True, text=True)
    members = {}
    cur = None
    pending = None
    for line in proc.stdout.splitlines():
        header = re.match(r"^(?:public\s+|final\s+|abstract\s+|static\s+)*(?:class|interface|enum|record)\s+([\w.$]+)", line)
        if header and line.rstrip().endswith("{"):
            cur = header.group(1)
            members.setdefault(cur, {})
            pending = None
            continue
        desc = re.match(r"^\s*descriptor:\s*(\S+)", line)
        if desc and pending is not None and cur is not None:
            members[cur].setdefault(pending, set()).add(desc.group(1))
            pending = None
            continue
        sig = re.match(r"^\s+(.*?);\s*$", line)
        if sig and cur is not None:
            text = sig.group(1)
            if "(" in text:
                name = text.split("(")[0].split()[-1]
                if name in (cur, cur.split(".")[-1].split("$")[-1]):
                    name = "<init>"
                pending = ("method", name)
            else:
                pending = ("field", text.split()[-1])
    missing_classes = [c for c in classes if c not in members]
    return members, missing_classes, proc.stderr


def sha512(path: Path) -> str:
    return hashlib.sha512(path.read_bytes()).hexdigest()


def jar_name(entry) -> str:
    return unquote(urlparse(entry["url"]).path.rsplit("/", 1)[-1])


def generate(reference: Path):
    with tempfile.TemporaryDirectory(prefix="sodium-compat-") as td:
        jar = classes_jar(reference, Path(td))
        members, missing, stderr = javap_members(jar, list(SPEC))
        if missing:
            sys.exit(f"reference jar lacks classes: {missing}\n{stderr}")
        contract = []
        errors = []
        for cls, entries in SPEC.items():
            for kind, name, desc in entries:
                found = members[cls].get((kind, name), set())
                if desc is None:
                    if len(found) != 1:
                        errors.append(f"ambiguous or absent {cls}.{name} ({kind}): {sorted(found) or 'MISSING'}")
                        continue
                    desc = next(iter(found))
                elif desc not in found:
                    errors.append(f"reference {cls}.{name} ({kind}) descriptor {desc} not in {sorted(found)}")
                    continue
                contract.append({"class": cls, "kind": kind, "name": name, "descriptor": desc})
            if not entries:
                contract.append({"class": cls, "kind": "class", "name": "*", "descriptor": "*"})
        if errors:
            sys.exit("\n".join(errors))
    CONTRACT.write_text(json.dumps(contract, indent=2) + "\n", encoding="utf-8")
    print(f"contract written: {len(contract)} members over {len(SPEC)} classes -> {CONTRACT}")
    for c in contract:
        if c["name"] in ("<init>", "extractRenderState", "renderAllPages", "mouseClicked", "render", "createScreen", "scheduleRebuildForChunks"):
            print(f'  {c["class"].split(".")[-1]}.{c["name"]} {c["descriptor"]}')


def check(jars_dir: Path):
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    failures = 0
    for entry in lock:
        name = jar_name(entry)
        jar = jars_dir / name
        label = entry["version"]
        if not jar.is_file():
            print(f"{label}: MISSING-JAR {name}")
            failures += 1
            continue
        if sha512(jar) != entry["sha512"]:
            print(f"{label}: PROVENANCE-FAIL sha512 mismatch for {name}")
            failures += 1
            continue
        with tempfile.TemporaryDirectory(prefix="sodium-compat-") as td:
            cj = classes_jar(jar, Path(td))
            classes = sorted({c["class"] for c in contract})
            members, missing_classes, _ = javap_members(cj, classes)
            problems = [f"class missing: {c}" for c in missing_classes]
            for c in contract:
                if c["kind"] == "class" or c["class"] in missing_classes:
                    continue
                descs = members[c["class"]].get((c["kind"], c["name"]), set())
                if c["descriptor"] not in descs:
                    problems.append(f'{c["kind"]} {c["class"]}.{c["name"]} {c["descriptor"]} absent (have: {sorted(descs) or "nothing"})')
        if problems:
            failures += 1
            print(f"{label}: FAIL ({len(problems)} problems)")
            for p in problems:
                print(f"    {p}")
        else:
            print(f"{label}: OK ({len(classes)} classes, {len(contract)} members, sha512 verified)")
    print("RESULT:", "ALL-VERSIONS-COMPATIBLE" if failures == 0 else f"{failures} version(s) failed")
    return 1 if failures else 0


def download(dest: Path):
    import urllib.request
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    dest.mkdir(parents=True, exist_ok=True)
    for entry in lock:
        host = urlparse(entry["url"]).netloc
        if host != "cdn.modrinth.com":
            sys.exit(f"refusing non-official host {host} for {entry['version']}")
        target = dest / jar_name(entry)
        if target.is_file() and sha512(target) == entry["sha512"]:
            print(f"{entry['version']}: already present, sha512 verified")
            continue
        print(f"{entry['version']}: downloading from {entry['url']}")
        urllib.request.urlretrieve(entry["url"], target)
        if sha512(target) != entry["sha512"]:
            sys.exit(f"sha512 mismatch after download: {target}")
    print("download complete, all sha512 verified")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jars-dir", type=Path)
    ap.add_argument("--generate-contract", type=Path, metavar="REF_JAR")
    ap.add_argument("--download", type=Path, metavar="DIR")
    args = ap.parse_args()
    if args.generate_contract:
        generate(args.generate_contract)
        return 0
    if args.download:
        download(args.download)
        return 0
    if not args.jars_dir:
        ap.error("--jars-dir is required (or --generate-contract / --download)")
    return check(args.jars_dir)


if __name__ == "__main__":
    sys.exit(main())