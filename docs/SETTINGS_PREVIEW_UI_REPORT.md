# Settings source audit · 2026-11-11

This audit supplements the option inventory in [SETTINGS_AUDIT](SETTINGS_AUDIT.md). It covers graphics providers and their settings dependencies in the current source.

| Control | What the player can change | What the status reports | Persistence and ownership |
| --- | --- | --- | --- |
| Upscaler and quality | Pick a technology and a quality mode. The quality row follows the pending backend. | Runtime availability, restart requirement, or initialization failure. DLSS Super Resolution shows the current quality when the context is ready. | Each backend keeps its quality preference across switches. |
| DLSS, XeSS and FSR render scale | A percentage per dimension beside each backend's Custom quality mode. Bilinear has a separate Custom 3D Scale. | The engine/vendor can restrict the actual input size. FSR reports the provider and actual input/output dimensions. | Separate TOML keys. A scale applies only when the matching backend uses Custom. |
| FSR Version | Auto, FSR 4, and legacy families reported by the SDK. A saved family remains visible after a hardware change. | Missing signed runtime, required restart, no eligible ML provider, initialization error, or active provider. | An explicit FSR 4 choice survives reload. If it cannot activate, the renderer reports the cause and uses bilinear for the frame. |
| FSR sharpening | Strength is adjacent to the FSR backend and version. | Live FSR diagnostics remain separate from a staged change. | Same per-backend value in the existing config. |
| Path denoiser | Off, NRD and DLSS Ray Reconstruction. | Draft selection, actual denoiser runtime, resolution reason, and recorded failure when it applies. The DLSS SR row reports when RR owns reconstruction. | The tracing quality profiles own bounces, samples and distance. Upscaling and denoising are independently adjustable. |
| Frame generation | Separate provider and multiplier, with runtime status. | Existing provider status and its restart requirement. | Existing FSR 3 and Streamline paths. FSR Upscaling 4 uses the existing upscaler slot. |
| DLSS 5 Neural Rendering | Partner SDK required. | Current public SDK availability is documented in the feature matrix. | Prototype DLL names and UI strings were removed. |

The new controls have English and Russian labels. The preview panel caches asset presence and the manifest for one screen. Screen close calls PreviewPanel.clearSelection(), which clears that state. This is a source trace of the cleanup path.

Custom scale uses the existing validated Sodium bindings and the same per-backend fields as TOML import/export. Native DLL installation and the next logical-device creation remain separate events.

The FSR 4 implementation is source-level. Visual quality, frame pacing, GPU-specific selection and screen layout were not run in this task.
