# Typed world frames for Iris, Bedray and voxel rendering

Source-only milestone, originally reviewed against voxel branch `24d721a` and cache/DXGI branch `7a99370`
on 2026-10-23. The 2026-10-26 main integration now includes those histories and their later fixes. The original
baseline/patch discussion below is historical; do not reapply its patches to current main. No new runtime evidence is implied.

## One host timeline and actual producer

`rtx.frame.WorldFrame` is immutable CPU metadata with no Vulkan/Minecraft dependency. It describes
borrowed GENERAL-layout images on the existing host device/graphics queue. A consumer never frees
them, creates another device, or extends image lifetime by retaining metadata. All handles supplied
by a producer must belong to the frame's device and remain valid until host queued uses retire.

`WorldFrames.beginFrame(level)` runs once at the host frame boundary. Stamps contain a host world
epoch and frame number, independent of voxel snapshot/material/atlas revisions. A depth guide must
declare the same stamp, producer, extent and origin as color. Stale/foreign frame submissions and
duplicate publication are rejected. Current adapters retain native bottom-left image orientation.
Top-left images are explicitly unsupported by the post adapter until an orientation adapter exists.

The hardware RT renderer records whether it actually copied into the requested target, publishing
provenance only after its command buffer has been handed to the host encoder. A selected RT mode,
allocated output image or old depth view is not evidence of a current RT frame. A failed/waiting RT
pass leaves raster as the color producer. The voxel merge must likewise use the actual boolean
returned by `renderDiagnostic()`, which can be false while camera/atlas data is unavailable.

Current raster capture publishes reverse-Z depth. Native RT and voxel capture publish no depth guide;
their post color remains usable, and depth packs report that the producer supplies no matching guide.
RT's existing depth/denoiser getters and voxel DISTANCE debug colors are deliberately not borrowed
as matching guide data. Motion/object velocity, material, normal and temporal color images are absent.

## Current camera and projection

`WorldTargetManager.beginLevelProjectionFrame` clears stale pose state, matching the newer voxel
guard. The first projection-buffer call captures the current world projection before the hand.
Missing capture does not reuse a previous frame's bob/projection. Raster uses the exact projection;
RT/voxel use the projection their renderer actually selected, including the optional bob setting.

For base native projection P and captured full projection F, model-view is
`inverse(P) * F * viewRotation`. This puts bob/hurt/portal pose into model-view while exposing a
base GL projection. The double camera world origin is separate from camera-relative matrices.
All matrices are copied, finite and invertible; callers receive defensive copies.

Near/far are reconstructed from native reverse-Z endpoints. An infinite native far plane uses the
camera's finite depth range. For native normalized depths dn/df at those planes, the window-depth map
is `GL_depth = native_depth / (df-dn) - dn / (df-dn)`. The same map changes the projection's clip Z
row; XY/W and internal image rows remain unchanged. This avoids the former mismatch between a bobbed
depth image and an unbobbed inverse projection. Raster conversion itself needs no per-pixel inverse.

## Frame uniform layout

Binding 2 is one device-local uniform buffer, updated by `vkCmdUpdateBuffer` before draws, with
read/write queue barriers around the update. A mapped CPU ring cannot overwrite this frame data
while older frames read it. All passes see the same values; descriptor sets retain one stable buffer.
The existing scalar push block stays 32 bytes. Frame UBO std140 offsets are fixed:

| Name | Type | Offset |
| --- | --- | --- |
| gbufferProjection | mat4 | 0 |
| gbufferProjectionInverse | mat4 | 64 |
| gbufferModelView | mat4 | 128 |
| gbufferModelViewInverse | mat4 | 192 |
| gbufferPreviousProjection | mat4 | 256 |
| gbufferPreviousModelView | mat4 | 320 |
| cameraPosition | vec3 | 384 |
| previousCameraPosition | vec3 | 400 |

Range: 416 bytes. GPU vec3 positions use floats, while the CPU contract keeps doubles. The other Iris
world uniforms this host supports are in a separate world block at binding 18 (see IRIS_VULKAN.md); the rest
remain unsupported by name. Stage declarations may read different subsets at the
same fixed offsets; runtime uniform values do not belong in any shader compilation cache key.

Previous camera data is available only across consecutive frames with the same world/device/producer,
extent, format and origin. A translation over 64 blocks is treated as a camera cut. Only CPU camera
metadata is retained; no previous borrowed GPU handle survives in history. A pack declaring previous
fields waits one continuous frame after discontinuity instead of reading fabricated previous values.

## Guide encodings and retirement

| Encoding | Meaning | Post handling |
| --- | --- | --- |
| REVERSE_DEVICE_Z | native 1-near/0-far device depth | affine current-camera conversion to R32F GL depth |
| OPENGL_WINDOW_Z | 0-near/1-far normalized depth | borrowed directly, nearest sampling |
| LINEAR_VIEW_Z | positive forward view distance, -view.z | finite-perspective conversion to GL depth |
| RAY_DISTANCE | positive world-unit distance along the pixel ray | inverse projection and inverse model-view basis recover view distance |

Linear/ray guides require R32F, a canonical finite GL perspective and affine model-view; unsupported
projections/formats are refused. Native/window guides accept R32F or supported depth formats.
Ray distances must use the unjittered pixel-center rays described by this frame's camera; jittered
or differently sampled guides need an explicit sampling contract before they can use this encoding.
Conversion uses 128 push bytes, within Vulkan's guaranteed minimum. Conversion runs on the host
graphics queue only when it has compute capability. The typed seam enables future matching RT/voxel
guides, but does not claim that those producers currently supply them or their sampling/jitter contract.

Graph/device/extent/format/depth-view/encoding changes retire queued references before replacing
images, descriptors or pipelines. World transitions do likewise. Color-only packs bind an owned sampled
color image in the unused depth slot and require no depth allocation. Graphics-only Vulkan devices no
longer query acceleration-structure properties merely to allocate Iris images/buffers.

## New branch compatibility and evidence

Voxel API minor 3 adds borrowed atlas/material state but still exposes color only. The common frame
model lives in the host: compute algorithms continue using renderer-api and importing no Iris/private
host classes. The atlas/session may reset without a world transition; that does not create a new Iris
world epoch. Merge the existing frame/device/UI hooks once, preserving the successful producer result.
The [integration notes and hook patch](IRIS_VOXEL_MERGE.md) record a temporary combined Java compilation
against voxel `24d721a`; neither development branch was merged into the other.

The independent cache/DXGI branch is not merged here. Its shared graphics/compute pipeline-cache seam
can be reused on integration. Its SPIR-V disk cache is compute-only; vertex/fragment reuse needs stage
and compiler profile in the key. Review found three LWJGL 3.3.3 compile incompatibilities in its cache
class; [a source patch](patches/vulkan-cache-lwjgl333.patch) corrects size_t, UUID access and empty-cache
retry. Apply it against that branch's source when integrating; the patched class compiled separately.

Current evidence: common/Fabric/NeoForge Java compilation; color source example NORMAL/BRIGHT;
depth example DISTANCE/MOTION to SPIR-V; updated depth compute source to SPIR-V. No tests, GPU
dispatch, game sessions, product JAR or releases. These checks do not establish visual correctness,
guide equivalence, frame lifetime on a GPU, or general shaderpack compatibility.
