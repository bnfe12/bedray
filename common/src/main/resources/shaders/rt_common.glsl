// Shared scene access of the RT shaders: one umbrella that includes the former 2748-line rt_common.glsl
// as ordered modules (RtShaderCompiler resolves includes textually with include-once semantics; the order
// is declaration-before-use and verified by RtShaderModulesTest). Consumers still include only this file.
#include "rt_common_core.glsl"
#include "rt_tonemap.glsl"
#include "rt_sky.glsl"
#include "rt_common_light.glsl"
#include "rt_common_sampling.glsl"
#include "rt_common_surface.glsl"
#include "rt_common_pixel_shadow.glsl"
#include "rt_common_emitters.glsl"
#include "rt_common_temporal.glsl"
#include "rt_common_water.glsl"
#include "rt_common_shade.glsl"
