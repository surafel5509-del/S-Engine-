package com.sengine.aibuilder

import com.sengine.aibuilder.engine.GameProjectInspector
import com.sengine.aibuilder.engine.GameBuildCoordinator
import com.sengine.aibuilder.engine.GameSceneValidator
import com.sengine.aibuilder.engine.RuntimeStartupSmokeTest
import com.sengine.aibuilder.engine.SafeGameToolExecutor
import com.sengine.aibuilder.memory.AiMemoryStore
import com.sengine.aibuilder.spec.GameAction
import com.sengine.aibuilder.spec.ComponentSpec
import com.sengine.aibuilder.spec.GameDimension
import com.sengine.aibuilder.spec.GameOperation
import com.sengine.aibuilder.spec.GameSpec
import com.sengine.aibuilder.spec.SpecValue
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.TextRenderer
import com.sengine.project.Project
import com.sengine.fixtures.legacy.LegacyTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GameToolWorkflowTest {
    @Test
    fun existingPlatformerTemplateSavesReloadsAndStartsInTheRealEngine() {
        val project = createProject("Platformer Demo")
        val spec = GameSpec(
            action = GameAction.CREATE,
            projectName = project.name,
            genre = "2D platformer",
            dimension = GameDimension.TWO_D,
            templateId = "platformer-demo",
            summary = "Use the installed playable platformer template.",
        )
        assertTrue(com.sengine.aibuilder.spec.GameSpecValidator.validate(spec).valid)
        val reloaded = Project(project.dir)
        assertEquals("Main", reloaded.startScene)
        assertTrue(reloaded.sceneExists("Main"))
        assertTrue(GameSceneValidator.validate(reloaded, reloaded.loadScene("Main")).isEmpty())
        val runtime = RuntimeStartupSmokeTest.run(reloaded)
        assertTrue("${runtime.errors}", runtime.passed)
        assertTrue(runtime.summary.contains("2 simulation frames"))
    }

    @Test
    fun providerProjectSnapshotIsStrictlyBoundedAndKeepsStartScene() {
        val project = createProject("Large Snapshot")
        val scene = project.loadScene(project.startScene)
        repeat(60) { scene.create("Extra Entity $it").add(TextRenderer()) }
        project.saveScene(scene)

        val snapshot = GameProjectInspector.snapshot(project)

        assertTrue(snapshot.toString().length <= 10_000)
        assertTrue(snapshot.optBoolean("truncated"))
        assertEquals(project.startScene, snapshot.optString("startScene"))
    }

    @Test
    fun startupSmokeUsesTransientStorageAndSuppressesAudio() {
        val project = createProject("Isolated Smoke Test")
        val scene = project.loadScene(project.startScene)
        val player = scene.find("Player")!!
        project.writeAsset("SmokeTest.js", "function start() { storage.set('smoke-check', 'written'); audio.beep(); }")
        player.add(ScriptComponent().also { it.script = "SmokeTest.js" })
        project.saveScene(scene)

        val result = RuntimeStartupSmokeTest.run(project)

        assertTrue("${result.errors}", result.passed)
        assertEquals(null, project.userData("smoke-check"))
        assertFalse((project.dir.parentFile.listFiles() ?: emptyArray()).any { it.name.startsWith(".ai-smoke-") })
    }

    @Test
    fun boundedOperationsModifyRealSceneDataAndSaveIt() {
        val project = createProject("Editable Platformer")
        val sourceBefore = project.readAsset("Player.js")
        val edit = GameSpec(
            action = GameAction.EDIT,
            projectName = project.name,
            genre = "Existing 2D game",
            dimension = GameDimension.TWO_D,
            summary = "Make the jump a little higher.",
            operations = listOf(GameOperation.SetScriptParameter("start", "Player", "jump", 13.75f, "Player.js")),
        )
        val result = SafeGameToolExecutor().apply(project, edit)
        assertEquals(listOf("Main"), result.affectedScenes)
        val saved = Project(project.dir).loadScene("Main").find("Player")!!
        val script = saved.components.filterIsInstance<ScriptComponent>().single { it.script == "Player.js" }
        assertTrue(script.params.contains("jump=13.75"))
        assertEquals("AI edits must not rewrite JavaScript source", sourceBefore, project.readAsset("Player.js"))
        assertTrue(GameSceneValidator.validate(project, project.loadScene("Main")).isEmpty())
    }

    @Test
    fun failedPatchRestoresOriginalSceneBytes() {
        val project = createProject("Rollback Platformer")
        val file = project.sceneFile("Main")
        val before = file.readText()
        val edit = GameSpec(
            action = GameAction.EDIT,
            projectName = project.name,
            genre = "Existing 2D game",
            dimension = GameDimension.TWO_D,
            summary = "The second operation intentionally targets a missing entity.",
            operations = listOf(
                GameOperation.AddHudText("start", "Temporary HUD", "not saved", size = 0.4f),
                GameOperation.SetTransform("start", "No Such Object", x = 2f),
            ),
        )
        try {
            SafeGameToolExecutor().apply(project, edit)
            throw AssertionError("missing entity should fail the operation batch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("does not exist"))
        }
        assertEquals(before, file.readText())
        assertFalse(project.loadScene("Main").objects.any { it.name == "Temporary HUD" })
    }

    @Test
    fun staticHudOperationUsesNativeTextRendererComponent() {
        val project = createProject("HUD Test")
        val edit = GameSpec(
            action = GameAction.EDIT,
            projectName = project.name,
            genre = "Existing 2D game",
            dimension = GameDimension.TWO_D,
            summary = "Add a static HUD label.",
            operations = listOf(GameOperation.AddHudText("start", "Status", "READY", x = 1f, y = 3f)),
        )
        SafeGameToolExecutor().apply(project, edit)
        val label = Project(project.dir).loadScene("Main").find("Status")!!
        val text = label.getAny<TextRenderer>()!!
        assertEquals("READY", text.text)
        assertTrue(text.screenSpace)
    }

    @Test
    fun safeAssetRecipesCreateEditableTexturesAudioAnimationShaderAndBlueprintAssets() {
        val project = createProject("AI Asset Game")
        val edit = GameSpec(
            action = GameAction.EDIT,
            projectName = project.name,
            genre = "2D platformer",
            dimension = GameDimension.TWO_D,
            summary = "Add a small generated hero sprite and safe game assets.",
            operations = listOf(
                GameOperation.GeneratePixelSprite("start", "AI_Hero.png", 2, 2,
                    listOf("#FFFF7043", "#FFFFFFFF"), listOf("01", "10")),
                GameOperation.GenerateSound("start", "AI_Coin.wav", "sine", 880f, 440f, 180, 0.5f),
                GameOperation.GenerateAnimationClip("start", "AI_Hero.anim", "AI_Hero.png", 1, 1, listOf(0), 8f, true),
                GameOperation.GenerateShaderPreset("start", "AI_Pulse.glsl", "pulse"),
                GameOperation.GenerateBlueprintPreset("start", "AI_Controller.bp", "platformer_controller"),
                GameOperation.AddEntity("start", "AI Hero", x = 3f, y = 2f, components = listOf(
                    ComponentSpec("SpriteRenderer", mapOf(
                        "Texture" to SpecValue.Text("AI_Hero.png"),
                        "Shader" to SpecValue.Text("AI_Pulse.glsl"),
                    )),
                    ComponentSpec("Animator", mapOf("Clip" to SpecValue.Text("AI_Hero.anim"))),
                    ComponentSpec("Script", mapOf("Script" to SpecValue.Text("AI_Controller.bp"))),
                    ComponentSpec("AudioSource", mapOf("Clip" to SpecValue.Text("AI_Coin.wav"))),
                )),
            ),
        )

        val result = SafeGameToolExecutor().apply(project, edit)

        assertEquals(5, result.generatedAssets.size)
        assertTrue(project.assetFile("AI_Hero.png").readBytes().copyOfRange(0, 8)
            .contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        assertEquals("RIFF", String(project.assetFile("AI_Coin.wav").readBytes().copyOfRange(0, 4), Charsets.US_ASCII))
        assertTrue(project.readAsset("AI_Pulse.glsl").orEmpty().contains("vec4 effect"))
        assertTrue(com.sengine.engine.blueprint.Blueprint.parse(project.readAsset("AI_Controller.bp")!!).nodes.any { it.type == "Platformer" })
        assertEquals("AI_Hero.png", org.json.JSONObject(project.readAsset("AI_Hero.anim")!!).optString("texture"))
        val saved = Project(project.dir).loadScene("Main").find("AI Hero")!!
        assertEquals("AI_Hero.png", saved.getAny<com.sengine.engine.core.SpriteRenderer>()!!.texture)
        assertTrue(GameSceneValidator.validate(project, project.loadScene("Main")).isEmpty())
    }

    @Test
    fun failedAssetAndScenePatchRemovesNewAssetsAndRestoresOriginalSceneBytes() {
        val project = createProject("Rollback Generated Asset")
        val file = project.sceneFile("Main")
        val before = file.readText()
        val edit = GameSpec(
            action = GameAction.EDIT,
            projectName = project.name,
            genre = "Existing 2D game",
            dimension = GameDimension.TWO_D,
            summary = "The second operation intentionally targets a missing entity.",
            operations = listOf(
                GameOperation.GeneratePixelSprite("start", "Transient_AI.png", 1, 1, listOf("#FFFFFFFF"), listOf("0")),
                GameOperation.SetTransform("start", "No Such Object", x = 2f),
            ),
        )
        try {
            SafeGameToolExecutor().apply(project, edit)
            throw AssertionError("missing entity should fail the operation batch")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("does not exist"))
        }
        assertEquals(before, file.readText())
        assertFalse(project.assetFile("Transient_AI.png").exists())
    }

    @Test
    fun rollbackRemovesOnlyAiAssetsWhoseContentStillMatchesItsSnapshot() {
        val project = createProject("Asset-aware rollback")
        val memoryDir = Files.createTempDirectory("sengine-ai-rollback").toFile()
        val coordinator = GameBuildCoordinator(AiMemoryStore(memoryDir))
        val edit = GameSpec(
            action = GameAction.EDIT,
            projectName = project.name,
            genre = "Existing 2D game",
            dimension = GameDimension.TWO_D,
            summary = "Generate reversible icon assets and add a temporary label.",
            operations = listOf(
                GameOperation.GeneratePixelSprite("start", "AI_Undo.png", 1, 1, listOf("#FFFFFFFF"), listOf("0")),
                GameOperation.GenerateSound("start", "AI_Keep.wav", "sine", 440f, 440f, 100, 0.5f),
                GameOperation.AddHudText("start", "Undo Marker", "TEMPORARY"),
            ),
        )

        val outcome = coordinator.edit(project, edit, "Local Template mode")
        assertTrue(outcome.report.rollbackToken != null)
        val generatedSound = project.assetFile("AI_Keep.wav")
        generatedSound.writeBytes(generatedSound.readBytes() + byteArrayOf(1))

        val report = coordinator.rollbackLatest(project)

        assertTrue(report.contains("Removed 1 unchanged AI-generated asset"))
        assertFalse(project.assetFile("AI_Undo.png").exists())
        assertTrue(generatedSound.exists())
        assertFalse(Project(project.dir).loadScene("Main").objects.any { it.name == "Undo Marker" })
    }

    private fun createProject(projectName: String, templateName: String = "Platformer Demo"): Project {
        val root = Files.createTempDirectory("sengine-ai-build").toFile()
        val project = Project(File(root, projectName))
        project.saveMeta()
        LegacyTemplates.all.first { it.name == templateName }.build(project)
        project.saveMeta()
        return project
    }
}
