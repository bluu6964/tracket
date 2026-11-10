// Host-side test of the editor audio preview core:
//   g++ -std=c++17 -O2 -Wall -Wextra -pthread -I../engine audio_preview_selftest.cpp ../engine/audio_mixer.cpp ../engine/audio_preview.cpp && ./a.out
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <thread>
#include "audio_preview.h"
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
// Constant source: every frame = value (so the fade / volume can be read straight off the output).
struct Const : FrameSource {
    float v; int64_t left;
    Const(float val, int64_t frames) : v(val), left(frames) {}
    int read(float* d, int n) override {
        int k = (int)std::min<int64_t>(n, left);
        for (int i = 0; i < k; ++i) d[2*i] = d[2*i+1] = v;
        left -= k; return k;
    }
};

static int fails = 0;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL: " __VA_ARGS__); printf("\n"); fails++; } } while (0)

// Reads everything the feeder produces until it is finished and drained (or a time limit).
static std::vector<int16_t> drain(PreviewFeeder& f, double maxSec = 10) {
    std::vector<int16_t> out;
    std::vector<int16_t> tmp(1024 * 2);
    const auto t0 = std::chrono::steady_clock::now();
    f.start();
    for (;;) {
        const size_t n = f.ring().read(tmp.data(), 1024);
        out.insert(out.end(), tmp.begin(), tmp.begin() + n * 2);
        if (n == 0) {
            if (f.finished() && f.ring().available() == 0) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        if (std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count() > maxSec) { printf("(timeout)\n"); break; }
    }
    return out;
}

int main() {
    // 1) ring: wrap around, skip, never over-fills
    {
        PcmRing r(1024);
        std::vector<int16_t> a(2000 * 2), b(2000 * 2);
        for (size_t i = 0; i < a.size(); ++i) a[i] = (int16_t)(i % 30000);
        CHECK(r.write(a.data(), 1500) == 1024, "ring fits only its capacity");
        CHECK(r.space() == 0 && r.available() == 1024, "ring full");
        CHECK(r.skip(24) == 24, "skip");
        CHECK(r.read(b.data(), 500) == 500, "read 500");
        bool same = true; for (size_t i = 0; i < 500 * 2; ++i) if (b[i] != a[(24 * 2) + i]) same = false;
        CHECK(same, "data order after skip");
        CHECK(r.write(a.data() + 1024 * 2, 400) == 400, "write after wrap");
        CHECK(r.read(b.data(), 2000) == 500 + 400, "read the rest (%zu)", r.available());
        printf("ring ok\n");
    }
    // 2) a whole clip, from 0
    {
        AudioClipSpec s; s.startFrame = 4800; s.durFrames = s.fullDurFrames = 48000; s.volume = 1.f;
        PreviewFeeder f([&](const AudioClipSpec&) { return std::unique_ptr<FrameSource>(new Const(0.5f, 1 << 20)); }, {s}, 0);
        auto out = drain(f);
        CHECK(out.size() == (size_t)(4800 + 48000) * 2, "length %zu", out.size() / 2);
        CHECK(out[2 * 100] == 0, "silence before the clip");
        CHECK(std::abs(out[2 * 20000] - 16384) < 3, "clip level %d", out[2 * 20000]);
        printf("whole clip ok (%zu frames)\n", out.size() / 2);
    }
    // 3) start in the middle of a clip: the source is opened at the right place (speed 1 and 2), fades keep their clock
    for (double speed : {1.0, 2.0}) {
        std::atomic<int64_t> trimSeen{-1};
        AudioClipSpec s; s.startFrame = 0; s.durFrames = s.fullDurFrames = 96000; s.volume = 1.f; s.speed = speed;
        s.trimStartUs = 250000; s.fadeInFrames = 48000;   // fade in over the first second
        PreviewFeeder f([&](const AudioClipSpec& sp) { trimSeen = sp.trimStartUs; return std::unique_ptr<FrameSource>(new Const(1.0f, 1 << 22)); },
                        {s}, 24000);   // start 0.5 s into the clip
        auto out = drain(f);
        const int64_t want = 250000 + (int64_t)(500000 * speed);
        CHECK(trimSeen == want, "speed %.1f: opened at %lld us, want %lld", speed, (long long)trimSeen.load(), (long long)want);
        CHECK(out.size() == (size_t)(96000 - 24000) * 2, "len %zu", out.size() / 2);
        // 0.5 s into a 1 s fade-in: gain 0.5 (full scale 32767)
        CHECK(std::abs(out[0] - 16384) < 40, "fade gain at the start %d", out[0]);
        CHECK(std::abs(out[2 * 24000] - 32767) < 3, "after the fade %d", out[2 * 24000]);
        printf("mid-clip speed %.1f ok\n", speed);
    }
    // 4) clips after the start are opened early (prefetch), a clip that is already over is ignored, past the end = finished
    {
        std::atomic<int> opened{0};
        AudioClipSpec late; late.source = 1; late.startFrame = 3 * kAudioRate; late.durFrames = late.fullDurFrames = 4800;
        AudioClipSpec over; over.source = 2; over.startFrame = 0; over.durFrames = over.fullDurFrames = 4800;
        PreviewFeeder f([&](const AudioClipSpec&) { ++opened; return std::unique_ptr<FrameSource>(new Const(0.25f, 1 << 20)); },
                        {late, over}, 2 * kAudioRate + 24000);   // 2.5 s: `late` starts in 0.5 s, `over` is done
        f.start();
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        CHECK(opened == 1, "late clip opened early (%d)", opened.load());
        f.stop();
        PreviewFeeder g([&](const AudioClipSpec&) { return std::unique_ptr<FrameSource>(new Const(1.f, 100)); }, {over}, 10 * kAudioRate);
        CHECK(g.finished(), "start past the end is finished at once");
        PreviewFeeder h([&](const AudioClipSpec&) { return std::unique_ptr<FrameSource>(new Const(1.f, 100)); }, {}, 0);
        CHECK(h.finished(), "no clips is finished at once");
        printf("prefetch / ended ok\n");
    }
    // 5) stopping a running feeder is quick and does not hang (ring never drained)
    {
        AudioClipSpec s; s.durFrames = s.fullDurFrames = 600 * kAudioRate;
        PreviewFeeder f([&](const AudioClipSpec&) { return std::unique_ptr<FrameSource>(new Sine(440, 1LL << 40)); }, {s}, 0);
        f.start();
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        const auto t0 = std::chrono::steady_clock::now();
        f.stop();
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        CHECK(ms < 200, "stop took %.0f ms", ms);
        CHECK(f.ring().available() > 0, "ring was filled ahead");
        printf("stop ok (%.1f ms, %zu frames buffered)\n", ms, f.ring().available());
    }
    printf(fails ? "FAILED\n" : "ALL OK\n");
    return fails ? 1 : 0;
}
