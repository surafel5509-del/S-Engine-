package com.sengine.engine.sprite

import kotlin.math.cos
import kotlin.math.sin

/** Palette used when stamping a library part. */
class PartColors(
    val primary: Int,
    val secondary: Int,
    val accent: Int,
    val outline: Int,
    val skin: Int = 0xFFF2C39B.toInt(),
) {
    companion object {
        val DEFAULT = PartColors(0xFF4A5A6E.toInt(), 0xFFD9A066.toInt(), 0xFFE05A47.toInt(), 0xFF1A1A22.toInt())
    }
}

/**
 * A pre-made sprite part (eyes, hair, wheels, lights, ...) that can be stamped into a Sprite Studio
 * frame. Parts are drawn procedurally instead of shipping binary art, which keeps the APK small, lets
 * the user recolour every part, and means the library scales to any sprite size.
 */
class SpritePart(
    val id: String,
    val name: String,
    val category: String,
    val defaultWidth: Int,
    val defaultHeight: Int,
    val description: String,
    val outlined: Boolean = true,
    val draw: (pixels: IntArray, w: Int, h: Int, colors: PartColors) -> Unit,
)

object SpriteParts {

    val categories = listOf("Character", "Eyes", "Brows", "Hair", "Facial Hair", "Hats", "Tops", "Shoes", "Wheels", "Lights", "Effects")

    private val parts = ArrayList<SpritePart>()

    private fun part(
        id: String, name: String, category: String, w: Int, h: Int, description: String,
        outlined: Boolean = true, draw: (IntArray, Int, Int, PartColors) -> Unit,
    ) {
        parts.add(SpritePart(id, name, category, w, h, description, outlined, draw))
    }

    val all: List<SpritePart> get() = parts

    fun find(id: String): SpritePart? = parts.firstOrNull { it.id == id }

    fun inCategory(category: String): List<SpritePart> =
        if (category == "All") parts.toList() else parts.filter { it.category == category }

    // ------------------------------------------------------------------ render helpers

    /** Renders [part] into a fresh transparent buffer of the requested size. */
    fun render(part: SpritePart, w: Int = part.defaultWidth, h: Int = part.defaultHeight, colors: PartColors = PartColors.DEFAULT): IntArray {
        val px = IntArray(w * h)
        part.draw(px, w, h, colors)
        if (part.outlined) PixelOps.outline(px, w, h, colors.outline, 1)
        return px
    }

    /** Stamps [part] into a frame, centred on [cx],[cy] in document pixels. */
    fun stampInto(doc: SpriteDocument, part: SpritePart, cx: Int, cy: Int, scale: Float = 1f, colors: PartColors = PartColors.DEFAULT) {
        val w = (part.defaultWidth * scale).toInt().coerceAtLeast(1)
        val h = (part.defaultHeight * scale).toInt().coerceAtLeast(1)
        var art = render(part, part.defaultWidth, part.defaultHeight, colors)
        if (w != part.defaultWidth || h != part.defaultHeight) art = PixelOps.resize(art, part.defaultWidth, part.defaultHeight, w, h)
        PixelOps.stamp(doc.pixels(), doc.width, doc.height, art, w, h, cx - w / 2, cy - h / 2, 1f)
    }

    private fun ellipseAt(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        PixelOps.ellipse(px, w, h, (cx - rx).toInt(), (cy - ry).toInt(), (cx + rx).toInt(), (cy + ry).toInt(), color, true)
    }

    private fun ring(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, thickness: Float, color: Int) {
        val steps = (r * 6f).toInt().coerceAtLeast(12)
        for (i in 0 until steps) {
            val a = i * 2.0 * Math.PI / steps
            val x = cx + cos(a).toFloat() * r
            val y = cy + sin(a).toFloat() * r
            PixelOps.brush(px, w, h, x, y, thickness * 0.5f, color, 1f)
        }
    }

    private fun quad(px: IntArray, w: Int, h: Int, pts: FloatArray, color: Int) = PixelOps.polygon(px, w, h, pts, color)

