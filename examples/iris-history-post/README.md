# Formats, history and mipmaps post example

Original unpackaged example for the experimental Iris Vulkan post host. It exercises the colortex model of
[the resource graph](../../docs/IRIS_RESOURCE_GRAPH.md):

- `composite` writes six targets at once (`RENDERTARGETS:5,2,6,3,7,4`) in declared formats: RGBA16F history,
  R16F luminance, R11F_G11F_B10F bright pass, RGB8 vignette, RG16 coordinates and colortex4 with a clear color.
- colortex5 has `Clear = false`: it keeps last frame's value, so `mix(history, color, 0.12)` leaves a trail.
- `composite1` enables mipmaps for colortex6 and samples levels 2, 4 and 6 as a cheap bloom. It reads the RGB8
  vignette's alpha, which is 1 as for an OpenGL RGB8 texture.
- `final` applies a small saturation change around the stored luminance.

Copy this folder to `shaderpacks/iris-history-post` and start a development instance with
`-Dvkrtx.shader_host=iris -Dbedray.iris.post=true -Dvkrtx.shaderhost.pack=iris-history-post`.
Restart after changes. The example uses no depth, camera or world uniforms.

It compiled through the real scanner, translator and SPIR-V compiler; it has not been run on a GPU or in a game.
This original example follows Bedray's LGPL license.
