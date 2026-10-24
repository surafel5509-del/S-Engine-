package com.sengine.aibuilder.provider

import com.sengine.aibuilder.engine.GameProjectInspector
import com.sengine.aibuilder.spec.GameAction
import com.sengine.aibuilder.spec.GameDimension
import com.sengine.aibuilder.spec.GameOperation
import com.sengine.aibuilder.spec.GameSpec
import com.sengine.aibuilder.spec.GameTemplateCatalog
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.TextRenderer
import com.sengine.project.Project
import com.sengine.project.Templates
import java.util.Locale

/** Deterministic, explicitly labelled offline mode built from real templates and safe engine operations. */
object DemoGamePlanner {
    fun plan(
        prompt: String,
        project: Project?,
        preferredTemplateId: String? = null,
        existingProjectNames: Collection<String> = emptyList(),
    ): GameSpec {
        val safePrompt = prompt.trim().take(8_000)
        val lower = safePrompt.lowercase(Locale.ROOT)
        if (project != null) return editPlan(safePrompt, lower, project)

        val templateId = chooseTemplate(lower, preferredTemplateId)
        val entry = GameTemplateCatalog.find(templateId)
            ?: GameTemplateCatalog.entries().firstOrNull { it.id == "platformer-demo" }
            ?: error("No S Engine template is available")
        val template = Templates.all.first { GameTemplateCatalog.idFor(it.name) == entry.id }
        val projectName = uniqueName(template.name, existingProjectNames)
        val mechanics = when (entry.id) {
            "hill-climb-racer" -> listOf("drive controls", "procedural terrain", "coins", "fuel", "hazards", "biome progression", "garage upgrades")
            "platformer-demo" -> listOf("run and jump", "platform collision", "coin collection", "score HUD")
            "space-shooter" -> listOf("touch/joystick movement", "shooting", "enemy waves", "score", "game over")
            "3d-demo" -> listOf("3D character movement", "physics", "pickup triggers", "camera follow")
            "animated-platformer" -> listOf("run and jump", "sprite-sheet animation", "coins", "enemy patrol")
            "physics-sandbox" -> listOf("2D rigidbody physics", "bouncy objects", "tap-to-spawn")
            "blueprint-demo" -> listOf("visual blueprint behaviors", "platform movement", "collectibles")
            else -> listOf("editable S Engine scene", "local template assets")
        }
        val genre = when (entry.id) {
            "hill-climb-racer" -> "2D hill-climb driving"
            "platformer-demo", "animated-platformer" -> "2D platformer"
            "space-shooter" -> "2D shooter"
            "3d-demo" -> "3D action adventure"
            "physics-sandbox" -> "2D physics sandbox"
            "blueprint-demo" -> "2D visual-scripting platformer"
            else -> "2D starter game"
        }
        val summary = "LOCAL DEMO PLAN — selected the installed '${template.name}' template. " +
            "Its existing S Engine scene, scripts and controls are used; no AI model or generated code was used. " +
            "The template includes: ${mechanics.joinToString()}."
        return GameSpec(
            action = GameAction.CREATE,
            projectName = projectName,
            genre = genre,
            dimension = entry.dimension,
            templateId = entry.id,
            summary = summary,
            mechanics = mechanics,
        )
    }

