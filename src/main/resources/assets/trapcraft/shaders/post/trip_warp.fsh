#version 150

#moj_import <minecraft:globals.glsl>

// The world image itself, bent.
//
// Everything before this shader painted ON TOP of the frame -- washes,
// vignettes, ripples drawn as rectangles over the finished picture. This one
// resamples the frame, so the world genuinely moves: walls breathe, the middle
// distance twists, and the colour channels come apart at the edges. That's the
// difference between "there's a green filter on my screen" and "the room is
// doing something".
//
// Animation rides on GameTime, which is the only clock a post shader can see.
// It's the world's day fraction, so it advances whenever the daylight cycle
// does. With the cycle frozen the warp holds still and reads as a static lens
// -- degraded, but not broken, and the camera and overlay are still moving.

uniform sampler2D InSampler;

layout(std140) uniform TripConfig {
    float Warp;    // ripple displacement, screen fraction
    float Swirl;   // radial twist at the centre, radians
    float Split;   // chromatic aberration at the edge, screen fraction
    float Spin;    // hue rotation, degrees
    float Pulse;   // how fast the whole thing breathes
    float Mirror;  // kaleidoscope segments, 0 = off
    float Ghost;   // how much of the mirrored image bleeds through, 0..1
    float Echo;    // concentric zoom echoes, 0 = off
    float Poster;  // colour quantisation steps, 0 = off
    float Sat;     // saturation multiplier, 1 = untouched
};

in vec2 texCoord;

out vec4 fragColor;

// Luminance-preserving hue rotation. Spinning the chroma plane rather than
// round-tripping through HSV keeps it branch-free and avoids the banding you
// get from quantising hue back to 8-bit every frame.
vec3 spinHue(vec3 c, float radians_) {
    float cs = cos(radians_);
    float sn = sin(radians_);
    mat3 m = mat3(
        0.213 + cs * 0.787 - sn * 0.213, 0.213 - cs * 0.213 + sn * 0.143, 0.213 - cs * 0.213 - sn * 0.787,
        0.715 - cs * 0.715 - sn * 0.715, 0.715 + cs * 0.285 + sn * 0.140, 0.715 - cs * 0.715 + sn * 0.715,
        0.072 - cs * 0.072 + sn * 0.928, 0.072 - cs * 0.072 - sn * 0.283, 0.072 + cs * 0.928 + sn * 0.072
    );
    return clamp(m * c, 0.0, 1.0);
}

void main() {
    // GameTime is 0..1 across a 24000-tick day. Scaled to roughly one unit per
    // second so the frequencies below are readable numbers.
    float t = GameTime * 1200.0;

    vec2 centred = texCoord - 0.5;
    float aspect = ScreenSize.x / max(ScreenSize.y, 1.0);
    float r = length(vec2(centred.x * aspect, centred.y));

    // Twist, strongest at the centre and easing out to nothing at the edges.
    // The other way round -- twisting the rim -- reads as a broken screen
    // rather than as the room moving.
    float angle = Swirl * (1.0 - smoothstep(0.0, 0.8, r)) * sin(t * 0.35 * Pulse);
    float ca = cos(angle);
    float sa = sin(angle);
    vec2 twisted = vec2(centred.x * ca - centred.y * sa,
                        centred.x * sa + centred.y * ca);

    // Two ripples per axis whose periods don't divide evenly, so the pattern
    // never lines up with itself twice and never looks like a loop.
    vec2 wobble = vec2(
        sin(twisted.y * 11.0 + t * 0.90 * Pulse) + 0.6 * sin(twisted.y * 27.0 - t * 0.50 * Pulse),
        cos(twisted.x *  9.0 - t * 0.70 * Pulse) + 0.6 * cos(twisted.x * 23.0 + t * 0.43 * Pulse)
    ) * Warp;

    vec2 base = clamp(twisted + wobble + 0.5, 0.0, 1.0);

    // Channel split pushed radially outward, scaled by distance from centre --
    // so it's zero exactly where your crosshair is and you can still aim.
    vec2 dir = normalize(centred + vec2(1e-6)) * Split * r;
    vec3 col;
    col.r = texture(InSampler, clamp(base + dir, 0.0, 1.0)).r;
    col.g = texture(InSampler, base).g;
    col.b = texture(InSampler, clamp(base - dir, 0.0, 1.0)).b;

    // --- the blend-only layers ------------------------------------------
    //
    // Reserved for mixes rather than single strains: they're the reward for
    // going to the trouble of blending, and they're strong enough that having
    // them on every joint would wear out fast.

    // Kaleidoscope. Folded in polar space, then BLENDED over the straight
    // image rather than replacing it -- a full mirror is genuinely unplayable,
    // you can't tell where you're walking. A ghost of it is all the effect
    // you actually want.
    if (Mirror > 0.5 && Ghost > 0.001) {
        vec2 p = vec2(centred.x * aspect, centred.y);
        float seg = 6.28318530718 / Mirror;
        float a = mod(atan(p.y, p.x) + t * 0.06 * Pulse, seg);
        a = abs(a - seg * 0.5);          // mirror within the wedge
        p = vec2(cos(a), sin(a)) * length(p);
        vec2 folded = clamp(vec2(p.x / aspect, p.y) + 0.5, 0.0, 1.0);
        col = mix(col, texture(InSampler, folded).rgb, Ghost);
    }

    // Concentric echoes: the same frame at two other scales, breathing in and
    // out of each other. Reads as the world tunnelling away from you.
    if (Echo > 0.001) {
        float beat = 0.06 * sin(t * 0.29 * Pulse);
        vec3 inner = texture(InSampler, clamp(centred * (0.86 - beat) + 0.5, 0.0, 1.0)).rgb;
        vec3 outer = texture(InSampler, clamp(centred * (1.20 + beat) + 0.5, 0.0, 1.0)).rgb;
        col = (col + (inner + outer) * Echo * 0.5) / (1.0 + Echo);
    }

    // Posterise. Collapsing the world to flat bands of colour is the single
    // most "this is not a normal screen" thing available, and it costs one
    // instruction.
    if (Poster > 1.5) {
        float steps = Poster + 3.0 * sin(t * 0.13 * Pulse);
        col = floor(col * steps + 0.5) / steps;
    }

    // Saturation last, so it governs everything above it. Below 1 this is the
    // coca crash draining the colour out of the world; above 1 it's a blend
    // pushing it past what the game normally shows you.
    if (abs(Sat - 1.0) > 0.001) {
        col = mix(vec3(dot(col, vec3(0.299, 0.587, 0.114))), col, Sat);
    }

    fragColor = vec4(spinHue(col, radians(Spin) * sin(t * 0.21 * Pulse)), 1.0);
}
