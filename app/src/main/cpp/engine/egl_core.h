// EGL / GLES3 context management. Everything here must be used from ONE thread (the GL thread).
#pragma once
#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <cstdint>

struct ANativeWindow;

namespace am {

class EglCore {
public:
    ~EglCore() { release(); }

    bool init();
    void release();

    // Window surface for a SurfaceView / TextureView / MediaCodec input surface.
    EGLSurface createWindowSurface(ANativeWindow* window);
    EGLSurface createPbufferSurface(int w, int h);  // EGL_NO_SURFACE if the device config has no pbuffer
    void destroySurface(EGLSurface s);
    bool makeCurrent(EGLSurface s);
    // ptsNs >= 0 sets the presentation time (needed for encoder input surfaces).
    bool swap(EGLSurface s, int64_t ptsNs);
    int width(EGLSurface s) const;
    int height(EGLSurface s) const;

    EGLSurface bootstrapSurface() const { return boot_; }
    bool hasPbuffer() const { return pbuffer_; }

private:
    EGLDisplay dpy_ = EGL_NO_DISPLAY;
    EGLContext ctx_ = EGL_NO_CONTEXT;
    EGLConfig cfg_ = nullptr;
    EGLSurface boot_ = EGL_NO_SURFACE;
    bool pbuffer_ = false;
    PFNEGLPRESENTATIONTIMEANDROIDPROC presTime_ = nullptr;
};

}  // namespace am
