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
import com.sengine.aibuilder.memory.AiMemoryEvent
import com.sengine.aibuilder.provider.AiBuilderSettingsStore
import com.sengine.aibuilder.provider.AiGameBuilderService
import com.sengine.aibuilder.provider.AiPlanResult
import com.sengine.aibuilder.provider.AiProviderException
import com.sengine.aibuilder.provider.DemoGamePlanner
import com.sengine.aibuilder.provider.RequestCancellation
import com.sengine.aibuilder.provider.SupabaseAuthClient
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
    private lateinit var historyColumn: LinearLayout
    private lateinit var planColumn: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var activeCancellation: RequestCancellation? = null
    private var authCancellation: RequestCancellation? = null
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
        authCancellation?.cancel()
        activeTask?.cancel(true)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = vbox().apply { setBackgroundColor(C.BG) }
        val header = hbox().apply { setPadding(dp(12), dp(10), dp(12), dp(8)); setBackgroundColor(C.HEADER) }
        header.addView(button("←") { finish() }, lp(WRAP, WRAP).margins(0, 0, dp(8), 0))
        val title = vbox()
        title.addView(label("AI Game Builder", 22f, C.TEXT, true))
        title.addView(label("Plan safely • build with S Engine • play in the editor", 12f, C.DIM))
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
        planButton = button("Plan request", C.ACCENT, 0xFFFFFFFF.toInt()) { makePlan() }
        actionRow.addView(planButton, lp(0, WRAP, 1f).margins(0, 0, dp(6), 0))
        cancelButton = button("Cancel", C.PANEL2) { cancelActiveRequest() }.apply { visibility = View.GONE }
        actionRow.addView(cancelButton, lp(WRAP, WRAP))
        scrollContent.addView(actionRow, lp(MATCH, WRAP))

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
        val config = settings.config()
        val demo = settings.useDemoMode()
        val direct = !demo && settings.useDirectOpenRouterMode()
        val secureReady = !demo && !direct && config != null && settings.hasSession()
        statusText.text = when {
            demo -> "LOCAL DEMO  •  No network/model calls. Uses installed templates and safe local edits."
            direct && settings.hasOpenRouterApiKey() -> "DIRECT OPENROUTER  •  No Supabase/Auth/backend. Key encrypted on this device; memory stays local.  •  ${settings.model()}"
            direct -> "DIRECT OPENROUTER SETUP REQUIRED  •  Add your OpenRouter API key in Settings. No Supabase account is needed."
            secureReady -> "SECURE AI  •  Supabase-authenticated Edge Function → OpenRouter  •  ${config!!.model}"
            else -> "SETUP REQUIRED  •  Configure Supabase and sign in, or choose Direct OpenRouter/Local Demo in Settings."
        }
        statusText.setTextColor(when {
            demo -> 0xFFB7D7F0.toInt()
            direct && settings.hasOpenRouterApiKey() || secureReady -> 0xFF8EE3B2.toInt()
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
                    progressText.text = result.cloudMemoryStatus
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
                    renderBuildReport(result)
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

    private fun renderBuildReport(outcome: GameBuildOutcome) {
        planColumn.removeAllViews()
        val report = outcome.report
        val card = vbox().apply {
            background = round(0xFF22352B.toInt(), dp(10).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(label(if (report.created) "PROJECT CREATED & VERIFIED" else "PROJECT EDIT APPLIED", 13f, 0xFF8EE3B2.toInt(), true))
        card.addView(label("${outcome.project.name}  •  ${report.templateName}", 13f, C.TEXT, true).apply { setPadding(0, dp(5), 0, dp(5)) })
        card.addView(label(report.summary, 13f, C.TEXT))
        report.changes.forEach { card.addView(label("• $it", 12f, C.TEXT).apply { setPadding(0, dp(4), 0, 0) }) }
        if (report.affectedObjects.isNotEmpty()) card.addView(label("Affected: ${report.affectedObjects.joinToString()}", 12f, C.DIM).apply { setPadding(0, dp(6), 0, 0) })
        card.addView(label("\nValidation: project and scenes saved, reopened through S Engine project APIs.\n${report.runtimeSmokeTest}", 11f, 0xFFB7D7F0.toInt()))
        val buttons = hbox().apply { setPadding(0, dp(10), 0, 0) }
        buttons.addView(button("Open Editor") { openEditor(outcome.project, report.templateName == "3D Demo") }, lp(0, WRAP, 1f).margins(0, 0, dp(5), 0))
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
        content.addView(label("\nSensitive-looking key/token/password text is redacted before local storage and provider requests. Do not paste secrets into game prompts. For AI requests, bounded retrieved memory and project context are sent to the selected provider; Direct OpenRouter does not sync them to Supabase. History synced during an earlier Secure AI session remains in Supabase until deleted there. You can inspect and delete this project's local memory below.", 11f, C.DIM))
        val remember = CheckBox(this).apply {
            text = "Remember chat, design decisions, and build summaries on this device"
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
                    .setMessage("This removes this project's local AI messages, decisions, build events, and rollback snapshots. In Secure Supabase mode, remote deletion is also attempted. Direct OpenRouter and Local Demo delete only local data; records synced during a previous Secure AI session remain in Supabase until deleted there.")
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
            val result = try { service.clearMemory(key, cancellation) } catch (e: Exception) { "Local memory was cleared; remote status is uncertain (${e.message ?: "network error"})." }
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
        val config = settings.config()
        val box = vbox().apply { setPadding(dp(18), dp(8), dp(18), dp(5)) }
        box.addView(label("Direct OpenRouter calls the provider from this device without Supabase Auth or a backend. Your key is encrypted with Android Keystore. The prompt, bounded project snapshot, and (when memory is enabled) retrieved memory are sent to OpenRouter to generate a plan; they are not synced to Supabase. Provider charges may apply.", 12f, C.YELLOW))
        val url = field(config?.projectUrl.orEmpty())
        url.hint = "https://your-project.supabase.co"
        box.addView(label("Supabase project URL", 11f, C.DIM).apply { setPadding(0, dp(8), 0, dp(2)) })
        box.addView(url, lp(MATCH, WRAP))
        val anonKey = field(config?.anonKey.orEmpty())
        anonKey.hint = "Supabase publishable / anon key (client key)"
        anonKey.isSaveEnabled = false
        box.addView(label("Supabase publishable/anon key (not a service-role key)", 11f, C.DIM).apply { setPadding(0, dp(7), 0, dp(2)) })
        box.addView(anonKey, lp(MATCH, WRAP))
        val model = field(config?.model ?: AiBuilderSettingsStore.DEFAULT_MODEL)
        model.hint = "OpenRouter model ID, for example openai/gpt-4o-mini"
        box.addView(label("OpenRouter model ID (used by either OpenRouter mode)", 11f, C.DIM).apply { setPadding(0, dp(7), 0, dp(2)) })
        box.addView(model, lp(MATCH, WRAP))

        val openRouterKey = field("").apply {
            hint = if (settings.hasOpenRouterApiKey()) "Key saved encrypted; leave blank to keep it" else "sk-or-v1-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        box.addView(label("Direct OpenRouter API key (stored encrypted on this device only)", 11f, C.DIM).apply { setPadding(0, dp(8), 0, dp(2)) })
        box.addView(openRouterKey, lp(MATCH, WRAP))
        box.addView(button("Forget saved OpenRouter key", C.PANEL2) {
            settings.clearOpenRouterApiKey()
            openRouterKey.text.clear()
            openRouterKey.hint = "sk-or-v1-…"
            toast("Saved OpenRouter key deleted from this device")
            updateStatus()
        }, lp(MATCH, WRAP).margins(0, dp(4), 0, dp(4)))

        val email = field("")
        email.hint = "Email"
        box.addView(label("Supabase Auth", 11f, C.DIM, true).apply { setPadding(0, dp(10), 0, dp(2)) })
        box.addView(email, lp(MATCH, WRAP))
        val password = field("").apply {
            hint = "Password (used for this sign-in only)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        box.addView(password, lp(MATCH, WRAP).margins(0, dp(4), 0, 0))
        val demo = CheckBox(this).apply {
            text = "Use Local Demo mode (no network/model calls)"
            setTextColor(C.TEXT)
            isChecked = settings.useDemoMode()
        }
        val direct = CheckBox(this).apply {
            text = "Use Direct OpenRouter (no Supabase Auth or backend)"
            setTextColor(C.TEXT)
            isChecked = settings.useDirectOpenRouterMode() && !settings.useDemoMode()
        }
        box.addView(direct, lp(MATCH, WRAP).margins(0, dp(6), 0, 0))
        box.addView(demo, lp(MATCH, WRAP))
        val remember = CheckBox(this).apply {
            text = "Remember chat/build memory on this device"
            setTextColor(C.TEXT)
            isChecked = settings.rememberOnDevice()
        }
        box.addView(remember, lp(MATCH, WRAP))
        val authStatus = label(when {
            direct.isChecked && settings.hasOpenRouterApiKey() -> "Direct OpenRouter key is encrypted with Android Keystore. No Supabase session is used."
            direct.isChecked -> "Add an OpenRouter API key above, then choose Direct OpenRouter. No Supabase account is required."
            settings.hasSession() -> "Supabase session stored encrypted with Android Keystore."
            else -> "Secure Supabase mode requires an account. Direct OpenRouter does not."
        }, 11f, C.DIM)
        demo.setOnCheckedChangeListener { _, checked ->
            if (checked) direct.isChecked = false
            authStatus.text = if (demo.isChecked) "Local Demo makes no network/model calls." else "Secure Supabase mode requires an account. Direct OpenRouter does not."
        }
        direct.setOnCheckedChangeListener { _, checked ->
            if (checked) demo.isChecked = false
            authStatus.text = if (direct.isChecked) {
                if (settings.hasOpenRouterApiKey()) "Direct OpenRouter key is encrypted with Android Keystore. No Supabase session is used."
                else "Add an OpenRouter API key above. No Supabase account is required."
            } else if (settings.hasSession()) "Supabase session stored encrypted with Android Keystore."
            else "Secure Supabase mode requires an account. Direct OpenRouter does not."
        }
        box.addView(authStatus, lp(MATCH, WRAP).margins(0, dp(4), 0, dp(7)))
        val buttons = vbox()
        buttons.addView(button("Save AI settings", C.PANEL2) {
            saveSettings(url, anonKey, model, openRouterKey, demo, direct, remember, authStatus)
        }, lp(MATCH, WRAP).margins(0, 0, 0, dp(5)))
        buttons.addView(button("Sign in to Supabase", C.ACCENT, 0xFFFFFFFF.toInt()) {
            authAction(url, anonKey, model, email, password, demo, direct, remember, authStatus, signUp = false)
        }, lp(MATCH, WRAP).margins(0, 0, 0, dp(5)))
        buttons.addView(button("Create Supabase account", C.PANEL2) {
            authAction(url, anonKey, model, email, password, demo, direct, remember, authStatus, signUp = true)
        }, lp(MATCH, WRAP).margins(0, 0, 0, dp(5)))
        buttons.addView(button("Sign out", C.PANEL2) {
            service.settings.clearSession()
            settings.setUseDemoMode(true)
            settings.setUseDirectOpenRouterMode(false)
            demo.isChecked = true
            direct.isChecked = false
            authStatus.text = "Signed out on this device. Local Demo is selected."
            updateStatus()
        }, lp(MATCH, WRAP))
        box.addView(buttons, lp(MATCH, WRAP))

        val dialog = MaterialAlertDialogBuilder(this).setTitle("AI provider & privacy")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun saveSettings(
        url: EditText,
        anon: EditText,
        model: EditText,
        openRouterKey: EditText,
        demo: CheckBox,
        direct: CheckBox,
        remember: CheckBox,
        status: android.widget.TextView,
    ) {
        try {
            val settings = service.settings
            settings.saveModel(model.text.toString())
            val enteredKey = openRouterKey.text.toString().trim()
            if (enteredKey.isNotEmpty()) settings.saveOpenRouterApiKey(enteredKey)
            if (!demo.isChecked && !direct.isChecked) {
                settings.saveConfig(url.text.toString(), anon.text.toString(), model.text.toString())
            }
            if (direct.isChecked && !settings.hasOpenRouterApiKey()) {
                throw IllegalArgumentException("Enter and save your OpenRouter API key, or select Local Demo / Secure Supabase.")
            }
            settings.setUseDemoMode(demo.isChecked)
            settings.setUseDirectOpenRouterMode(direct.isChecked)
            settings.setRememberOnDevice(remember.isChecked)
            openRouterKey.text.clear()
            openRouterKey.hint = if (settings.hasOpenRouterApiKey()) "Key saved encrypted; leave blank to keep it" else "sk-or-v1-…"
            status.text = when {
                direct.isChecked -> "Direct OpenRouter saved. No Supabase/Auth/backend is used; memory remains on this device."
                demo.isChecked -> "Local Demo selected. No model or network requests will be made."
                settings.hasSession() -> "Secure AI settings saved. Supabase session is encrypted with Android Keystore."
                else -> "Supabase settings saved. Sign in to enable Secure AI, or select Direct OpenRouter."
            }
            status.setTextColor(if (direct.isChecked || settings.hasSession()) 0xFF8EE3B2.toInt() else C.DIM)
            updateStatus()
        } catch (e: Exception) {
            status.text = e.message ?: "Settings are invalid."
            status.setTextColor(C.RED)
        }
    }

    private fun authAction(
        url: EditText,
        anon: EditText,
        model: EditText,
        email: EditText,
        password: EditText,
        demo: CheckBox,
        direct: CheckBox,
        remember: CheckBox,
        status: android.widget.TextView,
        signUp: Boolean,
    ) {
        try {
            service.settings.saveConfig(url.text.toString(), anon.text.toString(), model.text.toString())
            service.settings.setRememberOnDevice(remember.isChecked)
        } catch (e: Exception) {
            status.text = e.message ?: "Supabase settings are invalid."
            return
        }
        val cfg = service.settings.config() ?: run { status.text = "Supabase config is incomplete."; return }
        val passwordValue = password.text.toString()
        val emailValue = email.text.toString()
        val cancellation = RequestCancellation()
        authCancellation = cancellation
        status.text = if (signUp) "Creating Supabase Auth account…" else "Signing in…"
        status.setTextColor(C.DIM)
        worker.submit {
            val result: Pair<com.sengine.aibuilder.provider.AuthOutcome?, String?> = try {
                val client = SupabaseAuthClient(service.settings)
                val outcome = if (signUp) client.signUp(cfg, emailValue, passwordValue, cancellation)
                else client.signIn(cfg, emailValue, passwordValue, cancellation)
                outcome to null
            } catch (e: Exception) { null to (e.message ?: "Supabase Auth request failed") }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                password.setText("")
                authCancellation = null
                val outcome = result.first
                val error = result.second
                if (outcome != null) {
                    status.text = outcome.message
                    status.setTextColor(if (outcome.signedIn) 0xFF8EE3B2.toInt() else C.YELLOW)
                    if (outcome.signedIn) {
                        service.settings.setUseDemoMode(demo.isChecked)
                        service.settings.setUseDirectOpenRouterMode(false)
                        direct.isChecked = false
                    }
                    updateStatus()
                } else {
                    status.text = error ?: "Supabase Auth failed. Check network/configuration."
                    status.setTextColor(C.RED)
                }
            }
        }
    }

    private fun beginTask(message: String): Int {
        val generation = ++taskGeneration
        busy = true
        taskDescription = message
        progressText.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        cancelButton.visibility = View.VISIBLE
        planButton.isEnabled = false
        planButton.alpha = 0.55f
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
        planButton.isEnabled = true
        planButton.alpha = 1f
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
            description.startsWith("Applying") -> "Build cancellation requested. The operation batch will roll back; any staged new project will be discarded."
            description.startsWith("Restoring") -> "Rollback cancellation requested. If restoration had started, the original scene is being restored."
            description.startsWith("Deleting") -> "Memory deletion cancelled. Local data may already be deleted; remote deletion status may be uncertain."
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
