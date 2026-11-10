#include "decoder_pool.h"

#include <android/log.h>
#include <android/native_window.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#define TAG "AmDecoderPool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace am {
namespace {

constexpr int64_t kNoPts = INT64_MIN;
constexpr int64_t kFastForwardUs = 750000;  // scrub forward by less than this = just decode on
constexpr int64_t kDefaultFrameUs = 33333;
constexpr int kOutputTimeoutUs = 10000;
constexpr int kMaxIdleLoops = 300;          // ~3 s without any output = decoder is stuck
constexpr size_t kMinInputBytes = 1u << 20;
constexpr size_t kMaxInputBytes = 8u << 20;
constexpr int kModeNone = 0, kModeHw = 1, kModeSw = 2;

std::vector<const char*> softwareDecoders(const std::string& mime) {
    if (mime == "video/avc") return {"c2.android.avc.decoder", "OMX.google.h264.decoder"};
    if (mime == "video/hevc") return {"c2.android.hevc.decoder", "OMX.google.hevc.decoder"};
    if (mime == "video/x-vnd.on2.vp9") return {"c2.android.vp9.decoder", "OMX.google.vp9.decoder"};
    if (mime == "video/x-vnd.on2.vp8") return {"c2.android.vp8.decoder", "OMX.google.vp8.decoder"};
    if (mime == "video/av01") return {"c2.android.av1.decoder"};
    if (mime == "video/mp4v-es") return {"c2.android.mpeg4.decoder", "OMX.google.mpeg4.decoder"};
    if (mime == "video/3gpp") return {"c2.android.h263.decoder", "OMX.google.h263.decoder"};
    return {};
}

}  // namespace

// One opened video file. The extractor lives as long as the clip (cheap); the codec is the scarce
// resource and may be dropped by the pool at any time while nobody is using the clip.
class Clip {
public:
    Clip(DecoderPool* pool, int id, int fd, int64_t off, int64_t len, ANativeWindow* window)
        : id(id), pool_(pool), fd_(fd), off_(off), len_(len), window_(window) {}

    ~Clip() {
        dropCodec();
        if (ex_) AMediaExtractor_delete(ex_);
        if (window_) ANativeWindow_release(window_);
        if (fd_ >= 0) ::close(fd_);
    }

    bool init(ClipInfo* info) {
        ex_ = AMediaExtractor_new();
        if (!ex_) return false;
        if (len_ <= 0) {
            off_t end = lseek(fd_, 0, SEEK_END);
            lseek(fd_, 0, SEEK_SET);
            len_ = end > off_ ? end - off_ : 0;
        }
        if (AMediaExtractor_setDataSourceFd(ex_, fd_, off_, len_) != AMEDIA_OK) {
            LOGE("clip %d: setDataSourceFd failed", id);
            return false;
        }
        const size_t n = AMediaExtractor_getTrackCount(ex_);
        for (size_t i = 0; i < n; ++i) {
            AMediaFormat* f = AMediaExtractor_getTrackFormat(ex_, i);
            if (!f) continue;
            const char* mime = nullptr;
            if (AMediaFormat_getString(f, "mime", &mime) && mime && strncmp(mime, "video/", 6) == 0) {
                mime_ = mime;
                track_ = (int)i;
                int32_t v = 0;
                if (AMediaFormat_getInt32(f, "width", &v)) width_ = v;
                if (AMediaFormat_getInt32(f, "height", &v)) height_ = v;
                if (AMediaFormat_getInt32(f, "rotation-degrees", &v)) rotation_ = v;
                int64_t d = 0;
                if (AMediaFormat_getInt64(f, "durationUs", &d)) durationUs_ = d;
                float fr = 0.f;
                if (AMediaFormat_getFloat(f, "frame-rate", &fr)) fps_ = fr;
                else if (AMediaFormat_getInt32(f, "frame-rate", &v)) fps_ = (float)v;
                AMediaFormat_delete(f);
                break;
            }
            AMediaFormat_delete(f);
        }
        if (track_ < 0 || width_ <= 0 || height_ <= 0) {
            LOGE("clip %d: no usable video track", id);
            return false;
        }
        if (AMediaExtractor_selectTrack(ex_, track_) != AMEDIA_OK) return false;
        if (fps_ > 1.f && fps_ < 480.f) frameUs_ = (int64_t)(1000000.0 / fps_);
        if (info) {
            info->width = width_;
            info->height = height_;
            info->rotation = rotation_;
            info->durationUs = durationUs_;
            info->fps = fps_;
        }
        return true;
    }

