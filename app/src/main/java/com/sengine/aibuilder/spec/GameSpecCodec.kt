package com.sengine.aibuilder.spec

import org.json.JSONArray
import org.json.JSONObject

class GameSpecParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Strict, bounded JSON codec for GameSpec v1 and its fixed operation allowlist. */
object GameSpecCodec {
    private const val MAX_JSON_CHARS = 96_000
    private const val MAX_COMPONENT_PROPERTIES = 24

    fun parse(raw: String): GameSpec {
        if (raw.length > MAX_JSON_CHARS) throw GameSpecParseException("GameSpec response is too large")
        val jsonText = extractJsonObject(raw)
        val root = try { JSONObject(jsonText) } catch (e: Exception) {
            throw GameSpecParseException("The AI response is not valid JSON: ${e.message ?: "parse error"}", e)
        }
        try {
            val version = requiredInt(root, "schemaVersion")
            val action = GameAction.fromWire(requiredString(root, "action"))
                ?: throw GameSpecParseException("Unknown action. Expected create, edit, or explain.")
            val dimension = GameDimension.fromWire(requiredString(root, "dimension"))
                ?: throw GameSpecParseException("Unknown dimension. Expected 2d or 3d.")
            val mechanics = stringArray(root.optJSONArray("mechanics"), "mechanics", 16, 160)
            val rawOps = root.optJSONArray("operations") ?: JSONArray()
            if (rawOps.length() > MAX_GAME_OPERATIONS) {
                throw GameSpecParseException("GameSpec has more than $MAX_GAME_OPERATIONS operations")
            }
            val operations = (0 until rawOps.length()).map { index ->
                val value = rawOps.opt(index)
                if (value !is JSONObject) throw GameSpecParseException("operations[$index] must be an object")
                parseOperation(value, index)
            }
            return GameSpec(
                schemaVersion = version,
                action = action,
                projectName = requiredString(root, "projectName"),
                genre = requiredString(root, "genre"),
                dimension = dimension,
                templateId = root.optString("templateId", ""),
                summary = requiredString(root, "summary"),
                mechanics = mechanics,
                operations = operations,
            )
        } catch (e: GameSpecParseException) {
            throw e
        } catch (e: Exception) {
            throw GameSpecParseException("GameSpec is missing a required value or has the wrong type: ${e.message ?: "invalid value"}", e)
        }
    }

