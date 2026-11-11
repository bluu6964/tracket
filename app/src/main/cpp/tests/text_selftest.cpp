// Host self-test for the native text renderer (blit, blur, line breaking, shaping, drawing):
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -Wall -Wextra -DAM_TEXT_ENGINE=1 -I.. -Ihost_stub -I/usr/include/freetype2
//       ../engine/text_blit.cpp ../engine/text_layout.cpp ../engine/text_engine.cpp text_selftest.cpp
//       -lfreetype -lpng /usr/lib/x86_64-linux-gnu/libharfbuzz.so.0 -o text_selftest
//   ASAN_OPTIONS=detect_leaks=0 ./text_selftest      (font faces are cached for the whole process on purpose)
// Font paths below are the ones of the dev box; the engine tests are skipped when a font is missing.
// The emoji tests also need libpng and FreeType headers: add  -lpng  to the command above.
// The COLR emoji test needs /tmp/am_colr_test.ttf: python3 make_colr_font.py (and ../engine/colr_render.cpp in the command).
#include <png.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "engine/text_blit.h"
#include "engine/text_engine.h"
#include "engine/text_layout.h"
#include "engine/outline_trim.h"

using namespace am::text;

static int g_fail = 0, g_checks = 0;
#define CHECK(cond)                                                                      \
    do {                                                                                 \
        ++g_checks;                                                                      \
        if (!(cond)) { ++g_fail; std::printf("FAIL line %d: %s\n", __LINE__, #cond); }   \
    } while (0)

static bool fileExists(const char* p) { FILE* f = std::fopen(p, "rb"); if (f) std::fclose(f); return f != nullptr; }

static Image solid(int w, int h, uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    Image im; im.alloc(w, h);
    for (int i = 0; i < w * h; ++i) { im.px[i * 4] = r; im.px[i * 4 + 1] = g; im.px[i * 4 + 2] = b; im.px[i * 4 + 3] = a; }
    return im;
}
static long alphaSum(const std::vector<uint8_t>& px) { long s = 0; for (size_t i = 3; i < px.size(); i += 4) s += px[i]; return s; }

static void testLayout() {
    auto mk = [](std::initializer_list<std::pair<float, bool>> l) { std::vector<Item> v; for (auto& p : l) { Item i; i.adv = p.first; i.space = p.second; v.push_back(i); } return v; };
    // "ab cd" : a=10 b=10 ' '=5 c=10 d=10, width 100 -> one line, 45 wide
    auto items = mk({{10, 0}, {10, 0}, {5, 1}, {10, 0}, {10, 0}});
    auto l = breakLines(items, 100);
    CHECK(l.size() == 1 && l[0].first == 0 && l[0].last == 5 && std::fabs(l[0].width - 45.f) < 1e-4f);
    // maxW 30: "ab " | "cd"
    l = breakLines(items, 30);
    CHECK(l.size() == 2 && l[0].last == 3 && std::fabs(l[0].width - 20.f) < 1e-4f && l[1].first == 3 && std::fabs(l[1].width - 20.f) < 1e-4f);
    // a word longer than the line is cut between clusters
    auto w = mk({{10, 0}, {10, 0}, {10, 0}, {10, 0}, {10, 0}});
    l = breakLines(w, 25);
    CHECK(l.size() == 3 && l[0].last == 2 && l[1].last == 4 && l[2].last == 5);
    // empty paragraph = one empty line
    l = breakLines({}, 50);
    CHECK(l.size() == 1 && l[0].width == 0.f);
    // trailing spaces never overflow
    auto t = mk({{10, 0}, {10, 0}, {10, 1}, {10, 1}});
    l = breakLines(t, 20);
    CHECK(l.size() == 1);
    CHECK(lineOffset(0, 10, 100) == 0.f && lineOffset(1, 40, 100) == 30.f && lineOffset(2, 40, 100) == 60.f);
    CHECK(extraSpacing(-10, 4, 1.0f) == 0 && extraSpacing(-10, 4, 1.5f) == 7 && extraSpacing(-10, 4, 0.5f) == -7);
}

static void testBlit() {
    // exact translation, alpha 1
    Image src = solid(4, 3, 255, 0, 0, 255);
    std::vector<uint8_t> dst(10 * 8 * 4, 0);
    BlitParams bp; bp.srcToDst.e = 2; bp.srcToDst.f = 3;
    blit(dst.data(), 10, 8, 40, src, bp);
    CHECK(dst[(3 * 10 + 2) * 4] == 255 && dst[(3 * 10 + 2) * 4 + 3] == 255);
    CHECK(dst[(3 * 10 + 1) * 4 + 3] == 0 && dst[(6 * 10 + 2) * 4 + 3] == 0);
    CHECK(alphaSum(dst) == 255L * 12);
    // clipped at the bitmap edge
    std::fill(dst.begin(), dst.end(), 0);
    bp.srcToDst.e = 8; bp.srcToDst.f = 6;
    blit(dst.data(), 10, 8, 40, src, bp);
    CHECK(alphaSum(dst) == 255L * 4);
    // 50 % alpha over a white background (premultiplied)
    std::fill(dst.begin(), dst.end(), 255);
    bp.srcToDst.e = 0; bp.srcToDst.f = 0; bp.alpha = 0.5f;
    blit(dst.data(), 10, 8, 40, src, bp);
    CHECK(std::abs((int)dst[0] - 255) <= 1 && std::abs((int)dst[1] - 128) <= 1 && dst[3] == 255);
    // crop
    std::fill(dst.begin(), dst.end(), 0);
    bp.alpha = 1.f; bp.clipL = 1; bp.clipT = 1; bp.clipR = 3; bp.clipB = 2;
    blit(dst.data(), 10, 8, 40, src, bp);
    CHECK(alphaSum(dst) == 255L * 2);
    // rotation by 90 degrees about the centre keeps the area (general path)
    Image big = solid(20, 10, 0, 255, 0, 255);
    std::vector<uint8_t> d2(40 * 40 * 4, 0);
    BlitParams r; r.srcToDst.a = 0; r.srcToDst.b = 1; r.srcToDst.c = -1; r.srcToDst.d = 0;  // (x,y) -> (-y, x)
    r.srcToDst.e = 25; r.srcToDst.f = 10;
    blit(d2.data(), 40, 40, 160, big, r);
    const long a = alphaSum(d2);
    CHECK(std::labs(a - 255L * 200) < 255L * 12);
    CHECK(d2[(20 * 40 + 20) * 4 + 3] == 255);
    // blur keeps the energy and spreads it
    std::vector<uint8_t> m(31 * 31, 0);
    m[15 * 31 + 15] = 255;
    blurAlpha(m, 31, 31, 3.f);
    long e = 0; for (auto v : m) e += v;
    CHECK(std::labs(e - 255) < 40);
    CHECK(m[15 * 31 + 15] < 255 && m[15 * 31 + 18] > 0);
}

static int firstOpaqueCols(const std::vector<uint8_t>& px, int w, int h, int* minX, int* maxX, int* minY, int* maxY) {
    int n = 0; *minX = w; *maxX = -1; *minY = h; *maxY = -1;
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) if (px[((size_t)y * w + x) * 4 + 3] > 40) {
        ++n; *minX = std::min(*minX, x); *maxX = std::max(*maxX, x); *minY = std::min(*minY, y); *maxY = std::max(*maxY, y);
    }
    return n;
}

static void testEngine() {
    const char* latin = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    const char* bangla = "/usr/share/fonts/truetype/freefont/FreeSans.ttf";
    if (!fileExists(latin)) { std::printf("skip engine tests (no %s)\n", latin); return; }
    CHECK(available());
    // fake system matcher: always returns the Bengali-capable font
    setMatcherForTest([&](const std::string&, int, bool, const uint16_t*, uint32_t, MatchResult& out) {
        out.path = fileExists(bangla) ? bangla : latin; out.runLen = 1; out.weight = 400; return true;
    });
    const int W = 600, H = 300;
    std::vector<uint8_t> bmp((size_t)W * H * 4, 0);
    Request r;
    r.text = "Hello";
    r.fontPath = latin;
    r.fontPx = 60; r.maxTextW = 500; r.padH = 15; r.padV = 7; r.cx = 300; r.cy = 150;
    Stats st;
    std::string err;
    CHECK(draw(bmp.data(), W, H, W * 4, r, &st, &err));
    int x0, x1, y0, y1;
    int n = firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1);
    CHECK(n > 300);
    CHECK(st.lines == 1 && st.clusters == 5 && st.glyphs == 5);
    // centred on cx (box centre = 300, text is inside the padding)
    CHECK(std::abs(((x0 + x1) / 2) - 300) < 20);
    CHECK(y0 > 150 - st.boxH / 2 - 2 && y1 < 150 + st.boxH / 2 + 2);

    // letters do not change when drawn twice (deterministic) and scale 2 doubles the width
    std::vector<uint8_t> b2((size_t)W * H * 4, 0);
    r.scale = 2.f;
    CHECK(draw(b2.data(), W, H, W * 4, r));
    int a0, a1, c0, c1;
    firstOpaqueCols(b2, W, H, &a0, &a1, &c0, &c1);
    CHECK((a1 - a0) > (x1 - x0) * 1.8f && (a1 - a0) < (x1 - x0) * 2.2f);
    r.scale = 1.f;

    // wrapping: a narrow layout makes several lines and a taller box
    r.text = "one two three four";
    r.maxTextW = 200;
    Stats s2;
    std::fill(bmp.begin(), bmp.end(), 0);
    CHECK(draw(bmp.data(), W, H, W * 4, r, &s2));
    CHECK(s2.lines >= 3 && s2.boxH > st.boxH * 2.5f);
    // explicit new line
    r.text = "ab\ncd"; r.maxTextW = 500;
    CHECK(draw(bmp.data(), W, H, W * 4, r, &s2) && s2.lines == 2);

    // outline + shadow + background do not crash and add pixels around the text
    r.text = "Hi"; r.strokeW = 6; r.strokeArgb = 0xFFFF0000; r.shadowOn = true; r.shadowBlur = 20; r.shadowDx = 8; r.shadowDy = 8;
    r.shadowArgb = 0xFF000000; r.bgOn = true; r.bgArgb = 0xFF0000FF; r.bgCornerFrac = 0.3f; r.underline = true;
    std::fill(bmp.begin(), bmp.end(), 0);
    CHECK(draw(bmp.data(), W, H, W * 4, r));
    bool sawRed = false, sawBlue = false;
    for (size_t i = 0; i < bmp.size(); i += 4) { if (bmp[i] > 200 && bmp[i + 1] < 40 && bmp[i + 3] > 200) sawRed = true; if (bmp[i + 2] > 200 && bmp[i] < 40 && bmp[i + 3] > 200) sawBlue = true; }
    CHECK(sawRed && sawBlue);

    // rotation 90: the box becomes tall
    r = Request(); r.text = "WWWW"; r.fontPath = latin; r.fontPx = 50; r.maxTextW = 500; r.cx = 300; r.cy = 150; r.rotationDeg = 90.f;
    std::fill(bmp.begin(), bmp.end(), 0);
    CHECK(draw(bmp.data(), W, H, W * 4, r));
    firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1);
    CHECK((y1 - y0) > (x1 - x0));
    // crop to the left half
    r.rotationDeg = 0; r.crop[2] = 0.5f;
    std::fill(bmp.begin(), bmp.end(), 0);
    CHECK(draw(bmp.data(), W, H, W * 4, r));
    firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1);
    CHECK(x1 <= 300 + 2);

    // fallback: no font file, the matcher answers
    r = Request(); r.text = "Abc"; r.fontPx = 40; r.maxTextW = 400; r.cx = 300; r.cy = 150;
    std::fill(bmp.begin(), bmp.end(), 0);
    CHECK(draw(bmp.data(), W, H, W * 4, r));
    CHECK(firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1) > 100);

    // unsupported: right-to-left and emoji -> false and nothing drawn
    r.text = "\xD7\xA9\xD7\x9C\xD7\x95\xD7\x9D";  // Hebrew
    std::fill(bmp.begin(), bmp.end(), 0);
    CHECK(!draw(bmp.data(), W, H, W * 4, r, nullptr, &err));
    r.text = "hi \xF0\x9F\x98\x80";
    CHECK(!draw(bmp.data(), W, H, W * 4, r, nullptr, &err));

    if (fileExists(bangla)) {
        // Bengali: "ক্ষ" = 3 code points that shape into ONE cluster; the whole word draws
        r = Request(); r.fontPath = bangla; r.fontPx = 60; r.maxTextW = 500; r.cx = 300; r.cy = 150;
        r.text = "\xE0\xA6\x95\xE0\xA7\x8D\xE0\xA6\xB7";  // ক ্ ষ
        Stats sb;
        std::fill(bmp.begin(), bmp.end(), 0);
        CHECK(draw(bmp.data(), W, H, W * 4, r, &sb, &err));
        CHECK(sb.clusters == 1);
        CHECK(firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1) > 200);
        r.text = "\xE0\xA6\xAC\xE0\xA6\xBE\xE0\xA6\x82\xE0\xA6\xB2\xE0\xA6\xBE";  // বাংলা
        CHECK(draw(bmp.data(), W, H, W * 4, r, &sb, &err));
        CHECK(sb.clusters >= 2 && sb.clusters <= 4);
        // Latin primary font without Bengali: the Bengali letters come from the fallback font
        r.fontPath = latin; r.text = "A \xE0\xA6\xAC\xE0\xA6\xBE\xE0\xA6\x82\xE0\xA6\xB2\xE0\xA6\xBE";
        CHECK(draw(bmp.data(), W, H, W * 4, r, &sb, &err));
        CHECK(sb.fonts == 2);
    }
}