    Status seek(int64_t targetUs, int64_t* outPts) {
        if (durationUs_ > 0) targetUs = std::min(targetUs, std::max<int64_t>(0, durationUs_ - frameUs_));
        targetUs = std::max<int64_t>(0, targetUs);
        const int64_t half = frameUs_ / 2;

        // The requested frame is already on the surface (also true after the codec was evicted).
        if (haveFrame_ && std::llabs(targetUs - positionUs_) <= half) {
            *outPts = positionUs_;
            return kOkSame;
        }
        for (int attempt = 0; attempt < 3; ++attempt) {
            Status o = ensureOpen();
            if (o != kOk) return o;
            const bool forward = haveFrame_ && streamLive_ && !eosReached_ && targetUs > positionUs_ &&
                                 targetUs - positionUs_ < kFastForwardUs;
            if (!forward) resyncTo(targetUs);
            Status st = decodeOne(targetUs - half + 1, outPts);
            if (st != kError) return st;
            onFault(attempt);
        }
        return kError;
    }

    Status next(int64_t* outPts) {
        if (eosReached_) return kEndOfStream;
        for (int attempt = 0; attempt < 3; ++attempt) {
            Status o = ensureOpen();
            if (o != kOk) return o;
            int64_t renderMin = kNoPts;
            if (needResync_) {
                // codec was dropped (evicted / failed): continue right after the last shown frame
                resyncTo(haveFrame_ ? positionUs_ : 0);
                if (haveFrame_) renderMin = positionUs_ + 1;
            }
            Status st = decodeOne(renderMin, outPts);
            if (st != kError) return st;
            onFault(attempt);
        }
        return kError;
    }

    // Caller holds mu (or is the destructor). Safe to call when there is no codec.
    void dropCodec() {
        if (!codec_) return;
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        const int m = mode.exchange(kModeNone);
        if (m == kModeHw) pool_->releaseSlot(true);
        else if (m == kModeSw) pool_->releaseSlot(false);
        inputEos_ = false;
        streamLive_ = false;
        needResync_ = true;
    }

    // Use the software decoder only (called right after init, before the first decode).
    void setPreferSoftware() { forceSw_ = true; }

    const int id;
    std::mutex mu;
    bool closed = false;
    std::atomic<uint64_t> lastUse{0};
    std::atomic<int> mode{kModeNone};

private:
    Status ensureOpen() {
        if (codec_) return kOk;
        if (!forceSw_) {
            if (!pool_->reserveSlot(true, this)) return kBusy;
            if (createCodec(true)) {
                mode = kModeHw;
                return kOk;
            }
            pool_->releaseSlot(true);
            LOGW("clip %d: hardware decoder failed, using software", id);
            pool_->noteFallback();
            forceSw_ = true;
        }
        if (!pool_->reserveSlot(false, this)) return kBusy;
        if (createCodec(false)) {
            mode = kModeSw;
            return kOk;
        }
        pool_->releaseSlot(false);
        LOGE("clip %d: no decoder could be created for %s", id, mime_.c_str());
        return kError;
    }

    bool createCodec(bool hw) {
        // First with "export, not live" hints (priority 1 + highest operating rate: the decoder may run as fast as it
        // can, same picture); if a chip refuses them, once more without.
        for (int attempt = 0; attempt < 2; ++attempt) {
            AMediaFormat* fmt = AMediaExtractor_getTrackFormat(ex_, track_);
            if (!fmt) return false;
            // Small default input buffers are a known cause of decoder failures on some chips.
            int32_t cur = 0;
            AMediaFormat_getInt32(fmt, "max-input-size", &cur);
            size_t want = std::clamp<size_t>((size_t)width_ * (size_t)height_, kMinInputBytes, kMaxInputBytes);
            want = std::min(want * (size_t)inputBoost_, (size_t)INT32_MAX / 2);
            if ((int32_t)want > cur) AMediaFormat_setInt32(fmt, "max-input-size", (int32_t)want);
            if (attempt == 0) {
                AMediaFormat_setInt32(fmt, "priority", 1);
                AMediaFormat_setInt32(fmt, "operating-rate", 32767);
            }

            AMediaCodec* c = nullptr;
            if (hw) {
                c = AMediaCodec_createDecoderByType(mime_.c_str());
            } else {
                for (const char* name : softwareDecoders(mime_)) {
                    c = AMediaCodec_createCodecByName(name);
                    if (c) break;
                }
            }
            if (!c) {
                AMediaFormat_delete(fmt);
                return false;
            }
            const media_status_t cs = AMediaCodec_configure(c, fmt, window_, nullptr, 0);
            AMediaFormat_delete(fmt);
            if (cs != AMEDIA_OK || AMediaCodec_start(c) != AMEDIA_OK) {
                LOGW("clip %d: configure/start failed (%d) hw=%d%s", id, (int)cs, (int)hw,
                     attempt == 0 ? ", retrying without speed hints" : "");
                AMediaCodec_delete(c);
                if (attempt == 0) continue;
                return false;
            }
            codec_ = c;
            inputEos_ = false;
            streamLive_ = false;
            return true;
        }
        return false;
    }


