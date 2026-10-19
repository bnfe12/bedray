#version 120
// SPDX-License-Identifier: MIT
varying vec4 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0;
}