// Gradient / picture fill: colour depends on the position inside the text block.
static void testFill() {
    const char* latin = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf";
    if (!fileExists(latin)) { std::printf("skip fill tests\n"); return; }
    const int W = 600, H = 300;
    Request r;
    r.text = "MMMMMM";
    r.fontPath = latin;
    r.fontPx = 90; r.maxTextW = 560; r.padH = 10; r.padV = 6; r.cx = 300; r.cy = 150;
    // linear red -> blue across the block: left letters red-ish, right letters blue-ish
    r.fillKind = 1; r.gradType = 0; r.grad1 = 0xFFFF0000u; r.grad2 = 0xFF0000FFu;
    r.gx1 = 0.f; r.gy1 = 0.5f; r.gx2 = 1.f; r.gy2 = 0.5f;
    std::vector<uint8_t> bmp((size_t)W * H * 4, 0);
    std::string err;
    CHECK(draw(bmp.data(), W, H, W * 4, r, nullptr, &err));
    long lr = 0, lb = 0, rr = 0, rb = 0;
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            const uint8_t* q = &bmp[((size_t)y * W + x) * 4];
            if (q[3] < 250) continue;
            if (x < 200) { lr += q[0]; lb += q[2]; }
            if (x > 400) { rr += q[0]; rb += q[2]; }
        }
    CHECK(lr > lb * 2);   // left: mostly red
    CHECK(rb > rr * 2);   // right: mostly blue
    // sweep + radial draw something
    r.gradType = 1; CHECK(draw(bmp.data(), W, H, W * 4, r));
    r.gradType = 2; CHECK(draw(bmp.data(), W, H, W * 4, r));
    // picture fill: 2x2 premultiplied picture, left half green, right half yellow, stretched
    uint8_t pic[2 * 2 * 4] = {0, 255, 0, 255,  255, 255, 0, 255,  0, 255, 0, 255,  255, 255, 0, 255};
    r.fillKind = 2; r.fillPix = pic; r.fillW = 2; r.fillH = 2; r.fillStride = 8; r.fillImgMode = 2;
    std::fill(bmp.begin(), bmp.end(), 0);
    CHECK(draw(bmp.data(), W, H, W * 4, r, nullptr, &err));
    long lb2 = 0, rb2 = 0;
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            const uint8_t* q = &bmp[((size_t)y * W + x) * 4];
            if (q[3] < 250) continue;
            if (x < 250) lb2 += q[0];   // green: red channel ~0
            if (x > 350) rb2 += q[0];   // yellow: red channel ~255
        }
    CHECK(rb2 > lb2 * 4);
    // picture mode with no pixels: nothing is drawn, still succeeds
    r.fillPix = nullptr;
    std::fill(bmp.begin(), bmp.end(), 0);
    CHECK(draw(bmp.data(), W, H, W * 4, r));
    CHECK(alphaSum(bmp) == 0);
}

