package com.min.assetripper.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.min.assetripper.R

/** Small Material 3 style view factory (colors from res/values[-night]/colors.xml). */
class Ui(private val ctx: Context) {

    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    fun c(res: Int): Int = ContextCompat.getColor(ctx, res)

    private fun shape(fill: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }

    /** Rounded background with a primary outline when focused or pressed (keyboard/TV navigation). */
    fun stateBg(fillRes: Int, radiusDp: Int): StateListDrawable = StateListDrawable().apply {
        val primary = c(R.color.md_primary)
        addState(intArrayOf(android.R.attr.state_focused), shape(c(fillRes), radiusDp, primary))
        addState(intArrayOf(android.R.attr.state_pressed), shape(c(fillRes), radiusDp, primary))
        addState(intArrayOf(), shape(c(fillRes), radiusDp))
    }

    fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    fun label(text: CharSequence, sp: Float, colorRes: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(c(colorRes))
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.SANS_SERIF
            }
        }

    /** Small uppercase section title in the primary color. */
    fun listTitle(text: String) = label(text.uppercase(), 13f, R.color.md_primary, bold = true).apply {
        letterSpacing = 0.02f
        setPadding(0, 0, 0, dp(8))
    }

    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(c(R.color.md_surface_container_high), 24)
        setPadding(dp(18), dp(16), dp(18), dp(18))
    }

    fun button(text: CharSequence, primary: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(c(if (primary) R.color.md_on_primary else R.color.md_on_secondary_container))
        background = stateBg(if (primary) R.color.md_primary else R.color.md_secondary_container, 20)
        minHeight = dp(48)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        isFocusable = true
        isClickable = true
        setOnClickListener { if (isEnabled) onClick() }
    }

    fun iconButton(iconRes: Int, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        setColorFilter(c(R.color.md_on_surface))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun numberField(value: String): EditText = EditText(ctx).apply {
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
        setTextColor(c(R.color.md_on_surface))
    }

    fun switchRow(text: CharSequence, checked: Boolean): MaterialSwitch = MaterialSwitch(ctx).apply {
        this.text = text
        isChecked = checked
        setTextColor(c(R.color.md_on_surface))
        setPadding(0, dp(8), 0, dp(8))
    }

    fun spacer(heightDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }
}
