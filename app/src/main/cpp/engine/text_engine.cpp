#include "text_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>

#include "text_blit.h"
#include "text_layout.h"
#include "outline_trim.h"

#ifndef AM_TEXT_ENGINE
#define AM_TEXT_ENGINE 0
#endif

#ifdef __ANDROID__
#include <android/log.h>
#include <dlfcn.h>
#include "image_loader.h"
#define TLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AmText", __VA_ARGS__)
#else
#define TLOGE(...) ((void)0)
#endif

namespace am {
namespace text {

#if !AM_TEXT_ENGINE

bool available() { return false; }
bool emojiAvailable() { return false; }
void setPngDecoderForTest(PngDecoder) {}
bool colorGlyphForTest(const std::string&, uint32_t, float, int*, int*, int*, int*, int*, std::vector<uint8_t>*) { return false; }
void setMatcherForTest(Matcher) {}
bool draw(uint8_t*, int, int, int, const Request&, Stats*, std::string* err) {
    if (err) *err = "text engine not built (FreeType / HarfBuzz sources missing)";
    return false;
}

#else  // ---------------------------------------------------------------------------------------

}  // namespace text
}  // namespace am

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_GLYPH_H
#include FT_OUTLINE_H
#include FT_STROKER_H
#include FT_MULTIPLE_MASTERS_H
#include FT_TRUETYPE_TABLES_H
#include <hb-ot.h>
#include <hb.h>

#include "colr_render.h"

#include <tuple>

namespace am {
namespace text {

namespace {

std::mutex g_mu;
FT_Library g_lib = nullptr;
Matcher g_testMatcher;
PngDecoder g_testPng;

// ---------------------------------------------------------------- system font matcher (API 29+)
#ifdef __ANDROID__
struct FontApi {
    bool ok = false;
    void* (*create)() = nullptr;
    void (*destroy)(void*) = nullptr;
    void (*setStyle)(void*, uint16_t, bool) = nullptr;
    void* (*match)(const void*, const char*, const uint16_t*, uint32_t, uint32_t*) = nullptr;
    void (*closeFont)(void*) = nullptr;
    const char* (*path)(const void*) = nullptr;
    uint16_t (*weight)(const void*) = nullptr;
    bool (*italic)(const void*) = nullptr;
    size_t (*collection)(const void*) = nullptr;
    size_t (*axisCount)(const void*) = nullptr;
    uint32_t (*axisTag)(const void*, uint32_t) = nullptr;
    float (*axisValue)(const void*, uint32_t) = nullptr;

