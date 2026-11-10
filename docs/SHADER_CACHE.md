# Shader and pipeline cache

Bedray compiles its Slang shaders to SPIR-V at build time and ships them in the loader JAR with reflection manifests.
The native core uses those artifacts to create Vulkan pipelines.

When the core exports cached program registration, it borrows the host's Vulkan pipeline cache during program
registration. Bedray keys the cache by the device, driver and pipeline-cache UUID and saves it when the device closes.
If Vulkan rejects stored data, Bedray starts with an empty cache.

Cache files live under `config/bedray/cache/vulkan/` and contain driver pipeline data. Close Minecraft before deleting
them to rebuild the cache. User-supplied Iris GLSL has a separate compilation path.
