// 3D camera (v151, Alight Motion style Camera layer). The scene is the layers at their 3D places (x, y, Z depth, turned in
// 3D); the camera pans, dollies (Z), zooms, rolls and orbits, and looks at the scene through ONE pinhole camera (distance
// D, the same D as the Z depth). A layer under a camera is drawn on the 4 projected corners of its picture (the renderer's
// corner pin path, quad_warp.h), so there is no second drawing code.
//
// Layer (before the camera), the same maths as gl_math.h layerModel3d with the layer's own perspective replaced by the
// scene's one:   point = base + pivot + Rx * Ry * Rz * S * (q - pivot),  z (into the screen) = -Z + that point's z
// Camera:        p -= (panX, panY);  p = Rz(-roll) Ry(-rotY) Rx(-rotX) p;  z -= dolly;  (x, y) *= zoom;  screen = (x, y) / (1 + z / D)
// With no camera, Z = 0 and no 3D turn this is exactly the flat layer; with Z != 0 it is the old "depth factor" D / (D - Z).
// Pure C++, host-tested: tests/camera_selftest.cpp.
#pragma once

namespace am {

constexpr int kCamIn = 25;
// in[]: 0 canvasW, 1 canvasH, 2 contentW, 3 contentH (whole picture), 4 cropW, 5 cropH (fractions of it),
//       6 baseX, 7 baseY (canvas px from the centre, position WITHOUT the depth factor), 8 Z (px, toward the camera = +),
//       9 scaleX, 10 scaleY (without the depth factor; negative = flipped), 11 rotationDeg (Z), 12 rotX, 13 rotY (Compose
//       convention), 14 pivotX, 15 pivotY (px from the canvas centre), 16 D (px),
//       17 hasCamera (0 / 1), 18 panX, 19 panY, 20 dolly (px, + = camera moves into the scene), 21 zoom (1 = 100 %),
//       22 camRotX, 23 camRotY, 24 camRoll (degrees)
// out[8]: x, y of TL, TR, BR, BL in canvas px from the TOP-LEFT of the canvas (the corner pin order).
// Returns false when a corner is at / behind the camera (the layer is not drawn then).
bool cameraQuad(const float* in, float* out);

// Distance of the CENTRE of the layer (its pivot-compensated origin) from the camera plane, after pan / orbit / roll /
// dolly: bigger = farther. Same in[] as cameraQuad (content / crop / scale / pivot are not used). Used to order the layers:
// the farthest is drawn first, so a layer the camera has dollied past or orbited behind goes to the back. With no camera
// it is -Z. Returns false when the centre is at / behind the camera (the layer is not drawn then).
bool cameraDepth(const float* in, float* depth);

}  // namespace am
