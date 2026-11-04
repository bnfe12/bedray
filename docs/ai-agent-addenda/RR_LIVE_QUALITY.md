# RR quality follows the live SDK decision

RR previously read cached startup `DlssConfig.requestedQuality()` while live
render sizes followed `UpscalerRequest` and `DlssRuntime`. For example, switching
Quality to Performance changed the trace extent but could leave RR creation in
Quality. CUSTOM was also inconsistent: its SDK-selected `featureMode` can differ
from the UI's placeholder mode. This is a source-confirmed metadata defect;
specific vendor errors and visual impact have not been measured here.

`DlssFeatureSettings` now snapshots the READY runtime's actual input/output
extents, effective SDK quality and initialization epoch. It contains no native
feature handle and remains valid independently of SR feature residency.
`RrQualityPolicy` keeps native-size RR in DLAA and the non-DLSS scaled fallback
in Quality. Scaled active DLSS RR uses the snapshot's effective mode only when
all four extent dimensions match. Missing/stale active metadata is rejected
before feature creation, with requested dimensions and the SDK snapshot in the
error. RR's existing mode-change comparison then drains/recreates the feature
and resets history even if the dimensions stayed the same.

Before RR bootstrap, input/output are equal and take DLAA. For the combined
route, the frozen trace extent and READY metadata come from the same runtime;
the output is updated before RR ensure after resize. A source audit found no
normal bootstrap/resize/CUSTOM case requiring a different extent contract.

Verification: 1,225 common tests passed, 9 skipped, zero failures/errors; 34 shim
checks passed (`rr-quality-verification.log`). Cases cover live preset changes,
CUSTOM effective mode, unchanged extents with a changed mode, stale/missing
settings, native DLAA and non-DLSS fallback. Production wiring guards keep RR
off the cached startup configuration. RTX quality, live UI switching and FPS
acceptance remain unmeasured. RR-specific optimal-size queries remain open.
