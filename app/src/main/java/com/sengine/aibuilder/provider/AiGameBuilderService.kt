package com.sengine.aibuilder.provider

import android.content.Context
import com.sengine.aibuilder.engine.GameBuildCoordinator
import com.sengine.aibuilder.engine.GameBuildOutcome
import com.sengine.aibuilder.engine.GameProjectInspector
import com.sengine.aibuilder.memory.AiMemoryStore
import com.sengine.aibuilder.memory.SensitiveText
import com.sengine.aibuilder.spec.GameAction
import com.sengine.aibuilder.spec.GameSpec
import com.sengine.aibuilder.spec.GameSpecCodec
import com.sengine.aibuilder.spec.GameSpecParseException
import com.sengine.aibuilder.spec.GameSpecValidator
import com.sengine.aibuilder.spec.ValidationReport
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

class AiPlanResult(
    val spec: GameSpec,
    val validation: ValidationReport,
    val modeLabel: String,
    val responseModel: String,
    val memoryStatus: String,
)

data class AiAgentBuildResult(
    val outcome: GameBuildOutcome,
    val finalPlan: AiPlanResult,
    val passes: Int,
    val completionNote: String = "",
)

/** Local-first game-building service; the only remote call is directly to the selected AI provider. */
class AiGameBuilderService(context: Context) {
    private val appContext = context.applicationContext
    val settings = AiBuilderSettingsStore(appContext)
    val memory = AiMemoryStore(File(appContext.noBackupFilesDir, "ai-builder/memory"))
    private fun coordinator() = GameBuildCoordinator(if (settings.rememberOnDevice()) memory else null)

    fun plan(
        rawPrompt: String,
        selectedProjectName: String?,
        preferredTemplateId: String?,
        draftKey: String,
        cancellation: RequestCancellation,
    ): AiPlanResult {
        cancellation.check()
        val prompt = SensitiveText.redact(rawPrompt.trim())
        if (prompt.isBlank()) throw AiProviderException("Describe the game or change you want first.")
        if (prompt.length > 8_000) throw AiProviderException("Prompt is limited to 8,000 characters.")
        val project = selectedProjectName?.takeIf { it.isNotBlank() }?.let { ProjectManager.open(appContext, it) }
        if (project != null && !project.dir.isDirectory) {
            throw AiProviderException("Project '$selectedProjectName' no longer exists. Refresh the project list.")
        }
        val memoryKey = project?.name ?: draftKey
        val demo = settings.useDemoMode()
        val profile = settings.activeProfile()
        val modeLabel = if (demo) {
            "LOCAL TEMPLATE MODE — no model calls"
        } else {
            "${profile.kind.displayName} — direct HTTPS provider; encrypted device key; local-only memory"
        }
        if (!demo && profile.kind == AiProviderKind.OPENAI_COMPATIBLE && profile.endpoint.isBlank()) {
            throw AiProviderException("Enter a trusted HTTPS base URL for the OpenAI-compatible provider in AI Settings.")
        }
        val key = if (demo) null else settings.apiKey(profile.kind)
            ?: throw AiProviderException("Add a ${profile.kind.displayName} API key in AI Settings, or choose Local Template mode.")

        val remember = settings.rememberOnDevice()
        val context = if (remember) memory.context(memoryKey) else JSONObject()
        if (remember) memory.appendMessage(memoryKey, "user", prompt)
        val snapshot = project?.let(GameProjectInspector::snapshot)
        var responseModel = if (demo) "local-template-matcher" else profile.model
        val spec = try {
            if (demo) {
                DemoGamePlanner.plan(
                    prompt,
                    project,
                    preferredTemplateId,
                    ProjectManager.list(appContext).map { it.name },
                )
            } else {
                val request = AiRequest(prompt, profile.model, context, snapshot, preferredTemplateId?.takeIf { it != "auto" })
                val response = AiProviderFactory.create(profile, key!!).generate(request, cancellation)
                responseModel = response.model.ifBlank { profile.model }
                GameSpecCodec.parse(response.content)
            }
        } catch (e: Exception) {
            if (remember) memory.appendBuildEvent(memoryKey, "Planning request failed", "failed", errors = listOf(safeError(e.message)))
            throw e
        }
        val namedSpec = if (spec.action == GameAction.CREATE) {
            withAvailableProjectName(spec, ProjectManager.list(appContext).map { it.name })
        } else spec
        val validation = GameSpecValidator.validate(namedSpec, project?.name)
        val answer = buildString {
            append(namedSpec.summary)
            if (namedSpec.mechanics.isNotEmpty()) append("\n\nGame systems: ").append(namedSpec.mechanics.joinToString(", "))
            if (namedSpec.operations.isNotEmpty()) append("\n\nSafe build steps: ").append(namedSpec.operations.size)
            if (!validation.valid) append("\n\nValidation: ").append(validation.errors.joinToString(" ") { it.message })
        }
        if (remember) {
            memory.appendMessage(memoryKey, "assistant", answer, responseModel)
            if (!validation.valid) memory.appendBuildEvent(memoryKey, "GameSpec validation failed", "invalid", errors = validation.errors.map { it.message })
        }
        val memoryStatus = when {
            !remember -> "Memory saving is off; this request was not added to history."
            demo -> "Conversation memory is stored only on this device; no provider was called."
            else -> "Conversation memory is stored only on this device; the prompt and bounded project snapshot were sent directly to ${profile.kind.displayName}."
        }
        return AiPlanResult(namedSpec, validation, modeLabel, responseModel, memoryStatus)
    }

