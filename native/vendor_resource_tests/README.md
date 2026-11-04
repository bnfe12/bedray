# Actual vendor-wrapper resource contracts

This directory builds the unchanged production wrapper source twice: a real SDK
DLL build, and a fault DLL whose SDK boundary is substituted. The tests call the
actual exported `vkb_dlss_*` and `vkb_dlss_rr_*` entrypoints. NVIDIA headers and
inline creation/evaluation helpers remain real; no helper reimplementation stands
in for the wrapper lifecycle.

* `vendor_ngx_production`: real NGX SDK headers and `nvsdk_ngx_s.lib`, static CRT.
* `vendor_sl_production`: real Streamline headers, dynamically loaded SL exports.
* `vendor_ngx_fault`: same `vkb_dlss.cpp`, SDK symbols supplied by `ngx_fake.cpp`.
* `vendor_sl_fault`: same `vkb_sl_dlss.cpp`, SL boundary pointers supplied by
  `sl_fake.cpp`.

The 20 NGX and 14 Streamline cases exercise SR/RR isolation, failed creation with
partial ownership, release-only evaluation rejection, staged handle/parameter
cleanup, failed cleanup retry, checked shutdown, capabilities refresh, and SL
options-only/tag-failure/partially evaluated resource ownership. Counters reject
double release, premature shutdown, and access to a released SDK object. These
are CPU contracts; they do not validate GPU rendering or SDK-driver behavior.

## Windows MSVC invocation

Run `.github/scripts/vendor-resources-ci.ps1` with `SourceRoot`, Vulkan-Headers
`Headers`, `BuildRoot`, and a `DependencyRoot` outside the source checkout. The
script sparse-checks out these official immutable SDK revisions:

* NVIDIA/DLSS `9981c6350dc58c0ce21f024fd65e31e851c31689` (`v310.9.16`).
* NVIDIA-RTX/Streamline `2122257e0fce486f91b385aa63b9a09b0a34b363` (`v2.14.1`).

After both real DLL builds and both CTest contracts pass, `BuildRoot/runtime`
contains the two production DLLs, two clearly named fault DLLs, and a SHA256
manifest. SDK headers/libraries/runtime binaries are neither committed nor published; required license notices accompany our wrappers. The fault DLLs are only
for CPU/Java FFM probes; never deploy them into a game.

`fault_control.h` documents the additional test exports. Java FFM probes can call
`vendor_test_fail(operation, count, partial)` before the real C ABI call. Call
`vkb_dlss_shutdown_checked()` successfully before `vendor_test_reset()`; reset
changes injection/counters and deliberately refuses to erase live SDK ownership.

## Continuous integration

The standalone `vendor-resources` job in `native-windows.yml` builds these
wrappers and runs both contract executables. It then compiles the actual Java
FFM binding subset with pinned Temurin 25 and invokes all four production/fault
DLL probes through `dlss-ffm-ci.ps1`. This avoids Minecraft/launcher dependencies
while exercising the production Java marshalling and C entrypoints.

The evidence artifact contains command logs, CTest XML and FFM output even on
failure. The wrapper artifact contains hashes, SDK license notices and installation
instructions. Only `vkb_dlss.dll` and `vkb_sl.dll` belong in the game.
