// Build 224 — host selftests for the native timeline renderer (timeline_render.cpp):
// layout math, hit-test zones, fade/waveform helpers, and a full-scene draw smoke test
// that also renders the scene to a BMP (converted to the build's proof PNG outside).
// Build & run (no sanitizers, per the harness README):
//   g++ -std=c++17 -O2 -Wall -Wextra -I../engine timeline_selftest.cpp ../engine/timeline_render.cpp -o /tmp/timeline_selftest && /tmp/timeline_selftest
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "timeline_render.h"

using namespace am::timeline;

static int g_fail = 0;
static int g_pass = 0;
#define CHECK(cond, msg) do { if (cond) { ++g_pass; } else { ++g_fail; \
    std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, msg); } } while (0)
#define CHECK_F(a, b, msg) CHECK(std::fabs((a) - (b)) < 0.01f, msg)

static bool nearPx(uint32_t px, uint32_t want, int tol) {
    for (int s = 0; s <= 24; s += 8) {
        int d = (int)((px >> s) & 0xFF) - (int)((want >> s) & 0xFF);
        if (d < -tol || d > tol) return false;
    }
    return true;
}

// ---- Synthetic atlas: white blocks for every label the scene uses ----
struct TestAtlas {
    Atlas at;
    int add(int x, int y, int w, int h) {
        for (int j = y; j < y + h; ++j)
            for (int i = x; i < x + w; ++i) {
                size_t o = ((size_t)j * at.w + i) * 4;
                at.rgba[o] = at.rgba[o + 1] = at.rgba[o + 2] = at.rgba[o + 3] = 255;
            }
        AtlasEntry e;
        e.x = x; e.y = y; e.w = w; e.h = h; e.base = (float)h;
        at.entries.push_back(e);
        return (int)at.entries.size() - 1;
    }
};

