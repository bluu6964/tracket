// Pure line breaking / alignment maths of the native text renderer (same rules as Android's
// StaticLayout as used by LayerPainter.drawText). Host test: tests/text_layout_selftest.cpp
#pragma once
#include <vector>

namespace am {
namespace text {

/** One shaped cluster (a letter, or a letter with its marks, or a conjunct). */
struct Item {
    float adv = 0;
    bool space = false;  // breakable white space
};

struct LineSpan {
    int first = 0, last = 0;  // items [first, last)
    float width = 0;          // without trailing spaces
};

/** Greedy wrap: breaks after spaces; a word longer than [maxW] is split between clusters. Never returns 0 lines. */
std::vector<LineSpan> breakLines(const std::vector<Item>& items, float maxW);

/** x offset of a line of width [lineW] inside a layout [boxW] wide. align: 0 start, 1 centre, 2 end. */
float lineOffset(int align, float lineW, float boxW);

/** Android's extra line spacing: the line height (descent - ascent, ints) times (mult - 1), rounded half away from 0. */
int extraSpacing(int ascentNeg, int descentPos, float mult);

/** A point on a path at an arc distance: position + tangent angle (degrees, y down, 0 = +x). */
struct PathPoint {
    float x = 0, y = 0, angleDeg = 0;
};

/**
 * Arc-length walk over a polyline (frame pixels, y down) — the text-on-path layout law,
 * the twin of the Kotlin model (model/TextOnPath.kt), whose tests pin the same numbers:
 * consecutive duplicate points are dropped (they carry neither arc nor tangent); at(d)
 * clamps to [0, total]; the tangent at an exact joint is the OUTGOING segment's (the one
 * the pen is about to travel), except at the very end, where it is the last segment's.
 * A walk over fewer than 2 distinct points has total 0 and places nothing.
 */
class PathWalk {
public:
    PathWalk() = default;
    /** [pts] = x,y pairs. */
    PathWalk(const float* pts, size_t pairCount) { build(pts, pairCount); }
    PathWalk(const std::vector<float>& pts) { build(pts.data(), pts.size() / 2); }

    bool empty() const { return pts_.empty(); }
    float total() const { return total_; }
    float minX() const { return minX_; }
    float minY() const { return minY_; }
    float maxX() const { return maxX_; }
    float maxY() const { return maxY_; }
    PathPoint at(float d) const;

private:
    void build(const float* pts, size_t pairCount);
    std::vector<float> pts_;   // x,y pairs, duplicates dropped
    std::vector<float> cum_;   // cum_[i] = arc length at point i; cum_[0] = 0
    float total_ = 0;
    float minX_ = 0, minY_ = 0, maxX_ = 0, maxY_ = 0;
};

}  // namespace text
}  // namespace am
