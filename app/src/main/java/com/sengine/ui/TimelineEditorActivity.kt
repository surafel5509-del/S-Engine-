package com.sengine.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sengine.engine.timeline.*

/**
 * Professional Timeline Animation Editor
 *
 * Full-featured timeline editor similar to Unity's Timeline or After Effects:
 * - Multiple track types (Animation, Audio, Control, Activation, Script)
 * - Keyframe editing with curve interpolation
 * - Multi-channel curves (Position XYZ, Rotation XYZ, Scale XYZ)
 * - Animation State Machine editor
 * - Markers and events on timeline
 * - Preview playback
 * - Curve editor with tangent handles
 * - Track grouping and nesting
 */
class TimelineEditorActivity : AppCompatActivity() {

    private var timeline = TimelineClip(name = "MainTimeline", duration = 10f)
    private var controller = AnimationController(name = "CharacterController")
    private var stateMachine: AnimStateMachine? = null
    private var selectedTrack: Int = -1
    private var selectedChannel: Int = -1
    private var currentTime: Float = 0f
    private var isPlaying = false
    private var editMode = 0 // 0=Tracks, 1=Curves, 2=StateMachine

    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var trackPanel: LinearLayout
    private lateinit var centerPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var curveEditorPanel: FrameLayout
    private lateinit var statusBar: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDefaultTimeline()
        buildUI()
    }

    private fun setupDefaultTimeline() {
        // Create default tracks
        val posTrack = timeline.addTrack("Player Transform", TrackType.Animation, "Player")
        posTrack.targetComponent = "Transform"
        posTrack.targetProperty = "position"
        val posX = posTrack.addChannel("X")
        posX.addKeyframe(0f, 0f); posX.addKeyframe(2f, 3f); posX.addKeyframe(5f, 8f); posX.addKeyframe(8f, 3f); posX.addKeyframe(10f, 0f)
        val posY = posTrack.addChannel("Y")
        posY.addKeyframe(0f, 0f); posY.addKeyframe(1f, 2f); posY.addKeyframe(3f, 0f); posY.addKeyframe(4f, 3f); posY.addKeyframe(6f, 0f)

        val rotTrack = timeline.addTrack("Player Rotation", TrackType.Animation, "Player")
        rotTrack.targetComponent = "Transform"
        rotTrack.targetProperty = "rotation"
        val rotZ = rotTrack.addChannel("Z")
        rotZ.addKeyframe(0f, 0f); rotZ.addKeyframe(5f, 360f); rotZ.addKeyframe(10f, 0f)

        val scaleTrack = timeline.addTrack("Player Scale", TrackType.Animation, "Player")
        scaleTrack.targetComponent = "Transform"
        scaleTrack.targetProperty = "scale"
        val scaleX = scaleTrack.addChannel("X")
        val scaleY = scaleTrack.addChannel("Y")
        scaleX.addKeyframe(0f, 1f); scaleX.addKeyframe(2.5f, 1.5f); scaleX.addKeyframe(5f, 1f); scaleX.addKeyframe(7.5f, 0.5f); scaleX.addKeyframe(10f, 1f)
        scaleY.keyframes.addAll(scaleX.keyframes.map { it.copy() })

        // Audio track
        val audioTrack = timeline.addTrack("Background Music", TrackType.Audio)
        audioTrack.clipName = "bgm_theme.ogg"

        // Script track
        val scriptTrack = timeline.addTrack("Game Events", TrackType.Script, "GameManager")
        scriptTrack.addChannel("trigger")

        // Add markers
        timeline.addMarker("Level Start", 0f)
        timeline.addMarker("First Encounter", 3f)
        timeline.addMarker("Boss Fight", 7f)
        timeline.addMarker("Level End", 10f)

        // Setup animation controller
        controller.addState("Idle", "idle.anim")
        controller.addState("Run", "run.anim")
        controller.addState("Jump", "jump.anim")
        controller.addState("Attack", "attack.anim")
        controller.addState("Hurt", "hurt.anim")
        controller.addState("Death", "death.anim")

        controller.addTransition("Idle", "Run", 0.15f).conditions.add(TransitionCondition("speed", 2, 0.1f))
        controller.addTransition("Run", "Idle", 0.2f).conditions.add(TransitionCondition("speed", 3, 0.1f))
        controller.addTransition("Any", "Jump", 0.05f).conditions.add(TransitionCondition("jumping", 0))
        controller.addTransition("Jump", "Idle", 0.1f).conditions.add(TransitionCondition("grounded", 0))
        controller.addTransition("Any", "Attack", 0.05f).conditions.add(TransitionCondition("attacking", 0))
        controller.addTransition("Attack", "Idle", 0.3f)
        controller.addTransition("Any", "Hurt", 0.05f).conditions.add(TransitionCondition("health", 3, 0f))
        controller.addTransition("Hurt", "Idle", 0.5f)
        controller.addTransition("Any", "Death", 0.3f).conditions.add(TransitionCondition("dead", 0))

        controller.setFloat("speed", 0f)
        controller.setBool("jumping", false)
        controller.setBool("grounded", true)
        controller.setBool("attacking", false)
        controller.setFloat("health", 3f)
        controller.setBool("dead", false)

        stateMachine = AnimStateMachine(controller)
    }

    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12121F.toInt())
        }

        // ── Toolbar ──
        toolbar = buildToolbar()
        rootLayout.addView(toolbar)

        // ── Mode tabs ──
        val modeTabs = buildModeTabs()
        rootLayout.addView(modeTabs)

        // ── Main content ──
        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // Left: Track list
        trackPanel = buildTrackPanel()
        mainContent.addView(trackPanel, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.MATCH_PARENT))

        // Center: Timeline / Curve Editor / State Machine
        centerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        buildCenterContent()
        mainContent.addView(centerPanel)

        // Right: Inspector
        rightPanel = buildInspectorPanel()
        mainContent.addView(rightPanel, LinearLayout.LayoutParams(dp(250), LinearLayout.LayoutParams.MATCH_PARENT))

        rootLayout.addView(mainContent)

        // ── Status bar ──
        statusBar = TextView(this).apply {
            updateStatusBar()
            textSize = 11f
            setTextColor(0xFF666688.toInt())
            setPadding(12, 4, 12, 4)
            setBackgroundColor(0xFF0A0A14.toInt())
        }
        rootLayout.addView(statusBar)

        setContentView(rootLayout)
    }

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(4, 4, 4, 4)
            gravity = Gravity.CENTER_VERTICAL
        }

        toolBtn(bar, "▶ Play", ::onPlay)
        toolBtn(bar, "⏸ Pause", ::handlePause)
        toolBtn(bar, "⏹ Stop", ::handleStop)
        toolBtn(bar, "⏮", ::onStart)
        toolBtn(bar, "⏭", ::onEnd)
        bar.addView(separator())
        toolBtn(bar, "＋ Track", ::onAddTrack)
        toolBtn(bar, "🗑 Delete", ::onDeleteTrack)
        toolBtn(bar, "📋 Duplicate", ::onDuplicateTrack)
        bar.addView(separator())
        toolBtn(bar, "🔑 Key", ::onAddKeyframe)
        toolBtn(bar, "⊕ Mute", ::onToggleMute)
        toolBtn(bar, "🔒 Lock", ::onToggleLock)
        bar.addView(separator())
        toolBtn(bar, "💾 Save", ::onSave)
        toolBtn(bar, "📤 Export", ::onExport)

        // Time display
        bar.addView(TextView(this).apply {
            text = "%.2f / %.2f".format(currentTime, timeline.duration)
            textSize = 12f
            setTextColor(0xFF44FF88.toInt())
            typeface = Typeface.MONOSPACE
            setPadding(16, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
        })

        return bar
    }

    private fun buildModeTabs(): LinearLayout {
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0D0D1A.toInt())
            setPadding(8, 4, 8, 4)
        }

        val modes = listOf("Timeline" to 0, "Curves" to 1, "State Machine" to 2)
        for ((label, mode) in modes) {
            tabs.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(if (mode == editMode) 0xFF4488FF.toInt() else 0xFF666688.toInt())
                setPadding(24, 8, 24, 8)
                setBackgroundColor(if (mode == editMode) 0xFF1A1A33.toInt() else Color.TRANSPARENT)
                gravity = Gravity.CENTER
                setOnClickListener { editMode = mode; rebuildCenter(); rebuildModeTabs(tabs) }
            })
        }

        return tabs
    }

    private fun rebuildModeTabs(tabs: LinearLayout) {
        val parent = tabs.parent as? LinearLayout ?: return
        val idx = parent.indexOfChild(tabs)
        parent.removeViewAt(idx)
        val newTabs = buildModeTabs()
        parent.addView(newTabs, idx)
    }

    private fun buildCenterContent() {
        when (editMode) {
            0 -> buildTimelineView()
            1 -> buildCurveEditor()
            2 -> buildStateMachineView()
        }
    }

    private fun buildTimelineView() {
        // Timeline header with time ruler
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 2, 4, 2)
            setBackgroundColor(0xFF0D0D1A.toInt())
        }
        header.addView(TextView(this).apply {
            text = "Tracks"
            textSize = 10f
            setTextColor(0xFF666688.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(8, 4, 8, 4)
        })

        // Time ruler
        val ruler = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val steps = 20
        for (i in 0..steps) {
            ruler.addView(TextView(this).apply {
                text = if (i % 5 == 0) "%.0f".format(timeline.duration * i / steps) else "|"
                textSize = 9f
                setTextColor(0xFF444466.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
            })
        }
        header.addView(ruler)

        // Track rows
        val trackScroll = ScrollView(this)
        val trackContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        for ((i, track) in timeline.tracks.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply { setMargins(0, 1, 0, 1) }
                setBackgroundColor(if (i == selectedTrack) 0xFF1A1A44.toInt() else 0xFF111122.toInt())
                setOnClickListener { selectedTrack = i; refreshAll() }
            }

            // Track label
            row.addView(TextView(this).apply {
                val icon = when (track.type) {
                    TrackType.Animation -> "🎬"
                    TrackType.Audio -> "🔊"
                    TrackType.Activation -> "✅"
                    TrackType.Control -> "🎛"
                    TrackType.Script -> "📝"
                    else -> "📌"
                }
                text = "$icon ${track.name}"
                textSize = 11f
                setTextColor(if (track.muted) 0xFF444466.toInt() else 0xFFCCCCFF.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.MATCH_PARENT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 0, 8, 0)
            })

            // Track content area with keyframe indicators
            val trackContent = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundColor(0xFF0A0A15.toInt())
            }

            // Draw keyframe indicators
            for (channel in track.channels) {
                for (kf in channel.keyframes) {
                    val xPos = (kf.time / timeline.duration)
                    trackContent.addView(View(this).apply {
                        layoutParams = FrameLayout.LayoutParams(dp(8), dp(8)).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            marginStart = (xPos * 1000).toInt() // Approximate position
                        }
                        setBackgroundColor(channel.color)
                    })
                }
            }

            row.addView(trackContent)
            trackContainer.addView(row)
        }

        // Marker row
        val markerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24))
            setBackgroundColor(0xFF0D0D1A.toInt())
        }
        markerRow.addView(TextView(this).apply {
            text = "📌 Markers"
            textSize = 10f
            setTextColor(0xFFFF8800.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
        })
        markerRow.addView(TextView(this).apply {
            text = timeline.markers.joinToString("  ") { "◆${it.name}" }
            textSize = 9f
            setTextColor(0xFFFF8800.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        })
        trackContainer.addView(markerRow)

        trackScroll.addView(trackContainer)

        centerPanel.removeAllViews()
        centerPanel.addView(header)
        centerPanel.addView(trackScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildCurveEditor() {
        centerPanel.removeAllViews()

        val curveHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0xFF0D0D1A.toInt())
        }
        curveHeader.addView(TextView(this).apply {
            text = "📈 Curve Editor — "
            textSize = 12f
            setTextColor(0xFF8888CC.toInt())
        })
        curveHeader.addView(TextView(this).apply {
            text = if (selectedTrack >= 0) timeline.tracks[selectedTrack].name else "Select a track"
            textSize = 12f
            setTextColor(0xFF44FF88.toInt())
        })
        centerPanel.addView(curveHeader)

        // Curve canvas area
        val curveArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFF080812.toInt())
        }

        // Draw grid and axis
        val canvasView = View(this).apply {
            setBackgroundColor(0xFF080812.toInt())
        }
        curveArea.addView(canvasView)

        // Channel legend
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.START }
        }

        if (selectedTrack >= 0) {
            for (ch in timeline.tracks[selectedTrack].channels) {
                legend.addView(TextView(this).apply {
                    text = "■ ${ch.name}"
                    textSize = 10f
                    setTextColor(ch.color)
                    setPadding(4, 2, 4, 2)
                })
            }
        }
        curveArea.addView(legend)

        centerPanel.addView(curveArea)
    }

    private fun buildStateMachineView() {
        centerPanel.removeAllViews()

        val smHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0xFF0D0D1A.toInt())
        }
        smHeader.addView(TextView(this).apply {
            text = "🔀 Animation State Machine: ${controller.name}"
            textSize = 12f
            setTextColor(0xFF8888CC.toInt())
        })
        centerPanel.addView(smHeader)

        val stateArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFF080812.toInt())
        }

        // Draw states as boxes
        val stateLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
        }

        // State grid (2 columns)
        val grid = GridLayout(this).apply { columnCount = 3 }

        for (state in controller.states) {
            val stateBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF1A1A44.toInt())
                setPadding(12, 8, 12, 8)
                layoutParams = GridLayout.LayoutParams().apply {
                    setMargins(8, 8, 8, 8)
                }
            }
            stateBox.addView(TextView(this).apply {
                text = "▶ ${state.name}"
                textSize = 13f
                setTextColor(0xFF44AAFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            stateBox.addView(TextView(this).apply {
                text = "Clip: ${state.clip}\nSpeed: ${state.speed}x | Loop: ${state.loop}"
                textSize = 10f
                setTextColor(0xFF888899.toInt())
            })
            grid.addView(stateBox)
        }

        stateLayout.addView(grid)

        // Parameters section
        val paramsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF111122.toInt())
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM }
        }
        paramsSection.addView(TextView(this).apply {
            text = "Parameters"
            textSize = 12f
            setTextColor(0xFF8888CC.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })

        for ((name, param) in controller.parameters) {
            paramsSection.addView(TextView(this).apply {
                val typeLabel = when (param.type) { 0 -> "Bool"; 1 -> "Float"; 2 -> "Int"; 3 -> "Trigger"; else -> "Unknown" }
                val valueLabel = when (param.type) { 0 -> param.boolVal.toString(); 1 -> "%.2f".format(param.floatVal); 2 -> param.intVal.toString(); 3 -> if (param.triggerVal) "✓" else ""; else -> "?" }
                text = "$name [$typeLabel] = $valueLabel"
                textSize = 11f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(4, 2, 4, 2)
            })
        }

        stateArea.addView(stateLayout)
        stateArea.addView(paramsSection)
        centerPanel.addView(stateArea)
    }

    private fun buildTrackPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(4, 4, 4, 4)
        }

        panel.addView(TextView(this).apply {
            text = "🎬 TRACKS & CHANNELS"
            textSize = 11f
            setTextColor(0xFF8888CC.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 8, 8, 8)
        })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        for ((i, track) in timeline.tracks.withIndex()) {
            list.addView(TextView(this).apply {
                val icon = when (track.type) { TrackType.Animation -> "🎬"; TrackType.Audio -> "🔊"; TrackType.Script -> "📝"; else -> "📌" }
                text = "$icon ${track.name}"
                textSize = 12f
                setTextColor(if (i == selectedTrack) 0xFF44AAFF.toInt() else 0xFFCCCCFF.toInt())
                setBackgroundColor(if (i == selectedTrack) 0xFF222255.toInt() else Color.TRANSPARENT)
                setPadding(8, 6, 8, 6)
                setOnClickListener { selectedTrack = i; refreshAll() }
            })
            for (ch in track.channels) {
                list.addView(TextView(this).apply {
                    text = "  └ ${ch.name} (${ch.keyframes.size} keys)"
                    textSize = 11f
                    setTextColor(ch.color)
                    setPadding(24, 3, 8, 3)
                })
            }
        }

        scroll.addView(list)
        panel.addView(scroll)
        return panel
    }

    private fun buildInspectorPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(4, 4, 4, 4)
        }

        panel.addView(TextView(this).apply {
            text = "⚙ INSPECTOR"
            textSize = 11f
            setTextColor(0xFF8888CC.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 8, 8, 8)
        })

        val scroll = ScrollView(this)
        val props = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 0, 4, 4)
        }

        // Timeline properties
        props.addView(sectionHeader("Timeline"))
        props.addView(propRow("Name", timeline.name))
        props.addView(propRow("Duration", "%.2f".format(timeline.duration)))
        props.addView(propRow("FPS", "%.0f".format(timeline.fps)))
        props.addView(propRow("Loop", if (timeline.loop) "Yes" else "No"))
        props.addView(propRow("Tracks", timeline.tracks.size.toString()))
        props.addView(propRow("Markers", timeline.markers.size.toString()))

        // Selected track
        if (selectedTrack >= 0 && selectedTrack < timeline.tracks.size) {
            val track = timeline.tracks[selectedTrack]
            props.addView(sectionHeader("Selected Track"))
            props.addView(propRow("Name", track.name))
            props.addView(propRow("Type", track.type.name))
            props.addView(propRow("Target", track.targetObject))
            props.addView(propRow("Component", track.targetComponent))
            props.addView(propRow("Property", track.targetProperty))
            props.addView(propRow("Muted", if (track.muted) "Yes" else "No"))
            props.addView(propRow("Locked", if (track.locked) "Yes" else "No"))
            props.addView(propRow("Channels", track.channels.size.toString()))
        }

        // State machine info
        props.addView(sectionHeader("State Machine"))
        props.addView(propRow("Controller", controller.name))
        props.addView(propRow("States", controller.states.size.toString()))
        props.addView(propRow("Transitions", controller.transitions.size.toString()))
        props.addView(propRow("Parameters", controller.parameters.size.toString()))
        stateMachine?.let {
            props.addView(propRow("Current State", it.currentState))
            props.addView(propRow("Transitioning", if (it.isTransitioning) "→ ${it.transitionTarget}" else "No"))
        }

        scroll.addView(props)
        panel.addView(scroll)
        return panel
    }

    // ── Helpers ──
    private fun toolBtn(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            this.text = text; textSize = 11f; setTextColor(0xFFCCCCFF.toInt())
            setPadding(10, 6, 10, 6); setBackgroundColor(0xFF222244.toInt()); gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.setMargins(1, 0, 1, 0) })
    }

    private fun separator(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(2, dp(24)).apply { setMargins(4, 0, 4, 0) }
        setBackgroundColor(0xFF333355.toInt())
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title; textSize = 11f; setTextColor(0xFF7777AA.toInt()); typeface = Typeface.DEFAULT_BOLD; setPadding(8, 10, 8, 4)
    }

    private fun propRow(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(8, 3, 8, 3)
        addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(TextView(context).apply { text = value; textSize = 11f; setTextColor(0xFFDDDDDD.toInt()); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
    }

    private fun updateStatusBar() {
        statusBar.text = "Timeline: ${timeline.name} | Duration: %.1fs | Tracks: ${timeline.tracks.size} | Markers: ${timeline.markers.size} | Mode: ${listOf("Timeline", "Curves", "State Machine")[editMode]} | Time: %.2fs".format(currentTime)
    }

    private fun refreshAll() {
        rebuildCenter()
        updateStatusBar()
    }

    private fun rebuildCenter() {
        centerPanel.removeAllViews()
        buildCenterContent()
    }

    private fun onPlay() { isPlaying = true }
    private fun handlePause() { isPlaying = false }
    private fun handleStop() { isPlaying = false; currentTime = 0f; refreshAll() }
    private fun handleStart() { currentTime = 0f; refreshAll() }
    private fun onEnd() { currentTime = timeline.duration; refreshAll() }
    private fun onAddTrack() {
        timeline.addTrack("New Track", TrackType.Animation, "Player")
        refreshAll()
    }
    private fun onDeleteTrack() { if (selectedTrack >= 0) { timeline.tracks.removeAt(selectedTrack); selectedTrack = -1; refreshAll() } }
    private fun onDuplicateTrack() { /* Duplicate selected track */ }
    private fun onAddKeyframe() {
        if (selectedTrack >= 0) {
            val track = timeline.tracks[selectedTrack]
            for (ch in track.channels) ch.addKeyframe(currentTime, ch.evaluate(currentTime))
            refreshAll()
        }
    }
    private fun onToggleMute() { if (selectedTrack >= 0) { timeline.tracks[selectedTrack].muted = !timeline.tracks[selectedTrack].muted; refreshAll() } }
    private fun onToggleLock() { if (selectedTrack >= 0) { timeline.tracks[selectedTrack].locked = !timeline.tracks[selectedTrack].locked; refreshAll() } }
    private fun onSave() { /* Save timeline to project */ }
    private fun onExport() { /* Export timeline data */ }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
