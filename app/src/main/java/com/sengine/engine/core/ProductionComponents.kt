package com.sengine.engine.core

import com.sengine.engine.anim.SkeletonPose
import com.sengine.engine.anim.SkeletalClip

/** Separate from the sprite Animator so existing 2D projects retain their exact behaviour. */
class SkeletalAnimator : Component() {
    override val type = "SkeletalAnimator"
    var model = ""
    var clip = ""
    var speed = 1f
    var loop = true
    var playOnStart = true
    var crossfade = 0.18f
    var previewInEditor = true

    @Transient var current = ""
    @Transient var time = 0f
    @Transient var playing = false
    @Transient var finished = false
    @Transient var pose: SkeletonPose? = null
    @Transient var palette = FloatArray(0)
    @Transient var activeClip: SkeletalClip? = null
    @Transient var editorClip: SkeletalClip? = null
    @Transient var fadePose: SkeletonPose? = null
    @Transient var fadeElapsed = 0f
    @Transient var fadeDuration = 0f
    @Transient var requestedClip: String? = null
    @Transient var requestedFade = 0f
    @Transient var restart = false
    @Transient var lastStamp = ""

    override fun props() = listOf(
        Prop.Asset("Model", AssetKind.MODEL, { model }, { model = it }),
        Prop.S("Clip", { clip }, { clip = it }),
        Prop.F("Speed", { speed }, { speed = it.coerceIn(-4f,4f) }, 0.05f),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.F("Crossfade Seconds", { crossfade }, { crossfade = it.coerceIn(0f,2f) }, 0.02f),
        Prop.B("Preview In Editor", { previewInEditor }, { previewInEditor = it }),
    )
    override fun resetRuntime() {
        current = clip; time = 0f; playing = playOnStart; finished = false
        pose = null; activeClip = null; fadePose = null; fadeElapsed = 0f; fadeDuration = 0f
        requestedClip = null; restart = false; lastStamp = ""; editorClip = null
    }
    fun request(name: String, fade: Float = crossfade) {
        requestedClip = name; requestedFade = fade.coerceIn(0f,2f); restart = true; finished = false
    }
}
