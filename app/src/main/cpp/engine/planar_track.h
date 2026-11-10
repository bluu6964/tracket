// Planar tracker (the core of Mocha-style tracking): follows a flat surface (screen, poster, wall, table top) through a
// video, perspective included. The user gives 4 corners on the first frame; every new frame gives the 4 corners again.
//
// Method (no library): Shi-Tomasi corners inside the surface -> pyramidal Lucas-Kanade with a forward-backward check ->
// deterministic RANSAC homography (corners on the background / on moving objects are rejected as outliers) -> least-squares
// refit on the inliers -> the 4 corners are moved by that homography. New corners are picked when too many are lost.
// Plain C++17, no GL / Android: host-testable (tests/planar_track_selftest.cpp). Pictures are float grey, 0..255.
#pragma once
#include <cstdint>
#include <vector>

namespace am {

class PlanarTracker {
public:
    // Corners are x, y pairs in picture pixels: top-left, top-right, bottom-right, bottom-left (any consistent turn works).
    // False when the surface holds too little texture to be followed or the corners are not a proper quad.
    bool start(const float* gray, int w, int h, const float* quad8);

    // Follows the surface into the next frame (same size). False = lost (the corners keep their last place).
    // A single bad frame (motion blur, a flash) is bridged: true is returned and the corners are held.
    bool step(const float* gray, int w, int h);

    // Global mode (auto stabilize): follows the WHOLE picture, not a marked surface. Dense points over the frame, and the
    // motion is a similarity (move + turn + scale, 2-point RANSAC) instead of a perspective homography: far steadier, no
    // wobble from perspective noise. The quad is the frame rectangle carried by the camera move. Call before start().
    void setGlobal(bool on) { global_ = on; }

    const float* quad() const { return quad_; }       // 8 floats, picture pixels
    int pointCount() const { return n_; }             // corners followed right now
    int lastInliers() const { return inliers_; }
    // The corners followed right now, x, y, x, y ... (picture pixels), for the live display; returns how many points (not floats).
    int copyPoints(float* out, int maxPoints) const;

    static constexpr int kMaxPoints = 360;
    static constexpr int kMinPoints = 6;
    static constexpr int kMaxMisses = 3;

private:
    struct Level {
        int w = 0, h = 0;
        std::vector<float> img, ix, iy;
    };
    using Pyramid = std::vector<Level>;

    static Pyramid buildPyramid(const float* gray, int w, int h);
    bool trackPoint(const Pyramid& a, const Pyramid& b, float x, float y, float* out);
    void detect(const Level& l);
    int maxPts() const { return global_ ? kMaxPoints : 120; }   // marked surface: 120 as before; whole picture: dense
    bool miss() { return ++misses_ <= kMaxMisses; }
    bool inQuad(float x, float y, float scale) const;

    bool global_ = false;
    float quad_[8] = {0};
    float pts_[kMaxPoints * 2] = {0};
    int n_ = 0;
    int startCount_ = 0;
    int sinceReseed_ = 0;
    int misses_ = 0;
    int inliers_ = 0;
    uint32_t seed_ = 12345u;
    int w_ = 0, h_ = 0;
    Pyramid prev_;
    // scratch for one tracked point
    float tpl_[15 * 15];
    float tgx_[15 * 15];
    float tgy_[15 * 15];
};

}  // namespace am
