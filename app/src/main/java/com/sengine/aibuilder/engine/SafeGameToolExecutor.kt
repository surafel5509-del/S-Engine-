package com.sengine.aibuilder.engine

import com.sengine.aibuilder.spec.ComponentSpec
import com.sengine.aibuilder.spec.GameAction
import com.sengine.aibuilder.spec.GameOperation
import com.sengine.aibuilder.spec.GameSpec
import com.sengine.aibuilder.spec.GameSpecValidator
import com.sengine.aibuilder.spec.SpecValue
import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.Component
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.TextRenderer
import com.sengine.project.Project
import org.json.JSONObject
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException

class GameBuildException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

data class GameOperationResult(
    val affectedScenes: List<String>,
    val affectedObjects: List<String>,
    val changes: List<String>,
    /** Original serialized scene JSON, held outside the project for the optional undo action. */
    val rollbackSnapshots: Map<String, String>,
    /** Newly generated assets are reversible too; the executor never overwrites an existing asset. */
    val generatedAssets: List<String> = emptyList(),
)

/** Executes only the typed operation allowlist. No arbitrary paths, files, shell, or source writes. */
class SafeGameToolExecutor {
    fun apply(project: Project, spec: GameSpec, checkCancelled: () -> Unit = {}): GameOperationResult {
        checkCancelled()
        val validation = GameSpecValidator.validate(
            spec,
            selectedProjectName = if (spec.action == GameAction.EDIT) project.name else null,
        )
        if (!validation.valid) {
            throw GameBuildException(validation.errors.joinToString("\n") { it.message })
        }
        if (spec.action == GameAction.EXPLAIN) return GameOperationResult(emptyList(), emptyList(), emptyList(), emptyMap())
        if (spec.operations.isEmpty()) return GameOperationResult(emptyList(), emptyList(), emptyList(), emptyMap())

        val requestedAssets = spec.operations.mapNotNull(::generatedAssetName).distinct()
        for (assetName in requestedAssets) {
            checkCancelled()
            if (!GameSpecValidator.isSafeAssetName(assetName)) throw GameBuildException("Generated asset name is unsafe.")
            val root = project.assetsDir.canonicalFile
            val file = File(root, assetName).canonicalFile
            if (file.parentFile != root) throw GameBuildException("Generated assets must stay directly inside the project assets folder.")
            if (file.exists()) throw GameBuildException("Asset '$assetName' already exists; AI builds never overwrite project assets.")
        }

        val sceneNames = spec.operations.map { resolveSceneName(project, it.scene) }.distinct()
        val originalFiles = LinkedHashMap<String, ByteArray?>()
        val scenes = LinkedHashMap<String, Scene>()
        for (name in sceneNames) {
            checkCancelled()
            val file = project.sceneFile(name)
            if (!file.isFile) throw GameBuildException("Scene '$name' does not exist in project '${project.name}'.")
            if (file.length() > GameSceneValidator.MAX_SCENE_FILE_BYTES) {
                throw GameBuildException("Scene '$name' exceeds the 8 MB safety limit and cannot be edited by the Builder.")
            }
            originalFiles[name] = file.readBytes()
            scenes[name] = project.loadScene(name)
        }
        val snapshots = originalFiles.mapValues { (_, bytes) -> bytes?.toString(Charsets.UTF_8).orEmpty() }
        val changes = ArrayList<String>()
        val affected = LinkedHashSet<String>()
        val createdAssets = LinkedHashSet<String>()

        try {
            spec.operations.forEachIndexed { index, operation ->
                checkCancelled()
                val name = resolveSceneName(project, operation.scene)
                val scene = scenes[name] ?: throw GameBuildException("Operation ${index + 1} refers to an unavailable scene '$name'.")
                val labels = applyOperation(project, scene, operation, createdAssets)
                affected += labels.first
                changes += labels.second
            }
            for ((name, scene) in scenes) {
                checkCancelled()
                project.saveScene(scene)
            }
            for (name in sceneNames) {
                checkCancelled()
                val reloaded = project.loadScene(name)
                val errors = GameSceneValidator.validate(project, reloaded)
                if (errors.isNotEmpty()) throw GameBuildException("Scene '$name' failed validation after the change:\n${errors.joinToString("\n")}")
            }
            return GameOperationResult(sceneNames, affected.toList(), changes, snapshots, createdAssets.toList())
        } catch (e: Exception) {
            var restoreError: String? = null
            for ((name, bytes) in originalFiles) {
                val file = project.sceneFile(name)
                try {
                    if (bytes == null) file.delete() else {
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                    }
                } catch (restore: Exception) {
                    restoreError = restore.message ?: "could not restore $name"
                }
            }
            for (assetName in createdAssets) {
                try { project.assetFile(assetName).delete() }
                catch (restore: Exception) { restoreError = restore.message ?: "could not remove generated asset $assetName" }
            }
            val message = e.message ?: e.javaClass.simpleName
            if (restoreError != null) throw GameBuildException("$message\nRollback also failed: $restoreError", e)
            if (e is CancellationException) throw e
            if (e is GameBuildException) throw e
            throw GameBuildException("Build operation failed and the original scene data was restored: $message", e)
        }
    }

