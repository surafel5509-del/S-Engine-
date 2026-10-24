package com.sengine.render

import com.sengine.engine.render.api.GraphicsBackend
import com.sengine.engine.render.api.GraphicsBackendSelector
import com.sengine.engine.render.api.GlCapabilities
import com.sengine.engine.render.api.ShaderDialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsApiTest {

    private fun caps(version: String?, glsl: String?) =
        GlCapabilities.fromStrings("Vendor", "Renderer", version, glsl, maxTextureSize = 4096)

    @Test
    fun parsesEsVersionFromGlVersionString() {
        assertEquals(3 to 2, GlCapabilities.parseEsVersion("OpenGL ES 3.2 V@0502.0 (GIT@abc) (Date:01/02/25)"))
        assertEquals(3 to 0, GlCapabilities.parseEsVersion("OpenGL ES 3.0 Mali-T830 v1.r12p0"))
        assertEquals(2 to 0, GlCapabilities.parseEsVersion("OpenGL ES 2.0 Adreno (TM) 506"))
        assertEquals(0 to 0, GlCapabilities.parseEsVersion("something else"))
        assertEquals(0 to 0, GlCapabilities.parseEsVersion(null))
    }

    @Test
    fun parsesGlslVersionFromShadingLanguageString() {
        assertEquals(300, GlCapabilities.parseGlslVersion("OpenGL ES GLSL ES 3.00"))
        assertEquals(310, GlCapabilities.parseGlslVersion("OpenGL ES GLSL ES 3.10 (Build 1234)"))
        assertEquals(100, GlCapabilities.parseGlslVersion("OpenGL ES GLSL ES 1.00"))
        assertEquals(0, GlCapabilities.parseGlslVersion("unknown"))
        assertEquals(0, GlCapabilities.parseGlslVersion(null))
    }

    @Test
    fun selectsEs3WhenTheContextReportsEs3() {
        val c = caps("OpenGL ES 3.2 V@0502.0", "OpenGL ES GLSL ES 3.20")
        assertEquals(GraphicsBackend.GLES30, GraphicsBackendSelector.select(c))
    }

    @Test
    fun es3ContextWithoutGlslStringStillSelectsEs3() {
        // An ES 3 context implies GLSL ES 3.00; a missing GLSL string must not force the fallback.
        assertEquals(GraphicsBackend.GLES30, GraphicsBackendSelector.select(caps("OpenGL ES 3.0", null)))
    }

    @Test
    fun fallsBackToEs2WhenTheContextIsEs2() {
        val c = caps("OpenGL ES 2.0 Adreno", "OpenGL ES GLSL ES 1.00")
        assertEquals(GraphicsBackend.GLES20, GraphicsBackendSelector.select(c))
    }

    @Test
    fun es3ContextReportingGlsl100UsesEs2Shaders() {
        // Inconsistent driver report: use the dialect the driver actually compiles.
        assertEquals(GraphicsBackend.GLES20, GraphicsBackendSelector.select(caps("OpenGL ES 3.0", "OpenGL ES GLSL ES 1.00")))
    }

    @Test
    fun unknownOrMissingVersionCannotStart() {
        assertNull(GraphicsBackendSelector.select(null))
        assertNull(GraphicsBackendSelector.select(caps(null, null)))
        assertNull(GraphicsBackendSelector.select(caps("OpenGL ES 1.1", "")))
    }

    @Test
    fun backendsMapToSeparateDialects() {
        assertEquals(ShaderDialect.GLSL_300_ES, GraphicsBackend.GLES30.shaderDialect)
        assertEquals(ShaderDialect.GLSL_100_ES, GraphicsBackend.GLES20.shaderDialect)
        assertEquals("#version 300 es", ShaderDialect.GLSL_300_ES.versionLine)
        assertNull(ShaderDialect.GLSL_100_ES.versionLine)
    }

    @Test
    fun describeReportsDriverDetailsAndSelectedBackend() {
        val c = GlCapabilities.fromStrings("ARM", "Mali-T830", "OpenGL ES 3.0 v1", "OpenGL ES GLSL ES 3.00", 8192)
        val line = c.describe(GraphicsBackendSelector.select(c))
        assertTrue(line, line.contains("OpenGL ES 3.0"))
        assertTrue(line, line.contains("vendor: ARM"))
        assertTrue(line, line.contains("renderer: Mali-T830"))
        assertTrue(line, line.contains("GLSL ES: 300"))
        assertTrue(line, line.contains("8192px"))
        assertTrue(c.describe(null), c.describe(null).contains("NONE"))
    }
}
