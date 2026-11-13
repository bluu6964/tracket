// GLSL ES 3.00 sources of the v140 effects. Four programs, each with a `uMode` branch (see fxShaderOf in fx_plan.cpp):
//   kFxBlur2  : 0 directional blur, 1 radial blur (spin / zoom), 2 sharpen, 3 lens blur, 4 tilt-shift, 5 surface blur, 6 light rays,
//               7 long shadow, 8 spin blur (Alight), 9 streaks (Alight), 10 / 11 / 12 motion blur 4 position / scale / angle (Alight),
//               13 denoise (Alight), 14 cinematic bokeh (Alight), 15 lens blur obs (Alight), 16 chromatic vortex blur (Alight),
//               17 twitch / smart motion blur shake (Alight)
//   kFxColor  : 0 exposure, 1 brightness & contrast, 2 hue / saturation, 3 levels, 4 colour balance, 5 tint, 6 posterize,
//               7 invert, 8 black & white, 9 threshold, 10 temperature, 11 shadows / highlights, 12 colour pop, 13 cinematic, 14 solarize,
//               15 colour tune (Alight), 16 solid colour (Alight), 17 replace colour (Alight), 18 colour balance (Alight), 19 colour temperature (Alight),
//               20 colour tint (Alight), 21 colour hot (Alight), 22 colorize (Alight), 23 highlights & shadows (Alight), 24 spot colour (Alight),
//               25 threshold (Alight), 26 spectral map (Alight), 27 negative (Alight), 28 tritone (Alight)
//   kFxWarp   : 0 wave warp, 1 twirl, 2 bulge, 3 mirror, 4 kaleidoscope, 5 turbulent displace, 6 ripple, 7 lens distortion, 8 polar, 9 tile, 10 camera shake,
//               11 Alight mirror (whole-layer flip + blend), 12 stretch segment (Alight), 13 swirl (Alight), 14 halftone dots (Alight), 15 offset (Alight),
//               16 radial wipe (Alight), 17 hex shift (Alight), 18 wave warp 2 (Alight), 19 circular ripple (Alight), 20 stretch (Alight), 21 squeeze (Alight),
//               22 fractal warp (Alight), 23 stripes (Alight), 24 turbulent displace 3 (Alight), 25 roughen edges (Alight), 26 bend warp (Alight),
//               27 halftone lines (Alight), 28 rgb glitch (Alight), 29 fisheye / anti-fisheye (Alight), 30 refraction warp (Alight),
//               31 turbulent displace 2 (Alight), 32 cc glass (Alight), 33 solid colour wipe (Alight)
//   kFxStyle  : 0 mosaic, 1 halftone, 2 scanlines, 3 glitch, 4 emboss, 5 find edges, 6 cartoon, 7 sketch, 8 oil paint, 9 strobe, 10 VHS, 11 old film, 12 light leak, 13 lens flare,
//               14 find edges (Alight), 15 dissolve (Alight), 16 simple stars (Alight), 17 dots (Alight), 18 ridges (Alight),
//               19 clouds / turbulence 2 (Alight), 20 checkerboard 2 (Alight), 21 shape field (Alight), 22 halftone dots (Alight)
// All of them work on the premultiplied picture the layer shader uses (top of the picture at v = 1), take the raw slider
// values in uP[0..6] (documented at FxType in fx_plan.h) and write a premultiplied picture. Texture reads inside loops
// and branches use textureLod (the pictures have no mip levels), so there are no undefined derivatives.
#pragma once

// Same dither as the older effect shaders: removes banding of smooth gradients in the 8 bit pictures.
#define AM_FX_DITHER                                                                  \
    "float amHash(vec2 p) {\n"                                                        \
    "    vec3 p3 = fract(vec3(p.xyx) * 0.1031);\n"                                    \
    "    p3 += dot(p3, p3.yzx + 33.33);\n"                                            \
    "    return fract((p3.x + p3.y) * p3.z);\n"                                       \
    "}\n"                                                                             \
    "float amDither() {\n"                                                            \
    "    return (amHash(gl_FragCoord.xy) + amHash(gl_FragCoord.xy + 17.17) - 1.0) / 255.0;\n" \
    "}\n"

