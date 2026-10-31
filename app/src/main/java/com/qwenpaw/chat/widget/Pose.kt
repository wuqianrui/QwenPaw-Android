package com.qwenpaw.chat.widget

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** 跨帧命令总线。applyPose 只往这里塞意图，由编排层下一帧执行。 */
internal class Ctx {
    var nodUntil = 0f
    var nodEnd = 0f
    var angryShakeUntil = 0f
    var impulseAt = 0f
    var stAt = 0f
    var slumpAt = 0f
    var tyKick = 0f
    var spinKick = 0f
    var forceSleepEye = false
    var wantBlink = false
    var wakeEyeIdx = -1
}

/** 姿态输出：六条正交数值通道（spin/tx/ty/squash/lid/eyeBoost）。 */
internal class PoseOut {
    var spin = 0f
    var tx = 0f
    var ty = 0f
    var squash = 1f
    var lid = 1f
    var eyeBoost = 1f
}

/**
 * 姿态函数（对应 pose.js applyPose）：纯函数式的大 switch，
 * 对每个状态给出"这一帧想要什么"（目标值），逼近交给弹簧。
 *
 * 情绪的全部秘密：同一组通道，换基准角、换摆幅、换频率、换正负号。
 */
internal object Pose {

    /** 复用同一个输出对象：单线程帧循环里不需要每帧分配。 */
    val out = PoseOut()

