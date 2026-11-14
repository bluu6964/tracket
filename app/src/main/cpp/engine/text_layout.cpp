#include "text_layout.h"

#include <algorithm>
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

void PathWalk::build(const float* pts, size_t pairCount) {
    pts_.clear();
    cum_.clear();
    total_ = 0;
    minX_ = minY_ = maxX_ = maxY_ = 0;
    if (!pts) return;
    for (size_t i = 0; i < pairCount; ++i) {
        const float x = pts[2 * i], y = pts[2 * i + 1];
        if (!pts_.empty() && pts_[pts_.size() - 2] == x && pts_[pts_.size() - 1] == y) continue;
        if (pts_.empty()) { minX_ = maxX_ = x; minY_ = maxY_ = y; }
        pts_.push_back(x);
        pts_.push_back(y);
        minX_ = std::min(minX_, x); maxX_ = std::max(maxX_, x);
        minY_ = std::min(minY_, y); maxY_ = std::max(maxY_, y);
    }
    const size_t n = pts_.size() / 2;
    cum_.assign(n, 0.f);
    float acc = 0;
    for (size_t i = 1; i < n; ++i) {
        acc += std::hypot(pts_[2 * i] - pts_[2 * i - 2], pts_[2 * i + 1] - pts_[2 * i - 1]);
        cum_[i] = acc;
    }
    total_ = acc;
}

PathPoint PathWalk::at(float d) const {
    PathPoint out;
    const size_t n = pts_.size() / 2;
    if (n == 0) return out;
    out.x = pts_[0]; out.y = pts_[1];
    if (n == 1 || total_ <= 0.f) return out;
    const float dd = std::min(std::max(d, 0.f), total_);
    // First segment whose END is strictly past dd: at an exact joint this is the outgoing
    // segment. dd == total falls through to the last segment. (Signed indices: the
    // search's hi can step to -1, exactly as the Kotlin walk's does.)
    long lo = 0, hi = (long)n - 2, seg = (long)n - 2;
    while (lo <= hi) {
        const long mid = (lo + hi) / 2;
        if (cum_[(size_t)mid + 1] > dd) { seg = mid; hi = mid - 1; } else lo = mid + 1;
    }
    const float ax = pts_[2 * seg], ay = pts_[2 * seg + 1];
    const float bx = pts_[2 * seg + 2], by = pts_[2 * seg + 3];
    const float segLen = cum_[seg + 1] - cum_[seg];
    const float t = segLen > 0.f ? (dd - cum_[seg]) / segLen : 0.f;
    out.x = ax + (bx - ax) * t;
    out.y = ay + (by - ay) * t;
    out.angleDeg = (float)(std::atan2((double)(by - ay), (double)(bx - ax)) * 180.0 / 3.141592653589793);
    return out;
}

}  // namespace text
}  // namespace am
