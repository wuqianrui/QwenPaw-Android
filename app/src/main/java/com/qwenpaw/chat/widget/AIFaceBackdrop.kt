package com.qwenpaw.chat.widget

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * AIFaceView 的**背景层（舞台）** —— 单独封装成一个 [Drawable]。
 *
 * 为什么要 Drawable 而不是子 View：
 *  - 背景天生属于「一层 paint」，塞成子 View 就得参与测量/布局，
 *    而 [AIFaceView] 的 onMeasure/onLayout 是按「只有 bot 一个子 View」写的
 *    （见其 onLayout 注释），多一个子 View 会牵动容器与角色的比例排布；
 *  - Drawable 直接走 `View.background` 通道，尺寸由宿主 bounds 决定，
 *    天然铺满、天然在最底层，也天然跟随 `setVisible` 的可见性生命周期。
 *
 * 两种模式（[MODES]）：
 *  - [MODE_SOLID]（默认）：纯色舞台，取 [color]，就是改动前的样子；
 *  - [MODE_AURORA]：极光流动渐变，动画内核复用 [AuroraGradient]
 *    （与 [AuroraGradientView] 同一套关键帧数学）。
 *
 * **两种模式都走同一套形状分支**（[SHAPES]，默认 [SHAPE_CIRCLE]），
 * 所以极光也是圆的：不是把矩形渐变 View 摆在底下再裁，而是**直接把渐变色
 * 画进 circle/rounded 的路径里** —— 用 `drawOval` + 带 shader 的 Paint，
 * 边缘自带抗锯齿（`clipPath` 在硬件加速下不做抗锯齿，圆边会有锯齿）。
 *
 * 用法（宿主一般是 [AIFaceView]，也可单独塞给任何 View 当 background）：
 * ```kotlin
 * val bg = AIFaceBackdrop()
 * bg.mode = AIFaceBackdrop.MODE_AURORA
 * bg.shape = AIFaceBackdrop.SHAPE_CIRCLE
 * view.background = bg
 * ```
 */
class AIFaceBackdrop : Drawable() {

    companion object {
        /** 纯色舞台（默认）：取 [color]。 */
        const val MODE_SOLID = "solid"

        /** 极光流动渐变：动画参数见 [aurora]。 */
        const val MODE_AURORA = "aurora"

        /** 全部合法模式，供 XML 属性注释与 lint 断言对齐。 */
        val MODES = arrayOf(MODE_SOLID, MODE_AURORA)

        /** 形状：正圆。 */
        const val SHAPE_CIRCLE = "circle"

        /** 形状：矩形（[cornerRadius] > 0 时为圆角矩形）。 */
        const val SHAPE_ROUNDED = "rounded"

        /** 形状：不画背景。 */
        const val SHAPE_NONE = "none"

        /** 全部合法形状。 */
        val SHAPES = arrayOf(SHAPE_CIRCLE, SHAPE_ROUNDED, SHAPE_NONE)

        /** 模式名是否合法。 */
        fun isMode(mode: String?): Boolean = mode == MODE_SOLID || mode == MODE_AURORA

        /** 形状名是否合法。 */
        fun isShape(shape: String?): Boolean =
            shape == SHAPE_CIRCLE || shape == SHAPE_ROUNDED || shape == SHAPE_NONE
    }

    /**
     * 背景模式，取值见 [MODES]。默认 [MODE_SOLID]。
     * 传入非法值时**忽略**（保持原值），不抛异常也不静默改成别的模式。
     */
    var mode: String = MODE_SOLID
        set(value) {
            if (!isMode(value) || field == value) return
            field = value
            updateAnimator()
            invalidateSelf()
        }

    /** 纯色模式的底色；[Color.TRANSPARENT] = 这一层不画（等于没有背景）。 */
    var color: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    /** 形状，取值见 [SHAPES]。默认 [SHAPE_CIRCLE]（正圆）。 */
    var shape: String = SHAPE_CIRCLE
        set(value) {
            if (!isShape(value) || field == value) return
            field = value
            invalidateSelf()
        }