    private fun resolveSceneName(project: Project, requested: String): String {
        if (requested != "start" && !GameSpecValidator.isSafeSceneName(requested)) {
            throw GameBuildException("Scene name '$requested' is not a safe scene identifier.")
        }
        val resolved = if (requested == "start") project.startScene else requested
        if (!GameSpecValidator.isSafeSceneName(resolved)) throw GameBuildException("Project start scene name is invalid.")
        return resolved
    }

    private fun applyOperation(project: Project, scene: Scene, op: GameOperation, createdAssets: MutableSet<String>): Pair<String, String> = when (op) {
        is GameOperation.AddEntity -> {
            ensureNotDuplicate(scene, op.name)
            val go = scene.create(op.name)
            go.x = op.x; go.y = op.y; go.z = op.z; go.rotation = op.rotation
            go.scaleX = op.scaleX; go.scaleY = op.scaleY; go.scaleZ = op.scaleZ; go.tag = op.tag
            for (component in op.components) go.add(createComponent(project, component))
            go.name to "Added '${go.name}' to ${scene.name}."
        }
        is GameOperation.SetTransform -> {
            val go = requireObject(scene, op.objectName)
            op.x?.let { go.x = it }; op.y?.let { go.y = it }; op.z?.let { go.z = it }
            op.rotation?.let { go.rotation = it }; op.rotX?.let { go.rotX = it }; op.rotY?.let { go.rotY = it }
            op.scaleX?.let { go.scaleX = it }; op.scaleY?.let { go.scaleY = it }; op.scaleZ?.let { go.scaleZ = it }
            go.name to "Updated the transform of '${go.name}'."
        }
        is GameOperation.AddComponent -> {
            val go = requireObject(scene, op.objectName)
            if (go.components.any { it.type == op.component.type }) throw GameBuildException("'${go.name}' already has a ${op.component.type} component.")
            go.add(createComponent(project, op.component))
            go.name to "Added ${op.component.type} to '${go.name}'."
        }
        is GameOperation.SetComponentProperty -> {
            val go = requireObject(scene, op.objectName)
            val component = go.components.firstOrNull { it.type == op.componentType }
                ?: throw GameBuildException("'${go.name}' does not have a ${op.componentType} component.")
            setProperty(project, component, op.property, op.value)
            go.name to "Set ${go.name}.${op.componentType}.${op.property}."
        }
        is GameOperation.SetScriptParameter -> {
            val go = requireObject(scene, op.objectName)
            val candidates = go.components.filterIsInstance<ScriptComponent>().filter { script ->
                (op.scriptAsset == null || script.script == op.scriptAsset) && script.script.isNotBlank()
            }
            val target = candidates.firstOrNull { parameterExists(project, it.script, op.key) }
                ?: throw GameBuildException("No existing script on '${go.name}' declares the safe parameter '${op.key}'.")
            target.params = replaceNumericParameter(target.params, op.key, op.value)
            go.name to "Set '${go.name}' ${op.key} to ${formatNumber(op.value)} in its existing script parameters (source unchanged)."
        }
        is GameOperation.RenameEntity -> {
            val go = requireObject(scene, op.objectName)
            if (scene.objects.any { it !== go && it.name == op.newName }) throw GameBuildException("An entity named '${op.newName}' already exists.")
            val referencedByScript = project.listAssets(AssetKind.SCRIPT).any { asset ->
                (project.readAsset(asset) ?: "").contains(op.objectName)
            }
            if (referencedByScript) throw GameBuildException("Cannot safely rename '${op.objectName}': an existing script references that name. Rename it manually in the editor and review the script.")
            val old = go.name
            go.name = op.newName
            for (other in scene.objects) {
                other.getAny<Camera2D>()?.let { if (it.follow == old) it.follow = op.newName }
                other.getAny<Camera3D>()?.let { if (it.follow == old) it.follow = op.newName }
            }
            go.name to "Renamed '$old' to '${op.newName}' and updated camera follow references."
        }
        is GameOperation.SetSceneGravity -> {
            op.gravityX?.let { scene.gravityX = it }; op.gravityY?.let { scene.gravityY = it }
            op.gravityZ?.let { scene.gravityZ = it }; op.gravity3D?.let { scene.gravity3D = it }
            scene.name to "Updated gravity settings in '${scene.name}'."
        }
        is GameOperation.AddHudText -> {
            ensureNotDuplicate(scene, op.name)
            val go = scene.create(op.name)
            go.x = op.x; go.y = op.y; go.order = op.order
            go.add(TextRenderer().also {
                it.text = op.text; it.size = op.size; it.color = Component.parseColor(op.color); it.bold = true; it.screenSpace = true
            })
            go.name to "Added editable screen-space HUD text '${go.name}'."
        }
        is GameOperation.GeneratePixelSprite -> {
            val bytes = try { GeneratedAssetRecipes.pixelSprite(op.width, op.height, op.palette, op.rows) }
                catch (e: Exception) { throw GameBuildException("Could not generate '${op.assetName}': ${e.message ?: "invalid pixel recipe"}", e) }
            writeGeneratedAsset(project, op.assetName, bytes, createdAssets)
            op.assetName to "Generated a ${op.width}×${op.height} transparent pixel-art PNG '${op.assetName}'."
        }
        is GameOperation.GenerateSound -> {
            val bytes = try { GeneratedAssetRecipes.sound(op.waveform, op.frequencyHz, op.endFrequencyHz, op.durationMs, op.volume) }
                catch (e: Exception) { throw GameBuildException("Could not generate '${op.assetName}': ${e.message ?: "invalid sound recipe"}", e) }
            writeGeneratedAsset(project, op.assetName, bytes, createdAssets)
            op.assetName to "Generated a ${op.durationMs} ms ${op.waveform} WAV sound effect '${op.assetName}'."
        }
        is GameOperation.GenerateAnimationClip -> {
            if (!project.assetFile(op.textureAsset).isFile) throw GameBuildException("Animation sheet '${op.textureAsset}' must exist before '${op.assetName}' is generated.")
            val json = try { GeneratedAssetRecipes.animationClip(op.textureAsset, op.columns, op.rows, op.frames, op.fps, op.loop, op.pivotX, op.pivotY) }
                catch (e: Exception) { throw GameBuildException("Could not generate '${op.assetName}': ${e.message ?: "invalid animation recipe"}", e) }
            writeGeneratedAsset(project, op.assetName, json.toByteArray(Charsets.UTF_8), createdAssets)
            op.assetName to "Generated ${op.frames.size}-frame animation clip '${op.assetName}'."
        }
        is GameOperation.GenerateShaderPreset -> {
            val source = try { GeneratedAssetRecipes.shaderPreset(op.preset) }
                catch (e: Exception) { throw GameBuildException("Could not generate '${op.assetName}': ${e.message ?: "unknown shader preset"}", e) }
            writeGeneratedAsset(project, op.assetName, source.toByteArray(Charsets.UTF_8), createdAssets)
            op.assetName to "Generated vetted '${op.preset}' shader preset '${op.assetName}'."
        }
        is GameOperation.GenerateBlueprintPreset -> {
            val graph = try { GeneratedAssetRecipes.blueprintPreset(op.preset) }
                catch (e: Exception) { throw GameBuildException("Could not generate '${op.assetName}': ${e.message ?: "unknown blueprint preset"}", e) }
            writeGeneratedAsset(project, op.assetName, graph.toByteArray(Charsets.UTF_8), createdAssets)
            op.assetName to "Generated vetted '${op.preset}' visual blueprint '${op.assetName}'."
        }
    }

