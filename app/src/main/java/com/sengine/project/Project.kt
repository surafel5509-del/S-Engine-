package com.sengine.project

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import org.json.JSONObject
import java.io.File

/** A game project stored in app-private storage. */
class Project(val dir: File) {
    val name: String get() = dir.name
    val assetsDir = File(dir, "assets")
    val scenesDir = File(dir, "scenes")
    private val metaFile = File(dir, "project.json")

    var startScene = "Main"
    var created = System.currentTimeMillis()
    var orientation = 0 // 0 landscape, 1 portrait
    /** Preferred runtime overlay: "classic", "drive", or "driveTilt" (drive pedals plus accelerometer balance). */
    var controlScheme = "classic"

    private val userDataLock = Any()
    private val userDataFile = File(dir, ".sengine_user_data.json")

    init {
        if (metaFile.exists()) {
            try {
                val o = JSONObject(metaFile.readText())
                startScene = o.optString("startScene", "Main")
                created = o.optLong("created", created)
                orientation = o.optInt("orientation", 0)
                controlScheme = o.optString("controlScheme", "classic")
            } catch (_: Exception) {
            }
        }
    }

    fun saveMeta() {
        dir.mkdirs(); assetsDir.mkdirs(); scenesDir.mkdirs()
        val o = JSONObject()
        o.put("name", name)
        o.put("engine", "S Engine 1.0")
        o.put("startScene", startScene)
        o.put("created", created)
        o.put("orientation", orientation)
        o.put("controlScheme", controlScheme)
        metaFile.writeText(o.toString(2))
    }

    /** Small project-scoped save store used by games for wallets, upgrades and high scores. */
    fun userData(key: String): Any? = synchronized(userDataLock) {
        if (!userDataFile.exists()) return@synchronized null
        try {
            val value = JSONObject(userDataFile.readText()).opt(key)
            if (value == null || value === JSONObject.NULL) null else value
        } catch (_: Exception) {
            null
        }
    }

    fun putUserData(key: String, value: Any?) {
        synchronized(userDataLock) {
            val data = try {
                if (userDataFile.exists()) JSONObject(userDataFile.readText()) else JSONObject()
            } catch (_: Exception) { JSONObject() }
            data.put(key, value ?: JSONObject.NULL)
            dir.mkdirs()
            val temp = File(dir, ".sengine_user_data.tmp")
            temp.writeText(data.toString())
            if (!temp.renameTo(userDataFile)) {
                userDataFile.writeText(data.toString())
                temp.delete()
            }
        }
    }

    fun sceneFile(n: String) = File(scenesDir, "$n.scene.json")
    fun sceneExists(n: String) = sceneFile(n).exists()

    fun listScenes(): List<String> =
        (scenesDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".scene.json") }
            .map { it.name.removeSuffix(".scene.json") }
            .sorted()

    fun loadScene(n: String): Scene {
        val f = sceneFile(n)
        if (!f.exists()) return Scene(n)
        return SceneSerializer.fromJson(JSONObject(f.readText())).also { it.name = n }
    }

    fun saveScene(scene: Scene) {
        scenesDir.mkdirs()
        sceneFile(scene.name).writeText(SceneSerializer.toJson(scene).toString(1))
    }

    fun deleteScene(n: String) = sceneFile(n).delete()

    fun assetFile(n: String) = File(assetsDir, n)

    fun listAssets(kind: AssetKind? = null): List<String> =
        (assetsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && (kind == null || AssetKind.of(it.name) == kind) }
            .map { it.name }
            .sortedWith(compareBy({ AssetKind.of(it)?.ordinal ?: 9 }, { it.lowercase() }))

    fun readAsset(n: String): String? = assetFile(n).takeIf { it.exists() }?.readText()

    fun writeAsset(n: String, text: String) {
        assetsDir.mkdirs(); assetFile(n).writeText(text)
    }

    fun uniqueAssetName(base: String): String {
        if (!assetFile(base).exists()) return base
        val stem = base.substringBeforeLast('.')
        val ext = base.substringAfterLast('.', "")
        var i = 1
        while (assetFile("${stem}_$i.$ext").exists()) i++
        return "${stem}_$i.$ext"
    }
}
