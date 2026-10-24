package com.sengine.render

import com.sengine.engine.render.api.ShaderDiagnostics
import com.sengine.engine.render.api.ShaderDialect
import com.sengine.engine.render.api.ShaderSourceSet
import com.sengine.engine.render.gles2.Gles2Shaders
import com.sengine.engine.render.gles3.Gles3Shaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static checks on the built-in GLSL. The Android GL driver is not available to unit tests, so these
 * verify the properties that would otherwise only show up as a black screen on a device:
 * dialect purity, matching varyings, and every attribute/uniform the Kotlin binding code relies on.
 */
class ShaderSourcesTest {

    private val effect = "vec4 effect(vec4 color, vec2 uv) { return color * 0.5; }"

    /** A named vertex/fragment pair plus the names the renderer binds to it. */
    private data class Program(
        val name: String,
        val vs: String,
        val fs: String,
        val attributes: List<String>,
        val vsUniforms: List<String>,
        val fsUniforms: List<String>,
    )

    private fun programs(set: ShaderSourceSet): List<Program> = listOf(
        Program(
            "sprite", set.spriteVs, set.spriteFs(effect),
            attributes = listOf("aPos"),
            vsUniforms = listOf("uMVP", "uUV"),
            fsUniforms = listOf("uColor", "uTex", "uUseTex", "uShape", "uAA", "uTime", "uParam", "uResolution"),
        ),
        Program(
            "mesh", set.meshVs, set.meshFs(effect),
            attributes = listOf("aPos", "aNormal", "aUV"),
            vsUniforms = listOf("uMVP", "uModel", "uNormalMat"),
            fsUniforms = listOf(
                "uColor", "uTex", "uUseTex", "uTiling", "uCamPos", "uAmbient", "uDirDir", "uDirColor",
                "uPointPos", "uPointColor", "uSpec", "uShine", "uEmission", "uUnlit", "uFogColor", "uFog",
                "uTime", "uParam", "uResolution",
            ),
        ),
        Program(
            "post", set.postVs, set.postFs(effect),
            attributes = listOf("aPos"),
            vsUniforms = emptyList(),
            fsUniforms = listOf("uTex", "uTime", "uParam", "uResolution"),
        ),
        Program(
            "line", set.lineVs, set.lineFs,
            attributes = listOf("aPos", "aColor"),
            vsUniforms = listOf("uMVP"),
            fsUniforms = emptyList(),
        ),
        Program(
            "sky", set.skyVs, set.skyFs,
            attributes = listOf("aPos"),
            vsUniforms = emptyList(),
            fsUniforms = listOf("uInvVP", "uTop", "uHorizon", "uGround"),
        ),
    )

    /** Declared attribute/uniform/varying names with their GLSL type, e.g. `in vec2 vUV` -> ("in", "vec2", "vUV"). */
    private fun declarations(src: String, keyword: String): Map<String, String> {
        val re = Regex("""(?m)^\s*$keyword\s+(\w+)\s+(\w+)""")
        return re.findAll(src).associate { it.groupValues[2] to it.groupValues[1] }
    }

    private fun uniformNames(src: String): Set<String> =
        Regex("""(?m)^\s*uniform\s+\w+\s+(\w+)""").findAll(src).map { it.groupValues[1] }.toSet()

    private fun attributeNames(src: String, keyword: String): Set<String> =
        declarations(src, keyword).keys

    // ------------------------------------------------------------------ dialect purity

    @Test
    fun es3SourcesStartWithTheDeclaredVersionLine() {
        for (p in programs(Gles3Shaders)) {
            assertTrue("${p.name} vs", p.vs.startsWith(ShaderDialect.GLSL_300_ES.versionLine + "\n"))
            assertTrue("${p.name} fs", p.fs.startsWith(ShaderDialect.GLSL_300_ES.versionLine + "\n"))
        }
        assertEquals(ShaderDialect.GLSL_300_ES, Gles3Shaders.dialect)
    }

    @Test
    fun es2SourcesHaveNoVersionDirectiveAndNoEs3Syntax() {
        assertEquals(ShaderDialect.GLSL_100_ES, Gles2Shaders.dialect)
        for (p in programs(Gles2Shaders)) {
            for (src in listOf(p.vs, p.fs)) {
                assertFalse("${p.name} must not contain #version", src.contains("#version"))
                assertFalse("${p.name} must not declare fragment outputs", Regex("""\bout\s+vec4\s+fragColor""").containsMatchIn(src))
            }
        }
    }

    @Test
    fun es3SourcesUseNoEs2OnlyKeywords() {
        for (p in programs(Gles3Shaders)) {
            for (src in listOf(p.vs, p.fs)) {
                assertFalse("${p.name}: attribute keyword", Regex("""\battribute\b""").containsMatchIn(src))
                assertFalse("${p.name}: varying keyword", Regex("""\bvarying\b""").containsMatchIn(src))
                assertFalse("${p.name}: gl_FragColor", src.contains("gl_FragColor"))
            }
        }
    }

