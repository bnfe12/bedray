#version 130
// Mipmaps of colortex6 are generated before this program runs because it enables them.
const bool colortex6MipmapEnabled = true;
/* RENDERTARGETS:0 */
uniform sampler2D colortex0;
uniform sampler2D colortex3;
uniform sampler2D colortex5;
uniform sampler2D colortex6;
varying vec2 texcoord;

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    vec3 trail = texture2D(colortex5, texcoord).rgb;
    vec3 bloom = textureLod(colortex6, texcoord, 2.0).rgb * 0.5
               + textureLod(colortex6, texcoord, 4.0).rgb * 0.3
               + textureLod(colortex6, texcoord, 6.0).rgb * 0.2;
    // RGB8 colortex3 samples alpha as 1.0, as an OpenGL RGB8 texture does.
    vec4 vignette = texture2D(colortex3, texcoord);
    gl_FragData[0] = vec4((max(color, trail * 0.6) + bloom) * vignette.rgb * vignette.a, 1.0);
}
