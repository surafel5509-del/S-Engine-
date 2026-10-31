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
    /** Empty means a legacy project: infer from scene cameras instead of rewriting its data. */
    var dimension = ""
    var templateId = ""
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
                dimension = o.optString("dimension", "")
                templateId = o.optString("templateId", "")
            } catch (_: Exception) {
            }
        }
    }

    fun saveMeta() {
        dir.mkdirs(); assetsDir.mkdirs(); scenesDir.mkdirs()
        val o = JSONObject()
        o.put("name", name)
        o.put("engine", "S Engine Studio 3.0")
        o.put("schemaVersion", 3)
        o.put("dimension", dimension)
        o.put("templateId", templateId)
        o.put("startScene", startScene)
        o.put("created", created)
        o.put("orientation", orientation)
        o.put("controlScheme", controlScheme)
        AtomicFiles.writeText(metaFile, o.toString(2))
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
        AtomicFiles.writeText(sceneFile(scene.name), SceneSerializer.toJson(scene).toString(2))
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
        AtomicFiles.writeText(assetFile(n), text)
    }

    /** Binary asset write (imported images, fonts, models, generated PNGs). */
    fun writeAssetBytes(n: String, bytes: ByteArray) {
        AtomicFiles.writeBytes(assetFile(n), bytes)
    }

    fun readAssetBytes(n: String): ByteArray? = assetFile(n).takeIf { it.isFile }?.readBytes()

    private val materialStore by lazy { MaterialStore(this) }
    fun materials(): MaterialStore = materialStore

    fun reloadMaterials() {
        materialStore.clear()
    }

    // ---------------------------------------------------------------- texture pipeline

    private val textureSettingsFile = File(dir, "textures.json")
    private var textureSettingsCache: TextureSettingsStore? = null

    /** Import settings for every texture asset (filter, wrap, mipmaps, max size). */
    fun textureSettings(): TextureSettingsStore {
        textureSettingsCache?.let { return it }
        val store = TextureSettingsStore()
        if (textureSettingsFile.isFile) store.loadFromText(textureSettingsFile.readText())
        textureSettingsCache = store
        return store
    }

    fun saveTextureSettings() {
        val store = textureSettingsCache ?: return
        dir.mkdirs()
        AtomicFiles.writeText(textureSettingsFile, store.saveText())
    }

    // ---------------------------------------------------------------- sprite studio documents

    /** Editable `.sprite` documents created by Sprite Studio (not runtime assets). */
    val spritesDir = File(dir, "sprites")

    fun spriteFile(n: String) = File(spritesDir, if (n.endsWith(".sprite")) n else "$n.sprite")

    fun listSprites(): List<String> =
        (spritesDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".sprite") }
            .map { it.name.removeSuffix(".sprite") }
            .sorted()

    fun spriteExists(n: String) = spriteFile(n).isFile

    fun saveSprite(doc: com.sengine.engine.sprite.SpriteDocument) {
        spritesDir.mkdirs()
        spriteFile(doc.name).writeText(doc.toJsonText())
    }

    fun loadSprite(n: String): com.sengine.engine.sprite.SpriteDocument? {
        val f = spriteFile(n)
        if (!f.isFile) return null
        return try {
            com.sengine.engine.sprite.SpriteDocument.fromJsonText(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun deleteSprite(n: String) = spriteFile(n).delete()

    /** Unique sprite document name (`hero`, `hero_2`, ...). */
    fun uniqueSpriteName(base: String): String {
        val clean = base.trim().ifBlank { "Sprite" }
        if (!spriteExists(clean)) return clean
        var i = 2
        while (spriteExists("${clean}_$i")) i++
        return "${clean}_$i"
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
