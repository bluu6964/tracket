#include "text_layout.h"

#include <cmath>

namespace am {
namespace text {

std::vector<LineSpan> breakLines(const std::vector<Item>& items, float maxW) {
    const int n = (int)items.size();
    std::vector<LineSpan> lines;
    std::vector<float> pre((size_t)n + 1, 0.f);
    for (int i = 0; i < n; ++i) pre[i + 1] = pre[i] + items[i].adv;
    auto push = [&](int a, int b) {
        LineSpan l;
        l.first = a; l.last = b;
        int e = b;
        while (e > a && items[e - 1].space) --e;  // trailing spaces do not count
        l.width = pre[e] - pre[a];
        lines.push_back(l);
    };
    const float eps = 0.01f;
    int lineStart = 0, wordStart = 0;
    for (int i = 0; i < n; ++i) {
        if (items[i].space) { wordStart = i + 1; continue; }
        float w = pre[i + 1] - pre[lineStart];
        if (w > maxW + eps) {
            if (wordStart > lineStart) {
                push(lineStart, wordStart);
                lineStart = wordStart;
                w = pre[i + 1] - pre[lineStart];
            }
            if (w > maxW + eps && i > lineStart) {
                push(lineStart, i);
                lineStart = i;
                wordStart = i;
            }
        }
    }
    push(lineStart, n);
    return lines;
}

float lineOffset(int align, float lineW, float boxW) {
    if (align == 0) return 0.f;
    if (align == 2) return boxW - lineW;
    return (boxW - lineW) * 0.5f;
}

int extraSpacing(int ascentNeg, int descentPos, float mult) {
    const double ex = (double)(descentPos - ascentNeg) * ((double)mult - 1.0);
    return ex >= 0 ? (int)(ex + 0.5) : -(int)(-ex + 0.5);
}

}  // namespace text
}  // namespace am
