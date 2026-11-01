// One environment interface keeps visible background and illumination separate.
// The optional private sky packet changes only what an eye or glossy escape
// sees. Sampling, its PDF and diffuse/environment lighting keep the light dome.
public module frame_sky_light;
__exported import "light/sky.slang";
__exported import "contracts/sky_display.slang";
import "light/sky_background.slang";

struct FrameSky : IEnvironmentSampler {
    SunMoonSky fallback;
    SkyDisplayState display;

    float3 radiance(float3 direction) {
        return display.valid ? skyBackgroundRadiance(display, direction, true) : fallback.radiance(direction);
    }
    float3 viewRadiance(float3 direction) {
        // NEE already owns the discrete sun/moon at a glossy receiver.
        return display.valid ? skyBackgroundRadiance(display, direction, false) : fallback.viewRadiance(direction);
    }
    float3 skyRadiance(float3 direction) { return fallback.skyRadiance(direction); }
    SkySample sample(float3 normal, inout uint seed) { return fallback.sample(normal, seed); }
    float pdf(float3 normal, float3 direction) { return fallback.pdf(normal, direction); }
};
