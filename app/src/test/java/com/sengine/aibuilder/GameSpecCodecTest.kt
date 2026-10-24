package com.sengine.aibuilder

import com.sengine.aibuilder.spec.GameAction
import com.sengine.aibuilder.spec.GameSpecCodec
import com.sengine.aibuilder.spec.GameSpecParseException
import com.sengine.aibuilder.spec.GameSpecValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSpecCodecTest {
    @Test
    fun parsesVersionedTemplatePlanAndRoundTripsOperations() {
        val raw = """{
          "schemaVersion": 1,
          "action": "create",
          "projectName": "Hill Climb Test",
          "genre": "2D driving",
          "dimension": "2d",
          "templateId": "hill-climb-racer",
          "summary": "Use the installed hill climb game template.",
          "mechanics": ["fuel", "coins"],
          "operations": [
            {"type":"add_hud_text", "scene":"start", "name":"Hint", "text":"GO", "x":0, "y":3, "size":0.4, "color":"#FFFFFFFF", "order":100}
          ]
        }"""
        val spec = GameSpecCodec.parse(raw)
        assertEquals(GameAction.CREATE, spec.action)
        assertEquals("hill-climb-racer", spec.templateId)
        assertEquals(1, spec.operations.size)
        val restored = GameSpecCodec.parse(GameSpecCodec.toJson(spec).toString())
        assertEquals(spec.projectName, restored.projectName)
        assertEquals(spec.operations, restored.operations)
        assertTrue(GameSpecValidator.validate(spec).valid)
    }

    @Test
    fun acceptsJsonFencesButRejectsUnknownOperationsAndMalformedResponses() {
        val fenced = """```json
        {"schemaVersion":1,"action":"explain","projectName":"Game","genre":"existing","dimension":"2d","templateId":"","summary":"Explained","operations":[]}
        ```"""
        assertEquals(GameAction.EXPLAIN, GameSpecCodec.parse(fenced).action)
        val unknown = fenced.replace("\"operations\":[]", "\"operations\":[{\"type\":\"run_shell\"}]")
        try {
            GameSpecCodec.parse(unknown)
            throw AssertionError("unsupported operation should fail parsing")
        } catch (_: GameSpecParseException) { }
        try {
            GameSpecCodec.parse("not a plan")
            throw AssertionError("malformed output should fail parsing")
        } catch (_: GameSpecParseException) { }
    }

    @Test
    fun validatorRejectsUnsupportedCapabilitiesAndUnsafePaths() {
        val base = GameSpecCodec.parse("""{"schemaVersion":1,"action":"create","projectName":"Safe","genre":"platformer","dimension":"2d","templateId":"platformer-demo","summary":"Create a supported project.","operations":[{"type":"add_entity","scene":"../other","name":"../../outside","components":[{"type":"RunCommand"}]}]}""")
        val report = GameSpecValidator.validate(base)
        assertFalse(report.valid)
        assertTrue(report.errors.any { it.code == "invalid_scene" })
        assertTrue(report.errors.any { it.code == "invalid_entity_name" })
        assertTrue(report.errors.any { it.code == "unsupported_component" })
    }
}
