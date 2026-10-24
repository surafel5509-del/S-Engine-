package com.sengine.engine.ui

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import org.json.JSONArray
import org.json.JSONObject

// ─── Canvas & Layout ───────────────────────────────────────────────────────

/** Root UI canvas — defines the reference resolution and scaling mode. */
class UICanvas : Component() {
    override val type = "UICanvas"
    var refWidth = 1080
    var refHeight = 1920
    var scaleMode = 0 // 0=ScaleToFit, 1=ScaleToFill, 2=Stretch, 3=ConstantPixelSize
    var sortOrder = 0
    var backgroundColor = 0x00000000

    override fun props() = listOf(
        Prop.I("Reference Width", { refWidth }, { refWidth = it.coerceIn(100, 7680) }),
        Prop.I("Reference Height", { refHeight }, { refHeight = it.coerceIn(100, 7680) }),
        Prop.Choice("Scale Mode", listOf("Scale To Fit", "Scale To Fill", "Stretch", "Constant Pixel Size"), { scaleMode }, { scaleMode = it }),
        Prop.I("Sort Order", { sortOrder }, { sortOrder = it }),
        Prop.Color("Background", { backgroundColor }, { backgroundColor = it }),
    )
}

/** Rect transform – position, size and anchoring for UI elements. */
class RectTransform : Component() {
    override val type = "RectTransform"
    var anchorMinX = 0.5f; var anchorMinY = 0.5f
    var anchorMaxX = 0.5f; var anchorMaxY = 0.5f
    var pivotX = 0.5f; var pivotY = 0.5f
    var anchoredX = 0f; var anchoredY = 0f
    var width = 200f; var height = 100f
    var offsetX = 0f; var offsetY = 0f
    var offsetW = 0f; var offsetH = 0f

    override fun props() = listOf(
        Prop.F("Anchor Min X", { anchorMinX }, { anchorMinX = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Anchor Min Y", { anchorMinY }, { anchorMinY = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Anchor Max X", { anchorMaxX }, { anchorMaxX = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Anchor Max Y", { anchorMaxY }, { anchorMaxY = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Pivot X", { pivotX }, { pivotX = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Pivot Y", { pivotY }, { pivotY = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Anchored X", { anchoredX }, { anchoredX = it }, 1f),
        Prop.F("Anchored Y", { anchoredY }, { anchoredY = it }, 1f),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0f) }, 1f),
    )

    /** Compute world rect given parent rect and canvas size. */
    fun computeRect(parentW: Float, parentH: Float, parentX: Float, parentY: Float): FloatArray {
        val pMinX = parentX - parentW * 0.5f
        val pMinY = parentY - parentH * 0.5f
        val ax0 = pMinX + anchorMinX * parentW
        val ay0 = pMinY + anchorMinY * parentH
        val ax1 = pMinX + anchorMaxX * parentW
        val ay1 = pMinY + anchorMaxY * parentH
        val cx = (ax0 + ax1) * 0.5f + anchoredX + offsetX
        val cy = (ay0 + ay1) * 0.5f + anchoredY + offsetY
        val w = (ax1 - ax0) + width + offsetW
        val h = (ay1 - ay0) + height + offsetH
        return floatArrayOf(cx - w * pivotX, cy - h * pivotY, w, h)
    }
}

// ─── Visual Elements ───────────────────────────────────────────────────────

class UIImage : Component() {
    override val type = "UIImage"
    var image = ""
    var color = 0xFFFFFFFF.toInt()
    var imageType = 0 // 0=Sliced, 1=Filled, 2=Tiled
    var fillAmount = 1f
    var fillClockwise = true
    var fillOrigin = 0 // 0=Bottom, 1=Right, 2=Top, 3=Left
    var raycastTarget = true

