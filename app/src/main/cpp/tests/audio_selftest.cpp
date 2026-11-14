// Host-side test of the pure audio code:  g++ -std=c++17 -O2 -Wall -Wextra -I../engine audio_selftest.cpp ../engine/audio_mixer.cpp && ./a.out
#include <cstdio>
#include <cstdlib>
#include "audio_mixer.h"
using namespace am;

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
    printf(fails ? "FAILED\n" : "ALL OK\n");
    return fails ? 1 : 0;
}
