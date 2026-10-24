package com.sengine.project

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads asset packs (`.zip`) and turns them into a safe import plan.
 *
 * The Android side hands the raw bytes to [read] and then writes the accepted entries through
 * [Project.writeAssetBytes]. All the policy lives here so it can be unit tested without Android:
 *
 * * directory entries, `__MACOSX/` metadata, hidden files and OS junk (`Thumbs.db`, `.DS_Store`) are skipped,
 * * only extensions the engine can consume are imported (images, scripts, sounds, shaders, models, animations),
 * * absolute paths and `..` segments are rejected (zip-slip protection),
 * * every entry gets a flat, unique asset name; nested folders are flattened by default,
 * * per-entry and total size limits protect low-memory devices,
 * * sprite sheets can be imported together with a generated `.anim` clip ([sheet]) — the plan then also
 *   contains the animation JSON to write.
 */
object ZipAssetImporter {

    /** Extensions the engine can actually use. */
    val allowedExtensions = setOf(
        "png", "jpg", "jpeg", "webp", "bmp",
        "js", "bp", "glsl", "anim", "obj",
        "wav", "ogg", "mp3", "m4a",
        "sprite", "txt", "json", "md",
    )

    class Entry(
        val originalPath: String,
        val name: String,
        val bytes: ByteArray,
        val kind: String,
    ) {
        val size: Int get() = bytes.size
    }

    class Plan(
        val entries: List<Entry>,
        val skipped: List<String>,
        val totalBytes: Int,
        /** Optional generated `.anim` clip name and JSON when the caller asked for a sprite sheet. */
        var generatedAnimation: String? = null,
        var generatedAnimationName: String? = null,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
        fun summary(): String =
            "${entries.size} asset(s), ${totalBytes / 1024} KB" +
                if (skipped.isEmpty()) "" else ", ${skipped.size} skipped"
    }

    class Options(
        /** Skip entries bigger than this many bytes. */
        val maxEntryBytes: Int = 24 * 1024 * 1024,
        /** Abort (and report) when the whole pack is bigger than this. */
        val maxTotalBytes: Long = 256L * 1024 * 1024,
        /** Keep the folder structure in the generated name (folders become `_` prefixes). */
        val keepFolders: Boolean = false,
    )

    /**
     * Parses [bytes] as a zip archive and returns an import plan. Never throws for malformed input:
     * a corrupt archive simply produces an empty plan with the problem recorded in [Plan.skipped].
     */
    fun read(bytes: ByteArray, options: Options = Options(), existing: Collection<String> = emptyList()): Plan {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return Plan(emptyList(), listOf("not a zip archive"), 0)
        }
        val accepted = ArrayList<Entry>()
        val skipped = ArrayList<String>()
        var total = 0L
        val used = HashSet(existing.map { it.lowercase() })
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var guard = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (guard++ > 4096) { skipped.add("archive has too many entries"); break }
                    val path = entry.name
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    if (isJunk(path)) { skipped.add(path); zip.closeEntry(); continue }
                    val ext = path.substringAfterLast('.', "").lowercase()
                    if (ext !in allowedExtensions) { skipped.add(path); zip.closeEntry(); continue }
                    if (!isSafePath(path)) { skipped.add("$path (unsafe path)"); zip.closeEntry(); continue }
                    if (entry.size > options.maxEntryBytes) { skipped.add("$path (too large)"); zip.closeEntry(); continue }
                    val data = readEntry(zip, options.maxEntryBytes)
                    zip.closeEntry()
                    if (data == null) { skipped.add("$path (too large or unreadable)"); continue }
                    total += data.size
                    if (total > options.maxTotalBytes) { skipped.add("$path (pack over size limit)"); break }
                    val name = uniqueName(normalize(path, options.keepFolders), used)
                    accepted.add(Entry(path, name, data, ext))
                }
            }
        } catch (e: Exception) {
            skipped.add("not a readable zip: ${e.message ?: e.javaClass.simpleName}")
        }
        if (accepted.isEmpty() && skipped.isEmpty()) skipped.add("no importable assets found in the archive")
        return Plan(accepted, skipped, total.toInt())
    }

    private fun readEntry(zip: ZipInputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var count = 0
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            count += n
            if (count > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Creates a horizontal sprite-sheet clip for an imported image. */
    fun sheet(fileName: String, columns: Int, rows: Int, fps: Float, loop: Boolean = true, cellWidth: Int = 0, cellHeight: Int = 0): Pair<String, String> {
        val cols = columns.coerceIn(1, 64)
        val rws = rows.coerceIn(1, 64)
        val animName = fileName.substringBeforeLast('.') + ".anim"
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"texture\": \"").append(fileName).append("\",\n")
        sb.append("  \"columns\": ").append(cols).append(",\n")
        sb.append("  \"rows\": ").append(rws).append(",\n")
        sb.append("  \"fps\": ").append(fps).append(",\n")
        sb.append("  \"loop\": ").append(loop).append(",\n")
        sb.append("  \"frames\": [")
        val count = cols * rws
        for (i in 0 until count) {
            if (i > 0) sb.append(",")
            sb.append(i)
        }
        sb.append("]")
        if (cellWidth > 0) sb.append(",\n  \"cellWidth\": ").append(cellWidth)
        if (cellHeight > 0) sb.append(",\n  \"cellHeight\": ").append(cellHeight)
        sb.append("\n}\n")
        return animName to sb.toString()
    }

    fun isJunk(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (p.startsWith("__MACOSX/") || p.contains("/__MACOSX/")) return true
        val file = p.substringAfterLast('/')
        if (file.isEmpty()) return true
        if (file.startsWith(".")) return true
        if (p.split('/').any { it.startsWith(".") && it != "." && it != ".." }) return true
        val lowered = file.lowercase()
        return lowered == "thumbs.db" || lowered == "desktop.ini" || lowered.endsWith(".ds_store")
    }

    fun isSafePath(path: String): Boolean {
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains(":")) return false
        val parts = path.replace('\\', '/').split('/')
        return parts.none { it == ".." }
    }

    /** Flattens a zip path into a single asset file name. */
    fun normalize(path: String, keepFolders: Boolean): String {
        var p = path.replace('\\', '/').trim('/')
        if (p.isEmpty()) return "asset"
        p = p.substringAfterLast('/').takeIf { !keepFolders } ?: p
        p = p.replace('/', '_').replace(' ', '_')
        val cleaned = p.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        return if (cleaned.isBlank() || cleaned.startsWith(".")) "asset_$cleaned" else cleaned
    }

    /** Appends `_1`, `_2`, ... until the name is unused. */
    fun uniqueName(name: String, used: MutableSet<String>): String {
        var candidate = name
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (used.contains(candidate.lowercase())) {
            candidate = if (ext.isEmpty()) "${stem}_$i" else "${stem}_$i.$ext"
            i++
        }
        used.add(candidate.lowercase())
        return candidate
    }
}
