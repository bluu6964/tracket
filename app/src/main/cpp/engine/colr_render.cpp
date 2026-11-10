#include "colr_render.h"

#include <algorithm>
#include <cmath>

#include FT_COLOR_H
#include FT_OUTLINE_H
#include FT_IMAGE_H

namespace am {
namespace text {

namespace {

constexpr double kPi = 3.14159265358979323846;

struct Rgba { float r = 0, g = 0, b = 0, a = 0; };  // premultiplied
typedef std::vector<Rgba> Buf;

// x' = a*x + c*y + e ; y' = b*x + d*y + f   (pixel units, y up)
struct Aff {
    double a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
};

Aff mul(const Aff& m, const Aff& t) {  // apply t first, then m
    Aff r;
    r.a = m.a * t.a + m.c * t.b;
    r.b = m.b * t.a + m.d * t.b;
    r.c = m.a * t.c + m.c * t.d;
    r.d = m.b * t.c + m.d * t.d;
    r.e = m.a * t.e + m.c * t.f + m.e;
    r.f = m.b * t.e + m.d * t.f + m.f;
    return r;
}

bool invert(const Aff& m, Aff& o) {
    const double det = m.a * m.d - m.b * m.c;
    if (std::fabs(det) < 1e-12) return false;
    o.a = m.d / det;
    o.b = -m.b / det;
    o.c = -m.c / det;
    o.d = m.a / det;
    o.e = -(o.a * m.e + o.c * m.f);
    o.f = -(o.b * m.e + o.d * m.f);
    return true;
}

Aff translation(double x, double y) { Aff t; t.e = x; t.f = y; return t; }

// Transform [t] done around the point (cx, cy).
Aff around(double cx, double cy, const Aff& t) {
    return mul(translation(cx, cy), mul(t, translation(-cx, -cy)));
}

struct Stop { float off; Rgba c; };

struct Ctx {
    FT_Library lib = nullptr;
    FT_Face face = nullptr;
    FT_Color* pal = nullptr;
    int palCount = 0;
    double s = 1.0;  // pixels per font unit
    int W = 0, H = 0;
    double ox = 0, oy = 0;  // glyph space (pixels, y up) of the canvas' left / bottom edge
    int nodes = 0;
    int depth = 0;
    bool failed = false;
};

Rgba paletteColor(const Ctx& cx, uint32_t idx, double alpha) {
    float r = 0, g = 0, b = 0, a = 1;
    if (idx == 0xFFFF) {  // foreground colour: black (the text colour is not known here)
    } else if ((int)idx < cx.palCount && cx.pal) {
        const FT_Color& c = cx.pal[idx];
        r = c.red / 255.f; g = c.green / 255.f; b = c.blue / 255.f; a = c.alpha / 255.f;
    }
    a = (float)std::min(1.0, std::max(0.0, (double)a * alpha));
    Rgba o;
    o.r = r * a; o.g = g * a; o.b = b * a; o.a = a;
    return o;
}

// Fills [dst] where [mask] (or everywhere) says so with colour(px, py) = colour at the centre of canvas pixel.
template <typename F>
void fillWith(Ctx& cx, const uint8_t* mask, Buf& dst, F colourAt) {
    for (int y = 0; y < cx.H; ++y) {
        for (int x = 0; x < cx.W; ++x) {
            const size_t i = (size_t)y * cx.W + x;
            float m = mask ? mask[i] / 255.f : 1.f;
            if (m <= 0.f) continue;
            const Rgba c = colourAt(x, y);
            if (c.a <= 0.f && c.r <= 0.f && c.g <= 0.f && c.b <= 0.f) continue;
            Rgba& d = dst[i];
            const float k = 1.f - c.a * m;
            d.r = c.r * m + d.r * k;
            d.g = c.g * m + d.g * k;
            d.b = c.b * m + d.b * k;
            d.a = c.a * m + d.a * k;
        }
    }
}

// Reads the colour stops of a colour line (sorted by offset).
bool readStops(Ctx& cx, const FT_ColorLine& cl, std::vector<Stop>& out) {
    FT_ColorStopIterator it = cl.color_stop_iterator;
    FT_ColorStop st;
    int guard = 0;
    while (FT_Get_Colorline_Stops(cx.face, &st, &it)) {
        if (++guard > 256) break;
        Stop s;
        s.off = (float)st.stop_offset / 65536.f;  // 16.16 (not F2Dot14)
        s.c = paletteColor(cx, st.color.palette_index, (double)st.color.alpha / 16384.0);
        out.push_back(s);
    }
    if (out.empty()) return false;
    std::stable_sort(out.begin(), out.end(), [](const Stop& a, const Stop& b) { return a.off < b.off; });
    return true;
}

Rgba evalStops(const std::vector<Stop>& st, double t, FT_PaintExtend ext) {
    if (ext == FT_COLR_PAINT_EXTEND_REPEAT) {
        t = t - std::floor(t);
    } else if (ext == FT_COLR_PAINT_EXTEND_REFLECT) {
        double m = std::fmod(t, 2.0);
        if (m < 0) m += 2.0;
        t = m > 1.0 ? 2.0 - m : m;
    } else {
        t = std::min(1.0, std::max(0.0, t));
    }
    if (t <= st.front().off) return st.front().c;
    if (t >= st.back().off) return st.back().c;
    for (size_t i = 1; i < st.size(); ++i) {
        if (t <= st[i].off) {
            const Stop& a = st[i - 1];
            const Stop& b = st[i];
            const double span = (double)b.off - a.off;
            const float u = span > 1e-9 ? (float)((t - a.off) / span) : 1.f;
            Rgba o;
            o.r = a.c.r + (b.c.r - a.c.r) * u;
            o.g = a.c.g + (b.c.g - a.c.g) * u;
            o.b = a.c.b + (b.c.b - a.c.b) * u;
            o.a = a.c.a + (b.c.a - a.c.a) * u;
            return o;
        }
    }
    return st.back().c;
}

// Canvas pixel centre -> paint space (pixels, y up) through the inverse of [m].
struct Mapper {
    Aff inv;
    const Ctx* cx;
    void at(int x, int y, double& px, double& py) const {
        const double gx = cx->ox + x + 0.5;
        const double gy = cx->oy + (cx->H - y - 0.5);
        px = inv.a * gx + inv.c * gy + inv.e;
        py = inv.b * gx + inv.d * gy + inv.f;
    }
};

// Anti-aliased coverage of glyph [gid] placed by [m] (glyph space px, y up), 8 bit, canvas sized.
bool glyphMask(Ctx& cx, uint32_t gid, const Aff& m, std::vector<uint8_t>& out) {
    if (FT_Load_Glyph(cx.face, gid, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP) != 0) return false;
    FT_GlyphSlot slot = cx.face->glyph;
    if (slot->format != FT_GLYPH_FORMAT_OUTLINE) return false;
    FT_Outline src = slot->outline;
    out.assign((size_t)cx.W * cx.H, 0);
    if (src.n_points <= 0 || src.n_contours <= 0) return true;  // empty glyph: nothing to paint
    FT_Outline o;
    if (FT_Outline_New(cx.lib, (FT_UInt)src.n_points, src.n_contours, &o) != 0) return false;
    if (FT_Outline_Copy(&src, &o) != 0) { FT_Outline_Done(cx.lib, &o); return false; }
    for (int i = 0; i < o.n_points; ++i) {
        const double x = o.points[i].x / 64.0, y = o.points[i].y / 64.0;
        const double gx = m.a * x + m.c * y + m.e - cx.ox;
        const double gy = m.b * x + m.d * y + m.f - cx.oy;
        o.points[i].x = (FT_Pos)std::lround(gx * 64.0);
        o.points[i].y = (FT_Pos)std::lround(gy * 64.0);
    }
    FT_Bitmap bm;
    bm.rows = (unsigned)cx.H;
    bm.width = (unsigned)cx.W;
    bm.pitch = cx.W;
    bm.buffer = out.data();
    bm.pixel_mode = FT_PIXEL_MODE_GRAY;
    bm.num_grays = 256;
    bm.palette_mode = 0;
    bm.palette = nullptr;
    const int err = FT_Outline_Get_Bitmap(cx.lib, &o, &bm);
    FT_Outline_Done(cx.lib, &o);
    return err == 0;
}

// Porter-Duff and a few blend modes; both colours premultiplied.
Rgba composite(const Rgba& s, const Rgba& d, FT_Composite_Mode mode) {
    Rgba o;
    auto pd = [&](float fs, float fd) {
        o.r = s.r * fs + d.r * fd; o.g = s.g * fs + d.g * fd; o.b = s.b * fs + d.b * fd; o.a = s.a * fs + d.a * fd;
    };
    switch (mode) {
        case FT_COLR_COMPOSITE_CLEAR: pd(0, 0); break;
        case FT_COLR_COMPOSITE_SRC: pd(1, 0); break;
        case FT_COLR_COMPOSITE_DEST: pd(0, 1); break;
        case FT_COLR_COMPOSITE_SRC_OVER: pd(1, 1 - s.a); break;
        case FT_COLR_COMPOSITE_DEST_OVER: pd(1 - d.a, 1); break;
        case FT_COLR_COMPOSITE_SRC_IN: pd(d.a, 0); break;
        case FT_COLR_COMPOSITE_DEST_IN: pd(0, s.a); break;
        case FT_COLR_COMPOSITE_SRC_OUT: pd(1 - d.a, 0); break;
        case FT_COLR_COMPOSITE_DEST_OUT: pd(0, 1 - s.a); break;
        case FT_COLR_COMPOSITE_SRC_ATOP: pd(d.a, 1 - s.a); break;
        case FT_COLR_COMPOSITE_DEST_ATOP: pd(1 - d.a, s.a); break;
        case FT_COLR_COMPOSITE_XOR: pd(1 - d.a, 1 - s.a); break;
        case FT_COLR_COMPOSITE_PLUS:
            o.r = std::min(1.f, s.r + d.r); o.g = std::min(1.f, s.g + d.g);
            o.b = std::min(1.f, s.b + d.b); o.a = std::min(1.f, s.a + d.a);
            break;
        case FT_COLR_COMPOSITE_SCREEN:
            o.r = s.r + d.r - s.r * d.r; o.g = s.g + d.g - s.g * d.g;
            o.b = s.b + d.b - s.b * d.b; o.a = s.a + d.a - s.a * d.a;
            break;
        case FT_COLR_COMPOSITE_MULTIPLY:
            o.r = s.r * (1 - d.a) + d.r * (1 - s.a) + s.r * d.r;
            o.g = s.g * (1 - d.a) + d.g * (1 - s.a) + s.g * d.g;
            o.b = s.b * (1 - d.a) + d.b * (1 - s.a) + s.b * d.b;
            o.a = s.a + d.a - s.a * d.a;
            break;
        default: pd(1, 1 - s.a); break;  // the other blend modes: plain "over"
    }
    return o;
}

bool paintNode(Ctx& cx, FT_OpaquePaint op, const Aff& m, const uint8_t* mask, Buf& dst);

bool paintChild(Ctx& cx, FT_OpaquePaint op, const Aff& m, const uint8_t* mask, Buf& dst) {
    if (++cx.depth > 24) { cx.failed = true; --cx.depth; return false; }
    const bool ok = paintNode(cx, op, m, mask, dst);
    --cx.depth;
    return ok;
}

bool paintNode(Ctx& cx, FT_OpaquePaint op, const Aff& m, const uint8_t* mask, Buf& dst) {
    if (++cx.nodes > 6000) { cx.failed = true; return false; }
    FT_COLR_Paint p;
    if (!FT_Get_Paint(cx.face, op, &p)) { cx.failed = true; return false; }
    const double s = cx.s;
    const double u = cx.s / 65536.0;  // gradient points / radii come as 16.16 font units
    switch (p.format) {
        case FT_COLR_PAINTFORMAT_COLR_LAYERS: {
            FT_LayerIterator it = p.u.colr_layers.layer_iterator;
            FT_OpaquePaint lp;
            int guard = 0;
            while (FT_Get_Paint_Layers(cx.face, &it, &lp)) {
                if (++guard > 512) { cx.failed = true; break; }
                if (!paintChild(cx, lp, m, mask, dst)) return false;
            }
            return true;
        }
        case FT_COLR_PAINTFORMAT_SOLID: {
            const Rgba c = paletteColor(cx, p.u.solid.color.palette_index, (double)p.u.solid.color.alpha / 16384.0);
            fillWith(cx, mask, dst, [&](int, int) { return c; });
            return true;
        }
        case FT_COLR_PAINTFORMAT_LINEAR_GRADIENT: {
            std::vector<Stop> st;
            if (!readStops(cx, p.u.linear_gradient.colorline, st)) return true;
            const FT_PaintExtend ext = p.u.linear_gradient.colorline.extend;
            Mapper mp; mp.cx = &cx;
            if (!invert(m, mp.inv)) return true;
            const double x0 = p.u.linear_gradient.p0.x * u, y0 = p.u.linear_gradient.p0.y * u;
            const double x1 = p.u.linear_gradient.p1.x * u, y1 = p.u.linear_gradient.p1.y * u;
            const double x2 = p.u.linear_gradient.p2.x * u, y2 = p.u.linear_gradient.p2.y * u;
            // The gradient runs along p0->p1 projected on the normal of p0->p2.
            const double nx = -(y2 - y0), ny = x2 - x0;
            const double nn = nx * nx + ny * ny;
            double dx = x1 - x0, dy = y1 - y0;
            if (nn > 1e-12) {
                const double k = (dx * nx + dy * ny) / nn;
                dx = k * nx; dy = k * ny;
            }
            const double dd = dx * dx + dy * dy;
            if (dd < 1e-12) {  // degenerate: the last colour for pad, nothing otherwise
                const Rgba c = st.back().c;
                if (ext == FT_COLR_PAINT_EXTEND_PAD) fillWith(cx, mask, dst, [&](int, int) { return c; });
                return true;
            }
            fillWith(cx, mask, dst, [&](int x, int y) {
                double px, py;
                mp.at(x, y, px, py);
                return evalStops(st, ((px - x0) * dx + (py - y0) * dy) / dd, ext);
            });
            return true;
        }
        case FT_COLR_PAINTFORMAT_RADIAL_GRADIENT: {
            std::vector<Stop> st;
            if (!readStops(cx, p.u.radial_gradient.colorline, st)) return true;
            const FT_PaintExtend ext = p.u.radial_gradient.colorline.extend;
            Mapper mp; mp.cx = &cx;
            if (!invert(m, mp.inv)) return true;
            const double c0x = p.u.radial_gradient.c0.x * u, c0y = p.u.radial_gradient.c0.y * u;
            const double c1x = p.u.radial_gradient.c1.x * u, c1y = p.u.radial_gradient.c1.y * u;
            const double r0 = p.u.radial_gradient.r0 * u, r1 = p.u.radial_gradient.r1 * u;
            const double cdx = c1x - c0x, cdy = c1y - c0y, dr = r1 - r0;
            const double A = cdx * cdx + cdy * cdy - dr * dr;
            fillWith(cx, mask, dst, [&](int x, int y) {
                double px, py;
                mp.at(x, y, px, py);
                const double pdx = px - c0x, pdy = py - c0y;
                const double B = pdx * cdx + pdy * cdy + r0 * dr;
                const double C = pdx * pdx + pdy * pdy - r0 * r0;
                double t = 0;
                bool ok = false;
                if (std::fabs(A) < 1e-9) {
                    if (std::fabs(B) > 1e-12) { t = C / (2 * B); ok = (r0 + t * dr) >= 0; }
                } else {
                    const double disc = B * B - A * C;
                    if (disc >= 0) {
                        const double sq = std::sqrt(disc);
                        const double t1 = (B + sq) / A, t2 = (B - sq) / A;
                        const double hi = std::max(t1, t2), lo = std::min(t1, t2);
                        if (r0 + hi * dr >= 0) { t = hi; ok = true; }
                        else if (r0 + lo * dr >= 0) { t = lo; ok = true; }
                    }
                }
                if (!ok) return Rgba();
                return evalStops(st, t, ext);
            });
            return true;
        }
        case FT_COLR_PAINTFORMAT_SWEEP_GRADIENT: {
            std::vector<Stop> st;
            if (!readStops(cx, p.u.sweep_gradient.colorline, st)) return true;
            const FT_PaintExtend ext = p.u.sweep_gradient.colorline.extend;
            Mapper mp; mp.cx = &cx;
            if (!invert(m, mp.inv)) return true;
            const double ccx = p.u.sweep_gradient.center.x * u, ccy = p.u.sweep_gradient.center.y * u;
            // Angles come as 16.16 with 1.0 = 180 degrees; the sweep goes counter-clockwise from the +x axis.
            const double a0 = p.u.sweep_gradient.start_angle / 65536.0 * kPi;
            const double a1 = p.u.sweep_gradient.end_angle / 65536.0 * kPi;
            const double span = a1 - a0;
            if (std::fabs(span) < 1e-9) return true;
            fillWith(cx, mask, dst, [&](int x, int y) {
                double px, py;
                mp.at(x, y, px, py);
                double ang = std::atan2(py - ccy, px - ccx);
                if (ang < 0) ang += 2 * kPi;
                double t = (ang - a0) / span;
                return evalStops(st, t, ext);
            });
            return true;
        }
        case FT_COLR_PAINTFORMAT_GLYPH: {
            std::vector<uint8_t> gm;
            if (!glyphMask(cx, p.u.glyph.glyphID, m, gm)) { cx.failed = true; return false; }
            if (mask) for (size_t i = 0; i < gm.size(); ++i) gm[i] = (uint8_t)((gm[i] * mask[i] + 127) / 255);
            return paintChild(cx, p.u.glyph.paint, m, gm.data(), dst);
        }
        case FT_COLR_PAINTFORMAT_COLR_GLYPH: {
            FT_OpaquePaint rp;
            if (!FT_Get_Color_Glyph_Paint(cx.face, p.u.colr_glyph.glyphID, FT_COLOR_NO_ROOT_TRANSFORM, &rp)) return true;
            return paintChild(cx, rp, m, mask, dst);
        }
        case FT_COLR_PAINTFORMAT_TRANSFORM: {
            const FT_Affine23& t = p.u.transform.affine;
            Aff a;
            a.a = t.xx / 65536.0; a.b = t.yx / 65536.0; a.c = t.xy / 65536.0; a.d = t.yy / 65536.0;
            a.e = t.dx / 65536.0 * s; a.f = t.dy / 65536.0 * s;
            return paintChild(cx, p.u.transform.paint, mul(m, a), mask, dst);
        }
        case FT_COLR_PAINTFORMAT_TRANSLATE: {
            const Aff a = translation(p.u.translate.dx / 65536.0 * s, p.u.translate.dy / 65536.0 * s);
            return paintChild(cx, p.u.translate.paint, mul(m, a), mask, dst);
        }
        case FT_COLR_PAINTFORMAT_SCALE: {
            Aff a; a.a = p.u.scale.scale_x / 65536.0; a.d = p.u.scale.scale_y / 65536.0;
            a = around(p.u.scale.center_x / 65536.0 * s, p.u.scale.center_y / 65536.0 * s, a);
            return paintChild(cx, p.u.scale.paint, mul(m, a), mask, dst);
        }
        case FT_COLR_PAINTFORMAT_ROTATE: {
            const double ang = p.u.rotate.angle / 65536.0 * kPi;  // counter-clockwise, y up
            Aff a; a.a = std::cos(ang); a.b = std::sin(ang); a.c = -std::sin(ang); a.d = std::cos(ang);
            a = around(p.u.rotate.center_x / 65536.0 * s, p.u.rotate.center_y / 65536.0 * s, a);
            return paintChild(cx, p.u.rotate.paint, mul(m, a), mask, dst);
        }
        case FT_COLR_PAINTFORMAT_SKEW: {
            const double xa = p.u.skew.x_skew_angle / 65536.0 * kPi, ya = p.u.skew.y_skew_angle / 65536.0 * kPi;
            Aff a; a.b = std::tan(ya); a.c = std::tan(-xa);
            a = around(p.u.skew.center_x / 65536.0 * s, p.u.skew.center_y / 65536.0 * s, a);
            return paintChild(cx, p.u.skew.paint, mul(m, a), mask, dst);
        }
        case FT_COLR_PAINTFORMAT_COMPOSITE: {
            if (cx.depth > 8) { cx.failed = true; return false; }  // two canvases per level: keep memory small
            Buf back((size_t)cx.W * cx.H), src((size_t)cx.W * cx.H);
            if (!paintChild(cx, p.u.composite.backdrop_paint, m, nullptr, back)) return false;
            if (!paintChild(cx, p.u.composite.source_paint, m, nullptr, src)) return false;
            for (size_t i = 0; i < back.size(); ++i) back[i] = composite(src[i], back[i], p.u.composite.composite_mode);
            for (size_t i = 0; i < back.size(); ++i) {
                const float k = mask ? mask[i] / 255.f : 1.f;
                if (k <= 0.f) continue;
                const Rgba& c = back[i];
                Rgba& d = dst[i];
                const float inv = 1.f - c.a * k;
                d.r = c.r * k + d.r * inv; d.g = c.g * k + d.g * inv; d.b = c.b * k + d.b * inv; d.a = c.a * k + d.a * inv;
            }
            return true;
        }
        default:
            return true;  // unknown paint: skipped
    }
}

}  // namespace

bool colrFaceUsable(FT_Face face) {
    if (!face || !FT_IS_SCALABLE(face) || !FT_HAS_COLOR(face)) return false;
    FT_Palette_Data pd;
    if (FT_Palette_Data_Get(face, &pd) != 0) return false;
    return pd.num_palettes > 0;
}

bool colrRender(FT_Library lib, FT_Face face, uint32_t gid, int ppem, ColrImage& out) {
    if (!lib || !colrFaceUsable(face) || ppem < 4 || ppem > 512) return false;
    if (FT_Set_Char_Size(face, 0, (FT_F26Dot6)ppem * 64, 72, 72) != 0) return false;
    const double upem = face->units_per_EM ? (double)face->units_per_EM : 1000.0;

    Ctx cx;
    cx.lib = lib;
    cx.face = face;
    cx.s = (double)ppem / upem;
    FT_Color* pal = nullptr;
    if (FT_Palette_Select(face, 0, &pal) != 0 || !pal) return false;
    FT_Palette_Data pd;
    if (FT_Palette_Data_Get(face, &pd) != 0) return false;
    cx.pal = pal;
    cx.palCount = (int)pd.num_palette_entries;

    FT_OpaquePaint root;
    root.p = nullptr;
    root.insert_root_transform = 0;
    const bool v1 = FT_Get_Color_Glyph_Paint(face, gid, FT_COLOR_NO_ROOT_TRANSFORM, &root) != 0;

    // Layers of a v0 glyph.
    struct Layer { uint32_t glyph; uint32_t color; };
    std::vector<Layer> v0;
    if (!v1) {
        FT_LayerIterator it;
        it.p = nullptr;
        FT_UInt lg = 0, lc = 0;
        while (FT_Get_Color_Glyph_Layer(face, gid, &lg, &lc, &it)) {
            v0.push_back({lg, lc});
            if (v0.size() > 256) break;
        }
        if (v0.empty()) return false;
    }

    // Canvas: the clip box of the glyph when the font has one, else a box around the em square.
    double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    bool haveBox = false;
    if (v1) {
        FT_ClipBox cb;
        if (FT_Get_Color_Glyph_ClipBox(face, gid, &cb)) {
            const FT_Vector pts[4] = {cb.bottom_left, cb.top_left, cb.top_right, cb.bottom_right};
            x0 = x1 = pts[0].x / 64.0; y0 = y1 = pts[0].y / 64.0;
            for (int i = 1; i < 4; ++i) {
                x0 = std::min(x0, pts[i].x / 64.0); x1 = std::max(x1, pts[i].x / 64.0);
                y0 = std::min(y0, pts[i].y / 64.0); y1 = std::max(y1, pts[i].y / 64.0);
            }
            // Sanity: a clip box in pixels is about the size of the em square.
            haveBox = (x1 - x0) >= 0.3 * ppem && (x1 - x0) <= 4.0 * ppem && (y1 - y0) >= 0.3 * ppem && (y1 - y0) <= 4.0 * ppem;
        }
    } else {
        bool any = false;
        for (const Layer& l : v0) {
            if (FT_Load_Glyph(face, l.glyph, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP) != 0) continue;
            if (face->glyph->format != FT_GLYPH_FORMAT_OUTLINE || face->glyph->outline.n_points <= 0) continue;
            FT_BBox bb;
            FT_Outline_Get_CBox(&face->glyph->outline, &bb);
            if (!any) { x0 = bb.xMin / 64.0; x1 = bb.xMax / 64.0; y0 = bb.yMin / 64.0; y1 = bb.yMax / 64.0; any = true; }
            else {
                x0 = std::min(x0, bb.xMin / 64.0); x1 = std::max(x1, bb.xMax / 64.0);
                y0 = std::min(y0, bb.yMin / 64.0); y1 = std::max(y1, bb.yMax / 64.0);
            }
        }
        haveBox = any;
    }
    if (!haveBox) { x0 = -0.2 * ppem; x1 = 1.6 * ppem; y0 = -0.4 * ppem; y1 = 1.2 * ppem; }
    const double pad = 2.0;
    const int ix0 = (int)std::floor(x0 - pad), iy0 = (int)std::floor(y0 - pad);
    const int ix1 = (int)std::ceil(x1 + pad), iy1 = (int)std::ceil(y1 + pad);
    cx.W = ix1 - ix0;
    cx.H = iy1 - iy0;
    if (cx.W <= 0 || cx.H <= 0 || cx.W > 1400 || cx.H > 1400) return false;
    cx.ox = ix0;
    cx.oy = iy0;

    Buf canvas((size_t)cx.W * cx.H);
    Aff ident;
    if (v1) {
        if (!paintNode(cx, root, ident, nullptr, canvas) || cx.failed) return false;
    } else {
        for (const Layer& l : v0) {
            std::vector<uint8_t> gm;
            if (!glyphMask(cx, l.glyph, ident, gm)) continue;
            const Rgba c = paletteColor(cx, l.color, 1.0);
            fillWith(cx, gm.data(), canvas, [&](int, int) { return c; });
        }
    }

    // Crop to what was painted.
    int minX = cx.W, minY = cx.H, maxX = -1, maxY = -1;
    for (int y = 0; y < cx.H; ++y)
        for (int x = 0; x < cx.W; ++x)
            if (canvas[(size_t)y * cx.W + x].a > 0.002f) {
                minX = std::min(minX, x); maxX = std::max(maxX, x);
                minY = std::min(minY, y); maxY = std::max(maxY, y);
            }
    if (maxX < minX || maxY < minY) return false;
    out.w = maxX - minX + 1;
    out.h = maxY - minY + 1;
    out.bx = ix0 + minX;
    out.by = iy0 + (cx.H - minY);  // pixels from the baseline up to the top row
    out.px.assign((size_t)out.w * out.h * 4, 0);
    for (int y = 0; y < out.h; ++y) {
        for (int x = 0; x < out.w; ++x) {
            const Rgba& c = canvas[(size_t)(y + minY) * cx.W + (x + minX)];
            uint8_t* d = &out.px[((size_t)y * out.w + x) * 4];
            auto q = [](float v) { return (uint8_t)std::lround(std::min(1.f, std::max(0.f, v)) * 255.f); };
            d[3] = q(c.a);
            d[0] = std::min(d[3], q(c.r));
            d[1] = std::min(d[3], q(c.g));
            d[2] = std::min(d[3], q(c.b));
        }
    }
    return true;
}

}  // namespace text
}  // namespace am
