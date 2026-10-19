#version 120
// SPDX-License-Identifier: MIT
/* RENDERTARGETS: 0 */
#include "lib/vignette.glsl"

uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;
uniform float frameTimeCounter;

varying vec4 texcoord;

void main() {
    vec4 color = texture2D(colortex0, texcoord.st);
    float vig = computeVignette(texcoord.st, 0.4);
    float pulse = 0.95 + 0.05 * sin(frameTimeCounter);
    gl_FragData[0] = vec4(color.rgb * vig * pulse, color.a);
}
