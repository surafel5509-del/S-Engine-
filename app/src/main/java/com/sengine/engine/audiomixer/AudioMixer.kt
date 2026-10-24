package com.sengine.engine.audiomixer

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop

// ─── Audio Mixer System ──────────────────────────────────────────────────

/**
 * Professional audio mixer with channels, effects, and spatial audio.
 */

/** Audio channel/bus definition. */
class AudioChannel(
    var name: String = "Master",
    var volume: Float = 1f,
    var pitch: Float = 1f,
    var pan: Float = 0f,           // -1 = left, 0 = center, 1 = right
    var muted: Boolean = false,
    var solo: Boolean = false,
    var parentChannel: String = "", // For sub-buses
    val effects: MutableList<AudioEffect> = mutableListOf(),
) {
    /** Get effective volume considering mute/solo/parent. */
    fun getEffectiveVolume(allChannels: List<AudioChannel>): Float {
        if (muted) return 0f
        var vol = volume
        if (parentChannel.isNotEmpty()) {
            val parent = allChannels.firstOrNull { it.name == parentChannel }
            if (parent != null) vol *= parent.getEffectiveVolume(allChannels)
        }
        return vol.coerceIn(0f, 1f)
    }

    fun toJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
            .put("name", name).put("volume", volume.toDouble())
            .put("pitch", pitch.toDouble()).put("pan", pan.toDouble())
            .put("muted", muted).put("solo", solo)
            .put("parent", parentChannel)
        o.put("effects", org.json.JSONArray().also { arr ->
            effects.forEach { arr.put(it.toJson()) }
        })
        return o
    }

    companion object {
        fun fromJson(o: org.json.JSONObject): AudioChannel {
            val ch = AudioChannel(
                o.optString("name", "Master"),
                o.optDouble("volume", 1.0).toFloat(),
                o.optDouble("pitch", 1.0).toFloat(),
                o.optDouble("pan", 0.0).toFloat().coerceIn(-1f, 1f),
                o.optBoolean("muted", false),
                o.optBoolean("solo", false),
                o.optString("parent", "")
            )
            val arr = o.optJSONArray("effects") ?: org.json.JSONArray()
            for (i in 0 until arr.length()) ch.effects.add(AudioEffect.fromJson(arr.getJSONObject(i)))
            return ch
        }
    }
}

/** Audio effect on a channel. */
class AudioEffect(
    var type: Int = 0,  // 0=Reverb, 1=LowPass, 2=HighPass, 3=Echo, 4=Distortion, 5=Compressor
    var mix: Float = 1f, // Wet/dry mix
    var param1: Float = 0f,
    var param2: Float = 0f,
    var param3: Float = 0f,
    var enabled: Boolean = true,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("type", type).put("mix", mix.toDouble())
        .put("p1", param1.toDouble()).put("p2", param2.toDouble())
        .put("p3", param3.toDouble()).put("enabled", enabled)

    companion object {
        fun fromJson(o: org.json.JSONObject): AudioEffect = AudioEffect(
            o.optInt("type", 0), o.optDouble("mix", 1.0).toFloat(),
            o.optDouble("p1", 0.0).toFloat(), o.optDouble("p2", 0.0).toFloat(),
            o.optDouble("p3", 0.0).toFloat(), o.optBoolean("enabled", true)
        )
    }
}

/** Spatial audio settings for 3D sound positioning. */
class SpatialAudioSettings(
    var enabled: Boolean = false,
    var minDistance: Float = 1f,
    var maxDistance: Float = 50f,
    var rolloffMode: Int = 0, // 0=Logarithmic, 1=Linear, 2=Custom
    var dopplerLevel: Float = 1f,
    var spread: Float = 0f,    // 0-360 degrees
    var priority: Int = 128,   // 0=highest, 256=lowest
)

/** Audio mixer component — manages all audio channels and effects. */
class AudioMixer : Component() {
    override val type = "AudioMixer"
    val channels = mutableListOf<AudioChannel>()
    var masterVolume: Float = 1f
    var globalPitch: Float = 1f

    override fun props() = listOf(
        Prop.F("Master Volume", { masterVolume }, { masterVolume = it.coerceIn(0f, 2f) }, 0.05f),
        Prop.F("Global Pitch", { globalPitch }, { globalPitch = it.coerceIn(0.1f, 4f) }, 0.1f),
    )

    init {
        // Default channels
        channels.add(AudioChannel("Master", volume = 1f))
        channels.add(AudioChannel("Music", volume = 0.8f, parentChannel = "Master"))
        channels.add(AudioChannel("SFX", volume = 1f, parentChannel = "Master"))
        channels.add(AudioChannel("Ambient", volume = 0.6f, parentChannel = "Master"))
        channels.add(AudioChannel("UI", volume = 1f, parentChannel = "Master"))
    }

    fun getChannel(name: String): AudioChannel? = channels.firstOrNull { it.name == name }

    fun setChannelVolume(name: String, volume: Float) {
        getChannel(name)?.volume = volume.coerceIn(0f, 2f)
    }

    fun muteChannel(name: String, muted: Boolean) {
        getChannel(name)?.muted = muted
    }

    fun addEffect(channelName: String, effect: AudioEffect) {
        getChannel(channelName)?.effects?.add(effect)
    }

    /** Get effective volume for a channel considering all settings. */
    fun getEffectiveVolume(channelName: String): Float {
        val ch = getChannel(channelName) ?: return masterVolume
        return ch.getEffectiveVolume(channels) * masterVolume
    }

    /** Calculate spatial volume based on listener and source positions. */
    fun calculateSpatialVolume(
        listenerX: Float, listenerY: Float,
        sourceX: Float, sourceY: Float,
        settings: SpatialAudioSettings
    ): Float {
        if (!settings.enabled) return 1f
        val dx = sourceX - listenerX
        val dy = sourceY - listenerY
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)

        return when (settings.rolloffMode) {
            0 -> { // Logarithmic
                if (dist <= settings.minDistance) 1f
                else if (dist >= settings.maxDistance) 0f
                else 1f / (1f + (dist - settings.minDistance))
            }
            1 -> { // Linear
                if (dist <= settings.minDistance) 1f
                else if (dist >= settings.maxDistance) 0f
                else 1f - (dist - settings.minDistance) / (settings.maxDistance - settings.minDistance)
            }
            else -> 1f
        }
    }

    /** Calculate pan based on source position relative to listener. */
    fun calculatePan(
        listenerX: Float, sourceX: Float,
        settings: SpatialAudioSettings
    ): Float {
        if (!settings.enabled) return 0f
        val dx = sourceX - listenerX
        return (dx / settings.maxDistance).coerceIn(-1f, 1f)
    }
}

/** Audio snapshot — predefined mixer settings for quick transitions. */
class AudioSnapshot(
    var name: String = "",
    var channelVolumes: MutableMap<String, Float> = mutableMapOf(),
    var transitionTime: Float = 0.5f,
) {
    fun applyTo(mixer: AudioMixer) {
        for ((name, vol) in channelVolumes) {
            mixer.setChannelVolume(name, vol)
        }
    }
}
