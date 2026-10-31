package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.render.api.ShaderDiagnostics
import com.sengine.engine.render.api.ShaderSourceSet
import com.sengine.project.Project

/** Sprite program (built-in or user `effect()` shader). */
class SpriteProgram(val id: Int) {
    val aPos = GLES20.glGetAttribLocation(id, "aPos")
    val uMVP = GLES20.glGetUniformLocation(id, "uMVP")
    val uColor = GLES20.glGetUniformLocation(id, "uColor")
    val uTex = GLES20.glGetUniformLocation(id, "uTex")
    val uUseTex = GLES20.glGetUniformLocation(id, "uUseTex")
    val uShape = GLES20.glGetUniformLocation(id, "uShape")
    val uAA = GLES20.glGetUniformLocation(id, "uAA")
    val uUV = GLES20.glGetUniformLocation(id, "uUV")
    val uTime = GLES20.glGetUniformLocation(id, "uTime")
    val uParam = GLES20.glGetUniformLocation(id, "uParam")
    val uResolution = GLES20.glGetUniformLocation(id, "uResolution")
}

/** Lit 3D mesh program. */
class MeshProgram(val id: Int) {
    val aPos = GLES20.glGetAttribLocation(id, "aPos")
    val aNormal = GLES20.glGetAttribLocation(id, "aNormal")
    val aUV = GLES20.glGetAttribLocation(id, "aUV")
    val aTangent = GLES20.glGetAttribLocation(id, "aTangent")
    val aJoints = GLES20.glGetAttribLocation(id, "aJoints")
    val aWeights = GLES20.glGetAttribLocation(id, "aWeights")
    val uBones = GLES20.glGetUniformLocation(id, "uBones[0]")
    val uPBR = GLES20.glGetUniformLocation(id, "uPBR")
    val uMetallic = GLES20.glGetUniformLocation(id, "uMetallic")
    val uRoughness = GLES20.glGetUniformLocation(id, "uRoughness")
    val uNormalStrength = GLES20.glGetUniformLocation(id, "uNormalStrength")
    val uAOStrength = GLES20.glGetUniformLocation(id, "uAOStrength")
    val uAlphaMode = GLES20.glGetUniformLocation(id, "uAlphaMode")
    val uAlphaCutoff = GLES20.glGetUniformLocation(id, "uAlphaCutoff")
    val uUVTransform = GLES20.glGetUniformLocation(id, "uUVTransform")
    val uEmissiveColor = GLES20.glGetUniformLocation(id, "uEmissiveColor")
    val uEmissiveStrength = GLES20.glGetUniformLocation(id, "uEmissiveStrength")
    val uNormalMap = GLES20.glGetUniformLocation(id, "uNormalMap")
    val uMRMap = GLES20.glGetUniformLocation(id, "uMRMap")
    val uAOMap = GLES20.glGetUniformLocation(id, "uAOMap")
    val uEmissiveMap = GLES20.glGetUniformLocation(id, "uEmissiveMap")
    val uMapFlags = GLES20.glGetUniformLocation(id, "uMapFlags")
    val uMVP = GLES20.glGetUniformLocation(id, "uMVP")
    val uModel = GLES20.glGetUniformLocation(id, "uModel")
    val uNormalMat = GLES20.glGetUniformLocation(id, "uNormalMat")
    val uColor = GLES20.glGetUniformLocation(id, "uColor")
    val uTex = GLES20.glGetUniformLocation(id, "uTex")
    val uUseTex = GLES20.glGetUniformLocation(id, "uUseTex")
    val uTiling = GLES20.glGetUniformLocation(id, "uTiling")
    val uCamPos = GLES20.glGetUniformLocation(id, "uCamPos")
    val uAmbient = GLES20.glGetUniformLocation(id, "uAmbient")
    val uDirDir = GLES20.glGetUniformLocation(id, "uDirDir")
    val uDirColor = GLES20.glGetUniformLocation(id, "uDirColor")
    val uPointPos = GLES20.glGetUniformLocation(id, "uPointPos")
    val uPointColor = GLES20.glGetUniformLocation(id, "uPointColor")
    val uSpec = GLES20.glGetUniformLocation(id, "uSpec")
    val uShine = GLES20.glGetUniformLocation(id, "uShine")
    val uEmission = GLES20.glGetUniformLocation(id, "uEmission")
    val uUnlit = GLES20.glGetUniformLocation(id, "uUnlit")
    val uFogColor = GLES20.glGetUniformLocation(id, "uFogColor")
    val uFog = GLES20.glGetUniformLocation(id, "uFog")
    val uTime = GLES20.glGetUniformLocation(id, "uTime")
    val uParam = GLES20.glGetUniformLocation(id, "uParam")
    val uResolution = GLES20.glGetUniformLocation(id, "uResolution")
}

