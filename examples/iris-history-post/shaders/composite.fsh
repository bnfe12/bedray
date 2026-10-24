#version 120
/*
const int colortex2Format = R16F;
const int colortex3Format = RGB8;
const int colortex5Format = RGBA16F;
const int colortex6Format = R11F_G11F_B10F;
const int colortex7Format = RG16;
const bool colortex5Clear = false;
const vec4 colortex4ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
*/
/* RENDERTARGETS:5,2,6,3,7,4 */
uniform sampler2D colortex0;
uniform sampler2D colortex5;
uniform float viewWidth;
uniform float viewHeight;
varying vec2 texcoord;

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    // colortex5 keeps its value from the previous frame (Clear = false): an exponential trail.
    vec3 history = texture2D(colortex5, texcoord).rgb;
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    vec2 centered = texcoord * 2.0 - 1.0;
    float vignette = clamp(1.0 - dot(centered, centered) * 0.35, 0.0, 1.0);
    gl_FragData[0] = vec4(mix(history, color, 0.12), 1.0);          // colortex5 RGBA16F, persistent
    gl_FragData[1] = vec4(luma, 0.0, 0.0, 1.0);                      // colortex2 R16F
    gl_FragData[2] = vec4(max(color - vec3(0.75), vec3(0.0)), 1.0);  // colortex6 R11F_G11F_B10F bright pass
    gl_FragData[3] = vec4(vec3(vignette), 0.0);                      // colortex3 RGB8: alpha is not stored
    gl_FragData[4] = vec4(texcoord, 0.0, 0.0);                       // colortex7 RG16
    gl_FragData[5] = vec4(vec3(1.0 / viewWidth, 1.0 / viewHeight, 0.0), 1.0); // colortex4
}
