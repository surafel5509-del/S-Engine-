package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.render.api.GraphicsInitException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small GL helpers shared by the renderers. Every method must be called on the GL thread. */
object GL {
    /**
     * Compiles and links. Returns (program id, null) on success, or (0, "<stage>: <driver log>") on failure
     * where stage is "vertex", "fragment" or "link". Never returns a program id for a failed link.
     */
    fun tryCompile(vs: String, fs: String): Pair<Int, String?> {
        val st = IntArray(1)
        val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(v, vs); GLES20.glCompileShader(v)
        GLES20.glGetShaderiv(v, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val e = GLES20.glGetShaderInfoLog(v)
            GLES20.glDeleteShader(v)
            return 0 to "vertex: $e"
        }
        val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(f, fs); GLES20.glCompileShader(f)
        GLES20.glGetShaderiv(f, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) {
            val e = GLES20.glGetShaderInfoLog(f)
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            return 0 to "fragment: $e"
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, st, 0)
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        if (st[0] == 0) {
            val e = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            return 0 to "link: $e"
        }
        return p to null
    }

    /** Built-in programs must always build. Throws [GraphicsInitException] with the driver log otherwise. */
    fun compileOrThrow(name: String, vs: String, fs: String): Int {
        val (program, err) = tryCompile(vs, fs)
        if (err != null) throw GraphicsInitException("Built-in shader '$name' failed: ${err.trim()}")
        return program
    }

    /** Returns the GL error codes raised since the last call (empty when none). Used for init diagnostics. */
    fun drainErrors(): List<Int> {
        val out = ArrayList<Int>(2)
        while (out.size < 8) {
            val e = GLES20.glGetError()
            if (e == GLES20.GL_NO_ERROR) break
            out.add(e)
        }
        return out
    }

    fun floatBuffer(n: Int): FloatBuffer =
        ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    fun r(c: Int) = ((c shr 16) and 0xFF) / 255f
    fun g(c: Int) = ((c shr 8) and 0xFF) / 255f
    fun b(c: Int) = (c and 0xFF) / 255f
    fun a(c: Int) = ((c ushr 24) and 0xFF) / 255f

    /** Packs normalised 0..1 channel values into an ARGB int. */
    fun color(r: Float, g: Float, b: Float, a: Float = 1f): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val ai = (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
