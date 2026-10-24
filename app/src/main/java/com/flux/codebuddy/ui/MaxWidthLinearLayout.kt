package com.flux.codebuddy.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.flux.codebuddy.R

/**
 * A LinearLayout with a maximum measured width, used to keep the settings and
 * error cards readable on tablets without pulling in ConstraintLayout.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private var maxWidthPx: Int = 0

    init {
        val typed = context.obtainStyledAttributes(
            attrs,
            R.styleable.MaxWidthLinearLayout,
            defStyleAttr,
            0,
        )
        try {
            maxWidthPx = typed.getDimensionPixelSize(R.styleable.MaxWidthLinearLayout_maxWidthDp, 0)
        } finally {
            typed.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxWidthPx > 0) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            val mode = MeasureSpec.getMode(widthMeasureSpec)
            if (mode != MeasureSpec.UNSPECIFIED && size > maxWidthPx) {
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY),
                    heightMeasureSpec,
                )
                return
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
