#version 120
/* RENDERTARGETS:0 */
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
    vec4 color = texture2D(colortex0, texcoord);
    vec2 offset = texcoord - vec2(0.5);
    float vignette = 1.0 - 0.15 * dot(offset, offset);
    gl_FragColor = vec4(color.rgb * vignette, color.a);
}
