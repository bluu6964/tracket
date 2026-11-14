// Host-side test of the pure audio code:  g++ -std=c++17 -O2 -Wall -Wextra -I../engine audio_selftest.cpp ../engine/audio_mixer.cpp && ./a.out
#include <cstdio>
#include <cstdlib>
#include "audio_mixer.h"
#include "noise_gate.h"
using namespace am;

// Deterministic white noise (LCG, uniform in [-amp, amp], same stream on both channels).
struct NoiseSrc : FrameSource {
    uint32_t state = 22222; float amp; int64_t left;
    explicit NoiseSrc(float a, int64_t frames) : amp(a), left(frames) {}
    float next() {
        state = state * 1664525u + 1013904223u;
        return ((state >> 8) & 0xFFFF) / 32768.f - 1.f;
    }
    int read(float* d, int n) override {
        int k = (int)std::min<int64_t>(n, left);
        for (int i = 0; i < k; ++i) { float v = next() * amp; d[2*i] = d[2*i+1] = v; }
        left -= k; return k;
    }
};

// 3 s synthetic for the noise-reduction blocks: [0,1) noise, [1,2) voice-like harmonic
// tone (440 + 880 + 1320 Hz) over the same noise, [2,3) noise again.
struct ToneNoiseSrc : FrameSource {
    uint32_t state = 777; int64_t pos = 0, total;
    explicit ToneNoiseSrc(int64_t frames) : total(frames) {}
    float next() {
        state = state * 1664525u + 1013904223u;
        return ((state >> 8) & 0xFFFF) / 32768.f - 1.f;
    }
    int read(float* d, int n) override {
        int k = (int)std::min<int64_t>(n, total - pos);
        for (int i = 0; i < k; ++i) {
            const int64_t t = pos + i;
            float v = next() * 0.17f;   // noise, sigma ~0.098
            if (t >= kAudioRate && t < 2 * kAudioRate) {
                const double ph = 2 * M_PI * t / kAudioRate;
                v += (float)(0.5 * std::sin(440 * ph) + 0.25 * std::sin(880 * ph) + 0.125 * std::sin(1320 * ph));
            }
            d[2*i] = d[2*i+1] = v;
        }
        pos += k; return k;
    }
};

static double rmsRange(const std::vector<int16_t>& p, int64_t from, int64_t to) {
    double s = 0; for (int64_t i = from; i < to; ++i) s += (double)p[(size_t)i * 2] * p[(size_t)i * 2];
    return std::sqrt(s / (double)(to - from));
}

struct Sine : FrameSource {
    double f, ph = 0; int64_t left;
    Sine(double hz, int64_t frames) : f(hz), left(frames) {}
    int read(float* d, int n) override {
        int k = (int)std::min<int64_t>(n, left);
        for (int i = 0; i < k; ++i) { float v = 0.5f * std::sin(ph); ph += 2 * M_PI * f / kAudioRate; d[2*i] = d[2*i+1] = v; }
        left -= k; return k;
    }
};
static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL: " __VA_ARGS__); printf("\n"); fails++; } } while (0)

static double zeroCrossHz(const std::vector<int16_t>& p, size_t from, size_t to) {
    int z = 0; for (size_t i = from + 1; i < to; ++i) if ((p[(i-1)*2] < 0) != (p[i*2] < 0)) z++;
    return z / 2.0 / ((to - from) / (double)kAudioRate);
}

