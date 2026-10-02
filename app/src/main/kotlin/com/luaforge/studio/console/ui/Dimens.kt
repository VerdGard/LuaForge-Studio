package com.luaforge.studio.console.ui

import android.content.Context
import android.util.TypedValue

/** dp 转 px。 */
fun Context.dp(v: Int): Int =
    (resources.displayMetrics.density * v).toInt()

/** dp 转 px(float)。 */
fun Context.dpf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
