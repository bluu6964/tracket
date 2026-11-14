// Native text layers: shaping (HarfBuzz), glyph drawing (FreeType), line breaking, outline /
// shadow / background box, and the transformed blit onto an Android bitmap.
// It reproduces LayerPainter.drawText (VideoExporter.kt) - same sizes, padding, alignment - but
// honours the chosen font file and shapes Bengali conjuncts with HarfBuzz. Only compiled when
// FreeType + HarfBuzz sources are present (AM_TEXT_ENGINE, see CMakeLists.txt); otherwise
// available() is false and Kotlin keeps drawing text with the Android Canvas.
#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace am {
namespace text {

struct Request {
    std::string text;                 // UTF-8
    std::string fontPath;             // font file chosen for the layer, empty = system font
    std::string family = "sans-serif";  // system family used when fontPath is empty (and for missing glyphs)
    int weight = 400;
    bool italic = false;

    float fontPx = 40.f;              // all sizes below are in export pixels at scale 1
    float letterSpacing = 0.f;        // px added after every cluster
    float lineMult = 1.f;
    int align = 1;                    // 0 start, 1 centre, 2 end
    float maxTextW = 100.f;           // layout width (int in Kotlin)
    float padH = 0.f, padV = 0.f;

    float scale = 1.f;                // layer scale (>0)
    float rotationDeg = 0.f;
    float flipX = 1.f, flipY = 1.f;   // +1 / -1
    float cx = 0.f, cy = 0.f;         // centre of the box on the frame
    float alpha = 1.f;
    float crop[4] = {0.f, 0.f, 1.f, 1.f};  // l, t, r, b as fractions of the box

    bool underline = false, strike = false;
    bool fillOn = true;
    uint32_t fillArgb = 0xFFFFFFFFu;
    // Fill source (fillOn must be true): 0 = solid fillArgb, 1 = two-colour gradient, 2 = picture. Coordinates are the
    // layer's text block (maxTextW wide, all lines high), like Android's shader in LayerPainter.drawText.
    int fillKind = 0;
    int gradType = 0;                 // 0 linear, 1 radial, 2 sweep
    uint32_t grad1 = 0xFF1FD91Fu, grad2 = 0xFFFFFFFFu;  // straight ARGB
    float gx1 = 0.05f, gy1 = 0.5f, gx2 = 0.95f, gy2 = 0.5f;  // 0..1 inside the block
    // Picture fill: premultiplied RGBA8888 (an Android Bitmap), mode 0 fill (cover), 1 fit, 2 stretch.
    // fillKind 2 without pixels draws no fill (picture missing / not loaded yet), like the Canvas version.
    const uint8_t* fillPix = nullptr;
    int fillW = 0, fillH = 0, fillStride = 0;
    int fillImgMode = 0;
    // Right-to-left text: the embedding level of every code point (UAX #9, from java.text.Bidi), in the order the
    // code points appear after paragraph splitting (new lines, '\r' and control characters removed, tab = space), and
    // one flag per paragraph (1 = the paragraph itself runs right to left). Empty = everything left to right;
    // then a right-to-left letter makes draw() return false (nothing can be laid out without levels).
    std::vector<uint8_t> bidiLevels;
    std::vector<uint8_t> paraRtl;
    float strokeW = 0.f;              // px (0 = none)
    uint32_t strokeArgb = 0xFF000000u;
    bool shadowOn = false;
    float shadowBlur = 0.f, shadowDx = 0.f, shadowDy = 0.f;  // px
    uint32_t shadowArgb = 0x80000000u;
    bool bgOn = false;
    uint32_t bgArgb = 0u;
    float bgCornerFrac = 0.f;         // corner radius as a fraction of min(boxW, boxH)

    // Text animator (Alight Motion "texttransform", already evaluated for this frame by Kotlin: TextAnimEval.kt).
    // The text is cut into units (0 = every letter, 1 = every word, 2 = every line); the range [animStart, animEnd]
    // (0..1 of all units) says how far each unit has "arrived". A unit with arrived fraction c is drawn moved by
    // (animDx, animDy) * (1 - c) px (scale 1), with its opacity going from animAlpha (c = 0) to 1 (c = 1), and (when
    // animRecolor) its colour going from animArgb (c = 0) to the normal fill (c = 1). animOn = false: nothing changes.
    bool animOn = false;
    int animComponent = 2;
    float animStart = 0.f, animEnd = 1.f;
    float animDx = 0.f, animDy = 0.f;
    float animAlpha = 1.f;
    bool animRecolor = false;
    uint32_t animArgb = 0xFF00FF00u;

    // Text progress (Alight Motion "textprogress", already evaluated for this frame by Kotlin:
    // TextProgressEval.kt): a cluster is drawn when its slice of the whole text (counted in shaped
    // clusters, logical order) INTERSECTS [progressStart, progressEnd] at all — frame-measured
    // against Alight's ST Intro export (round 5): at end 0.378 five of twelve clusters show and at
    // 0.963 all twelve do, which the older lies-fully-inside rule (4 and 11) missed both times.
    // The layout is computed from the full text, so revealed characters appear in place. The cut is by
    // shaped cluster, so a Bengali conjunct is never split. progressOn = false: everything is drawn.
    // The effect's typing cursor is not part of this yet (only the corpus value cursor = 0, no cursor,
    // is handed over as progressOn at all - see the importer).
    bool progressOn = false;
    float progressStart = 0.f, progressEnd = 1.f;

