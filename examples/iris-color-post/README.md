# Color-only Iris GLSL example

Original source example for Bedray's experimental Vulkan post host. It applies a small contrast change
through a legacy GLSL 120 fullscreen program. This is an unpackaged source folder, not a release or a
claim of in-game validation. It uses only colortex0, so it needs no geometry/depth-provider integration.
The guarded `lib/color.glsl` include holds source defaults and conditional color functions.
Includes, saved options, profiles and macros are resolved before Vulkan translation. The source default
draws a contrast-only final pass. `BRIGHT` enables an additional exposure composite pass; `NEUTRAL`
disables both effects. `shaders.properties` uses inheritance and conditional screen metadata.

A future development instance can place this directory under `shaderpacks/` and select
`iris-color-post` with `-Dvkrtx.shader_host=iris -Dbedray.iris.post=true
-Dvkrtx.shaderhost.pack=iris-color-post`. Restart after changing packs/options in this milestone.

Use `-Dbedray.iris.profile=BRIGHT` to select that profile for the session. For saved options, copy
`options.example.txt` beside the installed pack as `shaderpacks/iris-color-post.txt`; when the pack is
named `Example.zip`, Iris's convention is `Example.zip.txt`. These explicit saved values override a
session profile baseline. Bedray reads the file without rewriting it. The example file is source
documentation and has no effect while it remains inside the pack folder.

The [option contract](../../docs/IRIS_OPTIONS.md) describes the supported syntax and remaining limits.

The example is part of Bedray and follows the repository's LGPL license.
