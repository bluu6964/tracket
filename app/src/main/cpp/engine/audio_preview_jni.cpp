// JNI bridge for com.example.engine.AudioPreviewNative: the editor's sound, played by the C++ engine.
//
// Sound of the timeline (audio layers + the sound of video layers) is mixed by am::PreviewFeeder (the same mixer as the
// export) into a ring buffer, and an AAudio output stream copies it out. Media3 is not involved.
//
// AAudio exists from Android 8 (API 26) but the app's minSdk is 24, so the library is opened with dlopen and the few
// calls that are needed are declared here (same approach as image_loader.cpp). available() is false on older phones;
// the Kotlin side then keeps using the Media3 players for the sound.
#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "audio_decoder.h"
#include "audio_preview.h"

#define TAG "AmAudioPreview"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {
using namespace am;

// ---- the part of <aaudio/AAudio.h> that is used (values from the NDK header; the ABI is stable) -------------------
struct AAudioStream;
struct AAudioStreamBuilder;
using aaudio_result_t = int32_t;
using DataCb = int32_t (*)(AAudioStream*, void*, void*, int32_t);
using ErrCb = void (*)(AAudioStream*, void*, aaudio_result_t);
constexpr int32_t AA_DIRECTION_OUTPUT = 0;
constexpr int32_t AA_FORMAT_PCM_I16 = 1;
constexpr int32_t AA_SHARING_SHARED = 1;
constexpr int32_t AA_PERF_NONE = 10;
constexpr int32_t AA_PERF_LOW_LATENCY = 12;
constexpr int32_t AA_USAGE_MEDIA = 1;
constexpr int32_t AA_CONTENT_MUSIC = 2;
constexpr int32_t AA_CALLBACK_CONTINUE = 0;

struct AAudioApi {
    void* lib = nullptr;
    aaudio_result_t (*createBuilder)(AAudioStreamBuilder**) = nullptr;
    void (*setDirection)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*setSharingMode)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*setPerformanceMode)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*setFormat)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*setChannelCount)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*setSampleRate)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*setDataCallback)(AAudioStreamBuilder*, DataCb, void*) = nullptr;
    void (*setErrorCallback)(AAudioStreamBuilder*, ErrCb, void*) = nullptr;
    void (*setUsage)(AAudioStreamBuilder*, int32_t) = nullptr;          // API 28, optional
    void (*setContentType)(AAudioStreamBuilder*, int32_t) = nullptr;    // API 28, optional
    aaudio_result_t (*openStream)(AAudioStreamBuilder*, AAudioStream**) = nullptr;
    aaudio_result_t (*deleteBuilder)(AAudioStreamBuilder*) = nullptr;
    aaudio_result_t (*requestStart)(AAudioStream*) = nullptr;
    aaudio_result_t (*requestStop)(AAudioStream*) = nullptr;
    aaudio_result_t (*closeStream)(AAudioStream*) = nullptr;
    int32_t (*framesPerBurst)(AAudioStream*) = nullptr;
    bool ok = false;
};

const AAudioApi& api() {
    static const AAudioApi a = [] {
        AAudioApi x;
        x.lib = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
        if (!x.lib) return x;
        auto sym = [&](const char* n) { return dlsym(x.lib, n); };
#define AM_BIND(field, name) x.field = reinterpret_cast<decltype(x.field)>(sym(name))
        AM_BIND(createBuilder, "AAudio_createStreamBuilder");
        AM_BIND(setDirection, "AAudioStreamBuilder_setDirection");
        AM_BIND(setSharingMode, "AAudioStreamBuilder_setSharingMode");
        AM_BIND(setPerformanceMode, "AAudioStreamBuilder_setPerformanceMode");
        AM_BIND(setFormat, "AAudioStreamBuilder_setFormat");
        AM_BIND(setChannelCount, "AAudioStreamBuilder_setChannelCount");
        AM_BIND(setSampleRate, "AAudioStreamBuilder_setSampleRate");
        AM_BIND(setDataCallback, "AAudioStreamBuilder_setDataCallback");
        AM_BIND(setErrorCallback, "AAudioStreamBuilder_setErrorCallback");
        AM_BIND(setUsage, "AAudioStreamBuilder_setUsage");
        AM_BIND(setContentType, "AAudioStreamBuilder_setContentType");
        AM_BIND(openStream, "AAudioStreamBuilder_openStream");
        AM_BIND(deleteBuilder, "AAudioStreamBuilder_delete");
        AM_BIND(requestStart, "AAudioStream_requestStart");
        AM_BIND(requestStop, "AAudioStream_requestStop");
        AM_BIND(closeStream, "AAudioStream_close");
        AM_BIND(framesPerBurst, "AAudioStream_getFramesPerBurst");
#undef AM_BIND
        x.ok = x.createBuilder && x.setDirection && x.setSharingMode && x.setPerformanceMode && x.setFormat &&
               x.setChannelCount && x.setSampleRate && x.setDataCallback && x.setErrorCallback && x.openStream &&
               x.deleteBuilder && x.requestStart && x.requestStop && x.closeStream;
        if (!x.ok) LOGW("libaaudio.so found but not complete");
        return x;
    }();
    return a;
}

