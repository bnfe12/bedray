# Color-only Iris GLSL example

Original source example for Bedray's experimental Vulkan post host. It applies a small contrast change
through a legacy GLSL 120 fullscreen program. This is an unpackaged source folder, not a release or a
claim of in-game validation. It uses only colortex0, so it needs no geometry/depth-provider integration.

A future development instance can place this directory under `shaderpacks/` and select
`iris-color-post` with `-Dvkrtx.shader_host=iris -Dbedray.iris.post=true
-Dvkrtx.shaderhost.pack=iris-color-post`. Restart after changing packs/options in this milestone.

The example is part of Bedray and follows the repository's LGPL license.
