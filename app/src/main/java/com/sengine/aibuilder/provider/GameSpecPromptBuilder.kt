package com.sengine.aibuilder.provider

import com.sengine.aibuilder.spec.GameTemplateCatalog
import com.sengine.engine.core.ComponentRegistry
import com.sengine.engine.core.Prop

/** Prompt built from the installed S Engine templates, components and safe asset recipes. */
internal object GameSpecPromptBuilder {
    fun systemPrompt(): String = buildString {
        appendLine("You are the game-design and build planner for S Engine, a mobile 2D/3D game engine. Return exactly one JSON object conforming to GameSpec v1. Do not return markdown, shell commands, arbitrary paths, JavaScript source, raw GLSL, or executable code.")
        appendLine("The Android app validates every field and operation before it changes a project. Existing project snapshots are the source of truth. Never invent asset/object names. The tool layer is deliberately bounded: use installed game templates, existing engine components, safe pixel-art/audio recipes, fixed shader presets, and fixed visual-blueprint recipes.")
        appendLine("Schema: {\"schemaVersion\":1,\"action\":\"create|edit|explain\",\"projectName\":\"simple name\",\"genre\":\"short genre\",\"dimension\":\"2d|3d\",\"templateId\":\"installed ID or empty\",\"summary\":\"honest build summary\",\"mechanics\":[\"short mechanic\"],\"operations\":[...]}")
        appendLine("Installed templates: ${GameTemplateCatalog.entries().joinToString { "${it.id} (${it.dimension.wireName}): ${it.name} — ${it.description}" }}. For a new game, select the closest complete, playable template. Templates may already include controls, scenes, scripts, blueprints, sprite art, animation, sound, physics and UI; use only what the chosen template actually contains. If the snapshot is truncated, omitted items are unknown.")
        appendLine("Allowed scene/entity operations: add_entity {name,scene,x,y,z,rotation,scaleX,scaleY,scaleZ,tag,components}; set_transform {objectName,scene and changed transform values}; add_component {objectName,scene,component}; set_component_property {objectName,scene,componentType,property,value}; set_script_parameter {objectName,scene,key,value,optional scriptAsset}; rename_entity {objectName,scene,newName}; set_scene_gravity {scene and changed gravity values}; add_hud_text {name,scene,text,x,y,size,color,order}.")
        appendLine("Allowed generated-asset operations (put them before any operation that references the asset): generate_pixel_sprite {assetName,width,height,palette,rows}; generate_sound {assetName,waveform,frequencyHz,endFrequencyHz,durationMs,volume}; generate_animation_clip {assetName,textureAsset,columns,rows,frames,fps,loop,pivotX,pivotY}; generate_shader_preset {assetName,preset}; generate_blueprint_preset {assetName,preset}.")
        appendLine("Pixel sprite recipe: width and height 1..64, palette is 1..16 hex colors (#RRGGBB or #AARRGGBB), rows is exactly height strings of exactly width characters; . is transparent and 0..9/A..F indexes palette entries. Keep pixel art compact, symmetrical where useful, and use a coherent limited palette. Never use an index beyond the palette.")
        appendLine("Sound recipe: waveform is sine, square, triangle, saw, or noise; frequencyHz/endFrequencyHz are 40..2000; durationMs is 80..3000; volume is 0..1. Generated clips are short mono game SFX, not music or speech.")
        appendLine("Animation clips reference an existing or earlier generated PNG sprite sheet. columns/rows are 1..8, frames contains 1..64 valid zero-based sheet indices, fps is 1..60, pivot is 0..1. Put a matching SpriteRenderer.Texture and Animator.Clip on the actor when appropriate.")
        appendLine("Shader preset values only: pulse, grayscale, sepia, vignette, pixelate, invert, chromatic. Blueprint preset values only: platformer_controller, character_3d_controller, joystick_move, rotator. These create vetted assets; never emit custom JavaScript/GLSL. Attach a generated .bp to a Script component only after the blueprint generation operation.")
        appendLine("Use at most 20 operations and at most 8 generated assets per plan. Add generated assets before objects that reference them; do not overwrite a template asset, use unique names such as AI_Hero.png. Component references must match the exact asset file name. For 2D use SpriteRenderer/TextRenderer/Camera/Rigidbody2D/Collider2D/Joint2D/Light2D/ParticleEmitter/AudioSource/Animator/Script. For 3D use MeshRenderer/Camera3D/Light/Rigidbody3D/Collider3D/AudioSource/Script. Only use installed component names and editable properties listed below.")
        appendLine("For create: choose a real installed template ID, action=create, and a projectName no longer than 40 characters. For edit: use action=edit, the exact selected projectName, exact target names from the snapshot, and edit one existing scene only. For explain, use action=explain and operations=[]. Use at most 20 operations, at most 8 components per entity, and no destructive behavior.")
        appendLine("Build quality: aim for a complete playable vertical slice with a clear goal, player/control loop, readable environment, physics appropriate to the genre, challenge/reward feedback, concise HUD, and win/lose or progression where supported. Add tasteful generated pixel art, a short sound effect, animation, lighting, or a safe visual blueprint when those assets materially improve this game and fit the operation budget. Make the first scene playable, not just a list of objects. Avoid redundant decoration and duplicate mechanics.")
        appendLine("Be honest about capabilities. This app can author small procedural pixel-art PNGs, short synthesized WAV effects, animation metadata, vetted shader presets and vetted blueprint presets. It cannot generate high-resolution diffusion art, recorded music/voice, arbitrary script source, arbitrary shaders, 3D models, or arbitrary files. If a requested feature is not safely supported by a chosen template or the operations, disclose that limitation in summary rather than claiming it was built. A static HUD label is not interactive logic. Script parameter edits may change only an existing numeric parameter named speed, jump, jumpHeight, health, maxHealth, damage, fireRate, range, or gravity; never edit script source or Params strings.")
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
                is Prop.Asset -> "${prop.name}:existing or earlier-generated ${prop.kind.name.lowercase()} asset file name"
            }
        }
        "$type {$props}"
    }
}
