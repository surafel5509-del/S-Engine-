package com.sengine.engine.render.api

/**
 * Complete set of built-in shader sources for one GLSL dialect. Implemented once per backend
 * (`render/gles2`, `render/gles3`); the renderer picks exactly one set at runtime and never mixes them.
 *
 * User effects (`.glsl` assets) are written against a fixed ABI: they define
 * `vec4 effect(vec4 color, vec2 uv)` and may use `uTime`, `uParam`, `uTex`, `uUseTex`, `uColor`,
 * `uResolution` and `texture2D(...)`. Each set wraps that code in its own dialect.
 */
interface ShaderSourceSet {
    val dialect: ShaderDialect

    val spriteVs: String
    fun spriteFs(effect: String): String

    val meshVs: String
    fun meshFs(effect: String): String

    val postVs: String
    fun postFs(effect: String): String

    val lineVs: String
    val lineFs: String

    val skyVs: String
    val skyFs: String
}

/** Turns raw driver compile/link logs into messages that point at the user's own code. */
object ShaderDiagnostics {
    private val DRIVER_LINE = Regex("""0:(\d+)""")

    /**
     * Number of source lines that come before [effect] inside [template]. A driver error on line
     * `L` of the full source is line `L - offset` of the user's effect code. Null when [effect] is absent.
     */
    fun effectLineOffset(template: String, effect: String): Int? {
        val idx = template.indexOf(effect)
        if (idx < 0) return null
        var lines = 0
        for (i in 0 until idx) if (template[i] == '\n') lines++
        return lines
    }

    /**
     * Maps the first `0:<line>` reported by the driver to a line of [effect]. Only fragment-stage errors
     * (`log` starts with "fragment:") can point into the effect; null otherwise.
     */
    fun userLine(log: String, template: String, effect: String): Int? {
        if (!log.startsWith("fragment:")) return null
        val offset = effectLineOffset(template, effect) ?: return null
        val driverLine = DRIVER_LINE.find(log)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val line = driverLine - offset
        return if (line >= 1 && effect.lines().size >= line) line else null
    }

    /**
     * Full, actionable message for a failed built-in or user shader. [log] is the stage-tagged output of
     * GL.tryCompile ("vertex: ...", "fragment: ..." or "link: ..."); [template] is the complete source
     * that contained [effect].
     */
    fun describe(name: String, dialect: ShaderDialect, log: String, template: String, effect: String): String {
        val sb = StringBuilder()
        sb.append("Shader '").append(name).append("' (").append(dialect.name)
            .append(") failed: ").append(log.trim().ifBlank { "no driver message" })
        userLine(log, template, effect)?.let { sb.append(" [line ").append(it).append(" of the effect code]") }
        return sb.toString()
    }
}