namespace am {
namespace fxsrc {

// ---------------------------------------------------------------------------------------------------------------------
// Blur 2: directional blur, radial / zoom blur, sharpen
// ---------------------------------------------------------------------------------------------------------------------
inline const char* kBlur2 = "#version 300 es\n"
R"(precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uAspect;
uniform float uUnit;
uniform int uFrame;
uniform int uMode;
uniform float uP[7];
uniform float uQ[7];
in vec2 vUv;
out vec4 oColor;

float ign(vec2 p) {  // interleaved gradient noise: a stable, evenly spread per-pixel jitter
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}
vec4 tap(vec2 uv) { return textureLod(uTex, uv, 0.0); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
// hue 0..360, full saturation and value
vec3 hueRgb(float h) {
    vec3 k = vec3(1.0, 2.0 / 3.0, 1.0 / 3.0);
    vec3 p = abs(fract(vec3(h / 360.0) + k) * 6.0 - 3.0);
    return clamp(p - 1.0, 0.0, 1.0);
}

void main() {
    vec4 c0 = tap(vUv);
    if (uMode == 0) {
        // Directional blur: a box along the angle (AE "Directional Blur"), optional one-sided comet trail.
        float lenPx = uP[1] / 100.0 * 160.0 * uUnit;
        if (lenPx < 0.5) { oColor = c0; return; }
        float a = radians(uP[0]);
        vec2 dir = vec2(cos(a), -sin(a));  // screen y points down, the angle is counter-clockwise like AE
        float trail = clamp(uP[2] / 100.0, 0.0, 1.0);
        const int N = 48;
        float jit = ign(gl_FragCoord.xy) - 0.5;
        vec4 acc = vec4(0.0);
        float wsum = 0.0;
        for (int i = 0; i < N; i++) {
            float s = (float(i) + 0.5 + jit) / float(N) * 2.0 - 1.0;  // -1 .. 1
            float pos = mix(s, (s + 1.0) * 0.5, trail);              // trail = 1: only ahead of the pixel
            float w = mix(1.0, 1.0 - 0.92 * (s + 1.0) * 0.5, trail);  // comet: fades along the tail
            w *= 1.0 - smoothstep(0.82, 1.0, abs(s));                 // soft ends, no hard box edge
            acc += tap(vUv + dir * pos * lenPx * uTexel) * w;
            wsum += w;
        }
        oColor = acc / max(wsum, 1e-4);
    } else if (uMode == 1) {
        // Radial blur: every tap turns (spin) and / or scales (zoom) the sample position around the centre.
        float amt = clamp(uP[0] / 100.0, 0.0, 1.0);
        if (amt < 0.005) { oColor = c0; return; }
        float zoom = clamp(uP[1] / 100.0, 0.0, 1.0);
        vec2 cen = vec2(uP[2] / 100.0, 1.0 - uP[3] / 100.0);
        vec2 q = (vUv - cen) * vec2(uAspect, 1.0);
        const int N = 48;
        float jit = ign(gl_FragCoord.xy) - 0.5;
        vec4 acc = vec4(0.0);
        float wsum = 0.0;
        for (int i = 0; i < N; i++) {
            float s = (float(i) + 0.5 + jit) / float(N) * 2.0 - 1.0;
            float ang = s * amt * 0.5 * (1.0 - zoom);
            float sc = 1.0 + s * amt * 0.30 * zoom;
            float cs = cos(ang), sn = sin(ang);
            vec2 r = vec2(q.x * cs - q.y * sn, q.x * sn + q.y * cs) * sc;
            float w = 1.0 - 0.6 * s * s;
            acc += tap(cen + r / vec2(uAspect, 1.0)) * w;
            wsum += w;
        }
        oColor = acc / max(wsum, 1e-4);
    } else if (uMode == 2) {
        // Sharpen: unsharp mask on the luminance only (no colour fringes), noise threshold, halo control.
        float amt = clamp(uP[0], 0.0, 300.0) / 100.0;
        if (amt < 0.005) { oColor = c0; return; }
        float radius = (0.6 + clamp(uP[1], 0.0, 100.0) * 0.09) * uUnit;  // px
        float thr = clamp(uP[2], 0.0, 100.0) / 100.0 * 0.12;
        float halo = clamp(uP[3], 0.0, 100.0) / 100.0;
        float l0 = luma(c0.rgb);
        float sum = 0.0, wsum = 0.0;
        float mn = l0, mx = l0;
        const int N = 32;
        for (int i = 0; i < N; i++) {
            float fi = float(i) + 0.5;
            float r = sqrt(fi / float(N));
            float a = fi * 2.39996323;
            vec2 o = vec2(cos(a), sin(a)) * r * radius * uTexel;
            float w = exp(-1.6 * r * r);  // gaussian-like falloff over the disc
            float l = luma(tap(vUv + o).rgb);
            sum += l * w;
            wsum += w;
            mn = min(mn, l);
            mx = max(mx, l);
        }
        sum += l0 * 1.0;
        wsum += 1.0;
        float blur = sum / wsum;
        float d = l0 - blur;
        // below the threshold the difference fades out, so noise is not sharpened
        float k = thr > 0.0001 ? smoothstep(thr * 0.5, thr * 1.5, abs(d)) : 1.0;
        float target = l0 + d * amt * k;
        float slack = (1.0 - halo) * 0.25;
        target = clamp(target, mn - slack, mx + slack);
        vec3 rgb = c0.rgb + (target - l0);
        oColor = vec4(clamp(rgb, 0.0, c0.a), c0.a);
    } else if (uMode == 3) {
        // Lens blur: a disc of taps (bokeh), bright spots get more weight so highlights bloom into discs.
        float R = clamp(uP[0], 0.0, 100.0) / 100.0 * 40.0 * uUnit;
        if (R < 0.5) { oColor = c0; return; }
        float boost = clamp(uP[1], 0.0, 100.0) / 100.0;
        float thr = clamp(uP[2], 0.0, 100.0) / 100.0;
        const int N = 64;
        float rot = ign(gl_FragCoord.xy) * 6.2831853;
        vec4 acc = vec4(0.0);
        float wsum = 0.0;
        for (int i = 0; i < N; i++) {
            float fi = float(i) + 0.5;
            float r = sqrt(fi / float(N));
            float a = fi * 2.39996323 + rot;
            vec4 s = tap(vUv + vec2(cos(a), sin(a)) * r * R * uTexel);
            vec3 sc = s.a > 0.001 ? s.rgb / s.a : vec3(0.0);
            float l = max(sc.r, max(sc.g, sc.b));
            float w = 1.0 + boost * 7.0 * smoothstep(thr, thr + 0.15, l);
            acc += s * w;
            wsum += w;
        }
        oColor = acc / max(wsum, 1e-4);
    } else if (uMode == 4) {
        // Tilt-shift: a sharp band across the picture, blur grows away from it.
        float amt = clamp(uP[0], 0.0, 100.0) / 100.0;
        if (amt < 0.005) { oColor = c0; return; }
        float ang = radians(uP[3]);
        vec2 p = (vUv - 0.5) * vec2(uAspect, 1.0);
        vec2 nrm = vec2(-sin(ang), cos(ang));
        vec2 cen = vec2(0.0, 0.5 - uP[1] / 100.0);
        float d = abs(dot(p - cen, nrm));
        float wd = clamp(uP[2], 0.0, 100.0) / 100.0 * 0.45;
        float tr = 0.03 + clamp(uP[4], 0.0, 100.0) / 100.0 * 0.5;
        float k = smoothstep(wd, wd + tr, d);
        float R = k * amt * 36.0 * uUnit;
        if (R < 0.4) { oColor = c0; return; }
        const int N = 40;
        float rot = ign(gl_FragCoord.xy) * 6.2831853;
        vec4 acc = c0;
        float wsum = 1.0;
        for (int i = 0; i < N; i++) {
            float fi = float(i) + 0.5;
            float r = sqrt(fi / float(N));
            float a = fi * 2.39996323 + rot;
            acc += tap(vUv + vec2(cos(a), sin(a)) * r * R * uTexel);
            wsum += 1.0;
        }
        oColor = acc / wsum;
    } else if (uMode == 5) {
        // Surface blur: a blur that does not cross edges (taps that differ a lot in colour count for little) = smooth skin.
        float R = clamp(uP[0], 0.0, 100.0) / 100.0 * 20.0 * uUnit;
        if (R < 0.5) { oColor = c0; return; }
        float thr = 0.02 + clamp(uP[1], 0.0, 100.0) / 100.0 * 0.35;
        vec3 b0 = c0.a > 0.001 ? c0.rgb / c0.a : vec3(0.0);
        const int N = 40;
        float rot = ign(gl_FragCoord.xy) * 6.2831853;
        vec4 acc = c0;
        float wsum = 1.0;
        for (int i = 0; i < N; i++) {
            float fi = float(i) + 0.5;
            float r = sqrt(fi / float(N));
            float a = fi * 2.39996323 + rot;
            vec4 s = tap(vUv + vec2(cos(a), sin(a)) * r * R * uTexel);
            vec3 sc = s.a > 0.001 ? s.rgb / s.a : b0;
            vec3 dd = sc - b0;
            float w = exp(-dot(dd, dd) / (thr * thr)) * exp(-1.2 * r * r);
            acc += s * w;
            wsum += w;
        }
        oColor = acc / max(wsum, 1e-4);
    } else if (uMode == 6) {
        // Light rays: bright parts of the picture are smeared towards a centre point and added on top.
        float thr = clamp(uP[0], 0.0, 100.0) / 100.0;
        float len = clamp(uP[1], 0.0, 100.0) / 100.0;
        float inten = clamp(uP[2], 0.0, 300.0) / 100.0;
        vec2 cen = vec2(uP[3] / 100.0, 1.0 - uP[4] / 100.0);
        vec2 dv = cen - vUv;
        const int N = 48;
        float jit = ign(gl_FragCoord.xy);
        vec3 acc = vec3(0.0);
        for (int i = 0; i < N; i++) {
            float t = (float(i) + jit) / float(N);
            vec4 s = tap(vUv + dv * t * len);
            vec3 sc = s.a > 0.001 ? s.rgb / s.a : vec3(0.0);
            float l = max(sc.r, max(sc.g, sc.b));
            float k = smoothstep(thr, thr + 0.2, l) * s.a;
            acc += sc * k * (1.0 - t);
        }
        vec3 rays = acc / float(N) * inten;
        rays = mix(rays, rays * hueRgb(uP[5]) * 1.8, clamp(uP[6], 0.0, 100.0) / 100.0);
        float ra = max(rays.r, max(rays.g, rays.b));
        float a = clamp(c0.a + ra * (1.0 - c0.a), 0.0, 1.0);
        oColor = vec4(min(c0.rgb + rays, vec3(a)), a);
    } else if (uMode == 8) {
        // Spin blur (Alight Motion's spinblur2, its recovered shader): taps rotated over
        // [-angle/2, +angle/2] around the centre, their count from the arc length at the
        // fragment's own radius, clamped 1..100 — AM's loop bound is an int uniform there, a
        // break here, same taps. uP[0] is the angle in degrees, uP[1] / uP[2] the centre
        // (AM units, /500 from the layer centre, y up). Samples outside the layer clamp to
        // the edge, as AM's texture does.
        float angDeg = uP[0];
        vec2 cen = vec2(0.5 + uP[1] / 500.0, 0.5 - uP[2] / 500.0);
        vec2 v = vUv - cen;
        v.y /= uAspect;
        float r = length(v);
        float l = 6.283185 * r * angDeg / 360.0;
        float a = angDeg * 0.0174533;
        float amin = a / 2.0;
        int nSamples = int(clamp(l / uTexel.x, 1.01, 100.01));
        vec4 acc = c0;
        for (int i = 1; i < 100; i++) {
            if (i >= nSamples) break;
            float sampleAngle = -amin + a * float(i) / float(nSamples);
            float s = sin(sampleAngle);
            float c = cos(sampleAngle);
            mat2 rot = mat2(c, -s, s, c);
            vec2 temp = v * rot;
            temp.y *= uAspect;
            acc += tap(cen + temp);
        }
        oColor = acc / float(nSamples);
    } else if (uMode == 9) {
        // Streaks (Alight Motion's streaks, its recovered shader): a directional multi-tap
        // smear whose taps combine per channel (modes 0 min, 1 max, 2 off, 3 average:
        // R/G/B in uP[4..6], A in uQ[0]). uP[0] is the strength (AM raw 0..1), uP[1] the
        // angle in degrees, uP[2] the bias, uP[3] the final mix. The seed is the UNpremultiplied pixel, the taps are read raw
        // and zeroed outside the layer, exactly like AM. fxStreaksAmVelocity /
        // fxStreaksAmCombine / fxStreaksAmFinish in fx_plan.cpp are the CPU twins.
        float rad = uP[1] * 0.0174533;
        vec2 velocity = vec2(cos(rad) * uP[0], -sin(rad) * uP[0]);
        velocity.x *= uTexel.x / uTexel.y;
        float speed = length(velocity / uTexel);
        int nSamples = int(clamp(speed, 1.01, 100.01));
        vec4 outColor = c0;
        outColor.rgb = outColor.a > 0.0 ? outColor.rgb / outColor.a : outColor.rgb;
        for (int i = 1; i < 100; i++) {
            if (i >= nSamples) break;
            vec2 offset = velocity * (float(i) / float(nSamples - 1) - (0.5 + uP[2] / 2.0));
            vec2 sc = vUv - offset;
            float inside = step(0.0, sc.x) * step(sc.x, 1.0) * step(0.0, sc.y) * step(sc.y, 1.0);
            vec4 tc = textureLod(uTex, sc, 0.0) * inside;
            outColor.r = uP[4] < 0.5 ? min(outColor.r, tc.r) : uP[4] < 1.5 ? max(outColor.r, tc.r) : uP[4] > 2.5 ? outColor.r + tc.r : outColor.r;
            outColor.g = uP[5] < 0.5 ? min(outColor.g, tc.g) : uP[5] < 1.5 ? max(outColor.g, tc.g) : uP[5] > 2.5 ? outColor.g + tc.g : outColor.g;
            outColor.b = uP[6] < 0.5 ? min(outColor.b, tc.b) : uP[6] < 1.5 ? max(outColor.b, tc.b) : uP[6] > 2.5 ? outColor.b + tc.b : outColor.b;
            outColor.a = uQ[0] < 0.5 ? min(outColor.a, tc.a) : uQ[0] < 1.5 ? max(outColor.a, tc.a) : uQ[0] > 2.5 ? outColor.a + tc.a : outColor.a;
        }
        outColor /= vec4(uP[4] > 2.5 ? float(nSamples) : 1.0, uP[5] > 2.5 ? float(nSamples) : 1.0,
                         uP[6] > 2.5 ? float(nSamples) : 1.0, uQ[0] > 2.5 ? float(nSamples) : 1.0);
        outColor.rgb *= outColor.a;
        oColor = mix(c0, outColor, clamp(uP[3], 0.0, 1.0));
    } else if (uMode == 10) {
        // Motion Blur 4, position stage (Alight Motion's motionblur4, its recovered def):
        // a plain average of taps along the layer's velocity over one frame — the pixel
        // itself plus taps at velocity * (i / (n - 1) - 0.5) — their count from the
        // trail's length in texels: n = int(clamp(speed, samplesMin + 0.01,
        // samplesMax + 0.01)) (fxMb4Samples in fx_plan.cpp is the CPU twin of the count).
        // uP[0..1] is the picture-space velocity as fractions of the canvas height per
        // frame (MotionBlurEval resolves it), uP[2] the tune, uP[3..4] the sample bounds.
        // The loop bound is the def's samplesMax ceiling (256).
        vec2 vel = vec2(uP[0], uP[1]) * uP[2] / uTexel.y;   // texels per frame, y down in the picture
        int nSamples = int(clamp(length(vel), uP[3] + 0.01, uP[4] + 0.01));
        vec4 acc = c0;
        for (int i = 1; i < 256; i++) {
            if (i >= nSamples) break;
            vec2 off = vel * (float(i) / float(nSamples - 1) - 0.5);   // texels, picture space
            acc += tap(vUv - vec2(off.x * uTexel.x, -off.y * uTexel.y));
        }
        oColor = acc / float(max(nSamples, 1));
    } else if (uMode == 11) {
        // Motion Blur 4, scale stage: taps along the ray from the layer's pivot through
        // the pixel, spread over the frame's scale change (total span |svel * tune| *
        // dist, taps at span * (i / (n - 1) - 0.5) like the def), the count from
        // |svel * tune| * dist / 2 — motionblur4's own stage-1 count omits the texel
        // factor its sibling stages carry; moblurplus's hybrid stage (the same def
        // family) pins the restored law (fxMb4ScaleSpeed). uP[0] is the relative scale
        // change per frame, uP[1] the tune, uP[2..3] the pivot (%), uP[4..5] the bounds.
        vec2 cen = vec2(uP[2] / 100.0, 1.0 - uP[3] / 100.0);
        vec2 vp = vec2((vUv.x - cen.x) / uTexel.x, -(vUv.y - cen.y) / uTexel.y);   // texels, picture space (y down)
        float dist = length(vp);
        float svel = abs(uP[0] * uP[1]);
        int nSamples = int(clamp(svel * dist / 2.0, uP[4] + 0.01, uP[5] + 0.01));
        vec4 acc = c0;
        if (dist > 0.001) {
            vec2 dir = vp / dist;
            float span = svel * dist;
            for (int i = 1; i < 256; i++) {
                if (i >= nSamples) break;
                vec2 off = dir * span * (float(i) / float(nSamples - 1) - 0.5);
                acc += tap(vUv - vec2(off.x * uTexel.x, -off.y * uTexel.y));
            }
        }
        oColor = acc / float(max(nSamples, 1));
    } else if (uMode == 12) {
        // Motion Blur 4, angle stage: taps rotated over [-angle / 2, +angle / 2] around
        // the layer's pivot, angle = |angularVelocity * tune| (degrees per frame), the
        // count from the arc length at the fragment's own radius — spin blur's form
        // (mode 8) with motionblur4's pivot, tune and sample bounds (fxMb4AngleSpeed /
        // fxMb4Samples). uP[0] is the rotation change per frame (degrees, signed: the
        // tap set is symmetric, so the sign cannot matter), uP[1] the tune, uP[2..3]
        // the pivot (%), uP[4..5] the bounds.
        float angDeg = abs(uP[0] * uP[1]);
        vec2 cen = vec2(uP[2] / 100.0, 1.0 - uP[3] / 100.0);
        vec2 v = vUv - cen;
        v.y /= uAspect;
        float r = length(v);
        float l = 6.283185 * r * angDeg / 360.0;
        float a = angDeg * 0.0174533;
        float amin = a / 2.0;
        int nSamples = int(clamp(l / uTexel.x, uP[4] + 0.01, uP[5] + 0.01));
        vec4 acc = c0;
        for (int i = 1; i < 256; i++) {
            if (i >= nSamples) break;
            float sampleAngle = -amin + a * float(i) / float(nSamples);
            float s = sin(sampleAngle);
            float c = cos(sampleAngle);
            mat2 rot = mat2(c, -s, s, c);
            vec2 temp = v * rot;
            temp.y *= uAspect;
            acc += tap(cen + temp);
        }
        oColor = acc / float(max(nSamples, 1));
    } else if (uMode == 7) {
        // Long shadow: the layer's own silhouette, extruded along the angle and laid UNDER the layer in
        // the shadow colour. Coverage at a pixel = the source alpha found marching from the pixel back
        // against the shadow direction; Solidity blends between an averaged (soft) and a max (hard)
        // extrusion, Fade lets the coverage fall off towards the tip. The length is % of the picture
        // height, the colour is packed 0xRRGGBB in uP[5] (written by the Alight importer; 0 = black).
        float lenPx = clamp(uP[0], 0.0, 100.0) / 100.0 / max(uTexel.y, 1e-6);
        float alpha = clamp(uP[2], 0.0, 100.0) / 100.0;
        if (lenPx < 0.5 || alpha < 0.005) { oColor = c0; return; }
        float ang = radians(uP[1]);
        vec2 dir = vec2(cos(ang), -sin(ang));  // uv space: the angle runs clockwise on screen, like mode 0
        float solid = clamp(uP[3], 0.0, 100.0) / 100.0;
        float fade = clamp(uP[4], 0.0, 100.0) / 100.0;
        float pk = floor(clamp(uP[5], 0.0, 16777215.0) + 0.5);
        vec3 scol = vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
        const int N = 64;
        float jit = ign(gl_FragCoord.xy);
        float mx = 0.0, sum = 0.0, wsum = 0.0;
        for (int i = 0; i < N; i++) {
            float t = (float(i) + 0.5 + (jit - 0.5)) / float(N);
            float sa = tap(vUv - dir * (t * lenPx) * uTexel).a;
            float w = mix(1.0, 1.0 - t, fade);
            mx = max(mx, sa * w);
            sum += sa * w;
            wsum += w;
        }
        float cov = mix(sum / max(wsum, 1e-4), mx, solid) * alpha;
        float sa2 = cov * (1.0 - c0.a);
        oColor = vec4(c0.rgb + scol * sa2, clamp(c0.a + sa2, 0.0, 1.0));
    } else if (uMode == 13) {
        // v180: Alight's Denoise (the TrueMotion conversion of skexnxxdenoise):
        // the "Sir Bird" golden-spiral average at the def's hard-coded 512 x 512
        // resolution, centre-direction / saturation weighted; `strength` (uP[3])
        // is declared by the def but never read. uP: SAMPLES, PIXEL_MULTIPLIER,
        // alpha, strength (inert). fxDenoiseAmPixel in fx_plan.cpp is the twin.
        float samples = uP[0];
        float sampleRadius = sqrt(max(samples, 0.0));
        float sampleTrueRadius = 0.5 / max(sampleRadius * sampleRadius, 1e-6);
        vec3 centre = c0.rgb;
        float cl = length(centre);
        vec3 cn = cl > 1e-6 ? centre / cl : vec3(0.0);
        float csA = cos(2.3999632), snA = sin(2.3999632);
        vec2 pr = vec2(0.0, 1.0);
        vec3 acc = vec3(0.0);
        float influenceSum = 0.0;
        for (int i = 0; i < 200; i++) {
            float x = float(i);
            if (x > samples) break;
            pr = vec2(csA * pr.x - snA * pr.y, snA * pr.x + csA * pr.y);
            vec2 o = uP[1] * pr * sqrt(x) * 0.5;
            float infl = 1.0 - sampleTrueRadius * pow(max(dot(o, o), 0.0), 0.6);
            vec4 smp = tap(vUv + o / 512.0);
            infl = infl * infl * infl;
            float sl = length(smp.rgb);
            float dn = sl > 1e-6 ? dot(cn, smp.rgb / sl) : 0.0;
            infl *= pow(max(0.5 + 0.5 * dn, 0.0), 20.0) *
                    pow(max(1.0 - abs(sl - cl), 0.0), 8.0);
            influenceSum += infl;
            acc += smp.rgb * infl;
        }
        vec3 colr = influenceSum != 0.0 ? acc / influenceSum : vec3(0.0);
        oColor = vec4(mix(c0.rgb, colr, uP[2]) * c0.a, mix(c0.a, 1.0, uP[2]) * c0.a);
    } else if (uMode == 14) {
        // v180: Alight's Cinematic Bokeh (Ultra "Bokeh Blur"): the golden-spiral
        // average with polygon radius modulation and highlight weighting; alpha
        // is forced to 1. uP: blurShape, blurWidth, blurHeight, blurRadius,
        // highlightThreshold, highlightGain, quality. fxBokehAmPixel is the twin.
        float iterations = uP[6];
        vec3 acc = vec3(0.0);
        float tot = 0.0;
        for (int i = 0; i < 128; i++) {
            if (float(i) >= iterations) break;
            float fi = float(i);
            float theta = fi * 2.39996323;
            float rad = sqrt(fi) / sqrt(max(iterations, 1e-6));
            if (uP[0] > 2.5) {
                float deg = 6.2831853 / uP[0];
                rad *= 1.0 / cos(mod(theta, deg) - deg * 0.5);
            }
            vec2 off = vec2(cos(theta), sin(theta)) * rad * uP[3];
            off.x *= uP[1] / uAspect;
            off.y *= uP[2];
            vec3 colr = tap(vUv + off).rgb;
            float lum = dot(colr, vec3(0.299, 0.587, 0.114));
            float wgt = pow(max(0.0, (lum - uP[4]) / (1.0 - min(0.99, uP[4]))), uP[5]) + 0.01;
            acc += colr * wgt;
            tot += wgt;
        }
        oColor = vec4(acc / tot, 1.0);
    } else if (uMode == 15) {
        // v180: Alight's Lens Blur OBS (Ultra "Bokeh Lens Blur"): the pow-4
        // weighted spiral bokeh, then the def's normal / multiply / screen
        // composite over the layer. uP: ITERATIONS, progressa, RADIUS, bright,
        // alpha, blendMode. fxLensBlurObsAmPixel in fx_plan.cpp is the twin.
        float rad = 0.8 - 0.8 * ((uP[1] + 0.15) * 6.283);
        float r = uP[2];
        vec2 va = vec2(0.0, rad * 0.01 / sqrt(max(uP[0], 1e-6)));
        float csA = cos(2.3999632), snA = sin(2.3999632);
        vec3 acc = vec3(0.0), divv = vec3(0.0);
        for (float j = 0.0; j < uP[0]; j += 1.0) {
            r += 1.0 / r;
            va = vec2(csA * va.x - snA * va.y, snA * va.x + csA * va.y);
            vec3 colr = tap(vUv + (r - 1.0) * va).rgb;
            vec3 bk = pow(colr, vec3(4.0));
            acc += colr * bk;
            divv += bk;
        }
        vec4 factor = vec4(acc / divv, 1.0);
        factor.rgb *= uP[4];
        factor.a = mix(c0.a, 1.0, uP[4]);
        vec3 straight = c0.a > 0.0 ? c0.rgb / c0.a : vec3(0.0);
        if (uP[5] > 1.5) {
            factor *= uP[3];
            oColor = vec4(1.0 - (1.0 - straight) * (1.0 - factor.rgb), 1.0) * c0.a;
        } else if (uP[5] > 0.5) {
            factor *= uP[3];
            oColor = vec4(straight * (factor.rgb + (1.0 - factor.a)), 1.0) * c0.a;
        } else {
            oColor = factor * uP[3] * c0.a;
        }
    } else if (uMode == 16) {
        // v180: Alight's Chromatic Vortex Blur (AM's stock def via Ultra): three
        // rotational blurs (one per channel, centre-channel offsets), composited
        // per mode. uP: strength, radius, colorAberration, centerChannel, mode,
        // centerPoint packed (the def's (0, 0) default is the layer centre).
        // fxVortexBlurAmPixel in fx_plan.cpp is the twin.
        float picW = 1.0 / uTexel.x;
        float picH = 1.0 / uTexel.y;
        float pk = floor(clamp(uP[5], 0.0, 16777215.0) + 0.5);
        vec2 diff = vec2((picW * 0.5 + (floor(pk / 4096.0) - 2048.0)) / picW,
                         (picH - (picH * 0.5 + (mod(pk, 4096.0) - 2048.0))) / picH);
        vec2 convRate = vec2(1.0);
        if (picH > picW) convRate.y = picH / picW; else convRate.x = picW / picH;
        vec3 offv = uP[3] < 0.5 ? vec3(0.0, -1.0, 1.0)
                    : uP[3] < 1.5 ? vec3(-1.0, 0.0, 1.0) : vec3(-1.0, 1.0, 0.0);
        vec4 blurs[3];
        for (int ch = 0; ch < 3; ch++) {
            float T = (1.0 + offv[ch] * uP[2]) * uP[0];
            vec2 UV = (vUv - diff) * convRate;
            float dist = length(UV);
            vec4 colr;
            if (dist < uP[1]) {
                float localPower = (uP[1] - dist) / uP[1];
                float aMap = T <= 0.5 ? T / 0.5 : (1.0 - T) / 0.5;
                float theta0 = localPower * localPower * aMap * 8.0 * 3.14159;
                float speed = abs(dist * theta0 / uTexel.x * 10.0);
                float nSamples = clamp(speed, 1.01, 2000.01);
                vec2 v = UV / nSamples;
                vec2 cc2 = vec2(0.0);
                vec4 acc = vec4(0.0);
                float count = 0.0;
                float d0 = max(uP[1] - abs(uP[0]) * localPower, dist);
                for (float d = d0; d <= uP[1]; d += 1.0 / nSamples) {
                    float percent = (uP[1] - d) / uP[1];
                    vec2 ccp = UV - cc2;
                    cc2 += v;
                    float th = percent * percent * aMap * 8.0 * 3.14159;
                    float S = sin(th), C = cos(th);
                    vec2 res = vec2(ccp.x * C - ccp.y * S, ccp.x * S + ccp.y * C) / convRate + diff;
                    acc += tap(res);
                    count += 1.0;
                }
                colr = acc / count;
            } else {
                colr = tap(UV / convRate + diff);
            }
            blurs[ch] = colr;
        }
        vec4 oc = vec4(blurs[0].r, blurs[1].g, blurs[2].b,
                       (blurs[0].a + blurs[1].a + blurs[2].a) / 3.0);
        vec4 midB = uP[3] < 0.5 ? blurs[0] : uP[3] < 1.5 ? blurs[1] : blurs[2];
        if (uP[4] < 0.5) {
            oColor = oc * midB.a;
        } else if (uP[4] < 1.5) {
            vec4 m = vec4(oc.rgb / 3.0, (oc.r + oc.g + oc.b) / 3.0);
            vec4 l = vec4(dot(midB.rgb, vec3(0.2126, 0.7152, 0.0722)));
            oColor = l + m * (1.0 - l.a);
        } else if (uP[4] < 2.5) {
            oColor = vec4(oc.rgb, midB.a);
        } else {
            oColor = oc * oc.a;
        }
    } else if (uMode == 17) {
        // v180: Alight's Twitch (smartMotionBlurShake): the stepwise random shake
        // vector averaged over N samples; the clock is the timeline frame
        // (uFrame / 24 s), the def's acTime. uP: amplitude, shakeInterval,
        // samples, angle. fxTwitchAmPixel in fx_plan.cpp is the twin.
        float t = floor((float(uFrame) / 24.0) / uP[1]);
        float jitter = (fract(sin(t * 12.9898) * 43758.5453) - 0.5) * 2.0;
        float radA = radians(uP[3]);
        vec2 shakeVec = vec2(cos(radA), sin(radA)) * jitter * uP[0];
        int n = int(clamp(uP[2], 1.0, 32.0));
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 32; i++) {
            if (i >= n) break;
            float f = n > 1 ? float(i) / float(n - 1) : 0.0;
            acc += tap(clamp(vUv + shakeVec * f, 0.0, 1.0)).rgb;
        }
        oColor = vec4(acc / float(n), 1.0);
    } else {
        oColor = c0;
    }
}
)";

// ---------------------------------------------------------------------------------------------------------------------
// Colour: exposure, brightness / contrast, hue / saturation, levels, colour balance, tint, posterize
// ---------------------------------------------------------------------------------------------------------------------
inline const char* kColor = "#version 300 es\n"
R"(precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform int uFrame;
uniform int uMode;
uniform float uP[7];
uniform float uQ[7];
in vec2 vUv;
out vec4 oColor;
)" AM_FX_DITHER R"(
const vec3 LW = vec3(0.2126, 0.7152, 0.0722);

vec3 toLin(vec3 c) {
    vec3 lo = c / 12.92;
    vec3 hi = pow((max(c, vec3(0.0)) + 0.055) / 1.055, vec3(2.4));
    return vec3(c.r <= 0.04045 ? lo.r : hi.r, c.g <= 0.04045 ? lo.g : hi.g, c.b <= 0.04045 ? lo.b : hi.b);
}
vec3 toSrgb(vec3 c) {
    c = max(c, vec3(0.0));
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return vec3(c.r <= 0.0031308 ? lo.r : hi.r, c.g <= 0.0031308 ? lo.g : hi.g, c.b <= 0.0031308 ? lo.b : hi.b);
}
// hue 0..360, full saturation and value
vec3 hueRgb(float h) {
    vec3 k = vec3(1.0, 2.0 / 3.0, 1.0 / 3.0);
    vec3 p = abs(fract(vec3(h / 360.0) + k) * 6.0 - 3.0);
    return clamp(p - 1.0, 0.0, 1.0);
}
// rotation of the colour around the grey axis: keeps the brightness (better than turning the HSV hue)
vec3 hueRot(vec3 c, float a) {
    float cs = cos(a), sn = sin(a);
    vec3 k = vec3(0.57735027);
    return c * cs + cross(k, c) * sn + k * dot(k, c) * (1.0 - cs);
}
// GLSL smoothstep by the formula, so REVERSED edges ramp the other way instead of being
// undefined (AM's spotcolor2 relies on it). fxSstep in fx_plan.cpp is the same formula.
float amSstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
// AM's hsv2rgb (the defs' K formula): hue in degrees, saturation / value 0..1.
vec3 amHsv(float hDeg, float s, float v) {
    return v * mix(vec3(1.0), hueRgb(hDeg), s);
}
// Spectral Map's channel ramp (AM's spectralmap mapChannel): a triangle over the
// wrapped phase, floored by f. fxSpectralmapAmMap in fx_plan.cpp is its twin.
float amMapChannel(float p, float f) {
    float t = mod(p + 10.0, 1.0);
    return clamp(abs(t - 0.5) * 4.0 - f, 0.0, 1.0);
}
// hue of a colour in degrees 0..360 (0 for a grey)
float hueOf(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    if (d < 1e-5) return 0.0;
    float h;
    if (mx == c.r) h = mod((c.g - c.b) / d, 6.0);
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    return h * 60.0;
}

void main() {
    vec4 c = texture(uTex, vUv);
    if (c.a <= 0.0005) { oColor = vec4(0.0); return; }
    vec3 rgb = clamp(c.rgb / c.a, 0.0, 1.0);
    vec3 o = rgb;

    if (uMode == 0) {
        // Exposure (AE "Exposure"): in linear light, stops + offset + gamma, optional soft highlight rolloff.
        vec3 lin = toLin(rgb);
        lin = lin * exp2(uP[0]) + uP[1] / 100.0 * 0.5;
        float g = clamp(uP[2], 20.0, 300.0) / 100.0;
        lin = pow(max(lin, vec3(0.0)), vec3(1.0 / g));
        float roll = clamp(uP[3], 0.0, 100.0) / 100.0;
        if (roll > 0.001) {
            vec3 soft = vec3(lin.r <= 0.8 ? lin.r : 0.8 + 0.2 * tanh((lin.r - 0.8) / 0.2),
                             lin.g <= 0.8 ? lin.g : 0.8 + 0.2 * tanh((lin.g - 0.8) / 0.2),
                             lin.b <= 0.8 ? lin.b : 0.8 + 0.2 * tanh((lin.b - 0.8) / 0.2));
            lin = mix(lin, soft, roll);
        }
        o = toSrgb(clamp(lin, 0.0, 1.0));
    } else if (uMode == 1) {
        // Brightness & contrast around mid grey.
        float ct = clamp(uP[1], -100.0, 100.0) / 100.0;
        float slope = ct >= 0.0 ? 1.0 / (1.0 - 0.98 * ct) : 1.0 + ct;
        o = (rgb - 0.5) * slope + 0.5 + clamp(uP[0], -100.0, 100.0) / 100.0 * 0.5;
    } else if (uMode == 2) {
        // Hue / saturation / lightness + vibrance.
        o = hueRot(rgb, radians(uP[0]));
        float y = dot(o, LW);
        float sat = clamp(uP[1], -100.0, 100.0) / 100.0;
        o = mix(vec3(y), o, 1.0 + sat);
        float vib = clamp(uP[3], -100.0, 100.0) / 100.0;
        float cmax = max(o.r, max(o.g, o.b)), cmin = min(o.r, min(o.g, o.b));
        float cs = cmax - cmin;  // how colourful the pixel already is
        float vw = vib >= 0.0 ? vib * (1.0 - cs) : vib * cs;
        o = mix(vec3(dot(o, LW)), o, 1.0 + vw);
        float li = clamp(uP[2], -100.0, 100.0) / 100.0;
        o = li >= 0.0 ? mix(o, vec3(1.0), li) : o * (1.0 + li);
    } else if (uMode == 3) {
        // Levels (AE "Levels"): input black / white, gamma, output black / white.
        float ib = clamp(uP[0], 0.0, 254.0) / 255.0;
        float iw = max(clamp(uP[1], 0.0, 255.0) / 255.0, ib + 1.0 / 255.0);
        float g = clamp(uP[2], 10.0, 500.0) / 100.0;
        float ob = clamp(uP[3], 0.0, 255.0) / 255.0;
        float ow = clamp(uP[4], 0.0, 255.0) / 255.0;
        vec3 t = clamp((rgb - ib) / (iw - ib), 0.0, 1.0);
        t = pow(t, vec3(1.0 / g));
        o = ob + t * (ow - ob);
    } else if (uMode == 4) {
        // Colour balance: a hue / amount per range (shadows, midtones, highlights), the tint has no net brightness.
        float l = dot(rgb, LW);
        float wS = (1.0 - l) * (1.0 - l);
        float wM = 4.0 * l * (1.0 - l);
        float wH = l * l;
        vec3 cs = hueRgb(uP[0]); cs -= dot(cs, LW);
        vec3 cm = hueRgb(uP[2]); cm -= dot(cm, LW);
        vec3 ch = hueRgb(uP[4]); ch -= dot(ch, LW);
        vec3 add = cs * (clamp(uP[1], 0.0, 100.0) / 100.0) * wS
                 + cm * (clamp(uP[3], 0.0, 100.0) / 100.0) * wM
                 + ch * (clamp(uP[5], 0.0, 100.0) / 100.0) * wH;
        o = rgb + add * 0.45;
        float keep = clamp(uP[6], 0.0, 100.0) / 100.0;
        float l2 = max(dot(o, LW), 0.0001);
        o *= mix(1.0, max(l, 0.0001) / l2, keep);
    } else if (uMode == 5) {
        // Tint: the picture's brightness mapped onto a two colour ramp (shadow colour -> highlight colour).
        float l = dot(rgb, LW);
        vec3 cs = mix(vec3(0.0), hueRgb(uP[0]), clamp(uP[1], 0.0, 100.0) / 100.0 * 0.5);
        vec3 ch = mix(vec3(1.0), hueRgb(uP[2]), clamp(uP[3], 0.0, 100.0) / 100.0);
        vec3 ramp = mix(cs, ch, l);
        o = mix(rgb, ramp, clamp(uP[4], 0.0, 100.0) / 100.0);
    } else if (uMode == 6) {
        // Posterize.
        float n = clamp(floor(uP[0] + 0.5), 2.0, 32.0);
        vec3 q = floor(rgb * (n - 1.0) + 0.5) / (n - 1.0);
        o = mix(rgb, q, clamp(uP[1], 0.0, 100.0) / 100.0);
    } else if (uMode == 7) {
        // Invert: every channel, or only the brightness (the colours stay).
        float amt = clamp(uP[0], 0.0, 100.0) / 100.0;
        float lo = clamp(uP[1], 0.0, 100.0) / 100.0;
        float y = dot(rgb, LW);
        vec3 inv = 1.0 - rgb;
        vec3 lum = rgb + (1.0 - 2.0 * y);
        o = mix(rgb, mix(inv, lum, lo), amt);
    } else if (uMode == 8) {
        // Black & white with free channel weights and an optional toning (sepia, blue tone ...).
        vec3 wt = vec3(uP[0], uP[1], uP[2]) / 100.0;
        float g = clamp(dot(rgb, wt), 0.0, 1.0);
        float ta = clamp(uP[5], 0.0, 100.0) / 100.0;
        vec3 tn = mix(vec3(1.0), 0.35 + 0.85 * hueRgb(uP[4]), ta);
        tn /= max(dot(tn, LW), 0.001);
        o = mix(rgb, vec3(g) * tn, clamp(uP[3], 0.0, 100.0) / 100.0);
    } else if (uMode == 9) {
        // Threshold: pure black and white around a level.
        float lvl = clamp(uP[0], 0.0, 255.0) / 255.0;
        float soft = clamp(uP[1], 0.0, 100.0) / 100.0 * 0.25 + 0.002;
        float t = smoothstep(lvl - soft, lvl + soft, dot(rgb, LW));
        o = mix(rgb, vec3(t), clamp(uP[2], 0.0, 100.0) / 100.0);
    } else if (uMode == 10) {
        // Temperature and tint (white balance) in linear light, grey keeps its brightness.
        float tmp = clamp(uP[0], -100.0, 100.0) / 100.0;
        float tnt = clamp(uP[1], -100.0, 100.0) / 100.0;
        vec3 gain = vec3(1.0 + 0.30 * tmp + 0.06 * tnt, 1.0 - 0.20 * tnt, 1.0 - 0.34 * tmp + 0.06 * tnt);
        gain /= dot(gain, LW);
        o = toSrgb(clamp(toLin(rgb) * gain, 0.0, 1.0));
    } else if (uMode == 11) {
        // Shadows / highlights: lifts dark parts, pulls bright parts back, optional midtone contrast.
        float sh = clamp(uP[0], 0.0, 100.0) / 100.0;
        float hi = clamp(uP[1], 0.0, 100.0) / 100.0;
        float wd = 0.2 + clamp(uP[2], 0.0, 100.0) / 100.0 * 0.8;
        float mc = clamp(uP[3], -100.0, 100.0) / 100.0;
        float y = dot(rgb, LW);
        float ws = 1.0 - smoothstep(0.0, wd, y);
        float wh = smoothstep(1.0 - wd, 1.0, y);
        float y2 = y + ws * sh * (1.0 - y) * 0.55 - wh * hi * y * 0.55;
        o = rgb * clamp(y2 / max(y, 0.02), 0.0, 6.0);
        vec3 cur = (o - 0.5) * (1.0 + mc * 0.6) + 0.5;
        o = mix(o, cur, 4.0 * y * (1.0 - y));
    } else if (uMode == 12) {
        // Color pop (leave colour): only colours near a hue stay, the rest fades to grey.
        float mx = max(rgb.r, max(rgb.g, rgb.b));
        float mn = min(rgb.r, min(rgb.g, rgb.b));
        float h = hueOf(rgb);
        float dh = abs(h - uP[0]);
        dh = min(dh, 360.0 - dh) / 180.0;
        float rng = clamp(uP[1], 0.0, 100.0) / 100.0 * 0.5 + 0.01;
        float sf = clamp(uP[2], 0.0, 100.0) / 100.0 * 0.5 + 0.005;
        float keep = (1.0 - smoothstep(rng, rng + sf, dh)) * smoothstep(0.02, 0.12, mx - mn);
        o = mix(rgb, mix(vec3(dot(rgb, LW)), rgb, keep), clamp(uP[3], 0.0, 100.0) / 100.0);
    } else if (uMode == 13) {
        // Cinematic grade: teal shadows, warm highlights, S-curve contrast, lifted blacks, saturation.
        float amt = clamp(uP[0], 0.0, 100.0) / 100.0;
        float ct = clamp(uP[1], 0.0, 100.0) / 100.0;
        float fd = clamp(uP[2], 0.0, 100.0) / 100.0;
        float sat = clamp(uP[3], -100.0, 100.0) / 100.0;
        float y = dot(rgb, LW);
        vec3 c2 = rgb + vec3(-0.06, 0.02, 0.08) * (1.0 - y) * (1.0 - y) + vec3(0.07, 0.02, -0.07) * y * y;
        c2 = clamp(c2, 0.0, 1.0);
        c2 = mix(c2, c2 * c2 * (3.0 - 2.0 * c2), ct * 0.8);
        c2 = c2 * (1.0 - 0.12 * fd) + 0.06 * fd;
        c2 = mix(vec3(dot(c2, LW)), c2, 1.0 + sat);
        o = mix(rgb, c2, amt);
    } else if (uMode == 14) {
        // Solarize: values above the threshold flip (the Sabattier look).
        float th = clamp(uP[0], 0.0, 100.0) / 100.0;
        vec3 s = mix(rgb, 1.0 - rgb, smoothstep(vec3(th - 0.02), vec3(th + 0.02), rgb));
        o = mix(rgb, s, clamp(uP[1], 0.0, 100.0) / 100.0);
    } else if (uMode == 15) {
        // Colour tune (Alight Motion's grading effect): a Lift and a Gain control, each a colour-disc
        // push plus a luminance shift, weighted by the pixel's luminance with Alight's published curves
        // (Lift (1 - y)^2, Gain y^2). As in Colour Balance (mode 4) the disc push carries no luminance of
        // its own; the same maths lives in fx_plan.cpp (fxColorTuneMap) — keep the two in step.
        float y = dot(rgb, LW);
        float wL = (1.0 - y) * (1.0 - y);
        float wG = y * y;
        vec3 cl = hueRgb(uP[0]); cl -= dot(cl, LW);
        vec3 cg = hueRgb(uP[3]); cg -= dot(cg, LW);
        vec3 add = cl * (clamp(uP[1], 0.0, 100.0) / 100.0) * wL + cg * (clamp(uP[4], 0.0, 100.0) / 100.0) * wG;
        float dl = clamp(uP[2], -100.0, 100.0) / 100.0 * wL + clamp(uP[5], -100.0, 100.0) / 100.0 * wG;
        o = rgb + add * 0.45 + dl;
    } else if (uMode == 16) {
        // Solid colour (Alight Motion's solidcolor): the picture's colours are replaced by one
        // colour — uP[0..2] are the colour 0..255, uP[3] the strength 0..100 — while its alpha
        // silhouette is kept (the tail below premultiplies by the pixel's own alpha, and fully
        // transparent pixels returned early above, so a layer with no picture stays invisible).
        // The same maths lives in fx_plan.cpp (fxSolidColorMap) — keep the two in step.
        vec3 sc = vec3(clamp(uP[0], 0.0, 255.0), clamp(uP[1], 0.0, 255.0), clamp(uP[2], 0.0, 255.0)) / 255.0;
        o = mix(rgb, sc, clamp(uP[3], 0.0, 100.0) / 100.0);
    } else if (uMode == 17) {
        // Replace colour (Alight Motion's replacecolor, its recovered shader): the distance to the
        // old colour is measured in YUV (chroma x4, luma x0.5); inside the feathered band the pixel
        // shifts by (new - old), or is recoloured at its own luminance when locked. uP[0] / uP[1]
        // are the two colours packed 0xRRGGBB, uP[2] the threshold %, uP[3] the feather %,
        // uP[4] the alpha %, uP[5] the luminance lock. AM's smoothstep here has REVERSED edges
        // (it ramps down with distance); ES gives the formula result on the target drivers, and
        // the same maths lives in fx_plan.cpp (fxReplaceColorMap) — keep the two in step.
        float pk1 = floor(clamp(uP[0], 0.0, 16777215.0) + 0.5);
        float pk2 = floor(clamp(uP[1], 0.0, 16777215.0) + 0.5);
        vec3 oc = vec3(mod(floor(pk1 / 65536.0), 256.0), mod(floor(pk1 / 256.0), 256.0), mod(pk1, 256.0)) / 255.0;
        vec3 nc = vec3(mod(floor(pk2 / 65536.0), 256.0), mod(floor(pk2 / 256.0), 256.0), mod(pk2, 256.0)) / 255.0;
        mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
        mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
        vec3 oldYuv = oc * rgb2yuv;
        vec3 srcYuv = rgb * rgb2yuv;
        vec3 dYuv = abs(oldYuv - srcYuv);
        dYuv.yz *= 4.0;
        dYuv.x *= 0.5;
        float diff = length(dYuv);
        float thr = clamp(uP[2], 0.0, 400.0) / 100.0;
        float fea = max(clamp(uP[3], 0.0, 200.0) / 100.0, 0.0005);
        float bb = max(thr - fea, 0.0);
        float aa = min(thr + fea, 4.0);
        float p = smoothstep(aa, min(bb, aa - 0.0005), diff);
        vec3 adj = nc;
        if (uP[5] > 0.5) {
            vec3 newYuv = nc * rgb2yuv;
            newYuv.x = srcYuv.x;
            adj = newYuv * yuv2rgb;
        } else {
            adj = rgb - oc + nc;
        }
        o = mix(rgb, mix(rgb, adj, p), clamp(uP[4], 0.0, 100.0) / 100.0);
    } else if (uMode == 18) {
        // Colour balance (Alight Motion's colorbalance, its recovered shader): YUV band weights
        // (lows / mids / highs over fixed luma bands) build a per-channel gain of
        // (filter + 100) / 100, and fixLum restores the input luma afterwards. AM computes on
        // the sampled (premultiplied) colour, so this branch does too and un-divides at the
        // end (the tail below multiplies back by c.a). uP[0..6] are crLow, mgLow, ybLow,
        // crMid, mgMid, ybMid, crHigh (AM units -100..100); uQ[0..2] are mgHigh, ybHigh and
        // the fix-luminance flag. The same maths lives in fx_plan.cpp (fxColorBalanceAmMap).
        mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
        mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
        vec3 yuv = c.rgb * rgb2yuv;
        float low = 1.0 - smoothstep(0.222, 0.444, yuv.x);
        float midLow = smoothstep(0.222, 0.444, yuv.x) * step(yuv.x, 0.555);
        float midHigh = (1.0 - smoothstep(0.555, 0.777, yuv.x)) * step(0.555, yuv.x) * step(yuv.x, 0.777);
        float high = smoothstep(0.555, 0.777, yuv.x);
        vec3 filt = vec3(uP[0], uP[1], uP[2]) * low
                    + vec3(uP[3], uP[4], uP[5]) * (midLow + midHigh)
                    + vec3(uP[6], uQ[0], uQ[1]) * high;
        filt = clamp(filt, -100.0, 100.0);
        vec3 conv = (filt + 100.0) / 100.0;
        vec3 convYuv = (c.rgb * conv) * rgb2yuv;
        if (uQ[2] > 0.5) convYuv.x = yuv.x;
        o = (convYuv * yuv2rgb) / c.a;
    } else if (uMode == 19) {
        // Colour temperature (Alight Motion's colortemperature, its recovered shader): a
        // Kelvin -> RGB gain from AM's own polynomial fit (two halves split at 6500 K, mixed
        // back to white below 1000 K), mixed in by the strength. uP[0] is the temperature in
        // AM units (Kelvin / 1000), uP[1] the strength %. A multiplicative gain is the same in
        // straight and premultiplied space, so the plain `rgb` is exact here. The same maths
        // lives in fx_plan.cpp (fxColorTempAmGain) — keep the two in step.
        float T = clamp(uP[0], 1.0, 40.0) * 1000.0;
        mat3 m = T <= 6500.0
            ? mat3(vec3(0.0, -2902.1955373783176, -8257.7997278925690),
                   vec3(0.0, 1669.5803561666639, 2575.2827530017594),
                   vec3(1.0, 1.3302673723350029, 1.8993753891711275))
            : mat3(vec3(1745.0425298314172, 1216.6168361476490, -8257.7997278925690),
                   vec3(-2666.3474220535695, -2173.1012343082230, 2575.2827530017594),
                   vec3(0.55995389139931482, 0.70381203140554553, 1.8993753891711275));
        vec3 gain = mix(clamp(m[0] / (vec3(clamp(T, 1000.0, 40000.0)) + m[1]) + m[2], vec3(0.0), vec3(1.0)),
                        vec3(1.0), smoothstep(1000.0, 0.0, T));
        o = mix(rgb, rgb * gain, clamp(uP[1], 0.0, 100.0) / 100.0);
    } else if (uMode == 20) {
        // Colour tint (Alight Motion's colortint, its recovered shader): the pixel's chroma
        // mixes toward the tint colour's chroma (HSV hue at full saturation, value 0.5) by
        // strength * alpha — AM works on the sampled (premultiplied) colour, so this branch
        // does too and un-divides at the end. uP[0] is the hue in degrees, uP[1] the
        // strength %. The same maths lives in fx_plan.cpp (fxColorTintAmMap).
        mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
        mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
        vec3 tintedYuv = (hueRgb(uP[0]) * 0.5) * rgb2yuv;
        vec3 yuv = c.rgb * rgb2yuv;
        yuv.yz = mix(yuv.yz, tintedYuv.yz, clamp(uP[1], 0.0, 100.0) / 100.0 * c.a);
        o = (yuv * yuv2rgb) / c.a;
    } else if (uMode == 21) {
        // Colour hot (Alight Motion's colorhot, its recovered shader): the luminance of the
        // sampled (premultiplied) colour, gamma-lifted by the colour disc's bias, picks a
        // colour between the colour disc (shadows) and the tint disc (lights) around the
        // tint bias cutoff; the blend is re-based onto the luminance in YUV, then a
        // highlight shine mixes toward white. uP[0..2] / uP[3..5] are the two hue-discs
        // (hue deg, saturation, bias). AM outputs vec4(blend, 1) * a, so `o` here is the
        // blend colour itself. fxColorHotAmMap in fx_plan.cpp is the CPU twin.
        mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
        mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
        float lum = dot(c.rgb, LW);
        lum = pow(max(lum, 0.0), 1.0 / (uP[2] + 1.0));
        vec3 base = amHsv(uP[0], uP[1], 1.0);
        vec3 light = amHsv(uP[3], uP[4], 1.0);
        float cutoff = uP[5] / 2.0 + 0.5;
        vec3 blend = lum < cutoff
            ? base
            : mix(base, light, (1.0 - cutoff) > 0.000001 ? (lum - cutoff) / (1.0 - cutoff) : 0.0);
        vec3 yuv = blend * rgb2yuv;
        yuv.x = lum;
        yuv.yz *= lum;
        blend = yuv * yuv2rgb;
        float shine = max(0.0, lum - 0.85) * (1.0 / 0.15);
        shine = shine * shine * uP[1];
        o = mix(blend, vec3(1.0), shine);
    } else if (uMode == 22) {
        // Colorize (Alight Motion's colorize, its recovered shader): the pixel's chroma
        // mixes toward the tint's chroma by amount * alpha — the Color Tint formula with
        // the hue-disc's y as the amount (uP[1], AM raw 0..1; uP[0] is the hue in degrees).
        // fxColorizeAmMap in fx_plan.cpp is the CPU twin.
        mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
        mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
        vec3 tintedYuv = (amHsv(uP[0], 1.0, 0.5)) * rgb2yuv;
        vec3 yuv = c.rgb * rgb2yuv;
        yuv.yz = mix(yuv.yz, tintedYuv.yz, clamp(uP[1], 0.0, 1.0) * c.a);
        o = (yuv * yuv2rgb) / c.a;
    } else if (uMode == 23) {
        // Highlights & shadows (Alight Motion's highlightshadow — GPUImage's formula,
        // verbatim in the def): the sampled (premultiplied) colour is re-scaled to the
        // adjusted luminance, pulled toward white by highlights over 1 and toward black by
        // shadows under 1. uP[0] is shadows, uP[1] highlights (AM raw 0..2). The division
        // by the luminance is guarded (AM gets NaN on black). fxHighlightShadowAmMap in
        // fx_plan.cpp is the CPU twin.
        float lum = dot(c.rgb, LW);
        float cs = clamp(uP[0], 1.0, 2.0);
        float shadow = clamp(pow(max(lum, 0.0), 1.0 / cs) - 0.76 * pow(max(lum, 0.0), 2.0 / cs) - lum, 0.0, 1.0);
        float hden = max(2.0 - uP[1], 0.001);
        float highlight = clamp(1.0 - (pow(max(1.0 - lum, 0.0), 1.0 / hden) - 0.8 * pow(max(1.0 - lum, 0.0), 2.0 / hden)) - lum, -1.0, 0.0);
        vec3 res = lum > 0.000001 ? c.rgb * ((lum + shadow + highlight) / lum) : vec3(0.0);
        float cl = (lum - 0.5) * 1.5 + 0.5;
        float whiteK = cl * cl * cl * (clamp(uP[1], 1.0, 2.0) - 1.0);
        res = mix(res, vec3(1.0), whiteK);
        float icl = 1.0 - cl;
        float blackK = icl * icl * icl * (1.0 - clamp(uP[0], 0.0, 1.0));
        res = mix(res, vec3(0.0), blackK);
        o = res / c.a;
    } else if (uMode == 24) {
        // Spot colour (Alight Motion's spotcolor2, its recovered shader group 0): pixels
        // whose YUV colour (chroma choked) sits near the spot colour keep their colour;
        // the rest fall to the desaturated colour (the CHOKED chroma scaled by saturation
        // / 2, like the def). uP[0] is the colour packed 0xRRGGBB, uP[1] the threshold,
        // uP[2] the feather, uP[3] the saturation, uP[4] the choke (all AM raw).
        // fxSpotColorAmMap in fx_plan.cpp is the CPU twin.
        mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
        mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
        float pk = floor(clamp(uP[0], 0.0, 16777215.0) + 0.5);
        vec3 spot = vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
        vec3 spotYuv = spot * rgb2yuv;
        vec3 texYuv = c.rgb * rgb2yuv;
        spotYuv.yz *= uP[4];
        texYuv.yz *= uP[4];
        float dist = length(spotYuv - texYuv);
        float aa = min(uP[1] + uP[2], 1.0);
        float bb = max(uP[1] - uP[2], 0.0);
        float p = amSstep(aa, min(bb, aa - 0.01), dist);
        vec3 desatYuv = texYuv;
        desatYuv.yz *= uP[3] / 2.0;
        vec3 desat = desatYuv * yuv2rgb;
        o = mix(desat, c.rgb, p) / c.a;
    } else if (uMode == 25) {
        // v175: Alight's Threshold (defs-batch7), verbatim: the mask p from the luma of
        // the PREMULTIPLIED pixel (c.rgb as sampled), AM's feather floor 0.00196 and
        // the (1.003 + 2f) recentring included; uP = (threshold, feather, invert,
        // blend mode 0 mask / 1 multiply / 2 screen). The result is divided by c.a
        // like every AM mode here, so a translucent pixel's mask clamps to its alpha
        // instead of AM's overbright write; opaque pixels (the corpus) are exact.
        // fxThresholdAmMap in fx_plan.cpp is the twin.
        float lum = dot(c.rgb, LW);
        float f = max(min(uP[1] * 0.25, abs(0.5 - uP[0])), 0.00196);
        float t = (uP[0] - 0.5) * (1.003 + 2.0 * f) + 0.5;
        float p = smoothstep(t - f, t + f, lum);
        if (uP[2] > 0.5) p = 1.0 - p;
        vec3 r = vec3(p);
        if (uP[3] > 1.5) r = 1.0 - (1.0 - c.rgb) * (1.0 - p);
        else if (uP[3] > 0.5) r = c.rgb * p;
        o = r / c.a;
    } else if (uMode == 26) {
        // v175: Alight's Spectral Map (defs-batch7), verbatim: a three-colour ramp
        // over the premultiplied luma (repeat + phase, reversed on request),
        // saturation through AM's YUV matrices, the result scaled by the luma when
        // scale-brightness is on, premultiplied by the pixel alpha and mixed over
        // the pixel by alpha. uP = (phase, repeat, alpha, squeeze, saturation,
        // reverse, scale brightness); uQ[0..2] pack the red / green / blue colours
        // 0xRRGGBB. fxSpectralmapAmMap in fx_plan.cpp is the twin.
        mat3 rgb2yuv = mat3(0.299, 0.587, 0.114, -0.14713, -0.28886, 0.436, 0.615, -0.51499, -0.10001);
        mat3 yuv2rgb = mat3(1.0, 0.0, 1.13983, 1.0, -0.39465, -0.58060, 1.0, 2.03211, 0.0);
        float lum0 = dot(c.rgb, LW);
        float bscale = uP[6] > 0.5 ? lum0 : 1.0;
        float lum = (uP[5] > 0.5 ? 1.0 - lum0 : lum0) * uP[1] + uP[0];
        float f = mix(0.333, 0.1, uP[3]);
        float pkR = floor(clamp(uQ[0], 0.0, 16777215.0) + 0.5);
        float pkG = floor(clamp(uQ[1], 0.0, 16777215.0) + 0.5);
        float pkB = floor(clamp(uQ[2], 0.0, 16777215.0) + 0.5);
        vec3 cR = vec3(mod(floor(pkR / 65536.0), 256.0), mod(floor(pkR / 256.0), 256.0), mod(pkR, 256.0)) / 255.0;
        vec3 cG = vec3(mod(floor(pkG / 65536.0), 256.0), mod(floor(pkG / 256.0), 256.0), mod(pkG, 256.0)) / 255.0;
        vec3 cB = vec3(mod(floor(pkB / 65536.0), 256.0), mod(floor(pkB / 256.0), 256.0), mod(pkB, 256.0)) / 255.0;
        vec3 res = amMapChannel(lum, f) * cR + amMapChannel(lum - 1.0 / 3.0, f) * cG
                 + amMapChannel(lum - 2.0 / 3.0, f) * cB;
        res = res * rgb2yuv;
        res.yz *= uP[4] + 1.0;
        res = res * yuv2rgb;
        vec4 rp = vec4(res * bscale * c.a, c.a);
        o = (c + (rp - c) * uP[2]).rgb / c.a;
    } else if (uMode == 27) {
        // v180: Alight's Negative (Ultra original), verbatim on the premultiplied
        // pixel: invert -> luma (the def's 0.299 / 0.587 / 0.114 weights) ->
        // unsharp against the 8 neighbours at the def's fixed 0.0015 offsets ->
        // brightness / contrast -> the black / white point remap -> the time-hash
        // grain (the def hashes uv * screenSize + fract(time * 100); here
        // gl_FragCoord + fract(uFrame * 100 / 24)). The result is divided by c.a
        // like every AM mode here, so the epilogue's re-premultiply restores the
        // def's vec4(lum, src.a) write (opaque pixels exact). uP = (contrast,
        // brightness, blackPoint, whitePoint, sharpness, grainAmount).
        // fxNegativeAmPixel in fx_plan.cpp is the twin.
        vec3 NW = vec3(0.299, 0.587, 0.114);
        float lum = dot(1.0 - c.rgb, NW);
        const vec2 offs[8] = vec2[](vec2(-1.0, 1.0), vec2(0.0, 1.0), vec2(1.0, 1.0),
                                    vec2(-1.0, 0.0), vec2(1.0, 0.0),
                                    vec2(-1.0, -1.0), vec2(0.0, -1.0), vec2(1.0, -1.0));
        float blurred = 0.0;
        for (int i = 0; i < 8; i++)
            blurred += dot(1.0 - texture(uTex, vUv + offs[i] * 0.0015).rgb, NW);
        blurred /= 8.0;
        lum += (lum - blurred) * uP[4];
        lum += uP[1];
        lum = (lum - 0.5) * uP[0] + 0.5;
        lum = clamp((lum - uP[2]) / max(uP[3] - uP[2], 0.001), 0.0, 1.0);
        float grain = (amHash(gl_FragCoord.xy + fract(float(uFrame) / 24.0 * 100.0)) - 0.5) * uP[5];
        lum = clamp(lum + grain, 0.0, 1.0);
        o = vec3(lum) / c.a;
    } else if (uMode == 28) {
        // v180: Alight's Tritone (Ultra original; the TrueMotion vs.tritone is a
        // different effect): the premultiplied luma drives the shadows ->
        // midtones -> highlights ramp, mixed back over the pixel by blend; the
        // def writes vec4(mix, 1) * src.a, reached here by dividing the
        // premultiplied mix by c.a before the epilogue. uP = (highlights packed,
        // its alpha, midtones packed, its alpha, shadows packed, its alpha,
        // blend) — the def reads the colours as rgb only (their alphas are
        // carried, unused). fxTritoneAmPixel in fx_plan.cpp is the twin.
        float pkH = floor(clamp(uP[0], 0.0, 16777215.0) + 0.5);
        float pkM = floor(clamp(uP[2], 0.0, 16777215.0) + 0.5);
        float pkS = floor(clamp(uP[4], 0.0, 16777215.0) + 0.5);
        vec3 cH = vec3(mod(floor(pkH / 65536.0), 256.0), mod(floor(pkH / 256.0), 256.0), mod(pkH, 256.0)) / 255.0;
        vec3 cM = vec3(mod(floor(pkM / 65536.0), 256.0), mod(floor(pkM / 256.0), 256.0), mod(pkM, 256.0)) / 255.0;
        vec3 cS = vec3(mod(floor(pkS / 65536.0), 256.0), mod(floor(pkS / 256.0), 256.0), mod(pkS, 256.0)) / 255.0;
        float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));
        vec3 tri = lum < 0.5 ? mix(cS, cM, lum * 2.0) : mix(cM, cH, (lum - 0.5) * 2.0);
        o = mix(tri, c.rgb, uP[6]) / c.a;
    }
    o = clamp(o + amDither(), 0.0, 1.0);
    oColor = vec4(o * c.a, c.a);
}
)";