// Right-to-left text: levels come from the caller (java.text.Bidi in the app).
static void testRtl() {
    const char* font = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    if (!fileExists(font)) { std::printf("skip rtl tests\n"); return; }
    const int W = 600, H = 300;
    Request r;
    r.fontPath = font;
    r.fontPx = 70; r.maxTextW = 560; r.padH = 10; r.padV = 6; r.cx = 300; r.cy = 150;
    std::vector<uint8_t> a((size_t)W * H * 4, 0), b((size_t)W * H * 4, 0);
    std::string err;
    // alef bet gimel (3 letters) without levels: refused (the caller must draw it another way)
    r.text = "\xD7\x90\xD7\x91\xD7\x92";
    CHECK(!draw(a.data(), W, H, W * 4, r, nullptr, &err));
    // same letters, level 0 (left to right) vs level 1 in a right-to-left paragraph: mirrored order,
    // so the ink of the LAST letter sits on the right in A and on the left in B.
    r.bidiLevels = {0, 0, 0}; r.paraRtl = {0};
    Stats sa;
    CHECK(draw(a.data(), W, H, W * 4, r, &sa, &err));
    r.bidiLevels = {1, 1, 1}; r.paraRtl = {1};
    Stats sb;
    CHECK(draw(b.data(), W, H, W * 4, r, &sb, &err));
    CHECK(sa.clusters == 3 && sb.clusters == 3);
    int ax0, ax1, ay0, ay1, bx0, bx1, by0, by1;
    CHECK(firstOpaqueCols(a, W, H, &ax0, &ax1, &ay0, &ay1) > 100);
    CHECK(firstOpaqueCols(b, W, H, &bx0, &bx1, &by0, &by1) > 100);
    auto thirdInk = [&](const std::vector<uint8_t>& im, int x0, int x1, bool left) {
        const int w3 = (x1 - x0) / 3;
        const int from = left ? x0 : x1 - w3, to = left ? x0 + w3 : x1;
        long sum = 0;
        for (int y = 0; y < H; ++y) for (int x = from; x < to; ++x) sum += im[((size_t)y * W + x) * 4 + 3];
        return sum;
    };
    const long gimelRightLtr = thirdInk(a, ax0, ax1, false), gimelLeftRtl = thirdInk(b, bx0, bx1, true);
    CHECK(gimelRightLtr > 1000 && std::labs(gimelRightLtr - gimelLeftRtl) * 10 < gimelRightLtr);  // within 10 %
    const long alefLeftLtr = thirdInk(a, ax0, ax1, true), alefRightRtl = thirdInk(b, bx0, bx1, false);
    CHECK(alefLeftLtr > 1000 && std::labs(alefLeftLtr - alefRightRtl) * 10 < alefLeftLtr);
    // Arabic joins: "salam" (seen lam alef meem) is shaped to fewer / different clusters, and draws
    r.text = "\xD8\xB3\xD9\x84\xD8\xA7\xD9\x85";
    r.bidiLevels = {1, 1, 1, 1}; r.paraRtl = {1};
    std::fill(a.begin(), a.end(), 0);
    Stats sc;
    CHECK(draw(a.data(), W, H, W * 4, r, &sc, &err));
    CHECK(sc.clusters >= 2 && sc.clusters <= 4);
    CHECK(firstOpaqueCols(a, W, H, &ax0, &ax1, &ay0, &ay1) > 100);
    // mixed line, left to right paragraph: "ab " + hebrew + " cd"; Latin stays level 0, Hebrew level 1
    r.text = "ab \xD7\x90\xD7\x91 cd";
    r.bidiLevels = {0, 0, 0, 1, 1, 0, 0, 0}; r.paraRtl = {0};
    std::fill(a.begin(), a.end(), 0);
    Stats sd;
    CHECK(draw(a.data(), W, H, W * 4, r, &sd, &err));
    CHECK(sd.clusters == 8 && sd.lines == 1);
    // wrong number of levels is rejected
    r.bidiLevels = {0, 0, 1};
    CHECK(!draw(a.data(), W, H, W * 4, r, nullptr, &err));
    // two paragraphs, second right to left, wraps into lines without losing clusters
    r.text = "one\n\xD7\x90\xD7\x91 \xD7\x92\xD7\x90 \xD7\x91\xD7\x92";
    r.bidiLevels = {0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1}; r.paraRtl = {0, 1};
    r.maxTextW = 120;
    Stats se;
    std::fill(a.begin(), a.end(), 0);
    CHECK(draw(a.data(), W, H, W * 4, r, &se, &err));
    CHECK(se.lines >= 3 && se.clusters == 3 + 8);
}


