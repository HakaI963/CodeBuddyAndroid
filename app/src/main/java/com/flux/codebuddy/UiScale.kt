package com.flux.codebuddy

/**
 * Size of the web UI relative to the 1.0.2 layout (1.00x).
 *
 * The scale is applied by widening the layout viewport and zooming out, so the
 * web app reflows instead of being stretched. 1.00 keeps the old appearance;
 * 0.17 is about 5.9x smaller and is the minimum offered.
 */
object UiScale {

    val STEPS = floatArrayOf(1.0f, 0.8f, 0.6f, 0.45f, 0.3f, 0.22f, 0.17f)

    const val DEFAULT = 0.6f
    const val MIN = 0.17f
    const val MAX = 1.0f

    fun clamp(value: Float): Float = value.coerceIn(MIN, MAX)

    /** Nearest preset, so a stored value always maps to a slider position. */
    fun nearest(value: Float): Float {
        val target = clamp(value)
        return STEPS.minByOrNull { kotlin.math.abs(it - target) } ?: DEFAULT
    }

    fun indexOf(value: Float): Int = STEPS.indexOf(nearest(value))
}
