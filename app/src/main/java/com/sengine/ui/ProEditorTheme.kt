package com.sengine.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Professional Editor Theme System
 *
 * Provides a consistent, modern, professional look for all editor activities.
 * Dark theme inspired by Unity, Unreal, and Blender — with subtle accent colors,
 * proper typography, spacing, and visual hierarchy.
 */
object ProEditorTheme {

    // ── Color Palette ──
    val bgDarkest = 0xFF0A0A14.toInt()
    val bgDark = 0xFF12121F.toInt()
    val bgMedium = 0xFF1A1A2E.toInt()
    val bgLight = 0xFF222244.toInt()
    val bgHighlight = 0xFF2A2A55.toInt()
    val bgSelected = 0xFF333377.toInt()

    val textPrimary = 0xFFE0E0FF.toInt()
    val textSecondary = 0xFF9999BB.toInt()
    val textMuted = 0xFF666688.toInt()
    val textAccent = 0xFF4488FF.toInt()
    val textSuccess = 0xFF44CC88.toInt()
    val textWarning = 0xFFFFAA44.toInt()
    val textError = 0xFFFF4466.toInt()

    val borderDark = 0xFF2A2A44.toInt()
    val borderMedium = 0xFF333355.toInt()
    val borderLight = 0xFF444466.toInt()

    val accentPrimary = 0xFF4488FF.toInt()
    val accentSecondary = 0xFF8844FF.toInt()
    val accentSuccess = 0xFF44CC88.toInt()
    val accentWarning = 0xFFFFAA44.toInt()
    val accentDanger = 0xFFFF4466.toInt()
    val accentInfo = 0xFF44CCFF.toInt()

    // Component-specific colors
    val hierarchyBg = 0xFF141428.toInt()
    val inspectorBg = 0xFF141428.toInt()
    val consoleBg = 0xFF0C0C18.toInt()
    val viewportBg = 0xFF0A0A14.toInt()
    val timelineBg = 0xFF0E0E1C.toInt()
    val toolbarBg = 0xFF16213E.toInt()
    val statusBarBg = 0xFF08081A.toInt()

    // ── Typography ──
    val titleSize = 16f
    val headerSize = 13f
    val bodySize = 12f
    val smallSize = 11f
    val tinySize = 10f

    // ── Spacing ──
    val padSmall = 4
    val padMedium = 8
    val padLarge = 12
    val padXLarge = 16

    // ── Factory Methods ──

    fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()

    fun sp(ctx: Context, value: Float): Float = value * ctx.resources.displayMetrics.scaledDensity

    /** Create a styled section header. */
    fun sectionHeader(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = headerSize
            setTextColor(textAccent)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(ctx, padLarge), dp(ctx, padMedium), dp(ctx, padMedium), dp(ctx, padSmall))
        }
    }

    /** Create a styled toolbar button. */
    fun toolButton(ctx: Context, text: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = bodySize
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, padLarge), dp(ctx, padSmall + 2), dp(ctx, padLarge), dp(ctx, padSmall + 2))
            background = GradientDrawable().apply {
                setColor(bgLight)
                cornerRadius = dp(ctx, 4).toFloat()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(ctx, 2), 0, dp(ctx, 2), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    /** Create a styled property row with label and value. */
    fun propertyRow(ctx: Context, label: String, value: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, padLarge), dp(ctx, padSmall), dp(ctx, padMedium), dp(ctx, padSmall))
            addView(TextView(ctx).apply {
                this.text = label
                textSize = bodySize
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                this.text = value
                textSize = bodySize
                setTextColor(textPrimary)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    /** Create a styled panel with header and scroll content. */
    fun panel(ctx: Context, title: String, contentBuilder: (LinearLayout) -> Unit): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMedium)
            setPadding(dp(ctx, padSmall), 0, dp(ctx, padSmall), 0)
            addView(sectionHeader(ctx, title))
            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, padSmall), 0, dp(ctx, padSmall), dp(ctx, padSmall))
            }
            contentBuilder(content)
            addView(content)
        }
    }

    /** Create a styled separator line. */
    fun separator(ctx: Context): View {
        return View(ctx).apply {
            setBackgroundColor(borderDark)
            layoutParams = when (parent) {
                is LinearLayout -> {
                    val ll = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
                    ll.setMargins(dp(ctx, padMedium), dp(ctx, padSmall), dp(ctx, padMedium), dp(ctx, padSmall))
                    ll
                }
                else -> FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
            }
        }
    }

    /** Create a colored badge/tag. */
    fun badge(ctx: Context, text: String, color: Int): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = tinySize
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, padMedium), dp(ctx, 2), dp(ctx, padMedium), dp(ctx, 2))
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(ctx, 10).toFloat()
            }
        }
    }

    /** Create a styled card (rounded background with subtle border). */
    /* CARD FUNCTION TEMPORARILY DISABLED
    fun card(ctx: Context, content: View): FrameLayout {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(bgDark)
        drawable.cornerRadius = dp(ctx, 6).toFloat()
                return FrameLayout(ctx).apply {
            background = drawable
            setPadding(dp(ctx, padMedium))
            addView(content)
        }
    END OF CARD FUNCTION */
    /** Style an existing ScrollView as a panel. */
    fun styleScrollView(sv: ScrollView, bgColor: Int = bgMedium) {
        sv.setBackgroundColor(bgColor)
    }

    /** Create the status bar text view. */
    fun statusBar(ctx: Context, text: String): TextView {
        return TextView(ctx).apply {
            this.text = text
            textSize = tinySize
            setTextColor(textMuted)
            setPadding(dp(ctx, padLarge), dp(ctx, padSmall), dp(ctx, padLarge), dp(ctx, padSmall))
            setBackgroundColor(statusBarBg)
        }
    }

    /** Get the color for a component category. */
    fun categoryColor(category: String): Int = when (category) {
        "Rendering 2D" -> 0xFF4488FF.toInt()
        "Rendering 3D" -> 0xFF8844FF.toInt()
        "UI System" -> 0xFF44CC88.toInt()
        "Skeleton Animation" -> 0xFFFFAA44.toInt()
        "Physics 2D" -> 0xFF44CCFF.toInt()
        "Physics 3D" -> 0xFF4488CC.toInt()
        "Scripting & Audio" -> 0xFFFF6688.toInt()
        "Textures & Materials" -> 0xFFCC88FF.toInt()
        else -> 0xFF888888.toInt()
    }
}