// ---------------------------------------------------------------------------------------------------------------------
// Warp: wave warp, twirl, bulge, mirror, kaleidoscope, turbulent displace (everything that moves pixels)
// ---------------------------------------------------------------------------------------------------------------------
inline const char* kWarp = "#version 300 es\n"
R"(precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uAspect;
uniform int uFrame;
uniform int uMode;
uniform float uP[7];
uniform float uQ[7];
uniform float uR[7];
in vec2 vUv;
out vec4 oColor;

const float PI = 3.14159265;

// GLSL smoothstep by the formula, so REVERSED edges ramp the other way instead of being
// undefined (AM's own shaders rely on that, e.g. radialwipe's p0 / p2). fxSstep in fx_plan.cpp
// is the same formula.
float amSstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
// Alight hexshift's cell hash (its constants, pinned by the def).
float amHexRand(vec2 co) {
    float dt = dot(co, vec2(719.310, 178.233));
    float sn = mod(dt, 3.14);
    return fract(sin(sn) * 33758.5453);
}
// Alight rgbglitch's slice hash (mode 28).
float amGlitchRnd(float x, float y) {
    return fract(sin(x * 12.9898 + y * 4.1414) * 43758.5453);
}
// Alight refractionwarp's cos field (the def's col(); mode 30).
float amRefractionCol(vec2 coord, float time) {
    float deltaTheta = 2.0 * 3.14159265 / uQ[2];
    float col = 0.0;
    for (int i = 0; i < 15; i++) {
        if (i >= int(uQ[1])) break;
        float theta = deltaTheta * float(i);
        float ct = cos(theta), st = sin(theta);
        vec2 adjc = coord;
        adjc.x += ct * time * uR[0] + time * uR[1];
        adjc.y -= st * time * uR[0] - time * uR[2];
        col += cos((adjc.x * ct - adjc.y * st) * uP[6]) * uP[5];
    }
    return cos(col);
}
// Alight cc_glass's property pick (mode 32).
float amGlassVal(vec4 col) {
    if (uP[0] > 3.5) return col.a;
    if (uP[0] > 2.5) return col.b;
    if (uP[0] > 1.5) return col.g;
    if (uP[0] > 0.5) return col.r;
    return dot(col.rgb, vec3(0.299, 0.587, 0.114));
}
// solidcolour wipe's smoothstepRev: a step when the edges coincide (mode 33).
float amSstepRev(float e0, float e1, float x) {
    if (e0 == e1) return step(e0, x);
    return amSstep(e0, e1, x);
}

