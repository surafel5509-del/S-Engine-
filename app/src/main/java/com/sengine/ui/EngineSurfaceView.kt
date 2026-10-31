package com.sengine.ui

import android.content.Context
import android.opengl.GLSurfaceView
import com.sengine.engine.render.SceneRenderer

/** Releases thread-bound script state only after GLSurfaceView has joined its render thread. */
class EngineSurfaceView(context: Context, private val sceneRenderer: SceneRenderer) : GLSurfaceView(context) {
    init { setEGLContextClientVersion(2); setRenderer(sceneRenderer) }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sceneRenderer.onRenderThreadStopped()
    }
}