// ---- one preview ------------------------------------------------------------------------------------------------
struct Handle {
    std::mutex mu;                       // start / stop / clip list (the Kotlin side calls from one thread anyway)
    std::vector<int> fds;                // duplicated file descriptors of the clips (spec.source = index)
    std::vector<int64_t> lens;
    std::vector<AudioClipSpec> clips;

    std::unique_ptr<PreviewFeeder> feeder;
    AAudioStream* stream = nullptr;

    // Shared with the audio callback.
    PcmRing* ring = nullptr;                    // = &feeder->ring() while the stream is open
    PreviewFeeder* feederRaw = nullptr;
    int64_t startFrame = 0;                     // timeline frame the feeder started at
    std::atomic<int64_t> pendingSkip{-1};       // < 0: not armed yet (the callback plays silence)
    std::atomic<bool> armed{false};
    std::atomic<bool> cbSeen{false};
    std::atomic<int64_t> baseFrame{0};          // timeline frame of the first frame played after arming
    std::atomic<int64_t> consumed{0};           // content frames played since arming
    std::atomic<int64_t> underruns{0};
    std::atomic<bool> broken{false};
};

int32_t onData(AAudioStream*, void* user, void* data, int32_t numFrames) {
    Handle* h = static_cast<Handle*>(user);
    int16_t* out = static_cast<int16_t*>(data);
    if (!h->armed.load(std::memory_order_acquire)) {
        const int64_t skip = h->pendingSkip.load(std::memory_order_acquire);
        if (skip < 0 || !h->ring) {
            std::memset(out, 0, (size_t)numFrames * 4);
            h->cbSeen.store(true, std::memory_order_release);
            return AA_CALLBACK_CONTINUE;
        }
        // The start took a while (opening the decoders, the stream): drop the sound that is already in the past,
        // so what plays now is what the playhead is on.
        const size_t dropped = h->ring->skip((size_t)skip);
        h->baseFrame.store(h->startFrame + (int64_t)dropped);
        h->armed.store(true, std::memory_order_release);
    }
    const size_t got = h->ring ? h->ring->read(out, (size_t)numFrames) : 0;
    if (got < (size_t)numFrames) {
        std::memset(out + got * 2, 0, ((size_t)numFrames - got) * 4);
        if (h->feederRaw && !h->feederRaw->finished()) h->underruns.fetch_add(1, std::memory_order_relaxed);
    }
    h->consumed.fetch_add((int64_t)got, std::memory_order_relaxed);
    return AA_CALLBACK_CONTINUE;
}

void onError(AAudioStream*, void* user, aaudio_result_t error) {
    // e.g. headphones unplugged: the stream is dead, the Kotlin side starts a new one at the next sync.
    static_cast<Handle*>(user)->broken.store(true);
    LOGW("AAudio stream error %d", error);
}

void stopLocked(Handle* h) {
    if (h->stream) {
        const AAudioApi& a = api();
        a.requestStop(h->stream);
        a.closeStream(h->stream);   // returns when the callback is not running any more
        h->stream = nullptr;
    }
    h->ring = nullptr;
    h->feederRaw = nullptr;
    h->feeder.reset();              // stops the mixer thread, closes the decoders
    h->armed.store(false);
    h->pendingSkip.store(-1);
    h->cbSeen.store(false);
    h->consumed.store(0);
}

