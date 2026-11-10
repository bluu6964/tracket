#include "overlay_pose.h"

#include <algorithm>
#include <cmath>

namespace am {

OverlayPoseOut overlayPose(const OverlayPoseIn& in) {
    const float s = std::max(in.scale, 0.01f);
    const float fx = in.flipH ? -1.f : 1.f, fy = in.flipV ? -1.f : 1.f;
    const float px = (in.pivotX - 0.5f) * in.boxW;
    const float py = (in.pivotY - 0.5f) * in.boxH;
    const float dx = px * (1.f - s * fx);
    const float dy = py * (1.f - s * fy);

    OverlayPoseOut o;
    o.rotationDeg = in.rotationDeg;
    o.rotX = in.rotX;
    o.rotY = in.rotY;
    o.perspD = in.perspD;
    o.is3d = in.perspD > 0.f && (in.rotX != 0.f || in.rotY != 0.f);
    if (o.is3d) {
        o.pivotX = px - dx;
        o.pivotY = py - dy;
        o.baseX = in.transX + dx;
        o.baseY = in.transY + dy;
    } else {
        const double rad = (double)in.rotationDeg * 0.017453292519943295;
        const float c = (float)std::cos(rad), sn = (float)std::sin(rad);
        const float vx = dx - px, vy = dy - py;
        o.offsetX = in.transX + px + (c * vx - sn * vy);
        o.offsetY = in.transY + py + (sn * vx + c * vy);
    }
    return o;
}

}  // namespace am
