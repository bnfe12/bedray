# Depth/camera post source example

Original unpackaged GLSL 120 example for the experimental Iris host. DISTANCE reconstructs view-space
distance from depthtex0 and gbufferProjectionInverse, adding a small haze. MOTION visualizes camera
motion using the current/previous projection, model-view and camera position; it has no temporal color
buffer and is not temporal antialiasing. Scene/object motion is not supplied.

In a future instance built from matching source, select `iris-depth-post` with the normal Iris post
flags and optionally `-Dbedray.iris.profile=MOTION`. Restart after changes. MOTION waits for one
continuous previous camera frame after world/producer/device/extent changes or camera cuts.

The current raster adapter supplies matching reverse-Z depth. Hardware RT and voxel adapters remain
color-only until they explicitly publish matching depth guides; this pack is suspended on them.
Compilation to SPIR-V is the available evidence. No game/GPU validation or product JAR is supplied.
This original example follows Bedray's LGPL license.
