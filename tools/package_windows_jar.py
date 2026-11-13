#!/usr/bin/env python3
"""Build a Bedray Windows player JAR from a matching combined JAR and pinned runtime inputs."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile


RUNTIME = "META-INF/natives/windows-x64/"
LICENSES = "META-INF/licenses/natives/"
STREAMLINE = (
    "sl.interposer.dll", "sl.common.dll", "sl.dlss.dll", "sl.dlss_d.dll", "sl.dlss_g.dll",
    "sl.reflex.dll", "sl.pcl.dll", "nvngx_dlss.dll", "nvngx_dlssd.dll", "nvngx_dlssg.dll", "NvLowLatencyVk.dll",
)
FSR24_DLLS = {
    "amd_fidelityfx_loader_vk.dll": "38a56a01fb3565c0757d9f5c1e4e69de4b65e5f2e5769b323b200dc1f142bab9",
    "amd_fidelityfx_upscaler_vk.dll": "4a7e895af6e0b615d40a84d59eb8c2b1054ad91cdd0c739567940270d51bdab7",
}
FSR24_LICENSE = "32357259b965d6a8001e9e508ba60eda6034027c5923a7d226ffbaff813c2bfb"

SDK_DIGESTS = {
    "fsr": "a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78",
    "xess": "88b8a373f30e33f3558a77a93e634f11b8132fc3047ea1a8edeead32b8471990",
    "streamline": "32041e51db0cab709a271b72a352281b690c2abbd669a827809401a5dd23956b",
    "dlss5": "2bc19bc09a7b89dd44f73e2ac0108fcc586e2154bdd0830f242f4f040e28789f",
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_file(path, expected):
    with path.open("rb") as file:
        actual = hashlib.file_digest(file, "sha256").hexdigest()
    require(actual == expected, f"Unexpected SHA-256 for {path.name}: {actual}")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def require_dll(path):
    with path.open("rb") as file:
        require(file.read(2) == b"MZ", f"{path.name} is not a PE image")
        file.seek(60)
        file.seek(struct.unpack("<I", file.read(4))[0])
        signature, machine, _, _, _, _, _, flags = struct.unpack("<4sHHIIIHH", file.read(24))
        require(signature == b"PE\0\0" and machine == 0x8664 and flags & 0x2000
                and file.read(2) == b"\x0b\x02", f"{path.name} is not a Windows x64 DLL")


def zip_member(archive, name, target):
    with archive.open(name) as source, target.open("wb") as dest:
        shutil.copyfileobj(source, dest)


def stage(args, directory):
    dlls = {}
    notices = {}

    def add_dll(archive, member, target, expected=None):
        file = directory / target
        file.parent.mkdir(parents=True, exist_ok=True)
        zip_member(archive, member, file)
        if expected:
            verify_file(file, expected)
        require_dll(file)
        dlls[target] = file

    with ZipFile(args.core) as core:
        meta = json.loads(core.read("manifest.json"))
        require(meta["revision"] == args.native_revision and meta["abi_version"] == 2
                and meta["native_contracts"] == "passed", "Renderer core revision, ABI or contracts mismatch")
        add_dll(core, "bedray_core.dll", "bedray_core.dll", meta["sha256"])

    with ZipFile(args.wrappers) as wrappers:
        meta = json.loads(wrappers.read("manifest.json"))
        require(meta["revision"] == args.native_revision, "Wrapper build revision mismatch")
        for name in ("NRD.dll", "vkb_nrd.dll", "vkb_dlss.dll", "vkb_sl.dll"):
            target = f"streamline/{name}" if name == "vkb_sl.dll" else name
            add_dll(wrappers, name, target, meta["sha256"][name])
        for name in ("NRD-LICENSE.txt", "DLSS-LICENSE.txt"):
            data = wrappers.read(name)
            require(digest(data) == meta["sha256"][name], f"{name} checksum mismatch")
            notices[f"NVIDIA/{name}"] = data

    with ZipFile(args.fsr) as fsr:
        add_dll(fsr, "PrebuiltSignedDLL/amd_fidelityfx_vk.dll", "amd_fidelityfx_vk.dll", SDK_DIGESTS["fsr"])
        notices["amd_fidelityfx_vk.dll/LICENSE.txt"] = fsr.read("sdk/LICENSE.txt")
        require(digest(notices["amd_fidelityfx_vk.dll/LICENSE.txt"])
                == "c93d509cd69d50698796c9c3274247352736ef122a3197ace2010f0978e21aa6", "Unexpected AMD SDK license")

    if args.fsr24 is not None:
        license_file = args.fsr24 / "license.md"
        verify_file(license_file, FSR24_LICENSE)
        license_text = license_file.read_bytes()
        for name, expected in FSR24_DLLS.items():
            file = args.fsr24 / name
            verify_file(file, expected)
            require_dll(file)
            dlls[name] = file
            notices[f"{name}/LICENSE.txt"] = license_text

    verify_file(args.xess, SDK_DIGESTS["xess"])
    with ZipFile(args.xess) as xess:
        add_dll(xess, "bin/libxess.dll", "libxess.dll",
                "251659dd84a3e84de67c886a4186e01f3eca49b00641906fe38bb6b807e5d5b7")
        for name in ("LICENSE.txt", "third-party-programs.txt"):
            notices[f"libxess.dll/{name}"] = xess.read(name)

    verify_file(args.streamline, SDK_DIGESTS["streamline"])
    with ZipFile(args.streamline) as streamline:
        for name in STREAMLINE:
            add_dll(streamline, f"bin/x64/{name}", f"streamline/{name}")
        for name in ("nvngx_dlss.dll", "nvngx_dlssd.dll"):
            dlls[name] = dlls[f"streamline/{name}"]
        for member, name in (("license.txt", "Streamline-LICENSE.txt"), ("3rd-party-licenses.md", "Streamline-THIRD-PARTY.md"),
                             ("bin/x64/reflex.license.txt", "Reflex-LICENSE.txt"),
                             ("bin/x64/nvngx_dlss.license.txt", "Streamline-NGX-LICENSE.txt")):
            notices[f"NVIDIA/{name}"] = streamline.read(member)

    verify_file(args.dlss5, SDK_DIGESTS["dlss5"])
    require_dll(args.dlss5)
    dlls["nvngx_dlssnr.dll"] = args.dlss5
    data = args.dlss5_license.read_bytes()
    require(digest(data) == "3027f23ca5a46dd9cb8183fbd522983a86f64d7daac5982912bf9f214671f294",
            "Unexpected NVIDIA DLSS 5 SDK license")
    notices["NVIDIA/DLSS-NR-LICENSE.txt"] = data
    return dlls, notices


def package(args, dlls, notices):
    output = args.output.resolve()
    require(not output.exists(), f"Output already exists: {output.name}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix=output.name + ".", suffix=".tmp", delete=False) as file:
        temporary = Path(file.name)
    checksums = {}
    for name, file in dlls.items():
        with file.open("rb") as source:
            checksums[name] = hashlib.file_digest(source, "sha256").hexdigest()

    try:
        write_jar(args, dlls, notices, checksums, temporary)
        temporary.rename(output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"{output} ({output.stat().st_size:,} bytes, {len(dlls)} DLL entries)")
    with output.open("rb") as file:
        print(hashlib.file_digest(file, "sha256").hexdigest())


def write_jar(args, dlls, notices, checksums, temporary):
    with ZipFile(args.jar) as base:
        manifest = base.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").splitlines()
        require(f"Git-Commit: {args.java_revision}" in manifest and "Git-Dirty: false" in manifest,
                "The combined JAR was built from a different revision or dirty checkout")
        members = set(base.namelist())
        require(any(name.endswith("/NativeBundle.class") for name in members)
                and not any(name.startswith(RUNTIME) for name in members), "Expected an unbundled Bedray combined JAR")
        require(any(name.endswith(".jar") and name.startswith(("META-INF/jars/", "META-INF/jarjar/")) for name in members),
                "The combined JAR is missing its Compute provider")

        with ZipFile(temporary, "w", compression=ZIP_DEFLATED, compresslevel=4) as jar:
            for member in base.infolist():
                with base.open(member) as source, jar.open(member, "w") as target:
                    shutil.copyfileobj(source, target)
            for name, file in sorted(dlls.items()):
                jar.write(file, RUNTIME + name)
            index = "schema=1\n" + "".join(f"{name}={sha}\n" for name, sha in sorted(checksums.items())
                                           if name not in ("amd_fidelityfx_vk.dll", "libxess.dll"))
            jar.writestr(RUNTIME + "bundle.properties", index)
            jar.writestr(RUNTIME + "provenance.json", json.dumps({"java_revision": args.java_revision,
                "native_revision": args.native_revision, "sha256": checksums}, indent=2) + "\n")
            for name, data in sorted(notices.items()):
                jar.writestr(LICENSES + name, data)
            jar.writestr(LICENSES + "NVIDIA/NOTICE.txt", "This software contains source code provided by NVIDIA Corporation.\n"
                "The bundled NVIDIA components are subject to their accompanying SDK license terms.\n"
                f"Bedray source: https://github.com/bnfe12/bedray/tree/{args.java_revision}\n")
    with ZipFile(temporary) as jar:
        require(len(set(jar.namelist())) == len(jar.namelist()), "Duplicate JAR entries")
        for name, sha in checksums.items():
            with jar.open(RUNTIME + name) as stream:
                require(hashlib.file_digest(stream, "sha256").hexdigest() == sha, f"Bundled {name} hash mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("jar", "output", "core", "wrappers", "fsr", "xess", "streamline", "dlss5", "dlss5-license"):
        parser.add_argument(f"--{name}", required=True, type=Path)
    parser.add_argument("--fsr24", type=Path,
                        help="directory with both signed SDK 2.4 Vulkan DLLs and Kits/FidelityFX/docs/license.md")
    parser.add_argument("--native-revision", required=True)
    parser.add_argument("--java-revision", required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="bedray-runtime-") as temp:
        package(args, *stage(args, Path(temp)))


if __name__ == "__main__":
    main()
