#version 120
#include "/lib/color.glsl"
/* DRAWBUFFERS:0 */
uniform sampler2D colortex0;
uniform float viewWidth, viewHeight;
void main() {
    vec2 uv = gl_FragCoord.xy / vec2(viewWidth, viewHeight);
    gl_FragColor = vec4(bedrayExposure(texture2D(colortex0, uv).rgb), 1.0);
}
