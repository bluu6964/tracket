// Native shape and sticker layers (step 1 of "Canvas out of the export", v119).
// Reproduces LayerPainter.drawShape / drawSticker (VideoExporter.kt): same box (65 dp * k half size), same
// transform order (translate, rotate, scale, flip), same fills (solid / gradient / picture), stroke, crop clip and
// layer opacity. Pure C++ (no FreeType, no HarfBuzz, no Android): shapes are rasterised with a signed distance per
// pixel (1 px wide anti-aliasing), stickers are blitted with text::blit (bilinear). Host test: tests/shape_selftest.cpp
#pragma once
#include <cstdint>

namespace am {
namespace shape {

// OVAL = 2.4 : 1.5 ellipse (what the old export drew); the editor's "OVAL" is drawn as a circle by Compose, so Kotlin maps it to CIRCLE.
// ROUNDED: rectangle with corner radius [Request::corner]. PENTAGON / STAR: regular polygon, outer radius = base, first tip up.
enum Kind { CIRCLE = 0, RECT = 1, ROUNDED = 2, OVAL = 3, TRIANGLE = 4, PENTAGON = 5, STAR = 6 };

struct Request {
    int kind = CIRCLE;
    float base = 100.f;              // half size of the 2*base box (export px at scale 1)
    float corner = 0.f;              // ROUNDED: corner radius in local units (0 = base / 4)
    float cx = 0.f, cy = 0.f;        // centre on the frame
    float scale = 1.f;               // > 0
    float rotationDeg = 0.f;         // clockwise (Canvas.rotate)
    float flipX = 1.f, flipY = 1.f;  // +1 / -1
    float alpha = 1.f;               // whole layer (Canvas.saveLayerAlpha)
    bool hasCrop = false;
    float crop[4] = {0.f, 0.f, 1.f, 1.f};  // l, t, r, b as fractions of the box
    bool fillOn = true;
    uint32_t fillArgb = 0xFFFF3377u;  // straight ARGB
    int fillKind = 0;                 // 0 solid, 1 gradient, 2 picture
    int gradType = 0;                 // 0 linear, 1 radial, 2 sweep
    uint32_t grad1 = 0xFF1FD91Fu, grad2 = 0xFFFFFFFFu;
    float gx1 = 0.05f, gy1 = 0.5f, gx2 = 0.95f, gy2 = 0.5f;  // 0..1 inside the box
    const uint8_t* fillPix = nullptr;  // premultiplied RGBA8888; fillKind 2 without pixels = no fill
    int fillW = 0, fillH = 0, fillStride = 0;
    int fillImgMode = 0;               // 0 fill (cover), 1 fit, 2 stretch
    float strokeW = 0.f;               // local units (scaled with the layer, like Canvas)
    uint32_t strokeArgb = 0xFFFFFFFFu;
};

/** Draws one shape onto a premultiplied RGBA8888 bitmap (source-over). False = invalid request, nothing drawn. */
bool drawShape(uint8_t* dst, int dstW, int dstH, int dstStride, const Request& rq);

struct StickerRequest {
    float halfW = 65.f, halfH = 65.f;  // the picture is drawn into [-halfW, halfW] x [-halfH, halfH] (local)
    float cx = 0.f, cy = 0.f;
    float scale = 1.f;
    float rotationDeg = 0.f;
    float flipX = 1.f, flipY = 1.f;
    float alpha = 1.f;
    const uint8_t* pix = nullptr;      // premultiplied RGBA8888
    int w = 0, h = 0, stride = 0;
};

/** Draws one sticker picture (bilinear, source-over). False = invalid request. */
bool drawSticker(uint8_t* dst, int dstW, int dstH, int dstStride, const StickerRequest& rq);

}  // namespace shape
}  // namespace am
