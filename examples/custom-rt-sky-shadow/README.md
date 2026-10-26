# Custom RT stage example: sky shadow

Original source example for Bedray's custom ray-tracing stage ([contract](../../docs/CUSTOM_RT_SHADERS.md)).
`shaders/bedray/rt.comp` rebuilds each pixel's primary ray from the `BedrayFrame` camera, takes the first opaque hit
from the RT distance guide (or traces it with a ray query when the guide is absent) and casts one shadow ray toward a
fixed sky direction. Blocked pixels are darkened to 65 %.

It exists to prove the host path (scene TLAS, camera block, color and depth in; one RGBA16F image out), not to look
good. It compiled through the startup path; it has not been run on a GPU or in a game session.

To try it in a development instance, copy this folder to `shaderpacks/custom-rt-sky-shadow`, enable hardware RT, and
start the game with `-Dbedray.customrt=custom-rt-sky-shadow`. Status appears in the F3 diag section as `[CUSTOM-RT]`.

The example is part of Bedray and follows the repository's LGPL license.
