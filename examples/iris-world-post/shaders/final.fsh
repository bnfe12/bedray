#version 120
// Pack constants read the way Iris reads them (const directives).
const float sunPathRotation = -30.0;
const float wetnessHalflife = 300.0;
const float drynessHalflife = 100.0;

uniform sampler2D colortex0;
uniform mat4 gbufferProjection;
uniform vec3 sunPosition;
uniform vec3 moonPosition;
uniform vec3 shadowLightPosition;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float sunAngle;
uniform float rainStrength;
uniform float wetness;
uniform float nightVision;
uniform float blindness;
uniform float darknessFactor;
uniform int worldTime;
uniform int moonPhase;
uniform int isEyeInWater;
uniform ivec2 eyeBrightnessSmooth;
uniform float viewWidth;
uniform float viewHeight;
varying vec2 texcoord;

vec2 toScreen(vec3 viewPos) {
    vec4 clip = gbufferProjection * vec4(viewPos, 1.0);
    return clip.xy / clip.w * 0.5 + 0.5;
}

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    float skyLight = float(eyeBrightnessSmooth.y) / 240.0;

    // Glow around the sun or moon when it is in front of the camera; stronger with open sky above the player.
    vec3 light = normalize(shadowLightPosition);
    if (light.z < 0.0) {
        vec2 aspect = vec2(viewWidth / viewHeight, 1.0);
        float d = length((toScreen(shadowLightPosition) - texcoord) * aspect);
        float day = sunAngle < 0.5 ? 1.0 : 0.25;
        color += mix(skyColor, vec3(1.0, 0.9, 0.7), 0.5) * exp(-d * 14.0) * 0.35 * day * skyLight;
    }

    // Rain darkens and desaturates; wetness lingers after the rain stops (Iris smoothing).
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(color, vec3(luma) * 0.85, wetness * 0.35 + rainStrength * 0.1);

    // Nights are cooler unless night vision is active; the moon phase dims a little.
    bool night = worldTime > 13000 && worldTime < 23000;
    if (night) color *= mix(vec3(0.85, 0.9, 1.05) * (1.0 - float(moonPhase) * 0.01), vec3(1.0), nightVision);

    // Fluids: tint toward the fog color, weighted by how short the fog range is.
    if (isEyeInWater > 0) {
        float density = clamp(16.0 / max(fogEnd - fogStart, 1.0), 0.0, 1.0);
        color = mix(color, fogColor, 0.35 + 0.4 * density);
    }

    // Blindness and darkness close in from the screen edges.
    vec2 centered = texcoord * 2.0 - 1.0;
    float edge = clamp(dot(centered, centered), 0.0, 1.0);
    color *= 1.0 - max(blindness, darknessFactor) * mix(0.3, 0.95, edge);

#if MC_VERSION >= 260000
    gl_FragColor = vec4(color, 1.0);
#else
    gl_FragColor = vec4(1.0, 0.0, 1.0, 1.0); // older game versions are not expected on this host
#endif
}