    fun apply(state: String, mt: Float, dtState: Float, now: Float, ctx: Ctx): PoseOut {
        out.spin = 0f; out.tx = 0f; out.ty = 0f; out.squash = 1f; out.lid = 1f; out.eyeBoost = 1f
        when (state) {
            // 常态呼吸：所有通道都在动，但没有一帧是完全静止的
            "idle" -> {
                out.spin = sin(mt * 0.5f) * 1.5f + sin(mt * 0.17f) * 0.6f
                out.tx = sin(mt * 0.27f)
                out.ty = sin(mt * 0.85f) * 1.2f
                out.squash = 1f + sin(mt * 0.85f) * 0.007f
            }
            // 倾听：前倾 + 每 2~3 秒点一次头（半秒的 sin 包络）
            "listening" -> {
                out.spin = 8f + sin(mt * 0.5f) * 1.5f
                out.tx = 2f
                out.ty = -2f + sin(mt * 0.8f) * 0.8f
                out.squash = 1.015f
                if (now >= ctx.nodUntil) { ctx.nodUntil = now + rand(1800f, 3200f); ctx.nodEnd = now + 380f }
                if (now < ctx.nodEnd) {
                    val e = 1f - (ctx.nodEnd - now) / 380f
                    out.ty += sin(e * PI_F) * 4.5f
                    out.spin += sin(e * PI_F) * 2f
                }
            }
            // 搜索：高频大幅摆动，幅度是 idle 的 8 倍
            "searching" -> {
                val e = sin(mt * 1.3f)
                out.spin = e * 13f
                out.tx = e * 7f
                out.ty = sin(mt * 1.7f) * 3f
            }
            // 兴奋：1.1Hz 弹跳 + 负 ty（往上蹦），眼睛放大 6%
            "excited" -> {
                val e = (mt * 2.2f) % 1f
                out.ty = -sin(e * PI_F) * 10f + 2f
                out.squash = when { e < 0.1f -> 0.92f; e < 0.3f -> 1.05f; else -> 1f }
                out.tx = sin(mt * 1.1f) * 4f
                out.eyeBoost = 1.06f
                out.spin = sin(mt * PI_F * 2f * 1.1f) * 7f
            }
            // 惊讶：位移一次性回位 + 高频抖动衰减 + 眼睛瞪到 1.15 再落回
            "surprised" -> {
                val e = min(dtState / 1.2f, 1f)
                out.tx = -4f * (1f - e)
                out.ty = -8f * (1f - e)
                out.squash = if (dtState < 0.2f) 1.08f else 1f
                out.eyeBoost = 1.15f - e * 0.08f
                out.spin = sin(mt * 11f) * 1.5f * (1f - e)
            }
            // 愤怒：不是持续摇，而是每 1.8~3.2 秒踹一脚（给弹簧一个瞬时冲量）
            "angry" -> {
                if (now >= ctx.impulseAt) {
                    ctx.angryShakeUntil = now + 420f
                    ctx.tyKick = 70f
                    ctx.impulseAt = now + rand(1800f, 3200f)
                }
                out.spin = if (now < ctx.angryShakeUntil) sin(now * 0.05f) * 4.5f else 0f
                out.ty = 3.5f
                out.squash = 0.975f
            }
            // 困倦：三段式剧本，中段眼皮在 0.32~0.46 处突然塌到 0.05
            "drowsy" -> {
                out.spin = sin(mt * 0.32f) * 2.5f
                out.tx = sin(mt * 0.2f) * 1.5f
                out.ty = 6f + sin(mt * 0.36f) * 2.2f
                out.squash = 1f + sin(mt * 0.36f) * 0.022f
                out.lid = 0.34f + sin(mt * 0.8f) * 0.07f
                if (now >= ctx.nodUntil && ctx.slumpAt == 0f) ctx.slumpAt = now
                if (ctx.slumpAt > 0f) {
                    val e = (now - ctx.slumpAt) / 1000f
                    val z = 1.7f; val d = 0.3f; val o = 1.5f
                    when {
                        e < z -> {
                            val b = e / z; val c = b * b
                            out.ty = 6f + c * 19f + sin(b * PI_F * 2.5f) * 2.2f * (1f - b)
                            out.spin = c * 10f
                            out.lid = 0.34f - c * (0.34f - 0.04f)
                            out.squash = 1f - c * 0.045f
                        }
                        e < z + d -> {
                            val b = (e - z) / d; val c = sin(b * PI_F)
                            out.ty = 25f - c * 7f
                            out.spin = 10f - c * 4f
                            out.lid = 0.04f + c * 0.42f
                        }
                        e < z + d + o -> {
                            val b = (e - z - d) / o
                            val c = 1f - (1f - b).pow(2.2f)
                            out.ty = 25f - 19f * c
                            out.spin = 10f * (1f - c)
                            out.lid = 0.46f + (0.34f - 0.46f) * c
                            if (b > 0.32f && b < 0.46f) out.lid = 0.05f
                        }
                        else -> { ctx.slumpAt = 0f; ctx.nodUntil = now + rand(1500f, 3500f) }
                    }
                }
            }
            // 开心：2.4Hz 双向上浮，永远不往下
            "happy" -> {
                val e = sin(mt * 2.4f)
                out.spin = sin(mt * 1.2f) * 3f
                out.tx = sin(mt * 1.1f) * 2.5f
                out.ty = -abs(e) * 3f
                out.squash = 1f + e * 0.02f
                out.eyeBoost = 1.05f
            }
            // 好奇：歪 10° + 侧头，每 1.6~2.8s 快速偏一下（440ms 半正弦包络）
            "curious" -> {
                out.spin = 10f + sin(mt * 0.7f) * 6f
                out.tx = sin(mt * 0.6f) * 5f
                out.ty = -2f + sin(mt * 0.9f) * 1.5f
                out.squash = 1.01f
                out.eyeBoost = 1.08f
                if (now >= ctx.nodUntil) { ctx.nodUntil = now + rand(1600f, 2800f); ctx.nodEnd = now + 440f }
                if (now < ctx.nodEnd) {
                    val e = 1f - (ctx.nodEnd - now) / 440f
                    out.tx += sin(e * PI_F) * 8f
                    out.spin += sin(e * PI_F) * 5f
                }
            }
            // 难过：全部往下沉，眼睑压到 0.7，连挤压都塌了
            "sad" -> {
                out.spin = 3f + sin(mt * 0.3f) * 2f
                out.tx = sin(mt * 0.25f) * 1.5f
                out.ty = 7f + sin(mt * 0.4f)
                out.squash = 0.97f
                out.lid = 0.7f
                out.eyeBoost = 0.97f
            }
            // 害怕：用 now 而不是 mt，得到不规则的高频发抖；眼睑甚至超过 1
            "scared" -> {
                out.spin = sin(now * 0.04f) * 2f
                out.tx = -2f + sin(now * 0.05f) * 1.5f
                out.ty = 2f + sin(mt * 1.5f)
                out.squash = 0.97f
                out.eyeBoost = 1.12f
                out.lid = 1.05f
            }
            // 庆祝：只上不下，眼睛 1.1
            "celebrate" -> {
                out.ty = -abs(sin(mt * 1.6f)) * 2.5f
                out.eyeBoost = 1.1f
                out.lid = 1.1f
            }
        }
        return out
    }

    /** 视线目标 + 停留时长。interval 越短 = 越不安。 */
    fun nextGaze(state: String): FloatArray = when (state) {
        "idle" -> floatArrayOf(0f, 0f, 2500f, 5500f)
        "thinking" -> floatArrayOf(rand(0.5f, 1f) * randSign() * 15f, -rand(0.4f, 1f) * 9f, 1500f, 2800f)
        "searching" -> floatArrayOf(rand(0.7f, 1f) * randSign() * 15f, rand(-1f, 1f) * 9f, 550f, 1150f)
        "scared" -> floatArrayOf(rand(0.7f, 1f) * randSign() * 15f, rand(-0.6f, 0.6f) * 9f, 450f, 1050f)
        "bored" -> floatArrayOf(rand(0.7f, 1f) * randSign() * 15f, rand(0.4f, 0.9f) * 9f, 3000f, 6000f)
        "happy" -> floatArrayOf(rand(-0.7f, 0.7f) * 15f, -rand(0f, 0.6f) * 9f, 1800f, 3400f)
        else -> floatArrayOf(rand(-0.4f, 0.4f) * 15f, rand(-0.3f, 0.3f) * 9f, 2500f, 5000f)
    }
}
