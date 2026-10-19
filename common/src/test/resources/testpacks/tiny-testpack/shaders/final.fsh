#version 120
// SPDX-License-Identifier: MIT
/* RENDERTARGETS: 0 */
uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;

varying vec4 texcoord;

void main() {
    vec4 color = texture2D(colortex0, texcoord.st);
    gl_FragColor = color;
}
