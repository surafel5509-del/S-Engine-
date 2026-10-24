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
import com.sengine.aibuilder.spec.GameSpecValidator
import com.sengine.aibuilder.spec.ValidationReport
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import org.json.JSONObject
import java.io.File

class AiPlanResult(
    val spec: GameSpec,
    val validation: ValidationReport,
    val modeLabel: String,
    val responseModel: String,
    val cloudMemoryStatus: String,
)

/** Offline-first orchestration around the existing project API and the configured provider mode. */
class AiGameBuilderService(context: Context) {
    private val appContext = context.applicationContext
    val settings = AiBuilderSettingsStore(appContext)
    val memory = AiMemoryStore(File(appContext.noBackupFilesDir, "ai-builder/memory"))
    private val auth = SupabaseAuthClient(settings)
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
        if (prompt.isBlank()) throw AiProviderException("Describe a game or a change first.")
        if (prompt.length > 8_000) throw AiProviderException("Prompt is limited to 8,000 characters.")
        val project = selectedProjectName?.takeIf { it.isNotBlank() }?.let { ProjectManager.open(appContext, it) }
        if (project != null && !project.dir.isDirectory) throw AiProviderException("Project '$selectedProjectName' no longer exists. Refresh the project list.")
        val memoryKey = project?.name ?: draftKey
        val demo = settings.useDemoMode()
        val direct = !demo && settings.useDirectOpenRouterMode()
        val secureSupabase = !demo && !direct
        val config = settings.config()
        val model = config?.model ?: settings.model()
        val modeLabel = when {
            demo -> "LOCAL DEMO — no model calls"
            direct -> "DIRECT OPENROUTER — device key; no Supabase/backend; device-only memory"
            else -> "SECURE AI — authenticated Supabase Edge Function / OpenRouter"
        }
        if (secureSupabase && config == null) throw AiProviderException("Secure AI needs Supabase URL and publishable/anon key. Use Direct OpenRouter or Local Demo to avoid Supabase.")
        if (secureSupabase && !settings.hasSession()) throw AiProviderException("Secure AI needs a signed-in Supabase account. Use Direct OpenRouter or Local Demo to avoid Supabase.")
        if (direct && !settings.hasOpenRouterApiKey()) throw AiProviderException("Direct OpenRouter mode needs an API key saved in AI Settings.")

