# DLSS resource ownership

SR feature residency is independent of READY settings, NGX initialization, RR and FG.
At frame head, after temporal runtime setup and before world recording, the same
resolution-plan function used by world rendering decides whether RR produces final
output. In that route, a resident SR feature is retired after its host completion
proof. READY, effective SDK quality, queried input/output size and initialization
epoch remain unchanged. Returning to SR lazily creates a feature and explicitly
resets its history. No feature means no recurring fence or release.

`DlssFeatureOwner` owns successful and partially created features. A retirement
attempt makes the owner release-only before proving completion. Proof or SDK
failure retains that owner, blocks evaluation and permits retry. Reconfiguration
cannot overwrite an occupied retirement slot. Java FFM reads creation output on
failure as well as success.

Direct NGX registers and publishes the opaque owner before allocating SDK
parameters or recording feature creation. Cleanup acknowledges `ReleaseFeature`
before clearing the SDK handle, then acknowledges `DestroyParameters` before
clearing parameters. It removes the registry entry last. Failed parameter cleanup
therefore cannot trigger a second SDK handle release. Capability refresh and
checked shutdown obey the same rule. Shutdown failure retains device/path owners
and refuses new work until retry succeeds.

Streamline tracks SR and RR separately. SetOptions creates a pseudo owner;
`resourcesMayExist` is marked before an evaluate attempt, including failures.
Never-evaluated owners require no `slFreeResources` call. Evaluated owners require
SDK acknowledgement before state is cleared. SR release targets DLSS/viewport0;
RR release targets RR/viewport0. FG/viewport1 and common camera constants are not
released by SR suspension. Session shutdown first checks wrapper cleanup and
only clears initialized state after `slShutdown` succeeds.

RR quality/extent changes and destruction check release status before freeing
guide resources. Native DLAA creation failure preserves its partial output,
discards the entire command buffer, retires that owner in the next preflight,
then attempts QUALITY in a fresh recording. It never retries over a potentially
partially recorded creation in the same command buffer.

The additive `vkb_dlss_resource_contract_version()==1` and
`vkb_dlss_shutdown_checked()` exports preserve existing POD/API layouts. An old
wrapper remains loadable; feature-only SR suspension requires the new contract.
Deploy matching new JAR and wrapper DLLs for the repaired native release paths.
The compatibility void shutdown delegates to checked cleanup and retains failure
diagnostics instead of forgetting resources.

## Verification

Eight host owner tests cover partial creation, failed proof, failed SDK release,
retry, transfer, empty suspension, invalid success and independent owners. Existing
world/FG integration checks retain the completion-before-destruction invariant.

Windows CI builds production `vkb_dlss.dll` against the actual NGX static library
and `vkb_sl.dll` against real Streamline/Vulkan headers. Separate fault DLLs compile
the same production entrypoints with only the SDK boundary substituted; their
contract executables cover SR/RR lifecycle, partial allocations, release-only
rejection, parameter cleanup, capabilities, shutdown and SR/RR isolation. Fault
fixtures are test-only artifacts. `DlssResourceContractProbe` exercises the actual
Java FFM bindings against both fixtures without a GPU.

SDK inputs are immutable revisions outside the checkout:

- [DLSS v310.9.16](https://github.com/NVIDIA/DLSS/tree/9981c6350dc58c0ce21f024fd65e31e851c31689).
- [Streamline v2.14.1](https://github.com/NVIDIA-RTX/Streamline/tree/2122257e0fce486f91b385aa63b9a09b0a34b363).
- Vulkan-Headers `2cd90f9d20df57eac214c148f3aed885372ddcfe`.

No SDK binaries or generated wrapper binaries are committed. CI manifests identify
source revisions and binary hashes. These CPU checks do not measure vendor GPU
allocation size, frame rate or visual stability. An RTX live RR↔SR/resize/toggle
run remains necessary for those claims. Logs identify stage, opaque handle,
generation/frame and retained ownership on failure.
