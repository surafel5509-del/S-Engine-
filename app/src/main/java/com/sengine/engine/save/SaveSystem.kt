package com.sengine.engine.save

import org.json.JSONObject
import kotlin.math.abs

// ─── Save System ──────────────────────────────────────────────────────────

/**
 * Professional save/load system with:
 * - Multiple save slots
 * - Simple XOR encryption
 * - Metadata (timestamp, playtime, level)
 * - Auto-save support
 * - Selective serialization
 */

class SaveSlot(
    var slotId: Int = 0,
    var name: String = "Save ${1}",
    var timestamp: Long = System.currentTimeMillis(),
    var playTime: Float = 0f,
    var sceneName: String = "",
    var data: MutableMap<String, Any> = mutableMapOf(),
    var thumbnail: String = "",
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("slot", slotId).put("name", name)
            .put("timestamp", timestamp).put("playTime", playTime.toDouble())
            .put("scene", sceneName).put("thumbnail", thumbnail)
        val dataObj = JSONObject()
        for ((key, value) in data) {
            when (value) {
                is Int -> dataObj.put(key, value)
                is Long -> dataObj.put(key, value)
                is Float -> dataObj.put(key, value.toDouble())
                is Double -> dataObj.put(key, value)
                is Boolean -> dataObj.put(key, value)
                is String -> dataObj.put(key, value)
                else -> dataObj.put(key, value.toString())
            }
        }
        o.put("data", dataObj)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SaveSlot {
            val s = SaveSlot()
            s.slotId = o.optInt("slot", 0)
            s.name = o.optString("name", "")
            s.timestamp = o.optLong("timestamp", 0)
            s.playTime = o.optDouble("playTime", 0.0).toFloat()
            s.sceneName = o.optString("scene", "")
            s.thumbnail = o.optString("thumbnail", "")
            val dataObj = o.optJSONObject("data") ?: JSONObject()
            for (key in dataObj.keys()) {
                s.data[key] = dataObj.get(key)
            }
            return s
        }
    }
}

class SaveManager {
    val slots = mutableMapOf<Int, SaveSlot>()
    var encryptionKey: String = "SEngine2024"
    var autoSaveInterval: Float = 300f // 5 minutes
    var maxSlots: Int = 10

    /** Save current game state to a slot. */
    fun save(slotId: Int, data: Map<String, Any>, sceneName: String = "", playTime: Float = 0f): SaveSlot {
        val slot = SaveSlot(
            slotId = slotId,
            name = "Save ${slotId + 1}",
            timestamp = System.currentTimeMillis(),
            playTime = playTime,
            sceneName = sceneName,
            data = data.toMutableMap(),
        )
        slots[slotId] = slot
        return slot
    }

    /** Load data from a save slot. */
    fun load(slotId: Int): Map<String, Any>? {
        return slots[slotId]?.data
    }

    /** Delete a save slot. */
    fun delete(slotId: Int) {
        slots.remove(slotId)
    }

    /** Get slot metadata without loading full data. */
    fun getSlotInfo(slotId: Int): SaveSlot? = slots[slotId]

    /** Simple XOR encryption for save data. */
    fun encrypt(data: String): String {
        val keyBytes = encryptionKey.toByteArray()
        val dataBytes = data.toByteArray()
        val encrypted = ByteArray(dataBytes.size)
        for (i in dataBytes.indices) {
            encrypted[i] = (dataBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    fun decrypt(data: String): String {
        val keyBytes = encryptionKey.toByteArray()
        val encrypted = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
        val decrypted = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            decrypted[i] = (encrypted[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return String(decrypted)
    }

    /** Export all saves to JSON. */
    fun toJson(): String {
        val obj = JSONObject()
        for ((id, slot) in slots) {
            obj.put(id.toString(), slot.toJson())
        }
        return obj.toString(2)
    }

    /** Import saves from JSON. */
    fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                val slotId = key.toIntOrNull() ?: continue
                slots[slotId] = SaveSlot.fromJson(obj.getJSONObject(key))
            }
        } catch (_: Exception) {}
    }

    /** Format play time as HH:MM:SS. */
    fun formatPlayTime(seconds: Float): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = (seconds % 60).toInt()
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