static Snapshot makeScene(TestAtlas& ta, int* idx) {
    ta.at.w = 512; ta.at.h = 128;
    ta.at.rgba.assign((size_t)512 * 128 * 4, 0);
    idx[0] = ta.add(0, 0, 70, 16);      // video name chip
    idx[1] = ta.add(80, 0, 14, 14);     // "fx" badge text
    idx[2] = ta.add(104, 0, 34, 16);    // audio duration badge
    idx[3] = ta.add(148, 0, 76, 16);    // audio name chip
    idx[4] = ta.add(234, 0, 46, 20);    // text label
    idx[5] = ta.add(290, 0, 50, 18);    // chain child A label
    idx[6] = ta.add(350, 0, 44, 18);    // chain child B label
    idx[7] = ta.add(404, 0, 36, 20);    // shape label
    idx[8] = ta.add(0, 32, 90, 20);     // spare entry (was the pill "6 Layers", removed in build 263)
    idx[9] = ta.add(100, 32, 40, 16);   // total "0:08"
    idx[10] = ta.add(150, 32, 40, 18);  // "Chain"
    for (int i = 0; i < 11; ++i) {
        int e = ta.add(i * 10, 64, 8, 14);
        ta.at.digit[i] = e;
        ta.at.digitAdv[i] = (i == 10) ? 5.f : 9.f;
    }
    ta.at.digitBaseY = 60.f;

    Snapshot s;
    s.density = 2.f;
    s.pxPerMs = 0.045f;
    s.rulerHPx = 88;
    s.railWPx = 84;
    s.topPadPx = 8;
    s.gapPx = 8;
    s.bottomPadPx = 8;
    s.totalMs = 8000;
    s.totalLabel = idx[9];
    s.chainLabel = idx[10];
    s.hasRange = true;
    s.rangeInMs = 1000;
    s.rangeOutMs = 5000;
    s.beatMs = {1500, 2500};
    s.rulerKeysMs = {3000};

    Track t0; t0.heightPx = 64;
    Clip v; v.startMs = 1000; v.durMs = 4000; v.kind = kGlyphVideo; v.color = 0xFF10B981;
    v.flags = kFlagVideo | kFlagHasUri | kFlagSelected | kFlagFx;
    v.chip = idx[0]; v.badge = idx[1]; v.keysRelMs = {1000};
    v.peaks.assign(100, 0.5f); v.volume = 1.f; v.speed = 1.f;
    t0.clips.push_back(v);

    Track t1; t1.heightPx = 64;
    Clip a; a.startMs = 1500; a.durMs = 3000; a.kind = kGlyphMusic; a.color = 0xFF8B5CF6;
    a.flags = kFlagAudio; a.chip = idx[3]; a.badge = idx[2];
    a.peaks.assign(60, 0.8f); a.volume = 1.f; a.speed = 1.f;
    a.fadeInMs = 500; a.fadeOutMs = 500;
    t1.clips.push_back(a);

    Track t2; t2.heightPx = 64;
    Clip tx; tx.startMs = 2000; tx.durMs = 2000; tx.kind = kGlyphText; tx.color = 0xFF1689F5;
    tx.label = idx[4];
    t2.clips.push_back(tx);

    Track t3; t3.heightPx = 88; t3.chainClip = 0;
    Clip ch; ch.startMs = 1000; ch.durMs = 3500; ch.kind = kGlyphChain;
    ch.flags = kFlagChain | kFlagSelected;
    Clip cA; cA.startMs = 1000; cA.durMs = 1500; cA.kind = kGlyphMusic; cA.color = 0xFF8B5CF6;
    cA.flags = kFlagAudio | kFlagInChain; cA.label = idx[5];
    Clip cB; cB.startMs = 3000; cB.durMs = 1500; cB.kind = kGlyphVideo; cB.color = 0xFF10B981;
    cB.flags = kFlagVideo | kFlagInChain; cB.label = idx[6];
    t3.clips = {ch, cA, cB};

    Track t4; t4.heightPx = 64;
    Clip sh; sh.startMs = 2500; sh.durMs = 1000; sh.kind = kGlyphShape; sh.color = 0xFF3EB1F7;
    sh.label = idx[7];
    t4.clips.push_back(sh);

    s.tracks = {t0, t1, t2, t3, t4};
    return s;
}

static void writeBmp(const char* path, const std::vector<uint8_t>& rgba, int w, int h) {
    FILE* f = std::fopen(path, "wb");
    if (!f) return;
    int rowSize = (w * 3 + 3) & ~3;
    uint32_t dataSize = (uint32_t)rowSize * h;
    uint8_t hdr[54] = {};
    std::memcpy(hdr, "BM", 2);
    auto put32 = [&](int off, uint32_t v) { std::memcpy(hdr + off, &v, 4); };
    auto put16 = [&](int off, uint16_t v) { std::memcpy(hdr + off, &v, 2); };
    put32(2, 54 + dataSize); put32(10, 54); put32(14, 40);
    put32(18, (uint32_t)w); put32(22, (uint32_t)h);
    put16(26, 1); put16(28, 24); put32(34, dataSize);
    std::fwrite(hdr, 1, 54, f);
    std::vector<uint8_t> row((size_t)rowSize, 0);
    for (int y = h - 1; y >= 0; --y) {
        for (int x = 0; x < w; ++x) {
            const uint8_t* px = &rgba[((size_t)y * w + x) * 4];
            row[x * 3] = px[2]; row[x * 3 + 1] = px[1]; row[x * 3 + 2] = px[0];
        }
        std::fwrite(row.data(), 1, (size_t)rowSize, f);
    }
    std::fclose(f);
}

