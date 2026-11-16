# Add-on settings

## Add Bedray to your project

Download the [Renderer API 1.8.0 Maven archive](https://github.com/bnfe12/bedray/releases/tag/renderer-api-v1.8.0)
and extract its `maven` directory into `vendor/bedray-api` in your project.
For a source build, `./gradlew :renderer-api:publishToMavenLocal` installs the
same coordinates into Maven Local. Use a development Bedray build that provides
API 1.8 on Fabric or NeoForge.

```groovy
repositories {
    maven {
        url = uri('vendor/bedray-api/maven')
        content { includeModule('io.github.andrexarlay', 'bedray-renderer-api') }
    }
}
dependencies {
    compileOnly 'io.github.andrexarlay:bedray-renderer-api:1.9.0'
}
```

Declare the `bedray` mod as a client dependency in your loader metadata. Keep the
API JAR on the compile classpath. The Bedray mod supplies the classes at runtime.
For optional integration, isolate the code in a class loaded only after your
loader confirms Bedray is present. Check
`GraphicsBackendRegistry.apiMinorVersion() >= 8 (>= 9 for light providers)` before invoking API 1.8 code.

## Contribute controls

`io.github.andrexarlay.bedray.api.v1.settings.AddonSettings` supports toggles,
integer sliders, decimal sliders and labeled choices.

Register once during client initialization. Use the mod ID as the namespace and
keep option and choice IDs stable across versions. Add localized keys to the
add-on's language resources and supply fallbacks.

```java
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings;
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.*;

import java.math.BigDecimal;
import java.util.List;

static void registerSettings(Storage storage) {
    Text group = new Text("example.settings.lighting", "Lighting");
    var enabled = new BooleanOption(new Details("enabled", group, 10,
        new Text("example.settings.enabled", "Enable lighting effect"),
        new Text("example.settings.enabled.help", "Adds the example light effect.")), false);
    var strength = new IntegerOption(new Details("strength", group, 20,
        new Text("example.settings.strength", "Intensity"),
        new Text("example.settings.strength.help", "Intensity in percent."),
        List.of(new Condition("enabled", true))), 50, 0, 100, 5);

    var falloff = new DecimalOption(new Details("falloff", group, 30,
        new Text("example.settings.falloff", "Falloff"),
        new Text("example.settings.falloff.help", "Distance falloff exponent."),
        List.of(new Condition("enabled", true))), new BigDecimal("1.0"),
        new BigDecimal("0.5"), new BigDecimal("2.0"), new BigDecimal("0.05"));

    AddonSettings.register(new Registration("example", Text.literal("Example add-on"),
        List.of(enabled, strength, falloff), storage));
}
```

Open **Bedray → System → Add-on settings** to edit the registered controls.
The screen stages changes per add-on and calls `Storage.apply` once with the
edited fields. The add-on owns its persistence. Merge the patch into your saved
values and validate any constraints involving more than one field before
committing. If saving fails, throw an exception and retain the previous state.

`Storage.read` supplies a snapshot keyed by local option ID. Missing or outdated
values use the descriptor defaults. The storage callbacks run on the client
thread when the page reads or applies settings. Conditions describe when the
control is available. Its saved preference is retained across enable and disable
cycles.

Decimal values use `BigDecimal` in the storage snapshot and patch. Persist exact
values and compare numerically with `compareTo`. Decimal conditions match equal
values with different trailing zeros.

## Renderer entry point

The same artifact includes the experimental `GraphicsBackend` registration,
`ComputeFrameContext` and world snapshot types. Implement `GraphicsBackend` and
register once with `GraphicsBackendRegistry.register(...)` on the client. The
host owns frame callbacks and permits one compute backend. Integrations that
use the Vulkan callback types also need the game's LWJGL Vulkan bindings on the
compile classpath. See the [companion module](../compute-common/) for an example.

## Contribute lights (API 1.9)

`io.github.andrexarlay.bedray.api.v1.light.LightProviders` lets an add-on supply extra point lights once per frame.
Check `GraphicsBackendRegistry.apiMinorVersion() >= 9` first.

```java
LightProviders.register("example", camera -> List.of(
    new PointLight(10.5, 70.0, -3.5, 1.0f, 0.6f, 0.2f, 12.0f)));
```

* Lights are validated when constructed (finite position, colour in `[0, 1000]`, radius `0.5` to `64` blocks).
* One provider may contribute at most 64 lights per frame and all providers together at most 256; the rest are dropped.
* A provider that throws is skipped for that frame and disabled after three failing frames in a row;
  `LightProviders.status()` reports each provider.
* **Status:** the host collects the lights each frame and, with the "Lights from other mods" switch on (default), turns each into a
  small emitter quad (colour = tint, brightness = largest component times (radius / 8)^2 of one torch). This is a first calibration and has not been looked at on a GPU yet; `LightProviders.appliedByHost()` tells an add-on whether lights were fed in the last frame.
  See `docs/EXTENSION_AND_INSTALLER_DESIGN.md` for the planned layers (sky, materials, pass hooks).
