package com.qwenpaw.chat.widget

import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D 旋转（对应 geometry-raw.js 的 rot3 / relRot）：
 * 把眼睛当作贴在旋转后身体球面上的元素。
 */
internal object Rot3 {

    /** 欧拉角（角度制）→ 3x3 行主序矩阵。 */
    fun rot(turn: Float, tilt: Float, roll: Float): FloatArray {
        val d = (PI_F / 180f)
        val ui = cos(turn * d); val si = sin(turn * d)
        val ea = cos(tilt * d); val ca = sin(tilt * d)
        val wo = cos(roll * d); val cc = sin(roll * d)
        return floatArrayOf(
            wo * ui - cc * ca * si, -cc * ea, wo * si + cc * ca * ui,
            cc * ui + wo * ca * si, wo * ea, cc * si - wo * ca * ui,
            -ea * si, ca, ea * ui
        )
    }

    /** 相对 home 姿态的旋转：pose * home^T。 */
    fun rel(pose: FloatArray, home: FloatArray): FloatArray {
        val g = pose; val n = home
        return floatArrayOf(
            g[0] * n[0] + g[1] * n[1] + g[2] * n[2],
            g[0] * n[3] + g[1] * n[4] + g[2] * n[5],
            g[0] * n[6] + g[1] * n[7] + g[2] * n[8],
            g[3] * n[0] + g[4] * n[1] + g[5] * n[2],
            g[3] * n[3] + g[4] * n[4] + g[5] * n[5],
            g[3] * n[6] + g[4] * n[7] + g[5] * n[8],
            g[6] * n[0] + g[7] * n[1] + g[8] * n[2],
            g[6] * n[3] + g[7] * n[4] + g[8] * n[5],
            g[6] * n[6] + g[7] * n[7] + g[8] * n[8]
        )
    }
}
