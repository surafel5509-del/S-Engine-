package com.sengine.engine.render

/**
 * Byte budget and least-recently-used order for GPU textures. This is pure bookkeeping with no GL
 * calls: [TextureCache] asks it which keys to delete and does the deleting.
 *
 * Textures used in the current frame are pinned. [victimsFor] never returns them, so a texture that
 * this frame's draws reference is not deleted underneath them. If everything resident is pinned, the
 * budget can be exceeded for one frame; [overBudget] reports that.
 *
 * Accounting: bytes are supplied by the caller (width × height × 4 for RGBA8888 uploads).
 */
class TextureBudget(val maxBytes: Long) {

    private class Entry(val bytes: Long, var lastFrame: Long)

    /** Access-ordered: iteration starts with the least recently used key. */
    private val entries = LinkedHashMap<String, Entry>(64, 0.75f, true)

    var usedBytes = 0L
        private set

    /** Frame number. Advanced once per frame by [nextFrame]. */
    var frame = 0L
        private set

    val count: Int get() = entries.size

    fun contains(key: String): Boolean = entries.containsKey(key)

    fun nextFrame() {
        frame++
    }

    /** Marks [key] as used this frame and as most recently used. No-op if [key] is not resident. */
    fun touch(key: String) {
        entries[key]?.lastFrame = frame
    }

    /** Registers [key] as resident with [bytes], pinned to the current frame. Replaces any previous entry. */
    fun add(key: String, bytes: Long) {
        remove(key)
        entries[key] = Entry(bytes, frame)
        usedBytes += bytes
    }

    /** Forgets [key]. Returns true if it was resident. The caller is responsible for deleting the GL object. */
    fun remove(key: String): Boolean {
        val e = entries.remove(key) ?: return false
        usedBytes -= e.bytes
        return true
    }

    /** Result of [plan]: keys to delete (least recently used first) and whether the budget is still exceeded. */
    class Plan(val victims: List<String>, val overBudget: Boolean)

    /**
     * Plans room for [incomingBytes]. Chooses unpinned keys, least recently used first, until the total fits.
     * If the unpinned keys are not enough, [Plan.overBudget] is true and the caller should upload anyway
     * and accept the overage for this frame. Does not change state and does not reorder the LRU list.
     */
    fun plan(incomingBytes: Long): Plan {
        var need = usedBytes + incomingBytes - maxBytes
        if (need <= 0L) return Plan(emptyList(), false)
        val out = ArrayList<String>()
        for ((key, e) in entries) {
            if (need <= 0L) break
            if (e.lastFrame == frame) continue
            out.add(key)
            need -= e.bytes
        }
        return Plan(out, need > 0L)
    }

    fun clear() {
        entries.clear()
        usedBytes = 0L
    }
}
