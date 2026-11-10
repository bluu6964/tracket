// After Motion native engine - audio building blocks (pure C++, no Android headers, so the logic
// can be unit-tested on a normal computer: see tests/audio_selftest.cpp).
//
// Everything works on interleaved STEREO float frames at 48 kHz (kAudioRate).
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

namespace am {

constexpr int kAudioRate = 48000;

// Sequential source of stereo frames.
class FrameSource {
public:
    virtual ~FrameSource() = default;
    // Writes up to `frames` stereo frames (2 floats each). Returns how many were written; fewer than
    // requested only means the end of the stream was reached.
    virtual int read(float* dst, int frames) = 0;
};

// Pitch-preserving speed change (WSOLA: waveform-similarity overlap-add). speed 2 = twice as fast.
// Replaces Media3's SonicAudioProcessor for the native export. Output length = input length / speed.
class TimeStretcher : public FrameSource {
public:
    TimeStretcher(std::unique_ptr<FrameSource> src, double speed)
        : src_(std::move(src)), speed_(std::min(4.0, std::max(0.1, speed))) {
        win_.resize(kBlock);
        for (int i = 0; i < kBlock; ++i)
            win_[i] = 0.5f - 0.5f * std::cos(2.0f * 3.14159265358979f * (float)i / (float)kBlock);
        tail_.assign((size_t)kHop * 2, 0.f);
    }

    int read(float* dst, int frames) override {
        int done = 0;
        while (done < frames) {
            if (outPos_ >= outBuf_.size() / 2) {
                if (!produce()) break;
            }
            const int avail = (int)(outBuf_.size() / 2 - outPos_);
            const int n = std::min(avail, frames - done);
            std::copy(outBuf_.begin() + outPos_ * 2, outBuf_.begin() + (outPos_ + n) * 2, dst + (size_t)done * 2);
            outPos_ += n;
            done += n;
        }
        return done;
    }

private:
    static constexpr int kHop = 512;       // output frames per step
    static constexpr int kBlock = 1024;    // analysis window = 2 hops
    static constexpr int kTol = 256;       // search range for the best matching position

    int64_t inEnd() const { return inBase_ + (int64_t)(in_.size() / 2); }

    float inAt(int64_t frame, int ch) const {
        if (frame < inBase_ || frame >= inEnd()) return 0.f;
        return in_[(size_t)(frame - inBase_) * 2 + ch];
    }

    // Make sure input frames up to (not including) `end` are buffered, unless the stream ended.
    void ensure(int64_t end) {
        std::vector<float> chunk;
        while (!eos_ && inEnd() < end) {
            chunk.assign((size_t)4096 * 2, 0.f);
            const int got = src_->read(chunk.data(), 4096);
            in_.insert(in_.end(), chunk.begin(), chunk.begin() + (size_t)got * 2);
            if (got < 4096) eos_ = true;
        }
    }

    void trim(int64_t keepFrom) {
        if (keepFrom <= inBase_) return;
        const int64_t drop = std::min<int64_t>(keepFrom - inBase_, (int64_t)(in_.size() / 2));
        in_.erase(in_.begin(), in_.begin() + (size_t)drop * 2);
        inBase_ += drop;
    }

    // Produces the next kHop output frames into outBuf_. False at the end of the stream.
    bool produce() {
        outBuf_.assign((size_t)kHop * 2, 0.f);
        outPos_ = 0;
        int64_t start;
        if (first_) {
            start = 0;
            ensure(kBlock + kTol);
            if (inEnd() <= 0) return false;
        } else {
            const int64_t nominal = (int64_t)std::llround(pos_);
            ensure(nominal + kTol + kBlock + 1);
            if (nominal - kTol >= inEnd()) return false;       // ran past the end of the source
            start = bestStart(nominal);
            if (start >= inEnd()) return false;
        }
        for (int i = 0; i < kHop; ++i) {
            const float w = first_ ? 1.f : win_[i];
            for (int ch = 0; ch < 2; ++ch)
                outBuf_[(size_t)i * 2 + ch] = tail_[(size_t)i * 2 + ch] + w * inAt(start + i, ch);
        }
        for (int i = 0; i < kHop; ++i) {
            const float w = win_[kHop + i];
            for (int ch = 0; ch < 2; ++ch) tail_[(size_t)i * 2 + ch] = w * inAt(start + kHop + i, ch);
        }
        prevStart_ = start;
        first_ = false;
        pos_ += kHop * speed_;
        trim(std::min<int64_t>(prevStart_ + kHop, (int64_t)std::llround(pos_) - kTol) - 8);
        return true;
    }

    // Position near `nominal` whose next kHop frames best match the natural continuation of the
    // previously used block (cross-correlation on a mono mix, subsampled for speed).
    int64_t bestStart(int64_t nominal) const {
        const int64_t refStart = prevStart_ + kHop;
        double best = -1e30;
        int64_t bestPos = nominal;
        for (int d = -kTol; d <= kTol; d += 2) {
            const int64_t cand = nominal + d;
            if (cand < inBase_) continue;
            double c = 0, e = 1e-9;
            for (int i = 0; i < kHop; i += 4) {
                const float a = inAt(refStart + i, 0) + inAt(refStart + i, 1);
                const float b = inAt(cand + i, 0) + inAt(cand + i, 1);
                c += (double)a * b;
                e += (double)b * b;
            }
            const double score = c / std::sqrt(e);
            if (score > best) { best = score; bestPos = cand; }
        }
        return bestPos;
    }

    std::unique_ptr<FrameSource> src_;
    double speed_;
    std::vector<float> win_, tail_, in_, outBuf_;
    size_t outPos_ = 0;
    int64_t inBase_ = 0, prevStart_ = 0;
    double pos_ = 0;
    bool first_ = true, eos_ = false;
};

}  // namespace am
