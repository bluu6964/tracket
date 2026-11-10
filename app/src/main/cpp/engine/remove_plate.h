// Remove (Mocha-style "Remove module"): builds the CLEAN PLATE that is pasted over a flaw on a flat surface.
//
// Nothing is "painted": the surface (the 4 corners that were planar-tracked, see planar_track.h) is straightened ("rectified")
// out of several frames, and the clean patch is made from that:
//   * the area the user painted (the flaw) is found on the straightened surface,
//   * "Frames": a per-pixel median over the straightened frames brings back what other frames show there
//     (works when the flaw is not glued to the surface: a small object, a shadow, something passing),
//   * "Surround": what no frame shows is filled from the clean pixels around the flaw (smooth fill + matching grain),
//   * the patch gets a soft edge, and every frame gets a brightness factor (ring around the flaw) so light changes match.
// The patch only covers a window of the surface around the painted area ([domain]), so it stays sharp.
// Plain C++17, no GL / Android: host-testable (tests/remove_plate_selftest.cpp). Corners are u, v pairs (0..1 of the upright
// picture): top-left, top-right, bottom-right, bottom-left.
#pragma once
#include <cstdint>
#include <vector>

namespace am {

class RemovePlate {
public:
    // [quadUv] = the 4 corners on the reference frame, [mask] = the painted area on that frame's picture (mw x mh, 0..255,
    // row 0 = top, the whole picture). picW / picH = pixel size of the colour frames that will be added (scale of the plate),
    // maxSide = longest side of the plate in pixels. False when the area is empty / outside the corners / the corners are bad.
    bool setMask(const float* quadUv, const uint8_t* mask, int mw, int mh, int picW, int picH, int maxSide);

    int plateW() const { return w_; }
    int plateH() const { return h_; }
    // The window of the surface the plate covers: s0, t0, s1, t1 (0..1 of the 4 corners, s = across, t = down).
    const float* domain() const { return dom_; }

    // Mean grey level (0..255) of the ring around the painted area on one frame (for the light match); 0 when unknown.
    float ringLuma(const float* gray, int w, int h, const float* quadUv) const;

    // Straightens one colour frame (ARGB ints like Bitmap.getPixels) and keeps it. The FIRST frame added is the reference.
    bool addFrame(const uint32_t* argb, int w, int h, const float* quadUv);
    int frames() const { return (int)plates_.size(); }

    // The clean patch: premultiplied RGBA, plateW x plateH, row 0 = top (= the top of the window). [useFrames] = false: from
    // the surrounding only. False when no frame was added.
    bool build(bool useFrames, std::vector<uint8_t>* rgba);

    // The corners of the window (domain) when the surface has the corners [quadUv]: 8 floats, TL, TR, BR, BL. False = bad quad.
    static bool subQuad(const float* quadUv, const float* dom, float* out8);

private:
    int w_ = 0, h_ = 0;
    float dom_[4] = {0, 0, 1, 1};
    int grow_ = 3;        // painted area is grown by this many plate pixels (the flaw's soft edge)
    int ringW_ = 6;       // width of the ring used for the light match / grain
    std::vector<float> mask_;                 // painted area on the plate, 0..1
    std::vector<float> ringPts_;              // s, t pairs (0..1 of the 4 corners) of the ring pixels (sub-sampled)
    std::vector<std::vector<float>> plates_;  // straightened frames, RGB floats 0..255
};

}  // namespace am
