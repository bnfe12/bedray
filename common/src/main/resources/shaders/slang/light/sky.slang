// Environment (sky) sampling.
//
// The sky is the third light source after the sun/moon and emitters.  It is not
// a discrete emitter, so it has its own interface instead of being forced into
// ReSTIR DI's reservoir.  A path tracer uses it on a miss; a reflection pass can
// use it for a roughness-dependent environment lobe.

public module sky_light;

import "contracts/frame.slang";
import "contracts/math.slang";
import "light/sun.slang";
import "sampling.slang";

struct SkySample {
    bool   valid;
    float3 direction;
    float3 radiance;
    float  pdf;
};

interface IEnvironmentSampler {
    float3 radiance(float3 direction);
    // The same sky without the bodies the light sampler already draws by NEE (the sun and moon discs). A ray that left a
    // surface whose NEE sampled them pays this one, or the bodies would be counted twice.
    float3 skyRadiance(float3 direction);
    SkySample sample(float3 normal, inout uint seed);
    float pdf(float3 normal, float3 direction);
};

// A two-colour gradient dome.  The host fills AtmosphereState from the current
// dimension, weather and time of day; the shader does not know biome names.
struct GradientSky : IEnvironmentSampler {
    AtmosphereState atmosphere;

    float3 radiance(float3 direction) {
        float3 d = safeNormalize(direction, float3(0.0, 1.0, 0.0));
        float t = saturate(d.y * 0.5 + 0.5);
        float3 zenith = atmosphere.zenithColor.rgb * max(atmosphere.zenithColor.w, 0.0);
        float3 horizon = atmosphere.horizonColor.rgb * max(atmosphere.horizonColor.w, 0.0);
        return lerp(horizon, zenith, t);
    }

    float3 skyRadiance(float3 direction) {
        return radiance(direction);
    }

    float pdf(float3 normal, float3 direction) {
        return max(dot(safeNormalize(normal, float3(0.0, 1.0, 0.0)),
                       safeNormalize(direction, float3(0.0, 1.0, 0.0))), 0.0) * INV_PI;
    }

    SkySample sample(float3 normal, inout uint seed) {
        SkySample result;
        result.valid = false;
        result.direction = float3(0.0, 1.0, 0.0);
        result.radiance = float3(0.0);
        result.pdf = 0.0;

        float3 n = safeNormalize(normal, float3(0.0, 1.0, 0.0));
        float3 direction = cosineAround(n, seed);
        float probability = max(dot(n, direction), 0.0) * INV_PI;
        if (probability <= 0.0) return result;

        result.valid = true;
        result.direction = direction;
        result.radiance = radiance(direction);
        result.pdf = probability;
        return result;
    }
};

// Fraction of a celestial disc a direction sees, in [0, 1].
//
// The comparison works in sines, not cosines.  The key light's angular radius is
// a few thousandths of a radian - `sun_size_tenths` defaults to 0.0044 - and
// between `cos(0.0044)` and `cos(0.6 * 0.0044)` there are only about fifty float
// steps at that magnitude, which turns the limb into a one-pixel dither that
// changes with the frame's jitter instead of an edge.  A sine carries the same
// information two orders of magnitude away from the rounding cliff.  `sin` cannot
// tell a small angle from one near pi, so the hemisphere guard is what keeps the
// far side of the sky empty.  The direction is normalized here rather than
// trusted: `length(cross(d, axis))` scales with |d|, so a caller that hands over
// a non-unit ray would enlarge the disc instead of tilting it - at worst paint
// the whole sky the colour of the sun.
float celestialDiscCoverage(float3 axis, float angularRadius, float3 direction) {
    float3 d = safeNormalize(direction, axis);
    if (dot(d, axis) <= 0.0) return 0.0;
    float sinOuter = sin(max(angularRadius, SUN_MIN_ANGULAR_RADIUS));
    float sinInner = sinOuter * 0.6;
    float sinAngle = length(cross(d, axis));
    return saturate((sinOuter - sinAngle) / max(sinOuter - sinInner, 1.0e-9));
}

// The sun and the moon as they appear in the sky itself.  Both bodies read the
// exact fields the key-light sampler uses - `direction.w` for the angular radius
// and `color` for radiance times intensity - so a disc can never disagree with
// the shadow that same body casts.
//
// A body whose centre has set is not drawn, whatever its intensity says: the
// host keeps the key intensity at 1 through the night (it fades the key in the
// sky model, not here), so without this gate a night sky would carry a phantom
// sun under the horizon.  The ramp spans the body's own diameter, which is what
// a half-set disc looks like.
float celestialHorizonFactor(float3 axis, float angularRadius) {
    float sinRadius = sin(max(angularRadius, SUN_MIN_ANGULAR_RADIUS));
    return saturate((axis.y + sinRadius) / max(2.0 * sinRadius, 1.0e-9));
}

float3 celestialRadiance(SunState sun, float3 direction) {
    float3 result = float3(0.0);

    float3 sunRadiance = sun.color.rgb * max(sun.color.w, 0.0);
    if (luminance(sunRadiance) > 0.0) {
        float3 sunAxis = safeNormalize(sun.direction.xyz, float3(0.0, 1.0, 0.0));
        result += sunRadiance * celestialDiscCoverage(sunAxis, sun.direction.w, direction)
            * celestialHorizonFactor(sunAxis, sun.direction.w);
    }

    float3 moonRadiance = sun.moonColor.rgb * max(sun.moonColor.w, 0.0);
    if (luminance(moonRadiance) > 0.0) {
        float3 moonAxis = safeNormalize(sun.moonDirection.xyz, float3(0.0, -1.0, 0.0));
        result += moonRadiance * celestialDiscCoverage(moonAxis, sun.moonDirection.w, direction)
            * celestialHorizonFactor(moonAxis, sun.moonDirection.w);
    }
    return result;
}

// A gradient dome with the two discs drawn into it.
//
// Only the dome is importance-sampled.  `sample` hands the work back to the
// gradient because the sun and the moon are already discrete entries in the MIS
// list (`sun_light.slang`), and adding their radiance to a cosine-dome draw
// would count each body twice and divide a huge value by a probability that
// never covers it.  `radiance` is a pure evaluation - the term a path pays when
// it leaves the world in some direction - so that is where the bodies belong.
struct SunMoonSky : IEnvironmentSampler {
    GradientSky dome;
    SunState sun;

    float3 radiance(float3 direction) {
        return dome.radiance(direction) + celestialRadiance(sun, direction);
    }

    float3 skyRadiance(float3 direction) {
        return dome.radiance(direction);
    }

    float pdf(float3 normal, float3 direction) {
        return dome.pdf(normal, direction);
    }

    SkySample sample(float3 normal, inout uint seed) {
        return dome.sample(normal, seed);
    }
};