    fun applyPlan(
        plan: AiPlanResult,
        selectedProjectName: String?,
        memoryKey: String,
        cancellation: RequestCancellation,
    ): GameBuildOutcome {
        cancellation.check()
        if (!plan.validation.valid) throw AiProviderException("This plan has validation errors and cannot be applied.")
        val coordinator = coordinator()
        val outcome = when (plan.spec.action) {
            GameAction.CREATE -> coordinator.create(appContext, plan.spec, plan.modeLabel) { cancellation.check() }
            GameAction.EDIT -> {
                val name = selectedProjectName ?: throw AiProviderException("Choose an existing project before applying edits.")
                val project = ProjectManager.open(appContext, name)
                coordinator.edit(project, plan.spec, plan.modeLabel) { cancellation.check() }
            }
            GameAction.EXPLAIN -> throw AiProviderException("Explanation-only plans do not have changes to apply.")
        }
        val projectName = outcome.project.name
        if (settings.rememberOnDevice()) {
            if (projectName != memoryKey) try { memory.moveProject(memoryKey, projectName) } catch (_: Exception) { }
            memory.appendBuildEvent(
                projectName,
                outcome.report.summary,
                "success",
                outcome.report.affectedObjects,
                changes = outcome.report.changes,
            )
            if (plan.spec.action == GameAction.CREATE) {
                memory.appendDecision(projectName, "Created from '${outcome.report.templateName}': ${plan.spec.mechanics.joinToString(", ")}", "created_game")
            }
        }
        return outcome
    }

