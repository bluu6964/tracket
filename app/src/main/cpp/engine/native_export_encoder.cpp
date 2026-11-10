#include "native_export_encoder.h"
#include "renderer.h"
#include "audio_decoder.h"
#include "prof.h"

#include <media/NdkMediaFormat.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>
#include <algorithm>
#include <cerrno>
#include <cstring>

#define TAG "AmNativeExport"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace am {
namespace {
constexpr int64_t kDequeueTimeoutUs = 10000;
constexpr int kMaxDrainAttempts = 1000;

// NDK MediaMuxer was introduced in API 26. Resolve dynamically so libam_engine can still load
// on minSdk 24 devices; NativeExportEncoder.isSupported gates use at runtime.
struct MuxerApi {
    void* lib = nullptr;
    AMediaMuxer* (*create)(int, OutputFormat) = nullptr;  // NDK: AMediaMuxer_new(int fd, format), NOT a path
    media_status_t (*createInputSurface)(AMediaCodec*, ANativeWindow**) = nullptr;
    media_status_t (*signalEndOfInputStream)(AMediaCodec*) = nullptr;
    ssize_t (*addTrack)(AMediaMuxer*, const AMediaFormat*) = nullptr;
    media_status_t (*start)(AMediaMuxer*) = nullptr;
    media_status_t (*writeSample)(AMediaMuxer*, size_t, const uint8_t*, const AMediaCodecBufferInfo*) = nullptr;
    media_status_t (*stop)(AMediaMuxer*) = nullptr;
    void (*destroy)(AMediaMuxer*) = nullptr;
    bool load() {
        lib = dlopen("libmediandk.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return false;
        create = reinterpret_cast<decltype(create)>(dlsym(lib, "AMediaMuxer_new"));
        createInputSurface = reinterpret_cast<decltype(createInputSurface)>(dlsym(lib, "AMediaCodec_createInputSurface"));
        signalEndOfInputStream = reinterpret_cast<decltype(signalEndOfInputStream)>(dlsym(lib, "AMediaCodec_signalEndOfInputStream"));
        addTrack = reinterpret_cast<decltype(addTrack)>(dlsym(lib, "AMediaMuxer_addTrack"));
        start = reinterpret_cast<decltype(start)>(dlsym(lib, "AMediaMuxer_start"));
        writeSample = reinterpret_cast<decltype(writeSample)>(dlsym(lib, "AMediaMuxer_writeSampleData"));
        stop = reinterpret_cast<decltype(stop)>(dlsym(lib, "AMediaMuxer_stop"));
        destroy = reinterpret_cast<decltype(destroy)>(dlsym(lib, "AMediaMuxer_delete"));
        return create && createInputSurface && signalEndOfInputStream && addTrack && start && writeSample && stop && destroy;
    }
    ~MuxerApi() { if (lib) dlclose(lib); }
};
MuxerApi& muxerApi() { static MuxerApi api; static const bool loaded = api.load(); (void)loaded; return api; }
}

NativeExportEncoder::~NativeExportEncoder() {
    if (started_) cancel();
    else cleanup(finished_);
}

bool NativeExportEncoder::start(Renderer* renderer, const std::string& path,
                                int width, int height, int fps, int bitrate) {
    if (started_) { error_ = "Encoder already started"; return false; }
    error_.clear();
    auto& mux = muxerApi();
    if (!mux.create || !mux.createInputSurface || !mux.signalEndOfInputStream ||
        !mux.addTrack || !mux.start || !mux.writeSample || !mux.stop || !mux.destroy) {
        error_ = "NDK encoder-surface/MP4 muxer APIs unavailable (Android API 26+)";
        return false;
    }
    if (!renderer || path.empty() || width < 2 || height < 2 || fps < 1 || bitrate < 64000) {
        error_ = "Invalid native export parameters"; return false;
    }
    // Most hardware encoders require even dimensions for 4:2:0 output.
    width &= ~1; height &= ~1;
    renderer_ = renderer;
    path_ = path;
    // First try with the "export, not live" hints: priority 1 (best effort, not real time) and the highest operating
    // rate let the hardware encoder run as fast as it can. Same bitrate / profile, so the picture quality is the same.
    // If a chip refuses them, configure again without.
    media_status_t status = AMEDIA_ERROR_UNKNOWN;
    for (int attempt = 0; attempt < 2; ++attempt) {
        if (video_.codec) { AMediaCodec_delete(video_.codec); video_.codec = nullptr; }
        video_.codec = AMediaCodec_createEncoderByType("video/avc");
        if (!video_.codec) { error_ = "No H.264 encoder available"; cleanup(false); return false; }
        AMediaFormat* format = AMediaFormat_new();
        if (!format) { error_ = "Could not allocate encoder format"; cleanup(false); return false; }
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, fps);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, bitrate);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 2);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 0x7F000789 /* COLOR_FormatSurface */);
        if (attempt == 0) {
            AMediaFormat_setInt32(format, "priority", 1);
            AMediaFormat_setInt32(format, "operating-rate", 32767);
        }
        status = AMediaCodec_configure(video_.codec, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
        AMediaFormat_delete(format);
        if (status == AMEDIA_OK) break;
        __android_log_print(ANDROID_LOG_ERROR, TAG, "encoder configure failed (%d)%s", (int)status,
                            attempt == 0 ? ", retrying without speed hints" : "");
    }
    if (status != AMEDIA_OK) { error_ = "H.264 encoder configure failed: " + std::to_string(status); cleanup(false); return false; }

    status = muxerApi().createInputSurface(video_.codec, &inputWindow_);
    if (status != AMEDIA_OK || !inputWindow_) { error_ = "Encoder input surface creation failed"; cleanup(false); return false; }
    status = AMediaCodec_start(video_.codec);
    if (status != AMEDIA_OK) { error_ = "H.264 encoder start failed: " + std::to_string(status); cleanup(false); return false; }

    // The muxer needs a file descriptor opened for reading AND writing; it does not take ownership of it.
    muxerFd_ = open(path_.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0644);
    if (muxerFd_ < 0) {
        error_ = std::string("Could not open output file: ") + std::strerror(errno);
        cleanup(false); return false;
    }
    muxer_ = mux.create(muxerFd_, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    if (!muxer_) { error_ = "MP4 muxer creation failed"; cleanup(false); return false; }
    if (!renderer_->setOutputWindow(inputWindow_)) {
        error_ = "Could not attach OpenGL renderer to encoder surface"; cleanup(false); return false;
    }
    started_ = true;
    return true;
}

