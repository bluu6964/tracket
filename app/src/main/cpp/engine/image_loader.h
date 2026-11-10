// Photo decoding with the NDK AImageDecoder (Android 11 / API 30+, resolved with dlsym so the library still
// loads on minSdk 24). Handles JPEG / PNG / WebP / HEIF / GIF-first-frame, applies the EXIF orientation and
// scales down to maxSide. Pixels are RGBA8888, alpha premultiplied (same as an Android Bitmap).
// Returns false when the decoder is missing or fails: the caller then uses the Kotlin decoder.
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace am {

// Size the picture is decoded at: the long side limited to maxSide, never below 1 (Kotlin: roundToInt).
void fitTargetSize(int w, int h, int maxSide, int* outW, int* outH);

// fd stays owned by the caller (it is only read).
bool decodePhotoRgba(int fd, int maxSide, std::vector<uint8_t>* pixels, int* w, int* h);

// A picture that is already in memory (a PNG cut out of a font, for example), decoded at its own size to
// premultiplied RGBA8888. Same decoder, so API 30+ only: encodedDecoderAvailable() says whether it can work.
bool encodedDecoderAvailable();
bool decodeEncodedRgba(const void* data, size_t len, std::vector<uint8_t>* pixels, int* w, int* h);

}  // namespace am
