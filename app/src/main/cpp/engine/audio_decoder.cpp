#include "audio_decoder.h"
#include "prof.h"

#include <android/log.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <cstring>
#include <unistd.h>

#define TAG "AmAudioDec"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace am {

std::unique_ptr<AudioFileSource> AudioFileSource::open(int fd, int64_t length, int64_t startUs) {
    std::unique_ptr<AudioFileSource> s(new AudioFileSource());
    if (!s->init(fd, length, startUs)) return nullptr;
    return s;
}

AudioFileSource::~AudioFileSource() {
    if (codec_) { AMediaCodec_stop(codec_); AMediaCodec_delete(codec_); }
    if (ex_) AMediaExtractor_delete(ex_);
}

bool AudioFileSource::init(int fd, int64_t length, int64_t startUs) {
    startUs_ = std::max<int64_t>(0, startUs);
    if (length <= 0) length = lseek(fd, 0, SEEK_END);
    ex_ = AMediaExtractor_new();
    if (!ex_ || AMediaExtractor_setDataSourceFd(ex_, fd, 0, length) != AMEDIA_OK) return false;
    int track = -1;
    const char* mime = nullptr;
    AMediaFormat* fmt = nullptr;
    const size_t n = AMediaExtractor_getTrackCount(ex_);
    for (size_t i = 0; i < n && track < 0; ++i) {
        AMediaFormat* f = AMediaExtractor_getTrackFormat(ex_, i);
        const char* m = nullptr;
        if (f && AMediaFormat_getString(f, AMEDIAFORMAT_KEY_MIME, &m) && m && strncmp(m, "audio/", 6) == 0) {
            track = (int)i; mime = m; fmt = f;
        } else if (f) {
            AMediaFormat_delete(f);
        }
    }
    if (track < 0) return false;   // e.g. a video without sound
    int32_t v = 0;
    if (AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &v) && v > 0) srcRate_ = v;
    if (AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &v) && v > 0) srcCh_ = v;
    AMediaExtractor_selectTrack(ex_, (size_t)track);
    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_ || AMediaCodec_configure(codec_, fmt, nullptr, nullptr, 0) != AMEDIA_OK ||
        AMediaCodec_start(codec_) != AMEDIA_OK) {
        AMediaFormat_delete(fmt);
        LOGE("audio decoder start failed");
        return false;
    }
    AMediaFormat_delete(fmt);
    if (startUs_ > 0) AMediaExtractor_seekTo(ex_, startUs_, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
    return true;
}

void AudioFileSource::appendPcm(const uint8_t* data, int bytes) {
    const int ch = std::max(1, srcCh_);
    const int bps = pcmEnc_ == 4 ? 4 : 2;
    const int frames = bytes / (bps * ch);
    fifo_.reserve(fifo_.size() + (size_t)frames * 2);
    for (int i = 0; i < frames; ++i) {
        float s[2];
        for (int c = 0; c < 2; ++c) {
            const int cc = std::min(c, ch - 1);   // mono -> both sides; >2 channels: first two
            const uint8_t* p = data + ((size_t)i * ch + cc) * bps;
            if (bps == 4) { float f; memcpy(&f, p, 4); s[c] = f; }
            else { int16_t q; memcpy(&q, p, 2); s[c] = (float)q / 32768.f; }
        }
        fifo_.push_back(s[0]);
        fifo_.push_back(s[1]);
    }
}

bool AudioFileSource::decodeMore() {
    if (outputEos_) return false;
    ProfScope prof(PROF_AUDIO_DECODE);
    const size_t fifoAtStart = fifo_.size();
    for (int guard = 0; guard < 5000; ++guard) {
        // Keep the codec busy: queue every free input buffer (not just one) before waiting for output.
        for (int k = 0; k < 8 && !inputEos_; ++k) {
            const ssize_t in = AMediaCodec_dequeueInputBuffer(codec_, 0);
            if (in < 0) break;
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, (size_t)in, &cap);
            const ssize_t sz = buf ? AMediaExtractor_readSampleData(ex_, buf, cap) : -1;
            if (sz < 0) {
                AMediaCodec_queueInputBuffer(codec_, (size_t)in, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                inputEos_ = true;
            } else {
                AMediaCodec_queueInputBuffer(codec_, (size_t)in, 0, (size_t)sz, (uint64_t)AMediaExtractor_getSampleTime(ex_), 0);
                AMediaExtractor_advance(ex_);
            }
        }
        // Take everything that is ready; wait (briefly) only while nothing has come out yet.
        for (;;) {
            AMediaCodecBufferInfo info{};
            const bool have = fifo_.size() > fifoAtStart;
            const ssize_t out = AMediaCodec_dequeueOutputBuffer(codec_, &info, have ? 0 : 2000);
            if (out >= 0) {
                size_t cap = 0;
                uint8_t* data = AMediaCodec_getOutputBuffer(codec_, (size_t)out, &cap);
                const size_t before = fifo_.size();
                if (data && info.size > 0) {
                    appendPcm(data + info.offset, info.size);
                    // The seek lands on or before the start: drop what comes before it.
                    if (info.presentationTimeUs < startUs_) {
                        const int64_t dropFrames = (startUs_ - info.presentationTimeUs) * srcRate_ / 1000000LL;
                        const size_t have2 = (fifo_.size() - before) / 2;
                        const size_t drop = (size_t)std::min<int64_t>(dropFrames, (int64_t)have2);
                        fifo_.erase(fifo_.begin() + before, fifo_.begin() + before + drop * 2);
                    }
                }
                if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) outputEos_ = true;
                AMediaCodec_releaseOutputBuffer(codec_, (size_t)out, false);
                if (outputEos_) break;
            } else if (out == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat* f = AMediaCodec_getOutputFormat(codec_);
                if (f) {
                    int32_t v = 0;
                    if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, &v) && v > 0) srcRate_ = v;
                    if (AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &v) && v > 0) srcCh_ = v;
                    pcmEnc_ = AMediaFormat_getInt32(f, "pcm-encoding", &v) ? v : 2;
                    AMediaFormat_delete(f);
                }
            } else if (out == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            } else {
                break;  // nothing more right now
            }
        }
        if (fifo_.size() > fifoAtStart) return true;
        if (outputEos_) return false;
    }
    LOGE("audio decoder stalled");
    outputEos_ = true;
    return false;
}

int AudioFileSource::read(float* dst, int frames) {
    int done = 0;
    while (done < frames) {
        // Linear resampling to 48 kHz: needs frame floor(pos)+1 to exist.
        const size_t need = (size_t)pos_ + 2;
        if (fifo_.size() / 2 < need) {
            if (!decodeMore()) {
                if (fifo_.size() / 2 < need) break;   // end of stream
            }
            continue;
        }
        const double stepNow = (double)srcRate_ / (double)kAudioRate;   // srcRate_ may change in decodeMore
        const size_t i0 = (size_t)pos_;
        const float fr = (float)(pos_ - (double)i0);
        for (int c = 0; c < 2; ++c)
            dst[(size_t)done * 2 + c] = fifo_[i0 * 2 + c] * (1.f - fr) + fifo_[(i0 + 1) * 2 + c] * fr;
        pos_ += stepNow;
        ++done;
        if (pos_ > 8192.0) {            // drop what was consumed
            const size_t drop = (size_t)pos_;
            fifo_.erase(fifo_.begin(), fifo_.begin() + drop * 2);
            pos_ -= (double)drop;
        }
    }
    return done;
}

}  // namespace am
