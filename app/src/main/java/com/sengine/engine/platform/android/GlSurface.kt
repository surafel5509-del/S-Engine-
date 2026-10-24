package com.sengine.engine.platform.android

import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.sengine.engine.render.api.GraphicsBackend
import com.sengine.engine.render.api.GlCapabilities
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

/**
 * EGL_OPENGL_ES3_BIT (EGL_KHR_create_context, value 0x40). Android exposes it only through EGLExt, and
 * EGL14 does not define it, so the value is declared here.
 */
private const val EGL_OPENGL_ES3_BIT = 0x00000040

/**
 * EGL setup for [GLSurfaceView]. OpenGL ES 3.0 is requested first; when the driver has no ES 3 config
 * or refuses to create an ES 3 context, an OpenGL ES 2.0 context is created instead. The backend that
 * is actually used is decided later on the GL thread from the GL strings (see [GlInfo]), so a driver
 * that silently reports ES 2 is handled as ES 2.
 *
 * Install with [install] before `setRenderer()`. This replaces `setEGLContextClientVersion(2)`.
 */
object GlSurface {
    const val TAG = "SEngine.GL"

    fun install(view: GLSurfaceView, log: (String) -> Unit) {
        view.setEGLConfigChooser(Es3FirstConfigChooser(log))
        view.setEGLContextFactory(Es3FirstContextFactory(log))
    }

    /** Renderable-type mask (EGL_RENDERABLE_TYPE) of [config]; 0 when it cannot be read. */
    internal fun renderableTypes(egl: EGL10, display: EGLDisplay, config: EGLConfig): Int {
        val value = IntArray(1)
        return if (egl.eglGetConfigAttrib(display, config, EGL10.EGL_RENDERABLE_TYPE, value)) value[0] else 0
    }

    internal fun errorName(code: Int) = "0x" + Integer.toHexString(code)
}

/**
 * Chooses an RGB888 + 16-bit depth window config that can host ES 3 contexts when one exists,
 * otherwise an ES 2 config. Falls back to RGB565 when no RGB888 config is available.
 */
private class Es3FirstConfigChooser(private val log: (String) -> Unit) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (channel in intArrayOf(8, 5)) {
            val attribs = intArrayOf(
                EGL10.EGL_RED_SIZE, channel,
                EGL10.EGL_GREEN_SIZE, if (channel == 8) 8 else 6,
                EGL10.EGL_BLUE_SIZE, channel,
                EGL10.EGL_ALPHA_SIZE, 0,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_STENCIL_SIZE, 0,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
                EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or EGL_OPENGL_ES3_BIT,
                EGL10.EGL_NONE,
            )
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, attribs, null, 0, count) || count[0] <= 0) continue
            val configs = arrayOfNulls<EGLConfig>(count[0])
            egl.eglChooseConfig(display, attribs, configs, configs.size, count)
            val usable = configs.filterNotNull()
            if (usable.isEmpty()) continue
            val es3 = usable.firstOrNull { (GlSurface.renderableTypes(egl, display, it) and EGL_OPENGL_ES3_BIT) != 0 }
            log("EGL config: RGB$channel + depth16, ${usable.size} candidates, ES3-capable config: ${es3 != null}")
            return es3 ?: usable.first()
        }
        throw IllegalStateException(
            "No EGL window config (RGB565/RGB888 with 16-bit depth) is available. EGL error " +
                GlSurface.errorName(egl.eglGetError()) + ". S Engine needs OpenGL ES 2.0 or 3.0.",
        )
    }
}

/** Creates an ES 3 context when the config allows it, falling back to ES 2 if creation fails. */
private class Es3FirstContextFactory(private val log: (String) -> Unit) : GLSurfaceView.EGLContextFactory {
    override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
        val es3Config = (GlSurface.renderableTypes(egl, display, config) and EGL_OPENGL_ES3_BIT) != 0
        val versions = if (es3Config) intArrayOf(3, 2) else intArrayOf(2)
        for (version in versions) {
            val attribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL10.EGL_NONE)
            val context = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs)
            if (context != null && context != EGL10.EGL_NO_CONTEXT) {
                log("EGL context created: OpenGL ES $version.x requested")
                return context
            }
            log("eglCreateContext(OpenGL ES $version) failed: EGL error ${GlSurface.errorName(egl.eglGetError())}")
        }
        throw IllegalStateException(
            "Could not create an OpenGL ES 3.0 or 2.0 context on this device. " +
                "Check the GPU driver. S Engine needs OpenGL ES 2.0 at minimum.",
        )
    }

    override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
        if (!egl.eglDestroyContext(display, context)) {
            log("eglDestroyContext failed: EGL error ${GlSurface.errorName(egl.eglGetError())}")
        }
    }
}

/** Reads what the driver reports for the GL context current on this thread. Requires a current context. */
object GlInfo {
    fun query(): GlCapabilities {
        val maxTexture = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
        return GlCapabilities.fromStrings(
            vendor = GLES20.glGetString(GLES20.GL_VENDOR),
            renderer = GLES20.glGetString(GLES20.GL_RENDERER),
            version = GLES20.glGetString(GLES20.GL_VERSION),
            shadingLanguageVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION),
            maxTextureSize = maxTexture[0],
        )
    }
}

/**
 * Process-wide graphics status for the UI (written on the GL thread, read on the UI thread).
 * [backend] is null until a context is up; [error] is set when the renderer could not start.
 */
object GraphicsStatus {
    @Volatile var backend: GraphicsBackend? = null
        private set
    @Volatile var description: String = ""
        private set
    @Volatile var error: String? = null
        private set

    fun succeeded(selected: GraphicsBackend, description: String) {
        backend = selected; this.description = description; error = null
    }

    fun failed(message: String, description: String = "") {
        backend = null; this.description = description; error = message
    }

    /** Short label for overlays, e.g. "GLES 3.0" or "GRAPHICS ERROR". */
    fun label(): String = when {
        error != null -> "GRAPHICS ERROR"
        backend == GraphicsBackend.GLES30 -> "GLES 3.0"
        backend == GraphicsBackend.GLES20 -> "GLES 2.0 (fallback)"
        else -> "starting…"
    }
}
