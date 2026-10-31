package com.qwenpaw.chat.widget

import kotlin.math.max
import kotlin.math.min

/**
 * 基础数学工具（对应 replica/src/math.js 顶部的散函数）。
 *
 * 包内共享，所以不能声明为 private —— private 顶层声明是文件级可见。
 */
internal const val TWO_PI = 6.2831855f
internal const val PI_F = 3.1415927f

internal fun Float.clamp(a: Float, b: Float) = min(b, max(a, this))

internal fun rand(a: Float, b: Float) = a + Math.random().toFloat() * (b - a)

/** 从 [min, max] 区间表（EYE_HOLD_MS / BLINK_MS 的 FloatArray）里取随机值。 */
internal fun rand(range: FloatArray) = rand(range[0], range[1])

internal fun randSign() = if (Math.random() < 0.5) -1f else 1f
