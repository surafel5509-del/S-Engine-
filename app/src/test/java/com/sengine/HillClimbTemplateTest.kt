package com.sengine

import com.sengine.engine.Engine
import com.sengine.engine.core.Scene
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.physics.PhysicsWorld
import com.sengine.project.Project
import com.sengine.project.Templates
import org.mozilla.javascript.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HillClimbTemplateTest {
    private fun newProject(): Project {
        val dir = Files.createTempDirectory("hillclimb").toFile()
        return Project(File(dir, "Hill Climb Racer")).also { p ->
            p.saveMeta()
            Templates.all.first { it.name == "Hill Climb Racer" }.build(p)
            p.saveMeta()
        }
    }

    @Test
    fun templateCreatesAPlayableDriveProjectAndCompilableScripts() {
        val p = newProject()
        assertEquals("Race", p.startScene)
        assertEquals("driveTilt", Project(p.dir).controlScheme)
        assertTrue(p.sceneExists("Race"))
        for (name in listOf("Game.js", "Vehicle.js", "Pickup.js", "Hazard.js")) {
            val source = p.readAsset(name) ?: error("missing $name")
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.languageVersion = Context.VERSION_ES6
                cx.compileString(source, name, 1, null)
            } finally { Context.exit() }
        }
    }

    @Test
    fun gameGeneratesTerrainDrivesCollectsAndPersistsItsWallet() {
        val p = newProject()
        val engine = Engine(p, p.loadScene(p.startScene))
        engine.gameView.widthPx = 1600
        engine.gameView.heightPx = 900
        val errors = ArrayList<String>()
        engine.listeners.add(object : Engine.Listener {
            override fun onLog(level: Int, message: String) {
                if (level >= 2) errors += message
            }
        })
        engine.play()

        fun frames(n: Int) = repeat(n) { synchronized(engine.lock) { engine.tick(1f / 60f) } }
        frames(2)
        val car = engine.scene.find("Car") ?: error("car prefab missing")
        val startX = car.x
        val terrain = engine.scene.objects.count { it.tag == "Terrain" && it.active }
        assertTrue("initial terrain should be generated ahead of the player", terrain > 30)
        assertTrue("the run should show its rotating challenge", engine.scene.find("Challenge Text") != null)
        assertTrue("procedural hazards should be seeded ahead", engine.scene.objects.any { it.tag == "Hazard" && it.active })

        engine.input.rawA = true
        frames(100)
        engine.input.rawA = false
        assertTrue("gas should drive the vehicle forward", car.x > startX + 2f)

        val pickup = engine.scene.objects.firstOrNull { it.tag == "Coin" && it.active && !it.destroyed }
            ?: error("coin clusters should be spawned")
        synchronized(engine.lock) {
            engine.scripts.sendMessage(car, "reset", pickup.x.toDouble())
        }
        frames(4)
        assertTrue("coin should be consumed by the car pickup sensor", pickup.destroyed || pickup !in engine.scene.objects)
        val reloadedProject = Project(p.dir)
        assertTrue("wallet is saved between sessions", ((reloadedProject.userData("hcr.wallet") as? Number)?.toInt() ?: 0) >= 5)
        assertTrue("template scripts should run without errors: $errors", errors.isEmpty())

        engine.stop()
        frames(1)
        engine.release()
    }

    @Test
    fun reachingTheNextBiomeUnlocksAndPaysTheStageRewardOnce() {
        val p = newProject()
        val engine = Engine(p, p.loadScene(p.startScene))
        engine.gameView.widthPx = 1600; engine.gameView.heightPx = 900
        engine.play()
        fun frames(n: Int) = repeat(n) { synchronized(engine.lock) { engine.tick(1f / 60f) } }
        frames(2)
        val car = engine.scene.find("Car")!!
        synchronized(engine.lock) { engine.scripts.sendMessage(car, "reset", 45.0) }
        frames(3)
        val stage = engine.scene.find("Stage Text")!!.getAny<com.sengine.engine.core.TextRenderer>()!!.text
        assertTrue("crossing 44 m should advance into the desert stage: $stage", stage.startsWith("LEVEL 2 / 4"))
        val wallet = (Project(p.dir).userData("hcr.wallet") as? Number)?.toInt() ?: 0
        assertEquals("first level-clear reward", 30, wallet)
        frames(5)
        val walletAgain = (Project(p.dir).userData("hcr.wallet") as? Number)?.toInt() ?: 0
        assertEquals("stage rewards must not repeat while remaining in the biome", 30, walletAgain)
        engine.release()
    }

    @Test
    fun rotatedTerrainColliderSupportsASettlingCircle() {
        val scene = Scene("Ramp")
        scene.gravityY = -9.81f
        val ramp = scene.create("Ramp").also { it.rotation = 24f; it.scaleX = 4f; it.scaleY = 0.35f }
        ramp.add(com.sengine.engine.core.Collider2D())
        ramp.add(Rigidbody2D().also { it.bodyType = 2; it.friction = 0.9f })
        val ball = scene.create("Ball").also { it.x = 0f; it.y = 1.1f; it.scaleX = 0.6f; it.scaleY = 0.6f }
        ball.add(com.sengine.engine.core.Collider2D().also { it.shape = 1; it.radius = 0.5f })
        ball.add(Rigidbody2D().also { it.bounciness = 0f; it.friction = 0.9f })
        val world = PhysicsWorld()
        repeat(180) { world.step(scene, 1f / 60f) }
        assertTrue("circle should rest on the sloped face", ball.y > 0.25f && ball.y < 1.2f)
        assertTrue("slope should move the ball away from its initial height", ball.y < 1.1f)
    }
}
