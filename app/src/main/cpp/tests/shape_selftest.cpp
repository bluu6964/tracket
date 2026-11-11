// g++ -std=c++17 -O1 -g -fsanitize=address,undefined -Wall -Wextra -I.. ../engine/shape_engine.cpp ../engine/text_blit.cpp shape_selftest.cpp -o shape_selftest && ./shape_selftest
// Host test of the C++ shape / sticker engine (areas of the outlines, stroke, crop, fills, opacity, sticker blit).
#include <cmath>
#include <cstdio>
#include <vector>

#include "engine/shape_engine.h"

using namespace am::shape;

static int fails = 0, checks = 0;
static void ok(bool c, const char* what) { ++checks; if (!c) { ++fails; std::printf("FAIL %s\n", what); } }
static void near(double v, double want, double tol, const char* what) {
    ++checks;
    if (std::fabs(v - want) > tol) { ++fails; std::printf("FAIL %s: got %.2f want %.2f (tol %.2f)\n", what, v, want, tol); }
}

struct Bmp {
    int w, h;
    std::vector<uint8_t> px;
    Bmp(int w_, int h_) : w(w_), h(h_), px((size_t)w_ * h_ * 4, 0) {}
    uint8_t* at(int x, int y) { return &px[((size_t)y * w + x) * 4]; }
    double coverage() {  // sum of alpha / 255 = covered area in pixels
        double s = 0;
        for (int i = 0; i < w * h; ++i) s += px[(size_t)i * 4 + 3] / 255.0;
        return s;
    }
};

static Request base(int kind) {
    Request r;
    r.kind = kind; r.base = 100.f; r.cx = 200.f; r.cy = 200.f;
    r.fillArgb = 0xFFFFFFFFu;
    return r;
}

