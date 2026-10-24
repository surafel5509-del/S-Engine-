package com.sengine.project

/** Registry of built-in project templates. */
object Templates {
    /** A project template definition. */
    data class Template(
        val name: String = "",
        val description: String = "",
        val build: (Project) -> Unit = {}
    )

    /** All available templates. */
    val all: List<Template> = listOf(
        Template("Empty", "A blank project with no content."),
        Template("Platformer", "2D platformer starter with player, enemies, and level."),
        Template("Space Shooter", "Top-down space shooter with enemies and scoring."),
        Template("Puzzle", "Match-3 style puzzle game."),
        Template("RPG", "Basic RPG with dialogue and inventory."),
    )

    /** Default shader template. */
    const val NEW_SHADER = "// S Engine Shader\nvoid main() {\n    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);\n}"

    /** Default script template. */
    const val NEW_SCRIPT = "// S Engine Script\nfunction start() {\n    \n}\n\nfunction update(dt) {\n    \n}\n"
}
