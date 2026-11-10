#include "export_plan.h"

#include <algorithm>
#include <cctype>

namespace am {

int totalFrames(int64_t durationMs, int fps) {
    const int64_t n = (durationMs * (int64_t)fps + 999) / 1000;
    return (int)std::max<int64_t>(n, 1);
}

namespace {
bool containsIgnoreCase(const std::string& s, const char* needle) {
    std::string a(s), b(needle);
    for (char& c : a) c = (char)std::tolower((unsigned char)c);
    for (char& c : b) c = (char)std::tolower((unsigned char)c);
    return a.find(b) != std::string::npos;
}
double bitsPerPixel(const std::string& q) {
    if (containsIgnoreCase(q, "low")) return 0.12;
    if (containsIgnoreCase(q, "medium")) return 0.20;
    if (containsIgnoreCase(q, "max")) return 0.40;
    return 0.28;  // High
}
}  // namespace

int videoBitrate(int w, int h, int fps, int customKbps, const std::string& quality) {
    if (customKbps > 0) return std::min(std::max(customKbps, 500), 50000) * 1000;
    const double raw = (double)((int64_t)w * h * fps) * bitsPerPixel(quality);
    const int64_t v = (int64_t)raw;  // Kotlin Double.toLong(): truncates
    return (int)std::min<int64_t>(std::max<int64_t>(v, 500000), 80000000);
}

std::vector<AudioPick> pickAudioClips(const std::vector<AudioCandidate>& clips, int64_t videoEndMs) {
    std::vector<AudioPick> out;
    for (size_t i = 0; i < clips.size(); ++i) {
        const AudioCandidate& c = clips[i];
        if (!(c.flags & AF_VISIBLE) || !(c.flags & AF_HAS_URI) || (c.flags & AF_CLIP_MUTED)) continue;
        const int type = c.flags & AF_TYPE_MASK;
        AudioPick p{(int)i, 1.f, 0, 0};
        // Audio layers and video layers (not photos) use their own volume and fades; a muted lane silences both.
        if ((type == AF_TYPE_AUDIO || (type == AF_TYPE_VIDEO && !(c.flags & AF_IS_IMAGE))) && !(c.flags & AF_TRACK_MUTED)) {
            p.volume = c.volume;
            p.fadeInMs = c.fadeInMs;
            p.fadeOutMs = c.fadeOutMs;
        } else {
            continue;
        }
        if (std::min(c.durationMs, videoEndMs - c.startMs) < 100) continue;
        out.push_back(p);
    }
    return out;
}

}  // namespace am
