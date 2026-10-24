package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Light2D
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Affine
import com.sengine.engine.math.Mat4
import com.sengine.engine.render.api.ShaderSourceSet
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 2D lighting: draws every enabled [Light2D] into an off-screen light map, then multiplies that map
 * over the finished 2D scene.
 *
 * Ambient lights are summed on the CPU and used as the clear colour, so unlit corners of a level are
 * never pitch black unless the designer wants that. Point, spot and directional lights are accumulated
 * additively in a single pass each, which keeps the cost proportional to the number of lights and works
 * on both the GLES 3 and GLES 2 backends (plain FBO + additive blending, no MRT).
 *
 * Camera cookies (a texture projected from the light centre) are supported for stained glass windows,
 * torch flicker patterns and projector beams.
 */
class Lighting2D {

    private var program = 0
    private var aPos = -1
    private var uViewProj = -1
    private var uCenter = -1
    private var uSize = -1
    private var uLightPos = -1
    private var uColor = -1
    private var uRadius = -1
    private var uInner = -1
    private var uFalloff = -1
    private var uSpotDir = -1
    private var uSpotCos = -1
    private var uSpotSoft = -1
    private var uKind = -1
    private var uUseTex = -1
    private var uTex = -1

    private var fbo = 0
    private var colorTex = 0
    private var width = 0
    private var height = 0
    /** Framebuffer that was bound when the light pass started (the post FX target, or 0). */
    private var targetFbo = 0
    private lateinit var quad: FloatBuffer
    private val viewProj = FloatArray(16)
    private val identity = FloatArray(16)
    private val compositeModel = FloatArray(16)
    private val temp = Affine()

    /** True after a successful [begin] and before [end]. */
    var active = false
        private set
    /** Number of lights submitted this frame (diagnostics). */
    var lightsDrawn = 0
        private set

