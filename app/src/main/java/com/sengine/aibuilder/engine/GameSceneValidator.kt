package com.sengine.aibuilder.engine

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Scene
import com.sengine.project.Project
import com.sengine.engine.blueprint.Blueprint
import com.sengine.engine.blueprint.BlueprintCompiler
import org.mozilla.javascript.Context
import org.json.JSONObject
import java.io.File

/** Source-level validation against S Engine's current Project and SceneSerializer formats. */
object GameSceneValidator {
    const val MAX_SCENE_FILE_BYTES = 8_000_000L
    private const val MAX_SCRIPT_FILE_BYTES = 1_000_000L

    fun validate(project: Project, scene: Scene): List<String> {
        if (scene.objects.size > 3_000) return listOf("Scene '${scene.name}' exceeds the 3,000-object safety limit.")
        val errors = ArrayList<String>()
        val checkedScripts = HashSet<String>()
        val scriptErrors = HashMap<String, String>()
        val ids = HashSet<Long>()
        val names = HashSet<String>()
        for (go in scene.objects) {
            if (!ids.add(go.id)) errors += "Scene '${scene.name}' has duplicate entity ID ${go.id}."
            if (go.name.isBlank() || go.name.length > 120) errors += "Entity ${go.id} has an invalid name."
            if (!names.add(go.name)) errors += "Scene '${scene.name}' contains duplicate entity name '${go.name}'."
            val transforms = listOf(go.x, go.y, go.z, go.rotation, go.rotX, go.rotY, go.scaleX, go.scaleY, go.scaleZ)
            if (transforms.any { !it.isFinite() || kotlin.math.abs(it) > 100_000f }) {
                errors += "Entity '${go.name}' has a non-finite or out-of-range transform."
            }
            for (component in go.components) {
                for (prop in component.props()) {
                    when (prop) {
                        is Prop.F -> if (!prop.get().isFinite()) errors += "${go.name}.${component.type}.${prop.name} is not finite."
                        is Prop.Asset -> {
                            val name = prop.get()
                            if (name.isNotBlank()) {
                                val expected = prop.kind
                                if (AssetKind.of(name) != expected) {
                                    errors += "${go.name}.${component.type}.${prop.name} has an invalid $expected asset reference '$name'."
                                } else if (!assetExists(project, name)) {
                                    errors += "${go.name}.${component.type}.${prop.name} references missing asset '$name'."
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                if (component.type == "Script") {
                    val script = (component as? com.sengine.engine.core.ScriptComponent)?.script.orEmpty()
                    if (script.isNotBlank()) {
                        if (checkedScripts.add(script)) validateScript(project, script)?.let { scriptErrors[script] = it }
                        scriptErrors[script]?.let { errors += "${go.name}: $it" }
                    }
                }
            }
            val follow2D = go.getAny<com.sengine.engine.core.Camera2D>()?.follow.orEmpty()
            val follow3D = go.getAny<com.sengine.engine.core.Camera3D>()?.follow.orEmpty()
            for (target in listOf(follow2D, follow3D).filter { it.isNotBlank() }) {
                if (scene.find(target) == null) errors += "Camera on '${go.name}' follows missing entity '$target'."
            }
        }
        if (scene.objects.mapNotNull { it.parent }.any { it !in scene.objects }) {
            errors += "Scene '${scene.name}' contains an entity parent reference outside this scene."
        }
        for (go in scene.objects) {
            val visited = HashSet<GameObject>()
            var parent = go.parent
            while (parent != null) {
                if (!visited.add(parent) || parent === go) {
                    errors += "Scene '${scene.name}' contains a hierarchy cycle involving '${go.name}'."
                    break
                }
                parent = parent.parent
            }
        }
        validateRawReferences(project, scene.name)?.let(errors::add)
        return errors.distinct()
    }

    private fun validateRawReferences(project: Project, sceneName: String): String? {
        if (!com.sengine.aibuilder.spec.GameSpecValidator.isSafeSceneName(sceneName)) return "Scene name '$sceneName' is not a safe identifier."
        val file = project.sceneFile(sceneName)
        if (!file.exists()) return "Scene file '${file.name}' is missing."
        if (file.length() > MAX_SCENE_FILE_BYTES) return "Scene '${sceneName}' exceeds the 8 MB safety limit."
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("objects") ?: return "Scene '${sceneName}' has no objects array."
            val ids = HashSet<Long>()
            val parents = LinkedHashMap<Long, Long>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: return "Scene '${sceneName}' object $i is not an object."
                val id = obj.optLong("id", Long.MIN_VALUE)
                if (id == Long.MIN_VALUE || !ids.add(id)) return "Scene '${sceneName}' has a missing or duplicate entity ID."
                if (obj.has("parent")) parents[id] = obj.optLong("parent", Long.MIN_VALUE)
            }
            for ((id, parent) in parents) if (parent !in ids) return "Scene '${sceneName}' entity $id refers to missing parent $parent."
            for (start in parents.keys) {
                val seen = HashSet<Long>()
                var id = start
                while (parents.containsKey(id)) {
                    if (!seen.add(id)) return "Scene '${sceneName}' contains a parent cycle at entity $id."
                    id = parents[id] ?: break
                }
            }
            null
        } catch (e: Exception) {
            "Scene '${sceneName}' is not valid JSON: ${e.message ?: "parse error"}"
        }
    }

    private fun assetExists(project: Project, name: String): Boolean {
        if (!com.sengine.aibuilder.spec.GameSpecValidator.isSafeAssetName(name)) return false
        return try {
            val root = project.assetsDir.canonicalFile
            val asset = File(root, name).canonicalFile
            asset.parentFile == root && asset.isFile
        } catch (_: Exception) { false }
    }

    private fun validateScript(project: Project, name: String): String? {
        if (!com.sengine.aibuilder.spec.GameSpecValidator.isSafeAssetName(name) || AssetKind.of(name) != AssetKind.SCRIPT) {
            return "script reference '$name' is not a safe .js or .bp asset name."
        }
        val file = project.assetFile(name)
        if (!file.isFile) return "script asset '$name' is missing."
        if (file.length() > MAX_SCRIPT_FILE_BYTES) return "script asset '$name' exceeds the 1 MB safety limit."
        val source = try { file.readText() } catch (_: Exception) { return "script asset '$name' could not be read." }
        var entered = false
        return try {
            val context = Context.enter()
            entered = true
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            val js = if (name.endsWith(".bp", ignoreCase = true)) {
                BlueprintCompiler.compile(Blueprint.parse(source))
            } else source
            context.compileString(js, name, 1, null)
            null
        } catch (e: Exception) {
            "script '$name' failed syntax/blueprint validation: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            if (entered) try { Context.exit() } catch (_: Exception) { }
        }
    }
}
