#include "audio_mixer.h"

#include <algorithm>

namespace am {
namespace {
constexpr int kChunk = 1024;
}

float AudioMixer::fadeGain(const AudioClipSpec& s, int64_t rel) {
    float g = 1.f;
    if (s.fadeInFrames > 0 && rel < s.fadeInFrames)
        g = std::min(g, (float)std::max<int64_t>(rel, 0) / (float)s.fadeInFrames);
    const int64_t left = s.fullDurFrames - rel;
    if (s.fadeOutFrames > 0 && left < s.fadeOutFrames)
        g = std::min(g, (float)std::max<int64_t>(left, 0) / (float)s.fadeOutFrames);
    return std::min(1.f, std::max(0.f, g));
}

// Opens the source of a clip so that its first frame is the one that plays at timeline frame `from`.
void AudioMixer::open(Clip& c, int64_t from) {
    c.opened = true;
    AudioClipSpec s = c.spec;
    if (from > s.startFrame) {
        // Already running at `from`: start that far into the file (the source time runs `speed` times faster).
        s.trimStartUs += (int64_t)((double)(from - s.startFrame) * 1e6 / (double)kAudioRate * s.speed);
    }
    c.src = factory_ ? factory_(s) : nullptr;
    if (!c.src) c.failed = true;   // no sound in this file: skip the clip
}

void AudioMixer::seek(int64_t frame) {
    cursor_ = std::max<int64_t>(0, frame);
    for (auto& c : clips_) {
        c.src.reset();
        c.opened = false;
        c.failed = false;
    }
}

void AudioMixer::prefetch(int64_t upToFrame) {
    for (auto& c : clips_) {
        if (c.opened || c.failed) continue;
        const AudioClipSpec& s = c.spec;
        if (s.startFrame >= upToFrame || cursor_ >= s.startFrame + s.durFrames) continue;
        open(c, std::max(cursor_, s.startFrame));
    }
}

bool AudioMixer::render(int64_t upToFrame, const PcmSink& sink) {
    const int64_t end = std::min(upToFrame, total_);
    std::vector<float> mix((size_t)kChunk * 2), tmp((size_t)kChunk * 2);
    std::vector<int16_t> pcm((size_t)kChunk * 2);
    while (cursor_ < end) {
        const int n = (int)std::min<int64_t>(kChunk, end - cursor_);
        std::fill(mix.begin(), mix.begin() + (size_t)n * 2, 0.f);
        for (auto& c : clips_) {
            const AudioClipSpec& s = c.spec;
            const int64_t clipEnd = s.startFrame + s.durFrames;
            if (c.failed) continue;
            if (cursor_ >= clipEnd) {
                c.src.reset();  // finished: free the decoder as soon as possible
                continue;
            }
            const int64_t from = std::max(cursor_, s.startFrame);
            const int64_t to = std::min(cursor_ + n, clipEnd);
            if (to <= from) continue;
            if (!c.opened) open(c, from);
            if (c.failed || !c.src) continue;
            const int cnt = (int)(to - from);
            std::fill(tmp.begin(), tmp.begin() + (size_t)cnt * 2, 0.f);
            c.src->read(tmp.data(), cnt);
            float* out = mix.data() + (size_t)(from - cursor_) * 2;
            for (int i = 0; i < cnt; ++i) {
                const float g = s.volume * fadeGain(s, from + i - s.startFrame);
                out[(size_t)i * 2] += tmp[(size_t)i * 2] * g;
                out[(size_t)i * 2 + 1] += tmp[(size_t)i * 2 + 1] * g;
            }
        }
        for (int i = 0; i < n * 2; ++i) {
            const float v = std::min(1.f, std::max(-1.f, mix[i]));
            pcm[i] = (int16_t)std::lrintf(v * 32767.f);
        }
        const int64_t ptsUs = cursor_ * 1000000LL / kAudioRate;
        cursor_ += n;
        if (!sink(pcm.data(), n, ptsUs)) return false;
    }
    return true;
}

}  // namespace am