// ------------------------------------------------------------------------------------------
// Audio
// ------------------------------------------------------------------------------------------

bool NativeExportEncoder::addAudioClip(int fd, int64_t length, int64_t startUs, int64_t durUs,
                                       int64_t trimStartUs, double speed, float volume,
                                       int64_t fadeInUs, int64_t fadeOutUs) {
    if (hasAudio_) { error_ = "Add audio clips before startAudio()"; return false; }
    const int copy = dup(fd);
    if (copy < 0) { error_ = "Could not duplicate audio file descriptor"; return false; }
    auto frames = [](int64_t us) { return us * kAudioRate / 1000000LL; };
    AudioClipSpec spec;
    spec.source = (int)audioFiles_.size();
    spec.startFrame = frames(std::max<int64_t>(0, startUs));
    spec.fullDurFrames = frames(std::max<int64_t>(0, durUs));
    spec.durFrames = spec.fullDurFrames;            // cut at the project end in startAudio()
    spec.trimStartUs = std::max<int64_t>(0, trimStartUs);
    spec.speed = speed;
    spec.volume = volume;
    spec.fadeInFrames = frames(std::max<int64_t>(0, fadeInUs));
    spec.fadeOutFrames = frames(std::max<int64_t>(0, fadeOutUs));
    audioFiles_.push_back({copy, length});
    pendingClips_.push_back(spec);
    return true;
}

