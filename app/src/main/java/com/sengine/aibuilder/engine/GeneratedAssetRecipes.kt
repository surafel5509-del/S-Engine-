package com.sengine.aibuilder.engine

import com.sengine.engine.anim.AnimationClip
import com.sengine.engine.blueprint.Blueprint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Constrained, deterministic local asset builders. AI output supplies recipes, never executable source. */
object GeneratedAssetRecipes {
    const val MAX_PIXEL_DIMENSION = 64
    const val MAX_GENERATED_SOUND_MS = 3_000
    const val MAX_GENERATED_ASSETS_PER_PLAN = 8

    fun pixelSprite(width: Int, height: Int, palette: List<String>, rows: List<String>): ByteArray {
        require(width in 1..MAX_PIXEL_DIMENSION && height in 1..MAX_PIXEL_DIMENSION && width * height <= 4_096) {
            "Pixel sprite dimensions are outside the 64×64 safety limit."
        }
        require(palette.isNotEmpty() && palette.size <= 16) { "Pixel sprite palette must contain 1–16 colors." }
        require(rows.size == height && rows.all { it.length == width }) { "Pixel rows must match the declared sprite dimensions." }
        val colors = palette.map(::parseColor)
        val scanlines = ByteArrayOutputStream(height * (1 + width * 4))
        for (row in rows) {
            scanlines.write(0) // PNG filter: none
            for (glyph in row) {
                if (glyph == '.') {
                    scanlines.write(byteArrayOf(0, 0, 0, 0))
                } else {
                    val index = glyph.digitToIntOrNull(16)
                        ?: throw IllegalArgumentException("Pixel rows may contain only '.', 0–9, and A–F palette indexes.")
                    val color = colors.getOrNull(index)
                        ?: throw IllegalArgumentException("Pixel row refers to a missing palette entry.")
                    scanlines.write(color)
                }
            }
        }
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { it.write(scanlines.toByteArray()) }
        val png = ByteArrayOutputStream()
        val out = DataOutputStream(png)
        out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { h ->
            h.writeInt(width)
            h.writeInt(height)
            h.writeByte(8)  // bit depth
            h.writeByte(6)  // truecolor + alpha
            h.writeByte(0)  // compression
            h.writeByte(0)  // filter
            h.writeByte(0)  // non-interlaced
        }
        writeChunk(out, "IHDR", header.toByteArray())
        writeChunk(out, "IDAT", compressed.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        out.flush()
        return png.toByteArray()
    }

    fun sound(
        waveform: String,
        frequencyHz: Float,
        endFrequencyHz: Float,
        durationMs: Int,
        volume: Float,
    ): ByteArray {
        require(waveform in WAVEFORMS) { "Unsupported sound waveform." }
        require(frequencyHz.isFinite() && frequencyHz in 40f..2_000f && endFrequencyHz.isFinite() && endFrequencyHz in 40f..2_000f) {
            "Sound frequencies must be between 40 and 2,000 Hz."
        }
        require(durationMs in 80..MAX_GENERATED_SOUND_MS) { "Sound effects must be between 80 and 3,000 ms." }
        require(volume.isFinite() && volume in 0f..1f) { "Sound volume must be between 0 and 1." }
        val sampleRate = 22_050
        val sampleCount = sampleRate * durationMs / 1_000
        val pcm = ByteArrayOutputStream(sampleCount * 2)
        var noiseState = 0x2F6E2B1
        for (i in 0 until sampleCount) {
            val t = i.toDouble() / (sampleCount - 1).coerceAtLeast(1)
            val frequency = frequencyHz + (endFrequencyHz - frequencyHz) * t.toFloat()
            val phase = 2.0 * PI * frequency * i / sampleRate
            val sine = sin(phase)
            val wave = when (waveform) {
                "sine" -> sine
                "square" -> if (sine >= 0.0) 1.0 else -1.0
                "triangle" -> 2.0 / PI * asin(sine)
                "saw" -> 2.0 * (phase / (2.0 * PI) - floor(phase / (2.0 * PI) + 0.5))
                "noise" -> {
                    noiseState = noiseState * 1_664_525 + 1_013_904_223
                    noiseState.toDouble() / Int.MAX_VALUE.toDouble()
                }
                else -> 0.0
            }
            val attack = (i.toDouble() / (sampleRate * 0.008)).coerceIn(0.0, 1.0)
            val release = ((sampleCount - 1 - i).toDouble() / (sampleRate * 0.10)).coerceIn(0.0, 1.0)
            val envelope = minOf(attack, release)
            val sample = (wave * volume * envelope * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            writeShortLe(pcm, sample)
        }
        val data = pcm.toByteArray()
        val wav = ByteArrayOutputStream(data.size + 44)
        wav.writeAscii("RIFF")
        writeIntLe(wav, 36 + data.size)
        wav.writeAscii("WAVE")
        wav.writeAscii("fmt ")
        writeIntLe(wav, 16)
        writeShortLe(wav, 1) // PCM
        writeShortLe(wav, 1) // mono
        writeIntLe(wav, sampleRate)
        writeIntLe(wav, sampleRate * 2)
        writeShortLe(wav, 2)
        writeShortLe(wav, 16)
        wav.writeAscii("data")
        writeIntLe(wav, data.size)
        wav.write(data)
        return wav.toByteArray()
    }

    fun animationClip(
        textureAsset: String,
        columns: Int,
        rows: Int,
        frames: List<Int>,
        fps: Float,
        loop: Boolean,
        pivotX: Float,
        pivotY: Float,
    ): String = AnimationClip(
        texture = textureAsset,
        columns = columns,
        rows = rows,
        frames = frames.toMutableList(),
        fps = fps,
        loop = loop,
        pivotX = pivotX,
        pivotY = pivotY,
    ).toJson().toString(2)

    fun shaderPreset(preset: String): String {
        val effect = when (preset) {
            "pulse" -> "vec4 effect(vec4 c, vec2 uv) { float p = 0.72 + 0.28 * sin(uTime * 4.0); return vec4(c.rgb * p, c.a); }"
            "grayscale" -> "vec4 effect(vec4 c, vec2 uv) { float g = dot(c.rgb, vec3(0.299, 0.587, 0.114)); return vec4(mix(c.rgb, vec3(g), clamp(uParam, 0.0, 1.0)), c.a); }"
            "sepia" -> "vec4 effect(vec4 c, vec2 uv) { vec3 s = vec3(dot(c.rgb, vec3(0.393,0.769,0.189)), dot(c.rgb, vec3(0.349,0.686,0.168)), dot(c.rgb, vec3(0.272,0.534,0.131))); return vec4(mix(c.rgb,s,clamp(uParam,0.0,1.0)), c.a); }"
            "vignette" -> "vec4 effect(vec4 c, vec2 uv) { float d = distance(uv, vec2(0.5)); float v = smoothstep(0.82, 0.25, d * (0.7 + uParam * 0.5)); return vec4(c.rgb * v, c.a); }"
            "pixelate" -> "vec4 effect(vec4 c, vec2 uv) { float px = max(2.0, 4.0 * uParam); vec2 g = uResolution / px; vec2 p = (floor(uv * g) + 0.5) / g; return vec4(texture2D(uTex, p).rgb, c.a); }"
            "invert" -> "vec4 effect(vec4 c, vec2 uv) { return vec4(mix(c.rgb, 1.0 - c.rgb, clamp(uParam, 0.0, 1.0)), c.a); }"
            "chromatic" -> "vec4 effect(vec4 c, vec2 uv) { vec2 o = (uv - 0.5) * 0.012 * uParam; return vec4(texture2D(uTex, uv + o).r, c.g, texture2D(uTex, uv - o).b, c.a); }"
            else -> throw IllegalArgumentException("Unsupported safe shader preset '$preset'.")
        }
        return "// S Engine vetted shader preset: $preset\n$effect\n"
    }

    fun blueprintPreset(preset: String): String {
        val blueprint = Blueprint()
        when (preset) {
            "platformer_controller" -> {
                val update = blueprint.add("OnUpdate", 40f, 40f)
                val controller = blueprint.add("Platformer", 300f, 40f)
                controller.params["speed"] = "6"
                controller.params["jump"] = "11"
                blueprint.connect(update.id, "out", controller.id)
            }
            "character_3d_controller" -> {
                val update = blueprint.add("OnUpdate", 40f, 40f)
                val controller = blueprint.add("Controller3D", 300f, 40f)
                controller.params["speed"] = "6"
                controller.params["jump"] = "7"
                blueprint.connect(update.id, "out", controller.id)
            }
            "joystick_move" -> {
                val update = blueprint.add("OnUpdate", 40f, 40f)
                val move = blueprint.add("JoystickMove", 300f, 40f)
                move.params["speed"] = "5"
                blueprint.connect(update.id, "out", move.id)
            }
            "rotator" -> {
                val update = blueprint.add("OnUpdate", 40f, 40f)
                val rotate = blueprint.add("Rotate", 300f, 40f)
                rotate.params["degrees"] = "90"
                blueprint.connect(update.id, "out", rotate.id)
            }
            else -> throw IllegalArgumentException("Unsupported safe blueprint preset '$preset'.")
        }
        return blueprint.toJson().toString(2)
    }

    private fun parseColor(raw: String): ByteArray {
        require(raw.matches(Regex("#(?:[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})"))) { "Palette colors must use #RRGGBB or #AARRGGBB." }
        val hex = raw.substring(1)
        val value = hex.toLong(16)
        val a: Int
        val r: Int
        val g: Int
        val b: Int
        if (hex.length == 6) {
            a = 255
            r = ((value shr 16) and 0xff).toInt()
            g = ((value shr 8) and 0xff).toInt()
            b = (value and 0xff).toInt()
        } else {
            a = ((value shr 24) and 0xff).toInt()
            r = ((value shr 16) and 0xff).toInt()
            g = ((value shr 8) and 0xff).toInt()
            b = (value and 0xff).toInt()
        }
        return byteArrayOf(r.toByte(), g.toByte(), b.toByte(), a.toByte())
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.writeInt(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
    private fun writeIntLe(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff); out.write(value ushr 8 and 0xff); out.write(value ushr 16 and 0xff); out.write(value ushr 24 and 0xff)
    }
    private fun writeShortLe(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff); out.write(value ushr 8 and 0xff)
    }

    private val WAVEFORMS = setOf("sine", "square", "triangle", "saw", "noise")
}
