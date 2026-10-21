#version 120
uniform sampler2D colortex0;
varying vec2 texcoord;
void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    // Deliberately small, color-only post effect. No world/G-buffer/depth assumptions.
    color = clamp((color - 0.5) * 1.05 + 0.5, 0.0, 1.0);
    gl_FragColor = vec4(color, 1.0);
}
