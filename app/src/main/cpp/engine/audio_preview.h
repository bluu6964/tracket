// After Motion native engine - editor audio preview, the part without Android headers (testable on a computer:
// tests/audio_preview_selftest.cpp). The sound of the editor is mixed by the SAME AudioMixer as the export
// (trim, speed with pitch kept, volume, fades), on a background thread, into a ring buffer. The audio
// callback (AAudio, see audio_preview_jni.cpp) only copies from the ring, so it never waits for a decoder.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "audio_mixer.h"

namespace am {

// Single producer / single consumer ring of interleaved stereo int16 frames. The consumer never blocks or allocates.
class PcmRing {
public:
    explicit PcmRing(size_t capacityFrames);
    size_t capacity() const { return cap_; }
    size_t available() const;                         // frames that can be read
    size_t space() const;                             // frames that can be written
    size_t write(const int16_t* pcm, size_t frames);  // producer; returns how many fitted
    size_t read(int16_t* dst, size_t frames);         // consumer; returns how many were there
    size_t skip(size_t frames);                       // consumer: drop frames (returns how many)

private:
    size_t cap_;                                      // power of two
    std::vector<int16_t> buf_;
    std::atomic<uint64_t> head_{0};                   // frames written
    std::atomic<uint64_t> tail_{0};                   // frames read
};

// Mixes the clips from `startFrame` on, as fast as the ring has room, until the last clip ends.
class PreviewFeeder {
public:
    PreviewFeeder(SourceFactory factory, const std::vector<AudioClipSpec>& clips, int64_t startFrame,
                  size_t ringFrames = 65536);
    ~PreviewFeeder();                                 // stops the thread, frees the decoders
    PreviewFeeder(const PreviewFeeder&) = delete;
    PreviewFeeder& operator=(const PreviewFeeder&) = delete;

    void start();
    void stop();
    PcmRing& ring() { return ring_; }
    int64_t startFrame() const { return startFrame_; }
    // True once everything up to the end of the last clip is in the ring (then the ring only drains).
    bool finished() const { return finished_.load(); }

private:
    void run();

    PcmRing ring_;
    std::unique_ptr<AudioMixer> mixer_;
    int64_t startFrame_;
    std::thread thread_;
    std::atomic<bool> stop_{false};
    std::atomic<bool> finished_{false};
};

}  // namespace am
