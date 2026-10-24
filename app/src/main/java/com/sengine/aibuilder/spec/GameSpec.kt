package com.sengine.aibuilder.spec

/** The independently versioned, reviewable description passed between the AI and S Engine. */
const val GAME_SPEC_VERSION = 1
const val MAX_GAME_OPERATIONS = 20
const val MAX_COMPONENTS_PER_ENTITY = 8
const val MAX_GENERATED_GAME_ASSETS = 8
const val MAX_GENERATED_PIXEL_DIMENSION = 64

/** Numeric string IDs are deliberately stable; they are not derived from file paths. */
enum class GameAction(val wireName: String) {
    CREATE("create"), EDIT("edit"), EXPLAIN("explain");

    companion object {
        fun fromWire(value: String): GameAction? = entries.firstOrNull { it.wireName == value }
    }
}

enum class GameDimension(val wireName: String) {
    TWO_D("2d"), THREE_D("3d");

    companion object {
        fun fromWire(value: String): GameDimension? = entries.firstOrNull { it.wireName == value }
    }
}

/** JSON property values are scalar by design: the tool layer never accepts arbitrary JSON trees. */
sealed class SpecValue {
    data class Text(val value: String) : SpecValue()
    data class Number(val value: Double) : SpecValue()
    data class Bool(val value: Boolean) : SpecValue()
}

data class ComponentSpec(
    val type: String,
    val properties: Map<String, SpecValue> = emptyMap(),
)

/** Explicit allowlisted engine operations. No filesystem writes or generated source code exist here. */
sealed class GameOperation {
    abstract val scene: String

    data class AddEntity(
        override val scene: String = "start",
        val name: String,
        val x: Float = 0f,
        val y: Float = 0f,
        val z: Float = 0f,
        val rotation: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val scaleZ: Float = 1f,
        val tag: String = "Untagged",
        val components: List<ComponentSpec> = emptyList(),
    ) : GameOperation()

    data class SetTransform(
        override val scene: String = "start",
        val objectName: String,
        val x: Float? = null,
        val y: Float? = null,
        val z: Float? = null,
        val rotation: Float? = null,
        val rotX: Float? = null,
        val rotY: Float? = null,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val scaleZ: Float? = null,
    ) : GameOperation()

    data class AddComponent(
        override val scene: String = "start",
        val objectName: String,
        val component: ComponentSpec,
    ) : GameOperation()

    data class SetComponentProperty(
        override val scene: String = "start",
        val objectName: String,
        val componentType: String,
        val property: String,
        val value: SpecValue,
    ) : GameOperation()

    /** Updates a numeric, allowlisted Script-component parameter; it never edits JavaScript source. */
    data class SetScriptParameter(
        override val scene: String = "start",
        val objectName: String,
        val key: String,
        val value: Float,
        val scriptAsset: String? = null,
    ) : GameOperation()

    data class RenameEntity(
        override val scene: String = "start",
        val objectName: String,
        val newName: String,
    ) : GameOperation()

    data class SetSceneGravity(
        override val scene: String = "start",
        val gravityX: Float? = null,
        val gravityY: Float? = null,
        val gravityZ: Float? = null,
        val gravity3D: Float? = null,
    ) : GameOperation()

    /** Creates a screen-space TextRenderer object, not a generated scripted widget. */
    data class AddHudText(
        override val scene: String = "start",
        val name: String,
        val text: String,
        val x: Float = 0f,
        val y: Float = 3.5f,
        val size: Float = 0.45f,
        val color: String = "#FFFFFFFF",
        val order: Int = 100,
    ) : GameOperation()

    /** Small indexed-color PNG generated locally from a bounded palette and pixel grid. */
    data class GeneratePixelSprite(
        override val scene: String = "start",
        val assetName: String,
        val width: Int,
        val height: Int,
        val palette: List<String>,
        val rows: List<String>,
    ) : GameOperation()

    /** Short deterministic mono WAV effect; this recipe never accepts audio bytes or executable data. */
    data class GenerateSound(
        override val scene: String = "start",
        val assetName: String,
        val waveform: String,
        val frequencyHz: Float,
        val endFrequencyHz: Float,
        val durationMs: Int,
        val volume: Float,
    ) : GameOperation()

    /** Animation metadata for an existing or earlier-generated sprite sheet. */
    data class GenerateAnimationClip(
        override val scene: String = "start",
        val assetName: String,
        val textureAsset: String,
        val columns: Int,
        val rows: Int,
        val frames: List<Int>,
        val fps: Float,
        val loop: Boolean,
        val pivotX: Float = 0.5f,
        val pivotY: Float = 0.5f,
    ) : GameOperation()

    /** Writes one vetted engine shader source from a fixed preset list, never model-written GLSL. */
    data class GenerateShaderPreset(
        override val scene: String = "start",
        val assetName: String,
        val preset: String,
    ) : GameOperation()

    /** Writes one vetted visual-blueprint graph from a fixed node recipe, never arbitrary source. */
    data class GenerateBlueprintPreset(
        override val scene: String = "start",
        val assetName: String,
        val preset: String,
    ) : GameOperation()
}

data class GameSpec(
    val schemaVersion: Int = GAME_SPEC_VERSION,
    val action: GameAction,
    val projectName: String,
    val genre: String,
    val dimension: GameDimension,
    val templateId: String = "",
    val summary: String,
    val mechanics: List<String> = emptyList(),
    val operations: List<GameOperation> = emptyList(),
)

data class ValidationIssue(
    val code: String,
    val message: String,
    val severity: Severity = Severity.ERROR,
    val operationIndex: Int? = null,
) {
    enum class Severity { ERROR, WARNING }
}

data class ValidationReport(val issues: List<ValidationIssue>) {
    val valid: Boolean get() = issues.none { it.severity == ValidationIssue.Severity.ERROR }
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == ValidationIssue.Severity.ERROR }
}

data class GameBuildReport(
    val projectName: String,
    val created: Boolean,
    val summary: String,
    val templateName: String,
    val affectedScenes: List<String>,
    val affectedObjects: List<String>,
    val changes: List<String>,
    val validation: List<ValidationIssue>,
    val runtimeSmokeTest: String,
    val modeLabel: String,
    val rollbackToken: String? = null,
    val rollbackScene: String? = null,
)
