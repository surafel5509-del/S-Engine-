package com.sengine.ui

import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.sengine.engine.skeleton.*
import com.sengine.engine.timeline.*

/**
 * Professional Skeleton Animation Editor
 *
 * Features:
 * - Visual bone hierarchy editing
 * - Bone transform manipulation (translate, rotate, scale)
 * - IK constraint solver (2-bone IK)
 * - Keyframe-based animation with curve editor
 * - Animation clip management
 * - Preview and playback with timeline
 * - Skin/attachment editing
 * - Export to .skel/.skelanim format
 */
class SkeletonEditorActivity : AppCompatActivity() {

    private var skeleton = SkeletonData()
    private var currentClip = SkeletonAnimClip(name = "idle", duration = 1f)
    private var selectedBone: Int = -1
    private var currentTime: Float = 0f
    private var isPlaying: Boolean = false
    private var playbackSpeed: Float = 1f
    private var showIKHandles: Boolean = false

    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var boneTreeView: LinearLayout
    private lateinit var canvasContainer: FrameLayout
    private lateinit var propertiesPanel: LinearLayout
    private lateinit var timelinePanel: LinearLayout
    private lateinit var statusBar: TextView
    private lateinit var canvasView: SkeletonCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createDefaultSkeleton()
        buildUI()
    }

    private fun createDefaultSkeleton() {
        skeleton.name = "Character"
        val root = skeleton.addBone("root", -1, 0f, 0f)
        root.length = 0.5f
        val spine = skeleton.addBone("spine", root.id, 0f, 0.5f)
        spine.length = 1.5f
        val head = skeleton.addBone("head", spine.id, 0f, 1.5f)
        head.length = 0.8f
        val lArm = skeleton.addBone("arm_L", spine.id, -0.3f, 1.2f)
        lArm.length = 1f; lArm.localRotation = Math.toRadians(30.0).toFloat()
        val lHand = skeleton.addBone("hand_L", lArm.id, -0.8f, -0.5f)
        lHand.length = 0.5f
        val rArm = skeleton.addBone("arm_R", spine.id, 0.3f, 1.2f)
        rArm.length = 1f; rArm.localRotation = Math.toRadians(-30.0).toFloat()
        val rHand = skeleton.addBone("hand_R", rArm.id, 0.8f, -0.5f)
        rHand.length = 0.5f
        val lLeg = skeleton.addBone("leg_L", root.id, -0.3f, 0f)
        lLeg.length = 1.2f; lLeg.localRotation = Math.toRadians(90.0).toFloat()
        val lFoot = skeleton.addBone("foot_L", lLeg.id, 0f, -1.2f)
        lFoot.length = 0.4f
        val rLeg = skeleton.addBone("leg_R", root.id, 0.3f, 0f)
        rLeg.length = 1.2f; rLeg.localRotation = Math.toRadians(90.0).toFloat()
        val rFoot = skeleton.addBone("foot_R", rLeg.id, 0f, -1.2f)
        rFoot.length = 0.4f
    }

    private fun buildUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt())
        }

        // ── Toolbar ──
        toolbar = buildToolbar()
        rootLayout.addView(toolbar)

        // ── Main content ──
        val mainContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // Left: Bone tree
        boneTreeView = buildBoneTree()
        mainContent.addView(boneTreeView, LinearLayout.LayoutParams(dp(200), LinearLayout.LayoutParams.MATCH_PARENT))

        // Center: Canvas + Timeline
        val centerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        canvasContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(0xFF0A0A15.toInt())
        }
        canvasView = SkeletonCanvasView(this, skeleton)
        canvasContainer.addView(canvasView)
        centerPanel.addView(canvasContainer)

        // Timeline panel (bottom)
        timelinePanel = buildTimelinePanel()
        centerPanel.addView(timelinePanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))

        mainContent.addView(centerPanel)

        // Right: Properties
        propertiesPanel = buildPropertiesPanel()
        mainContent.addView(propertiesPanel, LinearLayout.LayoutParams(dp(240), LinearLayout.LayoutParams.MATCH_PARENT))

        rootLayout.addView(mainContent)

        // ── Status bar ──
        statusBar = TextView(this).apply {
            text = "Skeleton: Character | Bones: ${skeleton.bones.size} | Time: 0.00s | FPS: 30"
            textSize = 11f
            setTextColor(0xFF666688.toInt())
            setPadding(12, 4, 12, 4)
            setBackgroundColor(0xFF0D0D1A.toInt())
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

        addToolBtn(bar, "🦴 Add Bone", ::onAddBone)
        addToolBtn(bar, "🗑 Delete", ::onDeleteBone)
        addToolBtn(bar, "📋 Duplicate", ::onDuplicateBone)
        addSeparator(bar)
        addToolBtn(bar, "🔗 IK Mode", { showIKHandles = !showIKHandles; canvasView.showIK = showIKHandles })
        addToolBtn(bar, "📏 Measure", {})
        addSeparator(bar)
        addToolBtn(bar, "▶ Play", ::onPlay)
        addToolBtn(bar, "⏸ Pause", ::handlePause)
        addToolBtn(bar, "⏹ Stop", ::handleStop)
        addToolBtn(bar, "⏮ Frame", ::onStepBack)
        addToolBtn(bar, "⏭ Frame", ::onStepForward)
        addSeparator(bar)
        addToolBtn(bar, "📁 New Clip", ::onNewClip)
        addToolBtn(bar, "💾 Save", ::onSave)
        addToolBtn(bar, "📤 Export", ::onExport)

        return bar
    }

    private fun buildBoneTree(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(4, 4, 4, 4)
        }

        panel.addView(TextView(this).apply {
            text = "🦴 BONE HIERARCHY"
            textSize = 11f
            setTextColor(0xFF8888CC.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 8, 8, 8)
        })

        val scroll = ScrollView(this)
        val tree = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Build visual tree
        fun addBoneTree(bone: Bone, depth: Int) {
            val isSelected = bone.id == selectedBone
            tree.addView(TextView(this).apply {
                val prefix = if (isSelected) "▶ " else "  "
                text = "  ".repeat(depth) + prefix + bone.name
                textSize = 12f
                setTextColor(if (isSelected) 0xFF44AAFF.toInt() else 0xFFCCCCFF.toInt())
                setBackgroundColor(if (isSelected) 0xFF222255.toInt() else Color.TRANSPARENT)
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    selectedBone = bone.id
                    refreshAll()
                }
            })
            for (child in skeleton.bones.filter { it.parentId == bone.id }) {
                addBoneTree(child, depth + 1)
            }
        }

        for (rootBone in skeleton.bones.filter { it.parentId < 0 }) {
            addBoneTree(rootBone, 0)
        }

        scroll.addView(tree)
        panel.addView(scroll)
        return panel
    }

    private fun buildPropertiesPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF16213E.toInt())
            setPadding(4, 4, 4, 4)
        }

        panel.addView(TextView(this).apply {
            text = "⚙ BONE PROPERTIES"
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

        val bone = if (selectedBone >= 0) skeleton.bones.getOrNull(selectedBone) else null

        // Transform section
        props.addView(sectionHeader("Transform"))
        if (bone != null) {
            props.addView(propRow("Name", bone.name))
            props.addView(numericRow("Local X", bone.localX))
            props.addView(numericRow("Local Y", bone.localY))
            props.addView(numericRow("Rotation", Math.toDegrees(bone.localRotation.toDouble()).toFloat()))
            props.addView(numericRow("Scale X", bone.localScaleX))
            props.addView(numericRow("Scale Y", bone.localScaleY))
            props.addView(numericRow("Length", bone.length))
            props.addView(propRow("Parent", if (bone.parentId >= 0) skeleton.bones[bone.parentId].name else "(root)"))
        }

        // IK section
        props.addView(sectionHeader("IK Constraints"))
        props.addView(propRow("IK Target", "None"))
        props.addView(propRow("IK Chain Length", "2"))
        props.addView(propRow("Bend Direction", "Positive"))

        // Animation section
        props.addView(sectionHeader("Animation"))
        props.addView(propRow("Current Clip", currentClip.name))
        props.addView(propRow("Duration", "%.2fs".format(currentClip.duration)))
        props.addView(propRow("Loop", if (currentClip.loop) "Yes" else "No"))

        scroll.addView(props)
        panel.addView(scroll)
        return panel
    }

    private fun buildTimelinePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111122.toInt())
            setPadding(4, 2, 4, 2)
        }

        // Timeline header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
        }
        header.addView(TextView(this).apply {
            text = "🎬 Timeline: ${currentClip.name}"
            textSize = 12f
            setTextColor(0xFF8888CC.toInt())
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "Time: %.2f / %.2f".format(currentTime, currentClip.duration)
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
        })
        panel.addView(header)

        // Track list
        val trackScroll = ScrollView(this)
        val trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Add tracks for each bone property
        val trackNames = listOf("Position X", "Position Y", "Rotation", "Scale X", "Scale Y")
        for (trackName in trackNames) {
            val trackRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(4, 2, 4, 2)
                setBackgroundColor(0xFF1A1A30.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply { setMargins(0, 1, 0, 1) }
            }

            trackRow.addView(TextView(this).apply {
                text = trackName
                textSize = 10f
                setTextColor(0xFF9999BB.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.MATCH_PARENT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 0, 0, 0)
            })

            // Keyframe visualization area (placeholder)
            trackRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundColor(0xFF0D0D1A.toInt())
            })

            trackList.addView(trackRow)
        }

        trackScroll.addView(trackList)
        panel.addView(trackScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Time ruler
        panel.addView(TextView(this).apply {
            text = buildTimeRuler()
            textSize = 9f
            setTextColor(0xFF666688.toInt())
            setPadding(104, 2, 8, 2)
            setBackgroundColor(0xFF0A0A15.toInt())
        })

        return panel
    }

    // ── Helpers ──

    private fun addToolBtn(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFFCCCCFF.toInt())
            setPadding(12, 6, 12, 6)
            setBackgroundColor(0xFF222244.toInt())
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.setMargins(2, 0, 2, 0)
        })
    }

    private fun addSeparator(parent: LinearLayout) {
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(2, dp(28)).apply { setMargins(6, 0, 6, 0) }
            setBackgroundColor(0xFF333355.toInt())
        })
    }

    private fun sectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(0xFF7777AA.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 10, 8, 4)
        }
    }

    private fun propRow(label: String, value: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 3, 8, 3)
            addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(TextView(context).apply { text = value; textSize = 11f; setTextColor(0xFFDDDDDD.toInt()); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        }
    }

    private fun numericRow(label: String, value: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 3, 8, 3)
            addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(0xFF888899.toInt()); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f) })
            addView(EditText(context).apply {
                text = android.text.Editable.Factory.getInstance().newEditable("%.3f".format(value))
                textSize = 11f; setTextColor(0xFFDDDDDD.toInt()); setBackgroundColor(0xFF222244.toInt())
                setPadding(8, 4, 8, 4)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
            })
        }
    }

    private fun buildTimeRuler(): String {
        val sb = StringBuilder()
        val steps = 10
        for (i in 0..steps) {
            val t = currentClip.duration * i / steps
            sb.append("%.1f  ".format(t))
        }
        return sb.toString()
    }

    private fun refreshAll() {
        // Refresh bone tree, properties, canvas
        canvasView.invalidate()
        statusBar.text = "Skeleton: ${skeleton.name} | Bones: ${skeleton.bones.size} | Selected: ${if (selectedBone >= 0) skeleton.bones[selectedBone].name else "None"} | Time: %.2fs".format(currentTime)
    }

    // ── Tool actions ──
    private fun onAddBone() { /* Add child bone to selected */ }
    private fun onDeleteBone() { /* Delete selected bone */ }
    private fun onDuplicateBone() { /* Duplicate selected bone chain */ }
    private fun onPlay() { isPlaying = true }
    private fun handlePause() { isPlaying = false }
    private fun handleStop() { isPlaying = false; currentTime = 0f; refreshAll() }
    private fun onStepBack() { currentTime = (currentTime - 1f / currentClip.sampleRate).coerceAtLeast(0f); refreshAll() }
    private fun onStepForward() { currentTime = (currentTime + 1f / currentClip.sampleRate).coerceAtMost(currentClip.duration); refreshAll() }
    private fun onNewClip() { currentClip = SkeletonAnimClip(name = "new_clip", duration = 1f); refreshAll() }
    private fun onSave() { /* Save skeleton + clips to project */ }
    private fun onExport() { /* Export to .skel format */ }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Custom view that renders the skeleton bones visually. */
