#include "layer_anim.h"

#include <algorithm>
#include <cmath>

namespace am {

namespace {
constexpr float kMinSpeed = 0.1f;
constexpr float kMaxSpeed = 4.f;
constexpr int64_t kFrameTolMs = 2;  // TrackFollow.FRAME_TOL_MS

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Kotlin Float.toLong(): truncates toward zero, NaN -> 0, saturates.
inline int64_t toLong(double v) {
    if (v != v) return 0;
    if (v >= 9.2233720368547758e18) return INT64_MAX;
    if (v <= -9.2233720368547758e18) return INT64_MIN;
    return (int64_t)v;
}
}  // namespace

// ---------------------------------------------------------------------------------------------
// Easing (cubic bezier, same solver as Easing.apply)

bool Easing::isLinear() const {
    return std::fabs(x1 - y1) < 1e-4f && std::fabs(x2 - y2) < 1e-4f && std::fabs(x1 - 1.f / 3.f) < 1e-3f &&
           std::fabs(x2 - 2.f / 3.f) < 1e-3f;
}

float Easing::apply(float t) const {
    if (t <= 0.f) return 0.f;
    if (t >= 1.f) return 1.f;
    auto bx = [&](float s) {
        const float u = 1.f - s;
        return 3.f * u * u * s * x1 + 3.f * u * s * s * x2 + s * s * s;
    };
    auto by = [&](float s) {
        const float u = 1.f - s;
        return 3.f * u * u * s * y1 + 3.f * u * s * s * y2 + s * s * s;
    };
    auto dbx = [&](float s) {
        const float u = 1.f - s;
        return 3.f * u * u * x1 + 6.f * u * s * (x2 - x1) + 3.f * s * s * (1.f - x2);
    };
    float s = t;
    for (int i = 0; i < 6; ++i) {
        const float err = bx(s) - t;
        if (std::fabs(err) < 1e-5f) return by(s);
        const float d = dbx(s);
        if (std::fabs(d) < 1e-6f) break;
        s = clampf(s - err / d, 0.f, 1.f);
    }
    float lo = 0.f, hi = 1.f;
    s = t;
    for (int i = 0; i < 24; ++i) {
        const float x = bx(s);
        if (std::fabs(x - t) < 1e-5f) break;
        if (x < t) lo = s; else hi = s;
        s = (lo + hi) / 2.f;
    }
    return by(s);
}

// ---------------------------------------------------------------------------------------------

KeyValue valueAt(const std::vector<Key>& k, int64_t relMs) {
    const Key& first = k.front();
    if (relMs <= first.timeMs) return {first.a, first.b};
    const Key& last = k.back();
    if (relMs >= last.timeMs) return {last.a, last.b};
    size_t i = 0;
    while (i < k.size() - 1 && k[i + 1].timeMs <= relMs) i++;
    const Key& k0 = k[i];
    const Key& k1 = k[i + 1];
    const int64_t span = std::max<int64_t>(k1.timeMs - k0.timeMs, 1);
    const float t = (float)(relMs - k0.timeMs) / (float)span;
    const float e = k0.easing.isLinear() ? t : k0.easing.apply(t);
    return {k0.a + (k1.a - k0.a) * e, k0.b + (k1.b - k0.b) * e};
}

KeyValue colorValueAt(const std::vector<Key>& k, int64_t relMs) {
    const Key& first = k.front();
    if (relMs <= first.timeMs) return {first.a, first.b};
    const Key& last = k.back();
    if (relMs >= last.timeMs) return {last.a, last.b};
    size_t i = 0;
    while (i < k.size() - 1 && k[i + 1].timeMs <= relMs) i++;
    const Key& k0 = k[i];
    const Key& k1 = k[i + 1];
    const int64_t span = std::max<int64_t>(k1.timeMs - k0.timeMs, 1);
    const float t = (float)(relMs - k0.timeMs) / (float)span;
    const float e = k0.easing.isLinear() ? t : k0.easing.apply(t);
    auto unpack = [](float pa, float pb, int ch[4]) {  // unpackColor: A R G B
        const int x = (int)clampf((float)(int)pa, 0.f, 65535.f);
        const int y = (int)clampf((float)(int)pb, 0.f, 65535.f);
        ch[0] = x >> 8; ch[1] = x & 0xFF; ch[2] = y >> 8; ch[3] = y & 0xFF;
    };
    int c0[4], c1[4], o[4];
    unpack(k0.a, k0.b, c0);
    unpack(k1.a, k1.b, c1);
    for (int c = 0; c < 4; ++c) {
        const float v = (float)c0[c] + (float)(c1[c] - c0[c]) * e;
        o[c] = (int)clampf((float)(int)v, 0.f, 255.f);  // Float.toInt() then coerceIn(0, 255)
    }
    return {(float)(o[0] * 256 + o[1]), (float)(o[2] * 256 + o[3])};
}

int64_t LayerAnim::sourceOffsetMs(int64_t relMs) const {
    if (relMs <= 0) return 0;
    const auto& list = keys[P_SPEED];
    if (list.empty()) return toLong((double)((float)relMs * st.speed));
    const int64_t step = 20;
    double acc = 0.0;
    int64_t t = 0;
    while (t < relMs) {
        const int64_t dt = std::min(step, relMs - t);
        acc += (double)valueAt(list, t + dt / 2).a * (double)dt;
        t += dt;
    }
    return toLong(acc);
}

void LayerAnim::finalize() {
    for (auto& v : keys) {
        std::stable_sort(v.begin(), v.end(), [](const Key& a, const Key& b) { return a.timeMs < b.timeMs; });
    }
    if (keys[P_SPEED].empty()) {
        exportSpeed_ = st.speed;
    } else {
        const int64_t span = sourceOffsetMs(st.durationMs);
        exportSpeed_ = clampf((float)span / (float)std::max<int64_t>(st.durationMs, 1), kMinSpeed, kMaxSpeed);
    }
}

int64_t LayerAnim::sourceMsAt(int64_t timeMs) const {
    return st.trimStartMs + toLong((double)((float)(timeMs - st.startMs) * exportSpeed_));
}

int LayerAnim::trackIndexAt(int64_t t) const {
    const int n = (int)trackTimes.size();
    if (n == 0) return -1;
    const int64_t key = t + kFrameTolMs;
    if (key < trackTimes[0]) return 0;
    int lo = 0, hi = n - 1;
    while (lo < hi) {
        const int mid = (int)(((unsigned)(lo + hi + 1)) >> 1);
        if (trackTimes[mid] <= key) lo = mid; else hi = mid - 1;
    }
    return lo;
}

ClipValues LayerAnim::clipAt(int64_t timeMs, int64_t trackMs) const {
    const int64_t rel = timeMs - st.startMs;
    auto get = [&](Prop p, float a, float b) -> KeyValue {
        const auto& l = keys[p];
        return l.empty() ? KeyValue{a, b} : valueAt(l, rel);
    };
    const KeyValue pos = get(P_POSITION, st.transformX, st.transformY);
    const KeyValue scl = get(P_SCALE, st.scalePercent, 0.f);
    const KeyValue rot = get(P_ROTATION, st.rotationDeg, 0.f);
    const KeyValue pz = get(P_POS_Z, st.transformZ, 0.f);
    const KeyValue cs = get(P_CROP_SIZE, st.cropWidthPercent, st.cropHeightPercent);
    const KeyValue cp = get(P_CROP_POS, st.cropHorizontalPercent, st.cropVerticalPercent);
    const KeyValue op = get(P_OPACITY, st.opacity, 0.f);
    const KeyValue sp = get(P_SPEED, st.speed, 0.f);
    const KeyValue tr = get(P_STROKE_TRIM, st.strokeTrimStart, st.strokeTrimEnd);
    ClipValues o;
    o.rotX = get(P_ROT_X, st.rotation3dX, 0.f).a;
    o.rotY = get(P_ROT_Y, st.rotation3dY, 0.f).a;
    auto col = [&](Prop p, float& ca, float& cb) {
        const auto& l = keys[p];
        const KeyValue v = l.empty() ? KeyValue{0.f, 0.f} : colorValueAt(l, rel);
        ca = v.a;
        cb = v.b;
    };
    col(P_FILL_COLOR, o.fillA, o.fillB);
    col(P_GRAD_COLOR_1, o.grad1A, o.grad1B);
    col(P_GRAD_COLOR_2, o.grad2A, o.grad2B);
    o.posX = pos.a;
    o.posY = pos.b;
    const int ti = trackIndexAt(trackMs);
    float trackRotDeg = 0.f, trackScl = 1.f;
    if (ti >= 0) {
        o.posX += trackDx[ti];
        o.posY += trackDy[ti];
        if ((size_t)ti < trackRot.size()) trackRotDeg = trackRot[ti];
        if ((size_t)ti < trackScale.size()) trackScl = trackScale[ti];
    }
    o.posZ = pz.a;
    o.scalePercent = scl.a * trackScl;
    o.scaleYPercent = scl.b * trackScl;
    o.rotationDeg = rot.a + trackRotDeg;
    o.cropWPercent = cs.a;
    o.cropHPercent = cs.b;
    o.cropXPercent = cp.a;
    o.cropYPercent = cp.b;
    o.opacityPercent = op.a;
    o.speed = clampf(sp.a, kMinSpeed, kMaxSpeed);
    o.strokeTrimStart = std::min(1.f, std::max(0.f, tr.a));
    o.strokeTrimEnd = std::min(1.f, std::max(0.f, tr.b));
    return o;
}

FrameParams LayerAnim::eval(int64_t timeMs, int outW, int outH, float previewWidthPx) const {
    const int64_t rel = timeMs - st.startMs;
    auto get = [&](Prop p, float a, float b) -> KeyValue {
        const auto& l = keys[p];
        return l.empty() ? KeyValue{a, b} : valueAt(l, rel);  // TimelineClip.at()
    };
    KeyValue pos = get(P_POSITION, st.transformX, st.transformY);
    const KeyValue scl = get(P_SCALE, st.scalePercent, 0.f);
    const KeyValue rot = get(P_ROTATION, st.rotationDeg, 0.f);
    const KeyValue pz = get(P_POS_Z, st.transformZ, 0.f);
    const KeyValue cs = get(P_CROP_SIZE, st.cropWidthPercent, st.cropHeightPercent);
    const KeyValue cp = get(P_CROP_POS, st.cropHorizontalPercent, st.cropVerticalPercent);
    const KeyValue op = get(P_OPACITY, st.opacity, 0.f);

    // renderAt(): tracking offset on top of the position.
    const int ti = trackIndexAt(timeMs);
    float trackRotDeg = 0.f, trackScl = 1.f;
    if (ti >= 0) {
        pos.a += trackDx[ti];
        pos.b += trackDy[ti];
        if ((size_t)ti < trackRot.size()) trackRotDeg = trackRot[ti];
        if ((size_t)ti < trackScale.size()) trackScl = trackScale[ti];
    }
    const float rotA = rot.a + trackRotDeg;

    // Depth (model/EditorModels.kt depthFactorOf): perspective factor D / (D - z) scales the layer AND its
    // position; from kDepthHideZ on the layer is at / behind the camera and is not drawn.
    const float d = 1000.f;
    const float kDepthHideZ = 980.f;
    const float z = std::min(pz.a, kDepthHideZ);
    const float depth = std::max(d / (d - z), 0.001f);
    const float effScale = scl.a * trackScl * depth;
    pos.a *= depth;
    pos.b *= depth;

    // layerPose()
    const float k = previewWidthPx > 0.f ? (float)outW / previewWidthPx : 1.f;
    const float s = std::max(effScale / 100.f, 0.01f);
    const double rad = (double)rotA * 0.017453292519943295;  // Math.toRadians
    const float cosr = (float)std::cos(rad);
    const float sinr = (float)std::sin(rad);
    const float pvx = (st.pivotX - 0.5f) * (float)outW;
    const float pvy = (st.pivotY - 0.5f) * (float)outH;
    const float rx = s * (cosr * pvx - sinr * pvy);
    const float ry = s * (sinr * pvx + cosr * pvy);

    FrameParams o;
    o.offsetX = pos.a * k + pvx - rx;
    o.offsetY = pos.b * k + pvy - ry;
    o.rotationDeg = rotA;
    o.scaleX = s * (st.flipH ? -1.f : 1.f);
    o.scaleY = s * (st.flipV ? -1.f : 1.f);
    o.opacity = pz.a >= kDepthHideZ ? 0.f : clampf(op.a / 100.f, 0.f, 1.f);
    const float cW = clampf(cs.a / 100.f, 0.05f, 1.f);
    const float cH = clampf(cs.b / 100.f, 0.05f, 1.f);
    o.cropW = cW;
    o.cropH = cH;
    o.cropX = (1.f - cW) * (cp.a / 100.f);
    o.cropY = (1.f - cH) * (cp.b / 100.f);
    o.rotX = get(P_ROT_X, st.rotation3dX, 0.f).a;
    o.rotY = get(P_ROT_Y, st.rotation3dY, 0.f).a;
    o.pivotX = pvx;
    o.pivotY = pvy;
    o.baseX = pos.a * k;
    o.baseY = pos.b * k;
    o.perspD = d * k;  // same camera distance as the Z depth (DEPTH_D preview pixels)
    return o;
}

}  // namespace am
