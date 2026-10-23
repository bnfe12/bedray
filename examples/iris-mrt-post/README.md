# Multiple-target post source example

Original unpackaged GLSL 120 example for the experimental Iris Vulkan post host. It splits the world
color into red and cyan intermediates, recombines them, then applies a small vignette.

- `composite`: `RENDERTARGETS:3,2` routes `gl_FragData[0]` to **colortex3** and `gl_FragData[1]` to
  **colortex2**. Fragment output locations are positions in that list, not logical target numbers.
- `composite1`: reads both initialized intermediates and writes the reconstructed color to colortex0.
- `final`: reads colortex0 and writes the vignette to a separate presentation scratch image.

In a future instance built from matching source, select `iris-mrt-post` with the normal Iris post flags.
Restart after pack changes. This example uses no depth, camera or object-motion guides. The world color
may come from raster, hardware RT or a successful voxel/synthetic render.

Auxiliary targets are produced during the current graph. This example supplies no normal/material
G-buffer and has no persistent color history. See [the resource contract](../../docs/IRIS_RESOURCE_GRAPH.md)
for allocation, ordering and unsupported properties.

The files are source only. No game/GPU validation, product JAR or shaderpack ZIP is supplied.
This original example follows Bedray's LGPL license.
