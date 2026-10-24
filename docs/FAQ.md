# FAQ

**Is this the same as Minecraft Bedrock RTX?**
No. Bedray is an unofficial mod for Minecraft **Java** Edition that aims at a similar look ("Bedrock RTX-style"). It is not affiliated with
Mojang, Microsoft, NVIDIA, AMD or Intel.

**Where is "Ray Tracing (Fast)"?**
It is in development and hidden in this release. The settings offer Off and **Path Tracing (recommended)**.

**Which GPUs work?**
Tested only on an NVIDIA GeForce RTX 5060 Ti (8 GB). Ray tracing needs ray query support (NVIDIA RTX, AMD RDNA 2+, Intel Arc). AMD and
Intel cards are untested; testers are wanted ([TESTING.md](TESTING.md)).

**Does it work with shader packs (Iris)?**
General shader-pack support is not implemented. An opt-in [Vulkan fullscreen post subset](IRIS_VULKAN.md) is in source-only development, with no tested-pack or release claim.

**Does it work on Linux or macOS?**
Not tested; the native pieces are Windows DLLs. Windows only.

**Why do I need extra DLLs for DLSS?**
The vendor libraries cannot be redistributed. The small wrapper DLL for DLSS contains NVIDIA's static library, so everybody has to
build it ([INSTALL.md](INSTALL.md)). FSR (with the official AMD DLL) and XeSS (with `libxess.dll`) need no wrapper.

**Can an AI assistant install the libraries for me?**
It can help with the careful parts (official links, hash check, copying to the right folder), under strict rules:
[INSTALL_NATIVES_WITH_AI.md](INSTALL_NATIVES_WITH_AI.md).

**What does the mod send over the network?**
Nothing. It does not download anything.

**Which upscaler is best?**
See [UPSCALERS.md](UPSCALERS.md). Quality was not compared systematically.

**Is frame generation supported?**
Yes: NVIDIA DLSS frame generation (2x/3x/4x) and Reflex on RTX 40/50 through Streamline (BETA, tested only on an RTX 5060 Ti). You add the Streamline files yourself and restart once ([FRAME_GENERATION.md](FRAME_GENERATION.md)).

**Does it work with other mods or modpacks?**
Only Sodium is required and supported. Other renderer-changing mods can conflict. Please test on a clean instance first.

**Can I use my settings from an older build?**
An old `config/vulkan_rtx.toml` is copied once to `config/bedray.toml`.

**How do I report a problem?**
Use the issue forms and attach `logs/latest.log` with personal paths removed ([TESTING.md](TESTING.md)).

**Can I redistribute Bedray or include it in a modpack?**
The licence is LGPL-3.0-or-later; read it. Never bundle vendor DLLs.
