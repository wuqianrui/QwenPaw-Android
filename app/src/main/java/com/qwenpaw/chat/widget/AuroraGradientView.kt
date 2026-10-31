package com.qwenpaw.chat.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 循环流动渐变背景 View —— 复刻网页版 gradient-palettes.html 里
 * 「B · 极光冷调 (AURORA)」的背景动画。
 *
 * 关键帧数学、配色、相位计算都在 [AuroraGradient] 里（与 [AIFaceBackdrop] 共用），
 * 本类只负责「驱动 + 画」：起一个 13s 无限循环的 ValueAnimator，
 * 每帧把相位交给 [AuroraGradient] 算出矩阵，再贴着 View 铺满。
 *
 * 用法：
 * ```xml
 * <com.qwenpaw.chat.widget.AuroraGradientView
 *     android:layout_width="300dp"
 *     android:layout_height="300dp" />
 * ```
 * ```kotlin
 * view.cornerRadius = 16f * resources.displayMetrics.density  // 对应 border-radius
 * view.loopMs = 13_000L
 * view.colors = intArrayOf(...)   // 换配色，首尾同色即可闭环
 * ```
 *
 * 注意：PathInterpolator 需要 API 21+。
 * 本 View 零第三方依赖，可整体拷贝到任何工程。
 */
class AuroraGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** B · 极光冷调：青 → 蓝 → 蓝紫 → 青，首尾同色闭环。转发自 [AuroraGradient]。 */
        val AURORA_COLORS: IntArray get() = AuroraGradient.AURORA_COLORS
    }

    /** 几何内核：相位、色阶、渐变轴、Shader 都在它里面。 */
    private val core = AuroraGradient()

    /** 渐变色阶。想闭环：最后一色必须等于第一色。 */
    var colors: IntArray
        get() = core.colors
        set(value) {
            core.colors = value
            invalidate()
        }

    /** 渐变角度（CSS 约定：0° 向上、90° 向右、顺时针）。 */
    var angleDeg: Float
        get() = core.angleDeg
        set(value) {
            core.angleDeg = value
            invalidate()
        }

    /** 一圈时长（毫秒），对应 animation 的 duration。 */
    var loopMs: Long = AuroraGradient.DEFAULT_LOOP_MS
        set(value) {
            field = value.coerceAtLeast(1_000L)
            restartAnimator()
        }

    /** 圆角半径（px），对应 CSS 的 border-radius；0 = 直角。 */
    var cornerRadius: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    /** 当前相位（单位：tile），范围约 `[0, 3]`。调试/测试用。 */
    val phase: Float get() = core.currentPhase

    private val paint = Paint().apply {
        isAntiAlias = true
        isDither = true // 渐变抗色带
    }
    private val clipRect = RectF()
    private val clipPath = Path()

    private var animator: ValueAnimator? = null

    // ------------------------------------------------------------------ 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimator()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnimator() else animator?.pause()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        core.setSize(w, h)
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val shader = core.shader() ?: return

        if (cornerRadius > 0f) {
            clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
        }

        // 把 4 tile 长的渐变底图沿轴平移，让可见窗口滑到当前相位
        core.applyMatrix(shader)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    // ------------------------------------------------------------------ 动画

    private fun startAnimator() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = loopMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator() // 缓动在分段内自己做
                addUpdateListener { onTick(it.animatedValue as Float) }
            }
        }
        if (animator?.isStarted != true) animator?.start()
        else animator?.resume()
    }

    private fun restartAnimator() {
        animator?.cancel()
        animator = null
        if (isAttachedToWindow) startAnimator()
    }

    private fun onTick(f: Float) {
        core.tick(f)
        invalidate()
    }
}
