package com.sengine

import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Light
import com.sengine.engine.core.Light2D
import com.sengine.engine.core.MeshRenderer
import com.sengine.engine.core.Rigidbody3D
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.physics.PhysicsWorld
import com.sengine.project.Project
import com.sengine.fixtures.legacy.ProTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The two shipped Pro templates are built by the real editor code path, so they must stay playable:
 * scenes are written to disk, scripts are attached, lights exist and the 2D level is not falling apart
 * after a few seconds of simulation.
 */
class ProTemplatesTest {

    private fun project(name: String): Project {
        val dir = Files.createTempDirectory("sengine-pro").toFile()
        return Project(File(dir, name)).also { it.saveMeta() }
    }

    private fun scriptNames(p: Project) = listOf("Player.js", "Star.js", "Torch.js", "Mover.js", "Menu.js")

    @Test
    fun legacyFixturesRemainAvailableForCompatibilityTests() {
        val names = com.sengine.fixtures.legacy.LegacyTemplates.all.map { it.name }
        assertTrue("Lumen Ascent missing", names.contains("Lumen Ascent (Pro 2D)"))
        assertTrue("Aurora Station missing", names.contains("Aurora Station (Pro 3D)"))
        assertEquals(2, ProTemplates.all.size)
        assertEquals("Lumen Ascent (Pro 2D)", ProTemplates.lumenAscent.name)
        assertEquals("Aurora Station (Pro 3D)", ProTemplates.auroraStation.name)
        assertTrue(ProTemplates.lumenAscent.threeD.not())
        assertTrue(ProTemplates.auroraStation.threeD)
    }

    @Test
    fun lumenAscentWritesAPlayableLitPlatformer() {
        val p = project("Lumen Ascent")
        ProTemplates.lumenAscent.build(p)

        assertTrue(p.sceneExists("Main"))
        assertTrue(p.sceneExists("Menu"))
        assertEquals("Menu", p.startScene)
        for (name in scriptNames(p)) assertTrue("$name should be written", p.assetFile(name).isFile)

        val main = p.loadScene("Main")
        val camera = main.objects.first { it.get<Camera2D>() != null }.get<Camera2D>()!!
        assertEquals("Player", camera.follow)
        assertTrue("the pro camera rig is on by default", camera.lookAhead > 0f && camera.deadzoneX > 0f)

        val lights = main.objects.mapNotNull { it.get<Light2D>() }
        assertTrue("ambient light", lights.any { it.kind == 0 })
        assertTrue("point lights (torches, goal)", lights.count { it.kind == 1 } >= 3)

        assertTrue("bumpy terrain collider", main.objects.any { it.get<Collider2D>()?.shape == 2 })
        assertTrue("scripted player", main.objects.first { it.tag == "Player" }.get<ScriptComponent>()?.script == "Player.js")
        assertTrue("collectibles", main.objects.count { it.tag == "Star" } >= 5)
        assertTrue("hazard", main.objects.any { it.tag == "Hazard" })
        assertTrue("HUD", main.objects.any { it.get<TextRenderer>()?.screenSpace == true })
        assertTrue("physics props", main.objects.count { it.get<com.sengine.engine.core.Rigidbody2D>() != null } >= 4)
    }

    @Test
    fun lumenAscentLevelHoldsTogetherUnderSimulation() {
        val p = project("Lumen Ascent")
        ProTemplates.lumenAscent.build(p)
        val main = p.loadScene("Main")
        val player = main.objects.first { it.tag == "Player" }

        val world = PhysicsWorld()
        repeat(240) { world.step(main, 1f / 60f) }

        assertTrue("player stays in the level (y=${player.y})", player.y > 0f && player.y < 6f)
        val crates = main.objects.filter { it.name.startsWith("Crate") }
        assertTrue("crates exist", crates.isNotEmpty())
        assertTrue("crates do not fall through the ground", crates.all { it.y > -1f })
    }

    @Test
    fun auroraStationWritesA3DScene() {
        val p = project("Aurora Station")
        ProTemplates.auroraStation.build(p)

        assertTrue(p.sceneExists("Main"))
        val scene = p.loadScene("Main")
        assertTrue("3D camera", scene.objects.any { it.get<Camera3D>() != null })
        assertTrue("meshes", scene.objects.count { it.get<MeshRenderer>() != null } >= 10)
        assertTrue("lights", scene.objects.count { it.get<Light>() != null } >= 4)
        assertTrue("physics props", scene.objects.count { it.get<Rigidbody3D>() != null } >= 5)
        for (name in listOf("Player3D.js", "Shard.js", "Orbit.js")) {
            assertTrue("$name should be written", p.assetFile(name).isFile)
        }
    }
}