// Ashima simplex noise (the snoise of AM's turbulentdisplace3 / roughenedges / ridges
// defs) and AM's turbulence sum over it; fxSnoise3 / fxTurbulence in fx_plan.cpp are the
// CPU twins.
vec3 amMod289v3(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 amMod289v4(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec3 amPermuteV3(vec3 x) { return amMod289v3(((x * 34.0) + 1.0) * x); }
vec4 amPermuteV4(vec4 x) { return amMod289v4(((x * 34.0) + 1.0) * x); }
vec4 amTaylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
float amSnoise3(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;
    i = amMod289v3(i);
    vec4 p = amPermuteV4(amPermuteV4(amPermuteV4(
        i.z + vec4(0.0, i1.z, i2.z, 1.0))
        + i.y + vec4(0.0, i1.y, i2.y, 1.0))
        + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = amTaylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x;
    p1 *= norm.y;
    p2 *= norm.z;
    p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}
float amTurbulence(vec2 st, int octaves, vec2 parallax, float evolution) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 9; i++) {
        if (i >= octaves) break;
        value += amplitude * abs(amSnoise3(vec3(st, evolution)));
        st *= 2.0;
        st += parallax;
        amplitude *= 0.5;
    }
    return value;
}
// McGuire value noise + fbm (AM's fractalwarp4 / dots2 helpers); fxValueNoise /
// fxValueFbm in fx_plan.cpp are the CPU twins.
float amValueRandom(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}
float amValueNoise(vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);
    float a = amValueRandom(i);
    float b = amValueRandom(i + vec2(1.0, 0.0));
    float c = amValueRandom(i + vec2(0.0, 1.0));
    float d = amValueRandom(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
float amValueFbm(vec2 st, vec2 parallax, int octaves, float intens) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 9; i++) {
        if (i >= octaves) break;
        value += amplitude * amValueNoise(st);
        st *= 2.0;
        st += parallax;
        amplitude *= intens;
    }
    return value;
}
// A packed AM point (12-bit signed fields) back to its two components.
vec2 amUnpackPoint(float pk) {
    pk = floor(clamp(pk, 0.0, 16777215.0) + 0.5);
    return vec2(mod(floor(pk / 4096.0), 4096.0) - 2048.0, mod(pk, 4096.0) - 2048.0);
}
// AM's bendwarp bend(): the circular-arc remap (l = w = 1 at both call sites).
vec2 amBendCurve(vec2 p, float a, bool vertical) {
    if (abs(a) < 0.001) return p;
    float s = sign(a);
    if (vertical) {
        float ra = 0.5 / a;
        p.x -= ra;
        float u = abs(ra) - length(p);
        float v = ra * atan(s * p.y, -s * p.x);
        return vec2(u * s, v);
    }
    float ra = 0.5 / a;
    p.y -= ra;
    float v = abs(ra) - length(p);
    float u = ra * atan(s * p.x, -s * p.y);
    return vec2(u, v * s);
}

// Reads the picture at uv. Outside the picture the picture is mirrored back in (no see-through holes at the edges of a
// warped layer, no smeared edge pixels either).
vec4 pic(vec2 uv) {
    uv = 1.0 - abs(1.0 - mod(uv, 2.0));
    return textureLod(uTex, uv, 0.0);
}
// uv <-> a space with height 1 and the centre at (0, 0) (so rounds stay round on a wide picture)
vec2 toP(vec2 uv) { return (uv - 0.5) * vec2(uAspect, 1.0); }
vec2 toUv(vec2 p) { return p / vec2(uAspect, 1.0) + 0.5; }
vec2 centreP(float x, float y) { return toP(vec2(x / 100.0, 1.0 - y / 100.0)); }

