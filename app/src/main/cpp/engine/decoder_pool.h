// After Motion native engine - video decoder pool.
//
// Why this exists: a project with many video layers needs many decoders, but phone chips (MediaTek
// especially) only allow a few hardware decoders at the same time. This pool
//   * keeps at most `maxHw` hardware decoders alive (LRU: the least recently used one is released
//     and transparently re-created, at the right position, the next time its clip is used),
//   * falls back to a software decoder when the hardware decoder cannot be created / configured /
//     keeps failing,
//   * gives exact-frame seeking (scrub / preview) and sequential decoding (export) per clip.
//
// Frames are rendered to the Surface given to open() (normally a SurfaceTexture of the GL renderer).
// Threading: every call is blocking and may take tens of ms - call from a render / worker thread,
// never from the UI thread. Calls for DIFFERENT clips may run in parallel; calls for the SAME clip
// are serialised by the pool.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>

struct ANativeWindow;

namespace am {

enum Status : int {
    kOk = 0,           // a NEW frame was rendered to the surface, *outPts is its timestamp
    kOkSame = 1,       // that frame is already on the surface (nothing new to wait for), *outPts valid
    kEndOfStream = -1, // no more frames (seek past the end keeps the last frame on the surface)
    kBusy = -2,        // all decoder slots are in use by other clips right now - retry shortly
    kError = -3        // unrecoverable for this clip (already retried + software fallback)
};

struct ClipInfo {
    int width = 0;
    int height = 0;
    int rotation = 0;       // degrees, the renderer must apply it
    int64_t durationUs = 0; // 0 = unknown
    float fps = 0.f;        // 0 = unknown
};

struct PoolStats {
    int activeHw = 0, activeSw = 0, maxHw = 0, maxSw = 0;
    int opened = 0, evictions = 0, fallbacks = 0, errors = 0;
};

class Clip;

class DecoderPool {
public:
    DecoderPool(int maxHw, int maxSw);
    ~DecoderPool();  // do not destroy while other threads are inside a call

    // Opens a video file (fd is dup()ed, the caller may close its own copy). Takes OWNERSHIP of
    // `window` (one reference, e.g. from ANativeWindow_fromSurface); may be null = decode only.
    // Returns a clip id > 0, or kError.
    // preferSoftware: skip the hardware decoder for this clip (a hardware decoder that outputs black).
    int open(int fd, int64_t offset, int64_t length, ANativeWindow* window, ClipInfo* info,
             bool preferSoftware = false);
    void close(int clipId);

    // Show the frame at `targetUs` (nearest frame, exact). Sequential scrubbing forward within
    // ~0.75 s does not re-seek.
    int seek(int clipId, int64_t targetUs, int64_t* outPts);
    // Decode and show the next frame in order (export / playback).
    int next(int clipId, int64_t* outPts);

    void setMaxHw(int n);
    PoolStats stats() const;

    // ---- used by Clip only ----
    bool reserveSlot(bool hw, const Clip* requester);
    void releaseSlot(bool hw);
    void noteFallback() { fallbacks_++; }
    void noteError() { errors_++; }
    uint64_t tick() { return ++tick_; }

private:
    std::shared_ptr<Clip> find(int id);
    bool evictOne(bool hw, const Clip* requester);  // pool mutex held

    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::map<int, std::shared_ptr<Clip>> clips_;
    std::atomic<int> maxHw_, maxSw_;
    std::atomic<int> activeHw_{0}, activeSw_{0};
    std::atomic<int> opened_{0}, evictions_{0}, fallbacks_{0}, errors_{0};
    std::atomic<uint64_t> tick_{0};
    int nextId_ = 1;
};

}  // namespace am
