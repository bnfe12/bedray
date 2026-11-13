# Building from source

You do not need this to play. Releases contain ready jars. Build from source if you want to contribute or check what you run.

Each NeoForge and Fabric JAR records `Git-Commit`, `Git-Dirty`, and `Build-Source` in
`META-INF/MANIFEST.MF`. The commit is the full checkout HEAD; dirty includes tracked changes and
untracked files that Git does not ignore. These values are refreshed for every Gradle invocation.
When building a source archive without its own `.git`, or when Git is unavailable, the commit and
dirty state are `unknown` and the source is `source-archive`. Build metadata never guesses a commit
from a containing repository. Builds do not embed timestamps, preserving reproducible metadata.

## What you need

* Windows, **JDK 25**, Git. The Gradle wrapper is included (`gradlew`).
* A Minecraft launcher on this PC that has run **Minecraft 26.3** and the loader you build for. The build reads loader classes from
  the launcher's `libraries` folder (`compileOnly`, never packaged).
* A local `libs/` folder with game and mod jars. **These are not in the repository and must not be committed or redistributed**
  (`libs/` is in `.gitignore`).
* The Java platform layer (`core/`) is built into the loader JAR.
* To build a runnable ray-tracing distribution, also install the pinned Slang compiler, CMake, MSVC and Vulkan headers.

## 1. Settings outside the repository

Machine-specific paths are not stored in `gradle.properties`. Either set the environment variable
`BEDRAY_LAUNCHER_LIBRARIES` to your launcher's `libraries` folder (the folder that contains `net/neoforged`, `net/fabricmc`, `org/lwjgl`),
or copy the lines you need from `gradle.properties.example` into `~/.gradle/gradle.properties`:

```
neoforge_libs_dir=C:/path/to/launcher/libraries/net/neoforged
fabric_libs_dir=C:/path/to/launcher/libraries/net/fabricmc
lwjgl_libs_dir=C:/path/to/launcher/libraries/org/lwjgl
```

The versions of the launcher jars that are expected (`neoforge_runtime_version`, `fml_loader_version`, `nf_bus_version`,
`nf_mergetool_version`, `fabric_loader_version`, `lwjgl_natives_version`) are in `gradle.properties`. If your launcher has other
versions, override them with `-P` or in your user Gradle home.

## 2. The `libs/` folder

Create `libs/` in the repository root and put these jars in it (any file name ending in `.jar` is picked up):

| Content | Where to get it legally |
|---|---|
| Minecraft 26.3 client jar | From your launcher: `versions/26.3/26.3.jar` (obtained by the launcher from Mojang). Do not share it. |
| Sodium 0.9.2 for NeoForge (the mod jar, and the nested `META-INF/jarjar/...-mod.jar` extracted from it as a second file) | Modrinth, project "Sodium" |
| Iris 1.11.x for 26.3 (compile only) | Modrinth, project "Iris Shaders" |
| authlib, datafixerupper, logging, jtracy, text2speech | From your launcher's `libraries` folder (the versions used by Minecraft 26.3) |

Only Minecraft and the mods you download yourself from their official pages belong here.

## 3. Modules

| Module | Content |
|---|---|
| `core` | Bedray Core: device-requirement registry, backend guard, native-library resolver (`<game>/bedray/natives`), frame phases, GL overlay layer, autotest harness, Iris GUI compatibility. No loader API. |
| `common` | All mod logic (rendering, ray tracing, upscalers, settings). No loader API. |
| `neoforge`, `fabric` | Entry point and descriptor. Each loader jar contains `core` + `common` + its own classes, so a user installs exactly one jar. |
| `renderer-api` | Host-owned versioned companion API, included only in host loader JARs. |
| `compute-common`, `compute-neoforge`, `compute-fabric` | Software voxel provider, nested in the player distribution and registered as its own mod. See [COMPUTE](COMPUTE.md). |

## 4. Build and test

```
gradlew clean :core:test :common:test :common:runShimTests :neoforge:jar :fabric:jar
```

Jars appear in `neoforge/build/libs/` and `fabric/build/libs/`, named `bedray-neoforge-mc26.3.0-<version>.jar` and
`bedray-fabric-mc26.3-<version>.jar`. `runShimTests` checks Java-level logic and compiles the shaders with shaderc; it does not use
the GPU and is **not** evidence that a feature works in the game.

### The JUnit suite (`:common:test`)

`:common:test` is the no-GPU Java suite. It compiles the Slang passes first when `slangc` is configured, and it declares the
LWJGL shaderc native artifact itself, so no launcher installation is needed to load `shaderc.dll` at test runtime. `libs/` is
still required for the game classes the tests compile against.

Two build guards keep a native or classpath failure from looking like a slow test:

* every test has a 120 s JUnit timeout; a native hang fails the run instead of holding the gate;
* on Windows the Gradle test worker can mangle non-ASCII classpath entries. Run the suite from an ASCII worktree
  (`git worktree add C:\Temp\bedray-wt HEAD` and run Gradle there). The build prints a warning when the project path
  contains non-ASCII characters.

The renderer's Slang shaders compile ahead of time. The Iris post host uses shaderc for pack-supplied GLSL.
A passing `:common:test` is L2 evidence (Java logic and source scans without a GPU), not a game check.