/** Full-screen post-processing program. */
class PostProgram(val id: Int) {
    val aPos = GLES20.glGetAttribLocation(id, "aPos")
    val uTex = GLES20.glGetUniformLocation(id, "uTex")
    val uTime = GLES20.glGetUniformLocation(id, "uTime")
    val uParam = GLES20.glGetUniformLocation(id, "uParam")
    val uResolution = GLES20.glGetUniformLocation(id, "uResolution")
}

/**
 * Compiles and caches GPU programs for one GLSL dialect ([ShaderSourceSet]). User shaders (`.glsl` assets)
 * define `vec4 effect(vec4 color, vec2 uv)` with uTime, uParam, uTex and uResolution available.
 * Call [init] after the GL context is created (and again after every context loss). GL thread only.
 */
class ShaderLibrary(private val project: Project, private val log: (String) -> Unit) {
    private val sprites = HashMap<String, Pair<Long, SpriteProgram?>>()
    private val meshes = HashMap<String, Pair<Long, MeshProgram?>>()
    private val skins = HashMap<String, Pair<Long, MeshProgram?>>()
    private val posts = HashMap<String, Pair<Long, PostProgram?>>()
    private var sources: ShaderSourceSet? = null
    lateinit var defaultSprite: SpriteProgram
    lateinit var defaultMesh: MeshProgram
    var skinMesh: MeshProgram? = null
    val supportsGpuSkin get() = sources?.supportsGpuSkin == true

    /** The dialect in use, or null before [init]. */
    val dialect get() = sources?.dialect

    /** Builds the built-in programs for [set]. Throws [GraphicsInitException] if they cannot be built. */
    fun init(set: ShaderSourceSet) {
        sprites.clear(); meshes.clear(); skins.clear(); posts.clear()
        sources = null
        val spriteFs = set.spriteFs(DEFAULT_EFFECT)
        defaultSprite = SpriteProgram(GL.compileOrThrow("sprite (${set.dialect.name})", set.spriteVs, spriteFs))
        val meshFs = set.meshFs(DEFAULT_EFFECT)
        defaultMesh = MeshProgram(GL.compileOrThrow("mesh (${set.dialect.name})", set.meshVs, meshFs))
        skinMesh = if(set.supportsGpuSkin) {
            val (id,error) = GL.tryCompile(set.skinnedMeshVs,meshFs)
            if(error!=null) { log("GPU skin unavailable; using CPU skinning: $error"); null } else MeshProgram(id)
        } else null
        sources = set
    }

    private fun source(name: String): Pair<Long, String>? {
        val f = project.assetFile(name)
        if (!f.exists()) return null
        return f.lastModified() to f.readText()
    }

    fun sprite(name: String): SpriteProgram? {
        if (name.isBlank()) return null
        val (stamp, src) = source(name) ?: return null
        sprites[name]?.let { if (it.first == stamp) return it.second }
        val set = sources ?: return null
        val fs = set.spriteFs(src)
        val (id, err) = GL.tryCompile(set.spriteVs, fs)
        if (err != null) log(ShaderDiagnostics.describe(name, set.dialect, err, fs, src))
        val p = if (id != 0) SpriteProgram(id) else null
        sprites[name] = stamp to p
        return p
    }

    fun mesh(name: String, skinned: Boolean = false): MeshProgram? {
        if (name.isBlank()) return null
        val (stamp, src) = source(name) ?: return null
        val cache = if(skinned) skins else meshes
        cache[name]?.let { if (it.first == stamp) return it.second }
        val set = sources ?: return null
        val fs = set.meshFs(src)
        val (id, err) = GL.tryCompile(if(skinned) set.skinnedMeshVs else set.meshVs, fs)
        if (err != null) log(ShaderDiagnostics.describe(name, set.dialect, err, fs, src))
        val p = if (id != 0) MeshProgram(id) else null
        cache[name] = stamp to p
        return p
    }