    /**
     * 形状为 [SHAPE_ROUNDED] 时的圆角半径（px）。
     * 默认 0 = 直角矩形 —— 与改动前 `GradientDrawable.RECTANGLE` 未设圆角的行为一致。
     */
    var cornerRadius: Float = 0f
        set(value) {
            val v = value.coerceAtLeast(0f)
            if (field == v) return
            field = v
            invalidateSelf()
        }

    /** 极光模式的几何内核：配色 [AuroraGradient.colors]、角度、相位都在这里。 */
    val aurora = AuroraGradient()

    /** 极光一圈的时长（ms）。 */
    var loopMs: Long = AuroraGradient.DEFAULT_LOOP_MS
        set(value) {
            field = value.coerceAtLeast(1_000L)
            restartAnimator()
        }

    /** 当前是否按 [drawsSomething] 会画出内容。 */
    val drawsSomething: Boolean
        get() = when {
            shape == SHAPE_NONE -> false
            mode == MODE_AURORA -> true // 极光自己就是颜色，不看 color
            else -> color != Color.TRANSPARENT
        }

    /** 当前是否在跑极光动画（[MODE_SOLID] 下恒为 false）。 */
    val isRunning: Boolean get() = running && mode == MODE_AURORA

    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true // 渐变抗色带
    }
    private val rectF = RectF()

    private var running = false
    private var animator: ValueAnimator? = null

    // ------------------------------------------------------------------ 动画启停

    /**
     * 跑 / 停极光动画。
     *
     * 由宿主按可见性驱动 —— 离屏、退后台、被 GONE 时应停掉，
     * 否则 ValueAnimator 会一直每帧 `invalidateSelf()`，白白唤醒整棵 View 树。
     * [MODE_SOLID] 下无论传什么都保持停止（没有要动的东西）。
     */
    fun setRunning(running: Boolean) {
        if (this.running == running) return
        this.running = running
        updateAnimator()
    }

    private fun updateAnimator() {
        if (running && mode == MODE_AURORA) startAnimator() else stopAnimator()
    }

    private fun startAnimator() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = loopMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator() // 缓动在关键帧分段内自己做
                addUpdateListener { onTick(it.animatedValue as Float) }
            }
        }
        if (animator?.isStarted != true) animator?.start()
        else animator?.resume()
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    private fun restartAnimator() {
        stopAnimator()
        updateAnimator()
    }

    private fun onTick(f: Float) {
        aurora.tick(f)
        invalidateSelf()
    }

    // ------------------------------------------------------------------ Drawable 生命周期

    /** View 把它挂成 background / 移出窗口时会调到；顺带对齐动画启停。 */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        setRunning(visible)
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        aurora.setSize(bounds.width(), bounds.height())
    }

    override fun setAlpha(alpha: Int) {
        solidPaint.alpha = alpha
        auroraPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        solidPaint.colorFilter = colorFilter
        auroraPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    /**
     * 抽象方法，必须实现。本层是「可能半透明的形状」，所以报 TRANSLUCENT。
     * 加 `@Deprecated` 注解是为了消掉「覆写了已废弃成员」的编译警告
     * （`Drawable.getOpacity()` 自 API 35 起废弃，但没有默认实现可继承）。
     */
    @Deprecated("Drawable.getOpacity() 自 API 35 起废弃，此处仅为满足抽象方法")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // ------------------------------------------------------------------ 绘制

    override fun draw(canvas: Canvas) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0 || !drawsSomething) return

        val paint: Paint
        if (mode == MODE_AURORA) {
            val shader = aurora.shader() ?: return
            aurora.applyMatrix(shader)
            auroraPaint.shader = shader
            paint = auroraPaint
        } else {
            solidPaint.color = color
            paint = solidPaint
        }

        // 渐变 Shader 的坐标是相对宿主 View 原点的，先把画布挪到 bounds 左上角
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        rectF.set(0f, 0f, w.toFloat(), h.toFloat())
        // 形状分支对两种模式一视同仁：极光是「渐变色填进圆形路径」，
        // 不是「矩形渐变再裁圆」，所以圆边抗锯齿、也不会多一层离屏缓冲。
        when {
            shape == SHAPE_CIRCLE -> canvas.drawOval(rectF, paint)
            cornerRadius > 0f -> canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
            else -> canvas.drawRect(rectF, paint)
        }
        canvas.restoreToCount(save)
    }
}
