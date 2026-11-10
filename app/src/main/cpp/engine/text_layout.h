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

}  // namespace text
}  // namespace am
