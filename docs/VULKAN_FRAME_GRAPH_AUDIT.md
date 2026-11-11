# FSR Vulkan frame graph audit · 2026-11-11

The SDK source and headers were inspected at AMD FSR SDK v2.4.0, commit 7539ae8b6568ad29ed78a753404351daf3b5cca7. The AMD-signed Vulkan effect DLL exports all five standard FidelityFX API functions. Its SHA-256 is pinned in the native catalog. The source was not compiled or run.

| Stage | Inputs and outputs | Ownership and state |
| --- | --- | --- |
| Logical device setup | SDK 2.4 uses the host device and Vulkan device-proc-address table. | Optional ML feature requests are made before device creation. Eligibility checks the enabled device features and the SDK's version list. |
| Provider selection | The version query receives a VkDeviceContext. Size queries chain the Vulkan backend descriptor and selected version ID. | Auto selects the best eligible family. A created context is queried again for its provider. |
| World frame | Bedray supplies the current color, reverse-Z depth, unjittered pixel motion vectors, camera jitter and history reset. | Existing world command buffer and temporal upscaler slot. FSR 4's resource-requirement query checks that Bedray supplies all mandatory inputs. |
| FSR recording | Inputs transition from GENERAL to SHADER_READ_ONLY_OPTIMAL for SDK 2.4, and back afterward. Output stays GENERAL. | The SDK dispatch records into the host command buffer. Barriers order prior world work and later output reads. Legacy SDK 1.1.4 keeps its existing input state contract. |
| Output and retirement | Existing native-resolution output feeds the later HUD stage. Resize and provider changes retire the previous context. | The context remains associated with its original DLL and host device until the existing host fence proves completion. |

Runtime acceptance still needs a supported Radeon and AMD driver. Check the selected provider and command recording first, then validate camera cuts, translucent materials, resize, alternate providers and FPS. AMD FSR Frame Generation 4 is outside this upscaler change. DLSS 5 requires NVIDIA's partner SDK.
