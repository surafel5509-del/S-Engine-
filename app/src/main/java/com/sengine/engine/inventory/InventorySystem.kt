package com.sengine.engine.inventory

import org.json.JSONArray
import org.json.JSONObject

// ─── Inventory & Item System ──────────────────────────────────────────────

/**
 * RPG-style inventory system with:
 * - Item definitions with stats and properties
 * - Stackable and non-stackable items
 * - Equipment slots
 * - Item categories and filtering
 * - Crafting recipes
 */

enum class ItemCategory {
    WEAPON, ARMOR, CONSUMABLE, MATERIAL, QUEST, MISC
}

enum class EquipSlot {
    NONE, HEAD, BODY, HANDS, FEET, WEAPON, OFFHAND, ACCESSORY
}

class ItemDef(
    var id: String = "",
    var name: String = "",
    var description: String = "",
    var icon: String = "",
    var category: ItemCategory = ItemCategory.MISC,
    var equipSlot: EquipSlot = EquipSlot.NONE,
    var stackable: Boolean = true,
    var maxStack: Int = 99,
    var rarity: Int = 0, // 0=Common, 1=Uncommon, 2=Rare, 3=Epic, 4=Legendary
    var value: Int = 0,  // Gold value
    var weight: Float = 0f,
    var stats: MutableMap<String, Float> = mutableMapOf(), // attack, defense, speed, etc.
    var useEffect: String = "", // Script function to call on use
    var level: Int = 1,
    var tags: MutableList<String> = mutableListOf(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id).put("name", name).put("desc", description)
            .put("icon", icon).put("category", category.ordinal)
            .put("slot", equipSlot.ordinal).put("stackable", stackable)
            .put("maxStack", maxStack).put("rarity", rarity)
            .put("value", value).put("weight", weight.toDouble())
            .put("useEffect", useEffect).put("level", level)
        o.put("stats", JSONObject().also { j -> stats.forEach { (k, v) -> j.put(k, v.toDouble()) } })
        o.put("tags", JSONArray(tags))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ItemDef {
            val item = ItemDef()
            item.id = o.optString("id", "")
            item.name = o.optString("name", "")
            item.description = o.optString("desc", "")
            item.icon = o.optString("icon", "")
            item.category = ItemCategory.entries.getOrElse(o.optInt("category", 5)) { ItemCategory.MISC }
            item.equipSlot = EquipSlot.entries.getOrElse(o.optInt("slot", 0)) { EquipSlot.NONE }
            item.stackable = o.optBoolean("stackable", true)
            item.maxStack = o.optInt("maxStack", 99)
            item.rarity = o.optInt("rarity", 0)
            item.value = o.optInt("value", 0)
            item.weight = o.optDouble("weight", 0.0).toFloat()
            item.useEffect = o.optString("useEffect", "")
            item.level = o.optInt("level", 1)
            val stats = o.optJSONObject("stats") ?: JSONObject()
            for (k in stats.keys()) item.stats[k] = stats.getDouble(k).toFloat()
            val tags = o.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until tags.length()) item.tags.add(tags.getString(i))
            return item
        }
    }
}

