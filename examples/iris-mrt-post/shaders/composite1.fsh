#version 120
/* DRAWBUFFERS:0 */
uniform sampler2D colortex3;
uniform sampler2D colortex2;
varying vec2 texcoord;
void main() {
    vec4 red = texture2D(colortex3, texcoord);
    vec4 cyan = texture2D(colortex2, texcoord);
    gl_FragData[0] = vec4(red.r, cyan.g, cyan.b, red.a);
}
