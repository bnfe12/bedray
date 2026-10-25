# World uniforms post example

Original unpackaged example for the experimental Iris Vulkan post host. A single `final` program reads the typed world
block and the engine macros:

- sun/moon glow from `shadowLightPosition` projected with `gbufferProjection` (sunPathRotation -30 via const directive),
  scaled by `eyeBrightnessSmooth` sky light;
- `rainStrength` and the smoothed `wetness` (custom wetness/dryness half-lives) darken and desaturate;
- `worldTime`, `moonPhase` and `nightVision` cool the night;
- `isEyeInWater`, `fogColor`, `fogStart`/`fogEnd` tint the view inside fluids;
- `blindness` and `darknessFactor` close in from the edges;
- `#if MC_VERSION >= 260000` selects the normal path (Iris's version formatting: 26.3 is 260300).

Copy this folder to `shaderpacks/iris-world-post` and start a development instance with
`-Dvkrtx.shader_host=iris -Dbedray.iris.post=true -Dvkrtx.shaderhost.pack=iris-world-post`. It reads the camera
projection, so it waits for a frame whose producer supplies one; it needs no depth guide.

It compiled through the real scanner, translator and SPIR-V compiler; it has not been run on a GPU or in a game.
This original example follows Bedray's LGPL license.