    void resyncTo(int64_t targetUs) {
        AMediaExtractor_seekTo(ex_, targetUs, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
        if (codec_) AMediaCodec_flush(codec_);
        inputEos_ = false;
        eosReached_ = false;
        streamLive_ = false;
        needResync_ = false;
    }

    void onFault(int attempt) {
        pool_->noteError();
        LOGW("clip %d: decode fault (attempt %d, %s)", id, attempt, mode.load() == kModeHw ? "hw" : "sw");
        dropCodec();
        if (attempt >= 1) {
            if (!forceSw_) pool_->noteFallback();
            forceSw_ = true;
            inputBoost_ = std::min(inputBoost_ * 2, 4);
        }
    }

    // Queue as many compressed samples as the codec accepts right now.
    bool feedInput() {
        int guard = 0;
        while (!inputEos_ && guard++ < 16) {
            const ssize_t idx = AMediaCodec_dequeueInputBuffer(codec_, 0);
            if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) return true;
            if (idx < 0) return false;
            size_t cap = 0;
            uint8_t* buf = AMediaCodec_getInputBuffer(codec_, (size_t)idx, &cap);
            if (!buf) return false;
            const ssize_t n = AMediaExtractor_readSampleData(ex_, buf, cap);
            if (n < 0) {
                if (AMediaCodec_queueInputBuffer(codec_, (size_t)idx, 0, 0, 0,
                                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != AMEDIA_OK)
                    return false;
                inputEos_ = true;
                return true;
            }
            if ((size_t)n > cap) return false;  // sample does not fit: recover with bigger buffers
            const int64_t pts = AMediaExtractor_getSampleTime(ex_);
            if (AMediaCodec_queueInputBuffer(codec_, (size_t)idx, 0, (size_t)n,
                                             (uint64_t)std::max<int64_t>(pts, 0), 0) != AMEDIA_OK)
                return false;
            AMediaExtractor_advance(ex_);
        }
        return true;
    }

    // Decode forward until one frame with pts >= renderMin is shown. Earlier frames are dropped
    // without rendering. Returns kError on any codec problem (caller recovers).
    Status decodeOne(int64_t renderMin, int64_t* outPts) {
        int idle = 0;
        for (;;) {
            if (!feedInput()) return kError;
            AMediaCodecBufferInfo info;
            const ssize_t idx = AMediaCodec_dequeueOutputBuffer(codec_, &info, kOutputTimeoutUs);
            if (idx >= 0) {
                idle = 0;
                const bool eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
                if (eos && info.size == 0) {
                    AMediaCodec_releaseOutputBuffer(codec_, (size_t)idx, false);
                    eosReached_ = true;
                    return kEndOfStream;
                }
                const int64_t pts = info.presentationTimeUs;
                const bool render = pts >= renderMin;
                AMediaCodec_releaseOutputBuffer(codec_, (size_t)idx, render && window_ != nullptr);
                streamLive_ = true;
                if (eos) eosReached_ = true;
                if (render) {
                    positionUs_ = pts;
                    haveFrame_ = true;
                    *outPts = pts;
                    return kOk;
                }
                if (eos) return kEndOfStream;
            } else if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                if (++idle > kMaxIdleLoops) {
                    LOGW("clip %d: decoder produced no output for 3 s", id);
                    return kError;
                }
            } else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED ||
                       idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            } else {
                return kError;
            }
        }
    }

    DecoderPool* pool_;
    int fd_;
    int64_t off_, len_;
    ANativeWindow* window_;
    AMediaExtractor* ex_ = nullptr;
    AMediaCodec* codec_ = nullptr;
    std::string mime_;
    int track_ = -1, width_ = 0, height_ = 0, rotation_ = 0;
    int64_t durationUs_ = 0;
    float fps_ = 0.f;
    int64_t frameUs_ = kDefaultFrameUs;

    bool inputEos_ = false;
    bool eosReached_ = false;
    bool streamLive_ = false;   // codec holds a continuous stream that ends near positionUs_
    bool needResync_ = false;   // codec was dropped: the extractor must be re-positioned
    bool haveFrame_ = false;
    bool forceSw_ = false;
    int inputBoost_ = 1;
    int64_t positionUs_ = 0;    // pts of the frame currently on the surface
};

// ---------------------------------------------------------------------------------------------

DecoderPool::DecoderPool(int maxHw, int maxSw)
    : maxHw_(std::max(1, maxHw)), maxSw_(std::max(1, maxSw)) {}