int main() {
    // ---- Layout math ----
    TestAtlas ta;
    int idx[11];
    Snapshot s = makeScene(ta, idx);
    View v;
    v.w = 720; v.h = 488; v.playheadMs = 2000;

    CHECK_F(ppmPx(s), 0.09f, "ppmPx = pxPerMs * density");
    CHECK_F(xForMs(s, v, 2000), 360.f, "playhead sits at the viewport centre");
    CHECK_F(xForMs(s, v, 1000), 270.f, "xForMs one second before the playhead");
    CHECK_F(laneTopPx(s, v, 0), 96.f, "first lane top = ruler + top pad");
    CHECK_F(laneTopPx(s, v, 2), 240.f, "lane tops accumulate heights + gaps");
    CHECK_F(laneTopPx(s, v, 3), 312.f, "chain lane top");
    CHECK_F(contentHeightPx(s), 392.f, "content height = pads + lanes + gaps between lanes");
    CHECK(rulerStepMs(0.09f, 2.f) == 200, "ruler step at default zoom");
    CHECK(rulerStepMs(0.0225f, 2.f) == 1000, "ruler step zoomed out");
    {
        float x, y, w, h;
        barRectPx(s, v, 0, 0, &x, &y, &w, &h);
        CHECK_F(x, 270.f, "video bar x"); CHECK_F(y, 96.f, "video bar y");
        CHECK_F(w, 360.f, "video bar w"); CHECK_F(h, 64.f, "video bar h");
        Snapshot s2 = s;
        s2.tracks[4].clips[0].durMs = 100;  // 100 ms -> 9 px, under the 30 dp floor
        barRectPx(s2, v, 4, 0, &x, &y, &w, &h);
        CHECK_F(w, 60.f, "bar width floor = 30 dp");
        // Chain child: 3 dp / 6 dp insets inside the section.
        barRectPx(s, v, 3, 1, &x, &y, &w, &h);
        CHECK_F(y, 318.f, "chain child y = section top + 3 dp");
        CHECK_F(h, 76.f, "chain child h = section - 6 dp");
    }
    {
        Clip fc;
        fc.durMs = 3000; fc.fadeInMs = 500; fc.fadeOutMs = 500;
        CHECK_F(fadeGainAt(fc, 0), 0.f, "fade gain at clip start");
        CHECK_F(fadeGainAt(fc, 250), 0.5f, "fade-in midpoint");
        CHECK_F(fadeGainAt(fc, 1000), 1.f, "fade gain in the body");
        CHECK_F(fadeGainAt(fc, 2750), 0.5f, "fade-out midpoint");
        Clip wc;
        wc.peaks.assign(60, 0.8f); wc.speed = 1.f;
        CHECK_F(wavePeak(wc, 0, 30), 0.8f, "waveform peak from buckets");
        Clip ec;
        CHECK_F(wavePeak(ec, 0, 30), 0.f, "waveform peak with no data is 0 (placeholder is the caller's)");
    }

    // ---- Hit testing (scene A: the video clip is the selection; the chain is not) ----
    Snapshot sA = s;
    sA.tracks[3].clips[0].flags = kFlagChain;
    Renderer r;
    r.setAtlas(ta.at);
    r.setSnapshot(sA);
    int32_t tr = -2, cl = -2;
    CHECK(r.hitTest(300, 128, v, &tr, &cl) == kZoneBar && tr == 0 && cl == 0, "tap on the video bar");
    CHECK(r.hitTest(42, 128, v, &tr, &cl) == kZoneEye && tr == 0 && cl == 0, "eye box hit");
    CHECK(r.hitTest(10, 128, v, &tr, &cl) == kZoneRail && tr == 0, "rail cell outside the eye box");
    CHECK(r.hitTest(400, 44, v, &tr, &cl) == kZoneRuler, "ruler hit");
    CHECK(r.hitTest(50, 44, v, &tr, &cl) == kZoneRuler, "former pill spot is plain ruler (build 263: pill removed)");
    CHECK(r.hitTest(650, 128, v, &tr, &cl) == kZoneTrimR && cl == 0, "right trim handle");
    CHECK(r.hitTest(250, 128, v, &tr, &cl) == kZoneTrimL && cl == 0, "left trim handle");
    CHECK(r.hitTest(700, 460, v, &tr, &cl) == kZoneEmpty, "empty lane area");
    {
        // Scene B: the chain section is the selection -> its delete affordances are live.
        Snapshot sB = s;
        sB.tracks[0].clips[0].flags &= ~(uint32_t)kFlagSelected;
        Renderer rB;
        rB.setAtlas(ta.at);
        rB.setSnapshot(sB);
        CHECK(rB.hitTest(360, 350, v, &tr, &cl) == kZoneChildX && tr == 3 && cl == 1,
              "chain child delete x");
        CHECK(rB.hitTest(696, 356, v, &tr, &cl) == kZoneChainX && tr == 3 && cl == 0,
              "chain section delete x");
        const int W2 = 720, H2 = 488;
        std::vector<uint8_t> imgB((size_t)W2 * H2 * 4, 0);
        rB.draw(imgB.data(), W2, H2, W2 * 4, v);
        const uint8_t* p = &imgB[((size_t)320 * W2 + 700) * 4];
        uint32_t secPx = ((uint32_t)p[3] << 24) | ((uint32_t)p[0] << 16) |
                         ((uint32_t)p[1] << 8) | p[2];
        CHECK(secPx == 0xFF131920u, "selected chain section background");
    }
    {
        // Fade knobs: a selected audio clip on the second lane; its knob box straddles the
        // lane's top edge and must win even over the lane above (Compose behaviour).
        Snapshot s2;
        s2.density = 2.f; s2.pxPerMs = 0.045f; s2.rulerHPx = 88; s2.railWPx = 84;
        s2.topPadPx = 8; s2.gapPx = 8; s2.bottomPadPx = 8; s2.totalMs = 8000;
        Track u0; u0.heightPx = 64;
        Clip tc; tc.startMs = 2000; tc.durMs = 2000; tc.kind = kGlyphText; tc.color = 0xFF1689F5;
        u0.clips.push_back(tc);
        Track u1; u1.heightPx = 64;
        Clip ac; ac.startMs = 2000; ac.durMs = 3000; ac.kind = kGlyphMusic; ac.color = 0xFF8B5CF6;
        ac.flags = kFlagAudio | kFlagSelected; ac.fadeInMs = 500; ac.fadeOutMs = 500;
        u1.clips.push_back(ac);
        s2.tracks = {u0, u1};
        Renderer r2;
        r2.setSnapshot(s2);
        // Knob centre x = barLeft(360) + 500 ms * 0.09 = 405; y = laneTop(1) = 168.
        CHECK(r2.hitTest(405, 148, v, &tr, &cl) == kZoneFadeL && tr == 1 && cl == 0,
              "fade knob wins above its own lane");
        CHECK(r2.hitTest(405, 168, v, &tr, &cl) == kZoneFadeL, "fade knob on its lane edge");
    }

    // ---- Draw smoke test + proof render ----
    const int W = 720, H = 488;
    std::vector<uint8_t> img((size_t)W * H * 4, 0);
    r.draw(img.data(), W, H, W * 4, v);
    auto px = [&](int x, int y) {
        const uint8_t* p = &img[((size_t)y * W + x) * 4];
        return ((uint32_t)p[3] << 24) | ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | p[2];
    };
    CHECK(px(700, 20) == 0xFF0A0E13u, "ruler background");
    CHECK(px(450, 465) == 0xFF3EB1F7u, "shape bar body colour");
    CHECK(px(360, 300) == 0xFFFFFFFFu, "playhead line over the lanes");
    CHECK(px(700, 320) == 0xFF0E1319u, "unselected chain section background");
    CHECK(px(271, 128) == 0xFFFFFFFFu, "selected bar white border");
    CHECK(px(42, 128) == 0xFF141B24u, "rail eye pupil shows the cell colour");
    CHECK(nearPx(px(370, 110), 0xFF1BBC87u, 4), "video bar filmstrip cell tint over the body colour");
    CHECK(px(363, 50) == 0xFFF4F4F5u, "timecode digit at the ruler centre");
    writeBmp("/tmp/timeline-proof.bmp", img, W, H);

    // ---- Build 227 hardening: hostile snapshots must not crash or read OOB ----
    {
        // Zero / NaN zoom: ppmPx falls back instead of dividing by zero.
        Snapshot sZ = s;
        sZ.pxPerMs = 0.f;
        CHECK_F(ppmPx(sZ), 0.045f, "ppmPx falls back when pxPerMs is 0");
        sZ.pxPerMs = std::nan("");
        CHECK_F(ppmPx(sZ), 0.045f, "ppmPx falls back when pxPerMs is NaN");
    }
    {
        // chainClip out of range (an index Kotlin never sends, but the
        // renderer must not dereference it): no section band, no chain zones.
        Snapshot sC = s;  // scene with the chain selected
        sC.tracks[3].chainClip = 99;
        Renderer rC;
        rC.setAtlas(ta.at);
        rC.setSnapshot(sC);
        std::vector<uint8_t> imgC((size_t)W * H * 4, 0);
        rC.draw(imgC.data(), W, H, W * 4, v);
        auto pxC = [&](int x, int y) {
            const uint8_t* p = &imgC[((size_t)y * W + x) * 4];
            return ((uint32_t)p[3] << 24) | ((uint32_t)p[0] << 16) |
                   ((uint32_t)p[1] << 8) | p[2];
        };
        CHECK(pxC(700, 320) == 0xFF0A0E13u, "out-of-range chainClip draws no section band");
        CHECK(rC.hitTest(696, 356, v, &tr, &cl) == kZoneBar && cl == 0,
              "out-of-range chainClip: no chain delete zone (section bg is a plain bar)");
        CHECK(rC.hitTest(42, 356, v, &tr, &cl) == kZoneEye && cl == 0,
              "out-of-range chainClip rail falls back to the head clip");
    }
    {
        // A digit entry whose rect extends past the atlas's right edge: the
        // timecode blit must clamp per pixel (build 224 read unchecked).
        TestAtlas ta2 = ta;
        AtlasEntry ov;
        ov.x = ta2.at.w - 2; ov.y = 64; ov.w = 8; ov.h = 14; ov.base = 14.f;
        ta2.at.entries.push_back(ov);
        ta2.at.digit[0] = (int)ta2.at.entries.size() - 1;
        Renderer rD;
        rD.setAtlas(ta2.at);
        rD.setSnapshot(sA);
        std::vector<uint8_t> imgD((size_t)W * H * 4, 0);
        rD.draw(imgD.data(), W, H, W * 4, v);  // completion is the assertion
        const uint8_t* p = &imgD[((size_t)300 * W + 360) * 4];
        uint32_t phPx = ((uint32_t)p[3] << 24) | ((uint32_t)p[0] << 16) |
                        ((uint32_t)p[1] << 8) | p[2];
        CHECK(phPx == 0xFFFFFFFFu, "overflowing digit entry: draw completes, playhead intact");
    }

    // ---- Build 259: filmstrip thumb window + strip blit ----
    {
        Clip tc;
        tc.srcDurMs = 10000; tc.trimStartMs = 2000; tc.durMs = 4000; tc.speed = 1.f;
        float u0, u1;
        thumbWindow(tc, &u0, &u1);
        CHECK_F(u0, 0.2f, "thumb window: trim start fraction");
        CHECK_F(u1, 0.6f, "thumb window: trim + duration fraction");
        tc.durMs = 2000; tc.speed = 2.f;
        thumbWindow(tc, &u0, &u1);
        CHECK_F(u1, 0.6f, "thumb window: speed consumes source twice as fast");
        tc.speed = 1.f; tc.durMs = 4000; tc.trimStartMs = 9000;
        thumbWindow(tc, &u0, &u1);
        CHECK_F(u0, 0.9f, "thumb window: late trim start");
        CHECK_F(u1, 1.f, "thumb window: overrun clamps to the source end");
        tc.trimStartMs = 12000;
        thumbWindow(tc, &u0, &u1);
        CHECK_F(u0, 1.f, "thumb window: trim past the source clamps to empty");
        CHECK_F(u1, 1.f, "thumb window: empty window has u1 == u0");
        tc.srcDurMs = 0;
        thumbWindow(tc, &u0, &u1);
        CHECK_F(u0, 0.f, "thumb window: no source -> empty");
        CHECK_F(u1, 0.f, "thumb window: no source -> empty end");
    }
    {
        // A two-colour strip: left half red, right half blue. A bar whose
        // window covers the first half of the source shows red; trimmed to
        // the second half it shows blue.
        ThumbAtlas th;
        th.w = 64; th.h = 16;
        th.rgba.assign((size_t)64 * 16 * 4, 0);
        for (int j = 0; j < 16; ++j)
            for (int i = 0; i < 64; ++i) {
                uint8_t* p = &th.rgba[((size_t)j * 64 + i) * 4];
                if (i < 32) { p[0] = 255; p[1] = 0; p[2] = 0; }
                else { p[0] = 0; p[1] = 0; p[2] = 255; }
                p[3] = 255;
            }
        AtlasEntry te; te.x = 0; te.y = 0; te.w = 64; te.h = 16;
        th.entries.push_back(te);

        Snapshot sT;
        sT.density = 2.f; sT.pxPerMs = 0.045f; sT.rulerHPx = 88; sT.railWPx = 84;
        sT.topPadPx = 8; sT.gapPx = 8; sT.bottomPadPx = 8; sT.totalMs = 8000;
        Track tT; tT.heightPx = 64;
        Clip vc; vc.startMs = 2000; vc.durMs = 4000; vc.kind = kGlyphVideo;
        vc.color = 0xFF10B981; vc.flags = kFlagVideo;
        vc.thumb = 0; vc.srcDurMs = 8000; vc.trimStartMs = 0; vc.speed = 1.f;
        tT.clips.push_back(vc);
        sT.tracks = {tT};
        Renderer rT;
        rT.setThumbAtlas(th);
        rT.setSnapshot(sT);
        const int WT = 720, HT = 300;
        std::vector<uint8_t> imgT((size_t)WT * HT * 4, 0);
        rT.draw(imgT.data(), WT, HT, WT * 4, v);
        auto pxT = [&](int x, int y) {
            const uint8_t* p = &imgT[((size_t)y * WT + x) * 4];
            return ((uint32_t)p[3] << 24) | ((uint32_t)p[0] << 16) |
                   ((uint32_t)p[1] << 8) | p[2];
        };
        // Bar: x 360..720, y 96..160. Window (0, 0.5) -> the red half
        // stretches across the whole bar.
        CHECK(pxT(400, 128) == 0xFFFF0000u, "filmstrip: first-half window shows the red frames");
        CHECK(pxT(680, 100) == 0xFFFF0000u, "filmstrip: red reaches toward the bar's right edge");
        // Trim into the second half of the source -> blue.
        sT.tracks[0].clips[0].trimStartMs = 4000;
        rT.setSnapshot(sT);
        rT.draw(imgT.data(), WT, HT, WT * 4, v);
        CHECK(pxT(400, 128) == 0xFF0000FFu, "filmstrip: second-half window shows the blue frames");
        // Strip not ready (thumb -1): the placeholder body colour stays.
        sT.tracks[0].clips[0].thumb = -1;
        rT.setSnapshot(sT);
        rT.draw(imgT.data(), WT, HT, WT * 4, v);
        CHECK(nearPx(pxT(400, 128), 0xFF10B981u, 30), "filmstrip: no strip -> placeholder body");
    }

    std::printf("timeline_selftest: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
