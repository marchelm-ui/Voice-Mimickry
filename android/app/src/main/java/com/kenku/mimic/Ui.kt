package com.kenku.mimic

import android.content.Context
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.HapticFeedbackConstants
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Small helpers so every screen shares the same look: colors, sizes, buttons, cards. */
class Ui(val ctx: Context) {
    fun c(id: Int) = ContextCompat.getColor(ctx, id)
    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun rounded(fill: Int, stroke: Int? = null, radius: Int = 12) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    /** Wraps a background so it shows the standard touch ripple when tapped. */
    fun ripple(content: Drawable?, radius: Int = 12): RippleDrawable {
        val mask = rounded(0xFFFFFFFF.toInt(), null, radius)
        return RippleDrawable(ColorStateList.valueOf(c(R.color.ripple)), content, mask)
    }

    /** A light tap feel, used on the main actions. */
    fun tick(v: View) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    enum class Kind { PRIMARY, NORMAL, DANGER, QUIET }

    fun button(text: String, kind: Kind = Kind.NORMAL, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        minHeight = dp(48); minimumHeight = dp(48)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        stateListAnimator = null
        when (kind) {
            Kind.PRIMARY -> { setTextColor(c(R.color.accent_ink)); background = ripple(rounded(c(R.color.accent))) }
            Kind.DANGER -> { setTextColor(c(R.color.danger)); background = ripple(rounded(c(R.color.surface), c(R.color.danger))) }
            Kind.QUIET -> { setTextColor(c(R.color.accent)); background = ripple(rounded(c(R.color.bg), null)) }
            Kind.NORMAL -> { setTextColor(c(R.color.ink)); background = ripple(rounded(c(R.color.surface), c(R.color.line))) }
        }
        setOnClickListener { onClick() }
    }

    fun title(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 28f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setTextColor(c(R.color.ink))
    }

    fun heading(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setTextColor(c(R.color.ink))
    }

    fun body(text: String, size: Float = 14f, color: Int = R.color.muted) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(c(color))
    }

    fun card(vertical: Boolean = true, highlight: Boolean = false) = LinearLayout(ctx).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        background = rounded(c(R.color.surface), if (highlight) c(R.color.brass) else c(R.color.line))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    }

    fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun wrap(rightMargin: Int = 8) = LinearLayout.LayoutParams(-2, -2).apply { this.rightMargin = dp(rightMargin) }
    fun fill(bottomMargin: Int = 10) = LinearLayout.LayoutParams(-1, -2).apply { this.bottomMargin = dp(bottomMargin) }
    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, -2, w)

    fun spacer(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
}