bool NativeExportEncoder::startAudio(int64_t totalDurationUs, int bitrate) {
    if (!started_ || hasAudio_) { error_ = "startAudio() must follow start() once"; return false; }
    if (pendingClips_.empty()) return true;         // nothing to mix: video-only file
    totalAudioFrames_ = totalDurationUs * kAudioRate / 1000000LL;
    mixer_.reset(new AudioMixer([this](const AudioClipSpec& spec) -> std::unique_ptr<FrameSource> {
        const AudioFile& f = audioFiles_[(size_t)spec.source];
        std::unique_ptr<FrameSource> src = AudioFileSource::open(f.fd, f.length, spec.trimStartUs);
        if (src && std::fabs(spec.speed - 1.0) > 0.001)
            src.reset(new TimeStretcher(std::move(src), spec.speed));
        return src;
    }, totalAudioFrames_));
    for (AudioClipSpec spec : pendingClips_) {
        if (spec.startFrame >= totalAudioFrames_) continue;
        spec.durFrames = std::min(spec.fullDurFrames, totalAudioFrames_ - spec.startFrame);
        if (spec.durFrames < 1) continue;
        mixer_->addClip(spec);
    }
    pendingClips_.clear();
    if (mixer_->empty()) { mixer_.reset(); return true; }

    audio_.codec = AMediaCodec_createEncoderByType("audio/mp4a-latm");
    if (!audio_.codec) { error_ = "No AAC encoder available"; return false; }
    AMediaFormat* f = AMediaFormat_new();
    AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, kAudioRate);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 2);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_BIT_RATE, bitrate > 0 ? bitrate : 192000);
    AMediaFormat_setInt32(f, "aac-profile", 2 /* AAC-LC */);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 32768);
    const media_status_t st = AMediaCodec_configure(audio_.codec, f, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(f);
    if (st != AMEDIA_OK || AMediaCodec_start(audio_.codec) != AMEDIA_OK) {
        error_ = "AAC encoder start failed";
        return false;
    }
    hasAudio_ = true;
    return true;
}

bool NativeExportEncoder::pumpAudio(int64_t upToUs) {
    if (!hasAudio_ || !mixer_) return true;
    const int64_t upToFrame = upToUs * kAudioRate / 1000000LL;
    const bool ok = mixer_->render(upToFrame, [this](const int16_t* pcm, int frames, int64_t ptsUs) {
        return encodeAudio(pcm, frames, ptsUs);
    });
    return ok && error_.empty();
}

bool NativeExportEncoder::encodeAudio(const int16_t* pcm, int frames, int64_t ptsUs) {
    ProfScope prof(PROF_AUDIO_ENCODE);
    size_t left = (size_t)frames * 4;                // 2 channels x 16 bit
    size_t done = 0;
    int stalls = 0;
    while (left > 0) {
        const ssize_t idx = AMediaCodec_dequeueInputBuffer(audio_.codec, 10000);
        if (idx < 0) {
            if (!drain(kAudio, false)) return false;
            if (++stalls > 1000) { error_ = "AAC encoder input stalled"; return false; }
            continue;
        }
        size_t cap = 0;
        uint8_t* buf = AMediaCodec_getInputBuffer(audio_.codec, (size_t)idx, &cap);
        if (!buf || cap == 0) { error_ = "AAC encoder gave no input buffer"; return false; }
        const size_t n = std::min(cap, left) & ~(size_t)3;
        memcpy(buf, reinterpret_cast<const uint8_t*>(pcm) + done, n);
        const int64_t chunkPts = ptsUs + (int64_t)(done / 4) * 1000000LL / kAudioRate;
        AMediaCodec_queueInputBuffer(audio_.codec, (size_t)idx, 0, n, (uint64_t)chunkPts, 0);
        done += n;
        left -= n;
    }
    return drain(kAudio, false);
}

