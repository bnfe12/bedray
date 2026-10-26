#version 120
#include "/lib/color.glsl"
/* DRAWBUFFERS:0 */
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    color = bedrayPostColor(color);
    gl_FragColor = vec4(color, 1.0);
}