// ---- colour emoji (CBDT font read by hand, PNG decoded by libpng here / AImageDecoder on the phone)
static bool hostPng(const uint8_t* d, size_t n, std::vector<uint8_t>& px, int& w, int& h) {
    png_image img;
    std::memset(&img, 0, sizeof img);
    img.version = PNG_IMAGE_VERSION;
    if (!png_image_begin_read_from_memory(&img, d, n)) return false;
    img.format = PNG_FORMAT_RGBA;  // straight alpha
    w = (int)img.width; h = (int)img.height;
    px.resize(PNG_IMAGE_SIZE(img));
    if (!png_image_finish_read(&img, nullptr, px.data(), 0, nullptr)) { png_image_free(&img); return false; }
    for (size_t i = 0; i + 3 < px.size(); i += 4) {  // premultiply
        const unsigned a = px[i + 3];
        px[i] = (uint8_t)((px[i] * a + 127) / 255); px[i + 1] = (uint8_t)((px[i + 1] * a + 127) / 255); px[i + 2] = (uint8_t)((px[i + 2] * a + 127) / 255);
    }
    return true;
}

static void testEmoji() {
    const char* latin = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    const char* emoji = "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf";
    if (!fileExists(latin) || !fileExists(emoji)) { std::printf("skip emoji tests (font missing)\n"); return; }

    // 1. The hand-written CBLC / CBDT reader gives exactly what FreeType (with libpng) gives for the same glyphs.
    setPngDecoderForTest(hostPng);
    {
        FT_Library lib; FT_Face face;
        CHECK(FT_Init_FreeType(&lib) == 0);
        CHECK(FT_New_Face(lib, emoji, 0, &face) == 0);
        FT_Select_Size(face, 0);
        const uint32_t cps[] = {0x1F600, 0x1F44D, 0x2764, 0x1F1FA, 0x1F680, 0x1F9D1, 0x0031, 0x1F3FD};
        int compared = 0;
        for (uint32_t cp : cps) {
            int w = 0, h = 0, bx = 0, by = 0, ppem = 0;
            std::vector<uint8_t> mine;
            if (!colorGlyphForTest(emoji, cp, 64.f, &w, &h, &bx, &by, &ppem, &mine)) continue;
            const FT_UInt gid = FT_Get_Char_Index(face, cp);
            CHECK(FT_Load_Glyph(face, gid, FT_LOAD_COLOR) == 0);
            const FT_Bitmap& bm = face->glyph->bitmap;
            CHECK(ppem == 109);
            CHECK((int)bm.width == w && (int)bm.rows == h);
            CHECK(face->glyph->bitmap_left == bx && face->glyph->bitmap_top == by);
            long diff = 0;
            for (int y = 0; y < h && (int)bm.width == w; ++y) for (int x = 0; x < w; ++x) {
                const uint8_t* f = bm.buffer + (size_t)y * bm.pitch + (size_t)x * 4;  // B G R A premultiplied
                const uint8_t* m = &mine[((size_t)y * w + x) * 4];                       // R G B A premultiplied
                diff += std::abs((int)f[0] - (int)m[2]) + std::abs((int)f[1] - (int)m[1]) + std::abs((int)f[2] - (int)m[0]) + std::abs((int)f[3] - (int)m[3]);
            }
            CHECK(diff <= (long)w * h / 4);  // same PNG: identical up to the premultiply rounding
            ++compared;
        }
        CHECK(compared >= 6);
        FT_Done_Face(face); FT_Done_FreeType(lib);
        // a glyph without a picture (space) is just "nothing", not a crash
        int w = 0, h = 0, bx = 0, by = 0, ppem = 0;
        CHECK(!colorGlyphForTest(emoji, 0x20, 64.f, &w, &h, &bx, &by, &ppem, nullptr));
    }

    // 2. Layout and drawing. The fake system matcher answers with the emoji font for anything that is not plain text.
    setMatcherForTest([&](const std::string&, int, bool, const uint16_t* t, uint32_t len, MatchResult& out) {
        bool emo = false;
        for (uint32_t i = 0; i < len; ++i) if ((t[i] >= 0xD800 && t[i] < 0xE000) || (t[i] >= 0x2600 && t[i] <= 0x27BF) || t[i] == 0xFE0F || t[i] == 0x20E3) emo = true;
        out.path = emo ? emoji : latin; out.runLen = len; out.weight = 400; return true;
    });
    CHECK(emojiAvailable());
    const int W = 700, H = 300;
    std::vector<uint8_t> bmp((size_t)W * H * 4, 0);
    Request r;
    r.fontPath = latin; r.fontPx = 80; r.maxTextW = 640; r.padH = 10; r.padV = 6; r.cx = 350; r.cy = 150;
    r.fillArgb = 0xFFFF0000u;  // red text fill
    Stats st;
    std::string err;
    int x0, x1, y0, y1;
    auto hasYellowish = [&]() { for (size_t i = 0; i < bmp.size(); i += 4) if (bmp[i + 3] > 200 && bmp[i + 1] > 150 && bmp[i + 2] < 120 && bmp[i] > 150) return true; return false; };
    auto hasRed = [&]() { for (size_t i = 0; i < bmp.size(); i += 4) if (bmp[i + 3] > 200 && bmp[i] > 200 && bmp[i + 1] < 40 && bmp[i + 2] < 40) return true; return false; };
    auto run = [&](const char* text, Stats* s) { r.text = text; std::fill(bmp.begin(), bmp.end(), 0); return draw(bmp.data(), W, H, W * 4, r, s, &err); };

    CHECK(run("\xF0\x9F\x98\x80", &st));  // U+1F600
    CHECK(st.colorGlyphs == 1 && st.clusters == 1 && st.glyphs == 1);
    CHECK(firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1) > 2000);
    CHECK(hasYellowish() && !hasRed());   // own colours, not the red text fill
    CHECK((x1 - x0) > 80 * 0.9 && (x1 - x0) < 80 * 1.3);  // the face fills ~1.06 em of its 1.25 em wide picture
    CHECK(std::abs(((x0 + x1) / 2) - 350) < 12);

    CHECK(run("A\xF0\x9F\x98\x80" "B", &st));
    CHECK(st.clusters == 3 && st.colorGlyphs == 1 && st.fonts == 2);
    CHECK(hasYellowish() && hasRed());

    CHECK(run("\xF0\x9F\x91\xA8\xE2\x80\x8D\xF0\x9F\x91\xA9\xE2\x80\x8D\xF0\x9F\x91\xA7", &st));  // family ZWJ sequence
    CHECK(st.clusters == 1 && st.colorGlyphs == 1);
    CHECK(run("\xF0\x9F\x87\xBA\xF0\x9F\x87\xB8", &st));  // flag
    CHECK(st.clusters == 1 && st.colorGlyphs == 1);
    CHECK(run("1\xEF\xB8\x8F\xE2\x83\xA3", &st));  // keycap 1
    CHECK(st.clusters == 1 && st.colorGlyphs == 1);
    CHECK(run("\xF0\x9F\x91\x8D\xF0\x9F\x8F\xBD", &st));  // thumbs up + skin tone
    CHECK(st.clusters == 1 && st.colorGlyphs == 1);
    CHECK(run("\xF0\x9F\x98\x80\xF0\x9F\x98\x80\xF0\x9F\x98\x80", &st));
    CHECK(st.clusters == 3 && st.colorGlyphs == 3);

    // text-default heart: plain glyph of the text font; with U+FE0F it becomes the emoji picture
    CHECK(run("\xE2\x9D\xA4", &st));
    CHECK(st.colorGlyphs == 0 && hasRed());
    CHECK(run("\xE2\x9D\xA4\xEF\xB8\x8F", &st));
    CHECK(st.colorGlyphs == 1 && st.clusters == 1);

    // line height / wrapping: emoji lines are laid out like any other
    CHECK(run("\xF0\x9F\x98\x80 \xF0\x9F\x98\x80 \xF0\x9F\x98\x80 \xF0\x9F\x98\x80 \xF0\x9F\x98\x80 \xF0\x9F\x98\x80 \xF0\x9F\x98\x80 \xF0\x9F\x98\x80 \xF0\x9F\x98\x80", &st));
    CHECK(st.lines >= 2 && st.colorGlyphs == 9);

    // scale, rotation, crop, shadow: no crash and the picture follows
    r.fontPx = 24;
    CHECK(run("\xF0\x9F\x98\x80", &st));  // small: the 2x2 averaged copy of the picture is used
    CHECK(firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1) > 150 && hasYellowish());
    r.fontPx = 80; r.rotationDeg = 30.f;
    CHECK(run("\xF0\x9F\x98\x80", &st) && hasYellowish());
    r.rotationDeg = 0.f; r.scale = 2.f;
    CHECK(run("\xF0\x9F\x98\x80", &st));
    const int wide = firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1);
    CHECK((x1 - x0) > 80 * 2 * 0.9);
    r.scale = 1.f; r.crop[2] = 0.5f;
    CHECK(run("\xF0\x9F\x98\x80", &st));
    firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1);
    CHECK(x1 <= 350 + 2 && wide > 0);
    r.crop[2] = 1.f;
    CHECK(run("\xF0\x9F\x98\x80", &st));
    const int plain = firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1);
    r.shadowOn = true; r.shadowBlur = 12; r.shadowDx = 14; r.shadowDy = 14; r.shadowArgb = 0xFF000000u;
    CHECK(run("\xF0\x9F\x98\x80", &st));
    CHECK(firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1) > plain);  // the shadow of the picture is drawn too
    r.shadowOn = false;
    r.fillOn = false;  // no text fill: the emoji picture is still there
    CHECK(run("\xF0\x9F\x98\x80", &st) && st.colorGlyphs == 1 && hasYellowish());
    r.fillOn = true;

    // 3. No PNG decoder (Android < 11) or no usable emoji font: false, nothing drawn, so the caller keeps the Compose / Canvas path
    setPngDecoderForTest(nullptr);
    CHECK(!emojiAvailable());
    CHECK(!run("hi \xF0\x9F\x98\x80", &st));
    CHECK(err.find("emoji") != std::string::npos);
    CHECK(run("hi", &st));  // plain text is not affected
    setPngDecoderForTest(hostPng);
    setMatcherForTest([&](const std::string&, int, bool, const uint16_t*, uint32_t len, MatchResult& out) {
        out.path = latin; out.runLen = len; out.weight = 400; return true;  // device without a colour emoji font
    });
    CHECK(!emojiAvailable());
    CHECK(!run("\xF0\x9F\x98\x80", &st));
    setPngDecoderForTest(nullptr);
}

