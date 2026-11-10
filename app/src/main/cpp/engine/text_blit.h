// Pure pixel helpers of the native text renderer (no FreeType, no Android): affine blit of a
// premultiplied RGBA picture onto a premultiplied RGBA bitmap, and an alpha blur for shadows.
// Host test: tests/text_blit_selftest.cpp
#pragma once
#include <cstdint>
#include <vector>

namespace am {
namespace text {

/** Premultiplied RGBA8, byte order R,G,B,A (= Android ARGB_8888 in memory). */
struct Image {
    int w = 0, h = 0;
    std::vector<uint8_t> px;  // w * h * 4
    void alloc(int w_, int h_) { w = w_; h = h_; px.assign((size_t)w_ * h_ * 4, 0); }
};

/** x' = a*x + c*y + e,  y' = b*x + d*y + f  (source pixel edges at integers). */
struct Affine {
    float a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
};

struct BlitParams {
    Affine srcToDst;
    float alpha = 1.f;                                   // 0..1, multiplies the whole picture
    float clipL = 0, clipT = 0, clipR = 1e9f, clipB = 1e9f;  // clip rectangle in SOURCE pixels
    bool clampEdges = false;  // bilinear taps outside the picture repeat the edge pixel (Android bitmaps) instead of counting as empty (glyphs)
};

/** Source-over of [src] onto [dst] (premultiplied). Whole-pixel translations are copied exactly. */
void blit(uint8_t* dst, int dstW, int dstH, int dstStride, const Image& src, const BlitParams& p);

/** In-place blur of an A8 buffer: three box passes, ~gaussian with this sigma (pixels). */
void blurAlpha(std::vector<uint8_t>& a, int w, int h, float sigma);

}  // namespace text
}  // namespace am
