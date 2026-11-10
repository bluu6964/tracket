#include "audio_preview.h"

#include <algorithm>
#include <chrono>

namespace am {
namespace {
constexpr int64_t kLookAheadFrames = 2LL * kAudioRate;   // clips starting within 2 s get their decoder opened early
constexpr size_t kStep = 1024;                           // same chunk as AudioMixer::render
size_t pow2Ceil(size_t n) {
    size_t p = 1;
    while (p < n) p <<= 1;
    return p;
}
}  // namespace

PcmRing::PcmRing(size_t capacityFrames) : cap_(pow2Ceil(std::max<size_t>(capacityFrames, 1024))) {
    buf_.assign(cap_ * 2, 0);
}

size_t PcmRing::available() const {
    return (size_t)(head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire));
}

size_t PcmRing::space() const { return cap_ - available(); }

size_t PcmRing::write(const int16_t* pcm, size_t frames) {
    const uint64_t head = head_.load(std::memory_order_relaxed);
    const uint64_t tail = tail_.load(std::memory_order_acquire);
    const size_t n = std::min(frames, cap_ - (size_t)(head - tail));
    for (size_t i = 0; i < n; ++i) {
        const size_t at = (size_t)((head + i) & (cap_ - 1)) * 2;
        buf_[at] = pcm[i * 2];
        buf_[at + 1] = pcm[i * 2 + 1];
    }
    head_.store(head + n, std::memory_order_release);
    return n;
}

size_t PcmRing::read(int16_t* dst, size_t frames) {
    const uint64_t tail = tail_.load(std::memory_order_relaxed);
    const uint64_t head = head_.load(std::memory_order_acquire);
    const size_t n = std::min(frames, (size_t)(head - tail));
    for (size_t i = 0; i < n; ++i) {
        const size_t at = (size_t)((tail + i) & (cap_ - 1)) * 2;
        dst[i * 2] = buf_[at];
        dst[i * 2 + 1] = buf_[at + 1];
    }
    tail_.store(tail + n, std::memory_order_release);
    return n;
}

size_t PcmRing::skip(size_t frames) {
    const uint64_t tail = tail_.load(std::memory_order_relaxed);
    const uint64_t head = head_.load(std::memory_order_acquire);
    const size_t n = std::min(frames, (size_t)(head - tail));
    tail_.store(tail + n, std::memory_order_release);
    return n;
}

PreviewFeeder::PreviewFeeder(SourceFactory factory, const std::vector<AudioClipSpec>& clips, int64_t startFrame,
                             size_t ringFrames)
    : ring_(ringFrames), startFrame_(std::max<int64_t>(0, startFrame)) {
    int64_t end = 0;
    for (const auto& c : clips) end = std::max(end, c.startFrame + c.durFrames);
    mixer_.reset(new AudioMixer(std::move(factory), end));
    for (const auto& c : clips) {
        if (c.durFrames < 1 || c.startFrame + c.durFrames <= startFrame_) continue;   // already over at the start
        mixer_->addClip(c);
    }
    mixer_->seek(startFrame_);
    if (mixer_->empty() || mixer_->finished()) finished_ = true;
}

PreviewFeeder::~PreviewFeeder() { stop(); }

void PreviewFeeder::start() {
    if (thread_.joinable() || finished_.load()) return;
    thread_ = std::thread([this] { run(); });
}

void PreviewFeeder::stop() {
    stop_ = true;
    if (thread_.joinable()) thread_.join();
}

void PreviewFeeder::run() {
    while (!stop_.load()) {
        if (mixer_->finished()) break;
        const size_t room = ring_.space();
        if (room < kStep) {
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
            continue;
        }
        // Decoders of clips that start soon are opened before the cursor gets there.
        mixer_->prefetch(mixer_->cursor() + kLookAheadFrames);
        const int64_t upTo = mixer_->cursor() + (int64_t)(room / kStep) * (int64_t)kStep;
        const bool ok = mixer_->render(upTo, [this](const int16_t* pcm, int frames, int64_t) {
            ring_.write(pcm, (size_t)frames);   // room was checked, it all fits
            return !stop_.load();
        });
        if (!ok) break;
    }
    finished_ = mixer_->finished();
}

}  // namespace am
