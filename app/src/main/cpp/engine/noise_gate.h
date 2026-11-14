// After Motion native engine - audio noise reduction (build 195): a spectral gate
// FrameSource that wraps one clip's decoded source inside the shared AudioMixer's
// per-clip source chain (decoder -> THIS GATE -> TimeStretcher), ahead of the mixer's
// volume x fade x duck gain. Preview and export build the same chain in their two
// factories, so both hear the gate identically.
//
// THE LAW is model/NoiseReduction.kt, mirrored 1:1 - keep the two in step: STFT
// frames of kNoiseFftSize Hann-windowed samples every kNoiseHop; a per-bin noise
// profile (the 10th percentile of the bin's magnitudes over the clip's played
// window, computed by the Kotlin side and handed in with the clip spec); the raw
// gain sqrt(max(1 - (over x n/m)^2, floor^2)) with over = kNoiseMaxOver x strength
// (strength 0 = every gain 1 = exact passthrough); 3-tap frequency smoothing;
// a one-pole temporal follower (10 ms attack / 80 ms release, first frame taken
// directly); overlap-add reconstruction divided by the accumulated squared-window
// sum. In stereo the gain of a bin comes from the LOUDER channel's magnitude and
// applies to both channels, so the stereo image never wobbles.
//
// STREAMING: the gate is per-clip state carried across read() calls - the analysis
// FIFO, the smoothed gains and the overlap-add accumulators live in the instance.
// It reads AHEAD of the caller by less than one frame, so its output stays aligned
// sample-for-sample with the unwrapped source (no added latency on the timeline),
// and the output length equals the input length exactly (the tail is zero-padded
// frames, flushed at the end of the stream).
#pragma once

#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>

#include "audio_dsp.h"

namespace am {

constexpr int kNoiseFftSize = 2048;
constexpr int kNoiseHop = 512;
constexpr int kNoiseBins = kNoiseFftSize / 2 + 1;
constexpr float kNoiseFloorGain = 0.05f;
constexpr float kNoiseMaxOver = 4.0f;
constexpr double kNoiseAttackMs = 10.0;
constexpr double kNoiseReleaseMs = 80.0;

// One bin's raw gate gain. Mirrors NoiseReduction.rawGain 1:1.
inline float noiseGateRawGain(float magnitude, float noise, float strength) {
    const float s = std::min(1.f, std::max(0.f, strength));
    if (s <= 0.f) return 1.f;
    if (magnitude <= 0.f) return kNoiseFloorGain;
    const double r = (double)(kNoiseMaxOver * s * noise / magnitude);
    const double g2 = 1.0 - r * r;
    const double floor2 = (double)(kNoiseFloorGain * kNoiseFloorGain);
    return g2 <= floor2 ? kNoiseFloorGain : (float)std::sqrt(g2);
}

// The 3-tap frequency smoothing of one frame's raw gains. Mirrors
// NoiseReduction.smoothAcrossFrequency 1:1.
inline void noiseGateSmoothAcrossFrequency(const float* raw, float* out, int bins) {
    for (int k = 0; k < bins; ++k) {
        const float a = raw[std::max(0, k - 1)];
        const float b = raw[k];
        const float c = raw[std::min(bins - 1, k + 1)];
        out[k] = 0.25f * a + 0.5f * b + 0.25f * c;
    }
}

// One-pole coefficient per STFT frame for a time constant in ms. Mirrors
// NoiseReduction.stepCoef 1:1.
inline double noiseGateStepCoef(double tauMs) {
    return 1.0 - std::exp(-(double)kNoiseHop * 1000.0 / (tauMs * (double)kAudioRate));
}

namespace detail {
// Iterative radix-2 FFT (in place, unscaled), the BeatDetect/NoiseReduction shape.
inline void noiseFft(float* re, float* im, int n, bool inverse) {
    if (inverse) for (int i = 0; i < n; ++i) im[i] = -im[i];
    int j = 0;
    for (int i = 1; i < n; ++i) {
        int bit = n >> 1;
        while (j & bit) { j ^= bit; bit >>= 1; }
        j |= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    const double pi = 3.14159265358979323846;
    for (int len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * pi / len;
        const float wr = (float)std::cos(ang), wi = (float)std::sin(ang);
        for (int i = 0; i < n; i += len) {
            float cwr = 1.f, cwi = 0.f;
            for (int k = 0; k < len / 2; ++k) {
                const float ur = re[i + k], ui = im[i + k];
                const float vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi;
                const float vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr;
                re[i + k] = ur + vr;
                im[i + k] = ui + vi;
                re[i + k + len / 2] = ur - vr;
                im[i + k + len / 2] = ui - vi;
                const float nwr = cwr * wr - cwi * wi;
                cwi = cwr * wi + cwi * wr;
                cwr = nwr;
            }
        }
    }
    if (inverse) {
        for (int i = 0; i < n; ++i) {
            re[i] /= (float)n;
            im[i] = -im[i] / (float)n;
        }
    }
}
}  // namespace detail

class NoiseGateSource : public FrameSource {
public:
    // `profile` must hold exactly kNoiseBins magnitudes (the Kotlin-computed noise
    // profile); anything else - or strength <= 0 - makes this a pure passthrough.
    NoiseGateSource(std::unique_ptr<FrameSource> src, std::vector<float> profile, float strength)
        : src_(std::move(src)), profile_(std::move(profile)), strength_(strength) {
        active_ = src_ && profile_.size() == (size_t)kNoiseBins && strength_ > 0.f;
        win_.resize(kNoiseFftSize);
        for (int i = 0; i < kNoiseFftSize; ++i)
            win_[i] = 0.5f - 0.5f * std::cos(2.0f * 3.14159265358979f * (float)i / (float)kNoiseFftSize);
        gains_.assign(kNoiseBins, 1.f);
        reL_.assign(kNoiseFftSize, 0.f); imL_.assign(kNoiseFftSize, 0.f);
        reR_.assign(kNoiseFftSize, 0.f); imR_.assign(kNoiseFftSize, 0.f);
        attack_ = noiseGateStepCoef(kNoiseAttackMs);
        release_ = noiseGateStepCoef(kNoiseReleaseMs);
    }