float h3(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}
float n3(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h3(i), h3(i + vec3(1, 0, 0)), f.x), mix(h3(i + vec3(0, 1, 0)), h3(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h3(i + vec3(0, 0, 1)), h3(i + vec3(1, 0, 1)), f.x), mix(h3(i + vec3(0, 1, 1)), h3(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}
float fbm(vec3 p, int oct) {
    float s = 0.0, a = 0.5, tot = 0.0;
    mat2 R = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        s += a * n3(p);
        tot += a;
        p.xy = R * p.xy * 2.0;
        p.z += 1.7;
        a *= 0.5;
    }
    return s / max(tot, 1e-4);
}

void main() {
    if (uMode == 11) {
        // Alight Motion's Mirror (AM's own recovered shader, fx_plan.cpp fxMirrorMap is the CPU
        // twin): the whole layer flipped — type 0 flips x, type 1 flips y, the offset is added to
        // the flipped coordinate — blended over the original per blendMode, mixed by alpha. The
        // flipped sample is read directly (NOT through pic(): outside the layer Alight samples
        // transparency, not a mirrored wrap). Both pictures are premultiplied here.
        vec4 S = textureLod(uTex, vUv, 0.0);
        vec2 fuv = vUv;
        float off = clamp(uP[3], -200.0, 200.0) / 100.0;
        if (uP[0] > 0.5) fuv.y = 1.0 - vUv.y + off; else fuv.x = 1.0 - vUv.x + off;
        vec4 F = vec4(0.0);
        if (all(greaterThanEqual(fuv, vec2(0.0))) && all(lessThanEqual(fuv, vec2(1.0)))) F = textureLod(uTex, fuv, 0.0);
        vec4 R;
        int bm = int(uP[1] + 0.5);
        if (bm == 1) {
            R = vec4((S.rgb / max(S.a, 1e-5)) * F.rgb, 1.0) * S.a;
        } else if (bm == 2) {
            R = vec4(1.0 - (1.0 - S.rgb / max(S.a, 1e-5)) * (1.0 - F.rgb), 1.0) * S.a;
        } else if (bm == 3) {
            R = S * (1.0 - F.a) + F;
        } else if (bm == 4) {
            R = F * (1.0 - S.a) + S;
        } else {
            R = F;
        }
        oColor = mix(S, R, clamp(uP[2], 0.0, 100.0) / 100.0);
        return;
    }
    if (uMode == 12) {
        // Stretch Segment (Alight Motion's stretchsegment, its recovered shader): along the angle
        // direction the picture is pulled toward the centre line — the sample sits sminCubic(
        // stretch, |dist|) back against the sign of dist, so a band around the line is stretched
        // flat. uP: angle (deg), stretch (AM units, /500 in the shader), offset (AM units),
        // smooth %. The same maths lives in fx_plan.cpp (fxStretchSegmentUv). Like mode 11 the
        // sample is read directly: outside the picture is transparent, as in Alight.
        float a = uP[0] * 0.0174533;
        vec2 v = vec2(cos(a), -sin(a));
        vec2 st = vUv - 0.5;
        float dist = dot(st, v) + uP[2] / 1000.0;
        float adj = clamp(uP[1], 0.0, 900.0) / 500.0;
        float kk = max(0.00001, clamp(uP[3], 0.0, 100.0) / 100.0 * adj);
        float hh = max(kk - abs(adj - abs(dist)), 0.0) / kk;
        float d = min(adj, abs(dist)) - hh * hh * hh * kk * (1.0 / 6.0);
        vec2 suv = vUv + v * d * -sign(dist);
        oColor = (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0);
        return;
    }
    if (uMode == 13) {
        // Swirl (Alight Motion's swirl4 / swirl3, their recovered shaders): inside the radius the
        // sampling turns by Percent^2 * A * 8 * pi around the centre, most at the centre. uP:
        // strength % (-50..50), radius %, centre x / y (AM units), version (4 / 3). The versions
        // differ in centre units (4: AM pixels from the picture centre; 3: per-mille of the
        // picture) and in the aspect rule (4 scales the longer axis, 3 always scales y). Corpus:
        // the centre is the default (the picture centre) in all 10 instances. The same maths
        // lives in fx_plan.cpp (fxSwirlUv).
        float strength = clamp(uP[0], -50.0, 50.0) / 100.0;
        float radius = clamp(uP[1], 0.0, 80.0) / 100.0;
        bool v4 = uP[4] > 3.5;
        vec2 cen = v4 ? vec2(0.5) + vec2(uP[2], uP[3]) * uTexel
                      : vec2(0.5) - (vec2(uP[2], uP[3]) / 1000.0) * vec2(-1.0, 1.0);
        vec2 conv = vec2(1.0);
        if (v4) {
            if (uAspect > 1.0) conv.x = uAspect; else conv.y = 1.0 / uAspect;
        } else {
            conv.y = 1.0 / uAspect;
        }
        vec2 q2 = (vUv - cen) * conv;
        float dist = length(q2);
        if (dist < radius && radius > 0.000001) {
            float percent = (radius - dist) / radius;
            float A = strength <= 0.5 ? mix(0.0, 1.0, strength / 0.5) : mix(1.0, 0.0, (strength - 0.5) / 0.5);
            float theta = percent * percent * A * 8.0 * 3.14159;
            float S = sin(theta), C = cos(theta);
            q2 = vec2(dot(q2, vec2(C, -S)), dot(q2, vec2(S, C)));
        }
        vec2 suv = q2 / conv + cen;
        oColor = (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0);
        return;
    }
    if (uMode == 14) {
        // Halftone Dots (Alight Motion's halftonedots, its recovered shader): the picture is
        // re-read at each dot cell's centre-most point; the cell's brightness + the fragment's
        // distance inside the cell choose between the two colours. uP: amount (AM units),
        // strength %, angle (deg), phase %, colour 1 / colour 2 packed 0xRRGGBB. The same maths
        // lives in fx_plan.cpp (fxHalftoneDotsUv / fxHalftoneDotsMap).
        float amount = clamp(uP[0], 0.0, 500.0);
        vec4 tex = textureLod(uTex, vUv, 0.0);
        if (amount < 0.01) { oColor = tex; return; }   // AM would divide by ~0; a no-op instead
        float strength = clamp(uP[1], 0.0, 100.0) / 100.0;
        float rad = uP[2] * 0.0174533;
        float phase = clamp(uP[3], -100000.0, 100000.0) / 100.0;
        mat2 rot = mat2(cos(rad), -sin(rad), sin(rad), cos(rad));
        mat2 invrot = mat2(cos(-rad), -sin(-rad), sin(-rad), cos(-rad));
        vec2 st = vUv - 0.5;
        st.y *= (1.0 / uAspect);
        st = st * rot;
        float distY = mod(st.y * amount, 1.0);
        if (distY < 0.5) distY = -distY; else distY = 1.0 - distY;
        float absDistY = abs(distY);
        distY /= amount;
        float xoffs = step(1.0, mod(st.y * amount + 0.5, 2.0)) * phase;
        float distX = mod(st.x * amount + xoffs, 1.0);
        if (distX < 0.5) distX = -distX; else distX = 1.0 - distX;
        float absDistX = abs(distX);
        distX /= amount;
        st.y += distY;
        st.x += distX;
        st = st * invrot;
        st.y /= (1.0 / uAspect);
        st += 0.5;
        vec4 samp = (all(greaterThanEqual(st, vec2(0.0))) && all(lessThanEqual(st, vec2(1.0))))
            ? textureLod(uTex, st, 0.0) : vec4(0.0);
        float lum = dot(samp.rgb, vec3(0.2126, 0.7152, 0.0722));
        float p = smoothstep(0.4, 0.6, lum + (1.0 - samp.a) + length(vec2(absDistX, absDistY)) + strength - 0.5);
        float pk1 = floor(clamp(uP[4], 0.0, 16777215.0) + 0.5);
        float pk2 = floor(clamp(uP[5], 0.0, 16777215.0) + 0.5);
        vec3 c1 = vec3(mod(floor(pk1 / 65536.0), 256.0), mod(floor(pk1 / 256.0), 256.0), mod(pk1, 256.0)) / 255.0;
        vec3 c2 = vec3(mod(floor(pk2 / 65536.0), 256.0), mod(floor(pk2 / 256.0), 256.0), mod(pk2, 256.0)) / 255.0;
        oColor = vec4(mix(c1, c2, p), 1.0) * tex.a;
        return;
    }
    if (uMode == 15) {
        // Offset (Alight Motion's offset, its recovered shader): the layer wrap-shifted by
        // offset / 1000 of its size, composited over itself; near the wrap seam the original
        // fades back in (the feather), and with mask on, the shifted copy shows only inside
        // the layer's own silhouette. uP[0] / uP[1] are the offset x / y (AM units),
        // uP[2] the feather %, uP[3] the mask. fxOffsetAmMap / fxOffsetFeather in fx_plan.cpp
        // are the CPU twins — keep the two in step.
        vec4 S = textureLod(uTex, vUv, 0.0);
        vec2 sc = vec2(mod(vUv.x - uP[0] / 1000.0, 1.0), mod(vUv.y + uP[1] / 1000.0, 1.0));
        float feather = clamp(uP[2], 0.0, 100.0) / 100.0;
        float fa = 1.0;
        if (feather >= 0.0001) {
            vec2 fo = vec2(mod(uP[0] / 1000.0, 1.0), mod(uP[1] / 1000.0, 1.0));
            vec2 fd = (0.5 - abs(fo - 0.5)) * 2.0 * feather;
            vec2 m = min(sc, vec2(1.0) - sc);
            fa = amSstep(0.0, fd.x, m.x) * amSstep(0.0, fd.y, m.y);
        }
        vec4 F = textureLod(uTex, sc, 0.0);
        float maskFactor = uP[3] > 0.5 ? S.a : 1.0;
        vec4 over = S * (1.0 - fa);
        oColor = over + F * maskFactor * (1.0 - over.a);
        return;
    }
    if (uMode == 16) {
        // Radial wipe (Alight Motion's radialwipe, its recovered shader): an angular matte
        // around the centre — the layer is multiplied by the visibility pp. uP[0] is the
        // progress (degrees), uP[1] the feather (degrees), uP[2] the direction (0 CW,
        // 1 CCW, 2 both), uP[3] the repeat count, uP[4] the centre point packed (AM units,
        // /1200 in the shader), uP[5] the offset (degrees, the start angle). fxRadialWipeAmPp
        // in fx_plan.cpp is the CPU twin — keep the two in step.
        float pk = floor(clamp(uP[4], 0.0, 16777215.0) + 0.5);
        vec2 cp = vec2(mod(floor(pk / 4096.0), 4096.0) - 2048.0, mod(pk, 4096.0) - 2048.0);
        vec2 st = vec2(vUv.x, 1.0 - vUv.y) - 0.5;
        st.x *= uAspect;
        st -= cp / 1200.0;
        float progress = uP[0];
        float feather = uP[1];
        int direction = int(uP[2] + 0.5);
        float repeat = max(uP[3], 1.0);
        float adjProgress = direction == 1 ? 360.0 - progress : progress;
        float end = adjProgress;
        end -= 180.0;
        end *= (360.0 + feather) / 360.0;
        end += 180.0;
        float offsetRad = (uP[5] - 90.0 + (direction == 2 ? end / 2.0 / repeat : 0.0)) * 0.0174533;
        mat2 offsetRot = mat2(cos(offsetRad), -sin(offsetRad), sin(offsetRad), cos(offsetRad));
        st = st * offsetRot;
        float adjFeather = max(0.01, feather * 0.0174533);
        float halfFeather = adjFeather / 2.0;
        float endRad = end * 0.0174533;
        float a = atan(st.y, st.x) + 3.14159;
        a = mod(a * repeat, 6.2831853072);
        float p0 = amSstep(adjFeather, 0.0, a);
        float p1 = amSstep(endRad - halfFeather, endRad + halfFeather, a);
        float p2 = amSstep(endRad - 6.2831853072 + halfFeather, endRad - 6.2831853072 - halfFeather, a);
        float pp = adjProgress <= 0.0001 ? 1.0 : adjProgress >= 359.999 ? 0.0 : min(1.0, p1 + p0 - p2);
        if (direction == 1) pp = 1.0 - pp;
        oColor = textureLod(uTex, vUv, 0.0) * pp;
        return;
    }
    if (uMode == 17) {
        // Hex shift (Alight Motion's hexshift, its recovered shader): every cell of a
        // hexagonal grid displaces the sampling by the cell's own fixed random vector.
        // uP[0] is the strength (AM raw), uP[1] the size, uP[2] / uP[3] the stretch x / y %,
        // uP[4] the angle (degrees), uP[5] the seed, uP[6] the screen-space flag; uQ[0] is
        // mask-to-layer and uQ[1] the position point packed (AM units). AM anchors the grid
        // to the screen in screen space; here it is anchored to the layer, like Turbulent
        // Displace's. fxHexShiftAmUv in fx_plan.cpp is the CPU twin — keep the two in step.
        vec4 tex = textureLod(uTex, vUv, 0.0);
        vec2 st = vUv - 0.5;
        st.x *= uAspect;
        vec2 scale = (uP[1] / 100.0) * (vec2(uP[2], uP[3]) / 100.0);
        float pk = floor(clamp(uQ[1], 0.0, 16777215.0) + 0.5);
        vec2 pos = vec2(mod(floor(pk / 4096.0), 4096.0) - 2048.0, mod(pk, 4096.0) - 2048.0) / 500.0 * vec2(1.0, -1.0);
        st /= scale;
        float rad = uP[4] * 0.0174533;
        mat2 rot = mat2(cos(rad), -sin(rad), sin(rad), cos(rad));
        st = st * rot;
        pos = pos * mat2(cos(-rad), -sin(-rad), sin(-rad), cos(-rad));
        vec2 modPos = mod(pos, vec2(2.0, 3.46));
        vec2 stepPos = pos - modPos;
        st -= modPos;
        vec2 grid = mod(st, vec2(1.0, 1.73)) - vec2(0.5, 0.865);
        vec2 ogrid = mod(st - vec2(0.5, 0.865), vec2(1.0, 1.73)) - vec2(0.5, 0.865);
        vec2 cell = length(grid) < length(ogrid) ? grid : ogrid;
        vec2 id = st - cell - stepPos + 1000.0;
        float dx = amHexRand(vec2(id.x, id.y + 3937.7249 * (uP[5] + 0.01)));
        float dy = amHexRand(vec2(id.x + 328.7319 + 432.7249 * (uP[5] + 0.01), id.y - 971.382));
        vec2 dv = (vec2(dx, dy) - 0.5) * 2.0;
        dv.x *= uAspect;
        vec4 samp = pic(vUv + dv * uP[0]);
        if (uQ[0] > 0.5) {
            samp *= tex.a;
            oColor = tex * (1.0 - samp.a) + samp;
        } else {
            oColor = samp;
        }
        return;
    }
    if (uMode == 18) {
        // Wave Warp (Alight Motion's wavewarp2, its recovered shader): a sine wave along
        // direction a1 displaces the sampling along a1 + a2, its spacing and magnitude
        // damped by the distance from the damping origin. uP: phase (turns), a1d, m1, m2,
        // a2d, damping, dampingSpace; uQ[0] is the damping origin. fxWaveWarpAmUv in
        // fx_plan.cpp is the CPU twin — keep the two in step.
        float a1 = uP[1] * 0.0174533;
        float a2 = (uP[1] + uP[4]) * 0.0174533;
        vec2 rv = vec2(cos(a1), -sin(a1));
        float rawP = dot(vUv, rv);
        float spaceDamp = 1.0;
        if (uP[6] < 0.0) spaceDamp = 1.0 - (clamp(abs(rawP - uQ[0]), 0.0, 1.0) * (0.0 - uP[6]));
        else if (uP[6] > 0.0) spaceDamp = 1.0 - ((1.0 - clamp(abs(rawP - uQ[0]), 0.0, 1.0)) * uP[6]);
        float space = uP[2] * spaceDamp;
        float p = rawP * space;
        float ddist = space != 0.0 ? abs(p / space) : 0.0;
        float damp = 1.0;
        if (uP[5] < 0.0) damp = 1.0 - (clamp(abs(ddist - uQ[0]), 0.0, 1.0) * (0.0 - uP[5]));
        else if (uP[5] > 0.0) damp = 1.0 - ((1.0 - clamp(abs(ddist - uQ[0]), 0.0, 1.0)) * uP[5]);
        vec2 offs = vec2(cos(a2), -sin(a2)) * ((uP[3] * damp) / 100.0) * sin(p + uP[0] * 6.28318);
        vec2 suv = vUv + offs;
        oColor = (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0);
        return;
    }
    if (uMode == 19) {
        // Circular Ripple (Alight Motion's circularripple2 = circularripple, their
        // recovered shader): a radial sine wave around the centre, feathered at the rim.
        // uP: strength, radius, frequency, feather, phase, centre x / y (AM units).
        // AM corrects y by the SCREEN aspect; the layer's is the stand-in here (identical
        // for full-frame layers). fxCircularRippleAmUv in fx_plan.cpp is the CPU twin.
        float featherSize = uP[1] * 0.5 * uP[3];
        float inner = max(0.0, uP[1] - featherSize);
        float outer = max(inner + 0.00001, uP[1] + featherSize);
        vec2 cen = vec2(-uP[5] / 1000.0, uP[6] / 1000.0);
        vec2 st = vUv - 0.5 + cen;
        st.y *= (1.0 / uAspect);
        float dist = length(st);
        float damping = amSstep(outer, inner, dist);
        vec2 nrm = dist > 0.000001 ? st / dist : vec2(0.0);
        st += sin(dist * uP[2] * 6.28319 + uP[4] * 6.28319) * uP[0] / 2.0 * nrm * damping;
        st.y /= (1.0 / uAspect);
        vec2 suv = st + 0.5 - cen;
        oColor = (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0);
        return;
    }
    if (uMode == 20) {
        // Stretch (Alight Motion's stretch2 / stretch, their recovered shaders): the layer
        // is scaled along a rotated axis; under contentOnly the sample is masked by the
        // layer's own alpha — v2 keeps it alone, v1 composites the layer under it.
        // uP: scale, angle (deg), contentOnly, version. fxStretchAmUv / fxStretchAmMap in
        // fx_plan.cpp are the CPU twins.
        float rad = uP[1] * 0.0174533;
        mat2 rot = mat2(cos(rad), -sin(rad), sin(rad), cos(rad));
        mat2 invrot = mat2(cos(-rad), -sin(-rad), sin(-rad), cos(-rad));
        vec2 sc = ((((vUv - 0.5) * rot) * vec2(1.0 / clamp(uP[0], 0.01, 50.0), 1.0)) * invrot) + 0.5;
        vec4 S = (all(greaterThanEqual(sc, vec2(0.0))) && all(lessThanEqual(sc, vec2(1.0))))
            ? textureLod(uTex, sc, 0.0) : vec4(0.0);
        if (uP[2] > 0.5) {
            vec4 T = textureLod(uTex, vUv, 0.0);
            S *= T.a;
            if (uP[3] < 1.5) S = S + T * (1.0 - S.a);
        }
        oColor = S;
        return;
    }
    if (uMode == 21) {
        // Squeeze (Alight Motion's squeeze, its recovered shader — the def pins the axis
        // rule: x is divided by a factor driven by |y| with -strength, y by |x| with
        // +strength). uP[0] is the strength (AM raw). fxSqueezeAmUv is the CPU twin.
        float str = uP[0] / 2.0;
        vec2 st = (vUv - 0.5) * 2.0;
        float xdiv = clamp(1.0 + (1.0 - st.y * st.y) * (-str), 0.0, 2.0);
        float ydiv = clamp(1.0 + (1.0 - st.x * st.x) * str, 0.0, 2.0);
        st.x = xdiv != 0.0 ? st.x / xdiv : st.x;
        st.y = ydiv != 0.0 ? st.y / ydiv : st.y;
        vec2 suv = st / 2.0 + 0.5;
        oColor = (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0);
        return;
    }
    if (uMode == 22) {
        // Fractal Warp (Alight Motion's fractalwarp4, its recovered shader): two value-
        // noise fbm fields displace the sampling. uP: magnitude, scale, intensity,
        // octaves, offs x/y, parallax x; uQ[0]: parallax y (components, so
        // keyframed points animate). fxFractalWarpAmUv in fx_plan.cpp is the CPU twin.
        vec2 st = vUv;
        st.x *= uAspect;
        st += vec2(uP[4], uP[5]) / 1000.0 * vec2(-1.0, 1.0);
        vec2 par = vec2(uP[6], uQ[0]) / 200.0 * vec2(-1.0, 1.0);
        int oct = int(clamp(uP[3], 1.0, 9.0) + 0.5);
        float dx = amValueFbm((st - 0.5) * 3.0 * uP[1] + 0.5, par, oct, uP[2]);
        float dy = amValueFbm((st + vec2(25.3, 12.9) - 0.5) * 3.0 * uP[1] + 0.5, par, oct, uP[2]);
        vec2 suv = vUv + (vec2(dx, dy) - 0.5) * uP[0];
        oColor = (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0);
        return;
    }
    if (uMode == 23) {
        // Stripes (Alight Motion's stripes, its recovered shader): rotated bands of a
        // colour composited over the layer (or punched out of it). uP: amount, width,
        // angle (deg), phase, colour packed 0xRRGGBB, smoothing, punchout.
        // fxStripesAmBand / fxStripesAmMap in fx_plan.cpp are the CPU twins.
        vec4 tex = textureLod(uTex, vUv, 0.0);
        vec2 picSize = 1.0 / uTexel;
        vec2 st = (vec2(vUv.x, 1.0 - vUv.y) * picSize - picSize * 0.5) / length(picSize);
        float a = uP[2] * 0.0174533;
        st *= mat2(sin(a), -cos(a), cos(a), sin(a));
        float p = mod(st.x * uP[0] + uP[3], 1.0);
        float band = amSstep(uP[1], uP[1] - uP[5], p) * amSstep(0.0, uP[5], p);
        float pk = floor(clamp(uP[4], 0.0, 16777215.0) + 0.5);
        vec3 col = vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
        if (uP[6] > 0.5) {
            oColor = tex * (1.0 - band);
        } else {
            float ca = band * tex.a;
            oColor = vec4(col * ca + tex.rgb * (1.0 - ca), ca + tex.a * (1.0 - ca));
        }
        return;
    }
    if (uMode == 24) {
        // Turbulent Displace 3 (Alight Motion's turbulentdisplace3, its recovered
        // shader): Ashima turbulence displaces the sampling along a direction (0 X, 1 Y,
        // 2 XY, 3 R, 4 φr, 5 φ). uP: intensity, scale, evolution, seed, direction,
        // octaves, offs; uQ: parallax x / y, position x / y, stretch x / y, flags
        // (screen space + 2 * single axis; screen space is layer-anchored here and only
        // swaps the stretch). fxTurbulentDisplace3AmUv in fx_plan.cpp is the CPU twin.
        vec2 st = vUv;
        st.x *= uAspect;
        st += vec2(uQ[2], uQ[3]) * vec2(-1.0, 1.0) / 500.0;
        vec2 par = vec2(uQ[0], uQ[1]) * vec2(-1.0, 1.0) / 100.0;
        vec2 scaleVec = vec2(uP[1] * uQ[5], uP[1] * uQ[4]);
        if (mod(uQ[6], 2.0) > 0.5) { float t = scaleVec.x; scaleVec.x = scaleVec.y; scaleVec.y = t; }
        int dir = int(uP[4] + 0.5);
        vec2 sc;
        if (floor(uQ[6] / 2.0) < 0.5) sc = st - 0.5;
        else if (dir == 0) sc = vec2(0.0, st.y - 0.5);
        else if (dir == 1) sc = vec2(st.x - 0.5, 0.0);
        else if (dir == 2 || dir == 4) sc = st - 0.5;
        else if (dir == 5) sc = vec2(0.0, length(st - 0.5));
        else sc = vec2(abs(atan(st.x - 0.5, st.y - 0.5) / 6.28318) * 2.0, 0.0);
        int oct = int(clamp(uP[5], 1.0, 9.0) + 0.5);
        float p = amTurbulence((sc - 0.5) / scaleVec + 0.5, oct, par, uP[2] + 7391.935 * uP[3]);
        p += (uP[6] - 0.25);
        p *= uP[0] * 2.0;
        vec2 suv = vUv;
        vec2 radial = suv - 0.5;
        float rl = length(radial);
        vec2 rdir = rl > 0.000001 ? radial / rl : vec2(0.0);
        if (dir == 0) {
            suv.x += p;
        } else if (dir == 1) {
            suv.y += p;
        } else if (dir == 2) {
            float q = amTurbulence(sc / scaleVec + 0.5, oct, par, uP[2] + 3937.7249 * (uP[3] + 6.3379));
            q += (uP[6] - 0.25);
            q *= uP[0] * 2.0;
            suv.x += p;
            suv.y += q;
        } else if (dir == 4) {
            float q = amTurbulence(sc / scaleVec + 0.5, oct, par, uP[2] + 3937.7249 * (uP[3] + 6.3379));
            q += (uP[6] - 0.25);
            q *= uP[0] * 2.0;
            suv += rdir * p;
            float cq = cos(q), sq = sin(q);
            suv = vec2(cq * suv.x - sq * suv.y, sq * suv.x + cq * suv.y);
        } else if (dir == 5) {
            float cq = cos(p), sq = sin(p);
            suv = vec2(cq * suv.x - sq * suv.y, sq * suv.x + cq * suv.y);
        } else {
            suv += rdir * p;
        }
        float inside = step(0.0, suv.x) * step(suv.x, 1.0) * step(0.0, suv.y) * step(suv.y, 1.0);
        oColor = ((all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0)) * inside;
        return;
    }
    if (uMode == 25) {
        // Roughen Edges (Alight Motion's roughenedges, its recovered shader):
        // turbulentdisplace3's body with direction R and fixed geometry; the output is
        // the layer times the displaced sample's alpha (edges erode). uP: strength,
        // detail, evolution, seed, octaves. fxRoughenEdgesAmUv is the CPU twin.
        vec4 orig = textureLod(uTex, vUv, 0.0);
        vec2 st = vUv;
        st.x *= uAspect;
        float sv = max(uP[1], 0.01) * 5.0;
        int oct = int(clamp(uP[4], 1.0, 9.0) + 0.5);
        float p = amTurbulence((st - 0.5) * sv + 0.5, oct, vec2(0.0), uP[2] + 7391.935 * uP[3]);
        p += (0.15 - 0.25);
        p *= uP[0] * 0.1;
        vec2 radial = vUv - 0.5;
        float rl = length(radial);
        vec2 rdir = rl > 0.000001 ? radial / rl : vec2(0.0);
        vec2 suv = vUv + rdir * p;
        vec4 disp = (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0);
        oColor = orig * disp.a;
        return;
    }
    if (uMode == 26) {
        // Bend Warp (Alight Motion's bendwarp, its recovered shader): the part of the
        // layer past the offset line is bent along a circular arc from an origin edge;
        // before the line it freezes (or counter-bends). uP: amount (deg), offset,
        // origin type, opposite type, high limit, low limit. fxBendWarpAmUv in
        // fx_plan.cpp is the CPU twin — keep the two in step.
        float adjOffset = uP[1] * 0.99;
        vec2 a, b, c;
        bool vertical = false;
        float angleVal = uP[0];
        int origin = int(uP[2] + 0.5);
        if (origin == 0) {
            a = vec2(adjOffset, 0.5); b = vec2(1.0, 0.5); c = vec2(0.0, 0.5);
        } else if (origin == 1) {
            a = vec2(1.0 - adjOffset, 0.5); b = vec2(0.0, 0.5); c = vec2(1.0, 0.5);
            angleVal = -uP[0];
        } else if (origin == 2) {
            a = vec2(0.5, 1.0 - adjOffset); b = vec2(0.5, 0.0); c = vec2(0.5, 1.0);
            vertical = true;
        } else {
            a = vec2(0.5, adjOffset); b = vec2(0.5, 1.0); c = vec2(0.5, 0.0);
            vertical = true;
            angleVal = -uP[0];
        }
        vec2 a2b = b - a;
        float check = dot(a2b, vUv - a);
        vec2 suv = vUv;
        bool passthrough = false;
        if (check < 0.0) {
            if (uP[3] < 0.5 || adjOffset <= 0.0) {
                passthrough = true;
            } else {
                if (adjOffset < uP[5] && uP[5] != 0.0) angleVal *= (adjOffset / uP[5]);
                a2b = c - a;
                if (uP[3] > 1.5) angleVal = -angleVal;
            }
        } else {
            if (adjOffset >= uP[4] * 0.99) {
                float den = 0.99 - uP[4] * 0.99;
                if (den != 0.0) angleVal *= (1.0 - (adjOffset - uP[4] * 0.99) / den);
            }
        }
        if (!passthrough) {
            angleVal = angleVal * 0.01745329252 * 0.5 / max(length(a2b), 0.000001);
            if (vertical) {
                vec2 vv = vec2(vUv.x * uAspect, vUv.y);
                vec2 av = vec2(a.x * uAspect, a.y);
                vec2 uv2 = amBendCurve(vv - av, angleVal, true);
                suv = vec2(uv2.x / uAspect + a.x, uv2.y + a.y);
            } else {
                vec2 vv = vec2(vUv.x, vUv.y / uAspect);
                vec2 av = vec2(a.x, a.y / uAspect);
                vec2 uv2 = amBendCurve(vv - av, angleVal, false);
                suv = vec2(uv2.x + a.x, uv2.y * uAspect + a.y);
            }
        }
        oColor = (all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0);
        return;
    }
    if (uMode == 27) {
        // v175: Halftone Lines (Alight Motion's halftonelines, its recovered shader):
        // halftone dots' sibling — the cell fold runs on the rotated y axis only, the
        // band distance is the scalar fold, and the phase enters the mod RAW (its
        // spinner range is -1000..1000, not a percent). uP: amount (AM units),
        // strength (AM 0..1), angle (deg), phase (raw), colour 1 / colour 2 packed
        // 0xRRGGBB. The same maths lives in fx_plan.cpp (fxHalftoneLinesUv /
        // fxHalftoneLinesMap).
        float amount = clamp(uP[0], 0.0, 500.0);
        vec4 tex = textureLod(uTex, vUv, 0.0);
        if (amount < 0.01) { oColor = tex; return; }   // AM would divide by ~0; a no-op instead
        float rad = uP[2] * 0.0174533;
        mat2 rot = mat2(cos(rad), -sin(rad), sin(rad), cos(rad));
        mat2 invrot = mat2(cos(-rad), -sin(-rad), sin(-rad), cos(-rad));
        vec2 st = vUv - 0.5;
        st.y *= (1.0 / uAspect);
        st = st * rot;
        float distY = mod(st.y * amount + uP[3], 1.0);
        if (distY < 0.5) distY = -distY; else distY = 1.0 - distY;
        float absDist = abs(distY);
        st.y += distY / amount;
        st = st * invrot;
        st.y /= (1.0 / uAspect);
        st += 0.5;
        vec4 samp = (all(greaterThanEqual(st, vec2(0.0))) && all(lessThanEqual(st, vec2(1.0))))
            ? textureLod(uTex, st, 0.0) : vec4(0.0);
        float lum = dot(samp.rgb, vec3(0.2126, 0.7152, 0.0722));
        float p = smoothstep(0.4, 0.6, lum + (1.0 - samp.a) + absDist + uP[1] - 0.5);
        float pk1 = floor(clamp(uP[4], 0.0, 16777215.0) + 0.5);
        float pk2 = floor(clamp(uP[5], 0.0, 16777215.0) + 0.5);
        vec3 c1 = vec3(mod(floor(pk1 / 65536.0), 256.0), mod(floor(pk1 / 256.0), 256.0), mod(pk1, 256.0)) / 255.0;
        vec3 c2 = vec3(mod(floor(pk2 / 65536.0), 256.0), mod(floor(pk2 / 256.0), 256.0), mod(pk2, 256.0)) / 255.0;
        oColor = vec4(mix(c1, c2, p), 1.0) * tex.a;
        return;
    }
    if (uMode == 28) {
        // v180: Alight's RGB Glitch (Ultra original): horizontal slices jump
        // sideways, then one channel is taken from an offset sample; the clock
        // is floor(progressa * 36) (the def's floor(progressa * 0.6 * 60)).
        // uP: progressa, progressb, progressc, progressd. fxRgbGlitchAmPixel
        // in fx_plan.cpp is the twin.
        vec4 tex = textureLod(uTex, vUv, 0.0);
        if (uP[1] <= 0.0) { oColor = tex; return; }
        float time = floor(uP[0] * 36.0);
        vec3 outCol = tex.rgb;
        float maxOffset = uP[1] / 2.0;
        for (int i = 0; i < 1000; i++) {
            if (float(i) >= 10.0 * uP[1]) break;
            float sliceY = amGlitchRnd(time, 2345.0 + float(i));
            float sliceH = amGlitchRnd(time, 9035.0 + float(i)) * uP[3];
            float hOffset = -maxOffset + amGlitchRnd(time, 9625.0 + float(i)) * (2.0 * maxOffset);
            float top = fract(sliceY + sliceH);
            float inside = step(sliceY, vUv.y) - step(top, vUv.y);
            if (inside == 1.0)
                outCol = textureLod(uTex, vec2(vUv.x + hOffset, vUv.y), 0.0).rgb;
        }
        float maxColOffset = uP[2] / 6.0;
        float r0 = amGlitchRnd(time, 9545.0);
        vec2 coff = vec2(-maxColOffset + r0 * (2.0 * maxColOffset),
                         -maxColOffset + amGlitchRnd(time, 7205.0) * (2.0 * maxColOffset));
        vec4 cs2 = textureLod(uTex, vUv + coff, 0.0);
        if (r0 < 0.33) outCol.r = cs2.r;
        else if (r0 < 0.66) outCol.g = cs2.g;
        else outCol.b = cs2.b;
        oColor = vec4(outCol * tex.a, tex.a);
        return;
    }
    if (uMode == 29) {
        // v180: Alight's Fisheye / Anti-Fisheye (Ultra original): the tan / atan
        // radial remap around the centre, the sample coordinate clamped like
        // the def. uP: power, centerX, centerY, aspectMode, alpha.
        // fxFisheyeAmPixel in fx_plan.cpp is the twin.
        float prop = uAspect;
        bool am2 = uP[3] != 0.0;
        vec2 m = vec2(uP[1], am2 ? uP[2] / prop : uP[2]);
        vec2 w = vec2(vUv.x, am2 ? vUv.y / prop : vUv.y);
        vec2 d = w - m;
        float r = length(d);
        float k = uP[0] * PI;
        float bind = 1.0;
        if (k > 0.0) bind = length(m);
        else if (k < 0.0) bind = prop < 1.0 ? m.x : m.y;
        vec2 u2 = w;
        if (k > 0.0) {
            float f = r > 1e-9 ? tan(r * k) * bind / tan(bind * k) / r : 1.0;
            u2 = m + d * f;
        } else if (k < 0.0) {
            float kk = -k * 10.0;
            float f = r > 1e-9 ? atan(r * kk) * bind / atan(kk * bind) / r : 1.0;
            u2 = m + d * f;
        }
        if (am2) u2.y *= prop;
        u2 = clamp(u2, 0.0, 1.0);
        vec4 src = textureLod(uTex, u2, 0.0);
        float a = src.a * uP[4];
        oColor = vec4(src.rgb * a, a);
        return;
    }
    if (uMode == 30) {
        // v180: Alight's Refraction Warp (Ultra original, pass 1 only — pass 0
        // is a dead copy): the cos-field refraction; the clock is the timeline
        // frame when autop is on. uP: autop, PROGRESS, SPEEED, scale, position
        // packed, intensity, frequency; uQ: emboss, steps, angle, delta, gain,
        // reflectionCutOff, reflectionIntensity; uR: speed, speed_x, speed_y,
        // alpha. fxRefractionAmPixel in fx_plan.cpp is the twin.
        vec4 tex = textureLod(uTex, vUv, 0.0);
        float time = uP[0] > 0.5 ? uP[1] + (float(uFrame) / 24.0) * uP[2] : uP[1];
        vec2 pos = amUnpackPoint(uP[4]);
        float sc = uP[3] != 0.0 ? uP[3] : 1.0;
        vec2 ume = vec2(-(vUv.x / sc) - pos.x / 500.0, -(vUv.y / sc) - pos.y / 500.0);
        vec2 p = vec2(-ume.x, ume.y);
        float cc1 = amRefractionCol(p, time);
        float dx = uQ[0] * (cc1 - amRefractionCol(p + vec2((1.0 / uTexel.x) / uQ[3], 0.0), time)) / uQ[3];
        float dy = uQ[0] * (cc1 - amRefractionCol(p + vec2(0.0, (1.0 / uTexel.y) / uQ[3]), time)) / uQ[3];
        float alphaa = 1.0 + dx * dy * uQ[4];
        float ddx = dx - uQ[5], ddy = dy - uQ[5];
        if (ddx > 0.0 && ddy > 0.0) alphaa = pow(uR[3], ddx * ddy * (uQ[6] * 1000.0));
        vec4 colr = textureLod(uTex, vec2(p.x + dx * 2.0, -(p.y + dy * 2.0)), 0.0) * alphaa;
        oColor = mix(tex, colr, uR[3]);
        return;
    }
    if (uMode == 31) {
        // v180: Alight's Turbulent Displace 2 (TrueMotion afterz-112): AM's
        // turbulence() field applied per direction mode (0 X, 1 Y, 2 XY,
        // 3 radial, 4 radial + twist, 5 twist) — turbulentdisplace3's sibling
        // (mode 24) with the scale MULTIPLYING the sample coordinate and this
        // def's own slot layout. uP: intensity, evolution, direction, scale,
        // stretch, seed, singleAxis; uQ: screenSpace (carried; this pipeline is
        // layer-local either way), octaves, offs, position packed, parallax
        // packed. fxTurbulent2AmUv in fx_plan.cpp is the twin.
        vec2 st = vUv;
        st.x *= uAspect;
        vec2 pos = amUnpackPoint(uQ[3]);
        vec2 par = amUnpackPoint(uQ[4]) * vec2(-1.0, 1.0) / 100.0;
        st += pos * vec2(-1.0, 1.0) / 500.0;
        vec2 scaleVec = uP[4] < 0.0 ? vec2(uP[3] - uP[4], uP[3]) : vec2(uP[3], uP[3] + uP[4]);
        int dir = int(uP[2] + 0.5);
        vec2 sc;
        if (uP[6] < 0.5) sc = st - 0.5;
        else if (dir == 0) sc = vec2(0.0, st.y - 0.5);
        else if (dir == 1) sc = vec2(st.x - 0.5, 0.0);
        else if (dir == 2 || dir == 4) sc = st - 0.5;
        else if (dir == 5) sc = vec2(0.0, length(st - 0.5));
        else sc = vec2(abs(atan(st.x - 0.5, st.y - 0.5) / 6.28318) * 2.0, 0.0);
        int oct = int(clamp(uQ[1], 1.0, 9.0) + 0.5);
        float p = amTurbulence((sc - 0.5) * scaleVec + 0.5, oct, par, uP[1] + 7391.935 * uP[5]);
        p += (uQ[2] - 0.25);
        p *= uP[0] * 2.0;
        vec2 suv = vUv;
        vec2 radial = suv - 0.5;
        float rl = length(radial);
        vec2 rdir = rl > 0.000001 ? radial / rl : vec2(0.0);
        if (dir == 0) {
            suv.x += p;
        } else if (dir == 1) {
            suv.y += p;
        } else if (dir == 2) {
            float q = amTurbulence(sc * scaleVec + 0.5, oct, par, uP[1] + 3937.7249 * (uP[5] + 6.3379));
            q += (uQ[2] - 0.25);
            q *= uP[0] * 2.0;
            suv.x += p;
            suv.y += q;
        } else if (dir == 4) {
            float q = amTurbulence(sc * scaleVec + 0.5, oct, par, uP[1] + 3937.7249 * (uP[5] + 6.3379));
            q += (uQ[2] - 0.25);
            q *= uP[0] * 2.0;
            suv += rdir * p;
            float cq = cos(q), sq = sin(q);
            suv = vec2(cq * suv.x - sq * suv.y, sq * suv.x + cq * suv.y);
        } else if (dir == 5) {
            float cq = cos(p), sq = sin(p);
            suv = vec2(cq * suv.x - sq * suv.y, sq * suv.x + cq * suv.y);
        } else {
            suv += rdir * p;
        }
        float inside = step(0.0, suv.x) * step(suv.x, 1.0) * step(0.0, suv.y) * step(suv.y, 1.0);
        oColor = ((all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
            ? textureLod(uTex, suv, 0.0) : vec4(0.0)) * inside;
        return;
    }
    if (uMode == 32) {
        // v180: Alight's CC Glass (Ultra "Glass Surface"): the bump gradient
        // from the layer's own content, a refraction offset, Blinn-Phong
        // light. uP: property, softness, height, displacement, lightType,
        // lightIntensity, lightColor packed; uQ: lightHeight, lightPosition
        // x / y, lightDirection, ambient, diffuse, specular; uR: roughness,
        // metal. fxCcGlassAmPixel in fx_plan.cpp is the twin.
        float soft = max(uP[1], 1.0) * 0.001;
        vec3 n = vec3(
            amGlassVal(textureLod(uTex, vUv + vec2(-soft, 0.0), 0.0)) -
                amGlassVal(textureLod(uTex, vUv + vec2(soft, 0.0), 0.0)),
            amGlassVal(textureLod(uTex, vUv + vec2(0.0, -soft), 0.0)) -
                amGlassVal(textureLod(uTex, vUv + vec2(0.0, soft), 0.0)),
            1.0 / (uP[2] * 0.1));
        float nl = length(n);
        n = (nl > 0.0 && nl < 1e30) ? n / nl : vec3(0.0, 0.0, 1.0);  // height 0 degenerates to +z
        vec4 src = textureLod(uTex, vUv + n.xy * (uP[3] * 0.01), 0.0);
        vec3 lDir;
        if (uP[4] < 0.5) {
            float rad = (uQ[3] - 90.0) * (PI / 180.0);
            float hrad = (uQ[0] * 0.9) * (PI / 180.0);
            lDir = normalize(vec3(cos(rad) * cos(hrad), sin(rad) * cos(hrad), sin(hrad)));
        } else {
            lDir = normalize(vec3(uQ[1] - vUv.x, uQ[2] - vUv.y, uQ[0] * 0.01));
        }
        vec3 halfDir = normalize(lDir + vec3(0.0, 0.0, 1.0));
        float diff = max(dot(n, lDir), 0.0);
        float spec = pow(max(dot(n, halfDir), 0.0), 1.0 / max(uR[0], 0.001));
        float pk = floor(clamp(uP[6], 0.0, 16777215.0) + 0.5);
        vec3 lc = vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
        float inten = uP[5] * 0.01;
        vec3 ambCol = src.rgb * (uQ[4] * 0.01);
        vec3 diffCol = src.rgb * diff * (uQ[5] * 0.01) * lc * inten;
        vec3 specBase = lc * spec * (uQ[6] * 0.01) * inten;
        vec3 specCol = mix(specBase, specBase * src.rgb, uR[1] * 0.01);
        oColor = vec4(ambCol + diffCol + specCol, src.a);
        return;
    }
    if (uMode == 33) {
        // v180: Alight's Solid Color Wipe (Ultra original): the wipe2 mask
        // along the angle, the solidcolor blend switch inside it. uP:
        // fillColor packed, its alpha (carried, unread by the def), alpha,
        // blendMode, start, end, angle; uQ[0]: feather. fxSolidWipeAmPixel
        // in fx_plan.cpp is the twin.
        vec4 tex = textureLod(uTex, vUv, 0.0);
        float rad = uP[6] * 0.0174533;
        vec2 v = vec2(cos(rad), -sin(rad));
        float p = dot(vUv - 0.5, v) + 0.5;
        float feather = uQ[0] / 100.0;
        float p0 = amSstepRev(uP[4] - feather, uP[4], p);
        float p1 = 1.0 - amSstepRev(uP[5], uP[5] + feather, p);
        float mask = clamp(p0 * p1, 0.0, 1.0);
        float pk = floor(clamp(uP[0], 0.0, 16777215.0) + 0.5);
        vec3 fill = vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
        vec3 straight = tex.a > 0.0 ? tex.rgb / tex.a : vec3(0.0);
        vec3 res = uP[3] > 1.5 ? 1.0 - (1.0 - straight) * (1.0 - fill)
                   : uP[3] > 0.5 ? straight * fill : fill;
        oColor = mix(tex, vec4(res, 1.0) * tex.a, uP[2] * mask);
        return;
    }
    vec2 p = toP(vUv);
    vec2 q = p;
    vec2 qr = q, qb = q;  // the red / blue sample positions of the lens distortion colour fringes
    bool chroma = false;
    if (uMode == 0) {
        // Wave warp: a sine wave travelling along `direction`, pushing pixels sideways to it.
        float amp = clamp(uP[0], 0.0, 100.0) / 100.0 * 0.07;
        float wl = 0.02 + clamp(uP[1], 0.0, 100.0) / 100.0 * 0.78;
        float a = radians(uP[2]);
        vec2 dir = vec2(cos(a), -sin(a));
        vec2 nrm = vec2(-dir.y, dir.x);
        float ph = radians(uP[3]) + 6.2831853 * float(uFrame) / 24.0 * (uP[4] / 100.0);
        q = p + nrm * amp * sin(6.2831853 * dot(p, dir) / wl + ph);
    } else if (uMode == 1) {
        // Twirl: inside the radius the picture is turned, most in the centre.
        float R = max(clamp(uP[1], 0.0, 100.0) / 100.0 * 0.95, 0.001);
        vec2 cen = centreP(uP[2], uP[3]);
        vec2 d = p - cen;
        float r = length(d);
        float f = clamp(1.0 - r / R, 0.0, 1.0);
        float ang = radians(uP[0]) * f * f;
        float cs = cos(ang), sn = sin(ang);
        q = cen + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
    } else if (uMode == 2) {
        // Bulge / pinch: a lens, smooth at the rim.
        float R = max(clamp(uP[1], 0.0, 100.0) / 100.0 * 0.95, 0.01);
        vec2 cen = centreP(uP[2], uP[3]);
        float s = clamp(uP[0], -100.0, 100.0) / 100.0;
        float k = s >= 0.0 ? 1.0 + 2.0 * s : 1.0 / (1.0 + 2.0 * (-s));
        vec2 d = p - cen;
        float r = length(d);
        if (r < R && r > 1e-5) {
            float nr = pow(r / R, k) * R;
            q = cen + d / r * nr;
        }
    } else if (uMode == 3) {
        // Mirror: one side of a line is reflected onto the other.
        vec2 cen = centreP(uP[0], uP[1]);
        float a = radians(uP[2]);
        vec2 dir = vec2(cos(a), -sin(a));
        vec2 nrm = vec2(-dir.y, dir.x);
        float s = dot(p - cen, nrm);
        if (s < 0.0) q = p - 2.0 * s * nrm;
    } else if (uMode == 4) {
        // Kaleidoscope: the plane is cut into segments, every second one is mirrored.
        float n = clamp(floor(uP[0] + 0.5), 2.0, 24.0);
        vec2 cen = centreP(uP[2], uP[3]);
        float zoom = max(clamp(uP[4], 20.0, 300.0) / 100.0, 0.05);
        vec2 d = (p - cen) / zoom;
        float r = length(d);
        float a = atan(d.y, d.x) - radians(uP[1]);
        float seg = 2.0 * PI / n;
        a = mod(a, seg);
        a = abs(a - seg * 0.5);
        a += radians(uP[5]);
        q = cen + r * vec2(cos(a), sin(a));
    } else if (uMode == 5) {
        // Turbulent displace: fractal noise pushes the pixels around (the "heat haze" / liquid look).
        float amt = clamp(uP[0], 0.0, 100.0) / 100.0 * 0.10;
        float freq = mix(24.0, 1.5, clamp(uP[1], 1.0, 100.0) / 100.0);
        int oct = int(clamp(floor(uP[2] + 0.5), 1.0, 6.0));
        float z = radians(uP[3]) / 6.2831853 * 4.0 + float(uFrame) / 24.0 * (uP[4] / 100.0) * 2.0;
        vec3 b = vec3(p * freq, z);
        vec2 dsp = vec2(fbm(b, oct), fbm(b + vec3(17.3, 31.7, 9.1), oct)) * 2.0 - 1.0;
        q = p + dsp * amt;
    } else if (uMode == 6) {
        // Ripple: rings travel out of a centre and push the pixels along the radius.
        float amp = clamp(uP[0], 0.0, 100.0) / 100.0 * 0.06;
        float wl = 0.01 + clamp(uP[1], 0.0, 100.0) / 100.0 * 0.4;
        vec2 cen = centreP(uP[2], uP[3]);
        float R = clamp(uP[4], 0.0, 100.0) / 100.0 * 1.6 + 0.001;
        vec2 d = p - cen;
        float r = length(d);
        float ph = radians(uP[6]) + 6.2831853 * float(uFrame) / 24.0 * (uP[5] / 100.0);
        float env = 1.0 - smoothstep(0.0, R, r);
        float off = sin(6.2831853 * r / wl - ph) * amp * env;
        q = p + (r > 1e-5 ? d / r : vec2(0.0)) * off;
    } else if (uMode == 7) {
        // Lens distortion: barrel (+) or pincushion (-) over the whole picture, optional colour fringes.
        float k = clamp(uP[0], -100.0, 100.0) / 100.0 * 0.9;
        float zm = max(clamp(uP[1], 50.0, 200.0) / 100.0, 0.05);
        float ca = clamp(uP[2], 0.0, 100.0) / 100.0 * 0.03;
        vec2 cen = centreP(uP[3], uP[4]);
        vec2 d = (p - cen) / zm;
        vec2 dd = d * (1.0 + k * dot(d, d));
        q = cen + dd;
        if (ca > 0.0001) {
            chroma = true;
            qr = cen + dd * (1.0 + ca);
            qb = cen + dd * (1.0 - ca);
        }
    } else if (uMode == 8) {
        // Polar coordinates: the picture bent into a circle (x -> angle, y -> radius), or a circle opened up again.
        float t = clamp(uP[0], 0.0, 100.0) / 100.0;
        vec2 m;
        if (uP[1] < 0.5) {
            m = toP(vec2(atan(p.x, -p.y) / 6.2831853 + 0.5, 1.0 - length(p) * 2.0));
        } else {
            float a = (vUv.x - 0.5) * 6.2831853;
            float rn = (1.0 - vUv.y) * 0.5;
            m = rn * vec2(sin(a), -cos(a));
        }
        q = mix(p, m, t);
    } else if (uMode == 9) {
        // Motion tile: the picture repeated nx * ny times, optionally mirrored, scrolling.
        float nx = clamp(floor(uP[0] + 0.5), 1.0, 10.0);
        float ny = clamp(floor(uP[1] + 0.5), 1.0, 10.0);
        vec2 ph = vec2(uP[3], uP[4]) / 100.0 + vec2(uP[5], uP[6]) / 100.0 * float(uFrame) / 24.0 * 0.5;
        vec2 g = vUv * vec2(nx, ny) - ph;
        vec2 f = uP[2] > 0.5 ? 1.0 - abs(1.0 - mod(g, 2.0)) : fract(g);
        q = toP(f);
    } else if (uMode == 10) {
        // Camera shake: a random push, turn and zoom per step (smooth or jumpy), a little zoom-in hides the edges.
        float amt = clamp(uP[0], 0.0, 100.0) / 100.0;
        float rate = clamp(uP[1], 1.0, 24.0);
        float rotA = clamp(uP[2], 0.0, 100.0) / 100.0;
        float fill = clamp(uP[3], 0.0, 100.0) / 100.0;
        float smo = clamp(uP[4], 0.0, 100.0) / 100.0;
        float seed = uP[5];
        float t = float(uFrame) * rate / 24.0;
        float i0 = floor(t);
        float f = smoothstep(0.0, 1.0, t - i0);
        vec3 n0 = vec3(h3(vec3(i0, 1.0, seed)), h3(vec3(i0, 2.0, seed)), h3(vec3(i0, 3.0, seed))) * 2.0 - 1.0;
        vec3 n1 = vec3(h3(vec3(i0 + 1.0, 1.0, seed)), h3(vec3(i0 + 1.0, 2.0, seed)), h3(vec3(i0 + 1.0, 3.0, seed))) * 2.0 - 1.0;
        vec3 nn = mix(n0, mix(n0, n1, f), smo);
        float ang = nn.z * rotA * 0.06;
        float cs = cos(ang), sn = sin(ang);
        vec2 pr = vec2(p.x * cs - p.y * sn, p.x * sn + p.y * cs);
        q = pr / (1.0 + fill * 0.12) + nn.xy * amt * 0.04;
    }
    if (chroma) {
        vec4 ra = pic(toUv(qr)), ga = pic(toUv(q)), ba = pic(toUv(qb));
        float al = max(ra.a, max(ga.a, ba.a));
        oColor = vec4(clamp(vec3(ra.r, ga.g, ba.b), 0.0, al), al);
    } else {
        oColor = pic(toUv(q));
    }
}
)";

// ---------------------------------------------------------------------------------------------------------------------
// Style: mosaic, halftone, scanlines, glitch
// ---------------------------------------------------------------------------------------------------------------------
inline const char* kStyle = "#version 300 es\n"
R"(precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uAspect;
uniform float uUnit;
uniform int uFrame;
uniform int uMode;
uniform float uP[7];
uniform float uQ[7];
uniform float uR[7];
in vec2 vUv;
out vec4 oColor;

const float PI = 3.14159265;
vec4 tap(vec2 uv) { return textureLod(uTex, uv, 0.0); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}
float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
// hue 0..360, full saturation and value
vec3 hueRgb(float h) {
    vec3 k = vec3(1.0, 2.0 / 3.0, 1.0 / 3.0);
    vec3 p = abs(fract(vec3(h / 360.0) + k) * 6.0 - 3.0);
    return clamp(p - 1.0, 0.0, 1.0);
}
vec3 unpm(vec4 c) { return c.a > 0.001 ? c.rgb / c.a : vec3(0.0); }
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x), mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}
// Sobel gradient of the brightness (x, y); px = distance of the taps in pixels
vec2 sobel(vec2 uv, float px) {
    vec2 o = uTexel * px;
    float tl = luma(unpm(tap(uv + vec2(-o.x, o.y))));
    float t = luma(unpm(tap(uv + vec2(0.0, o.y))));
    float tr = luma(unpm(tap(uv + vec2(o.x, o.y))));
    float l = luma(unpm(tap(uv + vec2(-o.x, 0.0))));
    float r = luma(unpm(tap(uv + vec2(o.x, 0.0))));
    float bl = luma(unpm(tap(uv + vec2(-o.x, -o.y))));
    float b = luma(unpm(tap(uv + vec2(0.0, -o.y))));
    float br = luma(unpm(tap(uv + vec2(o.x, -o.y))));
    return vec2((tr + 2.0 * r + br) - (tl + 2.0 * l + bl), (tl + 2.0 * t + tr) - (bl + 2.0 * b + br));
}
// GLSL smoothstep by the formula, so REVERSED edges ramp the other way instead of being
// undefined (AM's simplestars2 relies on it). fxSstep in fx_plan.cpp is the same formula.
float amSstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
// A packed AM point (12-bit signed fields) back to its two components.
vec2 amUnpackPoint(float pk) {
    pk = floor(clamp(pk, 0.0, 16777215.0) + 0.5);
    return vec2(mod(floor(pk / 4096.0), 4096.0) - 2048.0, mod(pk, 4096.0) - 2048.0);
}
// A packed 0xRRGGBB colour back to straight rgb.
vec3 amUnpackColor(float pk) {
    pk = floor(clamp(pk, 0.0, 16777215.0) + 0.5);
    return vec3(mod(floor(pk / 65536.0), 256.0), mod(floor(pk / 256.0), 256.0), mod(pk, 256.0)) / 255.0;
}
// Alight shapefield's hashes and colour maths (mode 21); the CPU twins are
// q15jRand4 / q15jRgb2Hsv / q15jShapeColor in fx_plan.cpp.
float amSfRand4(vec4 v) {
    return fract(sin(dot(v, vec4(12.9898, 78.233, 193.71, 397.318))) * 43758.5453123);
}
float amSfRand3(vec3 v) {
    return fract(sin(dot(v, vec3(12.9898, 78.233, 193.71))) * 43758.5453123);
}
vec2 amSfRandVec2(vec3 v) {
    return vec2(amSfRand4(vec4(v, 5.124)), amSfRand4(vec4(v, 3.0)));
}
vec3 amSfRgb2Hsv(vec3 c) {
    vec4 p = c.g >= c.b ? vec4(c.g, c.b, 0.0, -1.0 / 3.0) : vec4(c.b, c.g, 2.0 / 3.0, -1.0);
    vec4 q = c.r >= p.x ? vec4(c.r, p.y, p.z, p.x) : vec4(p.x, p.y, p.w, c.r);
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 amSfHsv2Rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
vec4 amSfMixColors(vec4 a, vec4 b, float t) {
    if (uR[6] > 3.5) {  // the interpHue flag (bit 4 of uR[6], values 0..7)
        return vec4(amSfHsv2Rgb(mix(amSfRgb2Hsv(a.rgb), amSfRgb2Hsv(b.rgb), t)), mix(a.a, b.a, t));
    }
    return mix(a, b, t);
}
vec4 amSfGetColor(vec3 id, float layerDepth) {
    vec4 c1 = vec4(amUnpackColor(uR[3]), 1.0);
    vec4 c2 = vec4(amUnpackColor(uR[4]), 1.0);
    vec4 c3 = vec4(amUnpackColor(uR[5]), 1.0);
    int coloring = int(uR[2] + 0.5);
    float rr4 = amSfRand4(vec4(id, 0.1974));
    if (coloring == 1) return c1;
    if (coloring == 2) return c1 * rr4;
    if (coloring == 3) return amSfMixColors(c1, c2, rr4);
    if (coloring == 4) {
        vec2 rr = amSfRandVec2(id);
        return amSfMixColors(amSfMixColors(c1, c2, rr.x), c3, rr.y);
    }
    if (coloring == 5) return c1 * layerDepth;
    if (coloring == 6) return amSfMixColors(c1, c2, layerDepth);
    if (coloring == 7) return layerDepth < 0.5 ? amSfMixColors(c1, c2, layerDepth * 2.0)
                                               : amSfMixColors(c2, c3, (layerDepth - 0.5) * 2.0);
    if (coloring == 8) return amSfMixColors(c3, amSfMixColors(c1, c2, rr4), layerDepth);
    return vec4(0.0);
}
vec4 amSfGrid(vec2 st, float layer, float layerDepth) {
    st *= 10.0;
    vec2 uv = fract(st);
    vec3 id = vec3(floor(st), layer);
    float size = uQ[0];
    if (uQ[2] >= 0.0001)
        size = min(size, size * (1.0 + (amSfRand4(vec4(id, 0.351)) - 0.5) * uQ[2]));
    float angle = uQ[3] < 0.0001 ? 0.0
        : (amSfRand4(vec4(id, 0.2793)) - 0.5) * uQ[3] * 12.56636;
    float rr = amSfRand3(id);
    size *= amSstep(rr, rr + 0.008, uP[3]);
    if (size < 0.00001) return vec4(0.0);
    uv -= 0.5;
    float picW = 1.0 / uTexel.x, picH = 1.0 / uTexel.y;
    if (picW > picH) uv.y *= picW / picH; else uv.x *= picH / picW;
    uv /= size;
    float maxDisplace = 1.0 / size - 1.0;
    vec2 displace;
    if (abs(uP[4]) > 0.001) {
        float fl = floor(uP[4]);
        displace = mix(amSfRandVec2(id + fl), amSfRandVec2(id + fl + 1.0), fract(uP[4]));
    } else {
        displace = amSfRandVec2(id);
    }
    uv += clamp((displace - 0.5) * max(1.0, maxDisplace) * uR[0], -maxDisplace, maxDisplace);
    float ra = angle + uQ[1] * 0.0174532925;
    float rc = cos(ra), rs = sin(ra);
    uv = vec2(uv.x * rc + uv.y * rs, -uv.x * rs + uv.y * rc);
    uv += 0.5;
    uv.y = 1.0 - uv.y;
    vec4 texColor = tap(uv);
    vec4 blendColor = amSfGetColor(id, layerDepth);
    bool lockLum = mod(uR[6], 2.0) > 0.5;
    bool lockSat = mod(floor(uR[6] / 2.0), 2.0) > 0.5;
    if (lockLum || lockSat) {
        vec3 texHsv = amSfRgb2Hsv(texColor.a <= 0.0 ? vec3(0.0) : texColor.rgb / texColor.a);
        vec3 blendHsv = amSfRgb2Hsv(blendColor.a <= 0.0 ? vec3(0.0) : blendColor.rgb / blendColor.a);
        if (lockLum) blendHsv.z = texHsv.z;
        if (lockSat) blendHsv.y = texHsv.y;
        return vec4(amSfHsv2Rgb(mix(texHsv, blendHsv, blendColor.a)), 1.0) * texColor.a;
    }
    vec3 ts = texColor.a <= 0.0 ? vec3(0.0) : texColor.rgb / texColor.a;
    vec3 bs = blendColor.a <= 0.0 ? vec3(0.0) : blendColor.rgb / blendColor.a;
    return vec4(mix(ts, bs, blendColor.a), 1.0) * texColor.a;
}
// McGuire value noise (AM's dots2 helper set); fxValueNoise in fx_plan.cpp is the twin.
float amValueRandom(vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}
float amValueNoise(vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);
    float a = amValueRandom(i);
    float b = amValueRandom(i + vec2(1.0, 0.0));
    float c = amValueRandom(i + vec2(0.0, 1.0));
    float d = amValueRandom(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}
vec2 amDotsRand2v(vec2 co) {
    return vec2(amValueNoise(co), amValueNoise(co + vec2(613.312, 736.019)));
}
// simplestars2's rand / rand2 / star / stars (the def's own helpers).
float amStarRand(vec2 co) {
    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}
vec2 amStarRand2(vec2 co) {
    return vec2(amStarRand(co), amStarRand(co + vec2(3.7321, 9.2517) * vec2(7.1231, 6.9377)));
}
float amStar(vec2 st, float size, float feather) {
    float adjFeather = size * feather;
    return amSstep(1.0 - size - adjFeather, 1.0 - size, sin(st.x * 20.0) * sin(st.y * 20.0));
}
float amStars(vec2 st, vec2 parallax, float depth, float size, float feather, float starCount) {
    float p = 0.0;
    size *= 0.001;
    float f = fract(depth);
    float layer = floor(depth);
    for (int i = 1; i < 50; i++) {
        float fi = float(i);
        if (!(fi < starCount)) break;
        vec2 uv = st;
        uv *= (fi - f) / 9.0;
        uv += parallax;
        p += amStar(uv + amStarRand2(vec2(fi + layer, 2.904)), size, feather) * amSstep(starCount, 1.0, fi - f);
    }
    return p;
}
// Ashima simplex noise (AM's ridges def) and its ridged multifractal; fxSnoise3 /
// fxRidgesAmField in fx_plan.cpp are the CPU twins.
vec3 amRidMod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 amRidMod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 amRidPermute(vec4 x) { return amRidMod289(((x * 34.0) + 1.0) * x); }
vec4 amRidTaylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }
float amSnoise3(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;
    i = amRidMod289(i);
    vec4 p = amRidPermute(amRidPermute(amRidPermute(
        i.z + vec4(0.0, i1.z, i2.z, 1.0))
        + i.y + vec4(0.0, i1.y, i2.y, 1.0))
        + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = amRidTaylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x;
    p1 *= norm.y;
    p2 *= norm.z;
    p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}
float amRidge(float h) {
    h = abs(h);
    h = 0.9 - h;
    return h * h;
}
// AM's turbulence(): the plain octave sum of |snoise| (ridges' unfolded sibling;
// the warp program carries the same helper). fxTurbulence in fx_plan.cpp is the twin.
float amTurbulence(vec2 st, int octaves, vec2 parallax, float evolution) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 9; i++) {
        if (i >= octaves) break;
        value += amplitude * abs(amSnoise3(vec3(st, evolution)));
        st *= 2.0;
        st += parallax;
        amplitude *= 0.5;
    }
    return value;
}
float amRidgedMF(vec2 p, int octaves, vec2 parallax, float evo) {
    float sum = 0.0;
    float freq = 1.0, amp = 0.5;
    float prev = 1.0;
    for (int i = 0; i < 9; i++) {
        if (i >= octaves) break;
        float n = amRidge(amSnoise3(vec3(p * freq, evo)));
        sum += n * amp;
        sum += n * amp * prev;
        prev = n;
        freq *= 2.0;
        p += parallax;
        amp *= 0.5;
    }
    return sum;
}
// Kuwahara: mean colour and brightness variance of one 3 x 3 block that starts at this pixel and reaches R px towards `sgn`
void kuwaharaQuad(vec2 sgn, float R, out vec3 mean, out float vr) {
    vec3 sum = vec3(0.0);
    float sl = 0.0, sl2 = 0.0;
    for (int j = 0; j < 3; j++) {
        for (int i = 0; i < 3; i++) {
            vec3 s = unpm(tap(vUv + vec2(float(i), float(j)) * 0.5 * R * sgn * uTexel));
            float l = luma(s);
            sum += s;
            sl += l;
            sl2 += l * l;
        }
    }
    mean = sum / 9.0;
    float ml = sl / 9.0;
    vr = sl2 / 9.0 - ml * ml;
}
// The picture under the centre of the halftone cell this pixel is in (5 taps, so a cell gets the average of its area).
vec3 cellColour(vec2 px, float cell, float ang, vec2 res) {
    vec2 cs = vec2(cos(ang), sin(ang));
    vec2 g = vec2(px.x * cs.x + px.y * cs.y, -px.x * cs.y + px.y * cs.x) / cell;
    vec2 gc = (floor(g) + 0.5) * cell;
    vec2 pc = vec2(gc.x * cs.x - gc.y * cs.y, gc.x * cs.y + gc.y * cs.x);
    vec2 lo = uTexel * 0.5, hi = 1.0 - uTexel * 0.5;
    float o = cell * 0.3;
    vec4 s0 = tap(clamp(pc / res, lo, hi));
    vec4 s1 = tap(clamp((pc + vec2(o, 0.0)) / res, lo, hi));
    vec4 s2 = tap(clamp((pc - vec2(o, 0.0)) / res, lo, hi));
    vec4 s3 = tap(clamp((pc + vec2(0.0, o)) / res, lo, hi));
    vec4 s4 = tap(clamp((pc - vec2(0.0, o)) / res, lo, hi));
    vec4 sum = s0 * 0.36 + (s1 + s2 + s3 + s4) * 0.16;
    return sum.a > 0.001 ? sum.rgb / sum.a : vec3(0.0);
}
// ink coverage (0..1) of one halftone screen at this pixel: value = how much ink (0..1) the cell wants
float dotCover(vec2 px, float cell, float ang, float value, float soft) {
    float cs = cos(ang), sn = sin(ang);
    vec2 g = vec2(px.x * cs + px.y * sn, -px.x * sn + px.y * cs) / cell;
    vec2 f = fract(g) - 0.5;
    float d = length(f);
    // dot radius so that the inked AREA equals the wanted ink (circle area up to 78 %, then the dots grow into the corners)
    float v = clamp(value, 0.0, 1.0);
    float r = v <= 0.7854 ? sqrt(v / PI) : 0.5 + (v - 0.7854) / 0.2146 * 0.2071;
    float aa = 0.75 / cell + soft * 0.35;
    return 1.0 - smoothstep(r - aa, r + aa, d);
}

