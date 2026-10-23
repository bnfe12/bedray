#version 120
#define DEPTH_VIEW 0 // [0 1]
uniform sampler2D colortex0, depthtex0;
uniform mat4 gbufferProjectionInverse;
varying vec2 texcoord;
#if DEPTH_VIEW == 1
uniform mat4 gbufferProjection, gbufferModelView, gbufferModelViewInverse;
uniform mat4 gbufferPreviousProjection, gbufferPreviousModelView;
uniform vec3 cameraPosition, previousCameraPosition;
#endif
/* DRAWBUFFERS:0 */
void main() {
    vec4 color = texture2D(colortex0, texcoord);
    float depth = texture2D(depthtex0, texcoord).r;
    if (depth >= 1.0) { gl_FragColor = color; return; }
    vec4 view = gbufferProjectionInverse * vec4(texcoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    view /= view.w;
#if DEPTH_VIEW == 1
    vec4 relativeWorld = gbufferModelViewInverse * view;
    vec4 currentClip = gbufferProjection * gbufferModelView * relativeWorld;
    vec3 previousRelative = relativeWorld.xyz + cameraPosition - previousCameraPosition;
    vec4 previousClip = gbufferPreviousProjection * gbufferPreviousModelView * vec4(previousRelative, 1.0);
    if (previousClip.w > 0.00001) {
        vec2 motion = currentClip.xy / currentClip.w - previousClip.xy / previousClip.w;
        color.rgb = mix(color.rgb, vec3(clamp(abs(motion) * 20.0, 0.0, 1.0), 0.0), 0.5);
    }
#else
    float haze = (1.0 - exp(-max(-view.z, 0.0) * 0.002)) * 0.2;
    color.rgb = mix(color.rgb, vec3(0.65, 0.75, 0.85), haze);
#endif
    gl_FragColor = color;
}