DecoderPool::~DecoderPool() {
    std::map<int, std::shared_ptr<Clip>> local;
    {
        std::lock_guard<std::mutex> lk(mu_);
        local.swap(clips_);
    }
    for (auto& kv : local) {
        std::lock_guard<std::mutex> cl(kv.second->mu);
        kv.second->closed = true;
        kv.second->dropCodec();
    }
}

std::shared_ptr<Clip> DecoderPool::find(int id) {
    std::lock_guard<std::mutex> lk(mu_);
    auto it = clips_.find(id);
    return it == clips_.end() ? nullptr : it->second;
}

int DecoderPool::open(int fd, int64_t offset, int64_t length, ANativeWindow* window, ClipInfo* info,
                      bool preferSoftware) {
    const int dupFd = dup(fd);
    if (dupFd < 0) {
        if (window) ANativeWindow_release(window);
        return kError;
    }
    int id;
    {
        std::lock_guard<std::mutex> lk(mu_);
        id = nextId_++;
    }
    auto clip = std::make_shared<Clip>(this, id, dupFd, offset, length, window);  // owns fd + window
    if (!clip->init(info)) return kError;
    if (preferSoftware) clip->setPreferSoftware();
    clip->lastUse = tick();
    std::lock_guard<std::mutex> lk(mu_);
    clips_[id] = clip;
    opened_++;
    return id;
}

void DecoderPool::close(int clipId) {
    std::shared_ptr<Clip> c;
    {
        std::lock_guard<std::mutex> lk(mu_);
        auto it = clips_.find(clipId);
        if (it == clips_.end()) return;
        c = it->second;
        clips_.erase(it);
    }
    std::lock_guard<std::mutex> cl(c->mu);
    c->closed = true;
    c->dropCodec();
    cv_.notify_all();
}

int DecoderPool::seek(int clipId, int64_t targetUs, int64_t* outPts) {
    auto c = find(clipId);
    if (!c) return kError;
    std::lock_guard<std::mutex> cl(c->mu);
    if (c->closed) return kError;
    c->lastUse = tick();
    return c->seek(targetUs, outPts);
}

int DecoderPool::next(int clipId, int64_t* outPts) {
    auto c = find(clipId);
    if (!c) return kError;
    std::lock_guard<std::mutex> cl(c->mu);
    if (c->closed) return kError;
    c->lastUse = tick();
    return c->next(outPts);
}

void DecoderPool::setMaxHw(int n) {
    maxHw_ = std::max(1, n);
    cv_.notify_all();
}

PoolStats DecoderPool::stats() const {
    PoolStats s;
    s.activeHw = activeHw_;
    s.activeSw = activeSw_;
    s.maxHw = maxHw_;
    s.maxSw = maxSw_;
    s.opened = opened_;
    s.evictions = evictions_;
    s.fallbacks = fallbacks_;
    s.errors = errors_;
    return s;
}

// Lock order: clip mutex -> pool mutex. Eviction goes the other way round (pool -> other clip) but
// only with try_lock, so it can never deadlock; a clip that is busy is simply skipped.
bool DecoderPool::reserveSlot(bool hw, const Clip* requester) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
    std::unique_lock<std::mutex> lk(mu_);
    std::atomic<int>& active = hw ? activeHw_ : activeSw_;
    for (;;) {
        const int limit = hw ? maxHw_.load() : maxSw_.load();
        if (active.load() < limit) {
            active++;
            return true;
        }
        if (evictOne(hw, requester)) continue;
        if (std::chrono::steady_clock::now() >= deadline) return false;
        cv_.wait_for(lk, std::chrono::milliseconds(5));
    }
}

void DecoderPool::releaseSlot(bool hw) {
    (hw ? activeHw_ : activeSw_)--;
    cv_.notify_all();
}

bool DecoderPool::evictOne(bool hw, const Clip* requester) {
    const int wanted = hw ? kModeHw : kModeSw;
    std::vector<std::shared_ptr<Clip>> cands;
    for (auto& kv : clips_)
        if (kv.second.get() != requester && kv.second->mode.load() == wanted) cands.push_back(kv.second);
    std::sort(cands.begin(), cands.end(),
              [](const std::shared_ptr<Clip>& a, const std::shared_ptr<Clip>& b) {
                  return a->lastUse.load() < b->lastUse.load();
              });
    for (auto& c : cands) {
        std::unique_lock<std::mutex> cl(c->mu, std::try_to_lock);
        if (!cl.owns_lock()) continue;       // being used right now
        if (c->mode.load() != wanted) continue;
        c->dropCodec();                      // frees the slot (releaseSlot: no pool lock needed)
        evictions_++;
        LOGI("evicted clip %d (%s decoder)", c->id, hw ? "hw" : "sw");
        return true;
    }
    return false;
}

}  // namespace am