    private fun poly(vararg xy: Float): FloatArray = xy

    // ------------------------------------------------------------------ library

    init {
        // ---------------- Character blocks
        part("head", "Head", "Character", 32, 32, "Rounded head base, skin tone from the palette") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.52f, w * 0.32f, h * 0.38f, c.skin)
            // ear shadows
            ellipseAt(px, w, h, w * 0.19f, h * 0.55f, w * 0.05f, h * 0.07f, Colors.scale(c.skin, 0.9f))
            ellipseAt(px, w, h, w * 0.81f, h * 0.55f, w * 0.05f, h * 0.07f, Colors.scale(c.skin, 0.9f))
            PixelOps.shadeVertical(px, w, h, 1.06f, 0.9f)
        }
        part("torso", "Torso", "Character", 32, 32, "Body block used as the base for clothing") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.28f, h * 0.14f, w * 0.72f, h * 0.14f, w * 0.78f, h * 0.86f, w * 0.22f, h * 0.86f), c.primary)
            PixelOps.shadeVertical(px, w, h, 1.08f, 0.86f)
        }
        part("arm", "Arm", "Character", 16, 32, "Simple two-segment arm") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.3f, h * 0.05f, w * 0.7f, h * 0.05f, w * 0.66f, h * 0.72f, w * 0.34f, h * 0.72f), c.skin)
            ellipseAt(px, w, h, w * 0.5f, h * 0.8f, w * 0.3f, h * 0.12f, c.skin)
        }
        part("leg", "Leg", "Character", 16, 32, "Straight leg block") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.28f, h * 0.05f, w * 0.72f, h * 0.05f, w * 0.66f, h * 0.9f, w * 0.34f, h * 0.9f), c.skin)
        }

        // ---------------- Eyes
        part("eye_round", "Round Eye", "Eyes", 16, 16, "Classic round eye with a highlight") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.38f, h * 0.42f, 0xFFFFFFFF.toInt())
            ellipseAt(px, w, h, w * 0.52f, h * 0.52f, w * 0.2f, h * 0.24f, c.secondary)
            ellipseAt(px, w, h, w * 0.52f, h * 0.52f, w * 0.09f, h * 0.12f, 0xFF101018.toInt())
            ellipseAt(px, w, h, w * 0.42f, h * 0.38f, w * 0.06f, h * 0.06f, 0xFFFFFFFF.toInt())
        }
        part("eye_anime", "Anime Eye", "Eyes", 16, 16, "Tall anime eye with a thick upper lash") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.36f, h * 0.46f, 0xFFFFFFFF.toInt())
            ellipseAt(px, w, h, w * 0.5f, h * 0.55f, w * 0.24f, h * 0.34f, c.accent)
            ellipseAt(px, w, h, w * 0.5f, h * 0.55f, w * 0.12f, h * 0.2f, 0xFF14141C.toInt())
            ellipseAt(px, w, h, w * 0.4f, h * 0.42f, w * 0.08f, h * 0.1f, 0xFFFFFFFF.toInt())
            PixelOps.rect(px, w, h, (w * 0.14f).toInt(), (h * 0.12f).toInt(), (w * 0.86f).toInt(), (h * 0.22f).toInt(), 0xFF14141C.toInt(), true)
        }
        part("eye_angry", "Angry Eye", "Eyes", 16, 16, "Slanted eye with a heavy brow shadow") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.55f, w * 0.34f, h * 0.32f, 0xFFFFFFFF.toInt())
            ellipseAt(px, w, h, w * 0.56f, h * 0.55f, w * 0.16f, h * 0.2f, c.secondary)
            quad(px, w, h, poly(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.32f, w * 0.9f, h * 0.46f, w * 0.1f, h * 0.24f), 0xFF14141C.toInt())
        }
        part("eye_sleepy", "Sleepy Eye", "Eyes", 16, 16, "Half closed eye") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.34f, h * 0.24f, 0xFFFFFFFF.toInt())
            ellipseAt(px, w, h, w * 0.5f, h * 0.52f, w * 0.16f, h * 0.16f, c.secondary)
            PixelOps.rect(px, w, h, (w * 0.1f).toInt(), (h * 0.18f).toInt(), (w * 0.9f).toInt(), (h * 0.34f).toInt(), c.skin, true)
        }

        // ---------------- Brows
        part("brow_thick", "Thick Brows", "Brows", 16, 8, "Bold straight brows") { px, w, h, c ->
            PixelOps.rect(px, w, h, 1, (h * 0.35f).toInt(), w - 2, (h * 0.65f).toInt(), c.primary, true)
        }
        part("brow_thin", "Thin Brows", "Brows", 16, 8, "Delicate arched brows") { px, w, h, c ->
            PixelOps.line(px, w, h, 1f, h * 0.62f, w * 0.5f, h * 0.32f, h * 0.16f, c.primary)
            PixelOps.line(px, w, h, w * 0.5f, h * 0.32f, w - 2f, h * 0.6f, h * 0.16f, c.primary)
        }
        part("brow_angry", "Angry Brows", "Brows", 16, 8, "Brows angled down toward the nose") { px, w, h, c ->
            quad(px, w, h, poly(1f, h * 0.7f, w * 0.55f, h * 0.2f, w * 0.55f, h * 0.55f, 1f, h * 0.95f), c.primary)
            quad(px, w, h, poly(w - 1f, h * 0.7f, w * 0.45f, h * 0.2f, w * 0.45f, h * 0.55f, w - 1f, h * 0.95f), c.primary)
        }

        // ---------------- Hair
        part("hair_spiky", "Spiky Hair", "Hair", 32, 32, "Sharp anime spikes") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.1f, h * 0.55f, w * 0.5f, h * 0.02f, w * 0.9f, h * 0.55f, w * 0.78f, h * 0.34f, w * 0.9f, h * 0.52f, w * 0.62f, h * 0.22f, w * 0.66f, h * 0.62f, w * 0.44f, h * 0.2f, w * 0.36f, h * 0.6f, w * 0.2f, h * 0.24f), c.primary)
            PixelOps.shadeVertical(px, w, h, 1.15f, 0.85f)
        }
        part("hair_bob", "Bob Cut", "Hair", 32, 32, "Rounded bob with side bangs") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.45f, w * 0.42f, h * 0.42f, c.primary)
            ellipseAt(px, w, h, w * 0.5f, h * 0.6f, w * 0.3f, h * 0.3f, 0)
            quad(px, w, h, poly(w * 0.14f, h * 0.5f, w * 0.42f, h * 0.36f, w * 0.36f, h * 0.78f, w * 0.16f, h * 0.84f), c.primary)
            quad(px, w, h, poly(w * 0.58f, h * 0.36f, w * 0.86f, h * 0.5f, w * 0.84f, h * 0.84f, w * 0.64f, h * 0.78f), c.primary)
        }
        part("hair_long", "Long Hair", "Hair", 32, 32, "Hair falling past the shoulders") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.36f, w * 0.4f, h * 0.34f, c.primary)
            quad(px, w, h, poly(w * 0.14f, h * 0.3f, w * 0.38f, h * 0.26f, w * 0.3f, h * 0.98f, w * 0.08f, h * 0.9f), c.primary)
            quad(px, w, h, poly(w * 0.62f, h * 0.26f, w * 0.86f, h * 0.3f, w * 0.92f, h * 0.9f, w * 0.7f, h * 0.98f), c.primary)
            PixelOps.shadeVertical(px, w, h, 1.1f, 0.8f)
        }
        part("hair_bun", "Top Bun", "Hair", 32, 32, "Neat bun with a fringe") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.22f, w * 0.18f, h * 0.16f, c.primary)
            ellipseAt(px, w, h, w * 0.5f, h * 0.48f, w * 0.36f, h * 0.24f, c.primary)
            PixelOps.rect(px, w, h, (w * 0.12f).toInt(), (h * 0.44f).toInt(), (w * 0.88f).toInt(), (h * 0.56f).toInt(), c.primary, true)
        }
        part("hair_ponytail", "Ponytail", "Hair", 32, 32, "Tied ponytail sweeping to one side") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.46f, h * 0.4f, w * 0.34f, h * 0.3f, c.primary)
            quad(px, w, h, poly(w * 0.72f, h * 0.3f, w * 0.94f, h * 0.5f, w * 0.84f, h * 0.86f, w * 0.66f, h * 0.6f), c.primary)
            ellipseAt(px, w, h, w * 0.74f, h * 0.32f, w * 0.06f, h * 0.06f, c.accent)
        }
        part("hair_curly", "Curly Hair", "Hair", 32, 32, "Cloud of curls") { px, w, h, c ->
            val r = w * 0.13f
            for (i in 0 until 9) {
                val a = i * 2.0 * Math.PI / 9
                ellipseAt(px, w, h, w * 0.5f + cos(a).toFloat() * w * 0.22f, h * 0.36f + sin(a).toFloat() * h * 0.18f, r, r, c.primary)
            }
            ellipseAt(px, w, h, w * 0.5f, h * 0.36f, w * 0.22f, h * 0.18f, c.primary)
        }

        // ---------------- Facial hair
        part("beard_full", "Full Beard", "Facial Hair", 32, 24, "Full beard with a moustache") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.1f, h * 0.1f, w * 0.9f, h * 0.1f, w * 0.82f, h * 0.7f, w * 0.5f, h * 0.98f, w * 0.18f, h * 0.7f), c.primary)
            ellipseAt(px, w, h, w * 0.5f, h * 0.42f, w * 0.28f, h * 0.16f, 0)
            PixelOps.rect(px, w, h, (w * 0.32f).toInt(), (h * 0.3f).toInt(), (w * 0.68f).toInt(), (h * 0.4f).toInt(), c.primary, true)
        }
        part("beard_goatee", "Goatee", "Facial Hair", 24, 20, "Chin goatee with moustache") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.28f, h * 0.45f, w * 0.72f, h * 0.45f, w * 0.6f, h * 0.95f, w * 0.4f, h * 0.95f), c.primary)
            PixelOps.rect(px, w, h, (w * 0.3f).toInt(), (h * 0.2f).toInt(), (w * 0.7f).toInt(), (h * 0.32f).toInt(), c.primary, true)
        }
        part("mustache", "Moustache", "Facial Hair", 24, 10, "Handlebar moustache") { px, w, h, c ->
            quad(px, w, h, poly(2f, h * 0.7f, w * 0.5f, h * 0.2f, w - 2f, h * 0.7f, w * 0.5f, h * 0.55f), c.primary)
            PixelOps.brush(px, w, h, 3f, h * 0.72f, h * 0.18f, c.primary)
            PixelOps.brush(px, w, h, w - 3f, h * 0.72f, h * 0.18f, c.primary)
        }
        part("stubble", "Stubble", "Facial Hair", 32, 16, "Dotted stubble layer") { px, w, h, c ->
            var seed = 12345
            for (y in 0 until h) for (x in 0 until w) {
                seed = seed * 1103515245 + 12345
                if ((seed ushr 16) % 7 == 0) PixelOps.plot(px, w, h, x, y, Colors.withAlpha(c.primary, 150))
            }
        }

        // ---------------- Hats
        part("hat_cap", "Cap", "Hats", 32, 16, "Baseball cap with a brim") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.6f, w * 0.4f, h * 0.34f, c.primary)
            PixelOps.rect(px, w, h, (w * 0.5f).toInt(), (h * 0.62f).toInt(), (w - 1).toInt(), (h * 0.82f).toInt(), Colors.scale(c.primary, 0.85f), true)
        }
        part("hat_top", "Top Hat", "Hats", 32, 24, "Tall formal top hat") { px, w, h, c ->
            PixelOps.rect(px, w, h, (w * 0.28f).toInt(), 1, (w * 0.72f).toInt(), (h * 0.72f).toInt(), c.primary, true)
            PixelOps.rect(px, w, h, (w * 0.28f).toInt(), (h * 0.6f).toInt(), (w * 0.72f).toInt(), (h * 0.72f).toInt(), c.accent, true)
            PixelOps.rect(px, w, h, (w * 0.08f).toInt(), (h * 0.72f).toInt(), (w * 0.92f).toInt(), (h * 0.86f).toInt(), c.primary, true)
        }
        part("hat_beanie", "Beanie", "Hats", 32, 16, "Knitted beanie with a pom pom") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.7f, w * 0.42f, h * 0.38f, c.primary)
            PixelOps.rect(px, w, h, (w * 0.1f).toInt(), (h * 0.62f).toInt(), (w * 0.9f).toInt(), (h * 0.86f).toInt(), Colors.scale(c.primary, 0.8f), true)
            ellipseAt(px, w, h, w * 0.5f, h * 0.2f, w * 0.12f, h * 0.14f, c.accent)
        }
        part("hat_crown", "Crown", "Hats", 24, 16, "Gold crown with three points") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.1f, h * 0.9f, w * 0.1f, h * 0.35f, w * 0.3f, h * 0.6f, w * 0.5f, h * 0.2f, w * 0.7f, h * 0.6f, w * 0.9f, h * 0.35f, w * 0.9f, h * 0.9f), 0xFFFFC107.toInt())
            PixelOps.rect(px, w, h, (w * 0.1f).toInt(), (h * 0.78f).toInt(), (w * 0.9f).toInt(), (h * 0.9f).toInt(), 0xFFF9A825.toInt(), true)
            ellipseAt(px, w, h, w * 0.5f, h * 0.7f, w * 0.06f, h * 0.08f, 0xFFE53935.toInt())
        }
        part("hat_helmet", "Helmet", "Hats", 32, 20, "Round helmet with a visor") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.55f, w * 0.44f, h * 0.42f, c.primary)
            quad(px, w, h, poly(w * 0.06f, h * 0.55f, w * 0.94f, h * 0.55f, w * 0.86f, h * 0.8f, w * 0.14f, h * 0.8f), 0xCC9FD8FF.toInt())
            PixelOps.rect(px, w, h, (w * 0.44f).toInt(), 1, (w * 0.56f).toInt(), (h * 0.22f).toInt(), c.accent, true)
        }

        // ---------------- Tops
        part("top_shirt", "T-Shirt", "Tops", 32, 32, "Short sleeved shirt") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.24f, h * 0.2f, w * 0.76f, h * 0.2f, w * 0.8f, h * 0.9f, w * 0.2f, h * 0.9f), c.primary)
            quad(px, w, h, poly(w * 0.24f, h * 0.2f, w * 0.05f, h * 0.42f, w * 0.16f, h * 0.5f, w * 0.26f, h * 0.34f), c.primary)
            quad(px, w, h, poly(w * 0.76f, h * 0.2f, w * 0.95f, h * 0.42f, w * 0.84f, h * 0.5f, w * 0.74f, h * 0.34f), c.primary)
            ellipseAt(px, w, h, w * 0.5f, h * 0.2f, w * 0.12f, h * 0.06f, Colors.scale(c.primary, 0.75f))
        }
        part("top_jacket", "Jacket", "Tops", 32, 32, "Open jacket with a collar") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.22f, h * 0.16f, w * 0.78f, h * 0.16f, w * 0.84f, h * 0.94f, w * 0.16f, h * 0.94f), c.primary)
            quad(px, w, h, poly(w * 0.42f, h * 0.16f, w * 0.58f, h * 0.16f, w * 0.5f, h * 0.6f), Colors.scale(c.primary, 0.6f))
            quad(px, w, h, poly(w * 0.22f, h * 0.16f, w * 0.42f, h * 0.16f, w * 0.36f, h * 0.4f), c.secondary)
            quad(px, w, h, poly(w * 0.58f, h * 0.16f, w * 0.78f, h * 0.16f, w * 0.64f, h * 0.4f), c.secondary)
            PixelOps.rect(px, w, h, (w * 0.46f).toInt(), (h * 0.5f).toInt(), (w * 0.54f).toInt(), (h * 0.58f).toInt(), c.accent, true)
        }
        part("top_dress", "Dress", "Tops", 32, 32, "Flared dress") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.36f, h * 0.18f, w * 0.64f, h * 0.18f, w * 0.66f, h * 0.55f, w * 0.34f, h * 0.55f), c.primary)
            quad(px, w, h, poly(w * 0.34f, h * 0.55f, w * 0.66f, h * 0.55f, w * 0.9f, h * 0.94f, w * 0.1f, h * 0.94f), c.primary)
            PixelOps.rect(px, w, h, (w * 0.33f).toInt(), (h * 0.52f).toInt(), (w * 0.67f).toInt(), (h * 0.57f).toInt(), c.accent, true)
        }
        part("top_armor", "Armour", "Tops", 32, 32, "Plated chest armour with shoulder pads") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.24f, h * 0.2f, w * 0.76f, h * 0.2f, w * 0.8f, h * 0.86f, w * 0.2f, h * 0.86f), 0xFFB0BEC5.toInt())
            ellipseAt(px, w, h, w * 0.16f, h * 0.28f, w * 0.14f, h * 0.12f, 0xFF90A4AE.toInt())
            ellipseAt(px, w, h, w * 0.84f, h * 0.28f, w * 0.14f, h * 0.12f, 0xFF90A4AE.toInt())
            PixelOps.rect(px, w, h, (w * 0.42f).toInt(), (h * 0.4f).toInt(), (w * 0.58f).toInt(), (h * 0.56f).toInt(), c.accent, true)
            PixelOps.shadeVertical(px, w, h, 1.1f, 0.82f)
        }

        // ---------------- Shoes
        part("shoe_sneaker", "Sneaker", "Shoes", 24, 12, "Sport shoe with a sole") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.06f, h * 0.3f, w * 0.6f, h * 0.25f, w * 0.96f, h * 0.5f, w * 0.96f, h * 0.72f, w * 0.06f, h * 0.72f), c.primary)
            PixelOps.rect(px, w, h, 1, (h * 0.72f).toInt(), w - 2, h - 1, 0xFFF5F5F5.toInt(), true)
            PixelOps.line(px, w, h, w * 0.3f, h * 0.4f, w * 0.45f, h * 0.6f, 1.2f, c.accent)
        }
        part("shoe_boot", "Boot", "Shoes", 20, 20, "Tall leather boot") { px, w, h, c ->
            PixelOps.rect(px, w, h, (w * 0.3f).toInt(), 1, (w * 0.7f).toInt(), (h * 0.66f).toInt(), c.primary, true)
            quad(px, w, h, poly(w * 0.3f, h * 0.66f, w * 0.7f, h * 0.66f, w * 0.94f, h * 0.9f, w * 0.12f, h * 0.9f), c.primary)
            PixelOps.rect(px, w, h, (w * 0.12f).toInt(), (h * 0.86f).toInt(), (w * 0.94f).toInt(), h - 1, Colors.scale(c.primary, 0.55f), true)
        }
        part("shoe_sandal", "Sandal", "Shoes", 20, 10, "Simple sandal with a strap") { px, w, h, c ->
            PixelOps.rect(px, w, h, 1, (h * 0.6f).toInt(), w - 2, h - 1, c.secondary, true)
            PixelOps.line(px, w, h, w * 0.2f, h * 0.62f, w * 0.5f, h * 0.2f, 1.2f, c.primary)
            PixelOps.line(px, w, h, w * 0.5f, h * 0.2f, w * 0.8f, h * 0.62f, 1.2f, c.primary)
        }

        // ---------------- Wheels
        part("wheel_car", "Car Tyre", "Wheels", 32, 32, "Rubber tyre with a metal rim and tread", outlined = false) { px, w, h, c ->
            val cx = w * 0.5f
            val cy = h * 0.5f
            ring(px, w, h, cx, cy, w * 0.4f, w * 0.13f, 0xFF1E1E22.toInt())
            ellipseAt(px, w, h, cx, cy, w * 0.33f, h * 0.33f, 0)
            // tread blocks
            for (i in 0 until 12) {
                val a = i * Math.PI / 6.0
                PixelOps.brush(px, w, h,
                    cx + cos(a).toFloat() * w * 0.44f, cy + sin(a).toFloat() * h * 0.44f,
                    w * 0.055f, 0xFF101014.toInt())
            }
            // rim
            ellipseAt(px, w, h, cx, cy, w * 0.24f, h * 0.24f, 0xFFB0BEC5.toInt())
            ellipseAt(px, w, h, cx, cy, w * 0.1f, h * 0.1f, 0xFF62727B.toInt())
            for (i in 0 until 5) {
                val a = i * 2.0 * Math.PI / 5
                PixelOps.brush(px, w, h, cx + cos(a).toFloat() * w * 0.17f, cy + sin(a).toFloat() * h * 0.17f, w * 0.045f, 0xFF8FA0AA.toInt())
            }
        }
        part("wheel_motor", "Motor Wheel", "Wheels", 32, 32, "Motorcycle wheel: thin tyre, fat hub, disc", outlined = false) { px, w, h, c ->
            val cx = w * 0.5f
            val cy = h * 0.5f
            ring(px, w, h, cx, cy, w * 0.44f, 1.8f, 0xFF15151A.toInt())
            ring(px, w, h, cx, cy, w * 0.34f, 1.1f, 0xFF9AA4AE.toInt())
            for (i in 0 until 8) {
                val a = i * Math.PI / 4.0
                PixelOps.line(px, w, h, cx, cy, cx + cos(a).toFloat() * w * 0.34f, cy + sin(a).toFloat() * h * 0.34f, 0.7f, 0xFFCBD5DB.toInt())
            }
            ellipseAt(px, w, h, cx, cy, w * 0.14f, h * 0.14f, 0xFF6E7B85.toInt())
            ellipseAt(px, w, h, cx, cy, w * 0.06f, h * 0.06f, c.accent)
        }
        part("wheel_bike", "Bicycle Wheel", "Wheels", 32, 32, "Spoked bicycle wheel with a hub", outlined = false) { px, w, h, c ->
            val cx = w * 0.5f
            val cy = h * 0.5f
            ring(px, w, h, cx, cy, w * 0.46f, 1.0f, 0xFF22262B.toInt())
            for (i in 0 until 12) {
                val a = i * Math.PI / 6.0
                PixelOps.line(px, w, h, cx, cy, cx + cos(a).toFloat() * w * 0.45f, cy + sin(a).toFloat() * h * 0.45f, 0.5f, 0xFFD7DEE3.toInt())
            }
            ellipseAt(px, w, h, cx, cy, w * 0.08f, h * 0.08f, c.secondary)
        }

        // ---------------- Lights
        part("light_red", "Red Light", "Lights", 16, 16, "Glowing red tail light") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.46f, h * 0.46f, Colors.withAlpha(0xFFFF1744.toInt(), 60))
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.34f, h * 0.34f, Colors.withAlpha(0xFFFF1744.toInt(), 140))
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.22f, h * 0.22f, 0xFFFF5252.toInt())
            ellipseAt(px, w, h, w * 0.42f, h * 0.4f, w * 0.06f, h * 0.06f, 0xFFFFFFFF.toInt())
        }
        part("light_head", "Headlight", "Lights", 24, 16, "Warm headlight with a beam") { px, w, h, c ->
            quad(px, w, h, poly(w * 0.1f, h * 0.3f, w * 0.9f, h * 0.05f, w * 0.9f, h * 0.95f, w * 0.1f, h * 0.7f), Colors.withAlpha(0xFFFFF59D.toInt(), 90))
            ellipseAt(px, w, h, w * 0.2f, h * 0.5f, w * 0.12f, h * 0.18f, 0xFFFFF176.toInt())
        }
        part("light_lamp", "Lamp Bulb", "Lights", 16, 16, "Round bulb for street lamps") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.45f, w * 0.32f, h * 0.34f, 0xFFFFF9C4.toInt())
            PixelOps.rect(px, w, h, (w * 0.36f).toInt(), (h * 0.72f).toInt(), (w * 0.64f).toInt(), (h * 0.9f).toInt(), 0xFF9E9E9E.toInt(), true)
        }

        // ---------------- Effects
        part("star_5", "Star (5 point)", "Effects", 24, 24, "Five point star for pickups and scores") { px, w, h, c ->
            quad(px, w, h, PixelOps.star(w * 0.5f, h * 0.5f, w * 0.46f, h * 0.2f, 5), 0xFFFFD54F.toInt())
            quad(px, w, h, PixelOps.star(w * 0.5f, h * 0.5f, w * 0.24f, h * 0.1f, 5), 0xFFFFF176.toInt())
        }
        part("star_4", "Sparkle", "Effects", 16, 16, "Four point sparkle") { px, w, h, c ->
            quad(px, w, h, PixelOps.star(w * 0.5f, h * 0.5f, w * 0.5f, h * 0.12f, 4, -90f), 0xFFFFFFFF.toInt())
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.12f, h * 0.12f, 0xFFFFFFFF.toInt())
        }
        part("heart", "Heart", "Effects", 16, 14, "Health / life pickup") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.32f, h * 0.35f, w * 0.22f, h * 0.24f, 0xFFE53935.toInt())
            ellipseAt(px, w, h, w * 0.68f, h * 0.35f, w * 0.22f, h * 0.24f, 0xFFE53935.toInt())
            quad(px, w, h, poly(w * 0.1f, h * 0.4f, w * 0.9f, h * 0.4f, w * 0.5f, h * 0.95f), 0xFFE53935.toInt())
            ellipseAt(px, w, h, w * 0.3f, h * 0.3f, w * 0.06f, h * 0.06f, 0xFFFF8A80.toInt())
        }
        part("coin", "Coin", "Effects", 16, 16, "Golden coin with a shine") { px, w, h, c ->
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.42f, h * 0.42f, 0xFFF9A825.toInt())
            ellipseAt(px, w, h, w * 0.5f, h * 0.5f, w * 0.3f, h * 0.3f, 0xFFFFD54F.toInt())
            PixelOps.line(px, w, h, w * 0.36f, h * 0.62f, w * 0.6f, h * 0.36f, 1f, 0xFFFFF8E1.toInt())
        }
        part("smoke_puff", "Smoke Puff", "Effects", 20, 20, "Soft round puff for dust and exhaust") { px, w, h, c ->
            val base = Colors.withAlpha(0xFFECEFF1.toInt(), 170)
            ellipseAt(px, w, h, w * 0.4f, h * 0.55f, w * 0.3f, h * 0.28f, base)
            ellipseAt(px, w, h, w * 0.6f, h * 0.45f, w * 0.26f, h * 0.24f, base)
            ellipseAt(px, w, h, w * 0.5f, h * 0.62f, w * 0.22f, h * 0.2f, base)
        }
        part("bullet", "Bullet", "Effects", 8, 16, "Rifle bullet with a tracer tip") { px, w, h, c ->
            PixelOps.rect(px, w, h, 0, (h * 0.3f).toInt(), w - 1, h - 1, 0xFFFFD54F.toInt(), true)
            quad(px, w, h, poly(0f, h * 0.32f, w - 1f, h * 0.32f, w * 0.5f, 0f), 0xFFFFF59D.toInt())
        }
    }
}
