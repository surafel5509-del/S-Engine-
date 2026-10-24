package com.sengine.engine.dialogue

import org.json.JSONArray
import org.json.JSONObject

// ─── Dialogue System ──────────────────────────────────────────────────────

/**
 * Branching dialogue system with:
 * - Multiple speakers
 * - Choice branches
 * - Conditions (variables, flags)
 * - Commands (give item, set flag, play animation)
 * - Localization support
 */

class DialogueNode(
    var id: Int = 0,
    var speaker: String = "",
    var text: String = "",
    var portrait: String = "",
    var choices: MutableList<DialogueChoice> = mutableListOf(),
    var nextNodeId: Int = -1,
    var conditions: MutableList<DialogueCondition> = mutableListOf(),
    var commands: MutableList<DialogueCommand> = mutableListOf(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id).put("speaker", speaker).put("text", text)
            .put("portrait", portrait).put("next", nextNodeId)
        o.put("choices", JSONArray().also { arr -> choices.forEach { arr.put(it.toJson()) } })
        o.put("conditions", JSONArray().also { arr -> conditions.forEach { arr.put(it.toJson()) } })
        o.put("commands", JSONArray().also { arr -> commands.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): DialogueNode {
            val n = DialogueNode()
            n.id = o.optInt("id", 0)
            n.speaker = o.optString("speaker", "")
            n.text = o.optString("text", "")
            n.portrait = o.optString("portrait", "")
            n.nextNodeId = o.optInt("next", -1)
            val choices = o.optJSONArray("choices") ?: JSONArray()
            for (i in 0 until choices.length()) n.choices.add(DialogueChoice.fromJson(choices.getJSONObject(i)))
            val conds = o.optJSONArray("conditions") ?: JSONArray()
            for (i in 0 until conds.length()) n.conditions.add(DialogueCondition.fromJson(conds.getJSONObject(i)))
            val cmds = o.optJSONArray("commands") ?: JSONArray()
            for (i in 0 until cmds.length()) n.commands.add(DialogueCommand.fromJson(cmds.getJSONObject(i)))
            return n
        }
    }
}

class DialogueChoice(
    var text: String = "",
    var targetNodeId: Int = -1,
    var conditions: MutableList<DialogueCondition> = mutableListOf(),
    var once: Boolean = false, // Hide after selected
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("text", text).put("target", targetNodeId).put("once", once)
        o.put("conditions", JSONArray().also { arr -> conditions.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): DialogueChoice {
            val c = DialogueChoice()
            c.text = o.optString("text", "")
            c.targetNodeId = o.optInt("target", -1)
            c.once = o.optBoolean("once", false)
            val conds = o.optJSONArray("conditions") ?: JSONArray()
            for (i in 0 until conds.length()) c.conditions.add(DialogueCondition.fromJson(conds.getJSONObject(i)))
            return c
        }
    }
}

class DialogueCondition(
    var variable: String = "",
    var operator: Int = 0, // 0=Equals, 1=NotEquals, 2=Greater, 3=Less, 4=Has, 5=NotHas
    var value: String = "",
) {
    fun evaluate(vars: Map<String, Any>): Boolean {
        val actual = vars[variable]
        return when (operator) {
            0 -> actual?.toString() == value
            1 -> actual?.toString() != value
            2 -> (actual as? Number)?.toDouble()?.compareTo(value.toDouble())?.let { it > 0 } ?: false
            3 -> (actual as? Number)?.toDouble()?.compareTo(value.toDouble())?.let { it < 0 } ?: false
            4 -> actual != null
            5 -> actual == null
            else -> true
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("var", variable).put("op", operator).put("value", value)

    companion object {
        fun fromJson(o: JSONObject): DialogueCondition = DialogueCondition(
            o.optString("var", ""), o.optInt("op", 0), o.optString("value", "")
        )
    }
}

class DialogueCommand(
    var type: Int = 0, // 0=SetVariable, 1=GiveItem, 2=RemoveItem, 3=PlayAnimation, 4=PlaySound, 5=Warp
    var target: String = "",
    var value: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("target", target).put("value", value)

    companion object {
        fun fromJson(o: JSONObject): DialogueCommand = DialogueCommand(
            o.optInt("type", 0), o.optString("target", ""), o.optString("value", "")
        )
    }
}

/** A complete dialogue tree / conversation. */
class DialogueTree(
    var name: String = "",
    val nodes: MutableList<DialogueNode> = mutableListOf(),
    var startNodeId: Int = 0,
) {
    fun getNode(id: Int): DialogueNode? = nodes.firstOrNull { it.id == id }

    fun toJson(): JSONObject {
        val o = JSONObject().put("name", name).put("start", startNodeId)
        o.put("nodes", JSONArray().also { arr -> nodes.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): DialogueTree {
            val t = DialogueTree(o.optString("name", ""), mutableListOf(), o.optInt("start", 0))
            val arr = o.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until arr.length()) t.nodes.add(DialogueNode.fromJson(arr.getJSONObject(i)))
            return t
        }
    }
}

/** Runtime dialogue player state. */
class DialoguePlayer(val tree: DialogueTree) {
    var currentNodeId: Int = tree.startNodeId
    val variables = mutableMapOf<String, Any>()
    val selectedChoices = mutableSetOf<Int>() // Track "once" choices
    var isFinished: Boolean = false

    fun currentNode(): DialogueNode? = tree.getNode(currentNodeId)

    fun advance(): DialogueNode? {
        val node = currentNode() ?: run { isFinished = true; return null }

        // Execute commands
        for (cmd in node.commands) {
            executeCommand(cmd)
        }

        // If no choices, advance to next
        val availableChoices = getAvailableChoices()
        if (availableChoices.isEmpty()) {
            if (node.nextNodeId >= 0) {
                currentNodeId = node.nextNodeId
            } else {
                isFinished = true
            }
        }

        return currentNode()
    }

    fun selectChoice(index: Int) {
        val available = getAvailableChoices()
        if (index !in available.indices) return
        val choice = available[index]
        if (choice.once) selectedChoices.add(index)
        currentNodeId = choice.targetNodeId
    }

    fun getAvailableChoices(): List<DialogueChoice> {
        val node = currentNode() ?: return emptyList()
        return node.choices.filterIndexed { index, choice ->
            !choice.once || index !in selectedChoices
        }.filter { choice ->
            choice.conditions.all { it.evaluate(variables) }
        }
    }

    fun setVariable(name: String, value: Any) {
        variables[name] = value
    }

    private fun executeCommand(cmd: DialogueCommand) {
        when (cmd.type) {
            0 -> variables[cmd.target] = cmd.value.toIntOrNull() ?: cmd.value.toFloatOrNull() ?: cmd.value.toBooleanStrictOrNull() ?: cmd.value
            // Other commands handled by game logic
        }
    }
}
