// g++ -std=c++17 -Wall -Wextra -I.. ../engine/image_loader.cpp image_loader_selftest.cpp -o image_loader_selftest -ldl && ./image_loader_selftest
// (on a PC without android/log.h, add -I with a stub header). Only the size maths is testable off-device.
#include <cstdio>

#include "engine/image_loader.h"

static int fails = 0;
static void check(int w, int h, int maxSide, int ew, int eh) {
    int ow = 0, oh = 0;
    am::fitTargetSize(w, h, maxSide, &ow, &oh);
    if (ow != ew || oh != eh) { ++fails; std::printf("FAIL %dx%d max %d -> %dx%d (want %dx%d)\n", w, h, maxSide, ow, oh, ew, eh); }
}
int main() {
    check(4000, 3000, 2560, 2560, 1920);   // landscape photo
    check(3000, 4000, 2560, 1920, 2560);   // portrait photo
    check(1000, 800, 2560, 1000, 800);     // small: untouched
    check(2560, 2560, 2560, 2560, 2560);   // exactly at the limit
    check(10000, 10, 2560, 2560, 3);       // 2560 / 1000 -> 2.56 rounds to 3
    check(20000, 1, 2560, 2560, 1);        // never below 1
    std::printf(fails ? "image_loader_selftest: %d FAILED\n" : "image_loader_selftest: all OK\n", fails);
    return fails ? 1 : 0;
}
