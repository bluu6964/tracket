// After Motion native engine - audio file decoder (NDK AMediaExtractor + AMediaCodec).
// Delivers 48 kHz stereo float frames, starting at a given position in the file.
#pragma once

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>

#include <cstdint>
#include <memory>
#include <vector>

#include "audio_dsp.h"

namespace am {

class AudioFileSource : public FrameSource {
public:
    // fd stays owned by the caller and must stay open while the source lives.
    // Returns null if the file has no decodable audio track.
    static std::unique_ptr<AudioFileSource> open(int fd, int64_t length, int64_t startUs);
    ~AudioFileSource() override;
    int read(float* dst, int frames) override;

private:
    AudioFileSource() = default;
    bool init(int fd, int64_t length, int64_t startUs);
    bool decodeMore();                       // one round of feed + drain; false = nothing more to come
    void appendPcm(const uint8_t* data, int bytes);

    AMediaExtractor* ex_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    bool inputEos_ = false, outputEos_ = false;
    int srcRate_ = 48000, srcCh_ = 2, pcmEnc_ = 2;   // pcmEnc: 2 = 16 bit, 4 = float
    int64_t startUs_ = 0;
    std::vector<float> fifo_;                // stereo frames at the source rate
    double pos_ = 0;                         // read position inside fifo_ (frames)
};

}  // namespace am