    private fun writeGeneratedAsset(project: Project, name: String, bytes: ByteArray, createdAssets: MutableSet<String>) {
        if (!GameSpecValidator.isSafeAssetName(name) || bytes.size > MAX_GENERATED_ASSET_BYTES) {
            throw GameBuildException("Generated asset name or size exceeds the safe limit.")
        }
        val root = project.assetsDir.canonicalFile
        val destination = File(root, name).canonicalFile
        if (destination.parentFile != root) throw GameBuildException("Generated assets must stay inside the project assets folder.")
        if (destination.exists()) throw GameBuildException("Asset '$name' already exists; AI builds never overwrite project assets.")
        root.mkdirs()
        val temp = File(root, ".ai-${UUID.randomUUID()}.tmp")
        try {
            temp.writeBytes(bytes)
            try {
                Files.move(temp.toPath(), destination.toPath())
            } catch (e: FileAlreadyExistsException) {
                throw GameBuildException("Asset '$name' already exists; AI builds never overwrite project assets.", e)
            }
            createdAssets += name
        } finally {
            temp.delete()
        }
    }

    private fun generatedAssetName(operation: GameOperation): String? = when (operation) {
        is GameOperation.GeneratePixelSprite -> operation.assetName
        is GameOperation.GenerateSound -> operation.assetName
        is GameOperation.GenerateAnimationClip -> operation.assetName
        is GameOperation.GenerateShaderPreset -> operation.assetName
        is GameOperation.GenerateBlueprintPreset -> operation.assetName
        else -> null
    }