// ---- colour emoji from a COLR (v1) font: outlines + paint graph drawn by colr_render.cpp, no PNG decoder involved
// The font is built by make_colr_font.py (/tmp/am_colr_test.ttf); the test is skipped when it is missing.
static void testColrEmoji() {
    const char* latin = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    const char* colr = "/tmp/am_colr_test.ttf";
    if (!fileExists(latin) || !fileExists(colr)) { std::printf("skip COLR emoji tests (font missing)\n"); return; }
    setPngDecoderForTest(nullptr);
    setMatcherForTest([&](const std::string&, int, bool, const uint16_t* t, uint32_t len, MatchResult& out) {
        bool emo = false;
        for (uint32_t i = 0; i < len; ++i) if (t[i] >= 0xD800 && t[i] < 0xE000) emo = true;
        out.path = emo ? colr : latin; out.runLen = len; out.weight = 400; return true;
    });
    CHECK(emojiAvailable());
    const int W = 700, H = 300;
    std::vector<uint8_t> bmp((size_t)W * H * 4, 0);
    Request r;
    r.fontPath = latin; r.fontPx = 80; r.maxTextW = 640; r.padH = 10; r.padV = 6; r.cx = 350; r.cy = 150;
    r.fillArgb = 0xFFFF00FFu;  // magenta text fill: must not show up in the emoji
    Stats st;
    std::string err;
    int x0, x1, y0, y1;
    auto has = [&](int rl, int rh, int gl, int gh, int bl, int bh) {
        for (size_t i = 0; i < bmp.size(); i += 4)
            if (bmp[i + 3] > 200 && bmp[i] >= rl && bmp[i] <= rh && bmp[i + 1] >= gl && bmp[i + 1] <= gh && bmp[i + 2] >= bl && bmp[i + 2] <= bh) return true;
        return false;
    };
    auto run = [&](const char* text, Stats* s) { r.text = text; std::fill(bmp.begin(), bmp.end(), 0); return draw(bmp.data(), W, H, W * 4, r, s, &err); };

    CHECK(run("\xF0\x9F\x98\x80", &st));  // U+1F600: gradient ring, rotated yellow H, green bar
    CHECK(st.colorGlyphs == 1 && st.clusters == 1);
    CHECK(firstOpaqueCols(bmp, W, H, &x0, &x1, &y0, &y1) > 1500);
    CHECK(has(0, 40, 130, 175, 0, 40));      // the green bar (solid palette colour)
    CHECK(has(200, 255, 0, 60, 0, 90));      // red end of the linear gradient (bottom of the ring)
    CHECK(has(0, 90, 0, 60, 170, 255));      // blue end (top of the ring)
    CHECK(!has(230, 255, 0, 40, 230, 255));  // no magenta text fill
    CHECK((x1 - x0) > 80 * 0.6 && (x1 - x0) < 80 * 1.6);

    CHECK(run("\xF0\x9F\x98\x81", &st));  // U+1F601: radial gradient + src_in composite
    CHECK(st.colorGlyphs == 1);
    CHECK(has(0, 40, 0, 40, 200, 255));      // blue where H and the ring overlap
    CHECK(has(220, 255, 60, 230, 0, 40));    // orange of the radial gradient (yellow centre -> red edge)

    CHECK(run("A\xF0\x9F\x98\x80" "B", &st));
    CHECK(st.clusters == 3 && st.colorGlyphs == 1 && st.fonts == 2);

    // Text in the COLR font that is not an emoji keeps the plain outline drawing (tinted by the text fill).
    r.fontPath = colr;
    CHECK(run("A", &st));
    CHECK(st.colorGlyphs == 0 && has(230, 255, 0, 40, 230, 255));
    r.fontPath = latin;

    setMatcherForTest([&](const std::string&, int, bool, const uint16_t*, uint32_t len, MatchResult& out) {
        out.path = latin; out.runLen = len; out.weight = 400; return true;  // a phone with no colour emoji font
    });
    CHECK(!emojiAvailable());
}