    // Drawing progress (Alight Motion "drawing.progress" on a text layer, window already evaluated
    // for this frame by Kotlin: TextNative.kt): the glyphs' OUTLINES are drawn stroked in the fill
    // colour, trimmed to [drawStart, drawEnd] of the concatenated outline length of the whole text
    // (draw order: line by line, visual order inside a line) — the draw-on handwriting look. The
    // fill, the layer's own stroke ring and the underline / strike are not drawn in this mode;
    // colour (emoji) glyphs have no outline and are skipped. drawStrokeW is the outline width in
    // px at scale 1 (the layer's text stroke width when it has one, else a default share of the
    // font size — the choice is made in Kotlin, see drawOnStrokeWidthPx).
    bool drawOn = false;
    float drawStart = 0.f, drawEnd = 1.f;
    float drawStrokeW = 0.f;

    // Text-on-path (build 191; the layout law is model/TextOnPath.kt, the walk is PathWalk in
    // text_layout.h): the text is laid along a polyline instead of into lines. [pathPts] are
    // x,y pairs in FRAME pixels — the caller (Kotlin) has already evaluated the path layer at
    // this frame and mapped its outline into frame space, so the path's own animation is
    // already in the points. Glyphs are placed by arc length: cluster advances (which include
    // letterSpacing) times [scale] give frame distances; the first glyph's origin sits at
    // [pathStartFrac] x the total length; a glyph whose origin's distance reaches the total
    // is not drawn (text longer than the path is clipped at its end). Each glyph's baseline
    // origin sits ON the path, rotated by the tangent there. Paragraphs run on continuously
    // (no jump at a paragraph break); for the texttransform animator a paragraph counts as
    // one "line" unit.
    // In path mode the layer's own placement fields (cx, cy, rotationDeg, flips) do NOT move
    // the text — the path is the placement; [scale] still sets the glyph size. What composes:
    // fill (solid / gradient / picture — the gradient's block is the path's bounding box),
    // the stroke ring, the shadow, the texttransform animator (its offsets apply in frame
    // space), the textprogress window, colour glyphs (rotated like the rest). What does NOT
    // compose and is ignored: the background box, underline / strike (they are line-span
    // rectangles; a curve has no line spans), crop (it is a fraction of the layout box, and
    // there is no box), wrap / alignment / line height (a single run along the path), and
    // drawing.progress (an outline trim along the GLYPH outlines cannot also follow the
    // path in this build — Kotlin does not hand it over for an attached layer).
    bool pathOn = false;
    std::vector<float> pathPts;
    float pathStartFrac = 0.f;
};

/** What a system font match returns (Android AFontMatcher). */
struct MatchResult {
    std::string path;
    int index = 0;
    int weight = 400;
    bool italic = false;
    std::vector<std::pair<uint32_t, float>> axes;  // variation axes (tag, value)
    uint32_t runLen = 0;                             // UTF-16 units covered
};
using Matcher = std::function<bool(const std::string& family, int weight, bool italic, const uint16_t* text,
                                   uint32_t len, MatchResult& out)>;

struct Stats {
    int clusters = 0, glyphs = 0, lines = 0, fonts = 0;
    int colorGlyphs = 0;           // emoji pictures drawn
    float boxW = 0.f, boxH = 0.f;  // layout box at scale 1
    int imgW = 0, imgH = 0;        // rendered picture
};

/** True when the engine is compiled in (FreeType + HarfBuzz). */
bool available();

/**
 * True when colour emoji can be drawn here: either the device's emoji font is a bitmap font (CBDT / CBLC) and the
 * system can decode its PNG pictures (Android 11+), or it is a COLR font (v0 / v1, colr_render.cpp, no PNG needed).
 * Layers with emoji are only handed to draw() when this is true (draw() returns false for them otherwise).
 */
bool emojiAvailable();

/** PNG decoder for host tests: premultiplied RGBA8888 out. nullptr removes it. */
using PngDecoder = std::function<bool(const uint8_t* data, size_t len, std::vector<uint8_t>& rgbaPremul, int& w, int& h)>;
void setPngDecoderForTest(PngDecoder d);

/** Host tests: the emoji picture of [codepoint] from the colour bitmap font at [fontPath], strike nearest to [wantPpem]. */
bool colorGlyphForTest(const std::string& fontPath, uint32_t codepoint, float wantPpem, int* w, int* h, int* bearingX,
                       int* bearingY, int* strikePpem, std::vector<uint8_t>* rgbaPremul);

/** Replaces the system font matcher (host tests). */
void setMatcherForTest(Matcher m);

/**
 * Draws one text layer onto an RGBA_8888 premultiplied bitmap. Returns false when it cannot (script
 * it does not handle - emoji without a usable emoji font, font not loadable, engine missing): nothing is
 * drawn then and the caller must draw the layer another way.
 */
bool draw(uint8_t* dst, int dstW, int dstH, int dstStride, const Request& rq, Stats* stats = nullptr,
          std::string* err = nullptr);

}  // namespace text
}  // namespace am
