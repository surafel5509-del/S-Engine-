package com.sengine.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.aibuilder.engine.GameBuildOutcome
import com.sengine.aibuilder.provider.AiGameBuilderService
import com.sengine.aibuilder.provider.AiPlanResult
import com.sengine.aibuilder.provider.AiProviderKind
import com.sengine.aibuilder.provider.RequestCancellation
import com.sengine.aibuilder.spec.GameAction
import com.sengine.aibuilder.spec.GameOperation
import com.sengine.aibuilder.spec.GameSpec
import com.sengine.aibuilder.spec.GameTemplateCatalog
import com.sengine.aibuilder.spec.SpecValue
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Native, offline-first AI workspace attached to the project manager and the existing editor/runtime. */
class AiBuilderActivity : AppCompatActivity() {
    private lateinit var service: AiGameBuilderService
    private lateinit var projectSpinner: Spinner
    private lateinit var templateSpinner: Spinner
    private lateinit var templateRow: LinearLayout
    private lateinit var projectNames: List<String>
    private lateinit var promptField: EditText
    private lateinit var statusText: android.widget.TextView
    private lateinit var progressText: android.widget.TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var cancelButton: android.widget.TextView
    private lateinit var planButton: android.widget.TextView
    private lateinit var buildGameButton: android.widget.TextView
    private lateinit var agentButton: android.widget.TextView
    private lateinit var historyColumn: LinearLayout
    private lateinit var planColumn: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var activeCancellation: RequestCancellation? = null
    private var activeTask: java.util.concurrent.Future<*>? = null
    private var currentPlan: AiPlanResult? = null
    private var lastOutcome: GameBuildOutcome? = null
    private var lastMemoryKey: String? = null
    private var draftKey = "draft:${UUID.randomUUID()}"
    private var busy = false
    private var taskDescription = ""
    private var taskGeneration = 0
    private var lastSelectedProject: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = AiGameBuilderService(this)
        draftKey = savedInstanceState?.getString("draftKey")
            ?: intent.getStringExtra("draftKey")
            ?: "draft:${UUID.randomUUID()}"
        buildUi()
        reloadProjects(intent.getStringExtra("project"))
        updateStatus()
        renderMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draftKey", draftKey)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) updateStatus()
        if (::projectSpinner.isInitialized && !busy) {
            reloadProjects(selectedProjectName())
            renderMemory()
        }
    }

    override fun onDestroy() {
        activeCancellation?.cancel()
        activeTask?.cancel(true)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = vbox().apply { setBackgroundColor(C.BG) }
        val header = hbox().apply { setPadding(dp(12), dp(10), dp(12), dp(8)); setBackgroundColor(C.HEADER) }
        header.addView(button("←") { finish() }, lp(WRAP, WRAP).margins(0, 0, dp(8), 0))
        val title = vbox()
        title.addView(label("AI Game Studio", 22f, C.TEXT, true))
        title.addView(label("Design • build • verify • play", 12f, C.DIM))
        header.addView(title, lp(0, WRAP, 1f))
        header.addView(button("Memory") { memoryDialog() }, lp(WRAP, WRAP).margins(dp(4), 0, dp(4), 0))
        header.addView(button("⚙") { settingsDialog() }, lp(WRAP, WRAP))
        root.addView(header, lp(MATCH, WRAP))

        val scrollContent = vbox().apply { setPadding(dp(14), dp(12), dp(14), dp(22)) }
        statusText = label("", 12f, C.TEXT, true).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = round(C.PANEL, dp(8).toFloat())
        }
        scrollContent.addView(statusText, lp(MATCH, WRAP).margins(0, 0, 0, dp(12)))

        scrollContent.addView(label("WORKING IN", 11f, C.DIM, true))
        projectSpinner = Spinner(this)
        scrollContent.addView(projectSpinner, lp(MATCH, dp(48)).margins(0, dp(4), 0, dp(8)))

        templateRow = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        templateRow.addView(label("STARTER TEMPLATE", 11f, C.DIM, true), lp(dp(112), WRAP))
        templateSpinner = Spinner(this)
        templateRow.addView(templateSpinner, lp(0, dp(48), 1f))
        scrollContent.addView(templateRow, lp(MATCH, WRAP).margins(0, 0, 0, dp(10)))
        configureTemplateSpinner()

        scrollContent.addView(label("TRY AN EXAMPLE", 11f, C.DIM, true).apply { setPadding(0, dp(2), 0, dp(5)) })
        val examples = hbox()
        val examplesScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(examples) }
        listOf(
            "2D hill-climb car with coins and fuel",
            "3D runner with obstacles",
            "Platformer with coins",
        ).forEach { example ->
            examples.addView(button(example, C.PANEL2) { promptField.setText(example) }, lp(WRAP, WRAP).margins(0, 0, dp(6), 0))
        }
        scrollContent.addView(examplesScroll, lp(MATCH, WRAP).margins(0, 0, 0, dp(8)))

        scrollContent.addView(label("DESCRIBE THE GAME OR CHANGE", 11f, C.DIM, true))
        promptField = field("", multiline = true).apply {
            hint = "Example: Create a 2D hill-climbing car game with coins and fuel."
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        scrollContent.addView(promptField, lp(MATCH, dp(116)).margins(0, dp(5), 0, dp(10)))

        val actionRow = hbox()
        planButton = button("Plan", C.PANEL2) { makePlan() }
        actionRow.addView(planButton, lp(0, dp(44), 1f).margins(0, 0, dp(5), 0))
        buildGameButton = button("Build game", C.ACCENT, 0xFFFFFFFF.toInt()) { startBuild(autonomous = false) }
        actionRow.addView(buildGameButton, lp(0, dp(44), 1f).margins(0, 0, dp(5), 0))
        agentButton = button("Agent", C.GREEN, 0xFFFFFFFF.toInt()) { startBuild(autonomous = true) }
        actionRow.addView(agentButton, lp(0, dp(44), 1f).margins(0, 0, dp(7), 0))
        cancelButton = button("Stop", C.RED, 0xFFFFFFFF.toInt()) { cancelActiveRequest() }.apply { visibility = View.GONE }
        actionRow.addView(cancelButton, lp(WRAP, dp(44)))
        scrollContent.addView(actionRow, lp(MATCH, WRAP))
        scrollContent.addView(label("Plan reviews without applying. Build game applies one validated plan. Agent can run up to five cancellable plan/build passes and may use provider credits.", 11f, C.DIM)
            .apply { setPadding(0, dp(5), 0, 0) }, lp(MATCH, WRAP))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }
        scrollContent.addView(progressBar, lp(MATCH, dp(4)).margins(0, dp(8), 0, dp(3)))
        progressText = label("", 12f, C.DIM)
        scrollContent.addView(progressText, lp(MATCH, WRAP))

        scrollContent.addView(label("RECENT CONVERSATION", 11f, C.DIM, true).apply { setPadding(0, dp(16), 0, dp(6)) })
        historyColumn = vbox()
        scrollContent.addView(historyColumn, lp(MATCH, WRAP))
        planColumn = vbox()
        scrollContent.addView(planColumn, lp(MATCH, WRAP))

        root.addView(ScrollView(this).apply { isFillViewport = true; addView(scrollContent) }, lp(MATCH, 0, 1f))
        setContentView(root)

        projectSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                templateRow.visibility = if (position == 0) View.VISIBLE else View.GONE
                val selected = if (position <= 0) null else projectNames.getOrNull(position - 1)
                if (selected == lastSelectedProject) return
                lastSelectedProject = selected
                currentPlan = null
                lastOutcome = null
                planColumn.removeAllViews()
                renderMemory()
            }
        }
    }

    private fun configureTemplateSpinner() {
        val entries = listOf("Auto choose from prompt") + GameTemplateCatalog.entries().map { "${it.name}  •  ${it.dimension.wireName.uppercase()}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, entries)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        templateSpinner.adapter = adapter
    }

    private fun reloadProjects(preferred: String?) {
        if (!::projectSpinner.isInitialized) return
        val names = ProjectManager.list(this).map { it.name }
        projectNames = listOf("Create new game") + names
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, projectNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        projectSpinner.adapter = adapter
        val index = preferred?.let { names.indexOf(it).takeIf { found -> found >= 0 }?.plus(1) } ?: 0
        projectSpinner.setSelection(index, false)
        lastSelectedProject = if (index == 0) null else names.getOrNull(index - 1)
        templateRow.visibility = if (index == 0) View.VISIBLE else View.GONE
    }

    private fun selectedProjectName(): String? {
        if (!::projectSpinner.isInitialized) return intent.getStringExtra("project")
        val position = projectSpinner.selectedItemPosition
        return if (position <= 0) null else projectNames.getOrNull(position - 1)
    }

    private fun preferredTemplateId(): String? {
        if (templateSpinner.selectedItemPosition <= 0) return null
        return GameTemplateCatalog.entries().getOrNull(templateSpinner.selectedItemPosition - 1)?.id
    }

    private fun memoryKey(): String = selectedProjectName() ?: draftKey

    private fun updateStatus() {
        val settings = service.settings
        val demo = settings.useDemoMode()
        val profile = settings.activeProfile()
        val hasKey = settings.hasApiKey(profile.kind)
        statusText.text = when {
            demo -> "LOCAL TEMPLATE MODE  •  No model/network calls. Choose a real AI provider in Settings to generate and iterate on builds."
            hasKey -> "${profile.kind.displayName.uppercase()} READY  •  ${profile.model}  •  API key encrypted on this device; conversation memory stays local."
            else -> "SETUP REQUIRED  •  Open Settings, choose OpenRouter, OpenAI / ChatGPT API, Gemini, Claude, or another compatible provider, then add its API key."
        }
        statusText.setTextColor(when {
            demo -> 0xFFB7D7F0.toInt()
            hasKey -> 0xFF8EE3B2.toInt()
            else -> C.YELLOW
        })
    }

    private fun makePlan() {
        val prompt = promptField.text.toString().trim()
        if (prompt.isBlank()) { toast("Describe your game or change first"); return }
        currentPlan = null
        planColumn.removeAllViews()
        val target = selectedProjectName()
        val memoryKey = memoryKey()
        lastMemoryKey = memoryKey
        val taskId = beginTask("Reading the current project and preparing a bounded plan…")
        val cancellation = RequestCancellation()
        activeCancellation = cancellation
        activeTask = worker.submit {
            try {
                val result = service.plan(prompt, target, preferredTemplateId(), draftKey, cancellation)
                handler.post {
                    if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                    currentPlan = result
                    finishTask(taskId)
                    renderPlan(result)
                    renderMemory()
                    progressText.text = result.memoryStatus
                }
            } catch (e: Exception) {
                handler.post {
                    if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                    finishTask(taskId)
                    if (cancellation.isCancelled) progressText.text = "Planning cancelled. No plan was applied."
                    else showFailure("Planning failed", e.message ?: "Unexpected error. No plan was applied.")
                    renderMemory()
                }
            }
        }
    }

    private fun startBuild(autonomous: Boolean) {
        val prompt = promptField.text.toString().trim()
        if (prompt.isBlank()) { toast("Describe your game or change first"); return }
        currentPlan = null
        planColumn.removeAllViews()
        val target = selectedProjectName()
        lastMemoryKey = target ?: draftKey
        val passLimit = if (autonomous) service.settings.agentMaxPasses() else 1
        val startMessage = if (autonomous) "Starting autonomous AI agent — up to $passLimit safe build passes…"
            else "Starting AI build — planning, validating, and checking the game…"
        val taskId = beginTask(startMessage)
        val cancellation = RequestCancellation()
        activeCancellation = cancellation
        activeTask = worker.submit {
            try {
                val result = service.buildAutonomously(
                    rawPrompt = prompt,
                    selectedProjectName = target,
                    preferredTemplateId = preferredTemplateId(),
                    draftKey = draftKey,
                    maxPasses = passLimit,
                    cancellation = cancellation,
                    onProgress = { message -> handler.post {
                        if (!isFinishing && !isDestroyed && taskId == taskGeneration) progressText.text = message
                    } },
                )
                handler.post {
                    if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                    finishTask(taskId)
                    currentPlan = result.finalPlan
                    lastOutcome = result.outcome
                    renderBuildReport(
                        result.outcome,
                        agentPasses = result.passes,
                        completionNote = result.completionNote,
                        mode3d = result.finalPlan.spec.dimension.wireName == "3d",
                    )
                    if (result.outcome.report.created) reloadProjects(result.outcome.project.name)
                    lastMemoryKey = result.outcome.project.name
                    renderMemory()
                    progressText.text = "AI build complete — your saved game passed the S Engine headless startup check."
                    Toast.makeText(this, "${result.outcome.project.name} is ready to open in S Engine", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, EditorActivity::class.java)
                        .putExtra("project", result.outcome.project.name)
                        .putExtra("mode3d", result.finalPlan.spec.dimension.wireName == "3d"))
                }
            } catch (e: Exception) {
                handler.post {
                    if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                    finishTask(taskId)
                    if (cancellation.isCancelled) {
                        showFailure("Build stopped", "No unfinished operation batch was kept. Any failed edit was rolled back and an unpublished new project was discarded.", error = false)
                    } else showFailure("AI build failed", e.message ?: "The project was not changed, or the failed transaction was rolled back.")
                    renderMemory()
                }
            }
        }
    }

    private fun applyCurrentPlan() {
        val plan = currentPlan ?: return
        if (!plan.validation.valid) { toast("Fix validation errors before applying this plan"); return }
        val target = selectedProjectName()
        val key = lastMemoryKey ?: memoryKey()
        val taskId = beginTask("Applying validated S Engine operations, saving, reloading, and checking runtime startup…")
        val cancellation = RequestCancellation()
        activeCancellation = cancellation
        activeTask = worker.submit {
            try {
                val result = service.applyPlan(plan, target, key, cancellation)
                handler.post {
                    if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                    finishTask(taskId)
                    lastOutcome = result
                    renderBuildReport(result, mode3d = plan.spec.dimension.wireName == "3d")
                    if (result.report.created) reloadProjects(result.project.name)
                    lastMemoryKey = result.project.name
                    renderMemory()
                    Toast.makeText(this, "${result.project.name} is ready to open in S Engine", Toast.LENGTH_LONG).show()
                    // Open the actual project/editor/runtime integration path after the user confirms the plan.
                    startActivity(Intent(this, EditorActivity::class.java)
                        .putExtra("project", result.project.name)
                        .putExtra("mode3d", plan.spec.dimension.wireName == "3d"))
                }
            } catch (e: Exception) {
                handler.post {
                    if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                    finishTask(taskId)
                    if (cancellation.isCancelled) {
                        showFailure("Build cancelled", "The current operation batch was rolled back; any staged new project was discarded.", error = false)
                    } else showFailure("Build failed — project was not changed or was rolled back", e.message ?: "Unexpected build error.")
                    renderMemory()
                }
            }
        }
    }

    private fun renderPlan(result: AiPlanResult) {
        planColumn.removeAllViews()
        val spec = result.spec
        val card = vbox().apply {
            background = round(C.PANEL, dp(10).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val heading = when (spec.action) {
            GameAction.CREATE -> "CREATE PLAN  •  ${spec.dimension.wireName.uppercase()}"
            GameAction.EDIT -> "EDIT PLAN  •  ${spec.dimension.wireName.uppercase()}"
            GameAction.EXPLAIN -> "EXPLANATION  •  NO CHANGES"
        }
        card.addView(label(heading, 13f, if (result.validation.valid) C.ACCENT else C.RED, true))
        card.addView(label(result.modeLabel + if (result.responseModel.startsWith("local")) "" else "\nModel: ${result.responseModel}", 11f, C.DIM).apply {
            setPadding(0, dp(4), 0, dp(7))
        })
        card.addView(label(spec.summary, 14f, C.TEXT).apply { setLineSpacing(dp(2).toFloat(), 1f) })
        if (spec.action == GameAction.CREATE) {
            val template = GameTemplateCatalog.find(spec.templateId)
            card.addView(label("Project: ${spec.projectName}  •  ${template?.name ?: spec.templateId}", 12f, C.DIM).apply { setPadding(0, dp(7), 0, 0) })
        }
        if (spec.mechanics.isNotEmpty()) card.addView(label("\nIncluded: ${spec.mechanics.joinToString(", ")}", 12f, C.DIM))
        if (spec.operations.isNotEmpty()) {
            card.addView(label("\nPROPOSED OPERATIONS (${spec.operations.size})", 11f, C.DIM, true))
            spec.operations.forEachIndexed { i, op ->
                card.addView(label("${i + 1}. ${describe(op)}", 12f, C.TEXT).apply { setPadding(0, dp(4), 0, 0) })
            }
        }
        if (result.validation.issues.isNotEmpty()) {
            card.addView(label("\nVALIDATION", 11f, C.DIM, true))
            result.validation.issues.forEach { issue ->
                card.addView(label("${issue.severity}: ${issue.message}", 12f, if (issue.severity.name == "ERROR") C.RED else C.YELLOW))
            }
        } else card.addView(label("\nValidation passed against the S Engine v1 GameSpec and operation allowlist.", 11f, 0xFF8EE3B2.toInt()))

        val buttons = hbox().apply { setPadding(0, dp(12), 0, 0) }
        if (spec.action != GameAction.EXPLAIN) {
            buttons.addView(button(if (spec.action == GameAction.CREATE) "Build & open editor" else "Apply & open editor", C.ACCENT, 0xFFFFFFFF.toInt()) {
                applyCurrentPlan()
            }.apply { isEnabled = result.validation.valid; alpha = if (isEnabled) 1f else 0.45f }, lp(0, WRAP, 1f).margins(0, 0, dp(6), 0))
        }
        buttons.addView(button("Remember decision") {
            if (!service.settings.rememberOnDevice()) toast("Turn on memory saving in Memory settings first")
            else {
                service.rememberDecision(memoryKey(), spec.summary)
                renderMemory()
                toast("Saved to this project's AI memory")
            }
        }, lp(WRAP, WRAP))
        card.addView(button("Inspect GameSpec JSON") { showSpecJson(spec) }.apply { setPadding(dp(6), dp(10), dp(6), 0) })
        card.addView(button("Change plan / prompt") { promptField.requestFocus() }.apply { setPadding(dp(6), dp(7), dp(6), 0) })
        card.addView(button("Close plan") { currentPlan = null; planColumn.removeAllViews() }.apply { setPadding(dp(6), dp(8), dp(6), 0) })
        if (buttons.childCount > 0) card.addView(buttons, lp(MATCH, WRAP))
        planColumn.addView(card, lp(MATCH, WRAP).margins(0, dp(12), 0, 0))
    }

    private fun renderBuildReport(
        outcome: GameBuildOutcome,
        agentPasses: Int? = null,
        completionNote: String = "",
        mode3d: Boolean = false,
    ) {
        planColumn.removeAllViews()
        val report = outcome.report
        val card = vbox().apply {
            background = round(0xFF22352B.toInt(), dp(10).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(label(if (report.created) "PROJECT CREATED & VERIFIED" else "PROJECT EDIT APPLIED", 13f, 0xFF8EE3B2.toInt(), true))
        card.addView(label("${outcome.project.name}  •  ${report.templateName}", 13f, C.TEXT, true).apply { setPadding(0, dp(5), 0, dp(5)) })
        card.addView(label(report.summary, 13f, C.TEXT))
        if (agentPasses != null) card.addView(label("Autonomous passes completed: $agentPasses", 11f, C.DIM).apply { setPadding(0, dp(5), 0, 0) })
        if (completionNote.isNotBlank()) card.addView(label(completionNote, 12f, C.DIM).apply { setPadding(0, dp(5), 0, 0) })
        report.changes.forEach { card.addView(label("• $it", 12f, C.TEXT).apply { setPadding(0, dp(4), 0, 0) }) }
        if (report.affectedObjects.isNotEmpty()) card.addView(label("Affected: ${report.affectedObjects.joinToString()}", 12f, C.DIM).apply { setPadding(0, dp(6), 0, 0) })
        card.addView(label("\nValidation: project and scenes saved, reopened through S Engine project APIs.\n${report.runtimeSmokeTest}", 11f, 0xFFB7D7F0.toInt()))
        val buttons = hbox().apply { setPadding(0, dp(10), 0, 0) }
        buttons.addView(button("Open Editor") { openEditor(outcome.project, mode3d) }, lp(0, WRAP, 1f).margins(0, 0, dp(5), 0))
        buttons.addView(button("Play") { openPlayer(outcome.project) }, lp(WRAP, WRAP).margins(0, 0, dp(5), 0))
        if (report.rollbackToken != null && report.rollbackScene != null) {
            buttons.addView(button("Undo AI edit", C.RED, 0xFFFFFFFF.toInt()) { rollback(report.rollbackScene) }, lp(WRAP, WRAP))
        }
        card.addView(buttons, lp(MATCH, WRAP))
        planColumn.addView(card, lp(MATCH, WRAP).margins(0, dp(12), 0, 0))
    }

    private fun showSpecJson(spec: GameSpec) {
        val text = label(com.sengine.aibuilder.spec.GameSpecCodec.toJson(spec).toString(2), 11f, C.TEXT)
            .apply { typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(14), dp(10), dp(14), dp(10)); setTextIsSelectable(true) }
        MaterialAlertDialogBuilder(this).setTitle("Validated GameSpec v${spec.schemaVersion}")
            .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Close", null).show()
    }

    private fun describe(operation: GameOperation): String = when (operation) {
        is GameOperation.AddEntity -> "Add '${operation.name}' to ${operation.scene} with ${operation.components.joinToString { it.type }.ifBlank { "no components" }}"
        is GameOperation.SetTransform -> "Set transform on '${operation.objectName}' in ${operation.scene}"
        is GameOperation.AddComponent -> "Add ${operation.component.type} to '${operation.objectName}'"
        is GameOperation.SetComponentProperty -> "Set '${operation.objectName}' ${operation.componentType}.${operation.property} = ${valueText(operation.value)}"
        is GameOperation.SetScriptParameter -> "Set existing ${operation.scriptAsset ?: "script"} parameter ${operation.key} on '${operation.objectName}' to ${operation.value} (source unchanged)"
        is GameOperation.RenameEntity -> "Rename '${operation.objectName}' to '${operation.newName}' if no script references would break"
        is GameOperation.SetSceneGravity -> "Adjust gravity in ${operation.scene}"
        is GameOperation.AddHudText -> "Add static HUD label '${operation.name}': ${operation.text}"
        is GameOperation.GeneratePixelSprite -> "Generate ${operation.width}×${operation.height} pixel-art texture '${operation.assetName}'"
        is GameOperation.GenerateSound -> "Generate ${operation.durationMs} ms ${operation.waveform} sound effect '${operation.assetName}'"
        is GameOperation.GenerateAnimationClip -> "Generate ${operation.frames.size}-frame animation '${operation.assetName}' from '${operation.textureAsset}'"
        is GameOperation.GenerateShaderPreset -> "Generate vetted '${operation.preset}' shader preset '${operation.assetName}'"
        is GameOperation.GenerateBlueprintPreset -> "Generate vetted '${operation.preset}' visual blueprint '${operation.assetName}'"
    }

    private fun valueText(value: SpecValue): String = when (value) {
        is SpecValue.Text -> "\"${value.value}\""
        is SpecValue.Number -> value.value.toString()
        is SpecValue.Bool -> value.value.toString()
    }

    private fun openEditor(project: Project, mode3d: Boolean) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("project", project.name).putExtra("mode3d", mode3d))
    }

    private fun openPlayer(project: Project) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("project", project.name))
    }

    private fun rollback(sceneName: String) {
        val projectName = lastOutcome?.project?.name ?: selectedProjectName() ?: return
        val taskId = beginTask("Restoring the previous scene snapshot and verifying it…")
        val cancellation = RequestCancellation()
        activeCancellation = cancellation
        activeTask = worker.submit {
            try {
                val project = ProjectManager.open(this, projectName)
                val message = service.rollback(project, sceneName, cancellation)
                handler.post {
                    if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                    finishTask(taskId)
                    currentPlan = null
                    lastOutcome = null
                    showFailure("AI edit rolled back", message, error = false)
                    renderMemory()
                }
            } catch (e: Exception) {
                handler.post {
                    if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                    finishTask(taskId)
                    showFailure("Rollback failed", e.message ?: "The original scene was left unchanged.")
                }
            }
        }
    }

    private fun renderMemory() {
        if (!::historyColumn.isInitialized) return
        historyColumn.removeAllViews()
        val key = memoryKey()
        val messages = service.memory.events(key).filter { it.kind == "message" }.takeLast(8)
        if (messages.isEmpty()) {
            historyColumn.addView(label(if (service.settings.rememberOnDevice()) "No saved conversation yet. Prompts and results for this project appear here." else "Memory saving is off. New prompts will not be added to history.", 12f, C.DIM))
            return
        }
        for (event in messages) {
            val role = event.payload.optString("role").uppercase()
            val text = event.payload.optString("text")
            val bubble = vbox().apply {
                background = round(if (role == "USER") 0xFF273449.toInt() else C.PANEL, dp(7).toFloat())
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            bubble.addView(label(role, 10f, if (role == "USER") C.ACCENT else C.GREEN, true))
            bubble.addView(label(text.take(800), 12f, C.TEXT).apply { maxLines = 6 })
            historyColumn.addView(bubble, lp(MATCH, WRAP).margins(0, dp(3), 0, dp(3)))
        }
    }

    private fun memoryDialog() {
        val key = memoryKey()
        val content = vbox().apply { setPadding(dp(18), dp(8), dp(18), dp(6)) }
        content.addView(label(service.memorySummary(key), 13f, C.TEXT))
        content.addView(label("\nPrompts, bounded conversation memory, and the current project snapshot may be sent directly to the selected AI provider when online AI is enabled. API keys are encrypted by Android Keystore and are never stored in the project or source code. Sensitive-looking key/token/password text is redacted before memory storage. All builder memory and rollback data stay on this device; no cloud account or project upload is used. Turning off memory saving also disables persistent undo snapshots; failed build transactions still roll back.", 11f, C.DIM))
        val remember = CheckBox(this).apply {
            text = "Remember chat, design decisions, build summaries, and undo snapshots on this device"
            setTextColor(C.TEXT)
            isChecked = service.settings.rememberOnDevice()
        }
        content.addView(remember, lp(MATCH, WRAP).margins(0, dp(6), 0, dp(4)))
        val view = ScrollView(this).apply { addView(content) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("AI memory & privacy")
            .setView(view)
            .setPositiveButton("Save privacy setting", null)
            .setNegativeButton("Close", null)
            .setNeutralButton("Forget this project", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                service.settings.setRememberOnDevice(remember.isChecked)
                renderMemory()
                toast(if (remember.isChecked) "Memory saving enabled" else "New prompts will not be saved; existing memory remains until deleted")
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete memory for '$key'?")
                    .setMessage("This removes this project's local AI messages, design decisions, build events, and rollback snapshots. Provider conversations are not synced to an S Engine cloud service.")
                    .setPositiveButton("Delete memory") { _, _ -> clearMemory(key) }
                    .setNegativeButton("Cancel", null)
                    .show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun clearMemory(key: String) {
        val taskId = beginTask("Deleting local/private project memory…")
        val cancellation = RequestCancellation()
        activeCancellation = cancellation
        activeTask = worker.submit {
            val result = try { service.clearMemory(key, cancellation) } catch (e: Exception) { "Device-only memory could not be fully cleared (${e.message ?: "storage error"})." }
            handler.post {
                if (isFinishing || isDestroyed || taskId != taskGeneration) return@post
                finishTask(taskId)
                planColumn.removeAllViews()
                renderMemory()
                toast(result)
            }
        }
    }

    private fun settingsDialog() {
        val settings = service.settings
        val kinds = AiProviderKind.entries
        val box = vbox().apply { setPadding(dp(18), dp(8), dp(18), dp(5)) }
        box.addView(label(
            "Connect a provider directly. Keys are encrypted with Android Keystore and sent only to the selected HTTPS provider; S Engine has no AI backend or cloud project storage. Provider usage may cost money. Never paste keys into prompts or source code.",
            12f, C.YELLOW,
        ))

        box.addView(label("AI PROVIDER", 11f, C.DIM, true).apply { setPadding(0, dp(10), 0, dp(2)) })
        val providerSpinner = Spinner(this)
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, kinds.map { it.displayName })
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = providerAdapter
        providerSpinner.setSelection(kinds.indexOf(settings.activeProvider()).coerceAtLeast(0), false)
        box.addView(providerSpinner, lp(MATCH, dp(48)))

        val modelField = field(settings.activeProfile().model)
        modelField.hint = "Provider model ID"
        box.addView(label("MODEL ID", 11f, C.DIM, true).apply { setPadding(0, dp(7), 0, dp(2)) })
        box.addView(modelField, lp(MATCH, WRAP))

        val endpointRow = vbox()
        endpointRow.addView(label("HTTPS API BASE URL (CUSTOM PROVIDER)", 11f, C.DIM, true).apply { setPadding(0, dp(7), 0, dp(2)) })
        val endpointField = field(settings.profile(AiProviderKind.OPENAI_COMPATIBLE).endpoint)
        endpointField.hint = "https://api.example.com/v1"
        endpointRow.addView(endpointField, lp(MATCH, WRAP))
        endpointRow.addView(label("The app appends /chat/completions unless the URL already ends with it. Only use a provider you trust.", 10f, C.DIM)
            .apply { setPadding(0, dp(3), 0, 0) })
        box.addView(endpointRow, lp(MATCH, WRAP))

        val apiKeyField = field("").apply {
            hint = "Paste provider API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        box.addView(label("API KEY  •  blank keeps the existing encrypted key", 11f, C.DIM, true).apply { setPadding(0, dp(7), 0, dp(2)) })
        box.addView(apiKeyField, lp(MATCH, WRAP))
        val keyStatus = label("", 11f, C.DIM).apply { setPadding(0, dp(4), 0, 0) }
        box.addView(keyStatus, lp(MATCH, WRAP))

        val localMode = CheckBox(this).apply {
            text = "Use Local Template mode (no model/network calls)"
            setTextColor(C.TEXT)
            isChecked = settings.useDemoMode()
        }
        box.addView(localMode, lp(MATCH, WRAP).margins(0, dp(9), 0, 0))
        val remember = CheckBox(this).apply {
            text = "Save conversation/build history and undo snapshots on this device"
            setTextColor(C.TEXT)
            isChecked = settings.rememberOnDevice()
        }
        box.addView(remember, lp(MATCH, WRAP))

        box.addView(label("AUTONOMOUS AGENT PASSES  •  each pass may make a provider request", 11f, C.DIM, true)
            .apply { setPadding(0, dp(6), 0, dp(2)) })
        val passesSpinner = Spinner(this)
        val passAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, (1..5).map { "$it pass${if (it == 1) "" else "es"}" })
        passAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        passesSpinner.adapter = passAdapter
        passesSpinner.setSelection(settings.agentMaxPasses() - 1, false)
        box.addView(passesSpinner, lp(MATCH, dp(46)))

        fun selectedKind(): AiProviderKind = kinds.getOrElse(providerSpinner.selectedItemPosition) { AiProviderKind.OPENROUTER }
        fun refreshKeyStatus() {
            val kind = selectedKind()
            endpointRow.visibility = if (kind == AiProviderKind.OPENAI_COMPATIBLE) View.VISIBLE else View.GONE
            keyStatus.text = when {
                localMode.isChecked -> "Local Template mode is selected; provider keys are not used."
                settings.hasApiKey(kind) -> "${kind.displayName} key is saved encrypted on this device."
                else -> "No ${kind.displayName} key is saved yet. Add one to enable online AI builds."
            }
        }
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val kind = kinds.getOrElse(position) { AiProviderKind.OPENROUTER }
                val profile = settings.profile(kind)
                modelField.setText(profile.model)
                endpointField.setText(profile.endpoint)
                apiKeyField.text.clear()
                refreshKeyStatus()
            }
        }
        localMode.setOnCheckedChangeListener { _, _ -> refreshKeyStatus() }
        refreshKeyStatus()

        box.addView(button("Forget this provider's saved API key", C.PANEL2) {
            val kind = selectedKind()
            settings.clearApiKey(kind)
            apiKeyField.text.clear()
            refreshKeyStatus()
            updateStatus()
            toast("${kind.displayName} key deleted from this device")
        }, lp(MATCH, WRAP).margins(0, dp(5), 0, dp(4)))

        box.addView(label("Privacy: bounded project metadata, your prompt, and (if enabled) local conversation context go directly to the selected provider. Scene script source and absolute file paths are not included. Generated changes are validated and reversible; arbitrary code execution is not offered.", 10f, C.DIM)
            .apply { setPadding(0, dp(7), 0, dp(8)) })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("AI providers & agent")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val kind = selectedKind()
                    val model = modelField.text.toString()
                    val endpoint = if (kind == AiProviderKind.OPENAI_COMPATIBLE) endpointField.text.toString() else ""
                    settings.saveProfile(kind, model, endpoint)
                    val enteredKey = apiKeyField.text.toString().trim()
                    if (enteredKey.isNotEmpty()) settings.saveApiKey(kind, enteredKey)
                    settings.setActiveProvider(kind)
                    settings.setUseDemoMode(localMode.isChecked)
                    settings.setRememberOnDevice(remember.isChecked)
                    settings.setAgentMaxPasses(passesSpinner.selectedItemPosition + 1)
                    apiKeyField.text.clear()
                    refreshKeyStatus()
                    updateStatus()
                    toast(if (localMode.isChecked) "Local Template mode saved" else "${kind.displayName} settings saved on this device")
                    dialog.dismiss()
                } catch (e: Exception) {
                    keyStatus.text = e.message ?: "Settings are invalid."
                    keyStatus.setTextColor(C.RED)
                }
            }
        }
        dialog.show()
    }

    private fun beginTask(message: String): Int {
        val generation = ++taskGeneration
        busy = true
        taskDescription = message
        progressText.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        cancelButton.visibility = View.VISIBLE
        listOf(planButton, buildGameButton, agentButton).forEach { it.isEnabled = false; it.alpha = 0.55f }
        projectSpinner.isEnabled = false
        templateSpinner.isEnabled = false
        return generation
    }

    private fun finishTask(generation: Int? = null) {
        if (generation != null && generation != taskGeneration) return
        taskGeneration++ // Ignore any late callback from a task that has already been cancelled/replaced.
        busy = false
        taskDescription = ""
        progressBar.visibility = View.GONE
        cancelButton.visibility = View.GONE
        listOf(planButton, buildGameButton, agentButton).forEach { it.isEnabled = true; it.alpha = 1f }
        projectSpinner.isEnabled = true
        templateSpinner.isEnabled = true
        activeCancellation = null
        activeTask = null
    }

    private fun cancelActiveRequest() {
        val description = taskDescription
        activeCancellation?.cancel()
        activeTask?.cancel(true)
        finishTask()
        progressText.text = when {
            description.startsWith("Starting autonomous") -> "Agent cancelled. Completed safe passes may remain saved; an in-flight batch is rolled back and an unfinished new project is discarded."
            description.startsWith("Starting AI build") -> "Build cancelled. Any in-flight batch is rolled back and an unfinished new project is discarded."
            description.startsWith("Applying") -> "Build cancellation requested. The operation batch will roll back; any staged new project will be discarded."
            description.startsWith("Restoring") -> "Rollback cancellation requested. If restoration had started, the original scene is being restored."
            description.startsWith("Deleting") -> "Memory deletion cancelled. Some device-local data may already have been deleted."
            else -> "Planning cancelled. No plan was applied."
        }
    }

    private fun showFailure(title: String, message: String, error: Boolean = true) {
        val card = vbox().apply {
            background = round(if (error) 0xFF402A2A.toInt() else C.PANEL, dp(8).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(label(title, 13f, if (error) C.RED else C.GREEN, true))
        card.addView(label(message, 12f, C.TEXT).apply { setPadding(0, dp(4), 0, 0) })
        planColumn.removeAllViews()
        planColumn.addView(card, lp(MATCH, WRAP).margins(0, dp(10), 0, 0))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
