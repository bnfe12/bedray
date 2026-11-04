# Native acceleration-structure update policy

The host previously disabled AS updates and assigned every entity capture a new
topology revision every frame. `EntityTopologyIdentity` now compares vertex/index
counts, vertex stride, index format and exact index bytes, independently of frame,
ring-buffer address and vertex positions. It copies index bytes only on a topology
change, preserves its snapshot after producer memory expires and never reuses a
revision after removal. Geometry revisions still follow each fresh capture.

Safe updates require the additive ABI-v2 `bedray_scene_features` query and its
`SAFE_AS_UPDATE` bit. An older core without that query keeps rebuilding, even with
the newer host, rather than exposing its old deferred-refit behavior. Descriptor
sizes, layouts and shader ABI remain unchanged. `-Dbedray.rt.disableAsUpdates=true`
selects the old build policy when the scene is created, for an A/B comparison.

## BLAS and published attributes

An entity with unchanged topology may refit only when its previous BLAS has no
outstanding readers. If it is still pinned, native builds a separate BLAS from
the current capture and publishes that generation's address immediately. It no
longer defers the geometry while the host shades current UV/material/position
attributes. Old BLAS and buffers retain their exact completion lifetimes. Changes
in shape/topology, unavailable update capability or non-finite positions force
full builds. Non-finite current/previous positions cannot cross Vulkan's
active/inactive primitive boundary through UPDATE. Static sections retain full
builds and do not request unnecessary ALLOW_UPDATE storage.

## TLAS source dependencies

For a released slot with the same instance count and nonzero BLAS references,
TLAS UPDATE may replace instance definitions, including BLAS addresses and masks.
The former fingerprint restriction and per-frame instance hashing are removed.
This follows the [Vulkan update rules](https://docs.vulkan.org/spec/latest/chapters/accelstructures.html#acceleration-structure-update-rules).

A cached TLAS still references its old BLAS even after its last trace completes.
Each slot therefore retains cached BLAS references. A TLAS UPDATE pins both its
old source bindings and new bindings through that recording's completion. Garbage
collection checks both active readers and cached update sources before freeing
a retired BLAS. Source vertex/index buffers need only their existing recording
lifetime: constructed BLAS are self-contained. Cached TLAS/BLAS residency is
bounded by the fixed slot count and released when the scene is destroyed.

## Evidence and limits

Common tests: 1,214 passed, 9 skipped; shim checks: 34 passed. Native contract
regressions cover pinned COW publication, later unpinned refit, replacement BLAS
relinking, mask changes, non-finite activation changes, static-section flags and
20 pipelined frames with bounded retained dependencies. The fake Vulkan recorder
checks that both current and source-TLAS BLAS references remain live. Windows CI
executed those cases successfully in [PR #73](https://github.com/bnfe12/bedray/pull/73);
there is no local MSVC toolchain.
`BedraySceneFeaturesProbe` exercises actual FFM loading/querying of old/new DLLs
without a Vulkan device.
The actual old/new core probe returned ABI 2 with features 0/1 respectively.

`native_scene active` logs interval frame counts, BLAS builds/refits, TLAS
builds/updates, copy-on-write frames, deferred updates and entity topology revision.
These are work counts, not GPU timings. RTX image/validation-layer checks,
residency under the owner's gameplay and native 1080p/60 remain unmeasured.
Pipelined entity BLAS reuse beyond safe COW remains separate work.