    int read(float* dst, int frames) override {
        if (!active_) return src_ ? src_->read(dst, frames) : 0;
        while (outAvail() < frames && !flushed_) pump();
        const int n = std::min(frames, outAvail());
        if (n <= 0) return 0;
        std::copy(outBuf_.begin() + (size_t)outHead_ * 2,
                  outBuf_.begin() + (size_t)(outHead_ + n) * 2, dst);
        outHead_ += n;
        if (outHead_ > 8192) {   // compact the delivered prefix away
            outBuf_.erase(outBuf_.begin(), outBuf_.begin() + (size_t)outHead_ * 2);
            outHead_ = 0;
        }
        return n;
    }

private:
    int outAvail() const { return (int)(outBuf_.size() / 2) - outHead_; }
    int64_t inEnd() const { return inBase_ + (int64_t)(inBuf_.size() / 2); }

    float inAt(int64_t frame, int ch) const {
        if (frame < inBase_) return 0.f;
        if (inTotal_ >= 0 && frame >= inTotal_) return 0.f;   // zero padding past the end
        const int64_t idx = frame - inBase_;
        if (idx >= (int64_t)(inBuf_.size() / 2)) return 0.f;
        return inBuf_[(size_t)idx * 2 + ch];
    }

    // Pull from the wrapped source until the input covers `end` frames or it ends.
    void fillTo(int64_t end) {
        std::vector<float> chunk((size_t)4096 * 2);
        while (!eof_ && inEnd() < end) {
            const int got = src_->read(chunk.data(), 4096);
            if (got > 0) inBuf_.insert(inBuf_.end(), chunk.begin(), chunk.begin() + (size_t)got * 2);
            if (got < 4096) {   // FrameSource contract: a short read is the end of the stream
                eof_ = true;
                inTotal_ = inEnd();
            }
        }
    }

    // One step: process the next frame, or flush the tail when the input is over.
    void pump() {
        if (eof_ && inTotal_ >= 0 && nextFrame_ >= inTotal_) {
            finalizeTo(inTotal_);
            flushed_ = true;
            return;
        }
        fillTo(nextFrame_ + kNoiseFftSize);
        if (!eof_ && inEnd() < nextFrame_ + kNoiseFftSize) return;   // cannot happen per contract; never spin
        if (eof_ && nextFrame_ >= inTotal_) {
            finalizeTo(inTotal_);
            flushed_ = true;
            return;
        }
        processFrame(nextFrame_);
        nextFrame_ += kNoiseHop;
        const int64_t fin = eof_ ? std::min(nextFrame_, inTotal_) : nextFrame_;
        finalizeTo(fin);
        // Input before nextFrame_ can never be read again.
        if (nextFrame_ - inBase_ > 8192) {
            const int64_t drop = nextFrame_ - inBase_;
            inBuf_.erase(inBuf_.begin(), inBuf_.begin() + (size_t)drop * 2);
            inBase_ = nextFrame_;
        }
    }