    @Test
    fun es3FragmentShadersWriteAnExplicitOutput() {
        for (p in programs(Gles3Shaders)) {
            assertTrue("${p.name} declares fragColor", p.fs.contains("out vec4 fragColor;"))
            assertTrue("${p.name} writes fragColor", Regex("""\bfragColor\s*=""").containsMatchIn(p.fs))
        }
    }

    @Test
    fun es3TexturedFragmentShadersKeepTheTexture2DShim() {
        // User effects are written against texture2D(); ES 3 must provide it through the macro shim.
        for (p in programs(Gles3Shaders)) {
            if (p.fs.contains("texture2D(")) {
                assertTrue("${p.name} needs texture2D shim", p.fs.contains("#define texture2D(s, p) texture(s, p)"))
            }
        }
        // And the shim must come after #version and before the user effect code.
        val fs = Gles3Shaders.spriteFs(effect)
        assertTrue(fs.indexOf("#define texture2D") < fs.indexOf(effect))
    }

    // ------------------------------------------------------------------ effect ABI

    @Test
    fun userEffectIsInsertedVerbatimInBothDialects() {
        for (set in listOf(Gles2Shaders, Gles3Shaders)) {
            listOf(set.spriteFs(effect), set.meshFs(effect), set.postFs(effect)).forEach { fs ->
                assertEquals("${set.dialect} effect count", 1, fs.split(effect).size - 1)
            }
        }
    }

    @Test
    fun builtInFragmentShadersCallTheEffectEntryPoint() {
        for (set in listOf(Gles2Shaders, Gles3Shaders)) {
            listOf(set.spriteFs(effect), set.meshFs(effect), set.postFs(effect)).forEach { fs ->
                assertTrue("${set.dialect} calls effect()", Regex("""\beffect\(""").findAll(fs).count() >= 2)
            }
        }
    }

    // ------------------------------------------------------------------ binding lint

    @Test
    fun everyBoundAttributeIsDeclaredInTheVertexShader() {
        for (set in listOf(Gles2Shaders, Gles3Shaders)) {
            val keyword = if (set.dialect == ShaderDialect.GLSL_300_ES) "in" else "attribute"
            for (p in programs(set)) {
                val declared = attributeNames(p.vs, keyword)
                for (a in p.attributes) assertTrue("${set.dialect} ${p.name} attribute $a", a in declared)
            }
        }
    }

    @Test
    fun everyBoundUniformIsDeclaredInItsStage() {
        for (set in listOf(Gles2Shaders, Gles3Shaders)) {
            for (p in programs(set)) {
                val vsU = uniformNames(p.vs)
                val fsU = uniformNames(p.fs)
                for (u in p.vsUniforms) assertTrue("${set.dialect} ${p.name} vs uniform $u", u in vsU)
                for (u in p.fsUniforms) assertTrue("${set.dialect} ${p.name} fs uniform $u", u in fsU)
            }
        }
    }

    @Test
    fun varyingsMatchBetweenVertexAndFragmentStages() {
        for (set in listOf(Gles2Shaders, Gles3Shaders)) {
            val (outKw, inKw) = if (set.dialect == ShaderDialect.GLSL_300_ES) "out" to "in" else "varying" to "varying"
            for (p in programs(set)) {
                val outs = declarations(p.vs, outKw)
                val ins = declarations(p.fs, inKw)
                for ((name, type) in ins) {
                    assertEquals("${set.dialect} ${p.name} varying $name", type, outs[name])
                }
            }
        }
    }

    // ------------------------------------------------------------------ diagnostics

    @Test
    fun driverLineInsideEffectIsMappedToUserLine() {
        val multi = "vec4 effect(vec4 c, vec2 uv) {\n  float x = 1.0;\n  return c * x; }"
        // Two lines precede the effect, so driver line 3 is effect line 1 and driver line 4 is effect line 2.
        val template = "line1\nline2\n$multi\nmore"
        assertEquals(2, ShaderDiagnostics.effectLineOffset(template, multi))
        val fsLog = "fragment: ERROR: 0:4: 'x' : undeclared identifier"
        assertEquals(2, ShaderDiagnostics.userLine(fsLog, template, multi))
        val message = ShaderDiagnostics.describe("Hit.glsl", ShaderDialect.GLSL_300_ES, fsLog, template, multi)
        assertTrue(message, message.contains("[line 2 of the effect code]"))
        assertTrue(message, message.contains("GLSL_300_ES"))
    }

    @Test
    fun vertexAndLinkErrorsAreNotMappedIntoTheEffect() {
        val template = "a\nb\n$effect"
        assertNull(ShaderDiagnostics.userLine("vertex: ERROR: 0:3: bad", template, effect))
        assertNull(ShaderDiagnostics.userLine("link: error 0:3", template, effect))
    }

    @Test
    fun errorsOutsideTheEffectAreNotMapped() {
        val template = "a\n$effect"
        // Driver line 1 is before the effect block (offset 1), so it is not a user line.
        assertNull(ShaderDiagnostics.userLine("fragment: ERROR: 0:1: precision", template, effect))
    }
}
