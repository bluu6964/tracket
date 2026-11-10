#include "camera.h"

#include <algorithm>
#include <cmath>

#include "gl_math.h"

namespace am {
namespace {
struct V3 {
    float x, y, z;
};
V3 mul3(const Mat4& m, const V3& v) {
    return {m.m[0] * v.x + m.m[4] * v.y + m.m[8] * v.z + m.m[12], m.m[1] * v.x + m.m[5] * v.y + m.m[9] * v.z + m.m[13],
            m.m[2] * v.x + m.m[6] * v.y + m.m[10] * v.z + m.m[14]};
}
}  // namespace

bool cameraQuad(const float* in, float* out) {
    const float W = in[0], H = in[1];
    const float cwF = std::min(1.f, std::max(0.01f, in[4]));
    const float chF = std::min(1.f, std::max(0.01f, in[5]));
    const float cW = in[2] * cwF, cH = in[3] * chF;
    if (W <= 0.f || H <= 0.f || cW <= 0.f || cH <= 0.f) return false;
    const float fit = std::min(W / cW, H / cH);
    const float bw = cW * fit, bh = cH * fit;  // the cropped picture, fitted into the canvas, at scale 1
    const float baseX = in[6], baseY = in[7], zToward = in[8];
    const float sx = in[9], sy = in[10];
    const float pvx = in[14], pvy = in[15];
    const float D = std::max(in[16], 1.f);
    // Same signs as layerModel3d (Android turns the other way round than the plain right-handed matrices).
    const Mat4 R = Mat4::rotateX(in[12] * -1.f) * Mat4::rotateY(in[13] * -1.f) * Mat4::rotateZ(in[11]);
    const bool cam = in[17] > 0.5f;
    Mat4 Rc = Mat4::identity();
    if (cam) Rc = Mat4::rotateZ(-in[24]) * Mat4::rotateY(-in[23] * -1.f) * Mat4::rotateX(-in[22] * -1.f);
    const float zoom = cam ? std::max(in[21], 0.001f) : 1.f;

    static const float corner[4][2] = {{-0.5f, -0.5f}, {0.5f, -0.5f}, {0.5f, 0.5f}, {-0.5f, 0.5f}};  // TL TR BR BL
    for (int i = 0; i < 4; ++i) {
        const float qx = corner[i][0] * bw, qy = corner[i][1] * bh;
        V3 v = mul3(R, {(qx - pvx) * sx, (qy - pvy) * sy, 0.f});
        V3 p = {v.x + pvx + baseX, v.y + pvy + baseY, v.z - zToward};
        if (cam) {
            p.x -= in[18];
            p.y -= in[19];
            p = mul3(Rc, p);
            p.z -= in[20];
            p.x *= zoom;
            p.y *= zoom;
        }
        const float den = 1.f + p.z / D;
        if (den <= 0.05f) return false;
        out[i * 2] = W * 0.5f + p.x / den;
        out[i * 2 + 1] = H * 0.5f + p.y / den;
    }
    return true;
}

bool cameraDepth(const float* in, float* depth) {
    const float baseX = in[6], baseY = in[7], zToward = in[8];
    const float pvx = in[14], pvy = in[15];
    const float D = std::max(in[16], 1.f);
    const Mat4 R = Mat4::rotateX(in[12] * -1.f) * Mat4::rotateY(in[13] * -1.f) * Mat4::rotateZ(in[11]);
    const bool cam = in[17] > 0.5f;
    // The layer's centre (q = 0), turned about its pivot like cameraQuad does for the corners.
    V3 v = mul3(R, {(0.f - pvx), (0.f - pvy), 0.f});
    V3 p = {v.x + pvx + baseX, v.y + pvy + baseY, v.z - zToward};
    if (cam) {
        p.x -= in[18];
        p.y -= in[19];
        const Mat4 Rc = Mat4::rotateZ(-in[24]) * Mat4::rotateY(-in[23] * -1.f) * Mat4::rotateX(-in[22] * -1.f);
        p = mul3(Rc, p);
        p.z -= in[20];
    }
    if (1.f + p.z / D <= 0.05f) return false;
    *depth = p.z;
    return true;
}

}  // namespace am
