// Layer masks (Alight Motion style, v134): up to 8 masks per layer, each a shape (rectangle, ellipse, polygon, star,
// line, mirror band, free pen path) with feather, expansion, opacity, invert and a combine mode (add / subtract /
// intersect / difference). The masks are laid in the picture's own space, so they move / scale / rotate with the layer.
// This file is the pure maths (no OpenGL): it turns the flat JNI array into pixel-space MaskOps and holds a CPU
// reference of the shader (tests/mask_plan_selftest.cpp checks the shader's maths against it).
#pragma once
#include <cstdint>

namespace am {

enum MaskShape : int {
    MS_RECT = 0,     // w, h, corner radius
    MS_ELLIPSE = 1,  // w, h
    MS_POLYGON = 2,  // sides (3..12), fitted into the w x h ellipse
    MS_STAR = 3,     // points (3..12), inner radius %
    MS_LINE = 4,     // half plane: the part ABOVE the line (before rotation) stays
    MS_MIRROR = 5,   // horizontal band of height h across the whole picture
    MS_PATH = 6      // closed pen path (3..16 control points, optional smooth curve)
};

enum MaskMode : int {
    MM_ADD = 0,         // union
    MM_SUBTRACT = 1,    // cut out of what the masks above made
    MM_INTERSECT = 2,   // keep only the overlap
    MM_DIFFERENCE = 3   // exclusive or
};

constexpr int kMaskMax = 8;
constexpr int kMaskCtrlMax = 16;                // pen control points
constexpr int kMaskPtsMax = 96;                 // flattened outline points the shader loops over
constexpr int kMaskStride = 16 + 2 * kMaskCtrlMax;  // floats per mask in the JNI array

/*
 * JNI layout of one mask (kMaskStride floats):
 *  0 shape  1 mode  2 invert (0/1)  3 centre x  4 centre y (0..1 of the picture, top-left origin)
 *  5 width  6 height (0..1+ of the picture width / height)  7 rotation (deg, clockwise)
 *  8 feather 0..100  9 expansion -100..100  10 opacity 0..100  11 corner 0..100 (rect)
 *  12 sides / points  13 inner radius % (star)  14 control point count  15 smooth (0/1)
 *  16.. control points x, y in the mask's own box: -0.5..0.5 (so the box handles move / scale / turn the path)
 */

struct MaskOp {
    int shape = MS_RECT;
    int mode = MM_ADD;
    bool invert = false;
    float cx = 0, cy = 0;       // centre, picture pixels (origin top-left)
    float hw = 1, hh = 1;       // half size, pixels
    float cosR = 1, sinR = 0;   // rotation
    float feather = 0;          // half width of the soft edge, pixels
    float expand = 0;           // pixels, > 0 grows the shape
    float opacity = 1;          // 0..1
    float corner = 0;           // rect corner radius, pixels
    float anRad = 0;            // pi / sides
    float anCos = 1, anSin = 0; // angle of the star / polygon sector
    float enCos = 0, enSin = 1; // direction of the star edge (cos / sin of "en")
    int nPts = 0;
    float pts[kMaskPtsMax * 2];  // PATH: flattened outline in picture pixels (rotation already applied)
};

// Reads `n` floats (groups of kMaskStride) for a picture of W x H pixels. Masks with opacity 0 and pens with fewer
// than 3 points are skipped. Returns the number of ops written (room for kMaskMax).
int maskParseOps(const float* flat, int n, int W, int H, MaskOp* out);

// Signed distance (pixels, negative inside) of picture point (x, y) to the shape, before feather / expansion.
float maskShapeDistance(const MaskOp& m, float x, float y);
// Coverage 0..1 of one mask at (x, y): feather, expansion, invert and opacity applied (the shader's first half).
float maskCoverage(const MaskOp& m, float x, float y);
// Combines the running result `prev` with the coverage `cov` of the next mask (the shader's second half).
float maskCombine(int mode, float prev, float cov);
// Value the running result starts at: 0 when the first mask adds, 1 when it subtracts / intersects (it then works
// on the whole picture).
float maskInitial(const MaskOp* ops, int n);
// Final value at a point with all masks (reference for tests).
float maskEvaluate(const MaskOp* ops, int n, float x, float y);

// Closed curve through the control points (Catmull-Rom) or the plain polygon, flattened to at most kMaskPtsMax points.
int maskFlattenPath(const float* ctrl, int n, bool smooth, float* out);

}  // namespace am
