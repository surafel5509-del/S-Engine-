package com.sengine.aibuilder.spec

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.Prop
import com.sengine.project.Templates

/** Catalog is built from real project templates; it never advertises an invented asset or template. */
object GameTemplateCatalog {
    data class Entry(val id: String, val name: String, val description: String, val dimension: GameDimension)

    private val knownDimensions = mapOf(
        "3d-demo" to GameDimension.THREE_D,
    )

    fun entries(): List<Entry> = Templates.all.map { template ->
        val id = idFor(template.name)
        Entry(id, template.name, template.description, knownDimensions[id] ?: GameDimension.TWO_D)
    }

    fun find(id: String): Entry? = entries().firstOrNull { it.id == id }
    fun template(id: String) = Templates.all.firstOrNull { idFor(it.name) == id }

    fun idFor(name: String): String = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

/** Rejects unsupported features before any project or scene is modified. */
object GameSpecValidator {
    private val scriptParameterAllowlist = setOf(
        "speed", "jump", "jumpHeight", "health", "maxHealth", "damage", "fireRate", "range", "gravity",
    )
    private val components2D = setOf(
        "SpriteRenderer", "TextRenderer", "Camera", "Rigidbody2D", "Collider2D", "Script",
        "ParticleEmitter", "AudioSource", "Animator",
    )
    private val components3D = setOf(
        "MeshRenderer", "Camera3D", "Light", "Rigidbody3D", "Collider3D", "Script", "AudioSource",
    )

    fun validate(spec: GameSpec, selectedProjectName: String? = null): ValidationReport {
        val issues = ArrayList<ValidationIssue>()
        fun error(code: String, message: String, index: Int? = null) {
            issues += ValidationIssue(code, message, operationIndex = index)
        }
        if (spec.schemaVersion != GAME_SPEC_VERSION) {
            error("unsupported_schema", "GameSpec version ${spec.schemaVersion} is unsupported; version $GAME_SPEC_VERSION is required.")
        }
        if (spec.projectName.length > 40 || sanitizeProjectName(spec.projectName) != spec.projectName || spec.projectName.isBlank()) {
            error("invalid_project_name", "Project name must use letters, numbers, spaces, hyphens, or underscores (max 40 characters).")
        }
        if (spec.genre.isBlank() || spec.genre.length > 80) error("invalid_genre", "Genre must be 1–80 characters.")
        if (spec.summary.isBlank() || spec.summary.length > 2_000) error("invalid_summary", "Summary must be 1–2,000 characters.")
        if (spec.mechanics.size > 16 || spec.mechanics.any { it.isBlank() || it.length > 160 }) error("invalid_mechanics", "Mechanics must be non-empty and are limited to 16 entries of 160 characters each.")
        if (spec.operations.size > MAX_GAME_OPERATIONS) error("operation_limit", "At most $MAX_GAME_OPERATIONS operations are allowed.")

        when (spec.action) {
            GameAction.CREATE -> {
                val template = GameTemplateCatalog.find(spec.templateId)
                if (template == null) error("unsupported_template", "Template '${spec.templateId}' is not in the installed S Engine catalog.")
                else if (template.dimension != spec.dimension) {
                    error("dimension_mismatch", "Template '${template.name}' is ${template.dimension.wireName}; the requested ${spec.dimension.wireName} mode is unsupported for it.")
                }
                if (selectedProjectName != null) error("create_target_mismatch", "Choose ‘Create new game’ before applying a create plan.")
            }
            GameAction.EDIT -> {
                if (selectedProjectName.isNullOrBlank()) error("project_required", "Choose an existing project before applying an edit plan.")
                else if (spec.projectName != selectedProjectName) error("project_mismatch", "The plan targets '${spec.projectName}', not the selected project '$selectedProjectName'.")
                if (spec.operations.isEmpty()) error("empty_edit", "An edit plan must contain at least one supported operation.")
                val editScenes = spec.operations.map { it.scene }.distinct()
                if (editScenes.size > 1) error("multi_scene_edit", "Edit one scene per build so the change can be reviewed and rolled back as a single transaction.")
            }
            GameAction.EXPLAIN -> if (spec.operations.isNotEmpty()) {
                error("explain_has_operations", "An explanation-only plan must not contain build operations.")
            }
        }

        spec.operations.forEachIndexed { index, operation -> validateOperation(spec.dimension, operation, index, issues) }
        return ValidationReport(issues)
    }