    void processFrame(int64_t start) {
        for (int ch = 0; ch < 2; ++ch) {
            float* re = ch == 0 ? reL_.data() : reR_.data();
            float* im = ch == 0 ? imL_.data() : imR_.data();
            for (int i = 0; i < kNoiseFftSize; ++i) {
                re[i] = inAt(start + i, ch) * win_[i];
                im[i] = 0.f;
            }
            detail::noiseFft(re, im, kNoiseFftSize, false);
        }
        float raw[kNoiseBins], sm[kNoiseBins];
        for (int k = 0; k < kNoiseBins; ++k) {
            const float ml = std::sqrt(reL_[k] * reL_[k] + imL_[k] * imL_[k]);
            const float mr = std::sqrt(reR_[k] * reR_[k] + imR_[k] * imR_[k]);
            raw[k] = noiseGateRawGain(std::max(ml, mr), profile_[k], strength_);
        }
        noiseGateSmoothAcrossFrequency(raw, sm, kNoiseBins);
        if (!started_) {
            for (int k = 0; k < kNoiseBins; ++k) gains_[k] = sm[k];
            started_ = true;
        } else {
            for (int k = 0; k < kNoiseBins; ++k) {
                const double a = sm[k] < gains_[k] ? attack_ : release_;
                gains_[k] = (float)(gains_[k] + (sm[k] - gains_[k]) * a);
            }
        }
        for (int ch = 0; ch < 2; ++ch) {
            float* re = ch == 0 ? reL_.data() : reR_.data();
            float* im = ch == 0 ? imL_.data() : imR_.data();
            for (int k = 0; k < kNoiseFftSize; ++k) {
                const float g = gains_[std::min(k, kNoiseFftSize - k)];
                re[k] *= g;
                im[k] *= g;
            }
            detail::noiseFft(re, im, kNoiseFftSize, true);
        }
        ensureAcc(start + kNoiseFftSize);
        for (int i = 0; i < kNoiseFftSize; ++i) {
            const size_t at = (size_t)(start + i - accBase_);
            const float w = win_[i];
            accL_[at] += reL_[i] * w;
            accR_[at] += reR_[i] * w;
            norm_[at] += w * w;
        }
    }

    void ensureAcc(int64_t end) {
        const size_t need = (size_t)std::max<int64_t>(0, end - accBase_);
        if (accL_.size() < need) {
            accL_.resize(need, 0.f);
            accR_.resize(need, 0.f);
            norm_.resize(need, 0.f);
        }
    }

    // Emit the finalized samples in [finalized_, upTo) into the output queue.
    void finalizeTo(int64_t upTo) {
        if (upTo <= finalized_) return;
        ensureAcc(upTo);
        for (int64_t i = finalized_; i < upTo; ++i) {
            const size_t at = (size_t)(i - accBase_);
            const float n = norm_[at];
            outBuf_.push_back(n > 1e-6f ? accL_[at] / n : 0.f);
            outBuf_.push_back(n > 1e-6f ? accR_[at] / n : 0.f);
        }
        finalized_ = upTo;
        // The accumulators before finalized_ are spent.
        const int64_t drop = finalized_ - accBase_;
        if (drop > 8192) {
            accL_.erase(accL_.begin(), accL_.begin() + (size_t)drop);
            accR_.erase(accR_.begin(), accR_.begin() + (size_t)drop);
            norm_.erase(norm_.begin(), norm_.begin() + (size_t)drop);
            accBase_ = finalized_;
        }
    }

    std::unique_ptr<FrameSource> src_;
    std::vector<float> profile_;
    float strength_;
    bool active_ = false;

    std::vector<float> win_;
    std::vector<float> gains_;
    bool started_ = false;
    double attack_ = 0.0, release_ = 0.0;

    std::vector<float> inBuf_;        // stereo input, inBuf_[0] is absolute frame inBase_
    int64_t inBase_ = 0;
    int64_t inTotal_ = -1;            // total input frames once the stream ended
    bool eof_ = false;

    int64_t nextFrame_ = 0;           // next STFT frame start (absolute)
    std::vector<float> accL_, accR_, norm_;   // overlap-add, index 0 = absolute frame accBase_
    int64_t accBase_ = 0;
    int64_t finalized_ = 0;

    std::vector<float> outBuf_;       // finalized stereo output queue
    int outHead_ = 0;
    bool flushed_ = false;

    std::vector<float> reL_, imL_, reR_, imR_;   // FFT scratch
};

}  // namespace am
