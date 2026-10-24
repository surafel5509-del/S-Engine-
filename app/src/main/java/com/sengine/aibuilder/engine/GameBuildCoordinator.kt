package com.sengine.aibuilder.engine

import android.content.Context
import com.sengine.aibuilder.memory.AiMemoryStore
import com.sengine.aibuilder.spec.GameAction
import com.sengine.aibuilder.spec.GameBuildReport
import com.sengine.aibuilder.spec.GameSpec
import com.sengine.aibuilder.spec.GameSpecValidator
import com.sengine.aibuilder.spec.GameTemplateCatalog
import com.sengine.engine.Engine
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

class GameBuildOutcome(val project: Project, val report: GameBuildReport)

/** Coordinates validation, bounded operations, real project save/load, startup smoke test and rollback. */
class GameBuildCoordinator(
    private val memory: AiMemoryStore? = null,
    private val executor: SafeGameToolExecutor = SafeGameToolExecutor(),
) {
    fun create(context: Context, spec: GameSpec, modeLabel: String, checkCancelled: () -> Unit = {}): GameBuildOutcome {
        checkCancelled()
        val validation = GameSpecValidator.validate(spec)
        if (!validation.valid) throw GameBuildException(validation.errors.joinToString("\n") { it.message })
        if (spec.action != GameAction.CREATE) throw GameBuildException("Only a create plan can create a project.")
        val template = GameTemplateCatalog.template(spec.templateId)
            ?: throw GameBuildException("Template '${spec.templateId}' is not installed in this S Engine build.")
        var operationResult: GameOperationResult? = null
        var smokeText = ""
        val project = ProjectManager.createGenerated(context, spec.projectName, template) { staged ->
            checkCancelled()
            operationResult = executor.apply(staged, spec, checkCancelled)
            checkCancelled()
            validateProject(staged, checkCancelled = checkCancelled)
            val smoke = RuntimeStartupSmokeTest.run(staged, checkCancelled)
            if (!smoke.passed) throw GameBuildException("Runtime startup smoke test failed:\n${smoke.errors.joinToString("\n")}")
            smokeText = smoke.summary
            checkCancelled()
        }
        val applied = operationResult ?: GameOperationResult(emptyList(), emptyList(), emptyList(), emptyMap())
        return GameBuildOutcome(project, GameBuildReport(
            projectName = project.name,
            created = true,
            summary = spec.summary,
            templateName = template.name,
            affectedScenes = project.listScenes(),
            affectedObjects = applied.affectedObjects,
            changes = listOf("Created from the installed '${template.name}' template.") + applied.changes,
            validation = validation.issues,
            runtimeSmokeTest = smokeText,
            modeLabel = modeLabel,
        ))
    }

    fun edit(project: Project, spec: GameSpec, modeLabel: String, checkCancelled: () -> Unit = {}): GameBuildOutcome {
        checkCancelled()
        val validation = GameSpecValidator.validate(spec, selectedProjectName = project.name)
        if (!validation.valid) throw GameBuildException(validation.errors.joinToString("\n") { it.message })
        if (spec.action != GameAction.EDIT) throw GameBuildException("Only an edit plan can modify an existing project.")
        val applied = executor.apply(project, spec, checkCancelled)
        try {
            checkCancelled()
            validateProject(project, applied.affectedScenes.toSet(), checkCancelled)
            val smoke = RuntimeStartupSmokeTest.run(project, checkCancelled, executeScripts = false)
            if (!smoke.passed) throw GameBuildException("Runtime startup smoke test failed:\n${smoke.errors.joinToString("\n")}")
            checkCancelled()
            val rollbackToken = if (applied.rollbackSnapshots.size == 1 && memory != null) {
                val (scene, snapshot) = applied.rollbackSnapshots.entries.first()
                val rollbackData = if (applied.generatedAssets.isEmpty()) snapshot else JSONObject()
                    .put("format", "sengine-ai-rollback-v1")
                    .put("scene", JSONObject(snapshot))
                    .put("assets", org.json.JSONArray().also { assets ->
                        applied.generatedAssets.forEach { name ->
                            val file = project.assetFile(name)
                            if (file.isFile) assets.put(JSONObject().put("name", name).put("sha256", sha256(file.readBytes())))
                        }
                    }).toString()
                try { memory.pushRollback(project.name, scene, rollbackData) } catch (_: Exception) { null }
            } else null
            return GameBuildOutcome(project, GameBuildReport(
                projectName = project.name,
                created = false,
                summary = spec.summary,
                templateName = "Existing project",
                affectedScenes = applied.affectedScenes,
                affectedObjects = applied.affectedObjects,
                changes = applied.changes,
                validation = validation.issues,
                runtimeSmokeTest = smoke.summary,
                modeLabel = modeLabel,
                rollbackToken = rollbackToken,
                rollbackScene = applied.rollbackSnapshots.keys.singleOrNull()?.takeIf { rollbackToken != null },
            ))
        } catch (e: Exception) {
            restore(project, applied.rollbackSnapshots)
            applied.generatedAssets.forEach { asset -> try { project.assetFile(asset).delete() } catch (_: Exception) { } }
            throw e
        }
    }

    /** Restore the most recent local-only scene snapshot for this project. */
    fun rollbackLatest(
        project: Project,
        requestedScene: String = project.startScene,
        checkCancelled: () -> Unit = {},
    ): String {
        checkCancelled()
        val rollbackStore = memory ?: throw GameBuildException("Local rollback storage is unavailable.")
        val sceneName = requestedScene
        if (!com.sengine.aibuilder.spec.GameSpecValidator.isSafeSceneName(sceneName) || !project.sceneExists(sceneName)) {
            throw GameBuildException("Rollback scene name is invalid or missing.")
        }
        val (token, snapshot) = rollbackStore.latestRollback(project.name, sceneName)
            ?: throw GameBuildException("There is no rollback snapshot for the start scene.")
        val file = project.sceneFile(sceneName)
        if (!file.isFile || file.length() > GameSceneValidator.MAX_SCENE_FILE_BYTES) {
            throw GameBuildException("Rollback target scene is missing or exceeds the 8 MB safety limit.")
        }
        val before = file.readText()
        val rollbackDoc = try { JSONObject(snapshot) } catch (e: Exception) {
            throw GameBuildException("Rollback snapshot is malformed.", e)
        }
        val isBundle = rollbackDoc.optString("format") == "sengine-ai-rollback-v1"
        val sceneJson = if (isBundle) rollbackDoc.optJSONObject("scene")?.toString()
            ?: throw GameBuildException("Rollback scene snapshot is missing.") else snapshot
        val removableAssets = ArrayList<String>()
        val assetRows = rollbackDoc.optJSONArray("assets")
        if (isBundle && assetRows != null) for (i in 0 until assetRows.length()) {
            val item = assetRows.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val expectedHash = item.optString("sha256")
            if (!com.sengine.aibuilder.spec.GameSpecValidator.isSafeAssetName(name) || expectedHash.isBlank()) continue
            val root = project.assetsDir.canonicalFile
            val assetFile = File(root, name).canonicalFile
            if (assetFile.parentFile == root && assetFile.isFile && sha256(assetFile.readBytes()) == expectedHash) removableAssets += name
        }
        val beforeAssets = LinkedHashMap<String, ByteArray>()
        try {
            checkCancelled()
            val old = SceneSerializer.fromJson(JSONObject(sceneJson)).also { it.name = sceneName }
            project.saveScene(old)
            for (assetName in removableAssets) {
                checkCancelled()
                val assetFile = project.assetFile(assetName)
                if (assetFile.isFile) {
                    beforeAssets[assetName] = assetFile.readBytes()
                    if (!assetFile.delete()) throw GameBuildException("Could not remove generated asset '$assetName' during rollback.")
                }
            }
            checkCancelled()
            val errors = GameSceneValidator.validate(project, project.loadScene(sceneName))
            if (errors.isNotEmpty()) throw GameBuildException("Rollback result failed validation:\n${errors.joinToString("\n")}")
            val smoke = RuntimeStartupSmokeTest.run(project, checkCancelled, executeScripts = false)
            if (!smoke.passed) throw GameBuildException("Restored scene did not pass the runtime startup smoke test:\n${smoke.errors.joinToString("\n")}")
            checkCancelled()
            rollbackStore.removeLatestRollback(project.name, sceneName, token)
            val removedNote = if (beforeAssets.isEmpty()) "" else " Removed ${beforeAssets.size} unchanged AI-generated asset(s)."
            return "Restored the previous '${sceneName}' scene.$removedNote ${smoke.summary}"
        } catch (e: Exception) {
            try { file.writeText(before) } catch (_: Exception) { }
            beforeAssets.forEach { (name, bytes) -> try { project.writeAssetBytes(name, bytes) } catch (_: Exception) { } }
            throw e
        }
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun validateProject(
        project: Project,
        onlyScenes: Set<String>? = null,
        checkCancelled: () -> Unit = {},
    ) {
        val scenes = onlyScenes ?: project.listScenes().toSet()
        if (scenes.isEmpty()) throw GameBuildException("Project has no saved scenes.")
        for (name in scenes) {
            checkCancelled()
            if (!project.sceneExists(name)) throw GameBuildException("Project start/operation scene '$name' was not saved.")
            val errors = GameSceneValidator.validate(project, project.loadScene(name))
            if (errors.isNotEmpty()) throw GameBuildException(errors.joinToString("\n"))
        }
        if (!com.sengine.aibuilder.spec.GameSpecValidator.isSafeSceneName(project.startScene)) throw GameBuildException("Project start scene name is invalid.")
        if (!project.sceneExists(project.startScene)) throw GameBuildException("Start scene '${project.startScene}' is missing.")
        checkCancelled()
        project.saveMeta()
        // Re-open the metadata and scenes through the same model the editor uses.
        val reopened = Project(project.dir)
        if (reopened.startScene != project.startScene || reopened.listScenes().isEmpty()) {
            throw GameBuildException("Project metadata failed its save/reload check.")
        }
        for (name in scenes) {
            checkCancelled()
            reopened.loadScene(name)
        }
    }

    private fun restore(project: Project, snapshots: Map<String, String>) {
        var failure: String? = null
        for ((sceneName, raw) in snapshots) {
            try {
                val scene: Scene = SceneSerializer.fromJson(JSONObject(raw)).also { it.name = sceneName }
                project.saveScene(scene)
            } catch (e: Exception) { failure = e.message ?: "restore failed" }
        }
        if (failure != null) throw GameBuildException("Build failed and rollback was incomplete: $failure")
    }
}

data class RuntimeSmokeResult(val passed: Boolean, val errors: List<String>, val summary: String)

/** Runs the actual S Engine simulation without claiming that a GPU/device rendering test occurred. */
object RuntimeStartupSmokeTest {
    fun run(
        project: Project,
        checkCancelled: () -> Unit = {},
        executeScripts: Boolean = true,
    ): RuntimeSmokeResult {
        checkCancelled()
        if (!com.sengine.aibuilder.spec.GameSpecValidator.isSafeSceneName(project.startScene)) {
            return RuntimeSmokeResult(false, listOf("Project start scene name is invalid."), "Start scene name failed path validation.")
        }
        val scene = try { project.loadScene(project.startScene) } catch (e: Exception) {
            return RuntimeSmokeResult(false, listOf("Could not load start scene: ${e.message}"), "Start scene did not load.")
        }
        val errors = ArrayList<String>()
        // Template smoke checks may execute scripts; edit/rollback checks disable them. Any writes
        // to S Engine persistent storage are redirected to a temporary directory, and audio is off.
        val smokeStorageDir = File(project.dir.parentFile ?: project.dir, ".ai-smoke-${UUID.randomUUID()}")
        val engine = try {
            Engine(project, scene, Project(smokeStorageDir), audioEnabled = false, scriptsEnabled = executeScripts)
        } catch (e: Exception) {
            smokeStorageDir.deleteRecursively()
            return RuntimeSmokeResult(false, listOf("Could not initialize S Engine: ${e.message}"), "Engine initialization failed.")
        }
        engine.listeners.add(object : Engine.Listener {
            override fun onLog(level: Int, message: String) {
                if (level >= 2) errors += message.take(500)
            }
        })
        return try {
            checkCancelled()
            engine.play()
            repeat(2) {
                checkCancelled()
                synchronized(engine.lock) { engine.tick(1f / 60f) }
            }
            checkCancelled()
            engine.stop()
            synchronized(engine.lock) { engine.tick(1f / 60f) }
            val passed = errors.isEmpty()
            RuntimeSmokeResult(
                passed,
                errors.distinct(),
                if (passed) "Headless S Engine startup passed: 2 simulation frames; ${if (executeScripts) "scripts executed" else "existing project scripts were syntax-checked but not executed"}. Visual/device rendering was not tested."
                else "Headless S Engine startup reported ${errors.distinct().size} runtime${if (executeScripts) "/script" else ""} error(s).",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += (e.message ?: e.javaClass.simpleName).take(500)
            RuntimeSmokeResult(false, errors.distinct(), "Headless startup failed; no visual/device rendering was tested.")
        } finally {
            try { engine.release() } catch (_: Exception) { }
            try { smokeStorageDir.deleteRecursively() } catch (_: Exception) { }
        }
    }
}
