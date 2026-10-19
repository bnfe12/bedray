# Release checklist: 0.1.0-alpha.1

This evidence log was updated on 2026-10-20. Checks describe only the evidence named beside them. This release is an ALPHA; a successful build and automated tests do not establish live-game stability.
Update this file for every release. Publishing is being done at the owner's request as an ALPHA pre-release.

## Build and metadata

- [x] Clean public checkout run: `clean :core:test :common:test :common:runShimTests :neoforge:jar :fabric:jar` succeeds; the release notes name the exact source commit
- [x] Unit tests: 462 passed, 0 failed, 1 optional shader-pack test skipped; shim tests: 33 passed, 0 failed
- [x] Jar names: `bedray-neoforge-mc26.3.0-0.1.0-alpha.1.jar`, `bedray-fabric-mc26.3-0.1.0-alpha.1.jar`
- [x] Exact licensed runtime set only: `amd_fidelityfx_vk.dll` and `libxess.dll`, with AMD `LICENSE.txt`, Intel `LICENSE.txt`
      and `third-party-programs.txt`; no other native binaries, SDKs, NVIDIA files or wrappers
- [x] `neoforge.mods.toml`: mod id `bedray`, version `0.1.0-alpha.1`, mixin config `mixins.bedray.json` present in the jar
- [x] `fabric.mod.json`: id `bedray`, depends on fabricloader >= 0.19.5, minecraft ~26.3, java >= 25, sodium >= 0.9; mixin config present
- [x] Both loaders: the language file `assets/bedray/lang/en_us.json` is in the jar
- [x] Both release JARs have `Git-Dirty: false`, valid loader metadata, zero test classes, and pass the JAR inspector
- [x] Both JAR SHA-256 values are listed in the GitHub release description
- [x] `tools/public_scan.ps1` passes on the public repository (684 allowlisted tracked files): `https://github.com/bnfe12/bedray`
- [x] Release docs describe the current bundle: `docs/FEATURE_MATRIX.md`, `docs/KNOWN_ISSUES.md`, `CHANGELOG.md`

## Smoke tests in the game (final jars, clean instances)

Log evidence: the latest Testbed run reaches `world_ready`, but FG was off and FSR Performance was active; RR feature handles were created, but no successful RR output is logged. An earlier Testbed log records generated frames with status `0x0`; it predates the final clean release build. Neither log proves current-artifact DLSS SR or RR success.

- [ ] NeoForge: new empty instance (Minecraft 26.3, Sodium 0.9.2, Bedray only): starts, Vulkan backend in F3,
      "Bedray ALPHA" pages appear, "Bedray (alpha)" block in F3
- [ ] NeoForge: Ray Tracing Low and Path Tracing Fast each render a world; screenshots opened and described
- [ ] NeoForge: built-in upscalers (Bilinear, FSR 1, NIS) switch live without a flipped or offset image
- [ ] NeoForge: `latest.log` of the smoke run has no `ERROR` from Bedray
- [ ] NeoForge: 30-minute session in a world without a crash
- [ ] Fabric: the same smoke on a new empty Fabric instance (startup, pages, Ray Tracing Low, upscaler switch)
- [ ] Bundled AMD FSR and Intel XeSS extraction/load lines in the log; DLSS, NRD and Streamline outcomes recorded separately
- [ ] Clean install: delete `config/bedray.toml` and `bedray/` and start: defaults are created, no errors
- [ ] Migration: place an old `config/vulkan_rtx.toml`, start without `bedray.toml`: the log line "settings migrated" appears
- [ ] Owner check (L5): the owner looks at the final build and accepts it

## Publishing (owner)

- [ ] GitHub pre-release `0.1.0-alpha.1`, title with "ALPHA"
- [ ] Attach the two loader jars only (NeoForge and Fabric); both contain only the reviewed AMD and Intel runtime DLLs and notices
- [ ] Release description: ALPHA warning, requirements, SHA-256 of both jars, known issues, link to `docs/INSTALL.md`,
      "tested only on RTX 5060 Ti", call for AMD / Intel testers
- [ ] Issue forms work (Bug report, GPU test report); `.github/ISSUE_TEMPLATE/config.yml` points to `bnfe12/bedray`; change it if the public repository has another name
- [ ] Screenshots from `docs/images/` added to the README (list in the private notes)
