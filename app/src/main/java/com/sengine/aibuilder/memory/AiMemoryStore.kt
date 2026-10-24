package com.sengine.aibuilder.memory

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Redacts common credential forms before prompts are stored or forwarded to a provider. */
object SensitiveText {
    private val providerKeys = listOf(
        Regex("(?i)\\bsk-(?:or-v1-|ant-api[0-9]+-|proj-)?[A-Za-z0-9_-]{16,}\\b"),
        Regex("\\bAIza[0-9A-Za-z_-]{30,}\\b"),
    )
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{16,}={0,2}")
    private val assignments = Regex("(?i)\\b(api[_ -]?key|password|passwd|secret|access[_ -]?token|refresh[_ -]?token)\\s*[:=]\\s*[^\\s,;]{6,}")

    fun redact(text: String): String = providerKeys.fold(text) { value, pattern -> value.replace(pattern, "[REDACTED]") }
        .replace(bearer, "Bearer [REDACTED]")
        .replace(assignments) { match -> "${match.groupValues[1]}=[REDACTED]" }
        .take(8_000)
}

data class AiMemoryEvent(
    val clientId: String,
    val projectKey: String,
    val kind: String,
    val payload: JSONObject,
    val createdAt: Long,
)

/** Device-private AI history and project/build memory, outside the S Engine project directory. */
class AiMemoryStore(private val root: File) {
    private val lock = Any()
    private val rollbackDir = File(root, "rollback")

    fun appendMessage(projectKey: String, role: String, text: String, model: String? = null): AiMemoryEvent? {
        if (role != "user" && role != "assistant") return null
        val safeText = SensitiveText.redact(text).take(4_000)
        val payload = JSONObject().put("role", role).put("text", safeText)
        if (!model.isNullOrBlank()) payload.put("model", model.take(120))
        return append(projectKey, "message", payload)
    }

    fun appendDecision(projectKey: String, summary: String, category: String = "design"): AiMemoryEvent =
        append(projectKey, "decision", JSONObject()
            .put("category", category.take(60))
            .put("summary", SensitiveText.redact(summary).take(1_000)))

    fun appendBuildEvent(
        projectKey: String,
        summary: String,
        status: String,
        affected: List<String> = emptyList(),
        errors: List<String> = emptyList(),
        changes: List<String> = emptyList(),
    ): AiMemoryEvent = append(projectKey, "build", JSONObject()
        .put("summary", SensitiveText.redact(summary).take(1_000))
        .put("status", status.take(40))
        .put("affected", JSONArray().also { a -> affected.take(40).forEach { a.put(SensitiveText.redact(it).take(100)) } })
        .put("changes", JSONArray().also { a -> changes.take(20).forEach { a.put(SensitiveText.redact(it).take(300)) } })
        .put("errors", JSONArray().also { a -> errors.take(12).forEach { a.put(SensitiveText.redact(it).take(500)) } }))

    fun append(projectKey: String, kind: String, payload: JSONObject): AiMemoryEvent {
        require(validKey(projectKey)) { "Memory project key is invalid" }
        require(kind in setOf("message", "decision", "build", "repair")) { "Unsupported memory event type" }
        val event = AiMemoryEvent(UUID.randomUUID().toString(), projectKey, kind, JSONObject(payload.toString()), System.currentTimeMillis())
        synchronized(lock) {
            val all = read(projectKey).toMutableList()
            all += event
            write(projectKey, all.takeLast(MAX_EVENTS))
        }
        return event
    }

    fun events(projectKey: String): List<AiMemoryEvent> = synchronized(lock) { read(projectKey) }

    /** A bounded, project-isolated context package; actual project state is supplied separately. */
    fun context(projectKey: String, maxChars: Int = 8_000): JSONObject {
        val all = events(projectKey)
        val messages = all.filter { it.kind == "message" }.takeLast(8).map { event ->
            JSONObject().put("role", event.payload.optString("role")).put("content", event.payload.optString("text").take(900))
        }
        val decisions = all.filter { it.kind == "decision" }.takeLast(6).map { event ->
            JSONObject().put("category", event.payload.optString("category"))
                .put("summary", event.payload.optString("summary").take(350))
        }
        val builds = all.filter { it.kind == "build" || it.kind == "repair" }.takeLast(4).map { event ->
            val errors = JSONArray()
            val sourceErrors = event.payload.optJSONArray("errors") ?: JSONArray()
            for (i in 0 until minOf(sourceErrors.length(), 3)) errors.put(sourceErrors.optString(i).take(180))
            JSONObject().put("status", event.payload.optString("status"))
                .put("summary", event.payload.optString("summary").take(300))
                .put("errors", errors)
        }
        val result = JSONObject().put("recentMessages", JSONArray().also { a -> messages.forEach { a.put(it) } })
            .put("projectDecisions", JSONArray().also { a -> decisions.forEach { a.put(it) } })
            .put("recentBuilds", JSONArray().also { a -> builds.forEach { a.put(it) } })
        val limit = maxChars.coerceAtLeast(0)
        // Drop oldest context first, never truncate JSON structure or cross project boundaries.
        while (result.toString().length > limit) {
            val recentMessages = result.optJSONArray("recentMessages") ?: JSONArray()
            val projectDecisions = result.optJSONArray("projectDecisions") ?: JSONArray()
            val recentBuilds = result.optJSONArray("recentBuilds") ?: JSONArray()
            when {
                recentMessages.length() > 2 -> recentMessages.remove(0)
                projectDecisions.length() > 1 -> projectDecisions.remove(0)
                recentBuilds.length() > 1 -> recentBuilds.remove(0)
                recentMessages.length() > 0 -> recentMessages.remove(0)
                projectDecisions.length() > 0 -> projectDecisions.remove(0)
                recentBuilds.length() > 0 -> recentBuilds.remove(0)
                else -> break
            }
        }
        return result
    }