    /** Built-in effect index (1..8) or custom asset. */
    fun post(builtin: Int, custom: String): PostProgram? {
        val set = sources ?: return null
        val key: String
        val src: String
        val stamp: Long
        if (builtin in 1 until POST_EFFECTS.size) {
            key = "#$builtin"; src = POST_EFFECTS[builtin]; stamp = 0L
        } else {
            val s = source(custom) ?: return null
            key = custom; src = s.second; stamp = s.first
        }
        posts[key]?.let { if (it.first == stamp) return it.second }
        val fs = set.postFs(src)
        val (id, err) = GL.tryCompile(set.postVs, fs)
        if (err != null) log(ShaderDiagnostics.describe("post $key", set.dialect, err, fs, src))
        val p = if (id != 0) PostProgram(id) else null
        posts[key] = stamp to p
        return p
    }

    companion object {
        const val DEFAULT_EFFECT = "vec4 effect(vec4 color, vec2 uv) { return color; }"

        /** Index matches ComponentRegistry.POST_FX. uParam = intensity. */
        val POST_EFFECTS = listOf(
            DEFAULT_EFFECT,
            // Grayscale
            "vec4 effect(vec4 c, vec2 uv) { float g = dot(c.rgb, vec3(0.299, 0.587, 0.114)); return vec4(mix(c.rgb, vec3(g), clamp(uParam, 0.0, 1.0)), 1.0); }",
            // Sepia
            "vec4 effect(vec4 c, vec2 uv) { vec3 s = vec3(dot(c.rgb, vec3(0.393, 0.769, 0.189)), dot(c.rgb, vec3(0.349, 0.686, 0.168)), dot(c.rgb, vec3(0.272, 0.534, 0.131))); return vec4(mix(c.rgb, s, clamp(uParam, 0.0, 1.0)), 1.0); }",
            // Vignette
            "vec4 effect(vec4 c, vec2 uv) { float d = distance(uv, vec2(0.5)); float v = smoothstep(0.8, 0.25, d * (0.6 + uParam * 0.6)); return vec4(c.rgb * v, 1.0); }",
            // CRT
            """vec4 effect(vec4 c, vec2 uv) {
  vec2 q = uv - 0.5; q *= 1.0 + dot(q, q) * 0.25 * uParam; vec2 w = q + 0.5;
  if (w.x < 0.0 || w.x > 1.0 || w.y < 0.0 || w.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
  vec3 col = texture2D(uTex, w).rgb;
  float scan = 0.85 + 0.15 * sin(w.y * uResolution.y * 3.14159);
  float vig = smoothstep(0.75, 0.3, length(q));
  return vec4(col * scan * vig * 1.15, 1.0);
}""",
            // Pixelate
            "vec4 effect(vec4 c, vec2 uv) { float px = max(2.0, 4.0 * uParam) ; vec2 g = uResolution / px; vec2 p = (floor(uv * g) + 0.5) / g; return vec4(texture2D(uTex, p).rgb, 1.0); }",
            // Bloom
            """vec4 effect(vec4 c, vec2 uv) {
  vec2 px = 2.5 / uResolution; vec3 acc = vec3(0.0);
  for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) {
    vec3 s = texture2D(uTex, uv + vec2(float(x), float(y)) * px).rgb;
    acc += max(s - 0.6, 0.0);
  }
  return vec4(c.rgb + acc / 25.0 * 3.0 * uParam, 1.0);
}""",
            // Invert
            "vec4 effect(vec4 c, vec2 uv) { return vec4(mix(c.rgb, 1.0 - c.rgb, clamp(uParam, 0.0, 1.0)), 1.0); }",
            // Chromatic aberration
            "vec4 effect(vec4 c, vec2 uv) { vec2 o = (uv - 0.5) * 0.012 * uParam; return vec4(texture2D(uTex, uv + o).r, c.g, texture2D(uTex, uv - o).b, 1.0); }",
        )
    }
}