// Text animator (Alight "texttransform"): units, arrived range, move, fade, recolour. animOn = false must change nothing.
static void testAnimator() {
    const char* latin = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    if (!fileExists(latin)) { std::printf("skip animator tests (no %s)\n", latin); return; }
    setMatcherForTest([&](const std::string&, int, bool, const uint16_t*, uint32_t, MatchResult& out) {
        out.path = latin; out.runLen = 1; out.weight = 400; return true;
    });
    const int W = 700, H = 400;
    auto base = [&]() {
        Request r; r.text = "ab cd\nef"; r.fontPath = latin; r.fontPx = 60; r.maxTextW = 600; r.padH = 15; r.padV = 7;
        r.cx = 350; r.cy = 200;
        return r;
    };
    auto render = [&](const Request& r, int* x0, int* x1, int* y0, int* y1, long* alpha) {
        std::vector<uint8_t> b((size_t)W * H * 4, 0);
        bool ok = draw(b.data(), W, H, W * 4, r);
        int n = firstOpaqueCols(b, W, H, x0, x1, y0, y1);
        *alpha = alphaSum(b);
        (void)n;
        return ok;
    };
    int x0, x1, y0, y1; long a0;
    Request r = base();
    CHECK(render(r, &x0, &x1, &y0, &y1, &a0));
    const int bx0 = x0, bx1 = x1, by0 = y0, by1 = y1;

    // animOn with everything arrived (range 0..1) and a big offset: identical picture
    Request r1 = base(); r1.animOn = true; r1.animComponent = 2; r1.animStart = 0.f; r1.animEnd = 1.f; r1.animDx = 0.f; r1.animDy = 70.f;
    long a1;
    CHECK(render(r1, &x0, &x1, &y0, &y1, &a1));
    CHECK(x0 == bx0 && x1 == bx1 && y0 == by0 && y1 == by1 && a1 == a0);

    // nothing arrived (range 0..0): every line is 70 px lower
    Request r2 = r1; r2.animEnd = 0.f;
    long a2;
    CHECK(render(r2, &x0, &x1, &y0, &y1, &a2));
    CHECK(y0 == by0 + 70 && y1 == by1 + 70 && x0 == bx0 && x1 == bx1);

    // half of two lines arrived (end = 0.5): line 1 is home, line 2 is still 70 px lower -> the bottom moved, the top did not
    Request r3 = r1; r3.animEnd = 0.5f;
    long a3;
    CHECK(render(r3, &x0, &x1, &y0, &y1, &a3));
    CHECK(y0 == by0 && y1 == by1 + 70);

    // a quarter: line 1 half way (35 px), line 2 not arrived (70 px)
    Request r4 = r1; r4.animEnd = 0.25f;
    CHECK(render(r4, &x0, &x1, &y0, &y1, &a3));
    CHECK(y0 == by0 + 35 && y1 == by1 + 70);

    // words: "ab cd ef" = 3 units; with nothing arrived and a sideways offset the whole text moves 40 px
    Request r5 = base(); r5.animOn = true; r5.animComponent = 1; r5.animEnd = 0.f; r5.animDx = 40.f;
    CHECK(render(r5, &x0, &x1, &y0, &y1, &a3));
    CHECK(x0 == bx0 + 40 && x1 == bx1 + 40 && y0 == by0 && y1 == by1);
    // letters: 6 units; only the first 2 letters arrived (end = 1/3): "ab" stays, the rest moves, so the right edge moves
    Request r6 = base(); r6.animOn = true; r6.animComponent = 0; r6.animEnd = 1.f / 3.f; r6.animDx = 40.f;
    CHECK(render(r6, &x0, &x1, &y0, &y1, &a3));
    CHECK(x0 == bx0 || x0 == bx0 + 0 || x0 < bx0 + 10);
    CHECK(x1 > bx1);

    // fade: nothing arrived with animAlpha 0 -> nothing visible; half way -> less ink than home
    Request r7 = base(); r7.animOn = true; r7.animComponent = 2; r7.animEnd = 0.f; r7.animAlpha = 0.f;
    long a7;
    std::vector<uint8_t> b7((size_t)W * H * 4, 0);
    CHECK(draw(b7.data(), W, H, W * 4, r7));
    a7 = alphaSum(b7);
    CHECK(a7 == 0);
    Request r8 = r7; r8.animEnd = 0.5f;   // line 1 home, line 2 invisible
    std::fill(b7.begin(), b7.end(), 0);
    CHECK(draw(b7.data(), W, H, W * 4, r8));
    CHECK(alphaSum(b7) > 0 && alphaSum(b7) < a0);

    // recolour: nothing arrived -> the text is green, all arrived -> the normal colour (white)
    Request r9 = base(); r9.animOn = true; r9.animComponent = 2; r9.animEnd = 0.f; r9.animRecolor = true; r9.animArgb = 0xFF00FF00u;
    std::vector<uint8_t> b9((size_t)W * H * 4, 0);
    CHECK(draw(b9.data(), W, H, W * 4, r9));
    bool green = false, white = false;
    for (size_t i = 0; i < b9.size(); i += 4) {
        if (b9[i + 3] > 250 && b9[i + 1] > 240 && b9[i] < 10 && b9[i + 2] < 10) green = true;
        if (b9[i + 3] > 250 && b9[i] > 240 && b9[i + 1] > 240 && b9[i + 2] > 240) white = true;
    }
    CHECK(green && !white);
    r9.animEnd = 1.f;
    std::fill(b9.begin(), b9.end(), 0);
    CHECK(draw(b9.data(), W, H, W * 4, r9));
    green = false; white = false;
    for (size_t i = 0; i < b9.size(); i += 4) {
        if (b9[i + 3] > 250 && b9[i + 1] > 240 && b9[i] < 10 && b9[i + 2] < 10) green = true;
        if (b9[i + 3] > 250 && b9[i] > 240 && b9[i + 1] > 240 && b9[i + 2] > 240) white = true;
    }
    CHECK(white && !green);
}