    private fun createComponent(project: Project, spec: ComponentSpec): Component {
        val component = ComponentRegistry.create(spec.type)
            ?: throw GameBuildException("Component '${spec.type}' is not supported by this S Engine build.")
        for ((property, value) in spec.properties) setProperty(project, component, property, value)
        return component
    }

    private fun setProperty(project: Project, component: Component, name: String, value: SpecValue) {
        val prop = component.props().firstOrNull { it.name == name }
            ?: throw GameBuildException("Property '$name' is not supported by ${component.type}.")
        try {
            when (prop) {
                is Prop.F -> {
                    val n = (value as? SpecValue.Number)?.value ?: throw GameBuildException("${component.type}.$name requires a number.")
                    prop.set(n.toFloat())
                    if (kotlin.math.abs(prop.get().toDouble() - n) > 0.0001) throw GameBuildException("${component.type}.$name rejected the requested value.")
                }
                is Prop.I -> {
                    val n = (value as? SpecValue.Number)?.value ?: throw GameBuildException("${component.type}.$name requires an integer.")
                    if (n % 1.0 != 0.0) throw GameBuildException("${component.type}.$name requires an integer.")
                    prop.set(n.toInt())
                    if (prop.get() != n.toInt()) throw GameBuildException("${component.type}.$name rejected the requested value.")
                }
                is Prop.B -> prop.set((value as? SpecValue.Bool)?.value ?: throw GameBuildException("${component.type}.$name requires true or false."))
                is Prop.S -> prop.set((value as? SpecValue.Text)?.value ?: throw GameBuildException("${component.type}.$name requires text."))
                is Prop.Color -> {
                    val color = (value as? SpecValue.Text)?.value ?: throw GameBuildException("${component.type}.$name requires a hex color.")
                    if (!GameSpecValidator.isColor(color)) throw GameBuildException("Color '$color' is invalid.")
                    prop.set(Component.parseColor(color))
                }
                is Prop.Choice -> {
                    val choice = (value as? SpecValue.Text)?.value ?: throw GameBuildException("${component.type}.$name requires a named choice.")
                    val index = prop.options.indexOf(choice)
                    if (index < 0) throw GameBuildException("'$choice' is not a valid ${component.type}.$name choice.")
                    prop.set(index)
                }
                is Prop.Asset -> {
                    val asset = (value as? SpecValue.Text)?.value ?: throw GameBuildException("${component.type}.$name requires an asset name.")
                    if (asset.isNotBlank()) {
                        if (!GameSpecValidator.isSafeAssetName(asset) || AssetKind.of(asset) != prop.kind) {
                            throw GameBuildException("'$asset' is not a valid ${prop.kind} asset reference.")
                        }
                        val root = project.assetsDir.canonicalFile
                        val file = File(root, asset).canonicalFile
                        if (file.parentFile != root || !file.isFile) throw GameBuildException("Asset '$asset' is not available in this project.")
                    }
                    prop.set(asset)
                }
            }
        } catch (e: GameBuildException) {
            throw e
        } catch (e: Exception) {
            throw GameBuildException("Could not set ${component.type}.$name: ${e.message ?: "invalid value"}", e)
        }
    }

    private fun parameterExists(project: Project, scriptName: String, key: String): Boolean {
        if (!GameSpecValidator.isSafeAssetName(scriptName) || AssetKind.of(scriptName) != AssetKind.SCRIPT) return false
        val source = project.readAsset(scriptName) ?: return false
        return Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(source)
    }

    private fun replaceNumericParameter(params: String, key: String, value: Float): String {
        val number = formatNumber(value)
        var replaced = false
        val parts = params.split(',', '\n', ';').map { raw ->
            val assignment = raw.split('=', limit = 2)
            if (assignment.size == 2 && assignment[0].trim() == key) {
                replaced = true
                "$key=$number"
            } else raw.trim()
        }.filter { it.isNotEmpty() }
        return if (replaced) parts.joinToString(", ") else (parts + "$key=$number").joinToString(", ")
    }

    private fun formatNumber(value: Float): String = String.format(java.util.Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    private fun ensureNotDuplicate(scene: Scene, name: String) {
        if (scene.find(name) != null) throw GameBuildException("An entity named '$name' already exists in '${scene.name}'.")
    }

    private fun requireObject(scene: Scene, name: String): GameObject =
        scene.find(name) ?: throw GameBuildException("Entity '$name' does not exist in scene '${scene.name}'.")

    companion object {
        private const val MAX_GENERATED_ASSET_BYTES = 512_000
    }
}
