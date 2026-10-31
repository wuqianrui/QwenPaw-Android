package com.qwenpaw.chat.widget

import kotlin.math.ceil
import kotlin.math.max

/**
 * 弹簧 —— 整套动画的"手感"来源（对应 math.js stepSpring）。
 *
 *   v += (-2 * damp * freq * v - freq^2 * (x - t)) * dt
 *   x += v * dt
 *
 * freq 决定速度，damp 决定阻尼：damp >= 1 不过冲，damp < 1 会回弹。
 * Web 侧参数：squash [10, 0.8]、eyeScale [9, 0.85]、blink [26, 1]。
 *
 * 注意：绝不能换成 ValueAnimator / SpringAnimation / Interpolator。
 * 那些的积分方式和阻尼曲线都不同，复刻不出原来的回弹。
 */
class Spring(x: Float = 0f) {
    var x: Float = x
    var v: Float = 0f
    var t: Float = x

    fun step(freq: Float, damp: Float, dt: Float) {
        v += (-2f * damp * freq * v - freq * freq * (x - t)) * dt
        x += v * dt
        // 数值兜底：极端 dt（卡顿后恢复）时直接吸附目标，避免 NaN 扩散
        if (!x.isFinite() || !v.isFinite()) {
            x = t
            v = 0f
        }
    }

    fun snap() {
        x = t
        v = 0f
    }
}

/** 固定子步长。dt 不同时切子步，保证 30fps 与 120fps 手感一致。 */
private const val SUB_DT = 1f / 120f

internal fun springSteps(dt: Float): Int = max(1, ceil(dt / SUB_DT).toInt())

/** 一组弹簧按同一步长推进（对应 character.js 里每帧统一积分的写法）。 */
internal fun stepSprings(steps: Int, dt: Float, vararg pairs: Pair<Spring, FloatArray>) {
    val h = dt / steps
    for (i in 0 until steps) {
        for ((s, p) in pairs) s.step(p[0], p[1], h)
    }
}