static void testProgress() {
    const char* latin = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    const char* bangla = "/usr/share/fonts/truetype/freefont/FreeSans.ttf";
    if (!fileExists(latin)) { std::printf("skip progress tests (no %s)\n", latin); return; }
    setMatcherForTest([&](const std::string&, int, bool, const uint16_t*, uint32_t, MatchResult& out) {
        out.path = latin; out.runLen = 1; out.weight = 400; return true;
    });
    const int W = 700, H = 400;
    auto render = [&](const Request& r, int* x0, int* x1, int* y0, int* y1, long* alpha, Stats* st) {
        std::vector<uint8_t> b((size_t)W * H * 4, 0);
        bool ok = draw(b.data(), W, H, W * 4, r, st);
        firstOpaqueCols(b, W, H, x0, x1, y0, y1);
        *alpha = alphaSum(b);
        return ok;
    };
    auto base = [&]() {
        Request r; r.text = "HELLO"; r.fontPath = latin; r.fontPx = 60; r.maxTextW = 600; r.padH = 15; r.padV = 7;
        r.cx = 350; r.cy = 200;
        return r;
    };
    int x0, x1, y0, y1; long a0; Stats st;
    CHECK(render(base(), &x0, &x1, &y0, &y1, &a0, &st));
    CHECK(st.clusters == 5);
    const int bx0 = x0, bx1 = x1, by0 = y0, by1 = y1;

    // the full window draws exactly the unrevealed picture
    Request r = base(); r.progressOn = true; r.progressStart = 0.f; r.progressEnd = 1.f;
    long a;
    CHECK(render(r, &x0, &x1, &y0, &y1, &a, nullptr));
    CHECK(x0 == bx0 && x1 == bx1 && y0 == by0 && y1 == by1 && a == a0);
    // flags off: the window values alone change nothing
    r = base(); r.progressStart = 0.f; r.progressEnd = 0.f;
    CHECK(render(r, &x0, &x1, &y0, &y1, &a, nullptr) && a == a0);
    // an empty window draws nothing
    r = base(); r.progressOn = true; r.progressEnd = 0.f;
    CHECK(render(r, &x0, &x1, &y0, &y1, &a, nullptr) && a == 0);
    // [0, 0.6] of 5 clusters = "HEL": same left edge, right edge pulled in, about 3/5 of the ink
    r = base(); r.progressOn = true; r.progressEnd = 0.6f;
    CHECK(render(r, &x0, &x1, &y0, &y1, &a, nullptr));
    CHECK(x0 == bx0 && x1 < bx1 && y0 == by0 && a > a0 * 45 / 100 && a < a0 * 75 / 100);
    // [0.6, 1] = "LO": same right edge, left edge pushed in
    r = base(); r.progressOn = true; r.progressStart = 0.6f; r.progressEnd = 1.f;
    CHECK(render(r, &x0, &x1, &y0, &y1, &a, nullptr));
    CHECK(x1 == bx1 && x0 > bx0 && a > a0 * 25 / 100 && a < a0 * 55 / 100);

    // two paragraphs: [0, 0.5] shows only the first line (clusters are counted across paragraphs)
    Request m; m.text = "ab\ncd"; m.fontPath = latin; m.fontPx = 60; m.maxTextW = 600; m.padH = 15; m.padV = 7;
    m.cx = 350; m.cy = 200;
    long am;
    CHECK(render(m, &x0, &x1, &y0, &y1, &am, nullptr));
    const int my0 = y0, my1 = y1;
    m.progressOn = true; m.progressEnd = 0.5f;
    CHECK(render(m, &x0, &x1, &y0, &y1, &a, nullptr));
    CHECK(y0 == my0 && y1 < my1 && a > 0);

    // a Bengali conjunct is one shaped cluster: never half shown (needs a Bengali-capable font)
    const char* bnFont = fileExists(bangla) ? bangla : "/usr/share/fonts/truetype/noto/NotoSansBengali-Regular.ttf";
    if (fileExists(bnFont)) {
        Request b; b.text = "\xE0\xA6\x95\xE0\xA7\x8D\xE0\xA6\xA4\xE0\xA6\xBF";  // ক্তি
        b.fontPath = bnFont; b.fontPx = 60; b.maxTextW = 600; b.padH = 15; b.padV = 7; b.cx = 350; b.cy = 200;
        Stats sb;
        CHECK(render(b, &x0, &x1, &y0, &y1, &a, &sb) && sb.clusters == 1 && a > 0);
        // Intersection rule (build 110): the one cluster's slice [0, 1] overlaps the window
        // [0, 0.5], so the conjunct pops in WHOLE — it is never half drawn, under either rule.
        b.progressOn = true; b.progressEnd = 0.5f;
        CHECK(render(b, &x0, &x1, &y0, &y1, &a, nullptr) && a > 0);
        b.progressEnd = 1.f;
        CHECK(render(b, &x0, &x1, &y0, &y1, &a, nullptr) && a > 0);
    }
}