        val remember = settings.rememberOnDevice()
        var cloudStatus = when {
            !remember -> "Memory saving is off; this request is not added to history."
            demo -> "Device-only memory; no model or cloud calls."
            direct -> "Device-only memory; OpenRouter is called directly from this device."
            else -> "Private Supabase memory sync attempted."
        }
        if (remember && secureSupabase && config != null) {
            try { syncMemory(memoryKey, config, cancellation) }
            catch (e: Exception) { cloudStatus = "Cloud memory sync unavailable; local history is still used (${safeError(e.message)})." }
        }
        val context = if (remember) memory.context(memoryKey) else JSONObject()
        if (remember) memory.appendMessage(memoryKey, "user", prompt)
        val snapshot = if (demo) null else project?.let(GameProjectInspector::snapshot)
        var spec: GameSpec
        var responseModel = if (demo) "local-template-matcher" else model
        try {
            if (demo) {
                spec = DemoGamePlanner.plan(
                    prompt,
                    project,
                    preferredTemplateId,
                    ProjectManager.list(appContext).map { it.name },
                )
            } else {
                val request = AiRequest(prompt, model, context, snapshot, preferredTemplateId?.takeIf { it != "auto" })
                val response = if (direct) {
                    val key = settings.openRouterApiKey() ?: throw AiProviderException("Direct OpenRouter key is missing; save it in AI Settings.")
                    DirectOpenRouterProvider(key).generate(request, cancellation)
                } else {
                    SupabaseOpenRouterProvider(config!!, auth).generate(request, cancellation)
                }
                responseModel = response.model.ifBlank { model }
                spec = GameSpecCodec.parse(response.content)
            }
        } catch (e: Exception) {
            if (remember) memory.appendBuildEvent(memoryKey, "Planning request failed", "failed", errors = listOf(safeError(e.message)))
            try { if (remember && secureSupabase && config != null) syncMemory(memoryKey, config, cancellation) } catch (_: Exception) { }
            throw e
        }
        if (spec.action == GameAction.CREATE) {
            val existingNames = ProjectManager.list(appContext).map { it.name }
            spec = withAvailableProjectName(spec, existingNames)
        }
        val validation = GameSpecValidator.validate(spec, project?.name)
        val answer = buildString {
            append(spec.summary)
            if (spec.mechanics.isNotEmpty()) append("\n\nMechanics: ").append(spec.mechanics.joinToString(", "))
            if (spec.operations.isNotEmpty()) append("\n\nProposed safe operations: ").append(spec.operations.size)
            if (!validation.valid) append("\n\nValidation failed: ").append(validation.errors.joinToString(" ") { it.message })
        }
        if (remember) {
            memory.appendMessage(memoryKey, "assistant", answer, responseModel)
            if (!validation.valid) memory.appendBuildEvent(memoryKey, "GameSpec validation failed", "invalid", errors = validation.errors.map { it.message })
        }
        try { if (remember && secureSupabase && config != null) syncMemory(memoryKey, config, cancellation) }
        catch (e: Exception) { cloudStatus = "Local history saved; cloud sync unavailable (${safeError(e.message)})." }
        return AiPlanResult(spec, validation, modeLabel, responseModel, cloudStatus)
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
            if (!settings.useDemoMode() && !settings.useDirectOpenRouterMode()) {
                val config = settings.config()
                if (config != null) try { syncMemory(projectName, config, cancellation) } catch (_: Exception) { }
            }
        }
        return outcome
    }

    fun rememberDecision(projectKey: String, summary: String) {
        if (settings.rememberOnDevice()) memory.appendDecision(projectKey, summary)
    }

    fun rollback(project: Project, sceneName: String, cancellation: RequestCancellation): String {
        val message = coordinator().rollbackLatest(project, sceneName) { cancellation.check() }
        if (settings.rememberOnDevice()) memory.appendBuildEvent(project.name, message, "rolled_back", affected = listOf(sceneName))
        return message
    }

    /** Local deletion always succeeds; configured remote deletion is attempted with RLS authorization. */
    fun clearMemory(projectKey: String, cancellation: RequestCancellation): String {
        val remote = !settings.useDemoMode() && !settings.useDirectOpenRouterMode()
        val config = settings.config()
        memory.clearProject(projectKey) // Local deletion is immediate, even if remote auth/network is unavailable.
        val token = if (remote && config != null) {
            try { auth.validAccessToken(config, cancellation) }
            catch (e: Exception) {
                return "Device memory for '$projectKey' was deleted. Supabase deletion could not be confirmed (${safeError(e.message)})."
            }
        } else null
        if (remote && config != null && token != null) {
            return try {
                SupabaseMemoryClient(config, token).deleteProject(projectKey, cancellation)
                "Device and Supabase memory for '$projectKey' were deleted."
            } catch (e: Exception) {
                "Device memory for '$projectKey' was deleted. Supabase deletion could not be confirmed (${safeError(e.message)}); reconnect and retry if needed."
            }
        }
        return if (remote && config != null) {
            "Device memory for '$projectKey' was deleted. Supabase deletion was not attempted because no active session is available."
        } else {
            "Device memory for '$projectKey' was deleted. Supabase was not configured or Local Demo mode is active."
        }
    }

    fun memorySummary(projectKey: String): String {
        val events = memory.events(projectKey)
        val messages = events.count { it.kind == "message" }
        val decisions = events.count { it.kind == "decision" }
        val builds = events.count { it.kind == "build" || it.kind == "repair" }
        return "Project key: $projectKey\nMessages: $messages\nDesign decisions: $decisions\nBuild/repair events: $builds\n\nStored locally in the app's private no-backup data. Direct OpenRouter and Local Demo keep all memory on this device. Only Secure AI mode syncs conversation/build records to the authenticated Supabase account under row-level security. Rollback snapshots always stay on this device."
    }

    private fun syncMemory(projectKey: String, config: SupabasePublicConfig, cancellation: RequestCancellation) {
        val token = auth.validAccessToken(config, cancellation)
            ?: throw AiProviderException("Supabase session expired; sign in again to sync private memory.")
        SupabaseMemoryClient(config, token).sync(projectKey, memory, cancellation)
    }

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
        val note = "\n\nA project with the requested name already exists; this plan will create '$name' instead."
        val summary = spec.summary.take((2_000 - note.length).coerceAtLeast(0)).trimEnd() + note
        return spec.copy(projectName = name, summary = summary.take(2_000))
    }

    private fun safeError(value: String?): String = SensitiveText.redact(value.orEmpty().ifBlank { "unknown error" }).take(240)
}
