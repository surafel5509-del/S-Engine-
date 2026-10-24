package com.sengine.aibuilder

import com.sengine.aibuilder.memory.AiMemoryStore
import com.sengine.aibuilder.memory.SensitiveText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AiMemoryStoreTest {
    @Test
    fun memoriesAreProjectIsolatedAndKeepLocalBuildHistory() {
        val dir = Files.createTempDirectory("ai-memory").toFile()
        val store = AiMemoryStore(dir)
        store.appendMessage("Project A", "user", "Create a coin platformer")
        store.appendDecision("Project A", "Prefer bright colors")
        store.appendBuildEvent("Project A", "Platformer saved", "success")
        store.appendMessage("Project B", "user", "Create a space shooter")

        val contextA = store.context("Project A").toString()
        val contextB = store.context("Project B").toString()
        assertTrue(contextA.contains("coin platformer"))
        assertTrue(contextA.contains("bright colors"))
        assertTrue(contextA.contains("Platformer saved"))
        assertFalse(contextA.contains("space shooter"))
        assertTrue(contextB.contains("space shooter"))
        assertFalse(contextB.contains("coin platformer"))
        assertEquals(3, store.events("Project A").size)
        assertEquals(1, store.events("Project B").size)
    }

    @Test
    fun retrievedContextNeverExceedsItsCharacterBudget() {
        val dir = Files.createTempDirectory("ai-memory-context").toFile()
        val store = AiMemoryStore(dir)
        repeat(8) { store.appendMessage("Bounded", "user", "x".repeat(4_000)) }
        repeat(4) { store.appendBuildEvent("Bounded", "y".repeat(1_000), "failed", errors = listOf("z".repeat(500))) }
        assertTrue(store.context("Bounded", 2_000).toString().length <= 2_000)
        assertTrue(store.context("Bounded").toString().length <= 8_000)
    }

    @Test
    fun secretsAreRedactedAndRollbackSnapshotsAreLocalAndConsumable() {
        val dir = Files.createTempDirectory("ai-memory-private").toFile()
        val store = AiMemoryStore(dir)
        val fakeProviderKey = "sk-or-v1-" + "abcdefghijklmnopqrstuvwxyz0123456789"
        val raw = "Change speed. api_key=abcdefghijk and $fakeProviderKey"
        val event = store.appendMessage("Demo", "user", raw)!!
        val saved = event.payload.optString("text")
        assertFalse(saved.contains("abcdefghijk"))
        assertFalse(saved.contains("sk-or-v1-"))
        val fakeClaudeKey = "sk-ant-api03-" + "abcdefghijklmnopqrstuvwxyz0123456789"
        val fakeGeminiKey = "AIza" + "0123456789abcdefghijklmnopqrstuvwxyzABCDE"
        assertFalse(SensitiveText.redact(fakeClaudeKey).contains("sk-ant-api03-"))
        assertFalse(SensitiveText.redact(fakeGeminiKey).contains("AIza"))
        assertTrue(SensitiveText.redact("password: letmein123").contains("[REDACTED]"))

        val token = store.pushRollback("Demo", "Main", "{\"name\":\"Main\"}")
        val latest = store.latestRollback("Demo", "Main")
        assertNotNull(latest)
        assertEquals(token, latest!!.first)
        assertEquals("{\"name\":\"Main\"}", latest.second)
        store.removeLatestRollback("Demo", "Main", token)
        assertNull(store.latestRollback("Demo", "Main"))
        store.clearProject("Demo")
        assertTrue(store.events("Demo").isEmpty())
    }
}