void main() {
    vec2 res = 1.0 / uTexel;
    vec4 c0 = tap(vUv);
    if (uMode == 0) {
        // Mosaic: square blocks, optional gap between them.
        float cell = max(1.0, clamp(uP[0], 1.0, 100.0) * uUnit * 0.5);
        vec2 px = vUv * res;
        vec2 cellId = floor(px / cell);
        vec2 centre = (cellId + 0.5) * cell;
        vec4 c = tap(clamp(centre / res, uTexel * 0.5, 1.0 - uTexel * 0.5));
        float gap = clamp(uP[1], 0.0, 40.0) / 100.0;
        vec2 f = abs(fract(px / cell) - 0.5) * 2.0;  // 0 centre .. 1 edge
        float edge = max(f.x, f.y);
        float aa = 1.0 / cell;
        float keep = gap > 0.001 ? 1.0 - smoothstep(1.0 - gap - aa, 1.0 - gap + aa, edge) : 1.0;
        oColor = c * keep;
    } else if (uMode == 1) {
        // Halftone: ink dots on paper. Mono = one angle on the luminance, colour = three screens (like cyan / magenta / yellow).
        float cell = max(2.0, clamp(uP[0], 2.0, 60.0) * uUnit * 0.6);
        float ang = radians(uP[1]);
        float soft = clamp(uP[2], 0.0, 100.0) / 100.0;
        float colour = clamp(uP[3], 0.0, 100.0) / 100.0;
        float mixv = clamp(uP[4], 0.0, 100.0) / 100.0;
        vec2 px = vUv * res;
        vec3 base = cellColour(px, cell, ang, res);
        float monoOut = 1.0 - dotCover(px, cell, ang, 1.0 - luma(base), soft);
        float a1 = ang + radians(15.0), a2 = ang + radians(75.0);
        vec3 col;
        col.r = 1.0 - dotCover(px, cell, a1, 1.0 - cellColour(px, cell, a1, res).r, soft);
        col.g = 1.0 - dotCover(px, cell, a2, 1.0 - cellColour(px, cell, a2, res).g, soft);
        col.b = 1.0 - dotCover(px, cell, ang, 1.0 - base.b, soft);
        vec3 ht = mix(vec3(monoOut), col, colour);
        vec3 orig = c0.a > 0.001 ? c0.rgb / c0.a : vec3(0.0);
        vec3 outc = mix(orig, ht, mixv);
        oColor = vec4(outc * c0.a, c0.a);
    } else if (uMode == 2) {
        // Scanlines (CRT): dark lines, RGB phosphor mask, flicker, a slow bright roll bar.
        float inten = clamp(uP[0], 0.0, 100.0) / 100.0;
        float size = max(1.0, clamp(uP[1], 1.0, 20.0) * uUnit);
        float maskAmt = clamp(uP[2], 0.0, 100.0) / 100.0;
        float flick = clamp(uP[3], 0.0, 100.0) / 100.0;
        float roll = uP[4] / 100.0;
        vec3 rgb = c0.a > 0.001 ? c0.rgb / c0.a : vec3(0.0);
        float y = gl_FragCoord.y;
        float line = 0.5 + 0.5 * cos(2.0 * PI * y / (size * 2.0));
        line = pow(line, 0.75);
        float lines = mix(1.0, line * 1.18, inten);
        // phosphor stripes: one third of the colour per pixel column
        float xi = mod(floor(gl_FragCoord.x / max(1.0, uUnit)), 3.0);
        vec3 stripe = vec3(xi < 0.5 ? 1.0 : 0.55, (xi > 0.5 && xi < 1.5) ? 1.0 : 0.55, xi > 1.5 ? 1.0 : 0.55);
        vec3 m = mix(vec3(1.0), stripe * 1.2, maskAmt);
        float fl = 1.0 - flick * 0.12 * hash11(float(uFrame) * 1.37);
        float bar = 0.0;
        if (abs(roll) > 0.001) {
            float t = fract(float(uFrame) / 24.0 * roll * 0.25);
            float yy = 1.0 - vUv.y;
            float dd = abs(fract(yy - t + 0.5) - 0.5);
            bar = smoothstep(0.12, 0.0, dd) * 0.10 * inten;
        }
        vec3 o = rgb * lines * m * fl + bar;
        oColor = vec4(clamp(o, 0.0, 1.0) * c0.a, c0.a);
    } else if (uMode == 3) {
        // Glitch: bands of the picture jump sideways, the colour channels split, lines wobble. The pattern changes `rate`
        // times a second and depends only on the frame number, so the preview and the export are the same.
        float amt = clamp(uP[0], 0.0, 100.0) / 100.0;
        if (amt < 0.005) { oColor = c0; return; }
        float rate = clamp(uP[3], 1.0, 24.0);
        float stp = floor(float(uFrame) * rate / 24.0);
        float bandH = mix(0.012, 0.22, clamp(uP[1], 1.0, 100.0) / 100.0);
        float row = floor((1.0 - vUv.y) / bandH);
        float h = hash21(vec2(row, stp));
        float h2 = hash21(vec2(row + 41.0, stp + 7.0));
        float isOn = h < amt * 0.55 ? 1.0 : 0.0;
        float shift = (h2 - 0.5) * 2.0 * amt * 0.28 * isOn;
        float line = floor(gl_FragCoord.y / max(1.0, uUnit));
        float wob = (hash21(vec2(line, stp + 3.0)) - 0.5) * clamp(uP[4], 0.0, 100.0) / 100.0 * 0.012 * amt;
        float split = clamp(uP[2], 0.0, 100.0) / 100.0 * 0.03 * (0.15 + isOn);
        float sgn = h2 > 0.5 ? 1.0 : -1.0;
        vec2 uvR = vec2(vUv.x + shift + wob + split * sgn, vUv.y);
        vec2 uvG = vec2(vUv.x + shift + wob, vUv.y);
        vec2 uvB = vec2(vUv.x + shift + wob - split * sgn, vUv.y);
        vec4 a = tap(uvR), b = tap(uvG), c = tap(uvB);
        float al = max(a.a, max(b.a, c.a));
        vec3 rgb = vec3(a.r, b.g, c.b);
        oColor = vec4(clamp(rgb, 0.0, al), al);
    } else if (uMode == 4) {
        // Emboss: the picture as a relief lit from one direction.
        float amt = clamp(uP[0], 0.0, 300.0) / 100.0;
        if (amt < 0.005) { oColor = c0; return; }
        float ang = radians(uP[1]);
        float px = max(1.0, clamp(uP[2], 1.0, 20.0) * uUnit * 0.5);
        vec2 dir = vec2(cos(ang), -sin(ang)) * uTexel * px;
        float e = (luma(unpm(tap(vUv + dir))) - luma(unpm(tap(vUv - dir)))) * amt * 2.0;
        vec3 base = unpm(c0);
        float keep = clamp(uP[3], 0.0, 100.0) / 100.0;
        vec3 o = mix(vec3(0.5 + e), base + e, keep);
        oColor = vec4(clamp(o, 0.0, 1.0) * c0.a, c0.a);
    } else if (uMode == 5) {
        // Find edges: dark lines on white (ink drawing) or bright lines on black (neon), mono or in the picture's colours.
        float st = clamp(uP[0], 0.0, 300.0) / 100.0;
        float th = max(1.0, clamp(uP[1], 1.0, 10.0) * uUnit * 0.5);
        float glow = clamp(uP[2], 0.0, 100.0) / 100.0;
        float col = clamp(uP[3], 0.0, 100.0) / 100.0;
        float mixv = clamp(uP[4], 0.0, 100.0) / 100.0;
        float m = clamp(length(sobel(vUv, th)) * st * 1.5, 0.0, 1.0);
        vec3 base = unpm(c0);
        float mxc = max(base.r, max(base.g, base.b));
        vec3 ec = mix(vec3(1.0), base / max(mxc, 0.2), col);
        vec3 dk = vec3(1.0) - m * (vec3(1.0) - mix(vec3(0.0), base * 0.7, col));
        vec3 res = mix(dk, ec * m, glow);
        oColor = vec4(clamp(mix(base, res, mixv), 0.0, 1.0) * c0.a, c0.a);
    } else if (uMode == 6) {
        // Cartoon: smoothed, few colour steps, ink outlines.
        float lv = clamp(floor(uP[0] + 0.5), 2.0, 16.0);
        float es = clamp(uP[1], 0.0, 100.0) / 100.0;
        float th = max(1.0, clamp(uP[2], 1.0, 6.0) * uUnit * 0.7);
        float sm = clamp(uP[3], 0.0, 100.0) / 100.0;
        float mixv = clamp(uP[4], 0.0, 100.0) / 100.0;
        vec2 r = uTexel * sm * 4.0 * uUnit;
        vec4 s = c0 * 0.4 + (tap(vUv + vec2(r.x, 0.0)) + tap(vUv - vec2(r.x, 0.0)) + tap(vUv + vec2(0.0, r.y)) + tap(vUv - vec2(0.0, r.y))) * 0.15;
        vec3 q = floor(unpm(s) * lv + 0.5) / lv;
        float edge = smoothstep(0.15, 0.6, clamp(length(sobel(vUv, th)) * es * 4.0, 0.0, 1.0));
        vec3 res = q * (1.0 - edge);
        oColor = vec4(clamp(mix(unpm(c0), res, mixv), 0.0, 1.0) * c0.a, c0.a);
    } else if (uMode == 7) {
        // Pencil sketch: hatching that gets denser in the dark parts, plus outlines, on white paper.
        float dens = clamp(uP[0], 1.0, 100.0) / 100.0;
        float dark = clamp(uP[1], 0.0, 100.0) / 100.0;
        float ang = radians(uP[2]);
        float es = clamp(uP[3], 0.0, 100.0) / 100.0;
        float col = clamp(uP[4], 0.0, 100.0) / 100.0;
        float mixv = clamp(uP[5], 0.0, 100.0) / 100.0;
        vec3 base = unpm(c0);
        float need = pow(1.0 - luma(base), 1.6) * dark * 1.6;
        vec2 px = gl_FragCoord.xy / max(1.0, uUnit);
        float sp = mix(14.0, 3.0, dens);
        float h = 0.0;
        for (int i = 0; i < 3; i++) {
            float a = ang + float(i) * 0.8;
            float tri = abs(fract(dot(px, vec2(cos(a), sin(a))) / sp) - 0.5) * 2.0;
            float lw = clamp((need - 0.15 - float(i) * 0.33) * 3.0, 0.0, 1.0) * 0.5;
            h = max(h, smoothstep(1.0 - lw - 0.02, 1.0 - lw + 0.05, tri));
        }
        float m = clamp(length(sobel(vUv, max(1.0, uUnit))) * es * 4.0, 0.0, 1.0);
        float pencil = (1.0 - h * 0.85) * (1.0 - m * 0.9);
        vec3 res = mix(vec3(pencil), clamp(base * 1.15, 0.0, 1.0) * pencil, col);
        oColor = vec4(clamp(mix(base, res, mixv), 0.0, 1.0) * c0.a, c0.a);
    } else if (uMode == 8) {
        // Oil paint (Kuwahara): every pixel takes the average of the calmest of four neighbouring blocks.
        float R = clamp(uP[0], 0.0, 100.0) / 100.0 * 6.0 * uUnit;
        if (R < 0.5) { oColor = c0; return; }
        float boost = clamp(uP[1], 0.0, 100.0) / 100.0;
        vec3 m0, m1, m2, m3;
        float v0, v1, v2, v3;
        kuwaharaQuad(vec2(1.0, 1.0), R, m0, v0);
        kuwaharaQuad(vec2(-1.0, 1.0), R, m1, v1);
        kuwaharaQuad(vec2(1.0, -1.0), R, m2, v2);
        kuwaharaQuad(vec2(-1.0, -1.0), R, m3, v3);
        vec3 res = m0;
        float best = v0;
        if (v1 < best) { best = v1; res = m1; }
        if (v2 < best) { best = v2; res = m2; }
        if (v3 < best) { best = v3; res = m3; }
        res = mix(vec3(luma(res)), res, 1.0 + boost * 0.6);
        oColor = vec4(clamp(res, 0.0, 1.0) * c0.a, c0.a);
    } else if (uMode == 9) {
        // Strobe light: a flash `rate` times a second (white, or the picture inverted).
        float rate = clamp(uP[0], 1.0, 24.0);
        float st = clamp(uP[1], 0.0, 100.0) / 100.0;
        float duty = clamp(uP[2], 1.0, 100.0) / 100.0;
        float inv = clamp(uP[3], 0.0, 100.0) / 100.0;
        float on = fract(float(uFrame) * rate / 24.0) < duty ? 1.0 : 0.0;
        vec3 base = unpm(c0);
        vec3 o = mix(base, mix(vec3(1.0), 1.0 - base, inv), on * st);
        oColor = vec4(o * c0.a, c0.a);
    } else if (uMode == 10) {
        // VHS tape: wobbling lines, a tracking band that rolls down, smeared colour, tape noise.
        float amt = clamp(uP[0], 0.0, 100.0) / 100.0;
        if (amt < 0.005) { oColor = c0; return; }
        float wob = clamp(uP[1], 0.0, 100.0) / 100.0;
        float bleed = clamp(uP[2], 0.0, 100.0) / 100.0;
        float trk = clamp(uP[3], 0.0, 100.0) / 100.0;
        float noi = clamp(uP[4], 0.0, 100.0) / 100.0;
        float fad = clamp(uP[5], 0.0, 100.0) / 100.0;
        float t = float(uFrame) / 24.0;
        float y = 1.0 - vUv.y;
        float line = floor(gl_FragCoord.y / max(1.0, uUnit));
        float jit = (vnoise(vec2(y * 40.0, t * 8.0)) - 0.5) * wob * 0.012 * amt
                  + (hash21(vec2(line, floor(t * 24.0))) - 0.5) * wob * 0.002 * amt;
        float band = smoothstep(0.06, 0.0, abs(y - fract(t * 0.18 + 0.2))) * trk * amt;
        float shiftX = jit + band * 0.05 * (hash11(floor(t * 24.0)) - 0.3);
        vec2 uv = vec2(vUv.x + shiftX, vUv.y);
        float bl = bleed * amt * 0.012;
        vec4 cs = tap(uv);
        vec3 cL = unpm(cs);
        vec3 cb = (unpm(tap(uv - vec2(bl, 0.0))) + unpm(tap(uv - vec2(bl * 2.0, 0.0))) + unpm(tap(uv - vec2(bl * 3.0, 0.0))) + cL) * 0.25;
        vec3 chroma = cb - vec3(luma(cb));
        vec3 rgb = vec3(luma(cL)) + chroma * (1.0 - fad * amt * 0.6);
        float nz = hash21(gl_FragCoord.xy / max(1.0, uUnit) + float(uFrame) * 7.0) - 0.5;
        float bn = hash21(vec2(floor(gl_FragCoord.x / max(1.0, uUnit) / 2.0), line + float(uFrame))) - 0.3;
        rgb += nz * noi * amt * 0.25 + band * bn * 0.6;
        rgb *= 1.0 - 0.08 * amt * (0.5 + 0.5 * sin(line * PI));
        oColor = vec4(clamp(rgb, 0.0, 1.0) * cs.a, cs.a);
    } else if (uMode == 11) {
        // Old film: gate weave, flicker, scratches and dust that change 12 times a second, sepia, faded blacks.
        float sc = clamp(uP[0], 0.0, 100.0) / 100.0;
        float dust = clamp(uP[1], 0.0, 100.0) / 100.0;
        float fl = clamp(uP[2], 0.0, 100.0) / 100.0;
        float wv = clamp(uP[3], 0.0, 100.0) / 100.0;
        float sep = clamp(uP[4], 0.0, 100.0) / 100.0;
        float fade = clamp(uP[5], 0.0, 100.0) / 100.0;
        float fs = floor(float(uFrame) * 12.0 / 24.0);
        vec2 off = vec2(hash11(fs * 1.7) - 0.5, hash11(fs * 3.1 + 9.0) - 0.5) * wv * 0.012;
        vec4 c = tap(vUv + off);
        vec3 rgb = unpm(c);
        float y = luma(rgb);
        rgb = mix(rgb, vec3(y * 1.10, y * 0.95, y * 0.78), sep);
        rgb = mix(rgb, rgb * 0.85 + 0.08, fade);
        rgb *= 1.0 + (hash11(float(uFrame) * 2.31) - 0.5) * fl * 0.35;
        for (int i = 0; i < 3; i++) {
            float x = hash11(fs * 7.0 + float(i) * 13.1);
            float on = step(0.55, hash11(fs * 3.3 + float(i) * 5.7)) * sc;
            float ln = smoothstep(0.0012, 0.0, abs(vUv.x - x)) * on * (0.3 + 0.7 * vnoise(vec2(vUv.y * 6.0, fs + float(i))));
            rgb += ln * 0.45;
        }
        for (int i = 0; i < 6; i++) {
            vec2 pos = vec2(hash11(fs * 11.0 + float(i) * 3.7), hash11(fs * 17.0 + float(i) * 9.1));
            float r = 0.002 + 0.004 * hash11(float(i) + fs);
            float dd = length((vUv - pos) * vec2(uAspect, 1.0));
            float on = step(0.5, hash11(fs * 5.0 + float(i) * 2.3)) * dust;
            float colr = (i % 2 == 0) ? 0.05 : 0.9;
            rgb = mix(rgb, vec3(colr), smoothstep(r, r * 0.4, dd) * on * 0.85);
        }
        oColor = vec4(clamp(rgb, 0.0, 1.0) * c.a, c.a);
    } else if (uMode == 12) {
        // Light leak: warm coloured light blobs drift over the picture (screen blend).
        float inten = clamp(uP[0], 0.0, 100.0) / 100.0;
        float hue = uP[1];
        float size = 0.15 + clamp(uP[2], 0.0, 100.0) / 100.0 * 0.7;
        float spd = uP[3] / 100.0;
        float pos = clamp(uP[4], 0.0, 100.0) / 100.0;
        float t = float(uFrame) / 24.0 * spd * 0.35;
        vec2 p = (vUv - 0.5) * vec2(uAspect, 1.0);
        vec3 leak = vec3(0.0);
        for (int i = 0; i < 3; i++) {
            float fi = float(i);
            vec2 cc = vec2(sin(t * (0.7 + fi * 0.31) + fi * 2.1 + pos * 6.2831853) * 0.33 * uAspect, cos(t * (0.5 + fi * 0.23) + fi * 1.3) * 0.35);
            float d = length(p - cc);
            float g = exp(-d * d / (size * size * (0.5 + 0.3 * fi)));
            leak += hueRgb(hue + fi * 28.0 - 28.0) * g * (1.0 - 0.2 * fi);
        }
        leak = clamp(leak * inten * 1.2, 0.0, 1.0);
        vec3 o = 1.0 - (1.0 - unpm(c0)) * (1.0 - leak);
        oColor = vec4(o * c0.a, c0.a);
    } else if (uMode == 14) {
        // Find edges (Alight Motion's findedges, its recovered shader): a Scharr gradient of
        // the premultiplied luma over the 8 neighbours; inverted, the picture is multiplied
        // by the grey edge value, otherwise the picture's alpha is scaled by (1 - g).
        // uP[0] is the smoothing (a texel multiplier), uP[1] the threshold %, uP[2] the
        // invert flag. fxFindEdgesAmG in fx_plan.cpp is the CPU twin of the gradient.
        vec2 fs = uTexel * uP[0];
        float w = luma(tap(vUv + vec2(-fs.x, 0.0)).rgb);
        float e = luma(tap(vUv + vec2(fs.x, 0.0)).rgb);
        float n = luma(tap(vUv + vec2(0.0, fs.y)).rgb);
        float s = luma(tap(vUv + vec2(0.0, -fs.y)).rgb);
        float nw = luma(tap(vUv + vec2(-fs.x, fs.y)).rgb);
        float ne = luma(tap(vUv + vec2(fs.x, fs.y)).rgb);
        float sw = luma(tap(vUv + vec2(-fs.x, -fs.y)).rgb);
        float se = luma(tap(vUv + vec2(fs.x, -fs.y)).rgb);
        float v = nw * 3.0 + n * 10.0 + ne * 3.0 + sw * -3.0 + s * -10.0 + se * -3.0;
        float h = nw * -3.0 + w * -10.0 + sw * -3.0 + ne * 3.0 + e * 10.0 + se * 3.0;
        float g = clamp(sqrt(h * h + v * v) - clamp(uP[1], 0.0, 100.0) / 100.0, 0.0, 1.0);
        if (uP[2] > 0.5) {
            oColor = vec4(vec3(g), 1.0) * c0;
        } else {
            oColor = vec4(vec3(1.0 - g), 1.0) * c0.a;
        }
    } else if (uMode == 15) {
        // Dissolve (Alight Motion's dissolve, its recovered shader): a per-pixel hash
        // over the layer's pixel grid thresholds the layer by amount. uP[0] is the
        // amount, uP[1] the seed. fxDissolveAmHash in fx_plan.cpp is the CPU twin.
        vec2 picSize = 1.0 / uTexel;
        vec2 st = floor(vec2((vUv.x - 0.5) * picSize.x, (0.5 - vUv.y) * picSize.y)) / picSize;
        float t = mod(uP[1] * 3197.3791, 2.8) + 2.7;
        float dt = dot(st * t, vec2(719.310, 178.233));
        float r = fract(sin(mod(dt, 3.14)) * 33758.5453);
        oColor = c0 * step(uP[0], r);
    } else if (uMode == 16) {
        // Simple Stars (Alight Motion's simplestars2, its recovered shader): procedural
        // star layers composited over the layer. uP: scale, star size, star feather,
        // colour packed 0xRRGGBB, star count, brightness, blend mode (0 normal,
        // 1 multiply, 2 screen, 3 punchout); uQ: parallax x / y / z, alpha.
        // fxSimpleStarsAmField / fxSimpleStarsAmAdj / fxSimpleStarsAmMap in
        // fx_plan.cpp are the CPU twins.
        vec2 st = vec2(vUv.x, 1.0 - vUv.y) - 0.5;
        st.x *= uAspect;
        st /= max(uP[0], 0.0001);
        vec2 par = vec2(uQ[0], uQ[1]);
        float p = amStars(st, -par / 1000.0, uQ[2] / 45.0, uP[1], uP[2], uP[4]);
        vec3 col = amUnpackColor(uP[3]);
        vec4 adj = clamp(vec4(mix(col + 0.15, vec3(1.0), 0.05) * p * uP[5], 1.0), 0.0, 1.0) * p;
        vec4 result;
        int bm = int(uP[6] + 0.5);
        if (bm == 1) {
            result = vec4((c0.rgb / max(c0.a, 0.00001)) * adj.rgb, 1.0) * c0.a;
        } else if (bm == 2) {
            result = vec4(1.0 - (1.0 - c0.rgb / max(c0.a, 0.00001)) * (1.0 - adj.rgb), 1.0) * c0.a;
        } else if (bm == 3) {
            result = c0 * (1.0 - min(p, 1.0));
        } else {
            result = adj * c0.a;
            result = c0 * (1.0 - result.a) + result;
        }
        oColor = mix(c0, result, clamp(uQ[3], 0.0, 1.0));
    } else if (uMode == 17) {
        // Dots (Alight Motion's dots2, its recovered shader): a stepped Voronoi dot
        // field composited over the layer. uP: scale, phase (turns), radius, feather,
        // colours 1/2 packed 0xRRGGBB, density; uQ: position x / y, fill, alpha,
        // blend mode (0 normal, 1 multiply, 2 screen), seed, slack. fxDotsAmField /
        // fxDotsAmMap in fx_plan.cpp are the CPU twins.
        vec2 st = vUv;
        st.x *= uAspect;
        st -= 0.5;
        st -= vec2(uQ[0], uQ[1]) * vec2(1.0, -1.0) / 1000.0;
        st /= max(uP[0], 0.01) * 0.07;
        vec2 tile = floor(st);
        vec2 tpart = fract(st);
        float maxDist = 100.0;
        vec2 nearestTile = vec2(0.0);
        float nearestK = 0.0;
        int densitySteps = int(max(1.0, uP[6] - 10.0));
        float threshold = min(1.0, uP[6] / 11.0);
        float actualPhase = uP[1] * 6.28318;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = 0; k < 3; k++) {
                    if (k >= densitySteps) break;
                    vec2 pp = amDotsRand2v(tile + vec2(523.135, 621.265) * float(k) + vec2(float(i), float(j)) + vec2(123.215, 272.055) * uQ[5]);
                    pp = 0.5 + (0.5 * sin(actualPhase + 6.2831 * pp) * max(0.00001, uQ[6]));
                    float dist = length(vec2(float(i), float(j)) + pp - tpart);
                    vec2 tileId = vec2(float(i), float(j)) + tile;
                    float ck = amValueRandom(tileId + vec2(float(k), -float(k)) + vec2(541.117, 163.713) * (uQ[5] + 0.1));
                    if (dist < maxDist && ck <= threshold) {
                        maxDist = dist;
                        nearestK = float(k);
                        nearestTile = tileId;
                    }
                }
            }
        }
        float colorPct = amValueRandom(nearestTile + vec2(nearestK, -nearestK) + vec2(721.117, 433.215) * uQ[5]);
        float intensity = amSstep(1.0 - uP[2], 1.0 - (uP[2] * (1.0 - uP[3])), 1.0 - maxDist);
        vec4 over = c0 * clamp(uQ[2], 0.0, 1.0);
        vec4 rcolor = vec4(mix(amUnpackColor(uP[4]), amUnpackColor(uP[5]), colorPct), 1.0) * intensity * c0.a;
        vec4 result;
        int bm = int(uQ[4] + 0.5);
        if (bm == 1) {
            result = vec4((c0.rgb / max(c0.a, 0.00001)) * (rcolor.rgb / max(rcolor.a, 0.00001)), 1.0) * rcolor.a * c0.a;
            result = clamp(result, 0.0, 1.0);
            result = result + (over * (1.0 - result.a));
            result = clamp(result, 0.0, 1.0);
        } else if (bm == 2) {
            result = vec4(1.0 - ((1.0 - (c0.rgb / max(c0.a, 0.00001))) * (1.0 - (rcolor.rgb / max(rcolor.a, 0.00001)))), 1.0) * rcolor.a * c0.a;
            result = clamp(result, 0.0, 1.0);
            result = result + (over * (1.0 - result.a));
            result = clamp(result, 0.0, 1.0);
        } else {
            result = rcolor + (over * (1.0 - rcolor.a));
        }
        oColor = mix(over, result, clamp(uQ[3], 0.0, 1.0));
    } else if (uMode == 18) {
        // Ridges (Alight Motion's ridges2 / ridges, their recovered shaders): a ridged
        // multifractal field mixed between two colours over the layer. uP: intensity,
        // scale, evolution, seed, octaves, colours 1/2 packed 0xRRGGBB (their alpha is
        // dropped, like every packed colour here); uQ: offs x / y, parallax x / y,
        // stretch x / y, version (1: scalar stretch + multiply; 2: point stretch
        // + divide). fxRidgesAmField in fx_plan.cpp is the CPU twin.
        vec2 st = vUv;
        st.x *= uAspect;
        st += vec2(uQ[0], uQ[1]) * vec2(-1.0, 1.0) / 500.0;
        st += mod(vec2(821.921 * uP[3], 437.317 * uP[3]), vec2(4.365, 7.701));
        vec2 par = vec2(uQ[2], uQ[3]) * vec2(-1.0, 1.0) / 100.0;
        vec2 arg;
        if (uQ[6] < 1.5) {
            vec2 scaleVec = uQ[4] < 0.0 ? vec2(uP[1] - uQ[4], uP[1]) : vec2(uP[1], uP[1] + uQ[4]);
            arg = (st - 0.5) * scaleVec + 0.5;
        } else {
            arg = (st - 0.5) / vec2(uP[1] * uQ[4], uP[1] * uQ[5]) + 0.5;
        }
        int oct = int(clamp(uP[4], 1.0, 9.0) + 0.5);
        float p = amRidgedMF(arg, oct, par, uP[2]) * uP[0];
        vec4 rcolor = vec4(mix(amUnpackColor(uP[5]), amUnpackColor(uP[6]), p), 1.0) * c0.a;
        oColor = rcolor + c0 * (1.0 - rcolor.a);
    } else if (uMode == 19) {
        // v175: Turbulence / Clouds (Alight Motion's turbulence2, its recovered
        // shader): the plain turbulence sum (ridges' unfolded sibling) mixed between
        // two colours, composed per blend mode and mixed over the layer by alpha.
        // uP: intensity, scale, evolution, seed, colours 1/2 packed 0xRRGGBB, blend
        // mode; uQ: offs x / y, parallax x / y, stretch x / y (SWAPPED into the scale
        // vector, verbatim), bias; uR: alpha, octaves. fxTurbulence2AmField /
        // fxTurbulence2AmMap in fx_plan.cpp are the twins.
        vec2 st = vUv;
        st.x *= uAspect;
        st += vec2(uQ[0], uQ[1]) * vec2(-1.0, 1.0) / 500.0;
        vec2 par = vec2(uQ[2], uQ[3]) * vec2(-1.0, 1.0) / 100.0;
        vec2 scaleVec = vec2(uP[1] * uQ[5], uP[1] * uQ[4]);
        int oct = int(clamp(uR[1], 1.0, 9.0) + 0.5);
        float p = amTurbulence((st - 0.5) / scaleVec + 0.5, oct, par, uP[2] + 7391.935 * uP[3]) * uP[0];
        p = clamp(p + uQ[6], 0.0, 1.0);
        vec3 rcolor = mix(amUnpackColor(uP[4]), amUnpackColor(uP[5]), p);
        vec3 res = rcolor;
        int bm = int(uP[6] + 0.5);
        if (bm == 1) res = (c0.rgb / max(c0.a, 0.00001)) * rcolor;
        else if (bm == 2) res = 1.0 - (1.0 - c0.rgb / max(c0.a, 0.00001)) * (1.0 - rcolor);
        vec4 result = vec4(res, 1.0) * c0.a;
        oColor = mix(c0, result, clamp(uR[0], 0.0, 1.0));
    } else if (uMode == 20) {
        // v175: Checker (Alight Motion's checker2, its recovered shader, layer mode):
        // the floored parity of the centred, aspect-scaled, position-shifted, rotated
        // position — the stretch components divide the OPPOSITE axes, verbatim — then
        // AM's composite (the colour weighted by p * tex.a; punchout erases the p
        // cells). uP: amount, angle (deg), colour packed 0xRRGGBB, punch out, screen
        // space (carried; the pattern anchors to the layer's rect either way here),
        // position x / y; uQ: stretch x / y. fxChecker2AmP / fxChecker2AmMap in
        // fx_plan.cpp are the twins.
        vec2 st = vUv - 0.5;
        st.x *= uAspect;
        st -= vec2(uP[5], uP[6]) / 1200.0;
        float ang = uP[1] * 0.0174533;
        mat2 m = mat2(sin(ang), -cos(ang), cos(ang), sin(ang));
        st = m * st;
        float px = floor(st.x * uP[0] / uQ[1]);
        float py = floor(st.y * uP[0] / uQ[0]);
        float p = step(mod(px + py, 2.0), 0.5);
        if (uP[3] > 0.5) {
            oColor = c0 * (1.0 - p);
        } else {
            vec4 c = vec4(amUnpackColor(uP[2]) * p, p);
            float alpha = c.a * c0.a;
            oColor = c * alpha + (c0 * (1.0 - alpha));
        }
    } else if (uMode == 21) {
        // v180: Alight's Shape Field (TrueMotion afterz-147): a stack of
        // parallax shape-cell layers, back layers first, each cell showing the
        // layer picture tinted by the colouring mode. uP: pos x / y / z,
        // density, evolution, seed, scale; uQ: shapeSize, shapeAngle,
        // sizeVariance, angleVariance, layerCount, fade, alpha; uR: slack,
        // blendMode, coloring, color1 / 2 / 3 packed, flags (lockLuminance 1 +
        // lockSaturation 2 + interpHue 4). fxShapefieldAmPixel in fx_plan.cpp
        // is the twin.
        vec2 st = vec2(vUv.x, 1.0 - vUv.y) - 0.5;
        st.x *= uAspect;
        st /= uP[6] * 6.0;
        vec2 parallax = vec2(uP[0], uP[1]) / 1000.0 + 25.35;
        float depth = uP[2] / 100.0;
        float f = fract(depth);
        float base = floor(depth);
        vec4 result = vec4(0.0);
        for (float i = uQ[4] - 1.0; i >= 0.0; i -= 1.0) {
            float k = ((1.0 + i) - f) / 9.0;
            float layerDepth = amSstep(uQ[4], uQ[4] * (0.9999 - uQ[5]), (i + 1.0) - f);
            vec4 color = amSfGrid(st * k + parallax, base + i + uP[5] / 5.0, layerDepth) * uQ[6] * layerDepth;
            if (uR[1] > 1.5) {
                vec3 fg = color.a < 0.0001 ? vec3(1.0) : color.rgb / color.a;
                vec3 bg = result.a < 0.0001 ? vec3(0.0) : result.rgb / result.a;
                result = result * (1.0 - color.a) + vec4(1.0 - (1.0 - fg) * (1.0 - bg), 1.0) * color.a;
            } else if (uR[1] > 0.5) {
                vec3 fg = color.a < 0.0001 ? vec3(0.0) : color.rgb / color.a;
                vec3 bg = result.a < 0.0001 ? vec3(1.0) : result.rgb / result.a;
                result = result * (1.0 - color.a) + vec4(fg * bg, 1.0) * color.a;
            } else {
                result = result * (1.0 - color.a) + color;
            }
        }
        oColor = result;
    } else if (uMode == 22) {
        // v180: Alight's Halftone Dots (Ultra original; NOT the native
        // halftone): the sin-product dot screen in pixel space over the
        // (optionally smoothed) luma. uP: dotsMode, dotsFrequency, dotsAngle,
        // dotsRelWidth, dotsSharpness, dotsLighten, smoothSource; uQ: color1
        // packed, color0 packed, shiftX, shiftY, alpha. fxHalftoneDotsAmPixel
        // in fx_plan.cpp is the twin.
        float grey;
        if (uP[6] <= 0.0) {
            grey = luma(c0.rgb);
        } else {
            vec2 o = clamp(uP[6], 0.0, 2.0) * uTexel;
            float acc = 0.0, w = 0.0;
            for (int j = -1; j <= 1; j++)
                for (int i = -1; i <= 1; i++) {
                    float ww = (i == 0 && j == 0) ? 2.0 : 1.0;
                    acc += luma(tap(vUv + vec2(float(i), float(j)) * o).rgb) * ww;
                    w += ww;
                }
            grey = acc / max(w, 1.0);
        }
        vec2 uvShift = vUv + vec2(uQ[2], -uQ[3]) * uTexel;
        vec2 pp = (uvShift - 0.5) / uTexel;
        float ang = radians(uP[2]);
        float ca = cos(ang), sa = sin(ang);
        vec2 q = vec2(ca * pp.x + sa * pp.y, -sa * pp.x + ca * pp.y);
        q.x *= uP[3];
        q *= uP[1] * 0.06;
        float pat = sin(q.x) * sin(q.y) * 4.0;
        float v = grey * 10.0 - 5.0 + pat + uP[5];
        float sm = max(uP[4], 0.0001);
        float m = amSstep(-sm, sm, v);
        if (uP[0] > 0.5) m = 1.0 - m;
        float a = c0.a * uQ[4];
        oColor = vec4(mix(amUnpackColor(uQ[1]), amUnpackColor(uQ[0]), m) * a, a);
    } else {
        // Lens flare: a bright core, halo, horizontal streak and ghost discs along the line through the picture's centre.
        vec2 cen = vec2(uP[0] / 100.0, 1.0 - uP[1] / 100.0);
        float br = clamp(uP[2], 0.0, 200.0) / 100.0;
        float sz = 0.05 + clamp(uP[3], 0.0, 100.0) / 100.0 * 0.35;
        float gh = clamp(uP[4], 0.0, 100.0) / 100.0;
        float stk = clamp(uP[5], 0.0, 100.0) / 100.0;
        float hue = uP[6];
        vec2 p = (vUv - 0.5) * vec2(uAspect, 1.0);
        vec2 L = (cen - 0.5) * vec2(uAspect, 1.0);
        vec3 tint = mix(vec3(1.0), hueRgb(hue), 0.55);
        float d = length(p - L);
        vec3 f = tint * (exp(-d * d / (sz * sz * 0.05)) * 1.2 + exp(-d / (sz * 0.9)) * 0.35);
        float dy = abs(p.y - L.y), dx = abs(p.x - L.x);
        f += tint * stk * exp(-dy * dy / (0.00012 + sz * sz * 0.002)) * exp(-dx / (sz * 6.0)) * 0.8;
        for (int i = 0; i < 5; i++) {
            float fi = float(i);
            vec2 gp = -L * (0.3 + 0.45 * fi);
            float gs = sz * (0.35 + 0.25 * hash11(fi + 3.0));
            float gd = length(p - gp);
            float disc = smoothstep(gs, gs * 0.8, gd);
            float ring = smoothstep(gs * 0.75, gs, gd) * smoothstep(gs * 1.1, gs * 0.95, gd);
            f += (hueRgb(hue + 40.0 * fi) * 0.8 + 0.2) * (disc * 0.18 + ring * 0.35) * gh * 0.5;
        }
        f = clamp(f * br, 0.0, 1.0);
        vec3 o = 1.0 - (1.0 - unpm(c0)) * (1.0 - f);
        oColor = vec4(o * c0.a, c0.a);
    }
}
)";

}  // namespace fxsrc
}  // namespace am
