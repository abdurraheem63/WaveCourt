package com.wavecourt.render

/**
 * AGSL (Android Graphics Shader Language) source for the probability
 * density visualization.
 *
 * Uniforms:
 *  - `densityTexture`: the physics grid's |psi|^2 field, already
 *    normalized to [0, 1] by [DensityExtractor], uploaded as a small
 *    (128x128 / 256x256) single-channel bitmap.
 *  - `gridSize`: the density texture's own resolution in texels, used to
 *    map normalized screen UVs into the texture's coordinate space.
 *  - `resolution`: the on-screen draw size in pixels.
 *
 * Color ramp design: black -> yellow -> orange -> white is built from
 * three overlapping `smoothstep` bands rather than a single linear
 * gradient. A `pow(t, 0.55)` boost is applied first so low-density values
 * (the "wings" of the wavefunction, where racket interaction and
 * near-miss collapses actually happen) get real visual separation instead
 * of crushing toward black -- a plain linear ramp makes everything below
 * ~20% density look identically dark.
 *
 * Sampling: `eval()` on the input shader samples at whatever grain the
 * backing bitmap gives it, which at 128x128 upscaled to a full screen is
 * visibly blocky. Rather than rely on implicit filtering, `sampleSmooth`
 * manually reads the four nearest texels and blends them with a quintic
 * "smootherstep" curve (Perlin's ease curve) instead of straight linear
 * interpolation -- it costs 4 texture reads instead of 1, trivial on GPU,
 * and produces a visibly softer result than standard bilinear because the
 * blend weight eases in/out near texel boundaries instead of ramping
 * linearly across them.
 */
const val WAVE_DENSITY_SHADER = """
uniform shader densityTexture;
uniform float2 gridSize;
uniform float2 resolution;

half3 colorRamp(float t) {
    t = clamp(t, 0.0, 1.0);

    // Perceptual boost: lifts low/mid densities so the faint wings of the
    // wavefunction stay visible instead of crushing to black. 0.55 was
    // chosen empirically for good wing contrast without blowing out the
    // bright core too early -- tune here first if the look needs
    // adjustment.
    float boosted = pow(t, 0.55);

    half3 black  = half3(0.0, 0.0, 0.0);
    half3 yellow = half3(1.0, 0.85, 0.15);
    half3 orange = half3(1.0, 0.45, 0.05);
    half3 white  = half3(1.0, 1.0, 1.0);

    float toYellow = smoothstep(0.0, 0.35, boosted);
    float toOrange = smoothstep(0.35, 0.70, boosted);
    float toWhite  = smoothstep(0.70, 1.0, boosted);

    half3 color = mix(black, yellow, toYellow);
    color = mix(color, orange, toOrange);
    color = mix(color, white, toWhite);
    return color;
}

// Quintic ease (Perlin's "smootherstep"): zero first AND second derivative
// at both ends, so texel-to-texel transitions have no visible kink -- the
// main source of residual "blockiness" that plain bilinear still shows.
float2 smootherWeight(float2 f) {
    return f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
}

float sampleSmooth(float2 texCoord) {
    // Center the 2x2 sampling neighborhood on the nearest texel grid.
    float2 base = floor(texCoord - 0.5) + 0.5;
    float2 f = texCoord - base;
    float2 w = smootherWeight(f);

    float s00 = float(densityTexture.eval(base).a);
    float s10 = float(densityTexture.eval(base + float2(1.0, 0.0)).a);
    float s01 = float(densityTexture.eval(base + float2(0.0, 1.0)).a);
    float s11 = float(densityTexture.eval(base + float2(1.0, 1.0)).a);

    float top = mix(s00, s10, w.x);
    float bottom = mix(s01, s11, w.x);
    return mix(top, bottom, w.y);
}

half4 main(float2 fragCoord) {
    // Normalize to the draw surface, then re-project into the density
    // texture's own texel space (still fractional -- sampleSmooth handles
    // the interpolation from here, so no bilinear dependency on the
    // backing BitmapShader's own filtering behavior).
    float2 uv = fragCoord / resolution;
    float2 texCoord = uv * gridSize;

    // densityTexture is an ALPHA_8 bitmap (density packed into alpha, RGB
    // channels unused) -- see DensityShaderBrush. sampleSmooth reads `.a`.
    float density = sampleSmooth(texCoord);

    half3 rgb = colorRamp(density);
    return half4(rgb, 1.0);
}
"""