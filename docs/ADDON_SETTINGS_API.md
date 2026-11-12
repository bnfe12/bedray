# Add-on settings

Bedray exposes `io.github.andrexarlay.bedray.api.v1.settings.AddonSettings` in
`bedray-renderer-api`. Compile against the API and declare Bedray as a
dependency. The Bedray mod supplies the API classes at runtime on Fabric and
NeoForge.

Register once during client initialization. Use the mod ID as the namespace and
keep option and choice IDs stable across versions. Add localized keys to the
add-on's language resources and supply fallbacks.

```java
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings;
import io.github.andrexarlay.bedray.api.v1.settings.AddonSettings.*;

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

    AddonSettings.register(new Registration("example", Text.literal("Example add-on"),
        List.of(enabled, strength), storage));
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
