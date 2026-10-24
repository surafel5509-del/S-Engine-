package com.sengine.render

import com.sengine.engine.render.TextureBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure bookkeeping for the GPU texture budget: accounting, LRU order, frame pinning. */
class TextureBudgetTest {

    @Test
    fun addAndRemoveKeepByteAccounting() {
        val b = TextureBudget(1000)
        b.add("a", 300)
        b.add("b", 200)
        assertEquals(500L, b.usedBytes)
        assertEquals(2, b.count)
        // Re-adding a key replaces its bytes rather than adding to them.
        b.add("a", 100)
        assertEquals(300L, b.usedBytes)
        assertTrue(b.remove("b"))
        assertEquals(100L, b.usedBytes)
        assertFalse(b.remove("missing"))
        b.clear()
        assertEquals(0L, b.usedBytes)
        assertEquals(0, b.count)
    }

    @Test
    fun noVictimsWhileTheNewTextureFits() {
        val b = TextureBudget(100)
        b.add("a", 40)
        b.nextFrame()
        val plan = b.plan(60)
        assertTrue(plan.victims.isEmpty())
        assertFalse(plan.overBudget)
    }

    @Test
    fun evictsLeastRecentlyUsedFirstUntilItFits() {
        val b = TextureBudget(100)
        b.add("a", 40)
        b.add("b", 40)
        b.nextFrame() // both are from an earlier frame, so neither is pinned
        // used 80 + 30 incoming - 100 budget = 10 needed; "a" (40) is the least recently used and covers it.
        val plan = b.plan(30)
        assertEquals(listOf("a"), plan.victims)
        assertFalse(plan.overBudget)
    }

    @Test
    fun touchMakesAKeyMostRecentlyUsed() {
        val b = TextureBudget(100)
        b.add("a", 40)
        b.add("b", 40)
        b.touch("a") // used in frame 0, so it becomes the most recently used key
        b.nextFrame() // both are unpinned now; only the LRU order decides
        assertEquals(listOf("b"), b.plan(30).victims)
    }

    @Test
    fun texturesUsedThisFrameAreNeverVictims() {
        val b = TextureBudget(100)
        b.add("old", 40)     // added in frame 0, unpinned
        b.nextFrame()
        b.add("current", 40) // added in frame 1, so pinned
        // used 80 + 70 incoming - 100 = 50 needed. "old" covers 40; the scan then reaches the pinned "current",
        // which must be skipped. So the plan is "old" only, and the budget is still exceeded by 10.
        val plan = b.plan(70)
        assertEquals(listOf("old"), plan.victims)
        assertTrue(plan.overBudget)
    }

    @Test
    fun overBudgetWhenEverythingResidentIsPinned() {
        val b = TextureBudget(100)
        b.add("a", 60) // pinned: added this frame
        val plan = b.plan(60) // need 20, but nothing unpinned exists
        assertTrue(plan.victims.isEmpty())
        assertTrue(plan.overBudget)
    }

    @Test
    fun aTextureLargerThanTheBudgetIsReportedAsOverBudget() {
        val b = TextureBudget(100)
        val plan = b.plan(150)
        assertTrue(plan.victims.isEmpty())
        assertTrue(plan.overBudget)
    }

    @Test
    fun planDoesNotReorderTheLruList() {
        val b = TextureBudget(100)
        b.add("a", 40)
        b.add("b", 40)
        b.nextFrame()
        assertEquals(listOf("a"), b.plan(30).victims)
        // Planning must be read-only: the same answer on a second call.
        assertEquals(listOf("a"), b.plan(30).victims)
    }

    @Test
    fun frameCounterAdvancesOnlyWhenAsked() {
        val b = TextureBudget(100)
        assertEquals(0L, b.frame)
        b.add("a", 10)
        assertEquals(0L, b.frame)
        b.nextFrame()
        assertEquals(1L, b.frame)
    }
}
