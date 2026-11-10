#include "egl_core.h"

#include <android/log.h>
#include <android/native_window.h>

#define TAG "AmEgl"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#endif
#ifndef EGL_RECORDABLE_ANDROID
#define EGL_RECORDABLE_ANDROID 0x3142
#endif

namespace am {

bool EglCore::init() {
    dpy_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy_ == EGL_NO_DISPLAY) return false;
    EGLint major = 0, minor = 0;
    if (!eglInitialize(dpy_, &major, &minor)) {
        LOGE("eglInitialize failed 0x%x", eglGetError());
        dpy_ = EGL_NO_DISPLAY;
        return false;
    }
    // Most useful config first: "recordable" (encoder input surfaces), with pbuffer (offscreen).
    struct Try { bool recordable, pbuffer; };
    const Try tries[] = {{true, true}, {true, false}, {false, true}, {false, false}};
    bool found = false;
    for (const Try& t : tries) {
        EGLint a[24];
        int i = 0;
        a[i++] = EGL_RENDERABLE_TYPE; a[i++] = EGL_OPENGL_ES3_BIT_KHR;
        a[i++] = EGL_SURFACE_TYPE;    a[i++] = EGL_WINDOW_BIT | (t.pbuffer ? EGL_PBUFFER_BIT : 0);
        a[i++] = EGL_RED_SIZE;        a[i++] = 8;
        a[i++] = EGL_GREEN_SIZE;      a[i++] = 8;
        a[i++] = EGL_BLUE_SIZE;       a[i++] = 8;
        a[i++] = EGL_ALPHA_SIZE;      a[i++] = 8;
        if (t.recordable) { a[i++] = EGL_RECORDABLE_ANDROID; a[i++] = 1; }
        a[i++] = EGL_NONE;
        EGLint n = 0;
        if (eglChooseConfig(dpy_, a, &cfg_, 1, &n) && n > 0) {
            pbuffer_ = t.pbuffer;
            LOGI("EGL config: recordable=%d pbuffer=%d", (int)t.recordable, (int)t.pbuffer);
            found = true;
            break;
        }
    }
    if (!found) {
        LOGE("no GLES3 EGL config");
        release();
        return false;
    }
    const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    ctx_ = eglCreateContext(dpy_, cfg_, EGL_NO_CONTEXT, ctxAttr);
    if (ctx_ == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed 0x%x", eglGetError());
        release();
        return false;
    }
    if (pbuffer_) boot_ = createPbufferSurface(1, 1);
    // Without a pbuffer the context is used "surfaceless" until a window surface is set.
    if (!makeCurrent(boot_)) {
        LOGE("makeCurrent(bootstrap) failed 0x%x", eglGetError());
        release();
        return false;
    }
    presTime_ = (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID");
    return true;
}

void EglCore::release() {
    if (dpy_ == EGL_NO_DISPLAY) return;
    eglMakeCurrent(dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (boot_ != EGL_NO_SURFACE) eglDestroySurface(dpy_, boot_);
    if (ctx_ != EGL_NO_CONTEXT) eglDestroyContext(dpy_, ctx_);
    eglTerminate(dpy_);
    dpy_ = EGL_NO_DISPLAY;
    ctx_ = EGL_NO_CONTEXT;
    boot_ = EGL_NO_SURFACE;
}

EGLSurface EglCore::createWindowSurface(ANativeWindow* window) {
    const EGLint attr[] = {EGL_NONE};
    EGLSurface s = eglCreateWindowSurface(dpy_, cfg_, (EGLNativeWindowType)window, attr);
    if (s == EGL_NO_SURFACE) LOGE("eglCreateWindowSurface failed 0x%x", eglGetError());
    return s;
}

EGLSurface EglCore::createPbufferSurface(int w, int h) {
    if (!pbuffer_) return EGL_NO_SURFACE;
    const EGLint attr[] = {EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE};
    EGLSurface s = eglCreatePbufferSurface(dpy_, cfg_, attr);
    if (s == EGL_NO_SURFACE) LOGE("eglCreatePbufferSurface failed 0x%x", eglGetError());
    return s;
}

void EglCore::destroySurface(EGLSurface s) {
    if (s != EGL_NO_SURFACE && s != boot_) eglDestroySurface(dpy_, s);
}

bool EglCore::makeCurrent(EGLSurface s) { return eglMakeCurrent(dpy_, s, s, ctx_) == EGL_TRUE; }

bool EglCore::swap(EGLSurface s, int64_t ptsNs) {
    if (ptsNs >= 0 && presTime_) presTime_(dpy_, s, (EGLnsecsANDROID)ptsNs);
    return eglSwapBuffers(dpy_, s) == EGL_TRUE;
}

int EglCore::width(EGLSurface s) const {
    EGLint v = 0;
    eglQuerySurface(dpy_, s, EGL_WIDTH, &v);
    return v;
}

int EglCore::height(EGLSurface s) const {
    EGLint v = 0;
    eglQuerySurface(dpy_, s, EGL_HEIGHT, &v);
    return v;
}

}  // namespace am