void closeFds(Handle* h) {
    for (int fd : h->fds) if (fd >= 0) close(fd);
    h->fds.clear();
    h->lens.clear();
    h->clips.clear();
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_example_engine_AudioPreviewNative_nativeAvailable(JNIEnv*, jclass) {
    return api().ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_example_engine_AudioPreviewNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Handle());
}

JNIEXPORT void JNICALL Java_com_example_engine_AudioPreviewNative_nativeRelease(JNIEnv*, jclass, jlong p) {
    Handle* h = reinterpret_cast<Handle*>(p);
    if (!h) return;
    {
        std::lock_guard<std::mutex> lock(h->mu);
        stopLocked(h);
        closeFds(h);
    }
    delete h;
}

// Forget the clips of the last plan (stops the sound first).
JNIEXPORT void JNICALL Java_com_example_engine_AudioPreviewNative_nativeClearClips(JNIEnv*, jclass, jlong p) {
    Handle* h = reinterpret_cast<Handle*>(p);
    std::lock_guard<std::mutex> lock(h->mu);
    stopLocked(h);
    closeFds(h);
}

// Same numbers as NativeExportEncoder.addAudioClip (microseconds on the timeline / in the file).
JNIEXPORT jboolean JNICALL Java_com_example_engine_AudioPreviewNative_nativeAddClip(
    JNIEnv*, jclass, jlong p, jint fd, jlong length, jlong startUs, jlong durUs, jlong trimStartUs, jdouble speed,
    jfloat volume, jlong fadeInUs, jlong fadeOutUs) {
    Handle* h = reinterpret_cast<Handle*>(p);
    std::lock_guard<std::mutex> lock(h->mu);
    const int copy = dup(fd);
    if (copy < 0) return JNI_FALSE;
    auto frames = [](int64_t us) { return us * kAudioRate / 1000000LL; };
    AudioClipSpec s;
    s.source = (int)h->fds.size();
    s.startFrame = frames(std::max<int64_t>(0, startUs));
    s.fullDurFrames = frames(std::max<int64_t>(0, durUs));
    s.durFrames = s.fullDurFrames;
    s.trimStartUs = std::max<int64_t>(0, trimStartUs);
    s.speed = speed;
    s.volume = volume;
    s.fadeInFrames = frames(std::max<int64_t>(0, fadeInUs));
    s.fadeOutFrames = frames(std::max<int64_t>(0, fadeOutUs));
    h->fds.push_back(copy);
    h->lens.push_back(length);
    h->clips.push_back(s);
    return JNI_TRUE;
}

