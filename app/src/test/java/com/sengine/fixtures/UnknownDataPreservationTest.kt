package com.sengine.fixtures

import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.SpriteRenderer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scene written by a newer build can contain component types and fields this build does not know.
 * Loading and saving must not remove them: project data is never discarded silently.
 */
class UnknownDataPreservationTest {

    private val source = """
    {
      "name": "Future",
      "gravityY": -9.81,
      "nextId": 3,
      "weatherSeed": {"mode": "storm", "levels": [1, 2]},
      "objects": [
        {
          "id": 1,
          "name": "Hero",
          "x": 1,
          "y": 2,
          "editorGroup": "heroes",
          "components": [
            {"type": "SpriteRenderer", "Color": "#FF00FF00"},
            {"type": "FutureWidget", "enabled": false, "Power": 3, "Tags": ["a", "b"], "Nested": {"k": true}}
          ]
        },
        {"id": 2, "name": "Plain", "components": []}
      ]
    }
    """.trimIndent()

    private fun load(json: String): Scene = SceneSerializer.fromJson(JSONObject(json))

    private fun Scene.byName(name: String): GameObject = objects.first { it.name == name }

    @Test
    fun unknownComponentIsWrittenBackUnchanged() {
        val original = JSONObject(source)
        val written = SceneSerializer.toJson(load(source))
        val hero = written.getJSONArray("objects").getJSONObject(0)
        val comps = hero.getJSONArray("components")
        assertEquals(2, comps.length())
        val expectedWidget = original.getJSONArray("objects").getJSONObject(0)
            .getJSONArray("components").getJSONObject(1)
        assertTrue(comps.getJSONObject(1).similar(expectedWidget))
    }

    @Test
    fun knownComponentsStillLoadBesideUnknownOnes() {
        val s = load(source)
        val hero = s.byName("Hero")
        assertEquals(1, hero.components.size)
        assertEquals(0xFF00FF00.toInt(), hero.getAny<SpriteRenderer>()!!.color)
        assertEquals(1, hero.unknownComponents.size)
    }

    @Test
    fun unknownObjectAndSceneKeysAreKept() {
        val written = SceneSerializer.toJson(load(source))
        assertTrue(written.getJSONObject("weatherSeed").similar(JSONObject(source).getJSONObject("weatherSeed")))
        assertEquals("heroes", written.getJSONArray("objects").getJSONObject(0).getString("editorGroup"))
        // A plain object gets no extra keys.
        assertTrue(!written.getJSONArray("objects").getJSONObject(1).has("editorGroup"))
    }

    @Test
    fun unknownDataSurvivesRepeatedLoadSaveCycles() {
        val first = SceneSerializer.toJson(load(source))
        val second = SceneSerializer.toJson(SceneSerializer.fromJson(first))
        assertTrue(first.similar(second))
        val third = SceneSerializer.toJson(SceneSerializer.fromJson(second))
        assertTrue(second.similar(third))
    }

    @Test
    fun duplicatingAnObjectCopiesItsUnknownData() {
        val s = load(source)
        val hero = s.byName("Hero")
        val copy = s.duplicate(hero)
        assertEquals(1, copy.unknownComponents.size)
        assertTrue(copy.unknownComponents[0].similar(hero.unknownComponents[0]))
        assertEquals("heroes", copy.unknownFields.getString("editorGroup"))
    }

    @Test
    fun unknownComponentDataSurvivesSnapshotRestore() {
        // Play mode and undo use the same JSON path; the widget must come back after a snapshot restore.
        val restored = load(SceneSerializer.toJson(load(source)).toString())
        val written = SceneSerializer.toJson(restored)
        assertTrue(written.getJSONArray("objects").getJSONObject(0).getJSONArray("components").length() == 2)
    }
}
