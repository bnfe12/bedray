#version 120
uniform sampler2D colortex0;
uniform sampler2D colortex2;
varying vec2 texcoord;

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    float luma = texture2D(colortex2, texcoord).r;
    // Gentle saturation boost around the frame's own luminance.
    gl_FragColor = vec4(mix(vec3(luma), color, 1.15), 1.0);
}
