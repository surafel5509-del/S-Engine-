package com.sengine.fixtures

import com.sengine.engine.core.Animator
import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Collider3D
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Light
import com.sengine.engine.core.MeshRenderer
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Rigidbody3D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.project.Project
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Phase 0 fixtures: a real project folder (`src/test/resources/fixtures/project`) in the current
 * on-disk format. These tests pin what the loader reads, what the saver writes, and that a load/save
 * cycle loses nothing. They are read-only: the fixture folder is never written to.
 */
class SceneFixtureTest {

    private val projectDir: File = File(
        javaClass.classLoader!!.getResource("fixtures/project/project.json")!!.toURI(),
    ).parentFile

    private fun project() = Project(projectDir)

    private fun Scene.byName(name: String): GameObject = objects.first { it.name == name }

    private inline fun <reified T : Component> GameObject.comp(): T =
        components.filterIsInstance<T>().single()

    private fun fixtureJson(scene: String): JSONObject =
        JSONObject(File(projectDir, "scenes/$scene.scene.json").readText())

    /**
     * Structural JSON equality: same keys, same array lengths, same values. Numbers are compared with a
     * tolerance because the saver widens floats to doubles (0.4f is written as 0.4000000059604645).
     */
    private fun assertJsonClose(expected: Any?, actual: Any?, path: String) {
        when {
            expected is JSONObject && actual is JSONObject -> {
                assertEquals("keys at $path", expected.keySet(), actual.keySet())
                for (k in expected.keySet()) assertJsonClose(expected.get(k), actual.get(k), "$path.$k")
            }
            expected is JSONArray && actual is JSONArray -> {
                assertEquals("length at $path", expected.length(), actual.length())
                for (i in 0 until expected.length()) assertJsonClose(expected.get(i), actual.get(i), "$path[$i]")
            }
            expected is Number && actual is Number -> {
                val a = expected.toDouble()
                val b = actual.toDouble()
                assertTrue("$path: expected $a but was $b", abs(a - b) <= 1e-5 * maxOf(1.0, abs(a)))
            }
            else -> assertEquals("value at $path", expected, actual)
        }
    }

    // ------------------------------------------------------------------ project metadata

    @Test
    fun projectMetadataIsReadAsWritten() {
        val p = project()
        assertEquals("Main", p.startScene)
        assertEquals("classic", p.controlScheme)
        assertEquals(0, p.orientation)
        assertEquals(1767225600000L, p.created)
        assertEquals(listOf("Arena3D", "Legacy", "Main"), p.listScenes())
    }

    // ------------------------------------------------------------------ 2D scene

    @Test
    fun mainSceneContainsEveryObjectAndTheHierarchy() {
        val s = project().loadScene("Main")
        assertEquals("Main", s.name)
        assertEquals(
            listOf("Camera", "Player", "Hat", "Ground", "Sparks", "Music", "Score", "Debug Marker"),
            s.objects.map { it.name },
        )
        assertEquals(9L, s.nextId)
        assertEquals("Player", s.byName("Hat").parent?.name)
        assertEquals("Player", s.byName("Player").tag)
        assertFalse(s.byName("Debug Marker").active)
        assertEquals(45f, s.byName("Debug Marker").rotation, 1e-6f)
        assertEquals(-9.81f, s.gravityY, 1e-6f)
        assertEquals(0xFF4A505A.toInt(), s.ambient)
        assertFalse(s.fog)
    }

    @Test
    fun spriteAndPhysicsComponentsKeepTheirValues() {
        val s = project().loadScene("Main")

        val player = s.byName("Player")
        val hero = player.comp<SpriteRenderer>()
        assertEquals("hero.png", hero.texture)
        assertEquals(0xFFFFFFFF.toInt(), hero.color)
        assertTrue(hero.flipY)
        assertFalse(hero.flipX)
        assertEquals(0, hero.shape) // Square
        val body = player.comp<Rigidbody2D>()
        assertEquals(0, body.bodyType) // Dynamic
        assertTrue(body.freezeRotation)
        assertEquals(0.4f, body.friction, 1e-6f)
        val box = player.comp<Collider2D>()
        assertEquals(0, box.shape) // Box
        assertFalse(box.isTrigger)

        val hat = s.byName("Hat").comp<SpriteRenderer>()
        assertEquals(2, hat.shape) // Triangle
        assertEquals(0f, hat.pivotY, 1e-6f)
        assertEquals(0xFFE04040.toInt(), hat.color)

        val ground = s.byName("Ground")
        val groundSprite = ground.comp<SpriteRenderer>()
        assertEquals("wave.glsl", groundSprite.shader)
        assertEquals(0.5f, groundSprite.shaderParam, 1e-6f)
        assertEquals(2, ground.comp<Rigidbody2D>().bodyType) // Static
    }