    FontApi() {
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return;
        auto S = [&](const char* n) { return dlsym(lib, n); };
        create = reinterpret_cast<decltype(create)>(S("AFontMatcher_create"));
        destroy = reinterpret_cast<decltype(destroy)>(S("AFontMatcher_destroy"));
        setStyle = reinterpret_cast<decltype(setStyle)>(S("AFontMatcher_setStyle"));
        match = reinterpret_cast<decltype(match)>(S("AFontMatcher_match"));
        closeFont = reinterpret_cast<decltype(closeFont)>(S("AFont_close"));
        path = reinterpret_cast<decltype(path)>(S("AFont_getFontFilePath"));
        weight = reinterpret_cast<decltype(weight)>(S("AFont_getWeight"));
        italic = reinterpret_cast<decltype(italic)>(S("AFont_isItalic"));
        collection = reinterpret_cast<decltype(collection)>(S("AFont_getCollectionIndex"));
        axisCount = reinterpret_cast<decltype(axisCount)>(S("AFont_getAxisCount"));
        axisTag = reinterpret_cast<decltype(axisTag)>(S("AFont_getAxisTag"));
        axisValue = reinterpret_cast<decltype(axisValue)>(S("AFont_getAxisValue"));
        ok = create && destroy && setStyle && match && closeFont && path && weight && italic && collection &&
             axisCount && axisTag && axisValue;
    }
};

bool androidMatch(const std::string& family, int weight, bool italic, const uint16_t* text, uint32_t len,
                  MatchResult& out) {
    static const FontApi api;
    if (!api.ok) return false;
    void* m = api.create();
    if (!m) return false;
    api.setStyle(m, (uint16_t)std::max(1, std::min(1000, weight)), italic);
    uint32_t run = 0;
    void* f = api.match(m, family.c_str(), text, len, &run);
    bool ok = false;
    if (f) {
        const char* p = api.path(f);
        if (p) {
            out.path = p;
            out.index = (int)api.collection(f);
            out.weight = api.weight(f);
            out.italic = api.italic(f);
            out.runLen = run;
            out.axes.clear();
            const size_t n = api.axisCount(f);
            for (size_t i = 0; i < n && i < 16; ++i) out.axes.emplace_back(api.axisTag(f, (uint32_t)i), api.axisValue(f, (uint32_t)i));
            ok = true;
        }
        api.closeFont(f);
    }
    api.destroy(m);
    return ok;
}
#endif

bool matchFont(const std::string& family, int weight, bool italic, const uint16_t* text, uint32_t len,
               MatchResult& out) {
    if (g_testMatcher) return g_testMatcher(family, weight, italic, text, len, out);
#ifdef __ANDROID__
    return androidMatch(family, weight, italic, text, len, out);
#else
    return false;
#endif
}

// ---------------------------------------------------------------- faces
// One bitmap size ("strike") of a colour bitmap font, from the 'CBLC' table.
struct CbStrike {
    uint32_t arrayOff = 0, numSub = 0;
    uint32_t first = 0, last = 0;  // glyph range
    int ppem = 0, depth = 0;
};

struct Face {
    FT_Face ft = nullptr;
    hb_blob_t* blob = nullptr;
    hb_face_t* hbFace = nullptr;
    hb_font_t* hb = nullptr;
    int weight = 400;
    bool italic = false;
    int sizeSet = -1;  // last FT_Set_Char_Size value (26.6)
    // Colour bitmap font (Android's emoji font): no outlines, every glyph is a PNG picture (see "colour bitmap fonts").
    bool color = false;
    std::vector<uint8_t> cblc;
    std::vector<CbStrike> strikes;
    unsigned long cbdtSize = 0;
    int hheaAsc = 0, hheaDesc = 0, upem = 0;
    // Colour outline font (COLR v0 / v1): has outlines like any font, its glyphs are drawn by colr_render.cpp, but only
    // when the font was picked as the emoji font (colrEmoji), so ordinary text in a COLR font keeps its old look.
    bool colr = false;
    bool colrEmoji = false;
    int colrChecked = 0;  // emojiReadyLocked result for this face: 0 unknown, 1 works, -1 does not
};
std::map<std::string, std::unique_ptr<Face>> g_faces;


// ---------------------------------------------------------------- colour bitmap fonts (CBLC / CBDT)
// Android's emoji font (Noto Color Emoji and the OEM copies of it) has no outlines: every glyph is a PNG picture inside
// the 'CBDT' table, found through the index in 'CBLC'. FreeType is built here without libpng, so the two tables are read
// by hand and the PNG is decoded by the system (AImageDecoder, Android 11+).
inline uint32_t be16(const std::vector<uint8_t>& d, size_t p) { return p + 2 <= d.size() ? ((uint32_t)d[p] << 8 | d[p + 1]) : 0u; }
inline uint32_t be32(const std::vector<uint8_t>& d, size_t p) {
    return p + 4 <= d.size() ? ((uint32_t)d[p] << 24 | (uint32_t)d[p + 1] << 16 | (uint32_t)d[p + 2] << 8 | d[p + 3]) : 0u;
}

bool loadColorTables(Face& f) {
    FT_ULong n = 0;
    if (FT_Load_Sfnt_Table(f.ft, FT_MAKE_TAG('C', 'B', 'L', 'C'), 0, nullptr, &n) != 0 || n < 8 || n > (8u << 20)) return false;
    f.cblc.resize(n);
    if (FT_Load_Sfnt_Table(f.ft, FT_MAKE_TAG('C', 'B', 'L', 'C'), 0, f.cblc.data(), &n) != 0) return false;
    f.cblc.resize(n);
    FT_ULong dn = 0;
    if (FT_Load_Sfnt_Table(f.ft, FT_MAKE_TAG('C', 'B', 'D', 'T'), 0, nullptr, &dn) != 0 || dn < 4) return false;
    f.cbdtSize = dn;
    const uint32_t num = be32(f.cblc, 4);
    if (num == 0 || num > 64 || 8 + (size_t)num * 48 > f.cblc.size()) return false;
    for (uint32_t i = 0; i < num; ++i) {  // BitmapSize records, 48 bytes each
        const size_t o = 8 + (size_t)i * 48;
        CbStrike s;
        s.arrayOff = be32(f.cblc, o);
        s.numSub = be32(f.cblc, o + 8);
        s.first = be16(f.cblc, o + 40);
        s.last = be16(f.cblc, o + 42);
        s.ppem = f.cblc[o + 45];
        s.depth = f.cblc[o + 46];
        if (s.numSub == 0 || s.numSub > 4096 || s.ppem <= 0) continue;
        f.strikes.push_back(s);
    }
    if (f.strikes.empty()) return false;
    auto* hh = (TT_HoriHeader*)FT_Get_Sfnt_Table(f.ft, FT_SFNT_HHEA);
    auto* hd = (TT_Header*)FT_Get_Sfnt_Table(f.ft, FT_SFNT_HEAD);
    if (hh && hd && hd->Units_Per_EM > 0) {
        f.hheaAsc = hh->Ascender;
        f.hheaDesc = hh->Descender;
        f.upem = hd->Units_Per_EM;
    }
    f.color = true;
    return true;
}

// Where the picture of a glyph is inside 'CBDT' (and its metrics when they are kept in the index).
struct CbImage {
    uint32_t off = 0, len = 0;
    int format = 0;
    int w = 0, h = 0, bx = 0, by = 0;  // only from index format 2
};

bool cbFind(const Face& f, const CbStrike& s, uint32_t gid, CbImage& out) {
    if (gid < s.first || gid > s.last) return false;
    const std::vector<uint8_t>& d = f.cblc;
    for (uint32_t i = 0; i < s.numSub; ++i) {  // IndexSubTableArray entries, 8 bytes each
        const size_t e = (size_t)s.arrayOff + (size_t)i * 8;
        if (e + 8 > d.size()) return false;
        const uint32_t first = be16(d, e), last = be16(d, e + 2);
        if (gid < first || gid > last) continue;
        const size_t st = (size_t)s.arrayOff + be32(d, e + 4);
        if (st + 8 > d.size()) return false;
        const uint32_t indexFormat = be16(d, st), imageFormat = be16(d, st + 2), dataOff = be32(d, st + 4);
        const uint32_t idx = gid - first;
        uint32_t o0 = 0, o1 = 0;
        if (indexFormat == 1) {
            const size_t p = st + 8 + (size_t)idx * 4;
            if (p + 8 > d.size()) return false;
            o0 = be32(d, p); o1 = be32(d, p + 4);
        } else if (indexFormat == 3) {
            const size_t p = st + 8 + (size_t)idx * 2;
            if (p + 4 > d.size()) return false;
            o0 = be16(d, p); o1 = be16(d, p + 2);
        } else if (indexFormat == 2) {
            if (st + 20 > d.size()) return false;
            const uint32_t size = be32(d, st + 8);
            o0 = size * idx; o1 = o0 + size;
            out.h = d[st + 12]; out.w = d[st + 13];
            out.bx = (int8_t)d[st + 14]; out.by = (int8_t)d[st + 15];
        } else {
            return false;  // sparse index formats (4, 5): not used by the emoji fonts
        }
        if (o1 <= o0) return false;  // this glyph has no picture
        out.off = dataOff + o0;
        out.len = o1 - o0;
        out.format = (int)imageFormat;
        return true;
    }
    return false;
}

// The colour strike to use for text of [wantPpem] pixels: the smallest picture that is at least that big (it is only
// ever scaled down, which stays sharp), else the biggest one.
int pickStrike(const Face& f, float wantPpem) {
    int best = -1;
    for (size_t i = 0; i < f.strikes.size(); ++i) {
        const CbStrike& s = f.strikes[i];
        if (s.depth != 32) continue;  // PNG strikes are 32 bit
        if (best < 0) { best = (int)i; continue; }
        const int bp = f.strikes[(size_t)best].ppem;
        const bool curOk = (float)s.ppem >= wantPpem, bestOk = (float)bp >= wantPpem;
        if ((curOk && !bestOk) || (curOk && bestOk && s.ppem < bp) || (!curOk && !bestOk && s.ppem > bp)) best = (int)i;
    }
    return best;
}

struct ColorGlyph {
    int w = 0, h = 0, bx = 0, by = 0, ppem = 0;  // picture size, bearing (px of the strike), strike size
    std::vector<uint8_t> px;                       // premultiplied RGBA8888
};
// A null entry = the glyph has no picture (a space, for example) or it could not be decoded.
std::map<std::tuple<const Face*, uint32_t, int>, std::unique_ptr<ColorGlyph>> g_colorGlyphs;

bool pngDecode(const uint8_t* d, size_t n, std::vector<uint8_t>& px, int& w, int& h) {
    if (g_testPng) return g_testPng(d, n, px, w, h);
#ifdef __ANDROID__
    return ::am::decodeEncodedRgba(d, n, &px, &w, &h);
#else
    return false;
#endif
}

bool pngDecoderPresent() {
    if (g_testPng) return true;
#ifdef __ANDROID__
    return ::am::encodedDecoderAvailable();
#else
    return false;
#endif
}

// Size (pixels per em) a COLR emoji is drawn at: the smallest of a few steps that is at least [want] (it is only ever
// scaled down when drawn, which stays sharp).
int colrPpemFor(float want) {
    static const int steps[] = {40, 64, 96, 160};
    for (int s : steps) if ((float)s >= want) return s;
    return steps[3];
}

const ColorGlyph* colorGlyph(Face& f, uint32_t gid, float wantPpem) {
    if (f.colr) {
        const int pp = colrPpemFor(wantPpem);
        const auto ckey = std::make_tuple((const Face*)&f, gid, -pp);  // negative: never clashes with a strike index
        auto cit = g_colorGlyphs.find(ckey);
        if (cit != g_colorGlyphs.end()) return cit->second.get();
        if (g_colorGlyphs.size() >= 192) g_colorGlyphs.clear();
        std::unique_ptr<ColorGlyph> cout;
        ColrImage im;
        if (colrRender(g_lib, f.ft, gid, pp, im) && im.w > 0 && im.h > 0) {
            auto g = std::make_unique<ColorGlyph>();
            g->w = im.w; g->h = im.h; g->bx = im.bx; g->by = im.by; g->ppem = pp;
            g->px = std::move(im.px);
            cout = std::move(g);
        }
        f.sizeSet = -1;  // colrRender changed the FreeType size of the face
        const ColorGlyph* craw = cout.get();
        g_colorGlyphs[ckey] = std::move(cout);
        return craw;
    }
    const int si = pickStrike(f, wantPpem);
    if (si < 0) return nullptr;
    const auto key = std::make_tuple((const Face*)&f, gid, si);
    auto it = g_colorGlyphs.find(key);
    if (it != g_colorGlyphs.end()) return it->second.get();
    if (g_colorGlyphs.size() >= 192) g_colorGlyphs.clear();  // ~70 KB each: a few MB at most
    std::unique_ptr<ColorGlyph> out;
    CbImage im;
    if (cbFind(f, f.strikes[(size_t)si], gid, im) && im.len >= 4 && im.len <= (4u << 20) &&
        (unsigned long)im.off + im.len <= f.cbdtSize) {
        std::vector<uint8_t> buf(im.len);
        FT_ULong n = im.len;
        if (FT_Load_Sfnt_Table(f.ft, FT_MAKE_TAG('C', 'B', 'D', 'T'), im.off, buf.data(), &n) == 0 && n == im.len) {
            size_t at = 0;
            uint32_t dlen = 0;
            int bx = im.bx, by = im.by;
            if (im.format == 17 && n >= 9) {          // small metrics: height width bearingX bearingY advance, length, PNG
                bx = (int8_t)buf[2]; by = (int8_t)buf[3]; dlen = be32(buf, 5); at = 9;
            } else if (im.format == 18 && n >= 12) {  // big metrics (8 bytes), length, PNG
                bx = (int8_t)buf[2]; by = (int8_t)buf[3]; dlen = be32(buf, 8); at = 12;
            } else if (im.format == 19 && n >= 4) {   // metrics are in the index, length, PNG
                dlen = be32(buf, 0); at = 4;
            }
            if (at && dlen > 0 && (size_t)at + dlen <= n) {
                auto g = std::make_unique<ColorGlyph>();
                int dw = 0, dh = 0;
                if (pngDecode(buf.data() + at, dlen, g->px, dw, dh) && dw > 0 && dh > 0 &&
                    g->px.size() >= (size_t)dw * dh * 4) {
                    g->w = dw; g->h = dh; g->bx = bx; g->by = by;
                    g->ppem = f.strikes[(size_t)si].ppem;
                    out = std::move(g);
                }
            }
        }
    }
    const ColorGlyph* raw = out.get();
    g_colorGlyphs[key] = std::move(out);
    return raw;
}

// Half-size copy (2x2 average): keeps big emoji pictures sharp when they are drawn small.
void halveImage(Image& im) {
    const int nw = (im.w + 1) / 2, nh = (im.h + 1) / 2;
    std::vector<uint8_t> out((size_t)nw * nh * 4);
    for (int y = 0; y < nh; ++y) {
        for (int x = 0; x < nw; ++x) {
            const int x1 = std::min(im.w - 1, 2 * x + 1), y1 = std::min(im.h - 1, 2 * y + 1);
            for (int k = 0; k < 4; ++k) {
                const int sum = im.px[((size_t)(2 * y) * im.w + 2 * x) * 4 + k] + im.px[((size_t)(2 * y) * im.w + x1) * 4 + k] +
                                im.px[((size_t)y1 * im.w + 2 * x) * 4 + k] + im.px[((size_t)y1 * im.w + x1) * 4 + k];
                out[((size_t)y * nw + x) * 4 + k] = (uint8_t)((sum + 2) / 4);
            }
        }
    }
    im.w = nw; im.h = nh; im.px.swap(out);
}

// Draws the picture of glyph [gid] with its origin (pen, baseline) at (x, y) into [dst], for text of [px] pixels.
bool drawColorGlyph(Face& f, uint32_t gid, float px, float x, float y, Image& dst) {
    const ColorGlyph* g = colorGlyph(f, gid, px);
    if (!g) return false;
    const float s0 = px / (float)g->ppem;
    Image src;
    src.w = g->w; src.h = g->h; src.px = g->px;
    float sc = s0;
    while (sc < 0.5f && src.w > 2 && src.h > 2) { halveImage(src); sc *= 2.f; }
    BlitParams bp;
    bp.srcToDst.a = sc;
    bp.srcToDst.d = sc;
    bp.srcToDst.e = x + (float)g->bx * s0;
    bp.srcToDst.f = y - (float)g->by * s0;
    bp.clampEdges = true;
    blit(dst.px.data(), dst.w, dst.h, dst.w * 4, src, bp);
    return true;
}

bool ensureLib() {
    if (g_lib) return true;
    return FT_Init_FreeType(&g_lib) == 0;
}

Face* loadFace(const std::string& path, int index, const std::vector<std::pair<uint32_t, float>>& axes,
               int weightHint, bool italicHint) {
    if (path.empty()) return nullptr;
    std::string key = path + "#" + std::to_string(index);
    for (auto& a : axes) key += ":" + std::to_string(a.first) + "=" + std::to_string(a.second);
    auto it = g_faces.find(key);
    if (it != g_faces.end()) return it->second.get();

    auto f = std::make_unique<Face>();
    if (FT_New_Face(g_lib, path.c_str(), index, &f->ft) != 0 || !f->ft) {
        TLOGE("FT_New_Face failed: %s", path.c_str());
        return nullptr;
    }
    if (!FT_IS_SCALABLE(f->ft) && !loadColorTables(*f)) { FT_Done_Face(f->ft); return nullptr; }  // only the emoji font may lack outlines
    FT_Select_Charmap(f->ft, FT_ENCODING_UNICODE);
    f->colr = !f->color && colrFaceUsable(f->ft);

    std::vector<hb_variation_t> vars;
    if (!axes.empty() && (f->ft->face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS)) {
        FT_MM_Var* mm = nullptr;
        if (FT_Get_MM_Var(f->ft, &mm) == 0 && mm) {
            std::vector<FT_Fixed> coords(mm->num_axis);
            for (FT_UInt i = 0; i < mm->num_axis; ++i) {
                float v = (float)mm->axis[i].def / 65536.f;
                for (auto& a : axes) if (a.first == (uint32_t)mm->axis[i].tag) v = a.second;
                coords[i] = (FT_Fixed)std::lround(v * 65536.f);
            }
            FT_Set_Var_Design_Coordinates(f->ft, mm->num_axis, coords.data());
            FT_Done_MM_Var(g_lib, mm);
        }
        for (auto& a : axes) { hb_variation_t v; v.tag = a.first; v.value = a.second; vars.push_back(v); }
    }

    f->blob = hb_blob_create_from_file(path.c_str());
    f->hbFace = f->blob ? hb_face_create(f->blob, (unsigned)std::max(0, index)) : nullptr;
    f->hb = f->hbFace ? hb_font_create(f->hbFace) : nullptr;
    if (!f->hb) {
        FT_Done_Face(f->ft);
        if (f->hbFace) hb_face_destroy(f->hbFace);
        if (f->blob) hb_blob_destroy(f->blob);
        return nullptr;
    }
    hb_ot_font_set_funcs(f->hb);
    if (!vars.empty()) hb_font_set_variations(f->hb, vars.data(), (unsigned)vars.size());

    if (weightHint > 0) {
        f->weight = weightHint;
    } else {
        auto* os2 = (TT_OS2*)FT_Get_Sfnt_Table(f->ft, FT_SFNT_OS2);
        f->weight = (os2 && os2->usWeightClass >= 1) ? os2->usWeightClass : ((f->ft->style_flags & FT_STYLE_FLAG_BOLD) ? 700 : 400);
    }
    f->italic = italicHint || (f->ft->style_flags & FT_STYLE_FLAG_ITALIC) != 0;
    Face* raw = f.get();
    g_faces[key] = std::move(f);
    return raw;
}

void setSize(Face& f, float px) {
    if (f.color) return;  // bitmap font: fixed picture sizes, scaled when drawn
    const int s = (int)std::lround(px * 64.f);
    if (f.sizeSet == s) return;
    FT_Set_Char_Size(f.ft, 0, s, 72, 72);
    f.sizeSet = s;
}

bool hasGlyph(Face& f, uint32_t cp) { return FT_Get_Char_Index(f.ft, cp) != 0; }

void fontMetrics(Face& f, float px, int& ascNeg, int& descPos) {
    if (f.color && f.upem > 0) {  // FreeType reports 0 for a bitmap-only font: read the hhea values
        ascNeg = -(int)std::ceil(f.hheaAsc * px / f.upem);
        descPos = (int)std::ceil(-f.hheaDesc * px / f.upem);
        return;
    }
    const float upem = f.ft->units_per_EM ? (float)f.ft->units_per_EM : 1000.f;
    ascNeg = -(int)std::ceil(f.ft->ascender * px / upem);
    descPos = (int)std::ceil(-f.ft->descender * px / upem);
    if (ascNeg == 0 && descPos == 0) { ascNeg = -(int)std::ceil(px * 0.93f); descPos = (int)std::ceil(px * 0.25f); }
}

// ---------------------------------------------------------------- text helpers
void decodeUtf8(const std::string& s, std::vector<uint32_t>& out) {
    const unsigned char* p = (const unsigned char*)s.data();
    const size_t n = s.size();
    for (size_t i = 0; i < n;) {
        uint32_t c = p[i];
        int len = 1;
        if (c >= 0xF0 && c < 0xF8) { c &= 0x07; len = 4; }
        else if (c >= 0xE0) { c &= 0x0F; len = 3; }
        else if (c >= 0xC0) { c &= 0x1F; len = 2; }
        else if (c >= 0x80) { out.push_back(0xFFFD); ++i; continue; }
        if (i + len > n) { out.push_back(0xFFFD); break; }
        for (int k = 1; k < len; ++k) c = (c << 6) | (p[i + k] & 0x3F);
        out.push_back(c);
        i += len;
    }
}

void toUtf16(uint32_t cp, uint16_t* buf, uint32_t& len) {
    if (cp < 0x10000) { buf[0] = (uint16_t)cp; len = 1; return; }
    cp -= 0x10000;
    buf[0] = (uint16_t)(0xD800 + (cp >> 10));
    buf[1] = (uint16_t)(0xDC00 + (cp & 0x3FF));
    len = 2;
}

// Emoji and pictographs: drawn from the colour emoji font when it is usable (emojiReadyLocked), otherwise Kotlin draws the layer.
bool emojiish(uint32_t c) {
    return (c >= 0x1F000 && c <= 0x1FAFF) || (c >= 0x2600 && c <= 0x27BF) || (c >= 0x2B00 && c <= 0x2BFF) || c == 0xFE0F;
}

bool isRegional(uint32_t c) { return c >= 0x1F1E6 && c <= 0x1F1FF; }
bool isEmojiMod(uint32_t c) { return c >= 0x1F3FB && c <= 0x1F3FF; }
bool isEmojiTag(uint32_t c) { return c >= 0xE0020 && c <= 0xE007F; }

// Characters that can be (part of) an emoji.
bool emojiCandidate(uint32_t c) {
    return (c >= 0x1F000 && c <= 0x1FAFF) || (c >= 0x2600 && c <= 0x27BF) || (c >= 0x2B00 && c <= 0x2BFF) ||
           (c >= 0x2190 && c <= 0x21FF) || (c >= 0x2300 && c <= 0x23FF) || (c >= 0x25A0 && c <= 0x25FF) ||
           (c >= 0x2900 && c <= 0x297F) || c == 0x00A9 || c == 0x00AE || c == 0x203C || c == 0x2049 || c == 0x2122 ||
           c == 0x2139 || c == 0x24C2 || c == 0x3030 || c == 0x303D || c == 0x3297 || c == 0x3299;
}

// Emoji_Presentation: shown as a picture even without the variation selector U+FE0F.
bool emojiByDefault(uint32_t c) {
    if (c >= 0x1F000 && c <= 0x1FAFF) return true;
    if ((c >= 0x23E9 && c <= 0x23EC) || (c >= 0x2648 && c <= 0x2653) || (c >= 0x2753 && c <= 0x2755) || (c >= 0x2795 && c <= 0x2797)) return true;
    switch (c) {
        case 0x231A: case 0x231B: case 0x23F0: case 0x23F3: case 0x25FD: case 0x25FE: case 0x2614: case 0x2615:
        case 0x267F: case 0x2693: case 0x26A1: case 0x26AA: case 0x26AB: case 0x26BD: case 0x26BE: case 0x26C4:
        case 0x26C5: case 0x26CE: case 0x26D4: case 0x26EA: case 0x26F2: case 0x26F3: case 0x26F5: case 0x26FA:
        case 0x26FD: case 0x2705: case 0x270A: case 0x270B: case 0x2728: case 0x274C: case 0x274E: case 0x2757:
        case 0x27B0: case 0x27BF: case 0x2B1B: case 0x2B1C: case 0x2B50: case 0x2B55:
            return true;
        default:
            return false;
    }
}

// Emoji sequences of a paragraph as [first, last) ranges of code points: one emoji with its variation selector, skin tone,
// keycap mark or tag characters, emoji joined by ZWJ (family, profession...), and flags (two regional indicators). A
// character that is text by default (a heart, a sun, a digit) only counts with U+FE0F, a skin tone or a ZWJ join.
void markEmojiSequences(const std::vector<uint32_t>& cps, std::vector<std::pair<size_t, size_t>>& out) {
    const size_t n = cps.size();
    size_t i = 0;
    while (i < n) {
        const uint32_t c = cps[i];
        size_t j = i + 1;
        bool emoji = false;
        if (isRegional(c)) {
            if (j < n && isRegional(cps[j])) { ++j; emoji = true; }
        } else if ((c >= '0' && c <= '9') || c == '#' || c == '*') {
            if (j + 1 < n && cps[j] == 0xFE0F && cps[j + 1] == 0x20E3) { j += 2; emoji = true; }
            else if (j < n && cps[j] == 0x20E3) { ++j; emoji = true; }
        } else if (emojiCandidate(c)) {
            const bool textSelector = j < n && cps[j] == 0xFE0E;
            emoji = !textSelector && (emojiByDefault(c) || (j < n && cps[j] == 0xFE0F));
            while (!textSelector && j < n) {
                const uint32_t d = cps[j];
                if (d == 0xFE0F || isEmojiMod(d) || d == 0x20E3 || isEmojiTag(d)) { ++j; emoji = true; continue; }
                if (d == 0x200D && j + 1 < n && emojiCandidate(cps[j + 1])) { j += 2; emoji = true; continue; }
                break;
            }
        }
        if (emoji) { out.emplace_back(i, j); i = j; } else { ++i; }
    }
}

// Letters of right-to-left scripts (Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic, presentation forms, Adlam...).
bool isRtlChar(uint32_t c) {
    return (c >= 0x0590 && c <= 0x08FF) || (c >= 0xFB1D && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF) ||
           (c >= 0x10800 && c <= 0x10FFF) || (c >= 0x1E800 && c <= 0x1EFFF);
}

// Characters that must stay in the font of the character before them (so a word is never cut in two fonts).
bool alwaysInherit(uint32_t c) {
    return (c >= 0x200C && c <= 0x200F) || c == 0x2060 || (c >= 0xFE00 && c <= 0xFE0F) || (c >= 0x0300 && c <= 0x036F);
}

struct Cluster {
    float adv = 0.f;
    bool space = false;
    int g0 = 0, gn = 0;  // glyph range
    int font = 0;
    int level = 0;       // bidi embedding level (odd = right to left)
};
struct GlyphRec {
    uint32_t gid = 0;
    int font = 0;
    float x = 0.f, y = 0.f;  // inside the cluster, px (y down)
};
struct LineRec {
    int para = 0, first = 0, last = 0;
    float width = 0.f;
    int asc = 0, desc = 0;  // ascent negative, descent positive (ints, like Android's FontMetricsInt)
    float top = 0.f, base = 0.f;
};

uint32_t argbA(uint32_t c) { return c >> 24; }

void compositeMask(Image& img, const std::vector<uint8_t>& mask, uint32_t argb) {
    const float a = (float)argbA(argb), r = (float)((argb >> 16) & 255), g = (float)((argb >> 8) & 255), b = (float)(argb & 255);
    const size_t n = (size_t)img.w * img.h;
    for (size_t i = 0; i < n; ++i) {
        const uint8_t c = mask[i];
        if (!c) continue;
        const float sa = a * c / 255.f;          // 0..255
        const float k = sa / 255.f;
        uint8_t* d = &img.px[i * 4];
        const float inv = 1.f - k;
        d[0] = (uint8_t)std::min(255.f, r * k + d[0] * inv + 0.5f);
        d[1] = (uint8_t)std::min(255.f, g * k + d[1] * inv + 0.5f);
        d[2] = (uint8_t)std::min(255.f, b * k + d[2] * inv + 0.5f);
        d[3] = (uint8_t)std::min(255.f, sa + d[3] * inv + 0.5f);
    }
}

// ---- gradient / picture fill: colour of the fill at a point of the text block (straight ARGB, 0..255 floats)
struct Rgba { float a, r, g, b; };

Rgba lerpArgb(uint32_t c1, uint32_t c2, float t) {
    t = std::min(1.f, std::max(0.f, t));
    auto ch = [&](int sh) { const float x1 = (float)((c1 >> sh) & 255), x2 = (float)((c2 >> sh) & 255); return x1 + (x2 - x1) * t; };
    return {ch(24), ch(16), ch(8), ch(0)};
}

Rgba gradientAt(const Request& rq, float bw, float bh, float x, float y) {
    const float x1 = rq.gx1 * bw, y1 = rq.gy1 * bh;
    float x2 = rq.gx2 * bw;
    const float y2 = rq.gy2 * bh;
    if (rq.gradType == 1) {  // radial: centre = point 1, radius = distance to point 2
        const float r = std::max(1.f, std::hypot(x2 - x1, y2 - y1));
        return lerpArgb(rq.grad1, rq.grad2, std::hypot(x - x1, y - y1) / r);
    }
    if (rq.gradType == 2) {  // sweep: colour 1 -> 2 -> 1 once round, start direction = towards point 2
        const float twoPi = 6.28318530718f;
        const float start = std::atan2(y2 - y1, x2 - x1);
        float a = std::atan2(y - y1, x - x1) - start;
        a = std::fmod(a, twoPi);
        if (a < 0.f) a += twoPi;
        const float f = a / twoPi;
        return lerpArgb(rq.grad1, rq.grad2, f < 0.5f ? f * 2.f : (1.f - f) * 2.f);
    }
    if (x1 == x2 && y1 == y2) x2 += 1.f;  // zero length: same nudge as Kotlin
    const float dx = x2 - x1, dy = y2 - y1;
    return lerpArgb(rq.grad1, rq.grad2, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy));
}

// Bilinear sample of the premultiplied source at (sx, sy) in source pixels; result straight ARGB. Outside = transparent
// (only reached by FIT letterboxing).
Rgba sampleFill(const Request& rq, float sx, float sy) {
    const int W = rq.fillW, H = rq.fillH;
    if (sx < 0.f || sy < 0.f || sx > (float)W || sy > (float)H) return {0, 0, 0, 0};
    const float fx = std::min((float)(W - 1), std::max(0.f, sx - 0.5f)), fy = std::min((float)(H - 1), std::max(0.f, sy - 0.5f));
    const int x0 = (int)fx, y0 = (int)fy, x1 = std::min(W - 1, x0 + 1), y1 = std::min(H - 1, y0 + 1);
    const float tx = fx - x0, ty = fy - y0;
    float acc[4] = {0, 0, 0, 0};
    const int xs[2] = {x0, x1}, ys[2] = {y0, y1};
    const float wx[2] = {1.f - tx, tx}, wy[2] = {1.f - ty, ty};
    for (int j = 0; j < 2; ++j)
        for (int i = 0; i < 2; ++i) {
            const uint8_t* q = rq.fillPix + (size_t)ys[j] * rq.fillStride + (size_t)xs[i] * 4;
            const float w = wx[i] * wy[j];
            for (int k = 0; k < 4; ++k) acc[k] += q[k] * w;  // premultiplied r g b a
        }
    if (acc[3] <= 0.5f) return {0, 0, 0, 0};
    const float inv = 255.f / acc[3];
    return {acc[3], std::min(255.f, acc[0] * inv), std::min(255.f, acc[1] * inv), std::min(255.f, acc[2] * inv)};
}

Rgba pictureAt(const Request& rq, float bw, float bh, float x, float y) {
    const float sw = (float)rq.fillW, sh = (float)rq.fillH;
    if (rq.fillImgMode == 2) return sampleFill(rq, x / bw * sw, y / bh * sh);
    if (rq.fillImgMode == 1) {  // fit: whole picture inside the block, centred
        const float k = std::min(bw / sw, bh / sh);
        return sampleFill(rq, (x - (bw - sw * k) * 0.5f) / k, (y - (bh - sh * k) * 0.5f) / k);
    }
    const float k = std::max(bw / sw, bh / sh);  // fill: cover, centred crop
    return sampleFill(rq, x / k + (sw - bw / k) * 0.5f, y / k + (sh - bh / k) * 0.5f);
}

// Like compositeMask, but the colour comes from the fill source at every pixel. (originX/Y, scale): picture pixel
// -> block coordinates  ((px + 0.5 - originX) / scale, ...).
void compositeFill(Image& img, const std::vector<uint8_t>& mask, const Request& rq, float originX, float originY,
                   float scale, float blockW, float blockH) {
    const bool pic = rq.fillKind == 2;
    if (pic && !rq.fillPix) return;
    for (int y = 0; y < img.h; ++y) {
        for (int x = 0; x < img.w; ++x) {
            const size_t i = (size_t)y * img.w + x;
            const uint8_t c = mask[i];
            if (!c) continue;
            const float bx = ((float)x + 0.5f - originX) / scale, by = ((float)y + 0.5f - originY) / scale;
            const Rgba col = pic ? pictureAt(rq, blockW, blockH, bx, by) : gradientAt(rq, blockW, blockH, bx, by);
            const float sa = col.a * c / 255.f;
            if (sa <= 0.f) continue;
            const float k = sa / 255.f, inv = 1.f - k;
            uint8_t* d = &img.px[i * 4];
            d[0] = (uint8_t)std::min(255.f, col.r * k + d[0] * inv + 0.5f);
            d[1] = (uint8_t)std::min(255.f, col.g * k + d[1] * inv + 0.5f);
            d[2] = (uint8_t)std::min(255.f, col.b * k + d[2] * inv + 0.5f);
            d[3] = (uint8_t)std::min(255.f, sa + d[3] * inv + 0.5f);
        }
    }
}

void maskAdd(std::vector<uint8_t>& mask, int w, int h, int x, int y, uint8_t v) {
    if (x < 0 || y < 0 || x >= w || y >= h || !v) return;
    uint8_t& m = mask[(size_t)y * w + x];
    m = (uint8_t)(m + v - (m * v + 127) / 255);  // union of two coverages
}

void maskRect(std::vector<uint8_t>& mask, int w, int h, float x0, float y0, float x1, float y1) {
    const int ix0 = std::max(0, (int)std::floor(x0)), ix1 = std::min(w, (int)std::ceil(x1));
    const int iy0 = std::max(0, (int)std::floor(y0)), iy1 = std::min(h, (int)std::ceil(y1));
    for (int y = iy0; y < iy1; ++y) {
        const float cy = std::min(y1, y + 1.f) - std::max(y0, (float)y);
        for (int x = ix0; x < ix1; ++x) {
            const float cx = std::min(x1, x + 1.f) - std::max(x0, (float)x);
            if (cx > 0 && cy > 0) maskAdd(mask, w, h, x, y, (uint8_t)std::lround(255.f * std::min(1.f, cx) * std::min(1.f, cy)));
        }
    }
}

void drawRoundRect(Image& img, float x0, float y0, float x1, float y1, float radius, uint32_t argb) {
    std::vector<uint8_t> m((size_t)img.w * img.h, 0);
    const float hx = (x1 - x0) * 0.5f, hy = (y1 - y0) * 0.5f, cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
    const float r = std::max(0.f, std::min(radius, std::min(hx, hy)));
    const int ix0 = std::max(0, (int)std::floor(x0) - 1), ix1 = std::min(img.w, (int)std::ceil(x1) + 1);
    const int iy0 = std::max(0, (int)std::floor(y0) - 1), iy1 = std::min(img.h, (int)std::ceil(y1) + 1);
    for (int y = iy0; y < iy1; ++y) {
        for (int x = ix0; x < ix1; ++x) {
            const float qx = std::fabs(x + 0.5f - cx) - (hx - r), qy = std::fabs(y + 0.5f - cy) - (hy - r);
            const float ox = std::max(qx, 0.f), oy = std::max(qy, 0.f);
            const float d = std::sqrt(ox * ox + oy * oy) + std::min(std::max(qx, qy), 0.f) - r;
            const float cov = std::min(1.f, std::max(0.f, 0.5f - d));
            m[(size_t)y * img.w + x] = (uint8_t)std::lround(cov * 255.f);
        }
    }
    compositeMask(img, m, argb);
}

// Converts glyph [g] (an outline or stroked outline glyph) to a bitmap at the sub-pixel origin of
// (x, y) and adds it into the mask with the alpha [gain]. Takes ownership of [g] either way.
bool blitGlyph(FT_Glyph g, float x, float y, std::vector<uint8_t>& mask, int mw, int mh, float gain) {
    const int ix = (int)std::floor(x), iy = (int)std::floor(y);
    FT_Vector origin;
    origin.x = (FT_Pos)std::lround((x - ix) * 64.f);
    origin.y = (FT_Pos)std::lround(-(y - iy) * 64.f);
    FT_Glyph t = g;
    if (FT_Glyph_To_Bitmap(&t, FT_RENDER_MODE_NORMAL, &origin, 1) != 0) { if (t) FT_Done_Glyph(t); return false; }
    FT_BitmapGlyph bg = (FT_BitmapGlyph)t;
    const FT_Bitmap& bm = bg->bitmap;
    if (bm.pixel_mode == FT_PIXEL_MODE_GRAY) {
        for (unsigned row = 0; row < bm.rows; ++row) {
            const unsigned char* src = bm.buffer + (bm.pitch >= 0 ? (size_t)row * bm.pitch : (size_t)(bm.rows - 1 - row) * -bm.pitch);
            for (unsigned col = 0; col < bm.width; ++col) {
                uint8_t v = src[col];
                if (gain < 0.999f) v = (uint8_t)std::lround(v * std::max(0.f, gain));
                maskAdd(mask, mw, mh, ix + bg->left + (int)col, iy - bg->top + (int)row, v);
            }
        }
    }
    FT_Done_Glyph(t);
    return true;
}

// Draws one glyph into an A8 mask (fill, or - with a stroker - the outline ring).
bool rasterGlyph(Face& f, uint32_t gid, float px, bool bold, bool shear, FT_Stroker stroker, float x, float y,
                 std::vector<uint8_t>& mask, int mw, int mh, float gain = 1.f) {
    setSize(f, px);
    FT_Matrix mat;
    mat.xx = 0x10000; mat.xy = shear ? 0x4000 : 0; mat.yx = 0; mat.yy = 0x10000;
    FT_Set_Transform(f.ft, &mat, nullptr);
    if (FT_Load_Glyph(f.ft, gid, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP) != 0) return false;
    FT_GlyphSlot slot = f.ft->glyph;
    if (slot->format != FT_GLYPH_FORMAT_OUTLINE) return false;
    if (bold) FT_Outline_Embolden(&slot->outline, (FT_Pos)std::lround(px / 24.f * 64.f));
    FT_Glyph g = nullptr;
    if (FT_Get_Glyph(slot, &g) != 0 || !g) return false;
    if (stroker) {
        FT_Glyph t = g;
        if (FT_Glyph_Stroke(&t, stroker, 1) != 0) { if (t) FT_Done_Glyph(t); return false; }
        g = t;
    }
    return blitGlyph(g, x, y, mask, mw, mh, gain);
}

namespace {

// Sink for FT_Outline_Decompose: one segment list per contour, points in 26.6 glyph units.
struct SegSink {
    std::vector<std::vector<OSeg>> contours;
    double cx = 0, cy = 0;  // current point
    void moveTo(const FT_Vector* to) {
        contours.emplace_back();
        cx = to->x; cy = to->y;
    }
    void lineTo(const FT_Vector* to) {
        OSeg s; s.kind = 0;
        s.x[0] = cx; s.y[0] = cy; s.x[1] = to->x; s.y[1] = to->y;
        contours.back().push_back(s);
        cx = to->x; cy = to->y;
    }
    void conicTo(const FT_Vector* c, const FT_Vector* to) {
        OSeg s; s.kind = 1;
        s.x[0] = cx; s.y[0] = cy; s.x[1] = c->x; s.y[1] = c->y; s.x[2] = to->x; s.y[2] = to->y;
        contours.back().push_back(s);
        cx = to->x; cy = to->y;
    }
    void cubicTo(const FT_Vector* c1, const FT_Vector* c2, const FT_Vector* to) {
        OSeg s; s.kind = 2;
        s.x[0] = cx; s.y[0] = cy; s.x[1] = c1->x; s.y[1] = c1->y; s.x[2] = c2->x; s.y[2] = c2->y; s.x[3] = to->x; s.y[3] = to->y;
        contours.back().push_back(s);
        cx = to->x; cy = to->y;
    }
};
int dcMoveTo(const FT_Vector* to, void* user) { ((SegSink*)user)->moveTo(to); return 0; }
int dcLineTo(const FT_Vector* to, void* user) { ((SegSink*)user)->lineTo(to); return 0; }
int dcConicTo(const FT_Vector* c, const FT_Vector* to, void* user) { ((SegSink*)user)->conicTo(c, to); return 0; }
int dcCubicTo(const FT_Vector* c1, const FT_Vector* c2, const FT_Vector* to, void* user) { ((SegSink*)user)->cubicTo(c1, c2, to); return 0; }

}  // namespace

// Prepares glyph [gid] of [f] exactly as rasterGlyph does (size, shear transform, embolden) and
// walks its outline into segment contours (26.6 glyph units). False = the glyph has no outline.
bool glyphOutlineSegs(Face& f, uint32_t gid, float px, bool bold, bool shear,
                      std::vector<std::vector<OSeg>>& out) {
    setSize(f, px);
    FT_Matrix mat;
    mat.xx = 0x10000; mat.xy = shear ? 0x4000 : 0; mat.yx = 0; mat.yy = 0x10000;
    FT_Set_Transform(f.ft, &mat, nullptr);
    if (FT_Load_Glyph(f.ft, gid, FT_LOAD_NO_HINTING | FT_LOAD_NO_BITMAP) != 0) return false;
    FT_GlyphSlot slot = f.ft->glyph;
    if (slot->format != FT_GLYPH_FORMAT_OUTLINE) return false;
    if (bold) FT_Outline_Embolden(&slot->outline, (FT_Pos)std::lround(px / 24.f * 64.f));
    SegSink sink;
    FT_Outline_Funcs fn;
    fn.move_to = &dcMoveTo; fn.line_to = &dcLineTo; fn.conic_to = &dcConicTo; fn.cubic_to = &dcCubicTo;
    fn.shift = 0; fn.delta = 0;
    if (FT_Outline_Decompose(&slot->outline, &fn, &sink) != 0) return false;
    out.clear();
    for (auto& c : sink.contours) if (!c.empty()) out.push_back(std::move(c));
    return !out.empty();
}

// Draws the part of one glyph's outline whose arc length (over this glyph's own concatenated
// contours) lies in [a, b] — 26.6 units — stroked with [stroker], into the mask. The outline is
// rebuilt from the exactly clipped segments and stroked by FreeType, so the drawing tip follows
// the true bezier shapes; a window covering the whole glyph goes through the ordinary stroker.
bool rasterGlyphTrimmed(Face& f, uint32_t gid, float px, bool bold, bool shear, FT_Stroker stroker,
                        double a, double b, float x, float y,
                        std::vector<uint8_t>& mask, int mw, int mh, float gain = 1.f) {
    std::vector<std::vector<OSeg>> contours;
    if (!glyphOutlineSegs(f, gid, px, bold, shear, contours)) return false;
    std::vector<std::vector<OSeg>> clipped;
    double off = 0;
    for (auto& c : contours) {
        const double len = contourLength(c);
        if (len > 0) {
            std::vector<OSeg> part = clipContour(c, a - off, b - off);
            if (!part.empty()) clipped.push_back(std::move(part));
        }
        off += len;
    }
    if (clipped.empty()) return true;  // the window misses this glyph: drawn, as nothing
    int nPts = 0, nCtr = 0;
    for (auto& c : clipped) {
        ++nCtr;
        nPts += 1;
        for (auto& s : c) nPts += s.n() - 1;
    }
    FT_Outline ol;
    if (FT_Outline_New(g_lib, (FT_UInt)nPts, nCtr, &ol) != 0) return false;
    ol.n_points = 0;
    ol.n_contours = 0;
    for (auto& c : clipped) {
        auto put = [&](double px26, double py26, char tag) {
            ol.points[ol.n_points].x = (FT_Pos)std::lround(px26);
            ol.points[ol.n_points].y = (FT_Pos)std::lround(py26);
            ol.tags[ol.n_points] = tag;
            ++ol.n_points;
        };
        put(c[0].sx(), c[0].sy(), FT_CURVE_TAG_ON);
        for (auto& s : c) {
            if (s.kind == 0) {
                put(s.x[1], s.y[1], FT_CURVE_TAG_ON);
            } else if (s.kind == 1) {
                put(s.x[1], s.y[1], FT_CURVE_TAG_CONIC);
                put(s.x[2], s.y[2], FT_CURVE_TAG_ON);
            } else {
                put(s.x[1], s.y[1], FT_CURVE_TAG_CUBIC);
                put(s.x[2], s.y[2], FT_CURVE_TAG_CUBIC);
                put(s.x[3], s.y[3], FT_CURVE_TAG_ON);
            }
        }
        ol.contours[ol.n_contours++] = (short)(ol.n_points - 1);
    }
    FT_Glyph g = nullptr;
    if (FT_New_Glyph(g_lib, FT_GLYPH_FORMAT_OUTLINE, &g) != 0 || !g) { FT_Outline_Done(g_lib, &ol); return false; }
    FT_OutlineGlyph og = (FT_OutlineGlyph)g;
    FT_Outline_Done(g_lib, &og->outline);  // the empty outline FT_New_Glyph made
    og->outline = ol;
    FT_Glyph t = g;
    if (FT_Glyph_Stroke(&t, stroker, 1) != 0) { if (t) FT_Done_Glyph(t); return false; }
    return blitGlyph(t, x, y, mask, mw, mh, gain);
}

}  // namespace

// Visual order (UAX #9 rule L2) of the clusters [first, last) of one line: indices into `cl`. Trailing spaces stay at the
// end (they are not part of the line width and have no ink).
static void visualOrder(const std::vector<Cluster>& cl, int first, int last, std::vector<int>& out) {
    out.clear();
    int e = last;
    while (e > first && cl[e - 1].space) --e;
    int maxL = 0, minOdd = 1000;
    for (int i = first; i < e; ++i) {
        out.push_back(i);
        maxL = std::max(maxL, cl[i].level);
        if (cl[i].level & 1) minOdd = std::min(minOdd, cl[i].level);
    }
    for (int lvl = maxL; lvl >= minOdd && lvl >= 1; --lvl) {
        size_t i = 0;
        while (i < out.size()) {
            if (cl[out[i]].level >= lvl) {
                size_t j = i;
                while (j < out.size() && cl[out[j]].level >= lvl) ++j;
                std::reverse(out.begin() + (long)i, out.begin() + (long)j);
                i = j;
            } else {
                ++i;
            }
        }
    }
    for (int i = e; i < last; ++i) out.push_back(i);
}

bool available() { return true; }
void setMatcherForTest(Matcher m) { g_testMatcher = std::move(m); }
void setPngDecoderForTest(PngDecoder d) {
    std::lock_guard<std::mutex> lock(g_mu);
    g_testPng = std::move(d);
    g_colorGlyphs.clear();  // pictures decoded by the previous decoder
}

namespace {
// Emoji can be drawn when the PNG decoder exists and the emoji font of the device is a bitmap font we can read.
// g_mu is held by the caller.
bool emojiReadyLocked() {
    if (!ensureLib()) return false;
    MatchResult mr;
    const uint16_t u[2] = {0xD83D, 0xDE00};  // U+1F600
    if (!matchFont("sans-serif", 400, false, u, 2, mr)) return false;
    Face* f = loadFace(mr.path, mr.index, mr.axes, mr.weight, mr.italic);
    if (!f) return false;
    const uint32_t gid = FT_Get_Char_Index(f->ft, 0x1F600);
    if (!gid) return false;
    if (f->colr) {  // COLR emoji font: try to draw one emoji, once
        if (f->colrChecked == 0) {
            ColrImage im;
            f->colrChecked = colrRender(g_lib, f->ft, gid, 64, im) ? 1 : -1;
            f->sizeSet = -1;
        }
        return f->colrChecked > 0;
    }
    if (!pngDecoderPresent() || !f->color) return false;
    const int si = pickStrike(*f, 64.f);
    CbImage im;
    return si >= 0 && cbFind(*f, f->strikes[(size_t)si], gid, im) && (im.format == 17 || im.format == 18 || im.format == 19);
}
}  // namespace

bool emojiAvailable() {
    std::lock_guard<std::mutex> lock(g_mu);
    return emojiReadyLocked();
}

bool colorGlyphForTest(const std::string& fontPath, uint32_t codepoint, float wantPpem, int* w, int* h, int* bearingX,
                       int* bearingY, int* strikePpem, std::vector<uint8_t>* rgbaPremul) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (!ensureLib()) return false;
    Face* f = loadFace(fontPath, 0, {}, -1, false);
    if (!f || !f->color) return false;
    const uint32_t gid = FT_Get_Char_Index(f->ft, codepoint);
    if (!gid) return false;
    const ColorGlyph* g = colorGlyph(*f, gid, wantPpem);
    if (!g) return false;
    if (w) *w = g->w;
    if (h) *h = g->h;
    if (bearingX) *bearingX = g->bx;
    if (bearingY) *bearingY = g->by;
    if (strikePpem) *strikePpem = g->ppem;
    if (rgbaPremul) *rgbaPremul = g->px;
    return true;
}

