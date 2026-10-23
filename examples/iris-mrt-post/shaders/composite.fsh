#version 120
/* RENDERTARGETS:3,2 */
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
    vec4 color = texture2D(colortex0, texcoord);
    // Output location 0 is routed to logical colortex3, location 1 to colortex2.
    gl_FragData[0] = vec4(color.r, 0.0, 0.0, color.a);
    gl_FragData[1] = vec4(0.0, color.g, color.b, color.a);
}
