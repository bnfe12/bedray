# Resource-pack sky display packet

The typed path now has a `FrameSky` environment adapter. The visible background
uses the existing vanilla display equations transferred from `rt_sky.glsl`
at `1ea94ce^`: resource-pack sun and moon phases, stars, FAST/FANCY clouds,
sunrise/fog composition and End sky/flash. Thirteen source fingerprints pin
the transferred equations after language and explicit-state conversion.

The modules have separate jobs: `contracts/sky_display.slang` owns the private
packet, `sky_textures.slang` the checked ABGR atlas access, `sky_celestial.slang`
the sprites/stars, `sky_clouds.slang` the cloud walk, `sky_background.slang` the
display composition and `frame_sky.slang` the environment interface.

The adapter changes visible radiance only. `radiance()` includes the display
bodies; `viewRadiance()` leaves their contribution to NEE. `skyRadiance()`,
`sample()` and `pdf()` keep the existing neutral lighting dome. No texture sky
term is added to diffuse lighting or RC field sources. The old separate
specular glint is not transferred, because NEE already owns that light.

The background is authored in display space. The typed display always applies
exposure, so conversion divides by exposure in both output modes, applying the
existing inverse tone curve when enabled. This retains the authored display
appearance when exposure changes.

## Packet and lifetime

The adapter reads the existing 576-byte slot at binding 37. Public ABI structures,
the 608-byte `FrameConstants`, 112-byte path push and descriptor counts do not
grow. No legacy declaration is imported.

| Bytes | Private meaning |
|---|---|
| 72..75 | Atlas allocation size in uint words; formerly unused std430 padding |
| 256..367 | Existing sky direction/angles, fog/stars, sunset, cloud and miscellaneous state |
| 368..375 | Borrowed atlas device address |
| 376..379 | Existing low sky flags, tonemap bit 10, version 1 in the high byte |
| 380..383 | Celestial tilt |
| 488..503 | Raw display sky RGB and day factor; replaces the deleted ray pass's dormant ring addresses |

The shader refuses a short slot, unknown version, null/unaligned/wrapping address
or a short atlas header before any physical-buffer read. Each loaded texture's
offset and dimensions must fit the allocation; pixel coordinates are checked
before access. Missing individual sprites keep the existing plain-sprite
fallback, and missing clouds remain absent. An unavailable packet uses the
existing gradient adapter; the host reports the reason.

The host must retain the immutable atlas allocation for every command buffer
that reads it. Resource reload must prove completion of queued host work before
freeing or replacing that allocation; device-idle alone cannot prove that an
unsubmitted command buffer stopped referencing it. The stats slot itself keeps
the renderer's existing exact completion/discard proof.

`ResourceReloadEpoch` advances after Minecraft completes resource loading,
including reloads with unchanged pack IDs. `RtSkyTextures.prepare` runs in frame
preflight before command allocation. It packs and uploads the complete new atlas,
then asks the renderer to submit/fence prior readers before retiring the old BDA.
Unchanged epochs do not allocate or enumerate packs. Failed upload/proof keeps
the old atlas and epoch; failed rollback keeps the new allocation reachable for
retry. The CPU encoder and GPU allocation owner are separate modules.

Atlas failures name stage, requested/loaded epoch, word range, prior address and
rollback ownership while preserving the original exception. Texture-load logs
include resource ID, slot, source pack and extent. The selected textured/gradient
display mode is logged only when its state changes.

Source and no-GPU tests verify packet writes, bounds, source ownership and
equations. AOT compilation is build evidence, not a rendered frame or an owner
acceptance/performance result.