    fun projectKeys(): List<String> = synchronized(lock) {
        (root.listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file -> try { JSONObject(file.readText()).optString("projectKey").takeIf(::validKey) } catch (_: Exception) { null } }
            .distinct().sorted()
    }

    fun moveProject(from: String, to: String) = synchronized(lock) {
        require(validKey(from) && validKey(to)) { "Memory project key is invalid" }
        if (from == to) return@synchronized
        val moved = read(from).map { it.copy(projectKey = to) }
        val combined = LinkedHashMap<String, AiMemoryEvent>()
        read(to).forEach { combined[it.clientId] = it }
        moved.forEach { combined[it.clientId] = it }
        write(to, combined.values.sortedBy { it.createdAt }.takeLast(MAX_EVENTS))
        dataFile(from).delete()
    }

    /** Rollback snapshots and hashes for generated assets are stored only on this device. */
    fun pushRollback(projectKey: String, sceneName: String, sceneJson: String): String {
        require(validKey(projectKey) && validKey(sceneName)) { "Rollback target is invalid" }
        require(sceneJson.length <= MAX_ROLLBACK_CHARS) { "Rollback snapshot exceeds the size limit" }
        synchronized(lock) {
            rollbackDir.mkdirs()
            val file = rollbackFile(projectKey, sceneName)
            val existing = readRollback(file)
            val token = UUID.randomUUID().toString()
            val entries = JSONArray()
            existing.takeLast(2).forEach { entries.put(it) }
            entries.put(JSONObject().put("token", token).put("createdAt", System.currentTimeMillis()).put("sceneJson", sceneJson))
            atomicWrite(file, JSONObject().put("projectKey", projectKey).put("sceneName", sceneName).put("snapshots", entries).toString())
            return token
        }
    }

    fun latestRollback(projectKey: String, sceneName: String): Pair<String, String>? = synchronized(lock) {
        val file = rollbackFile(projectKey, sceneName)
        val latest = readRollback(file).lastOrNull() ?: return@synchronized null
        latest.optString("token") to latest.optString("sceneJson")
    }

    fun removeLatestRollback(projectKey: String, sceneName: String, token: String) = synchronized(lock) {
        val file = rollbackFile(projectKey, sceneName)
        val entries = readRollback(file).filterNot { it.optString("token") == token }
        if (entries.isEmpty()) file.delete() else atomicWrite(file, JSONObject()
            .put("projectKey", projectKey).put("sceneName", sceneName)
            .put("snapshots", JSONArray().also { a -> entries.forEach { a.put(it) } }).toString())
    }

    fun clearProject(projectKey: String) = synchronized(lock) {
        dataFile(projectKey).delete()
        val prefix = hash(projectKey).take(24)
        (rollbackDir.listFiles() ?: emptyArray()).filter { it.name.startsWith("$prefix-") }.forEach { it.delete() }
    }

    fun clearAll() = synchronized(lock) {
        root.deleteRecursively()
        rollbackDir.mkdirs()
    }

    private fun read(projectKey: String): List<AiMemoryEvent> {
        val file = dataFile(projectKey)
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val key = root.optString("projectKey")
            if (key != projectKey) return emptyList()
            val array = root.optJSONArray("events") ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val payload = item.optJSONObject("payload") ?: JSONObject()
                AiMemoryEvent(
                    item.optString("clientId"), key, item.optString("kind"), JSONObject(payload.toString()),
                    item.optLong("createdAt", 0L),
                ).takeIf { it.clientId.isNotBlank() && it.kind in setOf("message", "decision", "build", "repair") }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun write(projectKey: String, events: List<AiMemoryEvent>) {
        root.mkdirs()
        val array = JSONArray()
        events.takeLast(MAX_EVENTS).forEach { event ->
            array.put(JSONObject().put("clientId", event.clientId).put("kind", event.kind)
                .put("payload", event.payload).put("createdAt", event.createdAt))
        }
        atomicWrite(dataFile(projectKey), JSONObject().put("projectKey", projectKey).put("events", array).toString())
    }

    private fun readRollback(file: File): List<JSONObject> = try {
        val array = JSONObject(file.readText()).optJSONArray("snapshots") ?: JSONArray()
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    } catch (_: Exception) { emptyList() }

    private fun dataFile(key: String) = File(root, "${hash(key)}.json")
    private fun rollbackFile(key: String, scene: String) = File(rollbackDir, "${hash(key).take(24)}-${hash(scene).take(16)}.json")

    private fun validKey(key: String): Boolean = key.isNotBlank() && key.length <= 100 && key.none { it.isISOControl() }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(file)) {
            file.writeText(content)
            temp.delete()
        }
    }

    companion object {
        private const val MAX_EVENTS = 300
        private const val MAX_ROLLBACK_CHARS = 2_000_000
    }
}
