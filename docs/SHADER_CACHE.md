# Shader cache

Bedray has two Vulkan-side caches for shader startup work:

* **SPIR-V cache:** Bedray compiles its GLSL shader variants with shaderc and stores the resulting SPIR-V under `config/bedray/cache/spirv/`. The key includes the fully expanded shader source, its defines, and Bedray's compiler profile. A changed shader source gets a new entry; invalid entries are ignored and replaced.
* **Vulkan pipeline cache:** Bedray shares a `VkPipelineCache` across its compute, graphics, and ray-tracing pipeline creation. The driver data is keyed by the Vulkan device and driver identity and saved when Minecraft closes the Vulkan device. Vulkan may reject old cache data after a driver change; Bedray then starts with an empty cache.

At startup, a background worker compiles the known Bedray shader variants so the first switch into ray tracing is less likely to trigger shaderc work on the render thread. This does not create GPU pipelines before a device exists; the Vulkan driver pipeline cache fills as those pipelines are first created.

Each compile captures the GLSL extension prefix and SPIR-V target as one immutable profile. Cache-key creation and shaderc
use that same snapshot even if the opacity-micromap capability probe finishes during background warmup. This prevents
storing output compiled for a newer target under an older profile's key. These are local Bedray caches; they do not implement
Microsoft Advanced Shader Delivery. Iris pack compilation has its own frontend and is not claimed to share this disk cache.

To disable background shader warmup, launch Minecraft with:

```text
-Dbedray.shaderCache.prewarm=false
```

Both caches are disposable. Delete `config/bedray/cache/` to rebuild them. Cache files contain compiled shader and driver data, not world or player data.