// ------------------------------------------------------------------------------------------
// Video frames
// ------------------------------------------------------------------------------------------

bool NativeExportEncoder::renderSolidFrame(float r, float g, float b, float a, int64_t ptsNs) {
    if (!started_ || finished_ || !renderer_) { error_ = "Native export is not running"; return false; }
    renderer_->beginFrame(r, g, b, a);
    return submitFrame(ptsNs);
}

bool NativeExportEncoder::submitFrame(int64_t ptsNs) {
    if (!started_ || finished_ || !renderer_) { error_ = "Native export is not running"; return false; }
    if (ptsNs < 0 || ptsNs <= lastPtsNs_) { error_ = "Frame timestamps must be non-negative and strictly increasing"; return false; }
    if (!renderer_->endFrame(ptsNs)) { error_ = "OpenGL encoder frame submission failed"; return false; }
    lastPtsNs_ = ptsNs;
    return drain(kVideo, false);
}

// ------------------------------------------------------------------------------------------
// Muxing
// ------------------------------------------------------------------------------------------

bool NativeExportEncoder::tryStartMuxer() {
    if (muxerStarted_) return true;
    if (!video_.format || (hasAudio_ && !audio_.format)) return true;   // wait for the other track
    video_.track = (int)muxerApi().addTrack(muxer_, video_.format);
    if (hasAudio_) audio_.track = (int)muxerApi().addTrack(muxer_, audio_.format);
    if (video_.track < 0 || (hasAudio_ && audio_.track < 0) || muxerApi().start(muxer_) != AMEDIA_OK) {
        error_ = "Could not start MP4 muxer";
        return false;
    }
    muxerStarted_ = true;
    for (const Pending& p : pending_) {
        if (!writeSample(static_cast<Kind>(p.kind), p.data.data(), p.info)) return false;
    }
    pending_.clear();
    return true;
}

bool NativeExportEncoder::writeSample(Kind kind, const uint8_t* data, const AMediaCodecBufferInfo& info) {
    // The muxer reads info.offset bytes into `data`; queued copies are stored from offset 0.
    AMediaCodecBufferInfo i = info;
    const media_status_t st = muxerApi().writeSample(muxer_, (size_t)stream(kind).track, data, &i);
    if (st != AMEDIA_OK) { error_ = "MP4 sample write failed: " + std::to_string(st); return false; }
    return true;
}

bool NativeExportEncoder::drain(Kind kind, bool endOfStream) {
    Stream& s = stream(kind);
    if (!s.codec) return false;
    bool sawEos = false;
    for (int attempt = 0; attempt < kMaxDrainAttempts; ++attempt) {
        AMediaCodecBufferInfo info{};
        const ssize_t index = AMediaCodec_dequeueOutputBuffer(s.codec, &info, endOfStream ? 10000 : 0);  // while running: never sit idle, take what is ready
        if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            if (!endOfStream) return true;
            continue;
        }
        if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            if (s.format) { error_ = "Encoder output format changed more than once"; return false; }
            s.format = AMediaCodec_getOutputFormat(s.codec);
            if (!s.format) { error_ = "Encoder returned no output format"; return false; }
            if (!tryStartMuxer()) return false;
            continue;
        }
        if (index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
        if (index < 0) { error_ = "Unexpected encoder dequeue status: " + std::to_string(index); return false; }

        size_t capacity = 0;
        uint8_t* data = AMediaCodec_getOutputBuffer(s.codec, static_cast<size_t>(index), &capacity);
        bool ok = true;
        if (data && info.size > 0 && !(info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG)) {
            if (info.offset < 0 || static_cast<size_t>(info.offset + info.size) > capacity) {
                error_ = "Encoder returned an invalid output buffer range"; ok = false;
            } else if (muxerStarted_) {
                ok = writeSample(kind, data, info);
            } else {
                // The other track has not announced its format yet: keep the sample until it has.
                Pending p;
                p.kind = kind;
                p.info = info;
                p.info.offset = 0;
                p.data.assign(data + info.offset, data + info.offset + info.size);
                pending_.push_back(std::move(p));
            }
        }
        if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) sawEos = true;
        AMediaCodec_releaseOutputBuffer(s.codec, static_cast<size_t>(index), false);
        if (!ok) return false;
        if (sawEos) return true;
    }
    if (endOfStream) error_ = "Timed out while draining encoder EOS";
    return !endOfStream;
}

