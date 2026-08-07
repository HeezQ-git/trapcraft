#version 150

// Temporal accumulation blur -- real motion blur, not a gaussian.
//
// Each frame is mixed with the accumulated previous frame. Anything that stays
// put converges to itself and reads perfectly sharp; anything that MOVES leaves
// a trail behind it. That's the difference from a box/gaussian blur, which
// smears the whole image uniformly whether it moved or not.
//
// PrevSampler comes from a target declared "persistent": true, so it survives
// between frames instead of being cleared.

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

layout(std140) uniform MotionBlurConfig {
    float Blend;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 current = texture(InSampler, texCoord);
    vec4 previous = texture(PrevSampler, texCoord);

    // Blend is how much of the OLD frame survives: higher = longer trails.
    fragColor = vec4(mix(current.rgb, previous.rgb, Blend), 1.0);
}
