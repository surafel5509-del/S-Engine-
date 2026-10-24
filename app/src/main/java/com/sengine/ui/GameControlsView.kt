package com.sengine.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.sengine.engine.Input
import kotlin.math.hypot
import kotlin.math.min

/** Virtual joystick/buttons, or a purpose-built gas/brake overlay for driving templates. */
class GameControlsView(
    context: Context,
    private val input: () -> Input?,
    private val driveMode: Boolean = false,
    private val tiltMode: Boolean = false,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF7FAFC.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3f, 0f, 1f, 0x99000000.toInt())
    }
    private var joyPointer = -1
    private var aPointer = -1
    private var bPointer = -1
    private var upgradePointer = -1
    private var knobX = 0f
    private var knobY = 0f

    private val baseR get() = min(width, height) * 0.13f
    private val joyCx get() = baseR * 1.6f
    private val joyCy get() = height - baseR * 1.6f
    private val btnR get() = baseR * 0.55f
    private val aCx get() = width - btnR * 2.0f
    private val aCy get() = height - btnR * 2.4f
    private val bCx get() = width - btnR * 4.4f
    private val bCy get() = height - btnR * 1.4f

    private val pedalR get() = (baseR * 0.78f).coerceAtLeast(dp(34f).toFloat())
    private val brakeCx get() = width * 0.16f
    private val gasCx get() = width * 0.84f
    private val pedalCy get() = height - pedalR * 1.12f
    private val tuneW get() = (baseR * 2.25f).coerceAtLeast(dp(108f).toFloat())
    private val tuneH get() = (baseR * 0.78f).coerceAtLeast(dp(42f).toFloat())
    private val tuneCx get() = width * 0.5f
    private val tuneCy get() = height - pedalR * 1.08f

    override fun onDraw(canvas: Canvas) {
        if (driveMode) drawDriveControls(canvas) else drawClassicControls(canvas)
    }

    private fun drawClassicControls(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0x33FFFFFF
        canvas.drawCircle(joyCx, joyCy, baseR, paint)
        paint.color = if (joyPointer >= 0) 0xAAFFFFFF.toInt() else 0x77FFFFFF
        val kx = if (joyPointer >= 0) knobX else joyCx
        val ky = if (joyPointer >= 0) knobY else joyCy
        canvas.drawCircle(kx, ky, baseR * 0.45f, paint)

        text.textSize = btnR * 0.8f
        paint.color = if (aPointer >= 0) 0xCC57AB5A.toInt() else 0x6657AB5A
        canvas.drawCircle(aCx, aCy, btnR, paint)
        canvas.drawText("A", aCx, aCy + text.textSize * 0.35f, text)
        paint.color = if (bPointer >= 0) 0xCCE5534B.toInt() else 0x66E5534B
        canvas.drawCircle(bCx, bCy, btnR, paint)
        canvas.drawText("B", bCx, bCy + text.textSize * 0.35f, text)
    }

    private fun drawDriveControls(canvas: Canvas) {
        drawPedal(canvas, brakeCx, pedalCy, pedalR, 0xFFE15B4F.toInt(), "BRAKE", "◀", bPointer >= 0)
        drawPedal(canvas, gasCx, pedalCy, pedalR, 0xFF39A875.toInt(), "GAS", "▶", aPointer >= 0)

        val left = tuneCx - tuneW * 0.5f
        val top = tuneCy - tuneH * 0.5f
        paint.style = Paint.Style.FILL
        paint.color = if (upgradePointer >= 0) 0xFFEFAD42.toInt() else 0xCC17283A.toInt()
        canvas.drawRoundRect(left, top, left + tuneW, top + tuneH, tuneH * 0.32f, tuneH * 0.32f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f).toFloat()
        paint.color = 0x99FFFFFF.toInt()
        canvas.drawRoundRect(left, top, left + tuneW, top + tuneH, tuneH * 0.32f, tuneH * 0.32f, paint)
        paint.style = Paint.Style.FILL
        text.textSize = (tuneH * 0.34f).coerceAtLeast(dp(13f).toFloat())
        canvas.drawText("TUNE  ↑", tuneCx, tuneCy + text.textSize * 0.36f, text)
        if (tiltMode) {
            text.textSize = dp(11f).toFloat()
            canvas.drawText("TILT TO BALANCE", tuneCx, top - dp(8f), text)
            val meterY = top - dp(19f)
            val half = dp(28f).toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f).toFloat()
            paint.color = 0x99FFFFFF.toInt()
            canvas.drawLine(tuneCx - half, meterY, tuneCx + half, meterY, paint)
            paint.style = Paint.Style.FILL
            val lean = (input()?.tiltX ?: 0f).coerceIn(-1f, 1f)
            paint.color = 0xFF78D8FF.toInt()
            canvas.drawCircle(tuneCx + lean * half, meterY, dp(3f).toFloat(), paint)
        }
    }

    private fun drawPedal(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, label: String, icon: String, pressed: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = 0x55000000
        canvas.drawCircle(cx, cy + dp(4f), radius * 1.08f, paint)
        paint.color = if (pressed) brighten(color) else (color and 0xBBFFFFFF.toInt())
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f).toFloat()
        paint.color = if (pressed) 0xF0FFFFFF.toInt() else 0x99FFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius * 0.9f, paint)
        paint.style = Paint.Style.FILL
        text.textSize = radius * 0.53f
        canvas.drawText(icon, cx, cy - radius * 0.03f + text.textSize * 0.35f, text)
        text.textSize = radius * 0.23f
        canvas.drawText(label, cx, cy + radius * 0.55f + text.textSize * 0.35f, text)
    }

    private fun brighten(color: Int): Int {
        fun channel(shift: Int) = (((color shr shift) and 0xFF) * 1.15f).toInt().coerceAtMost(255)
        return (color and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun hit(x: Float, y: Float): Int {
        if (driveMode) return when {
            hypot(x - brakeCx, y - pedalCy) < pedalR * 1.16f -> 2
            hypot(x - gasCx, y - pedalCy) < pedalR * 1.16f -> 3
            x in (tuneCx - tuneW * 0.62f)..(tuneCx + tuneW * 0.62f) &&
                y in (tuneCy - tuneH * 0.8f)..(tuneCy + tuneH * 0.8f) -> 4
            else -> 0
        }
        return when {
            hypot(x - joyCx, y - joyCy) < baseR * 1.7f -> 1
            hypot(x - aCx, y - aCy) < btnR * 1.3f -> 2
            hypot(x - bCx, y - bCy) < btnR * 1.3f -> 3
            else -> 0
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inp = input() ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                when (hit(e.getX(i), e.getY(i))) {
                    1 -> if (!driveMode && joyPointer < 0) { joyPointer = id; updateJoy(e.getX(i), e.getY(i), inp) }
                    2 -> if (driveMode && bPointer < 0) {
                        bPointer = id
                        inp.rawB = true
                    } else if (!driveMode && aPointer < 0) {
                        aPointer = id
                        inp.rawA = true
                    }
                    3 -> if (driveMode && aPointer < 0) {
                        aPointer = id
                        inp.rawA = true
                    } else if (!driveMode && bPointer < 0) {
                        bPointer = id
                        inp.rawB = true
                    }
                    4 -> {
                        if (upgradePointer < 0) {
                            upgradePointer = id
                            inp.rawUpgrade = true
                        }
                    }
                    else -> {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) return false
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (joyPointer >= 0) {
                    val i = e.findPointerIndex(joyPointer)
                    if (i >= 0) updateJoy(e.getX(i), e.getY(i), inp)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val all = e.actionMasked != MotionEvent.ACTION_POINTER_UP
                val id = e.getPointerId(e.actionIndex)
                if (all || id == joyPointer) { joyPointer = -1; inp.joyX = 0f; inp.joyY = 0f }
                if (all || id == aPointer) { aPointer = -1; inp.rawA = false }
                if (all || id == bPointer) { bPointer = -1; inp.rawB = false }
                if (all || id == upgradePointer) { upgradePointer = -1; inp.rawUpgrade = false }
            }
        }
        invalidate()
        return true
    }

    private fun updateJoy(x: Float, y: Float, inp: Input) {
        var dx = x - joyCx
        var dy = y - joyCy
        val d = hypot(dx, dy)
        if (d > baseR) { dx = dx / d * baseR; dy = dy / d * baseR }
        knobX = joyCx + dx; knobY = joyCy + dy
        inp.joyX = dx / baseR
        inp.joyY = -dy / baseR
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    fun reset() {
        joyPointer = -1; aPointer = -1; bPointer = -1; upgradePointer = -1
        input()?.let { it.joyX = 0f; it.joyY = 0f; it.rawA = false; it.rawB = false; it.rawUpgrade = false }
        invalidate()
    }
}
