package com.sengine.engine.prefab

import com.sengine.engine.core.*
import org.json.JSONArray
import org.json.JSONObject

// ─── Prefab System ────────────────────────────────────────────────────────

/**
 * Professional Prefab System — reusable game object templates.
 * Like Unity's Prefabs: define once, instance many, with override support.
 */

/** A prefab definition — a template for creating game object instances. */
class Prefab(
    var name: String = "",
    var description: String = "",
    var category: String = "General",
    var tags: MutableList<String> = mutableListOf(),
    /** The root object data. */
    var rootData: JSONObject = JSONObject(),
    /** Child object data list. */
    var childrenData: MutableList<JSONObject> = mutableListOf(),
    /** Whether this is a variant of another prefab. */
    var isVariant: Boolean = false,
    var basePrefabName: String = "",
) {
    /** Instantiate this prefab into a scene. */
    fun instantiate(scene: Scene, x: Float = 0f, y: Float = 0f, parent: GameObject? = null): GameObject {
        val go = scene.create(name, parent)
        go.x = x; go.y = y
        SceneSerializer.applyObjectJson(go, rootData)

        for (childData in childrenData) {
            val childName = childData.optString("name", "Child")
            val child = scene.create(childName, go)
            SceneSerializer.applyObjectJson(child, childData)
        }

        return go
    }

    /** Instantiate in 3D space. */
    fun instantiate3D(scene: Scene, x: Float = 0f, y: Float = 0f, z: Float = 0f, parent: GameObject? = null): GameObject {
        val go = instantiate(scene, x, y, parent)
        go.z = z
        return go
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("category", category)
            .put("isVariant", isVariant)
            .put("basePrefab", basePrefabName)
        o.put("tags", JSONArray(tags))
        o.put("root", rootData)
        o.put("children", JSONArray().also { arr -> childrenData.forEach { arr.put(it) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Prefab {
            val p = Prefab()
            p.name = o.optString("name", "")
            p.description = o.optString("description", "")
            p.category = o.optString("category", "General")
            p.isVariant = o.optBoolean("isVariant", false)
            p.basePrefabName = o.optString("basePrefab", "")
            val tags = o.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until tags.length()) p.tags.add(tags.getString(i))
            p.rootData = o.optJSONObject("root") ?: JSONObject()
            val children = o.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) p.childrenData.add(children.getJSONObject(i))
            return p
        }

        /** Create a prefab from an existing game object and its children. */
        fun createFromObject(scene: Scene, go: GameObject, name: String): Prefab {
            val prefab = Prefab(name = name)
            prefab.rootData = SceneSerializer.objectToJson(go)

            for (child in scene.childrenOf(go)) {
                prefab.childrenData.add(SceneSerializer.objectToJson(child))
            }

            return prefab
        }
    }
}

/** Manages all prefabs in a project. */
class PrefabLibrary {
    val prefabs = LinkedHashMap<String, Prefab>()

    fun register(prefab: Prefab) {
        prefabs[prefab.name] = prefab
    }

    fun get(name: String): Prefab? = prefabs[name]

    fun remove(name: String) {
        prefabs.remove(name)
    }

    fun listByCategory(): Map<String, List<Prefab>> {
        return prefabs.values.groupBy { it.category }
    }

    fun listByTag(tag: String): List<Prefab> {
        return prefabs.values.filter { tag in it.tags }
    }

    fun instantiate(name: String, scene: Scene, x: Float = 0f, y: Float = 0f): GameObject? {
        val prefab = get(name) ?: return null
        return prefab.instantiate(scene, x, y)
    }

    /** Save library to JSON. */
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("prefabs", JSONObject().also { j ->
            prefabs.forEach { (name, prefab) -> j.put(name, prefab.toJson()) }
        })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): PrefabLibrary {
            val lib = PrefabLibrary()
            val prefabs = o.optJSONObject("prefabs") ?: JSONObject()
            for (key in prefabs.keys()) {
                lib.register(Prefab.fromJson(prefabs.getJSONObject(key)))
            }
            return lib
        }
    }
}

/** Component that links a game object to its prefab source. */
class PrefabLink : Component() {
    override val type = "PrefabLink"
    var prefabName: String = ""
    var overrides: MutableMap<String, Any> = mutableMapOf() // Property overrides

    override fun props() = listOf(
        Prop.S("Prefab", { prefabName }, { prefabName = it }),
    )
}
