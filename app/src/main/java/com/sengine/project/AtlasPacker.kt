package com.sengine.project

/**
 * Rectangle atlas packing for the 2D texture pipeline.
 *
 * The packer is deterministic: the same input list always produces the same atlas, which matters
 * because scene files store the generated UVs. Pixels are grouped into square power-of-two pages
 * (the size every mobile GPU supports), heights are sorted to minimise wasted shelf space, and each
 * sprite gets a gutters of transparent pixels so bilinear filtering cannot bleed neighbouring art.
 */
object AtlasPacker {

    class Item(val name: String, val width: Int, val height: Int)

    class Placement(
        val name: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val page: Int,
    ) {
        /** Normalised UV rectangle: u0, v0 (bottom-left), u1, v1 (top-right) with a Y-flip for GL. */
        fun uv(pageW: Int, pageH: Int): FloatArray {
            val u0 = x.toFloat() / pageW
            val u1 = (x + width).toFloat() / pageW
            val v1 = 1f - y.toFloat() / pageH
            val v0 = 1f - (y + height).toFloat() / pageH
            return floatArrayOf(u0, v0, u1, v1)
        }
    }

    class Page(val width: Int, val height: Int, val placements: List<Placement>) {
        var index = 0
        val usedArea: Int get() = placements.sumOf { it.width * it.height }
    }

    class Result(val pages: List<Page>, val overflow: List<Item>) {
        val pageCount: Int get() = pages.size
        fun placement(name: String): Placement? = pages.firstNotNullOfOrNull { p -> p.placements.firstOrNull { it.name == name } }
        fun pageOf(name: String): Page? = pages.firstOrNull { p -> p.placements.any { it.name == name } }
        /** 0..1, useful for the import dialog's "efficiency" read-out. */
        fun efficiency(): Float {
            if (pages.isEmpty()) return 0f
            var used = 0
            var total = 0
            for (p in pages) { used += p.usedArea; total += p.width * p.height }
            return if (total == 0) 0f else used.toFloat() / total
        }
    }

    /**
     * Packs [items] into pages of at most [maxSize] pixels per side.
     *
     * @param padding transparent gutter around each sprite (default 2 px)
     * @param fixedSize force every page to this size instead of the smallest power of two that fits
     */
    fun pack(items: List<Item>, maxSize: Int = 2048, padding: Int = 2, fixedSize: Int = 0): Result {
        val usable = items.filter { it.width > 0 && it.height > 0 }
        val sorted = usable.sortedWith(compareByDescending<Item> { it.height }.thenByDescending { it.width }.thenBy { it.name })
        val shelves = ArrayList<ShelfPage>()
        val overflow = ArrayList<Item>()
        var current = ShelfPage(maxSize, padding)
        for (item in sorted) {
            if (item.width + padding * 2 > maxSize || item.height + padding * 2 > maxSize) { overflow.add(item); continue }
            if (!current.tryPlace(item)) {
                if (!current.isEmpty()) { shelves.add(current); current = ShelfPage(maxSize, padding) }
                if (!current.tryPlace(item)) { overflow.add(item) }
            }
        }
        if (!current.isEmpty()) shelves.add(current)
        // Shrink each page to the next power of two that contains its content.
        val pages = shelves.mapIndexed { i, p ->
            val side = if (fixedSize > 0) fixedSize else minOf(maxSize, pot(maxOf(p.width, p.height)))
            Page(side, side, ArrayList(p.placements)).also { it.index = i }
        }
        return Result(pages, overflow)
    }

    /** Next power of two, clamped to [min]. */
    fun pot(value: Int, min: Int = 32): Int {
        var v = min.coerceAtLeast(1)
        while (v < value) v = v shl 1
        return v
    }

    private class ShelfPage(val maxSize: Int, val padding: Int) {
        val placements = ArrayList<Placement>()
        var shelfY = 0
        var shelfH = 0
        var shelfX = 0
        /** Content bounds in pixels, including padding. */
        var width = 0
        var height = 0

        fun isEmpty() = placements.isEmpty()
        fun widthUsed() = width
        fun heightUsed() = height

        fun tryPlace(item: Item): Boolean {
            val w = item.width + padding * 2
            val h = item.height + padding * 2
            if (shelfX + w > maxSize) {
                shelfY += shelfH
                shelfX = 0
                shelfH = 0
            }
            if (shelfY + h > maxSize) return false
            val x = shelfX + padding
            val y = shelfY + padding
            placements.add(Placement(item.name, x, y, item.width, item.height, 0))
            shelfX += w
            shelfH = maxOf(shelfH, h)
            width = maxOf(width, shelfX)
            height = maxOf(height, shelfY + shelfH)
            return true
        }

        fun toPage(index: Int): Page = Page(width, height, ArrayList(placements)).also { it.index = index }
    }
}
