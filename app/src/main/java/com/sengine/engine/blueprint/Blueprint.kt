package com.sengine.engine.blueprint

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import org.json.JSONArray
import org.json.JSONObject

/**
 * Visual Scripting / Blueprint System
 *
 * Professional node-based visual programming system that allows
 * non-programmers to create game logic by connecting nodes.
 *
 * Features:
 * - Event nodes (Start, Update, Collision, Trigger, Tap, etc.)
 * - Flow control (If, Switch, Loop, Delay, Sequence)
 * - Math operations
 * - Variable management (local and global)
 * - Scene manipulation (spawn, destroy, find, move)
 * - Component property access
 * - Animation control
 * - UI manipulation
 * - Audio playback
 * - Custom function nodes
 */

enum class NodeCategory {
    Event, FlowControl, Math, Variables, Scene, Transform, Component,
    Animation, UI, Audio, Physics, Input, Time, Custom, Logic
}

/** A visual scripting node with input/output pins. */

/** Parameter definition for a node. */
data class ParamDef(val name: String, val str: Boolean = false, val type: Int = 0)

/** Node definition with metadata. */
data class NodeDef(
    val name: String,
    val category: Int = 0,
    val hasIn: Boolean = true,
    val outs: List<String> = listOf("Out"),
    val ins: List<String> = listOf("In"),
    val params: List<ParamDef> = emptyList(),
    val type: Int = 0
)

class BlueprintNode(
    var id: Int = 0,
    var name: String = "",
    var category: NodeCategory = NodeCategory.Event,
    var x: Float = 0f,
    var y: Float = 0f,
    val inputs: MutableList<Pin> = mutableListOf(),
    val outputs: MutableList<Pin> = mutableListOf(),
    var properties: MutableMap<String, Any> = mutableMapOf(),
    var comment: String = "",
    var collapsed: Boolean = false,
    var def: NodeDef? = null,
) {
    fun inputPin(name: String): Pin? = inputs.firstOrNull { it.name == name }
    fun outputPin(name: String): Pin? = outputs.firstOrNull { it.name == name }
    fun param(name: String): String = properties[name]?.toString() ?: ""

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id).put("name", name).put("category", category.ordinal)
            .put("x", x.toDouble()).put("y", y.toDouble())
            .put("comment", comment).put("collapsed", collapsed)
        o.put("inputs", JSONArray().also { arr -> inputs.forEach { arr.put(it.toJson()) } })
        o.put("outputs", JSONArray().also { arr -> outputs.forEach { arr.put(it.toJson()) } })
        o.put("properties", JSONObject().also { j ->
            properties.forEach { (k, v) ->
                when (v) {
                    is Float -> j.put(k, v.toDouble())
                    is Int -> j.put(k, v)
                    is Boolean -> j.put(k, v)
                    is String -> j.put(k, v)
                }
            }
        })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): BlueprintNode {
            val n = BlueprintNode(
                o.optInt("id", 0), o.optString("name", ""),
                NodeCategory.entries.getOrElse(o.optInt("category", 0)) { NodeCategory.Event },
                o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(),
                mutableListOf(), mutableListOf(), mutableMapOf(),
                o.optString("comment", ""), o.optBoolean("collapsed", false)
            )
            val ins = o.optJSONArray("inputs") ?: JSONArray()
            for (i in 0 until ins.length()) n.inputs.add(Pin.fromJson(ins.getJSONObject(i)))
            val outs = o.optJSONArray("outputs") ?: JSONArray()
            for (i in 0 until outs.length()) n.outputs.add(Pin.fromJson(outs.getJSONObject(i)))
            val props = o.optJSONObject("properties") ?: JSONObject()
            for (k in props.keys()) {
                val v = props.get(k)
                n.properties[k] = v
            }
            return n
        }
    }
}

/** A pin on a node — either input or output, with a type. */
class Pin(
    var name: String = "",
    var type: PinType = PinType.Flow,
    var defaultValue: Any? = null,
    var connected: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("type", type.ordinal)
        .put("default", defaultValue?.toString() ?: "")

    companion object {
        fun fromJson(o: JSONObject): Pin = Pin(
            o.optString("name", ""),
            PinType.entries.getOrElse(o.optInt("type", 0)) { PinType.Flow },
            o.optString("default", "")
        )
    }
}

enum class PinType {
    Flow,      // Execution flow
    Boolean, Float, Int, String, Vector2, Vector3, Color, Object, Any
}