    fun toJson(spec: GameSpec): JSONObject = JSONObject().apply {
        put("schemaVersion", spec.schemaVersion)
        put("action", spec.action.wireName)
        put("projectName", spec.projectName)
        put("genre", spec.genre)
        put("dimension", spec.dimension.wireName)
        put("templateId", spec.templateId)
        put("summary", spec.summary)
        put("mechanics", JSONArray().also { array -> spec.mechanics.forEach { array.put(it) } })
        put("operations", JSONArray().also { array -> spec.operations.forEach { array.put(operationToJson(it)) } })
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val withoutFence = trimmed
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        if (withoutFence.startsWith("{") && withoutFence.endsWith("}")) return withoutFence
        val start = trimmed.indexOf('{')
        if (start < 0) throw GameSpecParseException("The AI response did not contain a JSON GameSpec")
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until trimmed.length) {
            val c = trimmed[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return trimmed.substring(start, i + 1)
                }
            }
        }
        throw GameSpecParseException("The AI response contained an incomplete JSON object")
    }

    private fun parseOperation(o: JSONObject, index: Int): GameOperation {
        val kind = requiredString(o, "type")
        val scene = o.optString("scene", "start")
        return try {
            when (kind) {
                "add_entity" -> GameOperation.AddEntity(
                    scene = scene,
                    name = requiredString(o, "name"),
                    x = number(o, "x", 0f), y = number(o, "y", 0f), z = number(o, "z", 0f),
                    rotation = number(o, "rotation", 0f), scaleX = number(o, "scaleX", 1f),
                    scaleY = number(o, "scaleY", 1f), scaleZ = number(o, "scaleZ", 1f),
                    tag = o.optString("tag", "Untagged"),
                    components = componentArray(o.optJSONArray("components"), index),
                )
                "set_transform" -> GameOperation.SetTransform(
                    scene = scene, objectName = requiredString(o, "objectName"),
                    x = optionalNumber(o, "x"), y = optionalNumber(o, "y"), z = optionalNumber(o, "z"),
                    rotation = optionalNumber(o, "rotation"), rotX = optionalNumber(o, "rotX"),
                    rotY = optionalNumber(o, "rotY"), scaleX = optionalNumber(o, "scaleX"),
                    scaleY = optionalNumber(o, "scaleY"), scaleZ = optionalNumber(o, "scaleZ"),
                )
                "add_component" -> GameOperation.AddComponent(
                    scene, requiredString(o, "objectName"), parseComponent(o.optJSONObject("component")
                        ?: throw GameSpecParseException("operations[$index].component must be an object"), index),
                )
                "set_component_property" -> GameOperation.SetComponentProperty(
                    scene, requiredString(o, "objectName"), requiredString(o, "componentType"),
                    requiredString(o, "property"), scalar(o, "value"),
                )
                "set_script_parameter" -> GameOperation.SetScriptParameter(
                    scene = scene, objectName = requiredString(o, "objectName"),
                    key = requiredString(o, "key"), value = number(o, "value", Float.NaN),
                    scriptAsset = o.optString("scriptAsset", "").takeIf { it.isNotBlank() },
                )
                "rename_entity" -> GameOperation.RenameEntity(
                    scene, requiredString(o, "objectName"), requiredString(o, "newName"),
                )
                "set_scene_gravity" -> GameOperation.SetSceneGravity(
                    scene, optionalNumber(o, "gravityX"), optionalNumber(o, "gravityY"),
                    optionalNumber(o, "gravityZ"), optionalNumber(o, "gravity3D"),
                )
                "add_hud_text" -> GameOperation.AddHudText(
                    scene = scene, name = requiredString(o, "name"), text = requiredString(o, "text"),
                    x = number(o, "x", 0f), y = number(o, "y", 3.5f),
                    size = number(o, "size", 0.45f), color = o.optString("color", "#FFFFFFFF"),
                    order = optionalInt(o, "order") ?: 100,
                )
                else -> throw GameSpecParseException("operations[$index] uses unsupported type '$kind'")
            }
        } catch (e: GameSpecParseException) {
            throw e
        } catch (e: Exception) {
            throw GameSpecParseException("operations[$index] is malformed: ${e.message ?: "invalid value"}", e)
        }
    }

    private fun componentArray(array: JSONArray?, operationIndex: Int): List<ComponentSpec> {
        if (array == null) return emptyList()
        if (array.length() > MAX_COMPONENTS_PER_ENTITY) {
            throw GameSpecParseException("operations[$operationIndex] has more than $MAX_COMPONENTS_PER_ENTITY components")
        }
        return (0 until array.length()).map { i ->
            val item = array.opt(i) as? JSONObject
                ?: throw GameSpecParseException("operations[$operationIndex].components[$i] must be an object")
            parseComponent(item, operationIndex)
        }
    }

    private fun parseComponent(o: JSONObject, operationIndex: Int): ComponentSpec {
        val type = requiredString(o, "type")
        val props = o.optJSONObject("properties") ?: JSONObject()
        if (props.length() > MAX_COMPONENT_PROPERTIES) {
            throw GameSpecParseException("operations[$operationIndex] component has too many properties")
        }
        val values = LinkedHashMap<String, SpecValue>()
        val keys = props.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            values[key] = scalar(props, key)
        }
        return ComponentSpec(type, values)
    }

    private fun scalar(o: JSONObject, key: String): SpecValue {
        if (!o.has(key)) throw GameSpecParseException("Missing property '$key'")
        return when (val raw = o.get(key)) {
            is String -> SpecValue.Text(raw)
            is Boolean -> SpecValue.Bool(raw)
            is Number -> SpecValue.Number(raw.toDouble())
            else -> throw GameSpecParseException("Property '$key' must be a string, number, or boolean")
        }
    }

    private fun requiredString(o: JSONObject, key: String): String {
        val value = o.opt(key)
        if (value !is String || value.isBlank()) throw GameSpecParseException("'$key' must be a non-empty string")
        return value
    }

    private fun requiredInt(o: JSONObject, key: String): Int {
        val raw = o.opt(key)
        if (raw !is Number || raw.toDouble() % 1.0 != 0.0) throw GameSpecParseException("'$key' must be an integer")
        return raw.toInt()
    }

    private fun number(o: JSONObject, key: String, fallback: Float): Float =
        if (!o.has(key)) fallback else optionalNumber(o, key)
            ?: throw GameSpecParseException("'$key' must be a number")

    private fun optionalNumber(o: JSONObject, key: String): Float? {
        if (!o.has(key)) return null
        val raw = o.opt(key)
        if (raw !is Number) throw GameSpecParseException("'$key' must be a number")
        val value = raw.toDouble()
        if (!value.isFinite() || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
            throw GameSpecParseException("'$key' must be finite and representable")
        }
        return value.toFloat()
    }

    private fun optionalInt(o: JSONObject, key: String): Int? {
        if (!o.has(key)) return null
        val raw = o.opt(key)
        if (raw !is Number || raw.toDouble() % 1.0 != 0.0) throw GameSpecParseException("'$key' must be an integer")
        return raw.toInt()
    }

    private fun stringArray(array: JSONArray?, field: String, maxItems: Int, maxLength: Int): List<String> {
        if (array == null) return emptyList()
        if (array.length() > maxItems) throw GameSpecParseException("'$field' has too many entries")
        return (0 until array.length()).map { i ->
            val value = array.opt(i) as? String ?: throw GameSpecParseException("'$field[$i]' must be a string")
            if (value.length > maxLength) throw GameSpecParseException("'$field[$i]' is too long")
            value
        }
    }

    private fun operationToJson(operation: GameOperation): JSONObject = JSONObject().apply {
        put("scene", operation.scene)
        when (operation) {
            is GameOperation.AddEntity -> {
                put("type", "add_entity"); put("name", operation.name); put("tag", operation.tag)
                put("x", operation.x.toDouble()); put("y", operation.y.toDouble()); put("z", operation.z.toDouble())
                put("rotation", operation.rotation.toDouble()); put("scaleX", operation.scaleX.toDouble())
                put("scaleY", operation.scaleY.toDouble()); put("scaleZ", operation.scaleZ.toDouble())
                put("components", JSONArray().also { array -> operation.components.forEach { array.put(componentToJson(it)) } })
            }
            is GameOperation.SetTransform -> {
                put("type", "set_transform"); put("objectName", operation.objectName)
                putOptional("x", operation.x); putOptional("y", operation.y); putOptional("z", operation.z)
                putOptional("rotation", operation.rotation); putOptional("rotX", operation.rotX); putOptional("rotY", operation.rotY)
                putOptional("scaleX", operation.scaleX); putOptional("scaleY", operation.scaleY); putOptional("scaleZ", operation.scaleZ)
            }
            is GameOperation.AddComponent -> {
                put("type", "add_component"); put("objectName", operation.objectName)
                put("component", componentToJson(operation.component))
            }
            is GameOperation.SetComponentProperty -> {
                put("type", "set_component_property"); put("objectName", operation.objectName)
                put("componentType", operation.componentType); put("property", operation.property)
                put("value", valueToJson(operation.value))
            }
            is GameOperation.SetScriptParameter -> {
                put("type", "set_script_parameter"); put("objectName", operation.objectName)
                put("key", operation.key); put("value", operation.value.toDouble())
                operation.scriptAsset?.let { put("scriptAsset", it) }
            }
            is GameOperation.RenameEntity -> {
                put("type", "rename_entity"); put("objectName", operation.objectName); put("newName", operation.newName)
            }
            is GameOperation.SetSceneGravity -> {
                put("type", "set_scene_gravity"); putOptional("gravityX", operation.gravityX)
                putOptional("gravityY", operation.gravityY); putOptional("gravityZ", operation.gravityZ)
                putOptional("gravity3D", operation.gravity3D)
            }
            is GameOperation.AddHudText -> {
                put("type", "add_hud_text"); put("name", operation.name); put("text", operation.text)
                put("x", operation.x.toDouble()); put("y", operation.y.toDouble()); put("size", operation.size.toDouble())
                put("color", operation.color); put("order", operation.order)
            }
        }
    }

    private fun componentToJson(component: ComponentSpec) = JSONObject().apply {
        put("type", component.type)
        put("properties", JSONObject().also { props -> component.properties.forEach { (key, value) -> props.put(key, valueToJson(value)) } })
    }

    private fun valueToJson(value: SpecValue): Any = when (value) {
        is SpecValue.Text -> value.value
        is SpecValue.Number -> value.value
        is SpecValue.Bool -> value.value
    }

    private fun JSONObject.putOptional(key: String, value: Float?) {
        if (value != null) put(key, value.toDouble())
    }
}
