package com.sengine.aibuilder.engine

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.Prop
import com.sengine.engine.core.ScriptComponent
import com.sengine.project.Project
import org.json.JSONArray
import org.json.JSONObject

/** A small, freshly-read project snapshot. Absolute paths and source-code contents are never included. */
object GameProjectInspector {
    private const val MAX_SCENES = 12
    private const val MAX_OBJECTS_PER_SCENE = 60
    private const val MAX_COMPONENTS_PER_ENTITY = 12
    private const val MAX_PROPERTIES_PER_COMPONENT = 24
    private const val MAX_ASSETS = 80
    private const val MAX_SNAPSHOT_CHARS = 10_000

    fun snapshot(project: Project): JSONObject {
        val allScenes = project.listScenes()
        val sceneNames = (listOf(project.startScene) + allScenes.filter { it != project.startScene })
            .distinct().take(MAX_SCENES)
        val scenes = JSONArray()
        var truncated = allScenes.size > MAX_SCENES || project.listAssets().size > MAX_ASSETS
        for (sceneName in sceneNames) {
            val sceneFile = project.sceneFile(sceneName)
            if (!sceneFile.isFile || sceneFile.length() > GameSceneValidator.MAX_SCENE_FILE_BYTES) {
                truncated = true
                continue
            }
            val scene = try { project.loadScene(sceneName) } catch (_: Exception) { truncated = true; continue }
            if (scene.objects.size > MAX_OBJECTS_PER_SCENE) truncated = true
            val objects = JSONArray()
            for (go in scene.objects.take(MAX_OBJECTS_PER_SCENE)) {
                val components = JSONArray()
                if (go.components.size > MAX_COMPONENTS_PER_ENTITY) truncated = true
                for (component in go.components.take(MAX_COMPONENTS_PER_ENTITY)) {
                    val item = JSONObject().put("type", component.type)
                    val properties = JSONObject()
                    val componentProperties = component.props()
                    if (componentProperties.size > MAX_PROPERTIES_PER_COMPONENT) truncated = true
                    for (prop in componentProperties.take(MAX_PROPERTIES_PER_COMPONENT)) {
                        when (prop) {
                            is Prop.F -> if (prop.get().isFinite()) properties.put(prop.name, prop.get().toDouble())
                            is Prop.I -> properties.put(prop.name, prop.get())
                            is Prop.B -> properties.put(prop.name, prop.get())
                            is Prop.Choice -> properties.put(prop.name, prop.options.getOrElse(prop.get()) { "" })
                            is Prop.Asset -> if (prop.get().isNotBlank()) properties.put(prop.name, prop.get())
                            is Prop.Color -> properties.put(prop.name, String.format("#%08X", prop.get()))
                            is Prop.S -> Unit // user-authored text/params are not placed in provider context
                        }
                    }
                    if (component is ScriptComponent) {
                        item.put("scriptAsset", component.script)
                        val safeParams = JSONObject()
                        for (part in component.params.split(',', '\n', ';')) {
                            val kv = part.split('=', limit = 2)
                            if (kv.size == 2 && kv[0].trim() in setOf("speed", "jump", "jumpHeight", "health", "maxHealth", "damage", "fireRate", "range", "gravity")) {
                                kv[1].trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { safeParams.put(kv[0].trim(), it) }
                            }
                        }
                        if (safeParams.length() > 0) item.put("safeNumericParams", safeParams)
                    }
                    components.put(item.put("properties", properties))
                }
                objects.put(JSONObject()
                    .put("name", go.name)
                    .put("tag", go.tag)
                    .put("active", go.active)
                    .put("position", JSONArray().put(go.x.toDouble()).put(go.y.toDouble()).put(go.z.toDouble()))
                    .put("components", components))
            }
            scenes.put(JSONObject()
                .put("name", scene.name)
                .put("objectCount", scene.objects.size)
                .put("gravity", JSONArray().put(scene.gravityX.toDouble()).put(scene.gravityY.toDouble()).put(scene.gravityZ.toDouble()).put(scene.gravity3D.toDouble()))
                .put("entities", objects))
        }
        val assets = JSONArray()
        for (name in project.listAssets().take(MAX_ASSETS)) {
            val file = project.assetFile(name)
            val kind = AssetKind.of(name)?.name?.lowercase() ?: "unknown"
            assets.put(JSONObject().put("name", name).put("kind", kind).put("bytes", file.length().coerceAtLeast(0)))
        }
        val result = JSONObject()
            .put("projectName", project.name)
            .put("startScene", project.startScene)
            .put("orientation", if (project.orientation == 1) "portrait" else "landscape")
            .put("controlScheme", project.controlScheme)
            .put("scenes", scenes)
            .put("assets", assets)
            .put("truncated", truncated)
        trimToBudget(result)
        return result
    }

    /** Keep project metadata bounded before it is sent directly to the selected provider. */
    private fun trimToBudget(snapshot: JSONObject) {
        val sceneArray = snapshot.optJSONArray("scenes") ?: return
        val assetArray = snapshot.optJSONArray("assets") ?: JSONArray()
        while (snapshot.toString().length > MAX_SNAPSHOT_CHARS) {
            snapshot.put("truncated", true)
            val start = sceneArray.optJSONObject(0)
            val startEntities = start?.optJSONArray("entities") ?: JSONArray()
            when {
                sceneArray.length() > 1 -> sceneArray.remove(sceneArray.length() - 1)
                assetArray.length() > 12 -> assetArray.remove(assetArray.length() - 1)
                startEntities.length() > 0 -> startEntities.remove(startEntities.length() - 1)
                assetArray.length() > 0 -> assetArray.remove(assetArray.length() - 1)
                else -> break
            }
        }
    }

    fun summarize(project: Project): String {
        val snapshot = snapshot(project)
        val scenes = snapshot.optJSONArray("scenes") ?: JSONArray()
        val assets = snapshot.optJSONArray("assets") ?: JSONArray()
        val counts = (0 until scenes.length()).mapNotNull { scenes.optJSONObject(it) }.joinToString { scene ->
            "${scene.optString("name")}: ${scene.optInt("objectCount")} entities"
        }
        return "${project.name}; start scene '${project.startScene}'; ${scenes.length()} scene(s) [$counts]; ${assets.length()} listed asset(s)."
    }
}
