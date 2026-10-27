#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 paper = texture(Sampler0, texCoord0);
    if (paper.a < 0.94) discard;
    fragColor = vec4(paper.rgb, 1.0) * ColorModulator;
}