The `combinedJar` task of each loader module builds the canonical player artifact with the Bedray Compute provider
nested inside (`bedray-neoforge-mc26.3.0-<version>.jar`, `bedray-fabric-mc26.3-<version>.jar`):
NeoForge JarJar (`META-INF/jarjar/`) for NeoForge, a Fabric Loader nested JAR (`META-INF/jars/`, `fabric.mod.json` `jars`)
for Fabric. Compute keeps its own mod id (`bedray_compute`) and descriptor and still loads as its own mod. `assemble`,
`jar`, deployment and Maven publication select or produce this included-provider distribution. The `jar` task also creates
a developer-only `-host-only.jar`; a deliberately separate setup needs that host and the matching standalone provider.
Never install the player JAR together with a standalone Compute JAR: that duplicates the provider's mod ID.

Developer deploy tasks require a clean source build and closed Java clients. They verify that the provider is nested,
move earlier host and external companion JARs to a dated backup, then install the single player JAR. A failed move rolls
back the earlier files. `-PdryRun=true` inspects the planned changes without touching the instance. Configurations and
native-library folders are not part of this JAR replacement.

The default developer build contains no DLLs. Release builds may include only the exact AMD FidelityFX SDK v1.1.4 Vulkan DLL
and Intel XeSS SDK 3.0.2 DLL, verified against SHA-256 pins and bundled with their license files. No NVIDIA vendor DLLs or native wrappers are
allowed in release jars.

For a release build, obtain the official AMD/Intel SDK files and their notice files, then place these exact filenames in a folder
outside the repository: `amd_fidelityfx_vk.dll`, `amd_fidelityfx_vk.LICENSE.txt`, `libxess.dll`, `libxess.LICENSE.txt` and
`libxess.third-party-programs.txt`. The Gradle build checks both binary hashes and the exact license/third-party notice hashes, then fails closed if a file or notice is missing.
Run the normal build command with `-PbundleNatives=licensed -PredistributableNativesDir=<folder>`. Without those properties,
the build remains native-free. Verify the JAR contents and compare both DLL hashes with the pins in `THIRD_PARTY_NOTICES.md` before publishing.

### Windows renderer distribution

Set `SLANG_DIR` to the Slang SDK directory and run `gradlew fabricBedrayDistribution` or
`gradlew neoforgeBedrayDistribution`.
The distribution pairs the loader JAR and the matching `bedray_core.dll`, which exports the native GPU scene ABI.
See [distribution paths and build options](https://github.com/bnfe12/bedray/blob/main/native/bedray_core/DISTRIBUTION.md).

### Continuous integration and compatibility checks

* `.github/workflows/native-windows.yml` runs on pull requests and pushes to `main`: it builds the DLSS resource wrappers against pinned Vulkan-Headers and
  exercises the Java bindings against fault and production DLLs. It has no GPU, so it is not game evidence.
* `python tools/sodium_compat_check.py --jars-dir <folder>` verifies the sha512 of each official Sodium NeoForge 26.3 build against `tools/sodium-matrix.json`
  and compares the contract classes with `javap`; run Gradle with `-PsodiumJar=<version>` for the per-version compile.

## 5. Building without the native wrappers

Vendor wrappers are optional. With the matching renderer distribution, ray tracing and path tracing with accumulation
and the built-in upscalers (Bilinear, FSR 1, NIS) can run without them. The release JAR can supply XeSS and FSR 3.1 separately.
The settings page reports missing vendor libraries for DLSS, NRD and frame generation.
Current player packages omit the SDK wrappers, so users who need them build them from source.
See [Native library licences and delivery](NATIVES_LICENSES.md) for the per-component distribution policy;
this is not a blanket claim that SDK-linked wrappers can never be redistributed.

## 6. Native wrappers (optional)

`native/dlss_bridge`, `native/nrd_bridge` and `native/sl_bridge` contain the sources of the three wrapper DLLs, each with a
`build.bat`. They need Visual Studio 2022 Build Tools (MSVC), the Vulkan headers and the vendor SDK. The scripts read the locations
from environment variables (`VKB_NGX_SDK`, `VKB_VK_HEADERS`, `VKB_NRD_SRC`, `VKB_SL_SDK`, `VKB_VCVARS`) and have no defaults; each
script prints what is missing. Vendor SDKs are downloaded from the vendor and are never committed. Built DLLs go into `<game folder>/bedray/natives/`
(see [INSTALL.md](INSTALL.md)) and must not be committed.

For the opt-in DLSS 5 Neural Rendering build, see the [RTX Remix SDK overlay instructions](experiments/dlss5.md#build-a-local-wrapper).

## 7. Optional deploy tasks

`deployTestbed`, `deployFabricTestbed`, `deployUser`, `syncDlssNatives` copy the single Bedray jar (and for DLSS the two DLLs) into a test instance. They need `testbed_dir`,
`fabric_testbed_dir`, `user_dir`, `local_natives_dir` in your user Gradle home. `-PdryRun=true` only lists what would be copied.

## Before you publish a fork

Run `powershell -ExecutionPolicy Bypass -File tools/public_scan.ps1`. It checks the files listed in
`tools/PUBLIC_EXPORT_ALLOWLIST.txt` for personal paths, e-mail addresses, tokens and binaries.
