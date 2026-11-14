// After Motion native engine - timeline audio mixer. Pure C++ (testable on a computer).
//
// Mixes any number of clips (audio layers + the sound of video layers) onto the project timeline:
// start offset, trim, constant speed (pitch preserved), volume, fade in / out and ducking
// (build 194: a per-clip gain curve computed from another layer's loudness) - the same rules
// as the Media3 exporter (VideoExporter.buildAudioSequence + GainFadeAudioProcessor).
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

#include "audio_dsp.h"

namespace am {

struct AudioClipSpec {
    int source = 0;               // free id for the SourceFactory (e.g. index of an opened file)
    int64_t startFrame = 0;       // timeline position (48 kHz frames)
    int64_t durFrames = 0;        // frames actually played (already cut at the project end)
    int64_t fullDurFrames = 0;    // length of the whole layer on the timeline (fade-out clock)
    int64_t trimStartUs = 0;      // where in the source file the layer starts
    double speed = 1.0;
    float volume = 1.0f;
    int64_t fadeInFrames = 0;
    int64_t fadeOutFrames = 0;
    // Audio ducking (build 194): gain breakpoints on the clip's own clock (frames from its
    // start, ascending) with the gain at each; the mix multiplies volume x fade x duck.
    // Computed live by the Kotlin side from the trigger layer's envelope (model/Ducking) —
    // empty = no ducking. Same length, at least one point when non-empty.
    std::vector<int64_t> duckFrames;
    std::vector<float> duckGains;
};

using SourceFactory = std::function<std::unique_ptr<FrameSource>(const AudioClipSpec&)>;
// Receives interleaved stereo int16 PCM and the timestamp of its first frame. Return false to abort.
using PcmSink = std::function<bool(const int16_t* pcm, int frames, int64_t ptsUs)>;

class AudioMixer {
public:
    AudioMixer(SourceFactory factory, int64_t totalFrames) : factory_(std::move(factory)), total_(totalFrames) {}

    void addClip(const AudioClipSpec& spec) { clips_.push_back({spec, nullptr, false, false}); }
    bool empty() const { return clips_.empty(); }
    int64_t cursor() const { return cursor_; }
    int64_t totalFrames() const { return total_; }
    bool finished() const { return cursor_ >= total_; }

    // Mixes the timeline up to `upToFrame` (clamped to the total) and hands it to `sink` in chunks.
    bool render(int64_t upToFrame, const PcmSink& sink);

    // Preview: jump to another timeline position (all sources are closed and opened again where they are needed).
    // A clip that is already playing at that position is opened at the matching place of its file.
    void seek(int64_t frame);
    // Preview: open the clips that start before `upToFrame` now, so their decoders are ready when the cursor gets there.
    void prefetch(int64_t upToFrame);

private:
    struct Clip {
        AudioClipSpec spec;
        std::unique_ptr<FrameSource> src;
        bool opened;
        bool failed;
    };
    static float fadeGain(const AudioClipSpec& s, int64_t rel);
    // Duck gain at clip-relative frame `rel`: linear between the spec's breakpoints,
    // clamped to the end gains outside them, 1 when the clip has no duck curve.
    // Mirrors model/Ducking.gainAt 1:1 — keep the two in step.
    static float duckGain(const AudioClipSpec& s, int64_t rel);
    void open(Clip& c, int64_t from);

    SourceFactory factory_;
    int64_t total_;
    int64_t cursor_ = 0;
    std::vector<Clip> clips_;
};

}  // namespace am
