package com.sengine.engine.ai

import com.sengine.engine.core.*
import com.sengine.project.Project
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI Game Builder - Builds complete games from AI-generated designs
 * Handles scene creation, script generation, and asset management
 */
class AIGameBuilder(private val project: Project) {
    
    private val assistant = AIAssistant()
    
    /**
     * Build a complete game from AI generation result
     */
    fun buildGame(result: GameGenerationResult, callback: (Boolean, String) -> Unit) {
        try {
            // Step 1: Parse scene JSON and create scene
            val scene = parseSceneJson(result.sceneJson)
            
            // Step 2: Save all scripts
            saveScripts(result.scripts)
            
            // Step 3: Parse asset list
            val assetList = parseAssetList(result.assets)
            
            // Step 4: Save game design document
            saveDesignDocument(result.design)
            
            // Step 5: Save scene
            project.saveScene(scene)
            
            // Step 6: Create asset placeholder list
            createAssetManifest(assetList)
            
            callback(true, "Game built successfully!")
        } catch (e: Exception) {
            callback(false, "Error building game: ${e.message}")
        }
    }
    
    /**
     * Parse scene JSON into Scene object
     */
    private fun parseSceneJson(jsonString: String): Scene {
        // Clean JSON (remove markdown code blocks if present)
        val cleanJson = jsonString
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        
        val json = JSONObject(cleanJson)
        val sceneName = json.optString("name", "MainScene")
        val scene = Scene(sceneName)
        
        // Parse scene settings
        scene.gravityY = json.optDouble("gravityY", -9.81).toFloat()
        scene.gravityX = json.optDouble("gravityX", 0.0).toFloat()
        scene.ambient = parseColor(json.optString("ambient", "#FF4A505A"))
        
        val objects = json.optJSONArray("objects") ?: JSONArray()
        
        for (i in 0 until objects.length()) {
            val objJson = objects.getJSONObject(i)
            parseGameObject(objJson, scene)
        }
        
        return scene
    }
    
    /**
     * Parse a single game object from JSON
     */
    private fun parseGameObject(objJson: JSONObject, scene: Scene): GameObject {
        val name = objJson.optString("name", "GameObject")
        val go = scene.create(name)
        
        // Parse position
        val position = objJson.optJSONObject("position")
        if (position != null) {
            go.x = position.optDouble("x", 0.0).toFloat()
            go.y = position.optDouble("y", 0.0).toFloat()
        }
        
        // Parse scale
        val scale = objJson.optJSONObject("scale")
        if (scale != null) {
            go.scaleX = scale.optDouble("x", 1.0).toFloat()
            go.scaleY = scale.optDouble("y", 1.0).toFloat()
        }
        
        // Parse rotation
        go.rotation = objJson.optDouble("rotation", 0.0).toFloat()
        
        // Parse tag
        go.tag = objJson.optString("tag", "Untagged")
        
        // Parse components
        val components = objJson.optJSONArray("components") ?: JSONArray()
        for (i in 0 until components.length()) {
            val compJson = components.getJSONObject(i)
            parseComponent(compJson, go)
        }
        
        return go
    }
    
