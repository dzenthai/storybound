#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float CleanSheetContour;

in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec4 paper = texture(Sampler0, texCoord0);
    if (paper.a < 0.94) discard;
    if (CleanSheetContour > 0.5) {
        // Reject isolated export debris without touching connected ink or dithering.
        vec2 texel = 1.0 / vec2(textureSize(Sampler0, 0));
        vec2 center = (floor(texCoord0 / texel) + 0.5) * texel;
        float neighbor = max(
            max(texture(Sampler0, center + vec2(texel.x, 0.0)).a,
                texture(Sampler0, center - vec2(texel.x, 0.0)).a),
            max(texture(Sampler0, center + vec2(0.0, texel.y)).a,
                texture(Sampler0, center - vec2(0.0, texel.y)).a));
        if (neighbor < 0.94) discard;
    }
    fragColor = vec4(paper.rgb, 1.0) * ColorModulator;
}
