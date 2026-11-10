// Tiny column-major 4x4 matrix helpers (same layout as OpenGL / SurfaceTexture.getTransformMatrix).
#pragma once
#include <cmath>
#include <cstring>

namespace am {

struct Mat4 {
    float m[16];

    static Mat4 identity() {
        Mat4 r;
        memset(r.m, 0, sizeof(r.m));
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.f;
        return r;
    }
    static Mat4 fromArray(const float* a) {
        Mat4 r;
        memcpy(r.m, a, sizeof(r.m));
        return r;
    }
    // Canvas pixels -> clip space. Y points DOWN: (0,0) is the top-left corner of the canvas.
    static Mat4 canvas(float w, float h) {
        Mat4 r = identity();
        r.m[0] = 2.f / w;
        r.m[5] = -2.f / h;
        r.m[12] = -1.f;
        r.m[13] = 1.f;
        return r;
    }
    static Mat4 translate(float x, float y) {
        Mat4 r = identity();
        r.m[12] = x;
        r.m[13] = y;
        return r;
    }
    static Mat4 scale(float x, float y) {
        Mat4 r = identity();
        r.m[0] = x;
        r.m[5] = y;
        return r;
    }
    // Standard rotation matrix, degrees. In a Y-down canvas this turns clockwise on screen.
    static Mat4 rotateZ(float deg) {
        const float a = deg * 3.14159265358979f / 180.f;
        const float c = std::cos(a), s = std::sin(a);
        Mat4 r = identity();
        r.m[0] = c;
        r.m[1] = s;
        r.m[4] = -s;
        r.m[5] = c;
        return r;
    }
    // Plain right-handed 3D rotations (x right, y DOWN, z INTO the screen): rotateY(+a) brings the RIGHT edge toward the
    // viewer, rotateX(+a) the BOTTOM edge away. Android's rotationX / rotationY are the opposite (see layerModel3d). Degrees.
    static Mat4 rotateX(float deg) {
        const float a = deg * 3.14159265358979f / 180.f;
        const float c = std::cos(a), s = std::sin(a);
        Mat4 r = identity();
        r.m[5] = c;
        r.m[6] = s;
        r.m[9] = -s;
        r.m[10] = c;
        return r;
    }
    static Mat4 rotateY(float deg) {
        const float a = deg * 3.14159265358979f / 180.f;
        const float c = std::cos(a), s = std::sin(a);
        Mat4 r = identity();
        r.m[0] = c;
        r.m[2] = -s;
        r.m[8] = s;
        r.m[10] = c;
        return r;
    }
    // Pinhole camera on the z axis at distance d in FRONT of the z = 0 plane: w = 1 + z / d (z into the screen).
    static Mat4 perspective(float d) {
        Mat4 r = identity();
        r.m[11] = 1.f / d;
        return r;
    }
    // Drops z (the layers are painted in order, there is no depth buffer); w is kept for the perspective divide.
    static Mat4 flattenZ() {
        Mat4 r = identity();
        r.m[10] = 0.f;
        return r;
    }
    Mat4 operator*(const Mat4& b) const {
        Mat4 r;
        for (int col = 0; col < 4; ++col)
            for (int row = 0; row < 4; ++row) {
                float v = 0.f;
                for (int k = 0; k < 4; ++k) v += m[k * 4 + row] * b.m[col * 4 + k];
                r.m[col * 4 + row] = v;
            }
        return r;
    }
    void apply(float x, float y, float* ox, float* oy) const {
        *ox = m[0] * x + m[4] * y + m[12];
        *oy = m[1] * x + m[5] * y + m[13];
    }
};

// Model matrix of a layer in canvas pixels. The picture (cw x ch, already rotated) is fitted
// ("contain") into the canvas, then offset / scaled / rotated around the canvas centre + offset.
inline Mat4 layerModel(float canvasW, float canvasH, float cw, float ch, float offX, float offY,
                       float scaleX, float scaleY, float rotDeg) {
    const float fit = std::fmin(canvasW / cw, canvasH / ch);
    return Mat4::translate(canvasW * 0.5f + offX, canvasH * 0.5f + offY) * Mat4::rotateZ(rotDeg) *
           Mat4::scale(scaleX, scaleY) * Mat4::scale(cw * fit, ch * fit);
}

// Model matrix of a layer that is turned in 3D (rotX / rotY degrees) around its pivot, with perspective.
//   point' = centre + base + pivot + Persp( Rx * Ry * Rz * S * (point - pivot) )
// (Compose: translate(pivot) * rotateX * rotateY * rotateZ * scale * translate(-pivot)). [pivX, pivY] = pivot relative to the
// canvas centre, [baseX, baseY] = position after the depth factor, WITHOUT the pivot compensation that the 2D
// version folds into its offset. persp = camera distance in canvas pixels (the same camera as the Z depth,
// DEPTH_D = 1000 preview px). signX / signY: Android (Compose graphicsLayer / View) turns the OTHER way round than the plain
// right-handed matrices - positive rotationY moves the right edge AWAY, positive rotationX moves the top edge AWAY
// (measured on a phone against the Compose preview, v121) - so both default to -1.
inline Mat4 layerModel3d(float canvasW, float canvasH, float cw, float ch, float baseX, float baseY, float pivX,
                         float pivY, float scaleX, float scaleY, float rotZDeg, float rotXDeg, float rotYDeg,
                         float persp, float signX = -1.f, float signY = -1.f) {
    const float fit = std::fmin(canvasW / cw, canvasH / ch);
    return Mat4::translate(canvasW * 0.5f + baseX + pivX, canvasH * 0.5f + baseY + pivY) * Mat4::perspective(persp) *
           Mat4::rotateX(rotXDeg * signX) * Mat4::rotateY(rotYDeg * signY) * Mat4::rotateZ(rotZDeg) *
           Mat4::scale(scaleX, scaleY) * Mat4::translate(-pivX, -pivY) * Mat4::scale(cw * fit, ch * fit);
}

// Texture-coordinate matrix: SurfaceTexture transform, preceded by the video's rotation metadata
// (decoders do not rotate surface output). Works in (u, v-up) space around the picture centre.
inline Mat4 layerTexMatrix(const float* surfaceTexMatrix, int contentRotationDeg) {
    return Mat4::fromArray(surfaceTexMatrix) * Mat4::translate(0.5f, 0.5f) *
           Mat4::rotateZ((float)contentRotationDeg) * Mat4::translate(-0.5f, -0.5f);
}

}  // namespace am
