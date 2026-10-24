package com.sengine.aibuilder.provider

import com.sengine.aibuilder.spec.GameTemplateCatalog
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.Prop
import org.json.JSONArray
import org.json.JSONObject

/** Direct, user-key OpenRouter transport. The key is never sent to Supabase or logged. */
class DirectOpenRouterProvider(private val apiKey: String) : AiProvider {
    override fun generate(request: AiRequest, cancellation: RequestCancellation): AiResponse {
        cancellation.check()
        if (!apiKey.matches(Regex("sk-or-v1-[A-Za-z0-9_-]{16,}"))) {
            throw AiProviderException("Add a valid OpenRouter API key in AI Settings before planning.")
        }
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json",
            "X-Title" to "S Engine AI Game Builder",
        )
        var result = HttpJson.requestUrl(url, "POST", headers, body(request, jsonMode = true), cancellation, 300_000)
        if (result.status == 400 && isJsonModeRejection(result.body)) {
            result = HttpJson.requestUrl(url, "POST", headers, body(request, jsonMode = false), cancellation, 300_000)
        }
        if (result.status !in 200..299) {
            val detail = when (result.status) {
                401, 403 -> "OpenRouter rejected the API key. Check it in AI Settings."
                402 -> "OpenRouter account has insufficient credits or billing is not enabled."
                404 -> "OpenRouter could not find this model ID. Check the model ID, for example openai/gpt-4o-mini."
                408, 504 -> "OpenRouter timed out. Retry or choose a faster model."
                429 -> "OpenRouter rate limited this request. Check provider limits and retry later."
                in 500..599 -> "OpenRouter is temporarily unavailable (HTTP ${result.status})."
                else -> "OpenRouter rejected the request (HTTP ${result.status}). Check the selected model."
            }
            throw AiProviderException(detail, result.status)
        }
        return OpenRouterResponseParser.parse(result.body)
    }

    private fun body(request: AiRequest, jsonMode: Boolean): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", GameSpecPromptBuilder.systemPrompt()))
            .put(JSONObject().put("role", "user").put("content", JSONObject()
                .put("request", request.prompt)
                .put("projectMemory", request.context)
                .put("projectSnapshot", request.projectSnapshot ?: JSONObject.NULL)
                .put("preferredTemplateId", request.preferredTemplateId ?: "auto")
                .toString()))
        return JSONObject()
            .put("model", request.model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 3_000)
            .also { if (jsonMode) it.put("response_format", JSONObject().put("type", "json_object")) }
    }

    private fun isJsonModeRejection(body: String): Boolean =
        Regex("response_format|json.?mode|structured.?output", RegexOption.IGNORE_CASE).containsMatchIn(body)
}

/** Prompt built from the installed S Engine template and component registries, not invented APIs. */
internal object GameSpecPromptBuilder {
    fun systemPrompt(): String = buildString {
        appendLine("You are the planning assistant for S Engine, an Android game engine. Return exactly one JSON object conforming to the GameSpec v1 schema below. Do not return markdown, shell commands, arbitrary file paths, JavaScript source, GLSL, or instructions to execute code.")
        appendLine("The Android client independently validates every field and operation before applying it. Existing project files are the source of truth. Never invent an asset or target name. No generated source code or arbitrary filesystem operations are allowed.")
        appendLine("Schema: {\"schemaVersion\":1,\"action\":\"create|edit|explain\",\"projectName\":\"simple name\",\"genre\":\"short genre\",\"dimension\":\"2d|3d\",\"templateId\":\"installed ID or empty\",\"summary\":\"honest user-facing result and limitations\",\"mechanics\":[\"short mechanic\"],\"operations\":[...]}")
        appendLine("Installed templates: ${GameTemplateCatalog.entries().joinToString { "${it.id} (${it.dimension.wireName}): ${it.name}" }}. Use only these for create. A template already contains its actual gameplay and assets; do not claim features absent from its description. If snapshot.truncated is true, omitted items are unknown; ask for a missing exact target instead of inventing one.")
        appendLine("Allowed operations only: add_entity {name,scene,x,y,z,rotation,scaleX,scaleY,scaleZ,tag,components}; set_transform {objectName,scene and changed transform values}; add_component {objectName,scene,component}; set_component_property {objectName,scene,componentType,property,value}; set_script_parameter {objectName,scene,key,value,optional scriptAsset}; rename_entity {objectName,scene,newName}; set_scene_gravity {scene and changed gravity values}; add_hud_text {name,scene,text,x,y,size,color,order}.")
        appendLine("Use at most 20 operations, at most 8 components per new entity, and edit only one existing scene per plan. For edit, use exact names from the snapshot, action=edit and projectName exactly matching the selected project. For explain, use action=explain and operations=[]. For create, choose a real installed template ID. Static HUD text is only a label, not gameplay logic. A script parameter operation may change only an existing numeric parameter with key speed, jump, jumpHeight, health, maxHealth, damage, fireRate, range, or gravity; it never changes source. Do not write Params or script source strings.")
        appendLine("If a requested feature is not safely supported by the template and operations, explain the limitation in summary rather than claiming it was built. Use reasonable defaults unless destructive ambiguity requires one concise question in an explain plan.")
        appendLine("Real component names and editable property types from this S Engine build:")
        appendLine(componentCatalog())
    }

    private fun componentCatalog(): String = ComponentRegistry.types.keys.joinToString("\n") { type ->
        val component = ComponentRegistry.create(type)
        val props = component?.props().orEmpty().joinToString { prop ->
            when (prop) {
                is Prop.F -> "${prop.name}:number"
                is Prop.I -> "${prop.name}:integer"
                is Prop.B -> "${prop.name}:boolean"
                is Prop.S -> "${prop.name}:text"
                is Prop.Color -> "${prop.name}:hex color"
                is Prop.Choice -> "${prop.name}:one of ${prop.options.joinToString("/")}"
                is Prop.Asset -> "${prop.name}:existing ${prop.kind.name.lowercase()} asset file name"
            }
        }
        "$type {$props}"
    }
}
