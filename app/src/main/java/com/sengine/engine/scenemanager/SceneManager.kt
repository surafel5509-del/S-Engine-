package com.sengine.engine.scenemanager

import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.project.Project

// ─── Scene Manager ────────────────────────────────────────────────────────

/**
 * Advanced scene management with:
 * - Additive scene loading
 * - Scene transitions with effects
 * - Scene stacking
 * - Persistent objects across scenes
 */

class SceneManager(private val project: Project) {
    val loadedScenes = mutableListOf<Scene>()
    val persistentObjects = mutableListOf<String>() // Object names that persist
    var transitionEffect: Int = 0 // 0=None, 1=Fade, 2=Slide, 3=Wipe, 4=Dissolve
    var transitionDuration: Float = 0.5f
    var transitionProgress: Float = 0f
    var isTransitioning: Boolean = false
    var activeScene: Scene? = null

    /** Load a scene additively (doesn't unload current). */
    fun loadAdditive(sceneName: String): Scene? {
        if (!project.sceneExists(sceneName)) return null
        val scene = Scene(sceneName)
        loadedScenes.add(scene)
        if (activeScene == null) activeScene = scene
        return scene
    }

    /** Unload a specific scene. */
    fun unload(sceneName: String) {
        loadedScenes.removeAll { it.name == sceneName }
        if (activeScene?.name == sceneName) {
            activeScene = loadedScenes.firstOrNull()
        }
    }

    /** Load a scene (replacing current, with transition). */
    fun load(sceneName: String, effect: Int = transitionEffect, duration: Float = transitionDuration) {
        if (!project.sceneExists(sceneName)) return
        transitionEffect = effect
        transitionDuration = duration
        transitionProgress = 0f
        isTransitioning = true

        // Mark persistent objects before switching
        val persistent = activeScene?.objects?.filter { it.name in persistentObjects }

        // Switch scene
        val newScene = Scene(sceneName)
        loadedScenes.clear()
        loadedScenes.add(newScene)
        activeScene = newScene

        // Restore persistent objects
        persistent?.forEach { go ->
            if (newScene.find(go.name) == null) {
                newScene.objects.add(go)
            }
        }
    }

    /** Mark an object as persistent (survives scene changes). */
    fun markPersistent(objectName: String) {
        if (objectName !in persistentObjects) persistentObjects.add(objectName)
    }

    /** Unmark a persistent object. */
    fun unmarkPersistent(objectName: String) {
        persistentObjects.remove(objectName)
    }

    /** Update transition progress. */
    fun updateTransition(dt: Float) {
        if (isTransitioning) {
            transitionProgress += dt / transitionDuration
            if (transitionProgress >= 1f) {
                transitionProgress = 1f
                isTransitioning = false
            }
        }
    }

    /** Get all loaded scenes. */
    fun getAllScenes(): List<Scene> = loadedScenes.toList()

    /** Get scene by name. */
    fun getScene(name: String): Scene? = loadedScenes.firstOrNull { it.name == name }

    /** Get scene count. */
    val sceneCount: Int get() = loadedScenes.size

    /** Unload all scenes except active. */
    fun unloadAllExceptActive() {
        val active = activeScene
        loadedScenes.clear()
        if (active != null) loadedScenes.add(active)
    }
}

/** Scene transition data for rendering. */
data class TransitionState(
    var effect: Int = 0,
    var progress: Float = 0f,
    var color: Int = 0xFF000000.toInt(),
) {
    val alpha: Int get() = when {
        progress < 0.5f -> (progress * 2f * 255).toInt()
        else -> ((1f - progress) * 2f * 255).toInt()
    }.coerceIn(0, 255)

    val isActive: Boolean get() = progress in 0f..1f
}
