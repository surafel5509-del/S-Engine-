package com.sengine.engine.render.api

/**
 * Graphics backends S Engine can run on. OpenGL ES 3.0 is the primary backend; OpenGL ES 2.0 is
 * kept as a compatibility fallback for devices whose driver cannot create an ES 3 context.
 */
enum class GraphicsBackend(val label: String, val glesMajor: Int, val shaderDialect: ShaderDialect) {
    GLES30("OpenGL ES 3.0", 3, ShaderDialect.GLSL_300_ES),
    GLES20("OpenGL ES 2.0", 2, ShaderDialect.GLSL_100_ES),
}

/** GLSL dialects. Each backend uses exactly one dialect; sources of different dialects are never mixed. */
enum class ShaderDialect(val versionLine: String?) {
    /** GLSL ES 1.00 (implicit version, `attribute`/`varying`/`gl_FragColor`). */
    GLSL_100_ES(null),
    /** GLSL ES 3.00 (`#version 300 es`, `in`/`out`, explicit fragment output). */
    GLSL_300_ES("#version 300 es"),
}

/**
 * What the driver actually reported for the GL context that is current on the GL thread.
 * Unknown values are 0 (for example when a GL string is missing).
 */
data class GlCapabilities(
    val vendor: String,
    val renderer: String,
    val version: String,
    val shadingLanguageVersion: String,
    val glesMajor: Int,
    val glesMinor: Int,
    /** GLSL ES version as an integer, e.g. 100, 300 or 310. 0 when unknown. */
    val glslVersion: Int,
    val maxTextureSize: Int,
) {
    /** Single line for logs and the in-app console. */
    fun describe(selected: GraphicsBackend?): String =
        "GL backend: ${selected?.label ?: "NONE (unsupported)"} | vendor: ${vendor.ifBlank { "?" }} | " +
            "renderer: ${renderer.ifBlank { "?" }} | GL version: ${version.ifBlank { "?" }} | " +
            "GLSL ES: ${if (glslVersion > 0) glslVersion.toString() else "?"} | max texture: ${maxTextureSize}px"

    companion object {
        private val ES_VERSION = Regex("""OpenGL ES (\d+)\.(\d+)""")
        private val GLSL_VERSION = Regex("""GLSL ES (\d+)\.(\d+)""")

        /** Parses `GL_VERSION`, e.g. `OpenGL ES 3.2 V@0502.0 (GIT@...)`. Returns (0, 0) when not recognised. */
        fun parseEsVersion(version: String?): Pair<Int, Int> {
            val m = ES_VERSION.find(version.orEmpty()) ?: return 0 to 0
            return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
        }

        /** Parses `GL_SHADING_LANGUAGE_VERSION`, e.g. `OpenGL ES GLSL ES 3.00` -> 300. Returns 0 when unknown. */
        fun parseGlslVersion(shadingLanguageVersion: String?): Int {
            val m = GLSL_VERSION.find(shadingLanguageVersion.orEmpty()) ?: return 0
            val major = m.groupValues[1].toIntOrNull() ?: return 0
            val minor = m.groupValues[2].toIntOrNull() ?: return 0
            return major * 100 + minor
        }

        /** Builds capabilities from raw GL strings. Used by the GL thread and by unit tests. */
        fun fromStrings(
            vendor: String?,
            renderer: String?,
            version: String?,
            shadingLanguageVersion: String?,
            maxTextureSize: Int,
        ): GlCapabilities {
            val (major, minor) = parseEsVersion(version)
            return GlCapabilities(
                vendor = vendor.orEmpty(),
                renderer = renderer.orEmpty(),
                version = version.orEmpty(),
                shadingLanguageVersion = shadingLanguageVersion.orEmpty(),
                glesMajor = major,
                glesMinor = minor,
                glslVersion = parseGlslVersion(shadingLanguageVersion),
                maxTextureSize = maxTextureSize,
            )
        }
    }
}

object GraphicsBackendSelector {
    /**
     * Chooses the backend from the context that was really created.
     *  - ES 3.x context whose GLSL ES is 3.00+ (or not reported; ES 3 implies 3.00) -> [GraphicsBackend.GLES30]
     *  - ES 2.x context, or an ES 3 context that reports GLSL ES 1.00 -> [GraphicsBackend.GLES20]
     *  - anything else (missing/unknown GL version) -> null, meaning the renderer cannot start.
     */
    fun select(caps: GlCapabilities?): GraphicsBackend? {
        if (caps == null) return null
        return when {
            caps.glesMajor >= 3 && (caps.glslVersion == 0 || caps.glslVersion >= 300) -> GraphicsBackend.GLES30
            caps.glesMajor == 2 || (caps.glesMajor >= 3 && caps.glslVersion in 1..299) -> GraphicsBackend.GLES20
            else -> null
        }
    }
}

/** Raised when the renderer cannot start (no supported context, or a built-in shader failed to build). */
class GraphicsInitException(message: String) : RuntimeException(message)