int main() {
    const double pi = 3.14159265358979;
    {   // areas of the five shapes (px^2), anti-aliased edges -> about 0.5 % tolerance
        Bmp b(400, 400);
        Request r = base(CIRCLE);
        ok(drawShape(b.px.data(), b.w, b.h, b.w * 4, r), "circle drawn");
        near(b.coverage(), pi * 100 * 100, pi * 100 * 100 * 0.01, "circle area");
    }
    {
        Bmp b(400, 400); Request r = base(RECT);
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        near(b.coverage(), 200.0 * 200.0, 200.0 * 200.0 * 0.01, "rect area");
    }
    {
        Bmp b(400, 400); Request r = base(ROUNDED);
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        const double rad = 25, area = 200.0 * 200.0 - (4 - pi) * rad * rad;
        near(b.coverage(), area, area * 0.01, "rounded rect area");
    }
    {
        Bmp b(400, 400); Request r = base(OVAL);
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        const double area = pi * 120 * 75;
        near(b.coverage(), area, area * 0.015, "oval area");
    }
    {
        Bmp b(400, 400); Request r = base(TRIANGLE);
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        near(b.coverage(), 2.0 * 100 * 100, 2.0 * 100 * 100 * 0.01, "triangle area");
        ok(b.at(200, 290)[3] == 255, "triangle inside near base");
        ok(b.at(110, 120)[3] == 0, "triangle outside upper left");
        ok(b.at(200, 150)[3] == 255, "triangle inside under apex");
    }
    {   // rotation by 90 deg of a triangle: apex points right; scale 0.5; flip
        Bmp b(400, 400); Request r = base(TRIANGLE); r.rotationDeg = 90.f;
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        near(b.coverage(), 2.0 * 100 * 100, 200, "rotated triangle area");
        ok(b.at(290, 200)[3] == 255 && b.at(310, 200)[3] == 0 && b.at(110, 200)[3] == 255 && b.at(105, 95)[3] == 0, "90 deg: apex (0,-b) goes to the right (Canvas.rotate is clockwise)");
        Bmp c(400, 400); Request q = base(TRIANGLE); q.flipY = -1.f;
        drawShape(c.px.data(), c.w, c.h, c.w * 4, q);
        ok(c.at(200, 120)[3] == 255 && c.at(200, 280)[3] == 255 && c.at(130, 120)[3] == 255 && c.at(130, 280)[3] == 0, "flipY: apex down");
        Bmp d(400, 400); Request t = base(CIRCLE); t.scale = 0.5f;
        drawShape(d.px.data(), d.w, d.h, d.w * 4, t);
        near(d.coverage(), pi * 50 * 50, pi * 50 * 50 * 0.01, "scaled circle area");
    }
    {   // stroke only: circle ring and mitered rectangle ring
        Bmp b(400, 400); Request r = base(CIRCLE); r.fillOn = false; r.strokeW = 10.f; r.strokeArgb = 0xFFFF0000u;
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        const double ring = pi * (105.0 * 105 - 95.0 * 95);
        near(b.coverage(), ring, ring * 0.01, "circle ring area");
        ok(b.at(200, 100)[0] == 255 && b.at(200, 100)[1] == 0, "stroke colour red");
        Bmp c(400, 400); Request q = base(RECT); q.fillOn = false; q.strokeW = 20.f; q.strokeArgb = 0xFF00FF00u;
        drawShape(c.px.data(), c.w, c.h, c.w * 4, q);
        const double rr = 220.0 * 220 - 180.0 * 180;
        near(c.coverage(), rr, rr * 0.01, "rect ring (mitered corners)");
        Bmp d(400, 400); Request t = base(RECT); t.strokeW = 10.f; t.scale = 1.5f; t.fillArgb = 0xFF0000FFu; t.strokeArgb = 0xFFFFFFFFu;
        drawShape(d.px.data(), d.w, d.h, d.w * 4, t);  // stroke width scales with the layer (Canvas.scale)
        const double all = 315.0 * 315;  // outer edge = (100 + 5) * 1.5 * 2
        near(d.coverage(), all, all * 0.01, "stroke scales with the layer");
    }
    {   // opacity, none fill, crop
        Bmp b(400, 400); Request r = base(RECT); r.alpha = 0.5f;
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        near(b.at(200, 200)[3], 128, 1, "alpha 0.5 -> 128");
        near(b.at(200, 200)[0], 128, 1, "premultiplied colour");
        Bmp c(400, 400); Request q = base(RECT); q.fillOn = false;
        drawShape(c.px.data(), c.w, c.h, c.w * 4, q);
        near(c.coverage(), 0, 0.001, "no fill, no stroke draws nothing");
        Bmp d(400, 400); Request t = base(RECT); t.hasCrop = true; t.crop[0] = 0.25f; t.crop[1] = 0.f; t.crop[2] = 0.75f; t.crop[3] = 1.f;
        drawShape(d.px.data(), d.w, d.h, d.w * 4, t);
        near(d.coverage(), 100.0 * 200.0, 100.0 * 200 * 0.01, "crop = half the width");
        ok(d.at(140, 200)[3] == 0 && d.at(200, 200)[3] == 255 && d.at(260, 200)[3] == 0, "crop position");
    }
    {   // gradient: linear left -> right, radial, picture fill
        Bmp b(400, 400); Request r = base(RECT); r.fillKind = 1; r.gradType = 0;
        r.grad1 = 0xFFFF0000u; r.grad2 = 0xFF0000FFu; r.gx1 = 0.f; r.gy1 = 0.5f; r.gx2 = 1.f; r.gy2 = 0.5f;
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        ok(b.at(102, 200)[0] > 240 && b.at(102, 200)[2] < 15, "linear gradient start red");
        ok(b.at(297, 200)[2] > 240 && b.at(297, 200)[0] < 15, "linear gradient end blue");
        near(b.at(200, 200)[0], 127, 3, "linear gradient middle");
        std::vector<uint8_t> pic(4 * 4 * 4);
        for (int i = 0; i < 16; ++i) { pic[i * 4] = 0; pic[i * 4 + 1] = 255; pic[i * 4 + 2] = 0; pic[i * 4 + 3] = 255; }
        Bmp c(400, 400); Request q = base(RECT); q.fillKind = 2; q.fillPix = pic.data(); q.fillW = 4; q.fillH = 4; q.fillStride = 16; q.fillImgMode = 2;
        drawShape(c.px.data(), c.w, c.h, c.w * 4, q);
        ok(c.at(200, 200)[1] == 255 && c.at(200, 200)[0] == 0, "picture fill green");
        Bmp d(400, 400); Request t = base(RECT); t.fillKind = 2;  // picture missing -> no fill
        drawShape(d.px.data(), d.w, d.h, d.w * 4, t);
        near(d.coverage(), 0, 0.001, "picture fill without pixels draws nothing");
    }
    {   // sticker: 2 x 1 picture (red | blue), fitted into [-60,60] x [-30,30]
        const uint8_t pic[8] = {255, 0, 0, 255, 0, 0, 255, 255};
        StickerRequest s; s.halfW = 60; s.halfH = 30; s.cx = 200; s.cy = 200; s.pix = pic; s.w = 2; s.h = 1; s.stride = 8;
        Bmp b(400, 400);
        ok(drawSticker(b.px.data(), b.w, b.h, b.w * 4, s), "sticker drawn");
        near(b.coverage(), 120.0 * 60.0, 120.0 * 60 * 0.03, "sticker area");
        ok(b.at(160, 200)[0] > 200, "left half red");
        ok(b.at(240, 200)[2] > 200, "right half blue");
        Bmp c(400, 400); s.flipX = -1.f;
        drawSticker(c.px.data(), c.w, c.h, c.w * 4, s);
        ok(c.at(160, 200)[2] > 200 && c.at(240, 200)[0] > 200, "flipX swaps the halves");
        Bmp d(400, 400); s.flipX = 1.f; s.rotationDeg = 90.f;
        drawSticker(d.px.data(), d.w, d.h, d.w * 4, s);
        ok(d.at(200, 160)[0] > 200 && d.at(200, 240)[2] > 200, "rotate 90 deg clockwise: red on top, blue below");
        Bmp e(400, 400); s.rotationDeg = 0.f; s.alpha = 0.5f; s.scale = 2.f;
        drawSticker(e.px.data(), e.w, e.h, e.w * 4, s);
        near(e.at(200, 200)[3], 128, 2, "sticker opacity");
        near(e.coverage() / 2.0, 240.0 * 120.0 / 2.0 * 1.0 * 0.5 * 2.0 / 2.0, 240.0 * 120 * 0.03, "sticker scale 2");
    }
    {   // pentagon, star, rounded corner parameter
        Bmp b(400, 400); Request r = base(PENTAGON);
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        near(b.coverage(), 2.5 * 100 * 100 * std::sin(72 * pi / 180), 2.5 * 100 * 100 * 0.01, "pentagon area");
        ok(b.at(200, 105)[3] > 0 && b.at(200, 95)[3] == 0, "pentagon tip is up");
        Bmp c(400, 400); Request q = base(STAR);
        drawShape(c.px.data(), c.w, c.h, c.w * 4, q);
        near(c.coverage(), 5.0 * 100 * 45 * std::sin(36 * pi / 180), 5.0 * 100 * 45 * 0.02, "star area");
        ok(c.at(200, 105)[3] > 0 && c.at(200, 200)[3] == 255, "star tip up, centre filled");
        Bmp d(400, 400); Request t = base(ROUNDED); t.corner = 10.f / 65.f * 100.f;
        drawShape(d.px.data(), d.w, d.h, d.w * 4, t);
        const double rad = 10.0 / 65.0 * 100.0, area = 200.0 * 200.0 - (4 - pi) * rad * rad;
        near(d.coverage(), area, area * 0.005, "rounded rect with a corner parameter");
        Bmp e(400, 400); Request u = base(STAR); u.fillOn = false; u.strokeW = 6.f; u.strokeArgb = 0xFFFFFFFFu;
        drawShape(e.px.data(), e.w, e.h, e.w * 4, u);
        ok(e.coverage() > 6.0 * 10 * 45 && e.at(200, 200)[3] == 0, "star outline only");
    }
    {   // stroke trim (draw-on): only the outline between the trim fractions is drawn
        const double ring = pi * (105.0 * 105 - 95.0 * 95);
        Bmp b(400, 400); Request r = base(CIRCLE); r.fillOn = false; r.strokeW = 10.f; r.strokeArgb = 0xFFFF0000u;
        r.trimStart = 0.f; r.trimEnd = 0.25f;  // first quarter: +x axis clockwise (y down) = bottom-right quadrant
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        near(b.coverage(), ring / 4, ring * 0.02, "circle trim = a quarter of the ring");
        ok(b.at(271, 271)[3] == 255 && b.at(271, 129)[3] == 0 && b.at(129, 271)[3] == 0, "circle trim quadrant");

        Bmp c(400, 400); Request q = base(RECT); q.fillOn = false; q.strokeW = 20.f; q.strokeArgb = 0xFFFFFFFFu;
        q.trimStart = 0.5f; q.trimEnd = 1.f;  // second half: bottom edge (right to left) + left edge
        drawShape(c.px.data(), c.w, c.h, c.w * 4, q);
        ok(c.at(200, 100)[3] == 0 && c.at(300, 200)[3] == 0, "rect trim hides top + right");
        ok(c.at(200, 300)[3] == 255 && c.at(100, 200)[3] == 255, "rect trim keeps bottom + left");

        Bmp d(400, 400); Request u = base(ROUNDED); u.corner = 25.f; u.fillOn = false; u.strokeW = 10.f;
        u.strokeArgb = 0xFFFFFFFFu; u.trimEnd = 0.125f;  // 0.125 * (8*75 + 2pi*25) = 94.6 of the 150 long top edge
        drawShape(d.px.data(), d.w, d.h, d.w * 4, u);
        ok(d.at(150, 100)[3] == 255 && d.at(250, 100)[3] == 0, "rounded trim runs along the top edge");

        Bmp e(400, 400); Request v = base(TRIANGLE); v.fillOn = false; v.strokeW = 10.f; v.strokeArgb = 0xFFFFFFFFu;
        v.trimEnd = 0.2f;  // stays on the apex -> bottom-right edge (its share is 223.6 / 647.2 of the outline)
        drawShape(e.px.data(), e.w, e.h, e.w * 4, v);
        ok(e.at(225, 150)[3] == 255 && e.at(200, 300)[3] == 0, "triangle trim starts at the apex");

        Bmp f(400, 400); Request w = base(CIRCLE); w.fillOn = false; w.strokeW = 10.f; w.strokeArgb = 0xFFFFFFFFu;
        w.trimStart = 0.5f; w.trimEnd = 0.5f;  // an empty window draws nothing
        drawShape(f.px.data(), f.w, f.h, f.w * 4, w);
        near(f.coverage(), 0, 0.001, "empty trim window");
    }
    {   // an imported line (trimFill): the trim gates the RECT fill along local x
        Bmp b(400, 400); Request r = base(RECT);
        r.trimFill = true; r.trimStart = 0.25f; r.trimEnd = 0.75f;
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        near(b.coverage(), 100.0 * 200.0, 100.0 * 200 * 0.01, "line trim = the middle half");
        ok(b.at(200, 200)[3] == 255 && b.at(120, 200)[3] == 0 && b.at(280, 200)[3] == 0, "line trim position");
    }
    {   // non-uniform scale (scaleY): the shape's own aspect, composed with the layer scale
        Bmp b(500, 500); Request r = base(RECT); r.cx = 250.f; r.cy = 250.f; r.scale = 2.f; r.scaleY = 1.f;
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        near(b.coverage(), 400.0 * 200.0, 400.0 * 200.0 * 0.01, "non-uniform rect area (sx * sy)");
        ok(b.at(440, 250)[3] == 255 && b.at(460, 250)[3] == 0, "rect reaches 2x in x");
        ok(b.at(250, 340)[3] == 255 && b.at(250, 360)[3] == 0, "rect stays 1x in y");

        Bmp c(500, 500); Request q = base(CIRCLE); q.cx = 250.f; q.cy = 250.f; q.scaleY = 0.5f;
        drawShape(c.px.data(), c.w, c.h, c.w * 4, q);
        near(c.coverage(), pi * 100 * 50, pi * 100 * 50 * 0.015, "circle squashed to an ellipse: area");
        ok(c.at(340, 250)[3] == 255 && c.at(250, 295)[3] == 255 && c.at(250, 310)[3] == 0, "ellipse extents");

        // Rotated 90 deg the long axis stands upright (Canvas order: rotate, then the local scale).
        Bmp d(500, 500); Request t = base(RECT); t.cx = 250.f; t.cy = 250.f; t.scale = 2.f; t.scaleY = 1.f; t.rotationDeg = 90.f;
        drawShape(d.px.data(), d.w, d.h, d.w * 4, t);
        ok(d.at(250, 440)[3] == 255 && d.at(250, 460)[3] == 0 && d.at(340, 250)[3] == 255 && d.at(360, 250)[3] == 0, "rotated non-uniform rect swaps axes");

        // scaleY unset draws exactly like scaleY == scale (pixel-identical).
        Bmp e(500, 500); Request u = base(ROUNDED); u.cx = 250.f; u.cy = 250.f; u.scale = 1.5f; u.strokeW = 8.f; u.strokeArgb = 0xFF00FF00u;
        Bmp f(500, 500); Request v = u; v.scaleY = v.scale;
        drawShape(e.px.data(), e.w, e.h, e.w * 4, u);
        drawShape(f.px.data(), f.w, f.h, f.w * 4, v);
        ok(e.px == f.px, "scaleY unset == scaleY == scale");

        // The stroke band is sw local units wide: sw*sx frame px on the x-facing edges, sw*sy on the
        // y-facing ones — what Canvas draws when it scales a stroked path non-uniformly.
        Bmp g(500, 500); Request w = base(RECT); w.cx = 250.f; w.cy = 250.f; w.fillOn = false;
        w.strokeW = 10.f; w.strokeArgb = 0xFFFFFFFFu; w.scaleY = 2.f;
        drawShape(g.px.data(), g.w, g.h, g.w * 4, w);
        ok(g.at(353, 250)[3] == 255 && g.at(357, 250)[3] == 0, "stroke band on the right edge: 10 frame px");
        ok(g.at(250, 43)[3] == 255 && g.at(250, 36)[3] == 0, "stroke band on the top edge: 20 frame px");

        // Crop fractions are of the local box, so they follow the per-axis scale.
        Bmp h(500, 500); Request z = base(RECT); z.cx = 250.f; z.cy = 250.f; z.scale = 2.f; z.scaleY = 1.f;
        z.hasCrop = true; z.crop[0] = 0.25f; z.crop[1] = 0.f; z.crop[2] = 0.75f; z.crop[3] = 1.f;
        drawShape(h.px.data(), h.w, h.h, h.w * 4, z);
        near(h.coverage(), 200.0 * 200.0, 200.0 * 200.0 * 0.01, "crop of a non-uniform rect = half its width");
    }
    {   // Canvas clip: the shape engine draws into the frame-sized picture both composites use
        // (preview + export), so an off-canvas shape must contribute nothing and a shape crossing
        // an edge must be cut exactly at the edge — the painter half of the final-frame clip
        // (CanvasClip / the renderer's canvas scissor are the composite half).
        Bmp b(400, 400);
        Request r = base(CIRCLE); r.cx = 2000.f;  // fully past the right edge (the ST Intro case)
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        ok(b.coverage() == 0.0, "circle fully right of the canvas draws nothing");
        r = base(CIRCLE); r.cy = -500.f;
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        ok(b.coverage() == 0.0, "circle fully above the canvas draws nothing");
        r = base(RECT); r.cx = -500.f;
        drawShape(b.px.data(), b.w, b.h, b.w * 4, r);
        ok(b.coverage() == 0.0, "rect fully left of the canvas draws nothing");

        // Crossing the right edge: centre 10 px inside it, radius 100 -> the visible part is the
        // circle minus the segment past x = 400, and coverage reaches the last column.
        Bmp c(400, 400);
        Request q = base(CIRCLE); q.cx = 390.f;
        drawShape(c.px.data(), c.w, c.h, c.w * 4, q);
        const double seg = 100.0 * 100.0 * std::acos(0.1) - 10.0 * std::sqrt(100.0 * 100.0 - 100.0);
        near(c.coverage(), pi * 100 * 100 - seg, (pi * 100 * 100 - seg) * 0.02, "circle cut at the right edge: area");
        ok(c.at(399, 200)[3] == 255, "circle cut at the right edge reaches the last column");

        // Crossing the left edge: only a thin sliver (10 px) is on the canvas.
        Bmp d(400, 400);
        Request s = base(CIRCLE); s.cx = -90.f;
        drawShape(d.px.data(), d.w, d.h, d.w * 4, s);
        ok(d.at(0, 200)[3] == 255 && d.at(12, 200)[3] == 0, "circle cut at the left edge: sliver only");
        ok(d.coverage() > 300.0 && d.coverage() < 900.0, "circle cut at the left edge: sliver area");

        // Crossing the bottom edge: a rect centred 10 px above it keeps 110 of its 200 rows.
        Bmp e(400, 400);
        Request u = base(RECT); u.cy = 390.f;
        drawShape(e.px.data(), e.w, e.h, e.w * 4, u);
        near(e.coverage(), 200.0 * 110.0, 200.0 * 110.0 * 0.015, "rect cut at the bottom edge: area");
        ok(e.at(200, 399)[3] == 255, "rect cut at the bottom edge reaches the last row");
    }
    ok(!drawShape(nullptr, 1, 1, 4, base(CIRCLE)), "null target refused");
    Request z = base(CIRCLE); z.base = 0.f;
    Bmp t(8, 8);
    ok(!drawShape(t.px.data(), 8, 8, 32, z), "zero size refused");
    // tiny frame / shape fully outside: no crash
    Request out = base(CIRCLE); out.cx = -5000.f;
    ok(drawShape(t.px.data(), 8, 8, 32, out), "off-frame shape is fine");

    std::printf("shape_selftest: %d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}
