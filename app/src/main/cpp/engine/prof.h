// Tiny wall-clock counters for finding slow spots in the native export (read from Kotlin, shown after an export).
#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>

namespace am {

enum ProfId { PROF_AUDIO_DECODE = 0, PROF_AUDIO_ENCODE = 1, PROF_COUNT = 4 };

inline std::atomic<int64_t>& profCounter(int i) {
    static std::atomic<int64_t> c[PROF_COUNT];
    return c[(i < 0 || i >= PROF_COUNT) ? 0 : i];
}

// Adds the time between construction and destruction (ns) to counter i.
struct ProfScope {
    explicit ProfScope(int i) : i_(i), t0_(std::chrono::steady_clock::now()) {}
    ~ProfScope() {
        profCounter(i_).fetch_add(
            std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0_).count(),
            std::memory_order_relaxed);
    }
    ProfScope(const ProfScope&) = delete;
    ProfScope& operator=(const ProfScope&) = delete;

private:
    int i_;
    std::chrono::steady_clock::time_point t0_;
};

}  // namespace am