    private fun editPlan(prompt: String, lower: String, project: Project): GameSpec {
        val sceneName = project.startScene
        val scene = try { project.loadScene(sceneName) } catch (_: Exception) { null }
        val dimension = if (scene?.objects?.any { it.getAny<Camera3D>() != null } == true) GameDimension.THREE_D else GameDimension.TWO_D
        val operations = ArrayList<GameOperation>()
        var summary: String? = null

        if (("jump" in lower || "jumping" in lower) && ("higher" in lower || "increase" in lower || "more" in lower || "lower" in lower || "less" in lower)) {
            val candidate = findNumericScriptParameter(project, sceneName, "jump")
            if (candidate != null) {
                val factor = if ("lower" in lower || "less" in lower) 0.8f else 1.25f
                val next = (candidate.third * factor).coerceIn(0.5f, 80f)
                operations += GameOperation.SetScriptParameter(sceneName, candidate.first, "jump", next, candidate.second)
                summary = "LOCAL DEMO PLAN — adjusted the existing '${candidate.second}' jump parameter from ${candidate.third} to $next. Script source is unchanged."
            }
        }
        if (summary == null && ("faster" in lower || "speed up" in lower || "move faster" in lower || "slower" in lower || "reduce speed" in lower)) {
            val candidate = findNumericScriptParameter(project, sceneName, "speed")
            if (candidate != null) {
                val factor = if ("slower" in lower || "reduce speed" in lower) 0.85f else 1.15f
                val next = (candidate.third * factor).coerceIn(0.1f, 100f)
                operations += GameOperation.SetScriptParameter(sceneName, candidate.first, "speed", next, candidate.second)
                summary = "LOCAL DEMO PLAN — adjusted the existing '${candidate.second}' speed parameter from ${candidate.third} to $next. Script source is unchanged."
            }
        }
        if (summary == null && ("zoom in" in lower || "zoom out" in lower || "camera" in lower && "zoom" in lower)) {
            val camera = scene?.objects?.firstOrNull { it.getAny<Camera2D>() != null || it.getAny<Camera3D>() != null }
            if (camera != null) {
                val zoomIn = "zoom out" !in lower
                val c2 = camera.getAny<Camera2D>()
                val c3 = camera.getAny<Camera3D>()
                if (c2 != null) {
                    val next = (c2.size * if (zoomIn) 0.86f else 1.16f).coerceIn(0.2f, 80f)
                    operations += GameOperation.SetComponentProperty(sceneName, camera.name, "Camera", "Size", com.sengine.aibuilder.spec.SpecValue.Number(next.toDouble()))
                } else if (c3 != null) {
                    val next = (c3.fov * if (zoomIn) 0.86f else 1.16f).coerceIn(15f, 140f)
                    operations += GameOperation.SetComponentProperty(sceneName, camera.name, "Camera3D", "Field of View", com.sengine.aibuilder.spec.SpecValue.Number(next.toDouble()))
                }
                if (operations.isNotEmpty()) summary = "LOCAL DEMO PLAN — changed the existing camera framing. No camera or scene was recreated."
            }
        }
        if (summary == null && ("bigger" in lower || "larger" in lower) && ("player" in lower || "car" in lower)) {
            val target = scene?.objects?.firstOrNull { it.name.equals("Player", true) || it.name.equals("Car", true) }
            if (target != null) {
                operations += GameOperation.SetTransform(
                    sceneName, target.name,
                    scaleX = (target.scaleX * 1.1f).coerceAtMost(20f),
                    scaleY = (target.scaleY * 1.1f).coerceAtMost(20f),
                    scaleZ = if (dimension == GameDimension.THREE_D) (target.scaleZ * 1.1f).coerceAtMost(20f) else null,
                )
                summary = "LOCAL DEMO PLAN — increased '${target.name}' scale by 10%."
            }
        }
        if (summary == null && ("add hud" in lower || "add a label" in lower || "add text" in lower)) {
            val label = Regex("[\"']([^\"']{1,100})[\"']").find(prompt)?.groupValues?.getOrNull(1) ?: "New HUD Text"
            var name = "AI HUD Text"
            var suffix = 2
            while (scene?.find(name) != null) name = "AI HUD Text ${suffix++}"
            operations += GameOperation.AddHudText(sceneName, name, label)
            summary = "LOCAL DEMO PLAN — added an editable, static screen-space text label. It does not add gameplay scoring logic."
        }
        if (summary == null && ("coin counter" in lower || "score counter" in lower)) {
            val counter = scene?.objects?.firstOrNull { go -> go.getAny<TextRenderer>() != null &&
                (go.name.contains("score", true) || go.name.contains("coin", true) || go.name.contains("wallet", true)) }
            val existingLogic = project.listAssets(com.sengine.engine.core.AssetKind.SCRIPT).any { asset ->
                val source = project.readAsset(asset).orEmpty()
                source.contains(counter?.name ?: "\u0000") && (source.contains("Coins") || source.contains("coin", true) || source.contains("score", true))
            }
            if (counter != null && existingLogic) {
                val text = counter.getAny<TextRenderer>()!!.text
                summary = "The actual start scene already has '${counter.name}' and an existing script references it. Its current label is '$text'; no duplicate counter was added."
            }
        }

        val action = if (operations.isEmpty()) GameAction.EXPLAIN else GameAction.EDIT
        val finalSummary = summary ?: "LOCAL DEMO MODE cannot safely implement this request using the installed engine tools without generating script code. " +
            "No project files were changed. Supported demo edits include an existing numeric jump/speed parameter, camera framing, player scale, and a static HUD label. " +
            "Choose an AI provider and add its API key for richer validated plans and autonomous polish passes."
        return GameSpec(
            action = action,
            projectName = project.name,
            genre = "Existing ${dimension.wireName} game",
            dimension = dimension,
            summary = finalSummary,
            operations = operations,
        )
    }

    private fun findNumericScriptParameter(project: Project, sceneName: String, key: String): Triple<String, String, Float>? {
        val scene = try { project.loadScene(sceneName) } catch (_: Exception) { return null }
        for (go in scene.objects) for (script in go.components.filterIsInstance<ScriptComponent>()) {
            val value = Regex("(?:^|[,\\n;])\\s*${Regex.escape(key)}\\s*=\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)")
                .find(script.params)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: continue
            val source = project.readAsset(script.script) ?: continue
            if (Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(source)) return Triple(go.name, script.script, value)
        }
        return null
    }

    private fun chooseTemplate(prompt: String, preferred: String?): String {
        if (!preferred.isNullOrBlank() && preferred != "auto" && GameTemplateCatalog.find(preferred) != null) return preferred
        return when {
            listOf("hill climb", "hill-climb", "climb car", "fuel", "racing", "racer", "driving").any { it in prompt } -> "hill-climb-racer"
            listOf("3d", "three-dimensional", "third person", "3-d").any { it in prompt } -> "3d-demo"
            listOf("shooter", "shooting", "space", "enemy wave", "bullets").any { it in prompt } -> "space-shooter"
            listOf("physics", "bouncy", "sandbox").any { it in prompt } -> "physics-sandbox"
            listOf("animation", "animated", "sprite sheet").any { it in prompt } -> "animated-platformer"
            listOf("blueprint", "visual script", "no code").any { it in prompt } -> "blueprint-demo"
            listOf("platformer", "jump", "platform", "coin").any { it in prompt } -> "platformer-demo"
            else -> "platformer-demo"
        }
    }

    private fun uniqueName(base: String, names: Collection<String>): String {
        if (base !in names) return base
        var number = 2
        while ("$base $number" in names) number++
        return "$base $number"
    }
}