bool draw(uint8_t* dst, int dstW, int dstH, int dstStride, const Request& rq, Stats* st, std::string* err) {
    std::lock_guard<std::mutex> lock(g_mu);
    auto fail = [&](const char* m) {
        if (err) *err = m;
        return false;
    };
    if (!dst || dstW <= 0 || dstH <= 0) return fail("no target");
    if (!ensureLib()) return fail("FreeType init failed");
    for (auto& kv : g_faces) kv.second->colrEmoji = false;  // set again below for the faces picked as emoji font of this layer

    // ---- text -> code points, paragraphs
    std::vector<uint32_t> all;
    decodeUtf8(rq.text, all);
    std::vector<std::vector<uint32_t>> paras(1);
    bool anyEmoji = false;
    for (uint32_t c : all) if (emojiish(c)) { anyEmoji = true; break; }
    const bool emojiOk = anyEmoji && emojiReadyLocked();
    if (anyEmoji && !emojiOk) return fail("emoji font not usable natively");
    for (uint32_t c : all) {
        if (rq.bidiLevels.empty() && isRtlChar(c)) return fail("right-to-left text needs bidi levels");
        if (c == '\n') { paras.emplace_back(); continue; }
        if (c == '\r') continue;
        if (c == '\t') c = ' ';
        if (c < 0x20) continue;
        paras.back().push_back(c);
    }
    {
        size_t total = 0;
        for (const auto& pp : paras) total += pp.size();
        if (!rq.bidiLevels.empty() && rq.bidiLevels.size() != total) return fail("bidi levels do not match the text");
    }
    auto paraIsRtl = [&](size_t pi) { return pi < rq.paraRtl.size() && rq.paraRtl[pi] != 0; };

    // ---- fonts
    std::vector<Face*> fonts;
    Face* primary = nullptr;
    if (!rq.fontPath.empty()) primary = loadFace(rq.fontPath, 0, {}, -1, false);
    if (!primary) {
        MatchResult mr;
        const uint16_t a = 'A';
        if (matchFont(rq.family, rq.weight, rq.italic, &a, 1, mr)) primary = loadFace(mr.path, mr.index, mr.axes, mr.weight, mr.italic);
    }
    if (!primary) return fail("no font");
    fonts.push_back(primary);
    auto fontIndex = [&](Face* f) {
        for (size_t i = 0; i < fonts.size(); ++i) if (fonts[i] == f) return (int)i;
        fonts.push_back(f);
        return (int)fonts.size() - 1;
    };
    std::map<uint32_t, int> fallbackCache;
    auto fallbackFor = [&](uint32_t cp) {
        auto it = fallbackCache.find(cp);
        if (it != fallbackCache.end()) return it->second;
        int idx = 0;
        uint16_t u[2];
        uint32_t ul = 0;
        toUtf16(cp, u, ul);
        MatchResult mr;
        if (matchFont(rq.family, rq.weight, rq.italic, u, ul, mr)) {
            Face* f = loadFace(mr.path, mr.index, mr.axes, mr.weight, mr.italic);
            if (f && hasGlyph(*f, cp)) idx = fontIndex(f);
        }
        fallbackCache[cp] = idx;
        return idx;
    };

    const float px = rq.fontPx;
    hb_buffer_t* buf = hb_buffer_create();
    struct BufGuard { hb_buffer_t* b; ~BufGuard() { hb_buffer_destroy(b); } } guard{buf};

    std::vector<std::vector<Cluster>> pcl(paras.size());
    std::vector<GlyphRec> glyphs;
    int clusterCount = 0;
    size_t levelBase = 0;  // index of the paragraph's first code point in rq.bidiLevels
    for (size_t pi = 0; pi < paras.size(); ++pi) {
        const std::vector<uint32_t>& cps = paras[pi];
        const size_t n = cps.size();
        std::vector<int> lv(n, 0);
        if (!rq.bidiLevels.empty()) for (size_t i = 0; i < n; ++i) lv[i] = rq.bidiLevels[levelBase + i];
        levelBase += n;
        std::vector<int> fi(n, 0);
        std::vector<char> forced(n, 0);
        if (emojiOk) {
            // Every emoji sequence is shaped as a whole by the emoji font (it holds the flags, families, skin tones...).
            std::vector<std::pair<size_t, size_t>> seqs;
            markEmojiSequences(cps, seqs);
            for (const auto& sq : seqs) {
                std::vector<uint16_t> u16;
                for (size_t k = sq.first; k < sq.second; ++k) {
                    uint16_t b[2];
                    uint32_t bl = 0;
                    toUtf16(cps[k], b, bl);
                    for (uint32_t m = 0; m < bl; ++m) u16.push_back(b[m]);
                }
                MatchResult mr;
                if (!matchFont(rq.family, rq.weight, rq.italic, u16.data(), (uint32_t)u16.size(), mr)) continue;
                Face* ef = loadFace(mr.path, mr.index, mr.axes, mr.weight, mr.italic);
                if (!ef || !(ef->color || ef->colr) || !hasGlyph(*ef, cps[sq.first])) continue;
                if (ef->colr) ef->colrEmoji = true;
                const int idx = fontIndex(ef);
                for (size_t k = sq.first; k < sq.second; ++k) { fi[k] = idx; forced[k] = 1; }
            }
        }
        for (size_t i = 0; i < n; ++i) {
            const uint32_t c = cps[i];
            if (forced[i]) continue;
            if (i > 0 && alwaysInherit(c)) { fi[i] = fi[i - 1]; continue; }
            if (i > 0 && (c == 0x20 || c == 0xA0) && hasGlyph(*fonts[fi[i - 1]], c)) { fi[i] = fi[i - 1]; continue; }
            if (hasGlyph(*primary, c)) { fi[i] = 0; continue; }
            fi[i] = fallbackFor(c);
        }
        size_t s = 0;
        while (s < n) {
            size_t e = s + 1;
            while (e < n && fi[e] == fi[s] && lv[e] == lv[s]) ++e;
            const bool rtlRun = (lv[s] & 1) != 0;
            Face* face = fonts[fi[s]];
            hb_font_set_scale(face->hb, (int)std::lround(px * 64.f), (int)std::lround(px * 64.f));
            hb_buffer_clear_contents(buf);
            hb_buffer_add_utf32(buf, cps.data(), (int)n, (unsigned)s, (int)(e - s));
            hb_buffer_guess_segment_properties(buf);
            hb_buffer_set_direction(buf, rtlRun ? HB_DIRECTION_RTL : HB_DIRECTION_LTR);  // the bidi level decides
            hb_shape(face->hb, buf, nullptr, 0);
            unsigned gn = 0;
            hb_glyph_info_t* info = hb_buffer_get_glyph_infos(buf, &gn);
            hb_glyph_position_t* pos = hb_buffer_get_glyph_positions(buf, &gn);
            float penInCluster = 0.f;
            uint32_t curCluster = 0xFFFFFFFFu;
            const size_t runFirstCluster = pcl[pi].size();
            for (unsigned g = 0; g < gn; ++g) {
                if (info[g].cluster != curCluster) {
                    curCluster = info[g].cluster;
                    Cluster c;
                    c.space = curCluster < n && (cps[curCluster] == 0x20);
                    c.g0 = (int)glyphs.size();
                    c.font = fi[s];
                    c.level = lv[s];
                    pcl[pi].push_back(c);
                    penInCluster = 0.f;
                }
                GlyphRec gr;
                gr.gid = info[g].codepoint;
                gr.font = fi[s];
                gr.x = penInCluster + pos[g].x_offset / 64.f;
                gr.y = -pos[g].y_offset / 64.f;
                glyphs.push_back(gr);
                Cluster& cl = pcl[pi].back();
                cl.gn++;
                penInCluster += pos[g].x_advance / 64.f;
                cl.adv = penInCluster;
            }
            // HarfBuzz returns a right-to-left run in visual order: put its clusters back in logical order, the
            // line breaker and the per-line reordering below work on logical order.
            if (rtlRun) std::reverse(pcl[pi].begin() + (long)runFirstCluster, pcl[pi].end());
            s = e;
        }
        for (Cluster& c : pcl[pi]) c.adv += rq.letterSpacing;
        clusterCount += (int)pcl[pi].size();
    }

    // ---- lines
    std::vector<LineRec> lines;
    int pAsc = 0, pDesc = 0;
    fontMetrics(*primary, px, pAsc, pDesc);
    for (size_t pi = 0; pi < paras.size(); ++pi) {
        std::vector<Item> items;
        items.reserve(pcl[pi].size());
        for (const Cluster& c : pcl[pi]) { Item it; it.adv = c.adv; it.space = c.space; items.push_back(it); }
        for (const LineSpan& sp : breakLines(items, rq.maxTextW)) {
            LineRec l;
            l.para = (int)pi; l.first = sp.first; l.last = sp.last; l.width = sp.width;
            l.asc = pAsc; l.desc = pDesc;
            for (int k = sp.first; k < sp.last; ++k) {
                int a = 0, d = 0;
                fontMetrics(*fonts[pcl[pi][k].font], px, a, d);
                l.asc = std::min(l.asc, a);
                l.desc = std::max(l.desc, d);
            }
            lines.push_back(l);
        }
    }
    float layoutH = 0.f, tightW = 1.f;
    for (LineRec& l : lines) {
        const int extra = extraSpacing(l.asc, l.desc, rq.lineMult);
        l.top = layoutH;
        l.base = layoutH - l.asc;
        layoutH += (float)(l.desc - l.asc + extra);
        tightW = std::max(tightW, l.width);
    }
    tightW = std::min(tightW, rq.maxTextW);
    const float boxW = tightW + 2.f * rq.padH, boxH = layoutH + 2.f * rq.padV;

    // ---- raster size (the layer scale is applied when drawing glyphs, so text stays sharp)
    const float shadowSigma = rq.shadowOn ? (0.57735f * std::max(0.f, rq.shadowBlur) + 0.5f) : 0.f;
    float rs = std::max(0.01f, rq.scale);
    auto marginFor = [&](float s) {
        float m = 2.f + (rq.drawOn ? rq.drawStrokeW : rq.strokeW) * s * 0.5f;
        if (rq.shadowOn) m += std::max(std::fabs(rq.shadowDx), std::fabs(rq.shadowDy)) * s + 3.f * shadowSigma * s;
        // A text animator moves letters away from the box: the picture needs room for them (and for the shadow of a moved letter).
        if (rq.animOn) m += std::max(std::fabs(rq.animDx), std::fabs(rq.animDy)) * s;
        return std::min(rq.animOn ? 1600 : 400, (int)std::ceil(m));
    };
    for (int guardIter = 0; guardIter < 4; ++guardIter) {
        const float w = boxW * rs + 2 * marginFor(rs), h = boxH * rs + 2 * marginFor(rs);
        if (w * h <= 14e6f) break;
        rs *= std::sqrt(14e6f / (w * h)) * 0.98f;
    }
    const float ratio = std::max(0.01f, rq.scale) / rs;  // extra stretch when the picture was capped
    const int M = marginFor(rs);

    const bool translateOnly = std::fabs(std::fmod(rq.rotationDeg, 360.f)) < 1e-4f && rq.flipX > 0 && rq.flipY > 0 &&
                               std::fabs(ratio - 1.f) < 1e-5f;
    const float boxWs = boxW * rs, boxHs = boxH * rs;
    float subX = 0.f, subY = 0.f;
    int ix0 = 0, iy0 = 0;
    if (translateOnly) {
        const float p0x = rq.cx - boxWs * 0.5f, p0y = rq.cy - boxHs * 0.5f;
        ix0 = (int)std::floor(p0x); iy0 = (int)std::floor(p0y);
        subX = p0x - ix0; subY = p0y - iy0;
    }
    const int imgW = (int)std::ceil(boxWs + subX) + 2 * M + 1, imgH = (int)std::ceil(boxHs + subY) + 2 * M + 1;
    Image img;
    img.alloc(imgW, imgH);
    const float ox = M + subX, oy = M + subY;  // box origin inside the picture

    // ---- masks
    std::vector<uint8_t> fillMask, strokeMask, drawMask;
    const bool wantFill = rq.fillOn && !rq.drawOn;      // drawing progress replaces the fill with the trimmed outline
    const bool wantStroke = !rq.drawOn && rq.strokeW > 0.f;
    if (wantFill) fillMask.assign((size_t)imgW * imgH, 0);
    if (wantStroke) strokeMask.assign((size_t)imgW * imgH, 0);
    FT_Stroker stroker = nullptr;
    if (wantStroke && FT_Stroker_New(g_lib, &stroker) == 0)
        FT_Stroker_Set(stroker, (FT_Fixed)std::lround(rq.strokeW * rs * 0.5f * 64.f), FT_STROKER_LINECAP_ROUND, FT_STROKER_LINEJOIN_ROUND, 0);
    FT_Stroker drawStroker = nullptr;
    if (rq.drawOn && rq.drawStrokeW > 0.f) {
        drawMask.assign((size_t)imgW * imgH, 0);
        if (FT_Stroker_New(g_lib, &drawStroker) == 0)
            FT_Stroker_Set(drawStroker, (FT_Fixed)std::lround(rq.drawStrokeW * rs * 0.5f * 64.f), FT_STROKER_LINECAP_ROUND, FT_STROKER_LINEJOIN_ROUND, 0);
    }

    // ---- text animator: which unit (letter / word / line) every cluster belongs to
    std::vector<std::vector<int>> unitOf(pcl.size());
    int unitCount = 0;
    if (rq.animOn) {
        for (size_t pi = 0; pi < pcl.size(); ++pi) unitOf[pi].assign(pcl[pi].size(), -1);
        if (rq.animComponent == 2) {
            for (size_t li = 0; li < lines.size(); ++li)
                for (int k = lines[li].first; k < lines[li].last; ++k) unitOf[(size_t)lines[li].para][(size_t)k] = (int)li;
            unitCount = (int)lines.size();
        } else {
            int cur = -1;
            for (size_t pi = 0; pi < pcl.size(); ++pi) {
                bool inWord = false;
                for (size_t k = 0; k < pcl[pi].size(); ++k) {
                    if (pcl[pi][k].space) { inWord = false; unitOf[pi][k] = cur < 0 ? 0 : cur; continue; }
                    if (rq.animComponent == 0 || !inWord) { ++cur; inWord = true; }
                    unitOf[pi][k] = cur;
                }
                inWord = false;  // a new paragraph always starts a new word
            }
            unitCount = cur + 1;
        }
    }
    const bool animating = rq.animOn && unitCount > 0;
    // How far unit u has arrived (0..1): the part of its slice of [0, 1] that lies inside [animStart, animEnd].
    auto arrived = [&](int u) {
        const float lo = (float)u / (float)unitCount, hi = (float)(u + 1) / (float)unitCount;
        const float a = std::max(lo, std::min(rq.animStart, rq.animEnd)), b = std::min(hi, std::max(rq.animStart, rq.animEnd));
        return std::min(1.f, std::max(0.f, (b - a) * (float)unitCount));
    };
    // ---- text progress: the global index (paragraphs in order, clusters in logical order inside one) of
    // every cluster, and whether the reveal window shows it. Same rule as TextProgressEval.isVisible.
    std::vector<std::vector<int>> clusterOrd;
    if (rq.progressOn) {
        clusterOrd.resize(pcl.size());
        int ord = 0;
        for (size_t pi = 0; pi < pcl.size(); ++pi) {
            clusterOrd[pi].assign(pcl[pi].size(), -1);
            for (size_t k = 0; k < pcl[pi].size(); ++k) clusterOrd[pi][k] = ord++;
        }
    }
    const bool progressing = rq.progressOn && clusterCount > 0;
    auto clusterShown = [&](int ord) {
        if (!progressing) return true;
        const float lo = (float)ord / (float)clusterCount, hi = (float)(ord + 1) / (float)clusterCount;
        const float a = std::max(lo, std::min(rq.progressStart, rq.progressEnd)), b = std::min(hi, std::max(rq.progressStart, rq.progressEnd));
        return (b - a) * (float)clusterCount > 0.999f;
    };
    std::vector<uint8_t> tintMask;  // glyphs that are drawn (partly) in the animator colour
    if (animating && rq.animRecolor && wantFill) tintMask.assign((size_t)imgW * imgH, 0);

    // ---- drawing progress: every glyph's outline length and its offset in the concatenated
    // outline of the whole text, measured on the outlines exactly as they are drawn (same raster
    // size, embolden, shear), in draw order — line by line, visual order inside a line, the order
    // the loop below rasterizes. Glyphs the textprogress window hides still count (the layout is
    // computed from the full text, like the reveal itself); colour glyphs have no outline and
    // contribute nothing. The window is order-free, like the animators' ranges.
    std::vector<float> glyphOff, glyphLen;
    double drawS = 0, drawE = 0;  // the trim window in raster px over the concatenated lengths
    if (rq.drawOn) {
        glyphOff.assign(glyphs.size(), 0.f);
        glyphLen.assign(glyphs.size(), 0.f);
        double total = 0;
        std::vector<int> ord;
        for (const LineRec& l : lines) {
            const std::vector<Cluster>& cl = pcl[l.para];
            visualOrder(cl, l.first, l.last, ord);
            for (int k : ord) {
                const Cluster& c = cl[k];
                for (int g = c.g0; g < c.g0 + c.gn; ++g) {
                    const GlyphRec& gr = glyphs[g];
                    Face& f = *fonts[gr.font];
                    if (f.color || f.colrEmoji) continue;
                    const bool bold = rq.weight >= 600 && f.weight < 600;
                    const bool shear = rq.italic && !f.italic;
                    std::vector<std::vector<OSeg>> contours;
                    double len26 = 0;
                    if (glyphOutlineSegs(f, gr.gid, px * rs, bold, shear, contours))
                        for (auto& cc : contours) len26 += contourLength(cc);
                    glyphOff[(size_t)g] = (float)total;
                    glyphLen[(size_t)g] = (float)(len26 / 64.0);
                    total += len26 / 64.0;
                }
            }
        }
        drawS = (double)std::min(rq.drawStart, rq.drawEnd) * total;
        drawE = (double)std::max(rq.drawStart, rq.drawEnd) * total;
    }

    int glyphDrawn = 0;
    int colorDrawn = 0;
    Image colorImg;  // emoji pictures (own colours), drawn on top of the text
    bool haveColor = false;
    std::vector<int> order;
    for (const LineRec& l : lines) {
        const std::vector<Cluster>& cl = pcl[l.para];
        // Start / end follow the paragraph's direction (Android's ALIGN_NORMAL / ALIGN_OPPOSITE): right to left swaps them.
        int al = rq.align;
        if (paraIsRtl((size_t)l.para)) al = al == 0 ? 2 : al == 2 ? 0 : al;
        const float off = lineOffset(al, l.width, tightW);
        float pen = rq.padH + off;
        const float baseY = rq.padV + l.base;
        visualOrder(cl, l.first, l.last, order);
        float visL = 0.f, visR = 0.f;  // pen span (from the line's start) of the clusters the reveal shows
        bool anyShown = false;
        for (int k : order) {
            const Cluster& c = cl[k];
            const bool shown = clusterShown(progressing ? clusterOrd[(size_t)l.para][(size_t)k] : -1);
            if (shown) {
                if (!anyShown) { visL = pen - (rq.padH + off); anyShown = true; }
                visR = pen - (rq.padH + off) + c.adv;
            }
            float unitMove = 0.f, unitAlpha = 1.f;  // (1 - arrived) and the unit's opacity
            if (animating) {
                const int u = unitOf[(size_t)l.para][(size_t)k];
                if (u >= 0) {
                    const float arr = arrived(u);
                    unitMove = 1.f - arr;
                    unitAlpha = rq.animAlpha + (1.f - rq.animAlpha) * arr;
                }
            }
            const float mvx = rq.animDx * unitMove, mvy = rq.animDy * unitMove;
            if (shown) for (int g = c.g0; g < c.g0 + c.gn; ++g) {
                const GlyphRec& gr = glyphs[g];
                Face& f = *fonts[gr.font];
                const bool bold = rq.weight >= 600 && f.weight < 600;
                const bool shear = rq.italic && !f.italic;
                const float x = ox + (pen + gr.x + mvx) * rs, y = oy + (baseY + gr.y + mvy) * rs;
                if (rq.drawOn) {
                    // The trimmed outline replaces everything else for this glyph: fully inside the
                    // window it is stroked whole, straddling an edge it is cut exactly at the edge.
                    if (f.color || f.colrEmoji) continue;  // no outline to draw on
                    const double g0 = glyphOff[(size_t)g], g1 = g0 + glyphLen[(size_t)g];
                    if (drawStroker && glyphLen[(size_t)g] > 0.f && g1 > drawS && g0 < drawE) {
                        if (g0 >= drawS && g1 <= drawE) {
                            if (rasterGlyph(f, gr.gid, px * rs, bold, shear, drawStroker, x, y, drawMask, imgW, imgH, unitAlpha)) ++glyphDrawn;
                        } else if (rasterGlyphTrimmed(f, gr.gid, px * rs, bold, shear, drawStroker,
                                                       (drawS - g0) * 64.0, (drawE - g0) * 64.0,
                                                       x, y, drawMask, imgW, imgH, unitAlpha)) {
                            ++glyphDrawn;
                        }
                    }
                    continue;
                }
                if (animating && !(f.color || f.colrEmoji)) {
                    const float tint = tintMask.empty() ? 0.f : unitMove;
                    const float gFill = unitAlpha * (1.f - tint), gTint = unitAlpha * tint;
                    if (wantFill && gFill > 0.002f && rasterGlyph(f, gr.gid, px * rs, bold, shear, nullptr, x, y, fillMask, imgW, imgH, gFill)) ++glyphDrawn;
                    if (!tintMask.empty() && gTint > 0.002f) rasterGlyph(f, gr.gid, px * rs, bold, shear, nullptr, x, y, tintMask, imgW, imgH, gTint);
                    if (stroker && unitAlpha > 0.002f) rasterGlyph(f, gr.gid, px * rs, bold, shear, stroker, x, y, strokeMask, imgW, imgH, unitAlpha);
                    continue;
                }
                if (f.color || f.colrEmoji) {
                    if (!haveColor) { colorImg.alloc(imgW, imgH); haveColor = true; }
                    if (drawColorGlyph(f, gr.gid, px * rs, x, y, colorImg)) ++colorDrawn;
                    continue;
                }
                if (wantFill && rasterGlyph(f, gr.gid, px * rs, bold, shear, nullptr, x, y, fillMask, imgW, imgH)) ++glyphDrawn;
                if (stroker) rasterGlyph(f, gr.gid, px * rs, bold, shear, stroker, x, y, strokeMask, imgW, imgH);
            }
            pen += c.adv;
        }
        // underline / strike-through over the visible part of the line (with a reveal: only the shown span)
        if ((rq.underline || rq.strike) && wantFill && l.width > 0.f && (!progressing || anyShown)) {
            const float spanL = progressing ? visL : 0.f, spanR = progressing ? visR : l.width;
            const float x0 = ox + (rq.padH + off + spanL) * rs, x1 = ox + (rq.padH + off + spanR) * rs;
            const float thick = std::max(1.f, px * rs / 16.f);
            if (rq.underline) {
                const float yy = oy + (baseY + px * 0.1f) * rs;
                maskRect(fillMask, imgW, imgH, x0, yy, x1, yy + thick);
            }
            if (rq.strike) {
                const float yy = oy + (baseY - px * 0.3f) * rs;
                maskRect(fillMask, imgW, imgH, x0, yy - thick * 0.5f, x1, yy + thick * 0.5f);
            }
        }
    }
    if (stroker) FT_Stroker_Done(stroker);
    if (drawStroker) FT_Stroker_Done(drawStroker);
    FT_Set_Transform(primary->ft, nullptr, nullptr);

    // ---- compose: background, outline, shadow, fill (same order as the Canvas version)
    if (rq.bgOn && argbA(rq.bgArgb) > 0)
        drawRoundRect(img, ox, oy, ox + boxWs, oy + boxHs, rq.bgCornerFrac * std::min(boxWs, boxHs), rq.bgArgb);
    if (wantStroke) compositeMask(img, strokeMask, rq.strokeArgb);
    if (rq.shadowOn && (wantFill || haveColor || rq.drawOn) && argbA(rq.shadowArgb) > 0) {
        std::vector<uint8_t> sh((size_t)imgW * imgH, 0);
        std::vector<uint8_t> ink;  // what casts the shadow: the text fill (or the drawn outline), and the emoji pictures
        if (wantFill) ink = fillMask; else if (rq.drawOn) ink = drawMask; else ink.assign((size_t)imgW * imgH, 0);
        if (haveColor) for (size_t i = 0; i < ink.size(); ++i) ink[i] = std::max(ink[i], colorImg.px[i * 4 + 3]);
        const int dx = (int)std::lround(rq.shadowDx * rs), dy = (int)std::lround(rq.shadowDy * rs);
        for (int y = 0; y < imgH; ++y) {
            const int sy = y - dy;
            if (sy < 0 || sy >= imgH) continue;
            for (int x = 0; x < imgW; ++x) {
                const int sx = x - dx;
                if (sx >= 0 && sx < imgW) sh[(size_t)y * imgW + x] = ink[(size_t)sy * imgW + sx];
            }
        }
        blurAlpha(sh, imgW, imgH, shadowSigma * rs);
        compositeMask(img, sh, rq.shadowArgb);
    }
    // The text block starts at (padH + dx, padV) inside the box; Android's block is maxTextW wide.
    const float dxBlock = rq.align == 0 ? 0.f : rq.align == 2 ? tightW - rq.maxTextW : (tightW - rq.maxTextW) * 0.5f;
    if (wantFill) {
        if (rq.fillKind == 0) {
            compositeMask(img, fillMask, rq.fillArgb);
        } else {
            compositeFill(img, fillMask, rq, ox + (rq.padH + dxBlock) * rs, oy + rq.padV * rs, rs, rq.maxTextW, layoutH);
        }
    }
    if (rq.drawOn) {  // the trimmed outline is painted like the fill it replaces
        if (rq.fillKind == 0) {
            compositeMask(img, drawMask, rq.fillArgb);
        } else {
            compositeFill(img, drawMask, rq, ox + (rq.padH + dxBlock) * rs, oy + rq.padV * rs, rs, rq.maxTextW, layoutH);
        }
    }
    if (!tintMask.empty()) compositeMask(img, tintMask, rq.animArgb | 0xFF000000u);
    if (haveColor) {  // emoji keep their own colours: on top of the fill, never tinted or outlined
        BlitParams cp;
        blit(img.px.data(), imgW, imgH, imgW * 4, colorImg, cp);
    }

    // ---- blit
    BlitParams bp;
    bp.alpha = rq.alpha;
    bp.clipL = ox + rq.crop[0] * boxWs; bp.clipT = oy + rq.crop[1] * boxHs;
    bp.clipR = ox + rq.crop[2] * boxWs; bp.clipB = oy + rq.crop[3] * boxHs;
    if (rq.crop[0] <= 0.f && rq.crop[1] <= 0.f && rq.crop[2] >= 1.f && rq.crop[3] >= 1.f) {
        bp.clipL = 0; bp.clipT = 0; bp.clipR = (float)imgW; bp.clipB = (float)imgH;  // nothing cropped: keep shadow / outline
    }
    if (translateOnly) {
        bp.srcToDst.e = (float)(ix0 - M);
        bp.srcToDst.f = (float)(iy0 - M);
    } else {
        const float rad = rq.rotationDeg * 3.14159265358979f / 180.f;
        const float co = std::cos(rad), si = std::sin(rad);
        // dest = C + R * F * ratio * (b - (M + box/2))
        const float A00 = co * rq.flipX * ratio, A01 = -si * rq.flipY * ratio;
        const float A10 = si * rq.flipX * ratio, A11 = co * rq.flipY * ratio;
        const float bx = M + boxWs * 0.5f, by = M + boxHs * 0.5f;
        bp.srcToDst.a = A00; bp.srcToDst.c = A01; bp.srcToDst.b = A10; bp.srcToDst.d = A11;
        bp.srcToDst.e = rq.cx - (A00 * bx + A01 * by);
        bp.srcToDst.f = rq.cy - (A10 * bx + A11 * by);
    }
    blit(dst, dstW, dstH, dstStride, img, bp);

    if (st) {
        st->clusters = clusterCount; st->glyphs = (int)glyphs.size(); st->lines = (int)lines.size();
        st->fonts = (int)fonts.size(); st->colorGlyphs = colorDrawn; st->boxW = boxW; st->boxH = boxH; st->imgW = imgW; st->imgH = imgH;
        (void)glyphDrawn;
    }
    return true;
}

#endif  // AM_TEXT_ENGINE

}  // namespace text
}  // namespace am