    fun init(sources: ShaderSourceSet) {
        release()
        program = GL.compileOrThrow("light2d (${sources.dialect.name})", sources.lightVs, sources.lightFs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uViewProj = GLES20.glGetUniformLocation(program, "uViewProj")
        uCenter = GLES20.glGetUniformLocation(program, "uCenter")
        uSize = GLES20.glGetUniformLocation(program, "uSize")
        uLightPos = GLES20.glGetUniformLocation(program, "uLightPos")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uInner = GLES20.glGetUniformLocation(program, "uInner")
        uFalloff = GLES20.glGetUniformLocation(program, "uFalloff")
        uSpotDir = GLES20.glGetUniformLocation(program, "uSpotDir")
        uSpotCos = GLES20.glGetUniformLocation(program, "uSpotCos")
        uSpotSoft = GLES20.glGetUniformLocation(program, "uSpotSoft")
        uKind = GLES20.glGetUniformLocation(program, "uKind")
        uUseTex = GLES20.glGetUniformLocation(program, "uUseTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        quad = GL.floatBuffer(8)
        quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        Mat4.identity(identity)
        compositeModel[0] = 2f
        compositeModel[5] = 2f
        compositeModel[10] = 1f
        compositeModel[15] = 1f
    }

    fun release() {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (colorTex != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTex), 0)
        fbo = 0; colorTex = 0; width = 0; height = 0
        active = false
        lightsDrawn = 0
    }

    private fun ensure(w: Int, h: Int): Boolean {
        if (fbo != 0 && w == width && h == height) return true
        release()
        if (w <= 0 || h <= 0) return false
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); colorTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTex, 0)
        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!ok) { release(); return false }
        width = w; height = h
        return true
    }

    /** Ambient colour contributed by all enabled Ambient lights plus the scene ambient. */
    fun ambientColor(scene: Scene, base: Int): Int {
        var r = GL.r(base)
        var g = GL.g(base)
        var b = GL.b(base)
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val light = go.get<Light2D>() ?: continue
            if (light.kind != 0) continue
            r += GL.r(light.color) * light.intensity
            g += GL.g(light.color) * light.intensity
            b += GL.b(light.color) * light.intensity
        }
        return GL.color(minOf(1f, r), minOf(1f, g), minOf(1f, b), 1f)
    }

    /** Redirects rendering into the light map and clears it to [ambient]. */
    fun begin(w: Int, h: Int, ambient: Int): Boolean {
        lightsDrawn = 0
        if (!ensure(w, h)) { active = false; return false }
        val bound = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
        targetFbo = bound[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(GL.r(ambient), GL.g(ambient), GL.b(ambient), 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        active = true
        return true
    }

    /** Draws one light. [camera] supplies the world-to-clip matrix, [cookie] an optional texture. */
    fun drawLight(go: GameObject, camera: View2D, cookie: Tex?) {
        if (!active) return
        val light = go.get<Light2D>() ?: return
        if (light.kind == 0) return
        val w = go.computeWorld()
        val lx = w.tx
        val ly = w.ty
        camera.matrix(viewProj)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, viewProj, 0)
        GLES20.glUniform2f(uLightPos, lx, ly)
        val cr = GL.r(light.color) * light.intensity
        val cg = GL.g(light.color) * light.intensity
        val cb = GL.b(light.color) * light.intensity
        GLES20.glUniform4f(uColor, cr, cg, cb, 1f)
        GLES20.glUniform1f(uRadius, light.radius)
        GLES20.glUniform1f(uInner, light.innerRadius.coerceIn(0f, light.radius * 0.95f))
        GLES20.glUniform1f(uFalloff, light.falloff)
        GLES20.glUniform1f(uKind, light.kind.toFloat())

        var halfW: Float
        var halfH: Float
        var cx = lx
        var cy = ly
        when (light.kind) {
            3 -> {
                // Directional: one screen covering quad, constant contribution.
                cx = camera.cx
                cy = camera.cy
                halfW = camera.halfW + 0.5f
                halfH = camera.size + 0.5f
                GLES20.glUniform2f(uSpotDir, 0f, 1f)
                GLES20.glUniform1f(uSpotCos, -2f)
                GLES20.glUniform1f(uSpotSoft, 1f)
            }
            2 -> {
                val a = Math.toRadians(light.direction.toDouble())
                val dirX = cos(a).toFloat()
                val dirY = sin(a).toFloat()
                GLES20.glUniform2f(uSpotDir, dirX, dirY)
                val half = Math.toRadians(light.spotAngle.coerceIn(1f, 360f) / 2.0).toFloat()
                GLES20.glUniform1f(uSpotCos, cos(half))
                GLES20.glUniform1f(uSpotSoft, (0.25f + (180f - light.spotAngle.coerceIn(1f, 360f)) / 720f).coerceIn(0.05f, 1f))
                halfW = light.radius + 0.05f
                halfH = light.radius + 0.05f
            }
            else -> {
                GLES20.glUniform2f(uSpotDir, 0f, 1f)
                GLES20.glUniform1f(uSpotCos, -2f)
                GLES20.glUniform1f(uSpotSoft, 1f)
                halfW = light.radius + 0.05f
                halfH = light.radius + 0.05f
            }
        }
        GLES20.glUniform2f(uCenter, cx, cy)
        GLES20.glUniform2f(uSize, halfW, halfH)

        if (cookie != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cookie.id)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform1f(uUseTex, 1f)
        } else {
            GLES20.glUniform1f(uUseTex, 0f)
        }

        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        lightsDrawn++
    }

    /**
     * Composite pass: multiplies the light map over whatever is already in the framebuffer.
     * The caller must restore the framebuffer viewport afterwards.
     */
    fun end(renderer: Renderer2D, screenW: Int, screenH: Int): Int {
        if (!active) return 0
        active = false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo)
        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ZERO)
        renderer.begin(identity)
        renderer.quadModel(compositeModel, 0xFFFFFFFF.toInt(), 0, Tex(colorTex, width, height), 1f)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        return 1
    }
}