class InventorySlot(
    var itemId: String = "",
    var count: Int = 0,
    var durability: Float = 100f,
    var enchantments: MutableMap<String, Int> = mutableMapOf(),
    var customData: MutableMap<String, Any> = mutableMapOf(),
) {
    val isEmpty: Boolean get() = count <= 0 || itemId.isEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", itemId).put("count", count)
            .put("durability", durability.toDouble())
        o.put("enchants", JSONObject().also { j -> enchantments.forEach { (k, v) -> j.put(k, v) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): InventorySlot = InventorySlot(
            o.optString("id", ""), o.optInt("count", 0),
            o.optDouble("durability", 100.0).toFloat()
        )
    }
}

class Inventory(
    var maxSlots: Int = 20,
    var gold: Int = 0,
) {
    val slots = Array(maxSlots) { InventorySlot() }

    /** Add items to inventory. Returns leftover count. */
    fun addItem(itemId: String, count: Int, itemDef: ItemDef?): Int {
        var remaining = count

        // Try to stack with existing items
        if (itemDef?.stackable == true) {
            for (slot in slots) {
                if (slot.itemId == itemId && slot.count < itemDef.maxStack) {
                    val canAdd = (itemDef.maxStack - slot.count).coerceAtMost(remaining)
                    slot.count += canAdd
                    remaining -= canAdd
                    if (remaining <= 0) return 0
                }
            }
        }

        // Find empty slots
        for (i in slots.indices) {
            if (slots[i].isEmpty && remaining > 0) {
                val toAdd = if (itemDef?.stackable == true) remaining.coerceAtMost(itemDef.maxStack) else remaining
                slots[i] = InventorySlot(itemId, toAdd)
                remaining -= toAdd
            }
        }

        return remaining
    }

    /** Remove items from inventory. Returns actual removed count. */
    fun removeItem(itemId: String, count: Int): Int {
        var remaining = count
        for (slot in slots) {
            if (slot.itemId == itemId && remaining > 0) {
                val canRemove = slot.count.coerceAtMost(remaining)
                slot.count -= canRemove
                remaining -= canRemove
                if (slot.count <= 0) {
                    slot.itemId = ""
                    slot.count = 0
                }
            }
        }
        return count - remaining
    }

    /** Count total of an item. */
    fun countItem(itemId: String): Int {
        return slots.filter { it.itemId == itemId }.sumOf { it.count }
    }

    /** Check if inventory has enough of an item. */
    fun hasItem(itemId: String, count: Int = 1): Boolean = countItem(itemId) >= count

    /** Get first slot containing an item. */
    fun findSlot(itemId: String): Int = slots.indexOfFirst { it.itemId == itemId }

    /** Get total weight. */
    fun getTotalWeight(items: Map<String, ItemDef>): Float {
        return slots.sumOf { slot ->
            val def = items[slot.itemId] ?: return@sumOf 0.0
            (slot.count * def.weight).toDouble()
        }.toFloat()




    }

    fun toJson(): JSONObject {
        val o = JSONObject().put("maxSlots", maxSlots).put("gold", gold)
        o.put("slots", JSONArray().also { arr -> slots.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Inventory {
            val inv = Inventory(o.optInt("maxSlots", 20), o.optInt("gold", 0))
            val arr = o.optJSONArray("slots") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), inv.slots.size)) {
                inv.slots[i] = InventorySlot.fromJson(arr.getJSONObject(i))
            }
            return inv
        }
    }
}

/** Equipment set — manages equipped items. */
class Equipment {
    val equipped = mutableMapOf<EquipSlot, InventorySlot>()

    fun equip(slot: EquipSlot, item: InventorySlot) {
        equipped[slot] = item
    }

    fun unequip(slot: EquipSlot): InventorySlot? = equipped.remove(slot)

    fun getEquipped(slot: EquipSlot): InventorySlot? = equipped[slot]

    /** Get total stat bonus from all equipped items. */
    fun getTotalStats(statName: String, items: Map<String, ItemDef>): Float {
        return equipped.values.sumOf { slot ->
            val def = items[slot.itemId] ?: return@sumOf 0.0
            (def.stats[statName] ?: 0f).toDouble()
        }.toFloat()
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((slot, item) in equipped) {
            o.put(slot.name, item.toJson())
        }
        return o
    }
}

/** Crafting recipe. */
class CraftRecipe(
    var id: String = "",
    var resultItemId: String = "",
    var resultCount: Int = 1,
    val ingredients: MutableMap<String, Int> = mutableMapOf(), // itemId → count
    var craftTime: Float = 0f, // 0 = instant
    var requiredLevel: Int = 0,
    var tags: MutableList<String> = mutableListOf(),
) {
    fun canCraft(inventory: Inventory): Boolean {
        return ingredients.all { (itemId, count) -> inventory.hasItem(itemId, count) }
    }

    fun craft(inventory: Inventory): Boolean {
        if (!canCraft(inventory)) return false
        for ((itemId, count) in ingredients) {
            inventory.removeItem(itemId, count)
        }
        inventory.addItem(resultItemId, resultCount, null)
        return true
    }
}
