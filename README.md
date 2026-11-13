<p align="center">
  <picture>
  <source media="(prefers-reduced-motion: reduce)" srcset="docs/images/bedray-banner.png">
  <img src="docs/images/bedray-banner.gif" alt="Bedray — light, block by block. An animated illustration of a ray bouncing through a voxel scene." width="100%">
  </picture>
</p>

<h1 align="center">Path tracing for Minecraft Java</h1>

<p align="center">
  A new light on familiar worlds.<br>
  Bedray brings ray tracing and path tracing to Minecraft's native Vulkan backend, with settings inside Sodium.
</p>

<p align="center">
  <strong>Early alpha</strong> &nbsp;·&nbsp; Windows &nbsp;·&nbsp; NeoForge / Fabric &nbsp;·&nbsp; Minecraft 26.3
</p>

<p align="center">
  <a href="https://github.com/bnfe12/bedray/releases"><strong>Downloads</strong></a> &nbsp; / &nbsp;
  <a href="docs/INSTALL.md">Installation</a> &nbsp; / &nbsp;
  <a href="https://bnfe12.github.io/bedray/">Documentation</a> &nbsp; / &nbsp;
  <a href="ROADMAP.md">Roadmap</a> &nbsp; / &nbsp;
  <a href="https://github.com/bnfe12/bedray/issues/new/choose">Report an issue</a>
</p>

---

## Light, materials, atmosphere

Explore indirect lighting, emissive blocks, reflections and soft shadows in a block-built world.
Tune materials, water, atmosphere and image reconstruction from the in-game Bedray settings.

| Lighting | Materials | Image quality |
| :--- | :--- | :--- |
| Path-traced light with multiple bounces and emissive surfaces. | LabPBR resource packs, automatic vanilla materials, experimental water and tinted glass. | Accumulation, denoisers and DLSS / XeSS / FSR integrations, depending on your GPU and installed libraries. |

**Everything is still in development.** Visual artifacts, crashes and high GPU load are possible.
Feature availability and validation vary; the [feature status](docs/FEATURE_MATRIX.md) and
[known issues](docs/KNOWN_ISSUES.md) cover the details. Performance has no guaranteed target.

## Choose your version

| | What to expect |
| :--- | :--- |
| **Published alpha** · [0.1.0-alpha.1](https://github.com/bnfe12/bedray/releases/tag/v0.1.0-alpha.1) | The earlier renderer. Follow the installation instructions on that release. |
| **Development** · `main` / `0.2.0-alpha` | The new Slang renderer and native C++ core. No published build yet; [build from source](docs/BUILDING.md). |

The banner is a brand animation. The [legacy screenshot gallery](docs/GALLERY.md)
shows the earlier renderer; it does not represent current `main`.

## Get started

These requirements describe the **development version**. For a download, use its release notes.

| Requirement | Development setup |
| :--- | :--- |
| Game | Minecraft 26.3, native Vulkan backend, Java 25 |
| Mods | Sodium 0.9.x and Bedray for **either** NeoForge 26.3.0.x or Fabric Loader 0.19.5+ |
| Platform | Windows; other operating systems are untested |
| Graphics | Vulkan 1.3+ with ray query and acceleration structures for path tracing |
| Renderer | The Bedray JAR and matching `bedray_core.dll` from the same build |

1. Create a separate test instance and back up any worlds you use.
2. Install Sodium and the Windows `-all-natives.jar` for your loader. For a source build, place the matching core in `bedray/natives/`.
3. Open **Options → Video Settings → Bedray Settings** to configure the renderer.

**[Full installation guide →](docs/INSTALL.md)** Optional vendor features need compatible libraries;
use the **[runtime library downloads](docs/RUNTIME_LIBRARIES.md)** page to find official sources and import instructions.
Hardware validation is currently limited to NVIDIA RTX on Windows.
AMD Radeon and Intel Arc test reports are welcome.

## Find your way

| I want to… | Read |
| :--- | :--- |
| Install and set up Bedray | [Installation](docs/INSTALL.md) · [User guide](docs/USER_GUIDE.md) |
| Adjust the image | [Settings](docs/SETTINGS_REFERENCE.md) · [Upscalers](docs/UPSCALERS.md) |
| Fix a problem | [Troubleshooting](docs/TROUBLESHOOTING.md) · [Known issues](docs/KNOWN_ISSUES.md) · [FAQ](docs/FAQ.md) |
| Follow development | [Roadmap](ROADMAP.md) · [Changelog](CHANGELOG.md) · [Feature status](docs/FEATURE_MATRIX.md) |
| Explore experimental features | [DLSS 5 setup and validation limits](docs/experiments/dlss5.md) |
| Build or contribute | [Building](docs/BUILDING.md) · [Contributing](CONTRIBUTING.md) |

## Help shape Bedray

Bug reports, GPU tests and clear screenshots help guide development.
Use the [issue forms](https://github.com/bnfe12/bedray/issues/new/choose) and include your build,
hardware and steps to reproduce. **Review diagnostics and remove personal information before posting.**
See [testing](docs/TESTING.md), [privacy](PRIVACY.md) and [security reporting](SECURITY.md).

---

[LGPL-3.0-or-later](LICENSE) · [Third-party notices](THIRD_PARTY_NOTICES.md)

Bedray is an unofficial project, not affiliated with Mojang, Microsoft, NVIDIA, AMD or Intel.