    private fun validateOperation(
        dimension: GameDimension,
        operation: GameOperation,
        index: Int,
        issues: MutableList<ValidationIssue>,
    ) {
        fun error(code: String, message: String) {
            issues += ValidationIssue(code, message, operationIndex = index)
        }
        if (operation.scene != "start" && !isSafeSceneName(operation.scene)) {
            error("invalid_scene", "Scene must be 'start' or a simple existing scene name.")
        }
        when (operation) {
            is GameOperation.AddEntity -> {
                if (!isSafeObjectName(operation.name)) error("invalid_entity_name", "Entity name must be 1–48 simple characters.")
                if (!isSafeTag(operation.tag)) error("invalid_tag", "Entity tag is too long or contains control characters.")
                validateTransforms(listOf(operation.x, operation.y, operation.z, operation.rotation, operation.scaleX, operation.scaleY, operation.scaleZ), index, issues)
                if (operation.scaleX <= 0f || operation.scaleY <= 0f || operation.scaleZ <= 0f) error("invalid_scale", "Entity scales must be greater than zero.")
                if (operation.components.size > MAX_COMPONENTS_PER_ENTITY) error("component_limit", "An entity can have at most $MAX_COMPONENTS_PER_ENTITY components.")
                if (operation.components.map { it.type }.distinct().size != operation.components.size) error("duplicate_component", "An entity cannot add the same component type more than once.")
                operation.components.forEach { validateComponent(dimension, it, index, issues) }
            }
            is GameOperation.SetTransform -> {
                if (!isSafeObjectName(operation.objectName)) error("invalid_entity_name", "Target entity name is invalid.")
                val values = listOfNotNull(operation.x, operation.y, operation.z, operation.rotation, operation.rotX, operation.rotY, operation.scaleX, operation.scaleY, operation.scaleZ)
                if (values.isEmpty()) error("empty_transform", "Set-transform needs at least one property.")
                validateTransforms(values, index, issues)
                if (listOfNotNull(operation.scaleX, operation.scaleY, operation.scaleZ).any { it <= 0f }) error("invalid_scale", "Scale values must be greater than zero.")
            }
            is GameOperation.AddComponent -> {
                if (!isSafeObjectName(operation.objectName)) error("invalid_entity_name", "Target entity name is invalid.")
                validateComponent(dimension, operation.component, index, issues)
            }
            is GameOperation.SetComponentProperty -> {
                if (!isSafeObjectName(operation.objectName)) error("invalid_entity_name", "Target entity name is invalid.")
                val component = componentFor(operation.componentType)
                if (component == null) error("unsupported_component", "Component '${operation.componentType}' is not supported.")
                else validateProperty(componentType = operation.componentType, property = operation.property, value = operation.value, index = index, issues = issues)
            }
            is GameOperation.SetScriptParameter -> {
                if (!isSafeObjectName(operation.objectName)) error("invalid_entity_name", "Target entity name is invalid.")
                if (operation.key !in scriptParameterAllowlist) error("script_parameter_not_allowed", "Script parameter '${operation.key}' is not in the safe parameter allowlist.")
                if (!operation.value.isFinite() || operation.value !in -1_000f..1_000f) error("invalid_script_parameter", "Script parameter values must be finite and between -1,000 and 1,000.")
                operation.scriptAsset?.let { if (!isSafeAssetName(it) || AssetKind.of(it) != AssetKind.SCRIPT) error("invalid_script_asset", "Script reference must be a simple .js or .bp asset name.") }
            }
            is GameOperation.RenameEntity -> {
                if (!isSafeObjectName(operation.objectName) || !isSafeObjectName(operation.newName)) error("invalid_entity_name", "Old and new entity names must be simple names.")
            }
            is GameOperation.SetSceneGravity -> {
                val values = listOfNotNull(operation.gravityX, operation.gravityY, operation.gravityZ, operation.gravity3D)
                if (values.isEmpty()) error("empty_gravity", "Set-scene-gravity needs at least one gravity value.")
                if (values.any { !it.isFinite() || it !in -100f..100f }) error("invalid_gravity", "Gravity values must be finite and between -100 and 100.")
            }
            is GameOperation.AddHudText -> {
                if (!isSafeObjectName(operation.name)) error("invalid_entity_name", "HUD object name must be a simple name.")
                if (operation.text.length > 500) error("hud_text_too_long", "HUD text is limited to 500 characters.")
                validateTransforms(listOf(operation.x, operation.y, operation.size), index, issues)
                if (operation.size <= 0f || operation.size > 8f) error("invalid_hud_size", "HUD text size must be greater than zero and at most 8.")
                if (!isColor(operation.color)) error("invalid_color", "HUD color must be #RRGGBB or #AARRGGBB.")
                if (operation.order !in -10_000..10_000) error("invalid_order", "HUD draw order is outside the supported range.")
            }
        }
    }