// Start playing the clips from `positionUs` (timeline time). Blocks for up to a few hundred milliseconds (the decoders
// and the stream are opened), so call it from a worker thread. The sound that is already late when the stream runs is
// dropped, so it lines up with the playhead as it is at that moment.
JNIEXPORT jboolean JNICALL Java_com_example_engine_AudioPreviewNative_nativeStart(JNIEnv*, jclass, jlong p,
                                                                                  jlong positionUs) {
    using Clock = std::chrono::steady_clock;
    Handle* h = reinterpret_cast<Handle*>(p);
    std::lock_guard<std::mutex> lock(h->mu);
    const auto t0 = Clock::now();
    stopLocked(h);
    h->broken.store(false);
    const AAudioApi& a = api();
    if (!a.ok || h->clips.empty()) return JNI_FALSE;

    h->startFrame = std::max<int64_t>(0, positionUs) * kAudioRate / 1000000LL;
    Handle* hp = h;
    h->feeder.reset(new PreviewFeeder([hp](const AudioClipSpec& s) -> std::unique_ptr<FrameSource> {
        if (s.source < 0 || (size_t)s.source >= hp->fds.size()) return nullptr;
        std::unique_ptr<FrameSource> src =
            AudioFileSource::open(hp->fds[(size_t)s.source], hp->lens[(size_t)s.source], s.trimStartUs);
        if (src && std::fabs(s.speed - 1.0) > 0.001) src.reset(new TimeStretcher(std::move(src), s.speed));
        return src;
    }, h->clips, h->startFrame));
    if (h->feeder->finished()) {      // nothing left to play after this position
        h->feeder.reset();
        return JNI_TRUE;
    }
    h->feeder->start();
    h->ring = &h->feeder->ring();
    h->feederRaw = h->feeder.get();

    // Let the mixer get ~160 ms ahead first, so the stream does not start into an empty ring.
    constexpr size_t kPrebuffer = kAudioRate / 6;
    while (h->ring->available() < kPrebuffer && !h->feeder->finished() &&
           Clock::now() - t0 < std::chrono::milliseconds(600)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }

    AAudioStreamBuilder* b = nullptr;
    if (a.createBuilder(&b) != 0 || !b) { stopLocked(h); return JNI_FALSE; }
    a.setDirection(b, AA_DIRECTION_OUTPUT);
    a.setSharingMode(b, AA_SHARING_SHARED);
    a.setFormat(b, AA_FORMAT_PCM_I16);
    a.setChannelCount(b, 2);
    a.setSampleRate(b, kAudioRate);
    if (a.setUsage) a.setUsage(b, AA_USAGE_MEDIA);
    if (a.setContentType) a.setContentType(b, AA_CONTENT_MUSIC);
    a.setDataCallback(b, onData, h);
    a.setErrorCallback(b, onError, h);
    AAudioStream* stream = nullptr;
    a.setPerformanceMode(b, AA_PERF_LOW_LATENCY);
    aaudio_result_t r = a.openStream(b, &stream);
    if (r != 0) {   // some phones refuse the low-latency path: the normal one is fine for an editor
        a.setPerformanceMode(b, AA_PERF_NONE);
        r = a.openStream(b, &stream);
    }
    a.deleteBuilder(b);
    if (r != 0 || !stream) {
        LOGW("AAudio open failed %d", r);
        stopLocked(h);
        return JNI_FALSE;
    }
    h->stream = stream;
    if (a.requestStart(stream) != 0) {
        LOGW("AAudio start failed");
        stopLocked(h);
        return JNI_FALSE;
    }
    // The first callback plays silence. Once it is running, drop what is late: the time since this call began (decoders,
    // stream start-up) plus one burst, so the frame played next is the one the playhead is on.
    const auto waitUntil = Clock::now() + std::chrono::milliseconds(400);
    while (!h->cbSeen.load(std::memory_order_acquire) && Clock::now() < waitUntil) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    const int burst = a.framesPerBurst ? std::max(0, a.framesPerBurst(stream)) : 0;
    const double lateSec = std::chrono::duration<double>(Clock::now() - t0).count();
    h->pendingSkip.store((int64_t)(lateSec * kAudioRate) + burst, std::memory_order_release);
    LOGI("preview started at %lld us, %zu clips, burst %d, late %.0f ms", (long long)positionUs, h->clips.size(),
         burst, lateSec * 1000.0);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_example_engine_AudioPreviewNative_nativeStop(JNIEnv*, jclass, jlong p) {
    Handle* h = reinterpret_cast<Handle*>(p);
    std::lock_guard<std::mutex> lock(h->mu);
    stopLocked(h);
}

// Timeline time (us) of the sound that is playing now, or -1 when nothing is playing / not lined up yet.
JNIEXPORT jlong JNICALL Java_com_example_engine_AudioPreviewNative_nativePositionUs(JNIEnv*, jclass, jlong p) {
    Handle* h = reinterpret_cast<Handle*>(p);
    if (!h->armed.load(std::memory_order_acquire)) return -1;
    const int64_t frames = h->baseFrame.load() + h->consumed.load();
    return frames * 1000000LL / kAudioRate;
}

// False after the stream died (headphones unplugged...).
JNIEXPORT jboolean JNICALL Java_com_example_engine_AudioPreviewNative_nativeHealthy(JNIEnv*, jclass, jlong p) {
    Handle* h = reinterpret_cast<Handle*>(p);
    return h->broken.load() ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_com_example_engine_AudioPreviewNative_nativeUnderruns(JNIEnv*, jclass, jlong p) {
    return reinterpret_cast<Handle*>(p)->underruns.load();
}

}  // extern "C"
