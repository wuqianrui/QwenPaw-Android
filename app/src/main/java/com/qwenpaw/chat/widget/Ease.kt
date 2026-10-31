package com.qwenpaw.chat.widget

import kotlin.math.pow

/** 缓动（对应 math.js）。frameLerp 是与帧率无关的指数平滑。 */
internal object Ease {
    fun inOutCubic(n: Float) = if (n < 0.5f) 4f * n * n * n else 1f - (-2f * n + 2f).pow(3) / 2f

    fun outCubic(n: Float) = 1f - (1f - n).pow(3)

    fun outBack(n: Float) = 1f + 2.70158f * (n - 1f).pow(3) + 1.70158f * (n - 1f).pow(2)

    fun smooth(n: Float) = n * n * (3f - 2f * n)

    /** 对应源码 Rn(rate, dt)：60fps 下每帧逼近 rate，任意帧率手感一致。 */
    fun frameLerp(rate: Float, dt: Float) = 1f - (1f - rate).pow(60f * dt)
}