    /**
     * Bounded, cancellable autonomous agent: plan → validate → transactional build/smoke-check →
     * request a project-aware polish pass. Each pass is capped by the fixed GameSpec tool allowlist.
     */
    fun buildAutonomously(
        rawPrompt: String,
        selectedProjectName: String?,
        preferredTemplateId: String?,
        draftKey: String,
        maxPasses: Int = settings.agentMaxPasses(),
        cancellation: RequestCancellation,
        onProgress: (String) -> Unit = {},
    ): AiAgentBuildResult {
        val originalPrompt = SensitiveText.redact(rawPrompt.trim())
        if (originalPrompt.isBlank()) throw AiProviderException("Describe the game or change you want first.")
        if (originalPrompt.length > 8_000) throw AiProviderException("Prompt is limited to 8,000 characters.")
        val passLimit = maxPasses.coerceIn(1, 5)
        var targetProject = selectedProjectName
        var currentPrompt = originalPrompt
        var completedPasses = 0
        var latestOutcome: GameBuildOutcome? = null
        var latestPlan: AiPlanResult? = null
        var completionNote = ""

        while (completedPasses < passLimit) {
            cancellation.check()
            val pass = completedPasses + 1
            onProgress("Autonomous agent • pass $pass of $passLimit: analyzing the request and project…")
            val plan = try {
                plan(
                    currentPrompt,
                    targetProject,
                    if (pass == 1) preferredTemplateId else null,
                    draftKey,
                    cancellation,
                )
            } catch (e: GameSpecParseException) {
                completedPasses++
                if (completedPasses >= passLimit) throw AiProviderException("AI output could not be repaired within $passLimit agent passes: ${safeError(e.message)}", cause = e)
                currentPrompt = repairPrompt(originalPrompt, targetProject, "Invalid GameSpec: ${safeError(e.message)}")
                onProgress("Autonomous agent • repairing an invalid plan before any project changes…")
                continue
            }
            completedPasses++
            if (!plan.validation.valid) {
                val errors = plan.validation.errors.joinToString("; ") { it.message }
                if (completedPasses >= passLimit) throw AiProviderException("Agent stopped because its plan did not pass validation: ${safeError(errors)}")
                currentPrompt = repairPrompt(originalPrompt, targetProject, "Validation errors: ${safeError(errors)}")
                onProgress("Autonomous agent • re-planning against the operation and asset safety checks…")
                continue
            }
            if (plan.spec.action == GameAction.EXPLAIN) {
                if (latestOutcome != null && latestPlan != null) {
                    completionNote = plan.spec.summary
                    return AiAgentBuildResult(latestOutcome, latestPlan, completedPasses, completionNote)
                }
                throw AiProviderException(plan.spec.summary)
            }

            onProgress("Autonomous agent • pass $completedPasses: applying safe tools, saving the project, and running the S Engine startup check…")
            val memoryKey = targetProject ?: draftKey
            val outcome = try {
                applyPlan(plan, targetProject, memoryKey, cancellation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (completedPasses >= passLimit) throw AiProviderException("Agent build failed and its changes were rolled back: ${safeError(e.message)}", cause = e)
                currentPrompt = repairPrompt(originalPrompt, targetProject, "The previous transaction rolled back. Build check error: ${safeError(e.message)}")
                onProgress("Autonomous agent • the failed transaction was rolled back; creating a corrected plan…")
                continue
            }
            latestOutcome = outcome
            latestPlan = plan
            targetProject = outcome.project.name

            if (settings.useDemoMode()) {
                completionNote = "Local Template mode completed one safe build pass. Add a provider key to enable multi-pass autonomous polish."
                return AiAgentBuildResult(outcome, plan, completedPasses, completionNote)
            }
            if (completedPasses >= passLimit) {
                completionNote = "Autonomous pass limit reached; the project is saved and startup-checked."
                return AiAgentBuildResult(outcome, plan, completedPasses, completionNote)
            }
            currentPrompt = nextPassPrompt(originalPrompt, targetProject!!, completedPasses)
        }
        val outcome = latestOutcome ?: throw AiProviderException("The autonomous agent did not produce a build.")
        val plan = latestPlan ?: throw AiProviderException("The autonomous agent did not produce a valid plan.")
        return AiAgentBuildResult(outcome, plan, completedPasses, completionNote)
    }

    fun rememberDecision(projectKey: String, summary: String) {
        if (settings.rememberOnDevice()) memory.appendDecision(projectKey, summary)
    }

    fun rollback(project: Project, sceneName: String, cancellation: RequestCancellation): String {
        val message = coordinator().rollbackLatest(project, sceneName) { cancellation.check() }
        if (settings.rememberOnDevice()) memory.appendBuildEvent(project.name, message, "rolled_back", affected = listOf(sceneName))
        return message
    }

    fun clearMemory(projectKey: String, cancellation: RequestCancellation): String {
        cancellation.check()
        memory.clearProject(projectKey)
        return "Device-only AI conversation memory and rollback snapshots for '$projectKey' were deleted. No cloud memory is used."
    }

    fun memorySummary(projectKey: String): String {
        val events = memory.events(projectKey)
        val messages = events.count { it.kind == "message" }
        val decisions = events.count { it.kind == "decision" }
        val builds = events.count { it.kind == "build" || it.kind == "repair" }
        return "Project key: $projectKey\nMessages: $messages\nDesign decisions: $decisions\nBuild/repair events: $builds\n\nStored only in this app's private no-backup storage. Prompts and bounded project snapshots are sent directly to the selected provider when online AI is enabled. Rollback snapshots stay on this device."
    }

    private fun repairPrompt(original: String, projectName: String?, feedback: String): String = buildString {
        appendLine("Repair the previous S Engine game build plan. Keep the original goal unchanged:")
        appendLine(original.take(5_000))
        if (!projectName.isNullOrBlank()) appendLine("This is an edit to the existing project named exactly '$projectName'; do not create another project.")
        appendLine("Previous plan feedback: ${feedback.take(1_500)}")
        appendLine("Return one valid GameSpec v1 JSON object using only the installed templates, safe operations, and safe generated-asset recipes. If the goal cannot be completed safely, return action=explain with no operations and explain the limitation honestly.")
    }.take(8_000)

    private fun nextPassPrompt(original: String, projectName: String, pass: Int): String = buildString {
        appendLine("Autonomous game-builder polish pass $pass. Continue toward this original game brief:")
        appendLine(original.take(4_500))
        appendLine("The target already exists and is named exactly '$projectName'. Inspect the fresh project snapshot supplied separately. Return action=edit, projectName='$projectName', and at most one high-value safe improvement in one scene. Preserve the existing game style and working mechanics; do not duplicate existing systems.")
        appendLine("If the playable game already satisfies the brief, return action=explain, operations=[], and a concise honest completion note. Do not create a new project. Use only the bounded safe operation allowlist; no source code or arbitrary files.")
    }.take(8_000)

    private fun withAvailableProjectName(spec: GameSpec, existingNames: List<String>): GameSpec {
        if (spec.projectName !in existingNames) return spec
        val names = existingNames.toHashSet()
        var suffix = 2
        var name: String
        do {
            val tail = " $suffix"
            name = spec.projectName.take((40 - tail.length).coerceAtLeast(1)).trimEnd() + tail
            suffix++
        } while (name in names)
        val note = "\n\nA project with the requested name already exists; this build will create '$name' instead."
        val summary = spec.summary.take((2_000 - note.length).coerceAtLeast(0)).trimEnd() + note
        return spec.copy(projectName = name, summary = summary.take(2_000))
    }

    private fun safeError(value: String?): String =
        SensitiveText.redact(value.orEmpty().ifBlank { "unknown error" }).take(500)
}