bool NativeExportEncoder::finish() {
    if (!started_ || finished_) { error_ = "Native export is not running"; return false; }
    bool ok = true;
    if (hasAudio_) {
        ok = pumpAudio(totalAudioFrames_ * 1000000LL / kAudioRate + 1000000LL);   // whatever is left
        if (ok) {
            int stalls = 0;
            ssize_t idx;
            while ((idx = AMediaCodec_dequeueInputBuffer(audio_.codec, 10000)) < 0) {
                if (!drain(kAudio, false) || ++stalls > 1000) { ok = false; break; }
            }
            if (ok) {
                AMediaCodec_queueInputBuffer(audio_.codec, (size_t)idx, 0, 0,
                    (uint64_t)(totalAudioFrames_ * 1000000LL / kAudioRate), AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                ok = drain(kAudio, true);
            }
        }
        if (!ok && error_.empty()) error_ = "Audio encoding failed";
    }
    if (!ok) { cancel(); return false; }
    const media_status_t signalStatus = muxerApi().signalEndOfInputStream(video_.codec);
    if (signalStatus != AMEDIA_OK) { error_ = "Could not signal encoder EOS"; cleanup(false); return false; }
    const bool drained = drain(kVideo, true);
    if (renderer_) renderer_->setOutputWindow(nullptr);
    if (muxerStarted_) {
        const media_status_t stopStatus = muxerApi().stop(muxer_);
        muxerStarted_ = false;
        if (stopStatus != AMEDIA_OK && drained) error_ = "MP4 muxer stop failed";
    } else if (drained && error_.empty()) {
        error_ = "Encoder produced no output";
    }
    finished_ = drained && error_.empty();
    cleanup(finished_);
    return finished_;
}

void NativeExportEncoder::cancel() {
    if (finished_) return;  // A completed export must survive object destruction.
    if (renderer_) renderer_->setOutputWindow(nullptr);
    cleanup(false);
}

void NativeExportEncoder::cleanup(bool keepOutputFile) {
    for (Stream* s : {&video_, &audio_}) {
        if (s->codec) {
            AMediaCodec_stop(s->codec);
            AMediaCodec_delete(s->codec);
            s->codec = nullptr;
        }
        if (s->format) { AMediaFormat_delete(s->format); s->format = nullptr; }
        s->track = -1;
    }
    mixer_.reset();   // releases the audio decoders before their file descriptors are closed
    for (const AudioFile& f : audioFiles_) close(f.fd);
    audioFiles_.clear();
    pendingClips_.clear();
    pending_.clear();
    if (muxer_) {
        if (muxerStarted_) { muxerApi().stop(muxer_); muxerStarted_ = false; }
        muxerApi().destroy(muxer_);
        muxer_ = nullptr;
    }
    if (muxerFd_ >= 0) { close(muxerFd_); muxerFd_ = -1; }
    if (inputWindow_) { ANativeWindow_release(inputWindow_); inputWindow_ = nullptr; }
    if (!keepOutputFile && !path_.empty()) unlink(path_.c_str());
    renderer_ = nullptr;
    muxerStarted_ = false;
    started_ = false;
    hasAudio_ = false;
}

} // namespace am
