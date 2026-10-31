package com.qwenpaw.chat.widget

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 极光流动渐变的**几何内核** —— 与 View / Drawable 无关，只管算，画由宿主负责。
 *
 * 复刻网页版 gradient-palettes.html 里「B · 极光冷调 (AURORA)」的背景动画：
 *
 * ```
 * background: linear-gradient(120deg, #5EE7DF 0%, #66A6FF 25%, #7F7FD5 50%, #86FDE8 75%, #5EE7DF 100%);
 * background-size: 400% 400%;
 * animation: flow 13s ease-in-out infinite;
 * ```
 *
 * 原理（与 CSS 完全同构）：
 *  - 把 5 个色阶（首尾同色 = 闭环）铺在一条「4 倍视口」长的渐变线上；
 *  - 可见窗口只占其中 1/4，于是同一时刻画面里只有 2~3 个颜色，且随时间在变；
 *  - 窗口的滑动路径照抄 CSS 的 background-position 关键帧
 *    `(0%,50%) → (55%,10%) → (100%,50%) → (45%,90%) → (0%,50%)`，
 *    背景位移投影到 120° 渐变轴上，得到相位 phase ∈ [0, 3]（单位：tile）；
 *  - 每一段之间用 CSS 默认 ease（cubic-bezier(.25,.1,.25,1)）缓动，13s 一圈、无限循环。
 *
 * 宿主三步用法：
 * ```kotlin
 * core.setSize(w, h)                       // 尺寸变化时调一次（顺带把相位摆到关键帧 0）
 * core.tick(fraction)                      // fraction ∈ [0, 1]，由 ValueAnimator 驱动
 * val sh = core.shader()                   // 取（必要时重建）Shader
 * core.applyMatrix(sh); paint.shader = sh  // 把底图平移到当前相位
 * canvas.drawOval(rect, paint)             // 想圆形就画 Oval —— 不必 clipPath，
 *                                          // Oval 自带抗锯齿，着色器按位置取色，结果一样
 * ```
 *
 * 为什么单独成类：[AuroraGradientView] 和 [AIFaceBackdrop] 都要这套关键帧数学，
 * 各写一份迟早漂移（改一处关键帧忘了另一处，两处动画就对不上了）。
 */
class AuroraGradient {

    companion object {
        /**
         * B · 极光冷调：青 → 蓝 → 蓝紫 → 青，首尾同色闭环。
         * 共享常量数组，**只读使用，不要修改内容**；要换配色请给 [colors] 赋值。
         */
//        val AURORA_COLORS: IntArray = intArrayOf(
//            0xFF5EE7DF.toInt(),
//            0xFF66A6FF.toInt(),
//            0xFF7F7FD5.toInt(),
//            0xFF86FDE8.toInt(),
//            0xFF5EE7DF.toInt()
//        )

        val AURORA_COLORS: IntArray = intArrayOf(
            0xFF84FAB0.toInt(),
            0xFF5EE7DF.toInt(),
            0xFF66A6FF.toInt(),
            0xFF7F7FD5.toInt(),
            0xFF86FDE8.toInt(),
            0xFF5EE7DF.toInt()
        )

        /** CSS: `linear-gradient(120deg, ...)`。约定 0° = 向上，顺时针。 */
        const val DEFAULT_ANGLE_DEG = 120f

        /** 一圈时长（ms），对应 CSS animation 的 duration。 */
        const val DEFAULT_LOOP_MS = 6_000L

        /** 渐变底图长度 = 4 个 tile，对应 `background-size: 400%`。 */
        const val TILES = 4

        /** background-position 关键帧（与 CSS 的 `@keyframes flow` 一一对应）。 */
        private val KEY_PX = floatArrayOf(0f, 0.55f, 1f, 0.45f, 0f)
        private val KEY_PY = floatArrayOf(0.5f, 0.10f, 0.5f, 0.90f, 0.5f)
    }

    /** 渐变色阶。想闭环：最后一色必须等于第一色。 */
    var colors: IntArray = AURORA_COLORS.copyOf()
        set(value) {
            field = value.copyOf()
            stops = evenStops(field.size)
            shaderDirty = true
        }

