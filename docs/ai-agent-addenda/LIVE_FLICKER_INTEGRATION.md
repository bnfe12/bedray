# Light stability integration

The 2026-11-05 capture shows a working RR feature and SR suspension. The
emitter scan elapsed time spans many rendered frames. The selected 196
emitters fit within the configured budget of 512 in that capture. The
reported 3,686,400 pixels describe the 2560×1440 RT output. The trace grid
was 1280×720. Timing and counter logs now report the two grids separately
and identify the recording that produced delayed counters.

The incoming RR jitter A/B used a static torch room at 2560×1440 with DLSS
Quality. The author measured 0.87 pixels of shift before and 0.004 after
changing the RR Y sign. The same branch records a 41.6 to 24.0 ms trace
change with eight versus two emitter candidates in that room. The
conditions differ from the November 5 DLSS Performance capture.
[Jitter and sampling commit](https://github.com/bnfe12/bedray/commit/1264acbd2602c9c7561ed21e12afaef3f11d4ab7)

Static selection remains usable during a replacement scan. Entity quads
are composed with that snapshot using the publishing frame's camera.
Static hysteresis uses membership frozen at scan start. The published
selection updates its membership after a successful buffer publication.
The log includes current scan progress and CPU publication duration.

ReSTIR DI includes every fresh proposal in M. A batch can have positive
M and zero contribution, which still affects normalization when a
history sample wins. An occluded selected sample has separate semantics
and supplies neither weight nor count when imported. The CPU test
enumerates 256 equiprobable draw sequences with common target support.
[RTXDI reservoir sampling](https://github.com/NVIDIA-RTX/RTXDI-Library/blob/main/Include/Rtxdi/DI/Reservoir.hlsli#L139-L169)

Diffuse hits keep material emission while emitter NEE is unavailable.
The populated-table policy is still an approximation because a bounded
table cannot certify that a specific hit emitter was covered by NEE.

The integrated no-denoiser upscaler route uses raster depth for both
velocity generation and SDK evaluation. Enabling the proposed RT motion
route requires a current accepted recording and a matching SDK depth
and motion pair. The two incoming motion commits remain in the branch
history, with their behavior removed from the integration.

The combined build needs an RTX replay of the reported scene, including
camera movement and a separate native 1080p run.
