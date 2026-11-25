# mosaic-rt-check

The hardware ray tracing check of Mosaic: a node graph that traces Bedray's scene TLAS (ray-traced ambient occlusion
and water reflections plus voxel GI, the "Hybrid RT" template), then a bloom mip chain (1/2, 1/4, 1/8 with Kawase
steps) and Temporal AA on a full-size Buffer - five compute passes in the custom RT stage.

It needs a GPU with Vulkan ray query (RTX, RX 6000+, Arc). The same graph without ray nodes was checked on an
integrated GPU on 2026-11-25 (VERIFICATION_LOG); this pack is what is left to see on a ray tracing GPU.

## Run

1. Copy this folder into `<instance>/minecraft/shaderpacks/mosaic-rt-check`.
2. In Bedray's settings choose Ray Tracing (any hybrid mode) or Path Tracing; turn temporal upscaling and frame
   generation off (the stage waits while they are on).
3. Start the game with the JVM arguments

   ```
   -Dvkbridge.autotest=iris -Dbedray.customrt=mosaic-rt-check
   ```

   It creates a world, takes four shots (morning, noon, sunset, night) into `<instance>/minecraft/autotest/iris/` and
   quits. Without the autotest argument the stage just runs while you play.

## What to look for

* `latest.log`: `[CUSTOM-RT] pack=mosaic-rt-check state=ACTIVE ... depth bound, 5 passes (...)` and
  `result=PASS`. `state=WAITING ... hardware RT did not render this frame` means the renderer was not in a ray tracing
  mode.
* Shots: darker contact areas (ray-traced AO), reflections on water, a soft glow around bright parts, no ghost trails.
