// Tiny JPEG decoder that returns ONLY the luma (Y) plane. The cutout / face-blur masks are saved by
// the app as grey JPEGs (R = G = B), so Y is exactly the mask value and no colour conversion is
// needed. Supports baseline and extended-sequential Huffman JPEG (8 bit), 1 or 3 components, any
// chroma subsampling, restart intervals. Progressive JPEG is NOT supported (decode returns false).
// No dependencies, so it also builds on a host machine for tests.
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace am {

// Decodes `data` into `y` (width * height bytes, row 0 = top). Returns false on any error.
bool decodeJpegLuma(const uint8_t* data, size_t size, std::vector<uint8_t>* y, int* width, int* height);

}  // namespace am