    @Test
    fun cameraEffectsTextParticlesAndAudioKeepTheirValues() {
        val s = project().loadScene("Main")

        val cam = s.byName("Camera").comp<Camera2D>()
        assertEquals(6f, cam.size, 1e-6f)
        assertEquals("Player", cam.follow)
        assertEquals(9, cam.postFx) // Custom Shader
        assertEquals("glow.glsl", cam.postShader)
        assertEquals(0.8f, cam.postIntensity, 1e-6f)

        val sparks = s.byName("Sparks").comp<ParticleEmitter>()
        assertEquals(20f, sparks.rate, 1e-6f)
        assertEquals(200, sparks.maxParticles)
        assertTrue(sparks.additive)
        assertEquals(0xFFFFCC33.toInt(), sparks.startColor)
        assertEquals(0x00FF3300, sparks.endColor)

        val score = s.byName("Score").comp<TextRenderer>()
        assertEquals("Score: 0\nLevel 1", score.text)
        assertTrue(score.screenSpace)
        assertEquals(0, score.align) // Left
        assertTrue(score.bold)

        val music = s.byName("Music").comp<AudioSource>()
        assertEquals("theme.ogg", music.clip)
        assertTrue(music.loop)
        assertEquals(0.6f, music.volume, 1e-6f)

        assertEquals("player.js", player(s).comp<ScriptComponent>().script)
        assertEquals("{\"speed\":4}", player(s).comp<ScriptComponent>().params)
        assertEquals("hero_run.anim", player(s).comp<Animator>().clip)
    }

    private fun player(s: Scene) = s.byName("Player")

    @Test
    fun mainSceneSaveReproducesTheFixtureStructure() {
        val written = SceneSerializer.toJson(project().loadScene("Main"))
        assertJsonClose(fixtureJson("Main"), written, "Main")
    }

    @Test
    fun mainSceneSurvivesASecondLoadSaveCycle() {
        val first = SceneSerializer.toJson(project().loadScene("Main"))
        val second = SceneSerializer.toJson(SceneSerializer.fromJson(first))
        assertJsonClose(first, second, "Main(cycle2)")
    }

    // ------------------------------------------------------------------ 3D scene

    @Test
    fun arena3dSceneKeepsFogLightsMeshesAndPhysics() {
        val s = project().loadScene("Arena3D")
        assertTrue(s.fog)
        assertEquals(10f, s.fogStart, 1e-6f)
        assertEquals(60f, s.fogEnd, 1e-6f)
        assertEquals(0xFF8899AA.toInt(), s.fogColor)
        assertEquals(0xFF303040.toInt(), s.ambient)

        val sun = s.byName("Sun")
        assertEquals(-50f, sun.rotX, 1e-6f)
        assertEquals(30f, sun.rotY, 1e-6f)
        val light = sun.comp<Light>()
        assertEquals(0, light.kind) // Directional
        assertEquals(1.2f, light.intensity, 1e-6f)

        val ball = s.byName("Ball")
        assertEquals(-2f, ball.z, 1e-6f)
        assertEquals(1, ball.comp<MeshRenderer>().mesh) // Sphere
        assertEquals(0.6f, ball.comp<Rigidbody3D>().bounciness, 1e-6f)
        assertEquals(1, ball.comp<Collider3D>().shape) // Sphere
        assertEquals(0.5f, ball.comp<Collider3D>().radius, 1e-6f)

        val floor = s.byName("Floor")
        assertEquals(20f, floor.scaleX, 1e-6f)
        assertEquals(20f, floor.scaleZ, 1e-6f)
        assertEquals(2, floor.comp<MeshRenderer>().mesh) // Plane
        assertEquals(2, floor.comp<Rigidbody3D>().bodyType) // Static
        assertEquals(-0.25f, floor.comp<Collider3D>().centerY, 1e-6f)

        val cam = s.byName("Main Camera").comp<Camera3D>()
        assertEquals(60f, cam.fov, 1e-6f)
        assertEquals(200f, cam.far, 1e-6f)
        assertEquals("Ball", cam.follow)
        assertEquals(-6f, cam.offsetZ, 1e-6f)
        assertEquals(6, cam.postFx) // Bloom
    }

    @Test
    fun arena3dSceneSurvivesLoadSaveCycle() {
        val written = SceneSerializer.toJson(project().loadScene("Arena3D"))
        assertJsonClose(fixtureJson("Arena3D"), written, "Arena3D")
    }

    // ------------------------------------------------------------------ legacy scene

    @Test
    fun legacySceneFillsMissingFieldsWithDefaults() {
        val s = project().loadScene("Legacy")
        assertEquals("Legacy", s.name)
        assertEquals(1, s.objects.size)
        val box = s.objects[0]
        assertEquals("Box", box.name)
        assertEquals(1.5f, box.x, 1e-6f)
        assertEquals("Untagged", box.tag)
        assertTrue(box.active)
        assertEquals(0xFF00FF00.toInt(), box.comp<SpriteRenderer>().color)
        assertEquals(-9.81f, s.gravityY, 1e-6f)
        assertEquals(-9.81f, s.gravity3D, 1e-6f) // default when absent
        assertEquals(0xFF4A505A.toInt(), s.ambient) // default when absent
        assertEquals(2L, s.nextId) // one past the highest object id
    }

    @Test
    fun legacySceneIsWrittenInTheCurrentFormatOnSave() {
        val written = SceneSerializer.toJson(project().loadScene("Legacy"))
        for (key in listOf("gravity3D", "ambient", "fogColor", "fogStart", "fogEnd", "fog", "nextId")) {
            assertTrue("missing $key after save", written.has(key))
        }
        assertEquals(2L, written.getLong("nextId"))
        assertEquals(1, written.getJSONArray("objects").length())
    }
}