    override fun props() = listOf(
        Prop.Asset("Sprite", com.sengine.engine.core.AssetKind.TEXTURE, { image }, { image = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Choice("Type", listOf("Sliced", "Filled", "Tiled"), { imageType }, { imageType = it }),
        Prop.F("Fill Amount", { fillAmount }, { fillAmount = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.B("Fill Clockwise", { fillClockwise }, { fillClockwise = it }),
        Prop.Choice("Fill Origin", listOf("Bottom", "Right", "Top", "Left"), { fillOrigin }, { fillOrigin = it }),
        Prop.B("Raycast Target", { raycastTarget }, { raycastTarget = it }),
    )
}

class UIText : Component() {
    override val type = "UIText"
    var text = "Label"
    var fontSize = 32
    var color = 0xFFFFFFFF.toInt()
    var align = 1 // 0=Left, 1=Center, 2=Right
    var bold = false
    var italic = false
    var overflow = 0 // 0=Overflow, 1=Ellipsis, 2=Clamp
    var lineHeight = 1.2f
    var shadow = false
    var shadowColor = 0x80000000.toInt()
    var shadowOffsetX = 2f
    var shadowOffsetY = -2f
    var outline = false
    var outlineColor = 0xFF000000.toInt()
    var outlineWidth = 2f
    var raycastTarget = true

    override fun props() = listOf(
        Prop.S("Text", { text }, { text = it }, multiline = true),
        Prop.I("Font Size", { fontSize }, { fontSize = it.coerceIn(4, 512) }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Choice("Alignment", listOf("Left", "Center", "Right"), { align }, { align = it }),
        Prop.B("Bold", { bold }, { bold = it }),
        Prop.B("Italic", { italic }, { italic = it }),
        Prop.Choice("Overflow", listOf("Overflow", "Ellipsis", "Clamp"), { overflow }, { overflow = it }),
        Prop.F("Line Height", { lineHeight }, { lineHeight = it.coerceIn(0.5f, 3f) }, 0.1f),
        Prop.B("Shadow", { shadow }, { shadow = it }),
        Prop.Color("Shadow Color", { shadowColor }, { shadowColor = it }),
        Prop.F("Shadow Offset X", { shadowOffsetX }, { shadowOffsetX = it }, 1f),
        Prop.F("Shadow Offset Y", { shadowOffsetY }, { shadowOffsetY = it }, 1f),
        Prop.B("Outline", { outline }, { outline = it }),
        Prop.Color("Outline Color", { outlineColor }, { outlineColor = it }),
        Prop.F("Outline Width", { outlineWidth }, { outlineWidth = it.coerceIn(0f, 16f) }, 0.5f),
        Prop.B("Raycast Target", { raycastTarget }, { raycastTarget = it }),
    )
}

class UIButton : Component() {
    override val type = "UIButton"
    var normalColor = 0xFFFFFFFF.toInt()
    var highlightColor = 0xFFCCCCCC.toInt()
    var pressedColor = 0xFF999999.toInt()
    var disabledColor = 0xFF555555.toInt()
    var transition = 0 // 0=ColorTint, 1=SpriteSwap, 2=Animation
    var interactable = true
    var navigation = 0 // 0=None, 1=Horizontal, 2=Vertical, 3=Automatic
    var onClickScript = ""
    var onClickParams = ""

    override fun props() = listOf(
        Prop.Color("Normal Color", { normalColor }, { normalColor = it }),
        Prop.Color("Highlight Color", { highlightColor }, { highlightColor = it }),
        Prop.Color("Pressed Color", { pressedColor }, { pressedColor = it }),
        Prop.Color("Disabled Color", { disabledColor }, { disabledColor = it }),
        Prop.Choice("Transition", listOf("Color Tint", "Sprite Swap", "Animation"), { transition }, { transition = it }),
        Prop.B("Interactable", { interactable }, { interactable = it }),
        Prop.Choice("Navigation", listOf("None", "Horizontal", "Vertical", "Automatic"), { navigation }, { navigation = it }),
        Prop.Asset("On Click Script", com.sengine.engine.core.AssetKind.SCRIPT, { onClickScript }, { onClickScript = it }),
        Prop.S("On Click Params", { onClickParams }, { onClickParams = it }),
    )
}

class UISlider : Component() {
    override val type = "UISlider"
    var minValue = 0f
    var maxValue = 1f
    var value = 0.5f
    var wholeNumbers = false
    var direction = 0 // 0=LeftToRight, 1=RightToLeft, 2=BottomToTop, 3=TopToBottom
    var bgColor = 0xFF3A3A3A.toInt()
    var fillColor = 0xFF4488FF.toInt()
    var handleColor = 0xFFFFFFFF.toInt()
    var handleSize = 40f
    var onValueChangedScript = ""

    override fun props() = listOf(
        Prop.F("Min Value", { minValue }, { minValue = it }, 0.1f),
        Prop.F("Max Value", { maxValue }, { maxValue = it }, 0.1f),
        Prop.F("Value", { value }, { value = it }, 0.01f),
        Prop.B("Whole Numbers", { wholeNumbers }, { wholeNumbers = it }),
        Prop.Choice("Direction", listOf("Left→Right", "Right→Left", "Bottom→Top", "Top→Bottom"), { direction }, { direction = it }),
        Prop.Color("Background", { bgColor }, { bgColor = it }),
        Prop.Color("Fill", { fillColor }, { fillColor = it }),
        Prop.Color("Handle", { handleColor }, { handleColor = it }),
        Prop.F("Handle Size", { handleSize }, { handleSize = it.coerceAtLeast(8f) }, 1f),
    )
}

class UIScrollRect : Component() {
    override val type = "UIScrollRect"
    var horizontal = true
    var vertical = true
    var elasticity = 0.1f
    var decelerationRate = 0.135f
    var scrollSensitivity = 1f
    var contentName = ""
    var bgColor = 0x00000000
    var maskContent = true
    var showScrollbar = true
    var scrollbarWidth = 8f

    override fun props() = listOf(
        Prop.B("Horizontal", { horizontal }, { horizontal = it }),
        Prop.B("Vertical", { vertical }, { vertical = it }),
        Prop.F("Elasticity", { elasticity }, { elasticity = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Deceleration Rate", { decelerationRate }, { decelerationRate = it.coerceIn(0f, 1f) }, 0.01f),
        Prop.F("Scroll Sensitivity", { scrollSensitivity }, { scrollSensitivity = it.coerceAtLeast(0.01f) }, 0.1f),
        Prop.Color("Background", { bgColor }, { bgColor = it }),
        Prop.B("Mask Content", { maskContent }, { maskContent = it }),
        Prop.B("Show Scrollbar", { showScrollbar }, { showScrollbar = it }),
        Prop.F("Scrollbar Width", { scrollbarWidth }, { scrollbarWidth = it.coerceAtLeast(2f) }, 1f),
    )
}

class UIPanel : Component() {
    override val type = "UIPanel"
    var bgColor = 0xFF222222.toInt()
    var borderColor = 0xFF444444.toInt()
    var borderWidth = 0f
    var cornerRadius = 0f
    var shadow = false
    var shadowColor = 0x40000000
    var shadowBlur = 8f

    override fun props() = listOf(
        Prop.Color("Background", { bgColor }, { bgColor = it }),
        Prop.Color("Border", { borderColor }, { borderColor = it }),
        Prop.F("Border Width", { borderWidth }, { borderWidth = it.coerceAtLeast(0f) }, 0.5f),
        Prop.F("Corner Radius", { cornerRadius }, { cornerRadius = it.coerceAtLeast(0f) }, 1f),
        Prop.B("Shadow", { shadow }, { shadow = it }),
        Prop.Color("Shadow Color", { shadowColor }, { shadowColor = it }),
        Prop.F("Shadow Blur", { shadowBlur }, { shadowBlur = it.coerceAtLeast(0f) }, 1f),
    )
}

class UIProgressBar : Component() {
    override val type = "UIProgressBar"
    var value = 0f
    var minValue = 0f
    var maxValue = 1f
    var direction = 0 // 0=LeftToRight, 1=RightToLeft, 2=BottomToTop, 3=TopToBottom
    var bgColor = 0xFF3A3A3A.toInt()
    var fillColor = 0xFF44BB44.toInt()
    var fillTexture = ""
    var showLabel = false
    var labelColor = 0xFFFFFFFF.toInt()

    override fun props() = listOf(
        Prop.F("Value", { value }, { value = it }, 0.01f),
        Prop.F("Min", { minValue }, { minValue = it }, 0.1f),
        Prop.F("Max", { maxValue }, { maxValue = it }, 0.1f),
        Prop.Choice("Direction", listOf("Left→Right", "Right→Left", "Bottom→Top", "Top→Bottom"), { direction }, { direction = it }),
        Prop.Color("Background", { bgColor }, { bgColor = it }),
        Prop.Color("Fill", { fillColor }, { fillColor = it }),
        Prop.Asset("Fill Texture", com.sengine.engine.core.AssetKind.TEXTURE, { fillTexture }, { fillTexture = it }),
        Prop.B("Show Label", { showLabel }, { showLabel = it }),
        Prop.Color("Label Color", { labelColor }, { labelColor = it }),
    )

    val normalized: Float get() = if (maxValue <= minValue) 0f else ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
}

class UIToggle : Component() {
    override val type = "UIToggle"
    var isOn = true
    var onColor = 0xFF4488FF.toInt()
    var offColor = 0xFF3A3A3A.toInt()
    var checkColor = 0xFFFFFFFF.toInt()
    var checkSize = 24f
    var onToggleScript = ""

    override fun props() = listOf(
        Prop.B("Is On", { isOn }, { isOn = it }),
        Prop.Color("On Color", { onColor }, { onColor = it }),
        Prop.Color("Off Color", { offColor }, { offColor = it }),
        Prop.Color("Check Color", { checkColor }, { checkColor = it }),
        Prop.F("Check Size", { checkSize }, { checkSize = it.coerceAtLeast(4f) }, 1f),
    )
}

class UIDropdown : Component() {
    override val type = "UIDropdown"
    var options = mutableListOf("Option A", "Option B", "Option C")
    var selectedIndex = 0
    var bgColor = 0xFF2A2A2A.toInt()
    var textColor = 0xFFFFFFFF.toInt()
    var highlightColor = 0xFF4488FF.toInt()
    var fontSize = 28

    override fun props() = listOf(
        Prop.I("Selected Index", { selectedIndex }, { selectedIndex = it.coerceIn(0, options.size - 1) }),
        Prop.Color("Background", { bgColor }, { bgColor = it }),
        Prop.Color("Text", { textColor }, { textColor = it }),
        Prop.Color("Highlight", { highlightColor }, { highlightColor = it }),
        Prop.I("Font Size", { fontSize }, { fontSize = it.coerceIn(8, 128) }),
    )

    val selectedOption: String get() = options.getOrElse(selectedIndex) { "" }
}

class UIInputField : Component() {
    override val type = "UIInputField"
    var text = ""
    var placeholder = "Enter text..."
    var isPassword = false
    var maxLength = 256
    var fontSize = 28
    var textColor = 0xFFFFFFFF.toInt()
    var placeholderColor = 0xFF888888.toInt()
    var bgColor = 0xFF2A2A2A.toInt()
    var borderColor = 0xFF555555.toInt()
    var keyboardType = 0 // 0=Default, 1=NumberPad, 2=Email, 3=Phone

    override fun props() = listOf(
        Prop.S("Text", { text }, { text = it }),
        Prop.S("Placeholder", { placeholder }, { placeholder = it }),
        Prop.B("Password", { isPassword }, { isPassword = it }),
        Prop.I("Max Length", { maxLength }, { maxLength = it.coerceAtLeast(1) }),
        Prop.I("Font Size", { fontSize }, { fontSize = it.coerceIn(8, 128) }),
        Prop.Color("Text Color", { textColor }, { textColor = it }),
        Prop.Color("Placeholder Color", { placeholderColor }, { placeholderColor = it }),
        Prop.Color("Background", { bgColor }, { bgColor = it }),
        Prop.Color("Border", { borderColor }, { borderColor = it }),
        Prop.Choice("Keyboard", listOf("Default", "Number Pad", "Email", "Phone"), { keyboardType }, { keyboardType = it }),
    )
}

// ─── Layout Groups ─────────────────────────────────────────────────────────

class UILayoutGroup : Component() {
    override val type = "UILayoutGroup"
    var direction = 0 // 0=Horizontal, 1=Vertical, 2=Grid
    var spacing = 8f
    var paddingL = 8f; var paddingR = 8f; var paddingT = 8f; var paddingB = 8f
    var childForceWidth = false
    var childForceHeight = false
    var childControlWidth = true
    var childControlHeight = true
    var gridColumns = 3
    var gridCellWidth = 100f
    var gridCellHeight = 100f
    var childAlignment = 0 // 0=TopLeft, 1=TopCenter, 2=TopRight, 3=MiddleLeft, 4=MiddleCenter, 5=MiddleRight, 6=BottomLeft, 7=BottomCenter, 8=BottomRight

    override fun props() = listOf(
        Prop.Choice("Direction", listOf("Horizontal", "Vertical", "Grid"), { direction }, { direction = it }),
        Prop.F("Spacing", { spacing }, { spacing = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Padding Left", { paddingL }, { paddingL = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Padding Right", { paddingR }, { paddingR = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Padding Top", { paddingT }, { paddingT = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Padding Bottom", { paddingB }, { paddingB = it.coerceAtLeast(0f) }, 1f),
        Prop.B("Force Child Width", { childForceWidth }, { childForceWidth = it }),
        Prop.B("Force Child Height", { childForceHeight }, { childForceHeight = it }),
        Prop.B("Control Child Width", { childControlWidth }, { childControlWidth = it }),
        Prop.B("Control Child Height", { childControlHeight }, { childControlHeight = it }),
        Prop.I("Grid Columns", { gridColumns }, { gridColumns = it.coerceAtLeast(1) }),
        Prop.F("Grid Cell Width", { gridCellWidth }, { gridCellWidth = it.coerceAtLeast(1f) }, 1f),
        Prop.F("Grid Cell Height", { gridCellHeight }, { gridCellHeight = it.coerceAtLeast(1f) }, 1f),
    )
}

// ─── UI Event System ───────────────────────────────────────────────────────

/** Runtime state for UI elements during play mode. */
data class UIElementState(
    val id: Long,
    var hovered: Boolean = false,
    var pressed: Boolean = false,
    var focused: Boolean = false,
    var scrollX: Float = 0f,
    var scrollY: Float = 0f,
    var dragVelocityX: Float = 0f,
    var dragVelocityY: Float = 0f,
)

/** Resolves UI touch interactions and dispatches events. */
object UIEventSystem {
    val states = HashMap<Long, UIElementState>()

    fun getState(id: Long): UIElementState = states.getOrPut(id) { UIElementState(id) }

    fun clearStates() { states.clear() }

    /** Test if a point (px, py) hits the element rect (rx, ry, rw, rh). */
    fun hitTest(px: Float, py: Float, rx: Float, ry: Float, rw: Float, rh: Float): Boolean =
        px >= rx && px <= rx + rw && py >= ry && py <= ry + rh

    /** Update slider value from touch position. */
    fun updateSlider(slider: UISlider, rect: FloatArray, touchX: Float, touchY: Float) {
        val (rx, ry, rw, rh) = floatArrayOf(rect[0], rect[1], rect[2], rect[3])
        val norm = when (slider.direction) {
            0 -> ((touchX - rx) / rw).coerceIn(0f, 1f)
            1 -> (1f - (touchX - rx) / rw).coerceIn(0f, 1f)
            2 -> ((touchY - ry) / rh).coerceIn(0f, 1f)
            3 -> (1f - (touchY - ry) / rh).coerceIn(0f, 1f)
            else -> 0f
        }
        val raw = slider.minValue + norm * (slider.maxValue - slider.minValue)
        slider.value = if (slider.wholeNumbers) Math.round(raw).toFloat() else raw
    }
}
