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
manifest. SDK files are neither committed nor published. The fault DLLs are only
for CPU/Java FFM probes; never deploy them into a game.

`fault_control.h` documents the additional test exports. Java FFM probes can call
`vendor_test_fail(operation, count, partial)` before the real C ABI call. Call
`vkb_dlss_shutdown_checked()` successfully before `vendor_test_reset()`; reset
changes injection/counters and deliberately refuses to erase live SDK ownership.

## Candidate-only workflow step

Use the existing pinned Vulkan-Headers checkout. SDK checkouts and build evidence
stay outside the source checkout. Add these steps to the candidate matrix route:

```yaml
- name: Build vendor wrappers and fault contracts
  if: matrix.revision == 'candidate'
  shell: pwsh
  run: |
    ./driver/.github/scripts/vendor-resources-ci.ps1 -SourceRoot source -Headers deps/Vulkan-Headers/include -BuildRoot evidence/vendor -DependencyRoot "${{ runner.temp }}/bedray-vendor-sdk"
- name: Publish tested vendor wrappers and fault DLLs
  if: success() && matrix.revision == 'candidate'
  uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02
  with:
    name: vendor-wrappers-${{ github.sha }}
    if-no-files-found: error
    retention-days: 14
    path: evidence/vendor/runtime/*
```

The existing text-evidence artifact glob captures `commands.log`, `ctest.xml`,
and CMake configuration diagnostics in `evidence/vendor` even on failure.
