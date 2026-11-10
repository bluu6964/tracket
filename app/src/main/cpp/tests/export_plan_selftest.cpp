// g++ -std=c++17 -Wall -Wextra -I.. ../engine/export_plan.cpp export_plan_selftest.cpp -o export_plan_selftest && ./export_plan_selftest
#include <cmath>
#include <cstdio>

#include "engine/export_plan.h"

using namespace am;
static int fails = 0, checks = 0;
#define CHECK(c) do { ++checks; if (!(c)) { ++fails; std::printf("FAIL line %d: %s\n", __LINE__, #c); } } while (0)

static AudioCandidate cand(int flags, int64_t start, int64_t dur, float vol = 1.f, int64_t fi = 0, int64_t fo = 0) {
    AudioCandidate c; c.flags = flags; c.startMs = start; c.durationMs = dur; c.volume = vol; c.fadeInMs = fi; c.fadeOutMs = fo; return c;
}

int main() {
    // frames: ceil(durationMs * fps / 1000), at least 1
    CHECK(totalFrames(1000, 30) == 30);
    CHECK(totalFrames(1001, 30) == 31);
    CHECK(totalFrames(100, 24) == 3);       // 2.4 -> 3
    CHECK(totalFrames(1, 24) == 1);
    CHECK(totalFrames(0, 30) == 1);
    CHECK(totalFrames(600000, 60) == 36000);

    // bitrate
    CHECK(videoBitrate(1280, 720, 30, 0, "High") == (int)(1280.0 * 720 * 30 * 0.28));
    CHECK(videoBitrate(1280, 720, 30, 0, "Low") == (int)(1280.0 * 720 * 30 * 0.12));
    CHECK(videoBitrate(1280, 720, 30, 0, "MEDIUM quality") == (int)(1280.0 * 720 * 30 * 0.20));
    CHECK(videoBitrate(1280, 720, 30, 0, "Max") == (int)(1280.0 * 720 * 30 * 0.40));
    CHECK(videoBitrate(1280, 720, 30, 0, "") == (int)(1280.0 * 720 * 30 * 0.28));
    CHECK(videoBitrate(64, 64, 24, 0, "Low") == 500000);                    // lower clamp
    CHECK(videoBitrate(3840, 2160, 60, 0, "Max") == 80000000);              // upper clamp
    CHECK(videoBitrate(1280, 720, 30, 8000, "Low") == 8000000);             // custom wins
    CHECK(videoBitrate(1280, 720, 30, 100, "Low") == 500000);               // custom clamped up
    CHECK(videoBitrate(1280, 720, 30, 999999, "Low") == 50000000);          // custom clamped down

    // audio picking
    const int V = AF_VISIBLE | AF_HAS_URI;
    std::vector<AudioCandidate> l = {
        cand(V | AF_TYPE_AUDIO, 0, 5000, 0.5f, 200, 300),                   // 0 audio: picked with volume + fades
        cand(V | AF_TYPE_AUDIO | AF_TRACK_MUTED, 0, 5000),                  // 1 muted lane
        cand(V | AF_TYPE_AUDIO | AF_CLIP_MUTED, 0, 5000),                   // 2 muted clip
        cand(AF_HAS_URI | AF_TYPE_AUDIO, 0, 5000),                           // 3 hidden
        cand(AF_VISIBLE | AF_TYPE_AUDIO, 0, 5000),                           // 4 no file
        cand(V | AF_TYPE_VIDEO, 1000, 4000, 0.3f, 50, 50),                  // 5 video: own volume + fades
        cand(V | AF_TYPE_VIDEO | AF_IS_IMAGE, 0, 5000),                     // 6 photo: no sound
        cand(V | AF_TYPE_VIDEO | AF_TRACK_MUTED, 0, 5000),                  // 7 muted lane: silences video layers too
        cand(V, 0, 5000),                                                    // 8 other layer type
        cand(V | AF_TYPE_AUDIO, 4950, 3000),                                 // 9 only 50 ms inside the video
        cand(V | AF_TYPE_AUDIO, 4900, 3000),                                 // 10 exactly 100 ms inside: picked
        cand(V | AF_TYPE_AUDIO, 6000, 3000),                                 // 11 starts after the video end
        cand(V | AF_TYPE_AUDIO, 0, 80),                                      // 12 shorter than 100 ms
    };
    auto p = pickAudioClips(l, 5000);
    CHECK(p.size() == 3);
    CHECK(p[0].index == 0 && p[0].volume == 0.5f && p[0].fadeInMs == 200 && p[0].fadeOutMs == 300);
    CHECK(p[1].index == 5 && p[1].volume == 0.3f && p[1].fadeInMs == 50 && p[1].fadeOutMs == 50);
    CHECK(p[2].index == 10);
    CHECK(pickAudioClips({}, 5000).empty());

    std::printf(fails ? "export_plan_selftest: %d of %d FAILED\n" : "export_plan_selftest: all %d checks OK\n",
                fails ? fails : checks, checks);
    return fails ? 1 : 0;
}