// ---- outline trim math (drawing.progress on text): segment lengths, arc-length parameters,
// exact sub-chain extraction over a concatenated contour.
static void testOutlineTrim() {
    auto line = [](double x0, double y0, double x1, double y1) {
        OSeg s; s.kind = 0; s.x[0] = x0; s.y[0] = y0; s.x[1] = x1; s.y[1] = y1; return s;
    };
    // A 100 x 100 square contour (closed: the last segment returns to the start).
    const std::vector<OSeg> sq = { line(0, 0, 100, 0), line(100, 0, 100, 100), line(100, 100, 0, 100), line(0, 100, 0, 0) };
    CHECK(std::fabs(contourLength(sq) - 400.0) < 1e-9);
    // A window over exactly the middle two sides.
    const auto mid = clipContour(sq, 100, 300);
    CHECK(mid.size() == 2 && std::fabs(contourLength(mid) - 200.0) < 1e-9);
    // A window cutting two segments keeps the tail of side 1 and the head of side 2.
    const auto cut = clipContour(sq, 50, 150);
    CHECK(cut.size() == 2 && std::fabs(contourLength(cut) - 100.0) < 1e-6);
    CHECK(std::fabs(cut.front().sx() - 50) < 1e-9 && std::fabs(cut.front().sy()) < 1e-9);
    CHECK(std::fabs(cut.back().ex() - 100) < 1e-9 && std::fabs(cut.back().ey() - 50) < 1e-9);
    // Empty and missed windows keep nothing.
    CHECK(clipContour(sq, 10, 10).empty());
    CHECK(clipContour(sq, 500, 600).empty());
    // A conic arc: length between the chord and the control polygon; the curve is symmetric, so
    // the parameter at half the length is 0.5 and splitting there halves the length too.
    OSeg arc; arc.kind = 1;
    arc.x[0] = 0; arc.y[0] = 0; arc.x[1] = 100; arc.y[1] = 0; arc.x[2] = 100; arc.y[2] = 100;
    const double alen = segLength(arc);
    CHECK(alen > 141.5 && alen < 200.0);
    CHECK(std::fabs(segParamAtLength(arc, alen / 2) - 0.5) < 0.01);
    OSeg a1, a2;
    segSplit(arc, 0.5, a1, a2);
    CHECK(std::fabs(segLength(a1) - segLength(a2)) < alen * 0.02);
    double mx, my;
    segPoint(arc, 0.5, mx, my);
    CHECK(std::fabs(mx - 75) < 1e-9 && std::fabs(my - 25) < 1e-9);
    // A symmetric cubic: midpoint and a half-length clip ending exactly at it.
    OSeg cub; cub.kind = 2;
    cub.x[0] = 0; cub.y[0] = 0; cub.x[1] = 0; cub.y[1] = 100; cub.x[2] = 100; cub.y[2] = 100; cub.x[3] = 100; cub.y[3] = 0;
    segPoint(cub, 0.5, mx, my);
    CHECK(std::fabs(mx - 50) < 1e-9 && std::fabs(my - 75) < 1e-9);
    const double clen = segLength(cub);
    const auto chalf = clipContour(std::vector<OSeg>{cub}, 0, clen / 2);
    CHECK(chalf.size() == 1 && std::fabs(contourLength(chalf) - clen / 2) < clen * 0.01);
    CHECK(std::fabs(chalf[0].ex() - 50) < 0.5 && std::fabs(chalf[0].ey() - 75) < 0.5);
}

// ---- draw-on rendering: the trimmed glyph outlines replace the fill.
static void testDrawOn() {
    const char* latin = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    if (!fileExists(latin)) { std::printf("skip draw-on tests (no %s)\n", latin); return; }
    const int W = 600, H = 300;
    std::vector<uint8_t> bmp((size_t)W * H * 4, 0);
    auto render = [&](const Request& rq) {
        std::fill(bmp.begin(), bmp.end(), 0);
        CHECK(draw(bmp.data(), W, H, W * 4, rq));
        return alphaSum(bmp);
    };
    Request r;
    r.text = "Draw On";
    r.fontPath = latin;
    r.fontPx = 80; r.maxTextW = 560; r.padH = 15; r.padV = 7; r.cx = 300; r.cy = 150;
    const long fill = render(r);
    CHECK(fill > 10000);
    // An empty window draws nothing at all.
    r.drawOn = true; r.drawStrokeW = 2.f; r.drawStart = 0.f; r.drawEnd = 0.f;
    CHECK(render(r) == 0);
    // The full window draws the outline ring only: visible ink, strictly less than the fill.
    r.drawEnd = 1.f;
    const long full = render(r);
    CHECK(full > 2000 && full < fill);
    // The two halves of the window partition the outline: each draws something, and together
    // they cover the full outline within AA slack at the joint.
    r.drawEnd = 0.5f;
    const long h1 = render(r);
    r.drawStart = 0.5f; r.drawEnd = 1.f;
    const long h2 = render(r);
    CHECK(h1 > 200 && h2 > 200);
    CHECK(h1 < full && h2 < full);
    CHECK(std::labs(h1 + h2 - full) < full / 8);
}

int main() {
    testOutlineTrim();
    testLayout();
    testBlit();
    testEngine();
    testDrawOn();
    testFill();
    testRtl();
    testEmoji();
    testColrEmoji();
    testAnimator();
    testProgress();
    std::printf("%d checks, %d failed\n", g_checks, g_fail);
    return g_fail ? 1 : 0;
}