    /** 渐变角度（CSS 约定：0° 向上、90° 向右、顺时针）。 */
    var angleDeg: Float = DEFAULT_ANGLE_DEG
        set(value) {
            field = value
            if (viewW > 0 && viewH > 0) recomputeAxis()
            shaderDirty = true
        }

    /** 各色阶位置，均匀分布（CSS 里写 0/25/50/75/100，等价）。 */
    private var stops: FloatArray = evenStops(AURORA_COLORS.size)

    private var viewW = 0
    private var viewH = 0

    /** 渐变轴单位向量（x 向右、y 向下）。 */
    private var axisX = 0f
    private var axisY = 0f

    /** 一个 tile 在渐变轴上的投影长度。 */
    private var tileLen = 1f

    /** 当前相位（单位：tile）。可见窗口在 `[phase, phase+1] * tileLen` 上滑动。 */
    private var phase = 0f

    private var shaderDirty = true
    private var shader: LinearGradient? = null

    private val matrix = Matrix()

    /** CSS 默认 ease = `cubic-bezier(0.25, 0.1, 0.25, 1)`。 */
    private val ease = PathInterpolator(0.25f, 0.10f, 0.25f, 1f)

    /** 当前相位（单位 tile），范围约 `[0, 3]`。 */
    val currentPhase: Float get() = phase

    // ------------------------------------------------------------------ 几何

    /**
     * 尺寸变化时调用：重算渐变轴，并把相位摆到关键帧 0，避免首帧闪色。
     */
    fun setSize(w: Int, h: Int) {
        viewW = w
        viewH = h
        recomputeAxis()
        phase = phaseAt(KEY_PX[0], KEY_PY[0])
        shaderDirty = true
    }

    private fun recomputeAxis() {
        val rad = Math.toRadians(angleDeg.toDouble())
        axisX = sin(rad).toFloat()
        axisY = -cos(rad).toFloat() // View 的 y 轴向下，CSS 的 y 轴向上
        tileLen = viewW * axisX + viewH * axisY
        if (tileLen <= 0f) tileLen = (viewW + viewH).coerceAtLeast(1).toFloat()
    }

    /**
     * 推进动画。
     *
     * @param fraction ∈ `[0, 1]` 的一圈进度：先定位到四段关键帧中的哪一段，
     *                 再做段内 CSS ease 缓动，最后线性插值出相位。
     */
    fun tick(fraction: Float) {
        val segments = KEY_PX.size - 1
        val seg = (fraction * segments).toInt().coerceIn(0, segments - 1)
        val local = fraction * segments - seg
        val eased = ease.getInterpolation(local)
        val p0 = phaseAt(KEY_PX[seg], KEY_PY[seg])
        val p1 = phaseAt(KEY_PX[seg + 1], KEY_PY[seg + 1])
        phase = p0 + (p1 - p0) * eased
    }

    /**
     * 取（必要时重建）渐变 Shader；尺寸还没确定（宽或高为 0）时返回 null。
     */
    fun shader(): LinearGradient? {
        if (viewW <= 0 || viewH <= 0) return null
        if (shaderDirty || shader == null) {
            val len = tileLen * TILES
            shader = LinearGradient(
                0f, 0f,
                len * axisX, len * axisY,
                colors, stops,
                Shader.TileMode.CLAMP
            )
            shaderDirty = false
        }
        return shader
    }

    /**
     * 把 4 tile 长的渐变底图沿渐变轴反过来平移，让可见窗口滑到当前相位。
     * 传入 [shader] 返回的那个 Shader 即可。
     */
    fun applyMatrix(target: Shader) {
        matrix.setTranslate(-phase * tileLen * axisX, -phase * tileLen * axisY)
        target.setLocalMatrix(matrix)
    }

    /**
     * CSS `background-position(px%, py%)` → 渐变轴上的窗口起点（单位：tile）。
     *
     * background-size 400% 时背景盒比视口大 3 倍，位移投影到渐变轴即为窗口滑动量；
     * 首尾关键帧相同 → 循环回起点无接缝。
     */
    private fun phaseAt(px: Float, py: Float): Float {
        val travel = (TILES - 1) * (px * viewW * axisX + py * viewH * axisY)
        return travel / tileLen
    }

    private fun evenStops(n: Int): FloatArray =
        FloatArray(n) { if (n == 1) 0f else it.toFloat() / (n - 1) }
}