    private fun validateComponent(
        dimension: GameDimension,
        spec: ComponentSpec,
        index: Int,
        issues: MutableList<ValidationIssue>,
    ) {
        val component = componentFor(spec.type)
        if (component == null) {
            issues += ValidationIssue("unsupported_component", "Component '${spec.type}' is not supported.", operationIndex = index)
            return
        }
        val supported = if (dimension == GameDimension.TWO_D) components2D else components3D
        if (spec.type !in supported) {
            issues += ValidationIssue("component_dimension_mismatch", "Component '${spec.type}' is not supported for ${dimension.wireName} builds.", operationIndex = index)
        }
        if (spec.properties.size > 24) issues += ValidationIssue("component_property_limit", "A component can have at most 24 configured properties.", operationIndex = index)
        if (spec.type == "Script" && spec.properties.keys.any { it != "Script" }) {
            issues += ValidationIssue("script_params_restricted", "AI plans may attach an existing script asset, but may not write script source or parameter strings. Use set_script_parameter.", operationIndex = index)
        }
        spec.properties.forEach { (property, value) ->
            validateProperty(spec.type, property, value, index, issues)
        }
    }

    private fun validateProperty(
        componentType: String,
        property: String,
        value: SpecValue,
        index: Int,
        issues: MutableList<ValidationIssue>,
    ) {
        val component = componentFor(componentType)
        val prop = component?.props()?.firstOrNull { it.name == property }
        if (prop == null) {
            issues += ValidationIssue("unsupported_property", "'$property' is not an editable property of '$componentType'.", operationIndex = index)
            return
        }
        if (componentType == "Script" && property == "Params") {
            issues += ValidationIssue("script_params_restricted", "Use set_script_parameter with an allowlisted numeric key; arbitrary script parameter strings are not accepted.", operationIndex = index)
            return
        }
        if (componentType == "Script" && property == "Script" && value is SpecValue.Text && value.value.isNotBlank() &&
            (!isSafeAssetName(value.value) || AssetKind.of(value.value) != AssetKind.SCRIPT)) {
            issues += ValidationIssue("invalid_script_reference", "Script components may reference only a simple .js or .bp asset name.", operationIndex = index)
            return
        }
        val message = when (prop) {
            is Prop.F -> if (value !is SpecValue.Number || !value.value.isFinite() || value.value !in -100_000.0..100_000.0) "'$property' must be a finite number in the supported range." else null
            is Prop.I -> if (value !is SpecValue.Number || !value.value.isFinite() || value.value % 1.0 != 0.0 || value.value !in -100_000.0..100_000.0) "'$property' must be an integer in the supported range." else null
            is Prop.B -> if (value !is SpecValue.Bool) "'$property' must be true or false." else null
            is Prop.S -> if (value !is SpecValue.Text || value.value.length > 500 || value.value.any { it.isISOControl() && it != '\n' && it != '\t' }) "'$property' must be text of at most 500 characters." else null
            is Prop.Color -> if (value !is SpecValue.Text || !isColor(value.value)) "'$property' must be a #RRGGBB or #AARRGGBB color." else null
            is Prop.Choice -> if (value !is SpecValue.Text || value.value !in prop.options) "'$property' must be one of: ${prop.options.joinToString()}" else null
            is Prop.Asset -> if (value !is SpecValue.Text || (value.value.isNotBlank() && !isSafeAssetName(value.value))) "'$property' must reference a simple file name, not a path." else null
        }
        if (message != null) issues += ValidationIssue("invalid_component_property", message, operationIndex = index)
        if (componentType == "Script" && property == "Script" && value is SpecValue.Text && value.value.isNotBlank() && AssetKind.of(value.value) != AssetKind.SCRIPT) {
            issues += ValidationIssue("invalid_script_reference", "Script components may reference only existing .js or .bp assets.", operationIndex = index)
        }
    }

    private fun componentFor(type: String) = ComponentRegistry.create(type)

    private fun validateTransforms(values: List<Float>, index: Int, issues: MutableList<ValidationIssue>) {
        if (values.any { !it.isFinite() || kotlin.math.abs(it) > 10_000f }) {
            issues += ValidationIssue("invalid_transform", "Transform values must be finite and within ±10,000.", operationIndex = index)
        }
    }

    fun sanitizeProjectName(name: String): String = name.trim()
        .replace(Regex("[^A-Za-z0-9 _-]"), "")
        .take(40)

    fun isSafeObjectName(name: String): Boolean = name.isNotBlank() && name.length <= 48 &&
        name.none { it.isISOControl() || it == '/' || it == '\\' } && name.trim() == name

    fun isSafeTag(tag: String): Boolean = tag.length <= 48 && tag.none { it.isISOControl() || it == '/' || it == '\\' }

    fun isSafeSceneName(name: String): Boolean = name.isNotBlank() && name.length <= 40 &&
        name.matches(Regex("[A-Za-z0-9 _-]+")) && name != "." && name != ".."

    fun isSafeAssetName(name: String): Boolean = name.isNotBlank() && name.length <= 96 &&
        name.matches(Regex("[A-Za-z0-9_. -]+")) && name != "." && name != ".." &&
        !name.contains("..") && AssetKind.of(name) != null

    fun isColor(value: String): Boolean = value.matches(Regex("#(?:[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})"))
}