int main() {
    // 1) speed 2: length halves, pitch stays 440 Hz, no clicks.
    {
        const int64_t src = kAudioRate * 4;
        AudioClipSpec s; s.startFrame = 0; s.durFrames = kAudioRate * 2; s.fullDurFrames = s.durFrames; s.speed = 2.0;
        AudioMixer m([&](const AudioClipSpec& sp) { return std::unique_ptr<FrameSource>(new TimeStretcher(std::unique_ptr<FrameSource>(new Sine(440, src)), sp.speed)); }, s.durFrames);
        m.addClip(s);
        std::vector<int16_t> out;
        m.render(s.durFrames, [&](const int16_t* p, int n, int64_t) { out.insert(out.end(), p, p + n * 2); return true; });
        CHECK(out.size() == (size_t)s.durFrames * 2, "len %zu", out.size());
        double hz = zeroCrossHz(out, kAudioRate / 2, kAudioRate * 3 / 2);
        CHECK(std::fabs(hz - 440) < 15, "pitch %.1f", hz);
        int maxJump = 0; for (size_t i = 1; i < out.size() / 2; ++i) maxJump = std::max(maxJump, std::abs(out[i*2] - out[(i-1)*2]));
        CHECK(maxJump < 3500, "click, max jump %d", maxJump);   // sine 0.5 amp @440Hz: natural max step ~ 1900
        int peak = 0; for (size_t i = kAudioRate / 2; i < kAudioRate * 3 / 2; ++i) peak = std::max(peak, (int)std::abs(out[i*2]));
        CHECK(peak > 14000 && peak < 18000, "level %d", peak);
        printf("speed2: hz=%.1f maxJump=%d peak=%d\n", hz, maxJump, peak);
    }
    // 2) speed 0.5 and 1.5
    for (double sp : {0.5, 1.5}) {
        AudioClipSpec s; s.durFrames = kAudioRate * 2; s.fullDurFrames = s.durFrames; s.speed = sp;
        AudioMixer m([&](const AudioClipSpec& x) { return std::unique_ptr<FrameSource>(new TimeStretcher(std::unique_ptr<FrameSource>(new Sine(440, (int64_t)(kAudioRate * 2 * x.speed + 100))), x.speed)); }, s.durFrames);
        m.addClip(s); std::vector<int16_t> out;
        m.render(s.durFrames, [&](const int16_t* p, int n, int64_t) { out.insert(out.end(), p, p + n * 2); return true; });
        double hz = zeroCrossHz(out, kAudioRate / 2, kAudioRate * 3 / 2);
        CHECK(std::fabs(hz - 440) < 15, "pitch@%.1f %.1f", sp, hz);
        printf("speed%.1f: hz=%.1f\n", sp, hz);
    }
    // 3) offset, volume, fades, mixing two clips, clamp
    {
        AudioClipSpec a; a.source = 0; a.startFrame = 4800; a.durFrames = 48000; a.fullDurFrames = 48000; a.volume = 1.f; a.fadeInFrames = 4800; a.fadeOutFrames = 4800;
        AudioClipSpec b = a; b.source = 1; b.startFrame = 0; b.durFrames = b.fullDurFrames = 9600; b.fadeInFrames = b.fadeOutFrames = 0; b.volume = 0.5f;
        AudioMixer m([&](const AudioClipSpec& x) { return std::unique_ptr<FrameSource>(new Sine(x.source ? 100 : 1000, 100000)); }, 60000);
        m.addClip(a); m.addClip(b); std::vector<int16_t> out; int64_t firstPts = -1;
        m.render(1 << 30, [&](const int16_t* p, int n, int64_t pts) { if (firstPts < 0) firstPts = pts; out.insert(out.end(), p, p + n * 2); return true; });
        CHECK(out.size() == 60000 * 2, "total %zu", out.size());
        CHECK(m.finished(), "finished");
        int tailPeak = 0; for (size_t i = 55000; i < 60000; ++i) tailPeak = std::max(tailPeak, (int)std::abs(out[i*2]));
        CHECK(tailPeak == 0, "silence after clip end %d", tailPeak);
        int fadeStart = 0; for (size_t i = 4800; i < 4800 + 200; ++i) fadeStart = std::max(fadeStart, (int)std::abs(out[i*2]));
        printf("mix ok, fadeStart peak=%d\n", fadeStart);
    }
    // 4) clip that cannot be opened is skipped, others keep playing
    {
        AudioClipSpec a; a.source = 0; a.durFrames = a.fullDurFrames = 4800; AudioClipSpec b = a; b.source = 1;
        AudioMixer m([&](const AudioClipSpec& x) -> std::unique_ptr<FrameSource> { if (x.source == 0) return nullptr; return std::unique_ptr<FrameSource>(new Sine(440, 99999)); }, 4800);
        m.addClip(a); m.addClip(b); int peak = 0;
        m.render(4800, [&](const int16_t* p, int n, int64_t) { for (int i = 0; i < n * 2; ++i) peak = std::max(peak, (int)std::abs(p[i])); return true; });
        CHECK(peak > 10000, "second clip audible %d", peak);
    }
    // 5) ducking (build 194): the gain curve multiplies volume x fade, interpolated linearly
    // between breakpoints and clamped outside them (the model/Ducking.gainAt contract).
    {
        AudioClipSpec s; s.durFrames = s.fullDurFrames = kAudioRate; s.volume = 1.f;
        s.duckFrames = {0, kAudioRate / 2, kAudioRate};
        s.duckGains = {1.f, 0.25f, 1.f};
        AudioMixer m([&](const AudioClipSpec&) { return std::unique_ptr<FrameSource>(new Sine(440, 999999)); }, kAudioRate);
        m.addClip(s); std::vector<int16_t> out;
        m.render(kAudioRate, [&](const int16_t* p, int n, int64_t) { out.insert(out.end(), p, p + n * 2); return true; });
        auto peakAround = [&](int64_t centre) {
            int peak = 0;
            for (int64_t i = centre - 240; i < centre + 240; ++i) peak = std::max(peak, (int)std::abs(out[(size_t)i * 2]));
            return peak;
        };
        const int full = peakAround(2400);                    // gain 1 at the start (sine 0.5 -> ~16384)
        CHECK(full > 14000 && full < 18000, "duck start level %d", full);
        const int mid = peakAround(kAudioRate / 4);           // halfway down the ramp: gain ~0.625
        CHECK(mid > full * 0.45 && mid < full * 0.8, "duck ramp level %d vs full %d", mid, full);
        const int dipped = peakAround(kAudioRate / 2);        // the floor point: gain 0.25
        CHECK(dipped > full * 0.15 && dipped < full * 0.38, "duck floor level %d vs full %d", dipped, full);
        printf("duck ok: full=%d mid=%d dipped=%d\n", full, mid, dipped);
    }
    // 6) ducking: past the last breakpoint the gain CLAMPS to it (no snap back to 1).
    {
        AudioClipSpec s; s.durFrames = s.fullDurFrames = kAudioRate; s.volume = 1.f;
        s.duckFrames = {0, kAudioRate / 4};
        s.duckGains = {1.f, 0.25f};
        AudioMixer m([&](const AudioClipSpec&) { return std::unique_ptr<FrameSource>(new Sine(440, 999999)); }, kAudioRate);
        m.addClip(s); std::vector<int16_t> out;
        m.render(kAudioRate, [&](const int16_t* p, int n, int64_t) { out.insert(out.end(), p, p + n * 2); return true; });
        int late = 0;
        for (int64_t i = kAudioRate * 3 / 4 - 240; i < kAudioRate * 3 / 4 + 240; ++i)
            late = std::max(late, (int)std::abs(out[(size_t)i * 2]));
        CHECK(late > 3000 && late < 5500, "duck clamp level %d (want ~0.25 x 16384)", late);
        printf("duck clamp ok: late=%d\n", late);
    }
    // 7) noise reduction (build 195): the pure law's anchor points (mirrors model/NoiseReduction).
    {
        CHECK(noiseGateRawGain(3.f, 0.5f, 0.f) == 1.f, "nr strength 0 must pass");
        CHECK(noiseGateRawGain(0.f, 0.5f, 1.f) == kNoiseFloorGain, "nr silent bin takes the floor");
        // m == over x n -> exactly the floor gain.
        CHECK(std::fabs(noiseGateRawGain(4.f * 0.25f, 0.25f, 1.f) - kNoiseFloorGain) < 1e-6, "nr floor point");
        // m far above the floor -> near unity: sqrt(1 - (4 x 0.25 / 10)^2) = sqrt(0.99).
        CHECK(std::fabs(noiseGateRawGain(10.f, 0.25f, 1.f) - 0.99499f) < 1e-4, "nr unity point");
        float raw[3] = {0.f, 1.f, 0.f}, sm[3];
        noiseGateSmoothAcrossFrequency(raw, sm, 3);
        CHECK(std::fabs(sm[0] - 0.25f) < 1e-6 && std::fabs(sm[1] - 0.5f) < 1e-6 && std::fabs(sm[2] - 0.25f) < 1e-6,
              "nr frequency smoothing %f %f %f", sm[0], sm[1], sm[2]);
        printf("nr law ok\n");
    }
    // 8) noise gate as a source: passthrough exactness, tone preservation, silence.
    {
        // strength 0 -> float-exact passthrough of the wrapped source.
        NoiseGateSource gate(std::unique_ptr<FrameSource>(new Sine(440, 4800)),
                             std::vector<float>((size_t)kNoiseBins, 0.5f), 0.f);
        Sine raw(440, 4800);
        std::vector<float> a(4800 * 2), b(4800 * 2);
        const int na = gate.read(a.data(), 4800), nb = raw.read(b.data(), 4800);
        CHECK(na == nb && a == b, "nr strength 0 passthrough (%d vs %d)", na, nb);
        // A pure tone far above a low profile keeps its level; length is exact.
        NoiseGateSource gate2(std::unique_ptr<FrameSource>(new Sine(440, kAudioRate)),
                              std::vector<float>((size_t)kNoiseBins, 0.01f), 1.f);
        std::vector<float> out;
        float buf[4096 * 2];
        int got;
        while ((got = gate2.read(buf, 4096)) > 0) out.insert(out.end(), buf, buf + (size_t)got * 2);
        CHECK(out.size() == (size_t)kAudioRate * 2, "nr tone length %zu", out.size());
        float peak = 0; for (int64_t i = kAudioRate / 2; i < kAudioRate; ++i) peak = std::max(peak, std::fabs(out[(size_t)i * 2]));
        CHECK(peak > 0.45f && peak < 0.55f, "nr tone peak %.3f (want ~0.5)", peak);
        // Silence in -> silence out, exact length, no NaN.
        struct Silence : FrameSource { int64_t left; explicit Silence(int64_t n) : left(n) {}
            int read(float* d, int n) override { int k = (int)std::min<int64_t>(n, left); std::fill(d, d + (size_t)k * 2, 0.f); left -= k; return k; } };
        NoiseGateSource gate3(std::unique_ptr<FrameSource>(new Silence(10000)),
                              std::vector<float>((size_t)kNoiseBins, 0.5f), 1.f);
        int total = 0; bool clean = true;
        while ((got = gate3.read(buf, 4096)) > 0) {
            total += got;
            for (int i = 0; i < got * 2; ++i) if (buf[i] != 0.f || std::isnan(buf[i])) clean = false;
        }
        CHECK(total == 10000 && clean, "nr silence: %d frames, clean=%d", total, (int)clean);
        printf("nr source ok: tone peak=%.3f\n", peak);
    }
    // 9) noise reduction through the mixer (the production factory shape: decoder -> gate):
    // noise-only segments drop hard, the tone segment keeps its energy, length is exact.
    {
        // Flat profile = the analytic 10th percentile of the synthetic noise's bin
        // magnitudes (sigma ~0.098, Hann sum w^2 = 768 -> p10 ~ 9.0 x sigma ~ 0.88).
        AudioClipSpec s; s.durFrames = s.fullDurFrames = kAudioRate * 3; s.volume = 1.f;
        s.noiseProfile.assign((size_t)kNoiseBins, 0.88f);
        s.nrStrength = 1.f;
        auto factory = [](const AudioClipSpec& sp) -> std::unique_ptr<FrameSource> {
            std::unique_ptr<FrameSource> src(new ToneNoiseSrc(kAudioRate * 3));
            if (!sp.noiseProfile.empty() && sp.nrStrength > 0.f)
                src.reset(new NoiseGateSource(std::move(src), sp.noiseProfile, sp.nrStrength));
            return src;
        };
        AudioMixer m(factory, kAudioRate * 3);
        m.addClip(s); std::vector<int16_t> out;
        m.render(kAudioRate * 3, [&](const int16_t* p, int n, int64_t) { out.insert(out.end(), p, p + n * 2); return true; });
        CHECK(out.size() == (size_t)kAudioRate * 3 * 2, "nr mix length %zu", out.size());
        AudioClipSpec plain = s; plain.noiseProfile.clear(); plain.nrStrength = 0.f;
        AudioMixer m2(factory, kAudioRate * 3);
        m2.addClip(plain); std::vector<int16_t> in;
        m2.render(kAudioRate * 3, [&](const int16_t* p, int n, int64_t) { in.insert(in.end(), p, p + n * 2); return true; });
        const double noiseIn = rmsRange(in, kAudioRate / 4, kAudioRate * 3 / 4);
        const double noiseOut = rmsRange(out, kAudioRate / 4, kAudioRate * 3 / 4);
        const double toneIn = rmsRange(in, kAudioRate * 5 / 4, kAudioRate * 7 / 4);
        const double toneOut = rmsRange(out, kAudioRate * 5 / 4, kAudioRate * 7 / 4);
        const double tailIn = rmsRange(in, kAudioRate * 9 / 4, kAudioRate * 11 / 4);
        const double tailOut = rmsRange(out, kAudioRate * 9 / 4, kAudioRate * 11 / 4);
        CHECK(noiseOut < noiseIn * 0.25, "nr noise segment: in %.0f out %.0f", noiseIn, noiseOut);
        CHECK(tailOut < tailIn * 0.25, "nr noise tail: in %.0f out %.0f", tailIn, tailOut);
        CHECK(toneOut > toneIn * 0.8, "nr tone segment: in %.0f out %.0f", toneIn, toneOut);
        printf("nr mix ok: noise %.0f->%.0f (%.1f dB), tone %.0f->%.0f, tail %.0f->%.0f\n",
               noiseIn, noiseOut, 20 * std::log10(noiseIn / std::max(1.0, noiseOut)), toneIn, toneOut, tailIn, tailOut);
    }
    // 10) noise reduction OFF through the mixer: a spec carrying a profile but strength 0,
    // and a spec with strength but no profile, both render bit-identically to the plain spec.
    {
        AudioClipSpec base; base.durFrames = base.fullDurFrames = kAudioRate; base.volume = 1.f;
        auto renderWith = [&](const AudioClipSpec& sp) {
            AudioMixer m([](const AudioClipSpec& x) -> std::unique_ptr<FrameSource> {
                std::unique_ptr<FrameSource> src(new ToneNoiseSrc(kAudioRate));
                if (!x.noiseProfile.empty() && x.nrStrength > 0.f)
                    src.reset(new NoiseGateSource(std::move(src), x.noiseProfile, x.nrStrength));
                return src;
            }, kAudioRate);
            m.addClip(sp); std::vector<int16_t> o;
            m.render(kAudioRate, [&](const int16_t* p, int n, int64_t) { o.insert(o.end(), p, p + n * 2); return true; });
            return o;
        };
        const std::vector<int16_t> plain = renderWith(base);
        AudioClipSpec offStrength = base;
        offStrength.noiseProfile.assign((size_t)kNoiseBins, 0.88f); offStrength.nrStrength = 0.f;
        CHECK(renderWith(offStrength) == plain, "nr strength 0 render must be identical");
        AudioClipSpec noProfile = base; noProfile.nrStrength = 1.f;
        CHECK(renderWith(noProfile) == plain, "nr without profile render must be identical");
        printf("nr off ok\n");
    }
    printf(fails ? "FAILED\n" : "ALL OK\n");
    return fails ? 1 : 0;
}