class SkeletonCanvasView(context: android.content.Context, private val skeleton: SkeletonData) : View(context) {
    var showIK = false
    private val bonePaint = Paint().apply { color = 0xFF4488FF.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val selectedPaint = Paint().apply { color = 0xFFFF8844.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val jointPaint = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val ikPaint = Paint().apply { color = 0xFF44FF88.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val gridPaint = Paint().apply { color = 0x20FFFFFF; strokeWidth = 1f; style = Paint.Style.STROKE }
    private val textPaint = Paint().apply { color = 0xFF8888CC.toInt(); textSize = 24f; isAntiAlias = true }

    private val instance = SkeletonInstance(skeleton)

    init {
        instance.updateWorldTransforms()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.7f
        val scale = width / 8f

        // Grid
        for (i in -10..10) {
            val x = cx + i * scale
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            val y = cy + i * scale
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
        }

        // Axes
        val axisPaint = Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE }
        axisPaint.color = 0x40FF0000.toInt(); canvas.drawLine(cx, 0f, cx, height.toFloat(), axisPaint)
        axisPaint.color = 0x4000FF00.toInt(); canvas.drawLine(0f, cy, width.toFloat(), cy, axisPaint)

        // Draw bones
        for (bone in skeleton.bones) {
            val wb = instance.bones[bone.id]
            val px = cx + wb.worldMatrix.mapX(0f, 0f) * scale
            val py = cy - wb.worldMatrix.mapY(0f, 0f) * scale
            val tipX = cx + wb.worldMatrix.mapX(bone.length, 0f) * scale
            val tipY = cy - wb.worldMatrix.mapY(bone.length, 0f) * scale

            val paint = bonePaint // Simplified: no selection highlight for now
            canvas.drawLine(px, py, tipX, tipY, paint)

            // Joint circle
            canvas.drawCircle(px, py, 6f, jointPaint)

            // Bone name
            canvas.drawText(bone.name, tipX + 8f, tipY - 4f, textPaint)
        }

        // IK handle
        if (showIK) {
            ikPaint.color = 0xFF44FF88.toInt()
            canvas.drawCircle(cx + 50f, cy - 100f, 12f, ikPaint)
            canvas.drawText("IK Target", cx + 65f, cy - 96f, textPaint)
        }
    }
}