/** A connection between two pins. */
class BlueprintConnection(
    var fromNodeId: Int = 0,
    var fromPinName: String = "",
    var toNodeId: Int = 0,
    var toPinName: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fromNode", fromNodeId).put("fromPin", fromPinName)
        .put("toNode", toNodeId).put("toPin", toPinName)

    companion object {
        fun fromJson(o: JSONObject): BlueprintConnection = BlueprintConnection(
            o.optInt("fromNode", 0), o.optString("fromPin", ""),
            o.optInt("toNode", 0), o.optString("toPin", "")
        )
    }
}


class Blueprint(
    var name: String = "",
    var description: String = "",
    val nodes: MutableList<BlueprintNode> = mutableListOf(),
    val connections: MutableList<BlueprintConnection> = mutableListOf(),
    val variables: MutableList<BlueprintVariable> = mutableListOf(),
    var nextNodeId: Int = 0,
) {
    val links: MutableList<BlueprintConnection> get() = connections
    fun createNode(name: String, category: NodeCategory, x: Float = 0f, y: Float = 0f): BlueprintNode {
        val node = BlueprintNode(nextNodeId++, name, category, x, y)
        nodes.add(node)
        return node
    }

    /** Convenience method: add a node by name with position. */
    fun add(name: String, x: Float = 0f, y: Float = 0f): BlueprintNode {
        return createNode(name, NodeCategory.Event, x, y)
    }

    /** Convenience overload: connect with just from/to (uses "out" pin). */
    fun connect(fromNode: Int, pinName: String, toNode: Int) {
        connect(fromNode, pinName, toNode, "in")
    }


    fun connect(fromNode: Int, fromPin: String, toNode: Int, toPin: String) {
        connections.add(BlueprintConnection(fromNode, fromPin, toNode, toPin))
    }

    fun addVariable(name: String, type: PinType, defaultValue: Any? = null): BlueprintVariable {
        val v = BlueprintVariable(name, type, defaultValue)
        variables.add(v)
        return v
    }

    /** Generate JavaScript code from the blueprint. */
    fun toJavaScript(): String {
        val sb = StringBuilder()
        sb.appendLine("// Generated from Blueprint: $name")
        sb.appendLine("// Description: $description")
        sb.appendLine()

        // Variables
        for (v in variables) {
            sb.appendLine("var ${v.name} = ${v.defaultValue ?: "null"};")
        }
        sb.appendLine()

        // Find event nodes and generate functions
        val eventNodes = nodes.filter { it.category == NodeCategory.Event }
        for (event in eventNodes) {
            when (event.name) {
                "On Start" -> {
                    sb.appendLine("function start() {")
                    generateFlow(event.id, sb, 1)
                    sb.appendLine("}")
                }
                "On Update" -> {
                    sb.appendLine("function update(dt) {")
                    generateFlow(event.id, sb, 1)
                    sb.appendLine("}")
                }
                "On Collision" -> {
                    sb.appendLine("function onCollision(other) {")
                    generateFlow(event.id, sb, 1)
                    sb.appendLine("}")
                }
                "On Trigger" -> {
                    sb.appendLine("function onTrigger(other) {")
                    generateFlow(event.id, sb, 1)
                    sb.appendLine("}")
                }
                "On Tap" -> {
                    sb.appendLine("function onTap() {")
                    generateFlow(event.id, sb, 1)
                    sb.appendLine("}")
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun generateFlow(startNodeId: Int, sb: StringBuilder, indent: Int) {
        val prefix = "    ".repeat(indent)
        // Find connections from this node's "Out" pin
        val outgoing = connections.filter { it.fromNodeId == startNodeId && it.fromPinName == "Out" }
        for (conn in outgoing) {
            val targetNode = nodes.firstOrNull { it.id == conn.toNodeId } ?: continue
            when (targetNode.category) {
                NodeCategory.Math -> {
                    val op = targetNode.properties["operation"]?.toString() ?: "+"
                    val a = targetNode.properties["a"]?.toString() ?: "0"
                    val b = targetNode.properties["b"]?.toString() ?: "0"
                    sb.appendLine("${prefix}var result = $a $op $b;")
                }
                NodeCategory.Scene -> {
                    when (targetNode.name) {
                        "Destroy" -> sb.appendLine("${prefix}self.destroy();")
                        "Spawn" -> {
                            val prefab = targetNode.properties["prefab"]?.toString() ?: "\"\""
                            val x = targetNode.properties["x"]?.toString() ?: "0"
                            val y = targetNode.properties["y"]?.toString() ?: "0"
                            sb.appendLine("${prefix}scene.spawn($prefab, $x, $y);")
                        }
                        "Load Scene" -> {
                            val name = targetNode.properties["scene"]?.toString() ?: "\"\""
                            sb.appendLine("${prefix}scene.load($name);")
                        }
                    }
                }
                NodeCategory.Transform -> {
                    val prop = targetNode.properties["property"]?.toString() ?: "x"
                    val value = targetNode.properties["value"]?.toString() ?: "0"
                    sb.appendLine("${prefix}self.$prop = $value;")
                }
                NodeCategory.Audio -> {
                    sb.appendLine("${prefix}audio.beep();")
                }
                NodeCategory.FlowControl -> {
                    when (targetNode.name) {
                        "If" -> {
                            val condition = targetNode.properties["condition"]?.toString() ?: "true"
                            sb.appendLine("${prefix}if ($condition) {")
                            generateFlow(targetNode.id, sb, indent + 1)
                            sb.appendLine("${prefix}}")
                        }
                        "Delay" -> {
                            val seconds = targetNode.properties["seconds"]?.toString() ?: "1"
                            sb.appendLine("${prefix}after($seconds, function() {")
                            generateFlow(targetNode.id, sb, indent + 1)
                            sb.appendLine("${prefix}});")
                        }
                        "Loop" -> {
                            val count = targetNode.properties["count"]?.toString() ?: "10"
                            sb.appendLine("${prefix}for (var i = 0; i < $count; i++) {")
                            generateFlow(targetNode.id, sb, indent + 1)
                            sb.appendLine("${prefix}}")
                        }
                        else -> generateFlow(targetNode.id, sb, indent)
                    }
                }
                else -> { /* Generate appropriate code */ }
            }
        }
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("name", name).put("description", description)
            .put("nextId", nextNodeId)
        o.put("nodes", JSONArray().also { arr -> nodes.forEach { arr.put(it.toJson()) } })
        o.put("connections", JSONArray().also { arr -> connections.forEach { arr.put(it.toJson()) } })
        o.put("variables", JSONArray().also { arr -> variables.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun parse(json: String): Blueprint {
            val obj = JSONObject(json)
            return fromJson(obj)
        }
        fun fromJson(o: JSONObject): Blueprint {
            val bp = Blueprint(
                o.optString("name", ""), o.optString("description", ""),
                mutableListOf(), mutableListOf(), mutableListOf(),
                o.optInt("nextId", 0)
            )
            val nodes = o.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nodes.length()) bp.nodes.add(BlueprintNode.fromJson(nodes.getJSONObject(i)))
            val conns = o.optJSONArray("connections") ?: JSONArray()
            for (i in 0 until conns.length()) bp.connections.add(BlueprintConnection.fromJson(conns.getJSONObject(i)))
            val vars = o.optJSONArray("variables") ?: JSONArray()
            for (i in 0 until vars.length()) bp.variables.add(BlueprintVariable.fromJson(vars.getJSONObject(i)))
            return bp
        }

        /** Create a default set of node templates. */
        val nodeTemplates = listOf(
            // Events
            Triple("On Start", NodeCategory.Event, listOf(Pin("Out", PinType.Flow))),
            Triple("On Update", NodeCategory.Event, listOf(Pin("Out", PinType.Flow))),
            Triple("On Collision", NodeCategory.Event, listOf(Pin("Out", PinType.Flow))),
            Triple("On Trigger", NodeCategory.Event, listOf(Pin("Out", PinType.Flow))),
            Triple("On Tap", NodeCategory.Event, listOf(Pin("Out", PinType.Flow))),
            Triple("On Destroy", NodeCategory.Event, listOf(Pin("Out", PinType.Flow))),
            // Flow Control
            Triple("If", NodeCategory.FlowControl, emptyList()),
            Triple("Switch", NodeCategory.FlowControl, emptyList()),
            Triple("Loop", NodeCategory.FlowControl, emptyList()),
            Triple("Delay", NodeCategory.FlowControl, emptyList()),
            Triple("Sequence", NodeCategory.FlowControl, emptyList()),
            Triple("While", NodeCategory.FlowControl, emptyList()),
            // Math
            Triple("Add", NodeCategory.Math, emptyList()),
            Triple("Subtract", NodeCategory.Math, emptyList()),
            Triple("Multiply", NodeCategory.Math, emptyList()),
            Triple("Divide", NodeCategory.Math, emptyList()),
            Triple("Random", NodeCategory.Math, emptyList()),
            Triple("Clamp", NodeCategory.Math, emptyList()),
            Triple("Lerp", NodeCategory.Math, emptyList()),
            // Variables
            Triple("Get Variable", NodeCategory.Variables, emptyList()),
            Triple("Set Variable", NodeCategory.Variables, emptyList()),
            Triple("Global Get", NodeCategory.Variables, emptyList()),
            Triple("Global Set", NodeCategory.Variables, emptyList()),
            // Scene
            Triple("Spawn", NodeCategory.Scene, emptyList()),
            Triple("Destroy", NodeCategory.Scene, emptyList()),
            Triple("Find", NodeCategory.Scene, emptyList()),
            Triple("Find All", NodeCategory.Scene, emptyList()),
            Triple("Load Scene", NodeCategory.Scene, emptyList()),
            Triple("Reload Scene", NodeCategory.Scene, emptyList()),
            // Transform
            Triple("Set Position", NodeCategory.Transform, emptyList()),
            Triple("Get Position", NodeCategory.Transform, emptyList()),
            Triple("Move", NodeCategory.Transform, emptyList()),
            Triple("Rotate", NodeCategory.Transform, emptyList()),
            Triple("Scale", NodeCategory.Transform, emptyList()),
            // Animation
            Triple("Play Animation", NodeCategory.Animation, emptyList()),
            Triple("Set Animation", NodeCategory.Animation, emptyList()),
            Triple("Animation State", NodeCategory.Animation, emptyList()),
            // UI
            Triple("Set Text", NodeCategory.UI, emptyList()),
            Triple("Set Image", NodeCategory.UI, emptyList()),
            Triple("Set UI Value", NodeCategory.UI, emptyList()),
            Triple("Show/Hide UI", NodeCategory.UI, emptyList()),
            // Audio
            Triple("Play Sound", NodeCategory.Audio, emptyList()),
            Triple("Stop Sound", NodeCategory.Audio, emptyList()),
            Triple("Beep", NodeCategory.Audio, emptyList()),
            // Physics
            Triple("Add Force", NodeCategory.Physics, emptyList()),
            Triple("Apply Impulse", NodeCategory.Physics, emptyList()),
            Triple("Set Velocity", NodeCategory.Physics, emptyList()),
            Triple("Is Grounded", NodeCategory.Physics, emptyList()),
            // Input
            Triple("Axis X", NodeCategory.Input, emptyList()),
            Triple("Axis Y", NodeCategory.Input, emptyList()),
            Triple("Button Pressed", NodeCategory.Input, emptyList()),
            Triple("Touch Position", NodeCategory.Input, emptyList()),
            // Time
            Triple("Get Time", NodeCategory.Time, emptyList()),
            Triple("Get Delta Time", NodeCategory.Time, emptyList()),
            Triple("Get FPS", NodeCategory.Time, emptyList()),
            // Logic
            Triple("And", NodeCategory.Logic, emptyList()),
            Triple("Or", NodeCategory.Logic, emptyList()),
            Triple("Not", NodeCategory.Logic, emptyList()),
            Triple("Compare", NodeCategory.Logic, emptyList()),
        )

        /** Create a default starter blueprint graph */
        fun defaultGraph(): Blueprint {
            val bp = Blueprint(name = "New Blueprint")
            val startNode = bp.createNode("On Start", NodeCategory.Event, 100f, 100f)
            startNode.outputs.add(Pin("Out", PinType.Flow))
            return bp
        }
    }
}

/** A variable in a blueprint. */
class BlueprintVariable(
    var name: String = "",
    var type: PinType = PinType.Float,
    var defaultValue: Any? = null,
    var isGlobal: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("type", type.ordinal)
        .put("default", defaultValue?.toString() ?: "")
        .put("global", isGlobal)

    companion object {
        fun fromJson(o: JSONObject): BlueprintVariable = BlueprintVariable(
            o.optString("name", ""),
            PinType.entries.getOrElse(o.optInt("type", 1)) { PinType.Float },
            o.optString("default", ""),
            o.optBoolean("global", false)
        )
    }
}

/** Blueprint component that attaches visual scripts to game objects. */
class BlueprintComponent : Component() {
    override val type = "Blueprint"
    var blueprintAsset = ""
    var autoGenerateScript = true

    override fun props() = listOf(
        Prop.Asset("Blueprint", com.sengine.engine.core.AssetKind.CONTROLLER, { blueprintAsset }, { blueprintAsset = it }),
        Prop.B("Auto Generate", { autoGenerateScript }, { autoGenerateScript = it }),
    )
}

/** Stub implementation of BlueprintNodes for testing */
object BlueprintNodes {
    data class NodeDef(
        val type: String,
        val category: String = "Default",
        val hasIn: Boolean = true,
        val outs: List<String> = listOf("out")
    )
    val all: List<NodeDef> = listOf(
        NodeDef("On Start", "Event", hasIn = false),
        NodeDef("On Update", "Event", hasIn = false),
        NodeDef("Set Variable", "Flow"),
        NodeDef("Get Variable", "Flow"),
        NodeDef("Print", "Debug")
    )
}

/** Stub implementation of BlueprintCompiler for testing */
object BlueprintCompiler {
    fun compile(blueprint: Blueprint): String {
        return "// Compiled blueprint: ${blueprint.name}\n// Nodes: ${blueprint.nodes.size}"
    }
}
