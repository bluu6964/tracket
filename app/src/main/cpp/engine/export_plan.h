// Plan numbers of a native export that do not need Android: frame count, video bitrate, which clips carry sound.
// Port of the setup part of NativeProjectExporter.runExport / addAudio. Pure C++, host test: tests/export_plan_selftest.cpp.
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace am {

// Frames needed to cover durationMs at fps (at least 1).
int totalFrames(int64_t durationMs, int fps);

// Video bitrate in bit/s. customKbps > 0 wins (clamped 500..50000 kbit/s); otherwise width * height * fps * bits per
// pixel of the quality name ("low" / "medium" / "max", anything else = High), clamped 500 kbit/s .. 80 Mbit/s.
int videoBitrate(int w, int h, int fps, int customKbps, const std::string& quality);

enum AudioClipFlags {
    AF_VISIBLE = 1,
    AF_HAS_URI = 2,
    AF_CLIP_MUTED = 4,
    AF_TRACK_MUTED = 8,
    AF_IS_IMAGE = 16,
    AF_TYPE_AUDIO = 1 << 5,  // type bits (5..6): 1 = audio layer, 2 = video layer, 0 = anything else
    AF_TYPE_VIDEO = 2 << 5,
    AF_TYPE_MASK = 3 << 5,
};

struct AudioCandidate {
    int flags = 0;
    int64_t startMs = 0, durationMs = 0, fadeInMs = 0, fadeOutMs = 0;
    float volume = 1.f;  // the layer's own volume (used for audio layers only)
};

struct AudioPick {
    int index;  // into the candidate list
    float volume;
    int64_t fadeInMs, fadeOutMs;
};

// Clips that go into the mix, in candidate order: visible, with a file, not muted; audio layers and video layers (not
// photos), unless their lane is muted, with their own volume and fades; and at least 100 ms of the
// clip must lie inside the video.
std::vector<AudioPick> pickAudioClips(const std::vector<AudioCandidate>& clips, int64_t videoEndMs);

}  // namespace am
