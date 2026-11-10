#include "image_loader.h"

#include <dlfcn.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#define TAG "AmImageLoader"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace am {

void fitTargetSize(int w, int h, int maxSide, int* outW, int* outH) {
    const int side = std::max(w, h);
    if (maxSide > 0 && side > maxSide) {
        const float f = (float)maxSide / (float)side;
        *outW = std::max(1, (int)std::lround((double)w * f));
        *outH = std::max(1, (int)std::lround((double)h * f));
    } else {
        *outW = w;
        *outH = h;
    }
}

namespace {
// <android/imagedecoder.h> only declares these for API 30+, so the few needed ones are declared here.
struct AImageDecoder;
struct AImageDecoderHeaderInfo;
constexpr int kBitmapFormatRgba8888 = 1;  // ANDROID_BITMAP_FORMAT_RGBA_8888

struct DecoderApi {
    void* lib = nullptr;
    int (*createFromFd)(int, AImageDecoder**) = nullptr;
    int (*createFromBuffer)(const void*, size_t, AImageDecoder**) = nullptr;
    int (*setFormat)(AImageDecoder*, int32_t) = nullptr;
    int (*setTargetSize)(AImageDecoder*, int32_t, int32_t) = nullptr;
    const AImageDecoderHeaderInfo* (*header)(AImageDecoder*) = nullptr;
    int32_t (*headerW)(const AImageDecoderHeaderInfo*) = nullptr;
    int32_t (*headerH)(const AImageDecoderHeaderInfo*) = nullptr;
    size_t (*minStride)(AImageDecoder*) = nullptr;
    int (*decode)(AImageDecoder*, void*, size_t, size_t) = nullptr;
    void (*destroy)(AImageDecoder*) = nullptr;

    bool load() {
        lib = dlopen("libjnigraphics.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return false;
        auto sym = [&](const char* n) { return dlsym(lib, n); };
        createFromFd = reinterpret_cast<decltype(createFromFd)>(sym("AImageDecoder_createFromFd"));
        createFromBuffer = reinterpret_cast<decltype(createFromBuffer)>(sym("AImageDecoder_createFromBuffer"));
        setFormat = reinterpret_cast<decltype(setFormat)>(sym("AImageDecoder_setAndroidBitmapFormat"));
        setTargetSize = reinterpret_cast<decltype(setTargetSize)>(sym("AImageDecoder_setTargetSize"));
        header = reinterpret_cast<decltype(header)>(sym("AImageDecoder_getHeaderInfo"));
        headerW = reinterpret_cast<decltype(headerW)>(sym("AImageDecoderHeaderInfo_getWidth"));
        headerH = reinterpret_cast<decltype(headerH)>(sym("AImageDecoderHeaderInfo_getHeight"));
        minStride = reinterpret_cast<decltype(minStride)>(sym("AImageDecoder_getMinimumStride"));
        decode = reinterpret_cast<decltype(decode)>(sym("AImageDecoder_decodeImage"));
        destroy = reinterpret_cast<decltype(destroy)>(sym("AImageDecoder_delete"));
        return createFromFd && setFormat && setTargetSize && header && headerW && headerH && minStride && decode &&
               destroy;
    }
    ~DecoderApi() { if (lib) dlclose(lib); }
};
DecoderApi& api() { static DecoderApi a; static const bool ok = a.load(); (void)ok; return a; }
}  // namespace

namespace {
bool coreReady(const DecoderApi& a) {
    return a.lib && a.setFormat && a.setTargetSize && a.header && a.headerW && a.headerH && a.minStride && a.decode &&
           a.destroy;
}

// Decodes an opened decoder into tightly packed RGBA8888 pixels (the long side limited to maxSide, 0 = no limit).
bool decodeOpened(DecoderApi& a, AImageDecoder* dec, int maxSide, std::vector<uint8_t>* pixels, int* w, int* h) {
    bool ok = false;
    do {
        if (a.setFormat(dec, kBitmapFormatRgba8888) != 0) break;
        const AImageDecoderHeaderInfo* info = a.header(dec);
        if (!info) break;
        const int srcW = a.headerW(info), srcH = a.headerH(info);
        if (srcW <= 0 || srcH <= 0) break;
        int tw = srcW, th = srcH;
        fitTargetSize(srcW, srcH, maxSide, &tw, &th);
        if ((tw != srcW || th != srcH) && a.setTargetSize(dec, tw, th) != 0) {
            LOGE("setTargetSize %dx%d -> %dx%d refused", srcW, srcH, tw, th);
            break;
        }
        const size_t stride = a.minStride(dec);
        if (stride < (size_t)tw * 4) break;
        pixels->assign(stride * (size_t)th, 0);
        if (a.decode(dec, pixels->data(), stride, pixels->size()) != 0) {
            LOGE("decodeImage failed");
            break;
        }
        // Make the rows tightly packed (stride can be larger than width * 4).
        if (stride != (size_t)tw * 4) {
            for (int y = 1; y < th; ++y) {
                std::memmove(pixels->data() + (size_t)y * tw * 4, pixels->data() + (size_t)y * stride, (size_t)tw * 4);
            }
            pixels->resize((size_t)tw * th * 4);
        }
        *w = tw;
        *h = th;
        ok = true;
    } while (false);
    if (!ok) pixels->clear();
    return ok;
}
}  // namespace

bool decodePhotoRgba(int fd, int maxSide, std::vector<uint8_t>* pixels, int* w, int* h) {
    DecoderApi& a = api();
    if (!coreReady(a) || !a.createFromFd) return false;  // API < 30
    AImageDecoder* dec = nullptr;
    if (a.createFromFd(fd, &dec) != 0 || !dec) return false;
    const bool ok = decodeOpened(a, dec, maxSide, pixels, w, h);
    a.destroy(dec);
    return ok;
}

bool encodedDecoderAvailable() {
    DecoderApi& a = api();
    return coreReady(a) && a.createFromBuffer;
}

bool decodeEncodedRgba(const void* data, size_t len, std::vector<uint8_t>* pixels, int* w, int* h) {
    DecoderApi& a = api();
    if (!coreReady(a) || !a.createFromBuffer || !data || len == 0) return false;
    AImageDecoder* dec = nullptr;
    if (a.createFromBuffer(data, len, &dec) != 0 || !dec) return false;
    const bool ok = decodeOpened(a, dec, 0, pixels, w, h);
    a.destroy(dec);
    return ok;
}

}  // namespace am