    /**
     * Parse a component from JSON and add to game object
     */
    private fun parseComponent(compJson: JSONObject, go: GameObject) {
        val type = compJson.optString("type", "")
        val properties = compJson.optJSONObject("properties") ?: JSONObject()
        
        when (type) {
            "SpriteRenderer" -> {
                val sprite = SpriteRenderer()
                sprite.texture = properties.optString("texture", "")
                sprite.color = parseColor(properties.optString("color", "#FFFFFFFF"))
                go.order = properties.optInt("sortingOrder", 0)
                sprite.flipX = properties.optBoolean("flipX", false)
                sprite.flipY = properties.optBoolean("flipY", false)
                sprite.pivotX = properties.optDouble("pivotX", 0.5).toFloat()
                sprite.pivotY = properties.optDouble("pivotY", 0.5).toFloat()
                go.add(sprite)
            }
            "TextRenderer" -> {
                val text = TextRenderer()
                text.text = properties.optString("text", "")
                text.size = properties.optDouble("size", 0.5).toFloat()
                text.color = parseColor(properties.optString("color", "#FFFFFFFF"))
                text.align = properties.optInt("align", 1)
                text.bold = properties.optBoolean("bold", false)
                go.add(text)
            }
            "Rigidbody2D" -> {
                val rb = Rigidbody2D()
                rb.bodyType = when (properties.optString("bodyType", "dynamic")) {
                    "kinematic" -> 1
                    "static" -> 2
                    else -> 0
                }
                rb.gravityScale = properties.optDouble("gravityScale", 1.0).toFloat()
                rb.mass = properties.optDouble("mass", 1.0).toFloat()
                rb.drag = properties.optDouble("drag", 0.0).toFloat()
                rb.freezeRotation = properties.optBoolean("freezeRotation", false)
                go.add(rb)
            }
            "Collider2D" -> {
                val col = Collider2D()
                col.shape = if (properties.optString("shape", "box") == "circle") 1 else 0
                col.width = properties.optDouble("width", 1.0).toFloat()
                col.height = properties.optDouble("height", 1.0).toFloat()
                col.radius = properties.optDouble("radius", 0.5).toFloat()
                col.offsetX = properties.optDouble("offsetX", 0.0).toFloat()
                col.offsetY = properties.optDouble("offsetY", 0.0).toFloat()
                col.isTrigger = properties.optBoolean("isTrigger", false)
                go.add(col)
            }
            "Script" -> {
                val script = ScriptComponent()
                script.script = properties.optString("scriptAsset", "")
                script.params = properties.optString("params", "")
                go.add(script)
            }
            "Camera" -> {
                val cam = Camera2D()
                cam.size = properties.optDouble("size", 5.0).toFloat()
                cam.background = parseColor(properties.optString("background", "#FF1B2533"))
                cam.follow = properties.optString("follow", "")
                cam.smoothing = properties.optDouble("smoothing", 5.0).toFloat()
                go.add(cam)
            }
            "AudioSource" -> {
                val audio = AudioSource()
                // AudioSource has no special properties in this engine
                go.add(audio)
            }
            "Animator" -> {
                val anim = Animator()
                anim.clip = properties.optString("clip", "")
                go.add(anim)
            }
        }
    }
    
    /**
     * Parse color string to Int
     */
    private fun parseColor(colorString: String): Int {
        return try {
            android.graphics.Color.parseColor(colorString)
        } catch (e: Exception) {
            0xFFFFFFFF.toInt()
        }
    }
    
    /**
     * Save all generated scripts to project
     */
    private fun saveScripts(scripts: Map<String, String>) {
        scripts.forEach { (name, code) ->
            val scriptFile = project.assetFile(name)
            scriptFile.writeText(code)
        }
    }
    
    /**
     * Parse asset list from JSON
     */
    private fun parseAssetList(jsonString: String): List<AssetInfo> {
        val cleanJson = jsonString
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        
        val assets = mutableListOf<AssetInfo>()
        
        try {
            val jsonArray = JSONArray(cleanJson)
            for (i in 0 until jsonArray.length()) {
                val assetJson = jsonArray.getJSONObject(i)
                val asset = AssetInfo(
                    name = assetJson.optString("name", ""),
                    type = assetJson.optString("type", "sprite"),
                    description = assetJson.optString("description", ""),
                    dimensions = assetJson.optString("dimensions", "")
                )
                assets.add(asset)
            }
        } catch (e: Exception) {
            // If parsing fails, return empty list
        }
        
        return assets
    }
    
    /**
     * Save game design document
     */
    private fun saveDesignDocument(design: String) {
        val designFile = project.assetFile("GameDesign.md")
        designFile.writeText(design)
    }
    
    /**
     * Create asset manifest file
     */
    private fun createAssetManifest(assets: List<AssetInfo>) {
        val manifestFile = project.assetFile("AssetManifest.json")
        val jsonArray = JSONArray()
        
        assets.forEach { asset ->
            val assetJson = JSONObject()
            assetJson.put("name", asset.name)
            assetJson.put("type", asset.type)
            assetJson.put("description", asset.description)
            assetJson.put("dimensions", asset.dimensions)
            assetJson.put("status", "placeholder")
            jsonArray.put(assetJson)
        }
        
        manifestFile.writeText(jsonArray.toString(2))
    }
    
    /**
     * Generate a simple game from text prompt
     */
    fun quickGenerate(prompt: String, genre: String, callback: (Boolean, String) -> Unit) {
        assistant.generateGame(prompt, genre) { result ->
            buildGame(result, callback)
        }
    }
}

/**
 * Information about a game asset
 */
data class AssetInfo(
    val name: String,
    val type: String, // sprite, sound, music
    val description: String,
    val dimensions: String
)
