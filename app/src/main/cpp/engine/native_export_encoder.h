#pragma once
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaMuxer.h>
#include <android/native_window.h>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "audio_mixer.h"

namespace am { class Renderer; }

namespace am {

// Surface-input H.264 encoder + AAC encoder + MP4 muxer. Must be driven on the same thread as Renderer.
//
// Video: the caller draws a frame with Renderer::beginFrame/drawLayer, then submitFrame(pts).
// Audio: addAudioClip() for every sound on the timeline, then startAudio(); the clips are mixed
//        (volume, fades, speed, trim) and AAC-encoded by pumpAudio(upToUs), which the caller calls
//        while it renders video. finish() mixes whatever is left.
class NativeExportEncoder {
public:
    NativeExportEncoder() = default;
    ~NativeExportEncoder();
    bool start(Renderer* renderer, const std::string& path, int width, int height, int fps, int bitrate);

    // ---- audio (optional, call before the first frame) ----
    // `fd` is dup()ed. length <= 0 = until the end of the file.
    // duckRelUs / duckGains (build 194): the clip's duck curve on its own clock (microseconds
    // from its start + the gain there), same length; empty = no ducking.
    // noiseProfile / nrStrength (build 195): the clip's per-bin noise profile (kNoiseBins
    // magnitudes, noise_gate.h) + gate strength; empty / 0 = the source passes ungated.
    bool addAudioClip(int fd, int64_t length, int64_t startUs, int64_t durUs, int64_t trimStartUs,
                      double speed, float volume, int64_t fadeInUs, int64_t fadeOutUs,
                      const std::vector<int64_t>& duckRelUs, const std::vector<float>& duckGains,
                      const std::vector<float>& noiseProfile, float nrStrength);
    bool startAudio(int64_t totalDurationUs, int bitrate);
    bool pumpAudio(int64_t upToUs);

    bool renderSolidFrame(float r, float g, float b, float a, int64_t ptsNs);
    // Submit a frame already composed by Renderer::beginFrame/drawLayer.
    bool submitFrame(int64_t ptsNs);
    bool finish();
    void cancel();
    const std::string& error() const { return error_; }
private:
    enum Kind { kVideo = 0, kAudio = 1 };
    struct Stream {
        AMediaCodec* codec = nullptr;
        AMediaFormat* format = nullptr;
        int track = -1;
    };
    struct Pending {
        int kind;
        std::vector<uint8_t> data;
        AMediaCodecBufferInfo info;
    };
    struct AudioFile { int fd; int64_t length; };

    bool drain(Kind kind, bool endOfStream);
    bool tryStartMuxer();
    bool writeSample(Kind kind, const uint8_t* data, const AMediaCodecBufferInfo& info);
    bool encodeAudio(const int16_t* pcm, int frames, int64_t ptsUs);
    void cleanup(bool keepOutputFile);
    Stream& stream(Kind k) { return k == kVideo ? video_ : audio_; }

    Renderer* renderer_ = nullptr;
    Stream video_, audio_;
    AMediaMuxer* muxer_ = nullptr;
    int muxerFd_ = -1;                      // output file, kept open while the muxer lives
    ANativeWindow* inputWindow_ = nullptr;
    bool muxerStarted_ = false;
    bool started_ = false;
    bool finished_ = false;
    bool hasAudio_ = false;
    int64_t lastPtsNs_ = -1;
    std::vector<Pending> pending_;          // encoded samples that arrived before the muxer could start
    std::vector<AudioFile> audioFiles_;
    std::unique_ptr<AudioMixer> mixer_;
    std::vector<AudioClipSpec> pendingClips_;
    int64_t totalAudioFrames_ = 0;
    std::string path_;
    std::string error_;
};

} // namespace am
