package com.sengine

import com.sengine.engine.render.api.ShaderSourceSet
import com.sengine.engine.render.gles2.Gles2Shaders
import com.sengine.engine.render.gles3.Gles3Shaders
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Optional offline compiler gate. CI installs glslang and sets SENGINE_REQUIRE_GLSLANG=1. */
class ShaderValidationTest {
    @Test fun everyBuiltinShaderCompilesAndEveryStagePairLinks() {
        val executable=System.getenv("GLSLANG_VALIDATOR") ?: listOf("/usr/bin/glslangValidator","/usr/local/bin/glslangValidator").firstOrNull{File(it).canExecute()}
        if(System.getenv("SENGINE_REQUIRE_GLSLANG")=="1")assertNotNull("CI requires glslangValidator; install glslang-tools",executable)
        assumeTrue("Set GLSLANG_VALIDATOR to run offline GLSL validation",executable!=null)
        if(executable==null)return
        val dir=Files.createTempDirectory("sengine-glsl").toFile()
        try {
            for((label,sources) in listOf("es2" to Gles2Shaders,"es3" to Gles3Shaders)) validate(executable,dir,label,sources)
        } finally {dir.deleteRecursively()}
    }
    private fun validate(executable:String,dir:File,label:String,s:ShaderSourceSet) {
        val effect="vec4 effect(vec4 c,vec2 uv){return c;}"
        val pairs=listOf("sprite" to (s.spriteVs to s.spriteFs(effect)),"mesh" to (s.meshVs to s.meshFs(effect)),
            "post" to (s.postVs to s.postFs(effect)),"line" to (s.lineVs to s.lineFs),"sky" to (s.skyVs to s.skyFs),"light" to (s.lightVs to s.lightFs))
        for((name,pair) in pairs) check(executable,dir,"$label-$name",pair)
        if(s.supportsGpuSkin)check(executable,dir,"$label-skinned",s.skinnedMeshVs to s.meshFs(effect))
    }
    private fun check(executable:String,dir:File,name:String,pair:Pair<String,String>) {
        val vs=File(dir,"$name.vert");val fs=File(dir,"$name.frag");vs.writeText(pair.first);fs.writeText(pair.second)
        val process=ProcessBuilder(executable,"-l",vs.path,fs.path).redirectErrorStream(true).start()
        val output=process.inputStream.bufferedReader().readText()
        assertTrue("$name validator timed out",process.waitFor(20,TimeUnit.SECONDS))
        assertEquals("$name\n$output",0,process.exitValue())
    }
}
