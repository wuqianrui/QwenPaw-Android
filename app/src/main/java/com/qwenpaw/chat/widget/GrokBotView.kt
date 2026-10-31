package com.qwenpaw.chat.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.qwenpaw.chat.R
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grok Bot 角色本体 —— 纯角色 View（不含容器 / 背景）。
 *
 * **通常不直接使用**，请用上层组件 [AIFaceView]（自带圆形舞台容器）。
 * 只有需要把角色塞进自定义容器时才单独用它。
 *
 * 职责：
 *  - 后台线程加载 assets/grok_geometry.json，完成后回调 [onBotReady]
 *  - Choreographer 驱动帧循环（onDraw 里 tick + draw，每帧一次）
 *  - 触摸时眼睛跟随指针（mapPointer 椭圆映射），松手回落
 *  - 情绪状态机：手动 [setBotState] / onboarding 自动巡演，
 *    切换时回调 [onStateChanged]（手动与自动两个来源都会通知）
 *  - 主题色：11 套调色板 × 明暗两案 + 135° 墨色渐变 + 完全自定义
 *
 * XML 属性（前缀 grok*，见 attrs.xml）：
 *  grokPalette / grokScheme / grokFgColor / grokInkColor / grokBgColor
 */
class GrokBotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * 眼睛画笔。**初始色必须在这里给**：
     * [bgColor] 是带自定义 setter 的属性，而 Kotlin 的属性初始化器
     * 直接写 backing field、**不经过 setter**，所以 `bgPaint.color` 不会
     * 跟着 `bgColor` 的初值自动同步。漏掉这行就会一直沿用 Paint 默认的
     * 黑色 —— 黑色眼睛画在黑色身体上，看起来就是"没有眼睛"。
     */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Theme.EYE_BG
    }
    private val choreographer = Choreographer.getInstance()

    private var character: GrokCharacter? = null
    private var running = false
    private var pointer: PointF? = null

    /** 几何数据后台加载完成后的回调（主线程）。 */
    var onBotReady: (() -> Unit)? = null

    /** 当前情绪状态名。 */
    var botState = "idle"
        private set

    /** 当前调色板 id（对应源码 INK 表）。 */
    var palette: String = "black"
        private set

    /** 当前明暗方案："light" | "dark"。 */
    var scheme: String = "light"
        private set

    /** 主题变化回调。 */
    var onThemeChanged: ((fg: Int, ink: Int, bg: Int) -> Unit)? = null

    /**
     * 情绪状态切换回调 (from, to)。
     *
     * 两个来源都会触发：手动 [setBotState]，以及 onboarding 自动巡演
     * （约每 [Tables.ONBOARDING_MS] ms 在 "idle ↔ 情绪" 之间轮换一次）。
     * 主线程；自动轮换是在 `onDraw → tick` 里切换的，所以回调也可能
     * 发生在绘制过程中 —— 回调里改状态 / 改主题是安全的（下一帧生效），
     * 但别做 `requestLayout()` 这类会引发重新布局的操作。
     */
    var onStateChanged: ((from: String, to: String) -> Unit)? = null

    private var customFg: Int? = null
    private var customInk: Int? = null
    private var fgIsGradient = false
    private var pendingInk = 0

    /** 几何未就绪时挂起的初始状态。 */
    private var pendingState: String? = null

    /**
     * 是否让眼睛跟随手指。关闭（默认 false）时触摸穿透给父容器，
     * 容器上的点击 / 长按才能正常工作。
     */
    var followPointer: Boolean = false

    /**
     * 是否 onboarding 自动巡演。
     *
     * 赋值即切换角色模式，**两个方向都要处理**：
     * true → "onboarding"，false → "hold"。
     *
     * 曾经只处理 false（`if (!value) mode = "hold"`），于是
     * 「先 setBotState 把模式打成 hold，再 autoOnboarding = true」
     * 这种恢复顺序完全失效 —— 巡演永远起不来。XML 同时写了
     * `aiFaceInitialState` 与 `aiFaceAutoOnboarding="true"` 时就会踩到。
     */
    var autoOnboarding: Boolean = true
        set(value) {
            field = value
            character?.mode = if (value) "onboarding" else "hold"
        }

    /** 本体主色。赋值即覆盖调色板，走"自定义单色"路径。 */
    var fgColor: Int = Color.BLACK
        set(value) {
            field = value
            customFg = value
            applyFgPaint()
        }

    /** 眼睛颜色（画在身体上的镂空色，默认米白 [Theme.EYE_BG]）。 */
    var bgColor: Int = Theme.EYE_BG
        set(value) {
            field = value
            bgPaint.color = value
        }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(nanos: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            invalidate()
        }
    }

    /**
     * 几何加载作用域。View 没有 lifecycleScope，这里用独立作用域承载这次
     * 一次性加载；**故意不随 [onDetachedFromWindow] 取消** —— 与原先
     * `Thread + post` 的语义一致：离开窗口时若还没加载完，结果仍然会应用，
     * 重新 attach 后照常显示。
     *
     * **必须声明在 init 之前**：init 里会调 [startLoading]，而 Kotlin 属性
     * 按声明顺序初始化，写在 init 之后就还是 null，`launch` 读 receiver 的
     * coroutineContext 会直接 NPE（不是"协程没跑"，是崩在构造过程里）。
     */
    private val loadScope = CoroutineScope(Dispatchers.Main.immediate)

    init {
        attrs?.let {
            // grokPalette / grokScheme 在 attrs.xml 里是 format="string"，
            // 所以 getString 拿到的就是名字（"violet" / "dark"）。
            // 别把它们改成 enum —— enum 值编译期折算成 int，这里会拿到 "8"
            // 这种序号串，resolveTheme() 查不到表就静默退回黑色。
            val a = context.obtainStyledAttributes(it, R.styleable.GrokBotView)
            a.getString(R.styleable.GrokBotView_grokPalette)?.let { p -> palette = p }
            a.getString(R.styleable.GrokBotView_grokScheme)?.let { s -> scheme = s }
            autoOnboarding = a.getBoolean(R.styleable.GrokBotView_grokAutoOnboarding, true)
            pendingState = a.getString(R.styleable.GrokBotView_grokInitialState)
            if (a.hasValue(R.styleable.GrokBotView_grokFgColor)) {
                customFg = a.getColor(R.styleable.GrokBotView_grokFgColor, 0)
            }
            if (a.hasValue(R.styleable.GrokBotView_grokInkColor)) {
                customInk = a.getColor(R.styleable.GrokBotView_grokInkColor, 0)
            }
            if (a.hasValue(R.styleable.GrokBotView_grokBgColor)) {
                bgColor = a.getColor(R.styleable.GrokBotView_grokBgColor, bgColor)
            }
            a.recycle()
        }
        resolveTheme()
        startLoading()
    }

    private fun startLoading() {
        // 78KB JSON + 25 眼 × 48 点 + 18 身形轮廓，放 IO 线程一次性解析；
        // withContext 返回后续在 Main 上执行，等价于原来的 post { }
        loadScope.launch {
            val geo = withContext(Dispatchers.IO) { GrokGeo.load(context) }
            character = GrokCharacter(geo).also { c ->
                c.mode = if (autoOnboarding) "onboarding" else "hold"
                // 状态变化统一在这里落地。历史问题：botState 只有 setBotState
                // 会改，onboarding 自动轮换时它是过期的（一直停在旧值），
                // 这个钩子同时负责把它同步成真实状态。
                c.onStateChanged = { from, to ->
                    botState = to
                    onStateChanged?.invoke(from, to)
                }
            }
            // 初始状态要等角色就绪后再应用。注意 setBotState 会顺手关掉巡演
            // （它本身就是"锁定到某个情绪"的语义），所以先把原本的意图记下来，
            // 应用完再恢复 —— 否则 XML 同时写了 aiFaceInitialState 与
            // aiFaceAutoOnboarding="true" 时，巡演永远起不来（真机实测过）。
            val resumeOnboarding = autoOnboarding
            pendingState?.let { s -> setBotState(s); pendingState = null }
            if (resumeOnboarding) autoOnboarding = true
            onBotReady?.invoke()
        }
    }

    /**
     * 切换调色板（11 套：black/brown/red/orange/yellow/green/cyan/
     * blue/violet/magenta/gray）。[sch] 传 "dark" 取暗色方案。
     */
    fun setPalette(id: String, sch: String? = null) {
        if (!Theme.INK_FG.containsKey(id)) return
        palette = id
        sch?.let { scheme = it }
        customFg = null
        customInk = null
        resolveTheme()
    }

    /** 恢复默认主题（黑色）。 */
    fun resetTheme() = setPalette("black", "light")

    /**
     * 完全自定义：直接给本体色与墨色渐变终点。
     * [ink] 传 null 时按主色自动推导（亮方案提亮 / 暗方案加深）。
     */
    fun setCustomTheme(fg: Int, ink: Int? = null, bg: Int? = null) {
        customInk = ink
        fgColor = fg
        bg?.let { bgColor = it }
        resolveTheme()
    }

    /** 按当前 palette / scheme / 自定义色算最终画笔。 */
    private fun resolveTheme() {
        val pair = Theme.INK_FG[palette] ?: Theme.INK_FG.getValue("black")
        val autoFg = if (scheme == "dark") pair.second else pair.first
        val fg = customFg ?: autoFg
        fgColor = fg

        val ink = customInk ?: Theme.inkToOf(fg, scheme)
        fgIsGradient = true
        pendingInk = ink
        applyFgPaint()

        // 颜色统一同步点：bgColor 可能被 attrs / setCustomTheme 改过，
        // 这里保证 bgPaint 与它一致（init 末尾也会走一次）。
        bgPaint.color = bgColor
        onThemeChanged?.invoke(fg, ink, bgColor)
    }

    /** 建 135° 线性渐变（源码 INK_ANGLE=135）。尺寸未定前退化为纯色。 */
    private fun applyFgPaint() {
        val fg = fgColor
        val w = width.toFloat()
        val h = height.toFloat()
        if (!fgIsGradient || w <= 0f || h <= 0f) {
            fgPaint.shader = null
            fgPaint.color = fg
            return
        }
        fgPaint.shader = LinearGradient(0f, 0f, w, h, fg, pendingInk, Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFgPaint()
    }

    /** 锁定到指定情绪（退出 onboarding 自动巡演）。状态名无效时忽略。 */
    fun setBotState(name: String) {
        // 先校验：GrokCharacter.setState 对无效名是静默 return 的，
        // 若在这里无脑赋值，botState / currentState 会报出一个并不存在的状态
        if (!Tables.isState(name)) return
        botState = name
        autoOnboarding = false
        val c = character
        if (c == null) { pendingState = name; return }
        c.mode = "hold"
        c.setState(name, resetEyes = false)
    }

    /** 可用情绪状态名（24 个，对应源码 EYE_PLAYLIST）。 */
    val states: Set<String> get() = Tables.EYE_PLAYLIST.keys

    /** 当前实际状态名（几何未就绪时返回挂起值）。 */
    val currentState: String get() = character?.state ?: pendingState ?: botState

    /** 恢复 onboarding 自动巡演。 */
    fun resetToOnboarding() {
        pendingState = null
        autoOnboarding = true
        character?.mode = "onboarding"
        // 不要硬写 "idle"：轮换要等下一个 ONBOARDING_MS 周期才生效，
        // 这期间 character.state 还是上一个情绪，写死会让 botState 与实际不符。
        botState = character?.state ?: "idle"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val c = character ?: return
        val nowMs = SystemClock.uptimeMillis().toFloat()
        val px = min(width, height).toFloat()
        c.pointer = pointer
        c.tick(nowMs, px)
        c.draw(canvas, width.toFloat(), height.toFloat(), fgPaint, bgPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 只有开启跟随指针时才消费触摸，否则让容器拿到点击 / 长按
        if (!followPointer) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                pointer = PointF(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointer = null
        }
        return true
    }

    /** 调色板常量（对应 replica/src/tables.js 的 INK / EYE_BG / INK_ANGLE）。 */
    internal object Theme {
        /** [light, dark] 两套，取自源码 palette。 */
        val INK_FG: Map<String, Pair<Int, Int>> = mapOf(
            "black" to (0xFF000000.toInt() to 0xFFFFFFFF.toInt()),
            "brown" to (0xFFA27952.toInt() to 0xFF855C36.toInt()),
            "red" to (0xFFFF3E51.toInt() to 0xFFE02135.toInt()),
            "orange" to (0xFFFF781C.toInt() to 0xFFFF6700.toInt()),
            "yellow" to (0xFFFFAF38.toInt() to 0xFFFF9800.toInt()),
            "green" to (0xFF00C972.toInt() to 0xFF009957.toInt()),
            "cyan" to (0xFF1CC3B0.toInt() to 0xFF00A592.toInt()),
            "blue" to (0xFF2A92FE.toInt() to 0xFF0E74E0.toInt()),
            "violet" to (0xFFA97EFE.toInt() to 0xFF804EE0.toInt()),
            "magenta" to (0xFFFF5EB1.toInt() to 0xFFE02A88.toInt()),
            "gray" to (0xFF959595.toInt() to 0xFF777777.toInt()),
        )

        /** 源码 INK 的 lightFrom/lightTo 系列（135° 渐变两端）。 */
        val INK_GRADIENT: Map<String, Pair<Int, Int>> = mapOf(
            "black" to (0xFF000000.toInt() to 0xFF585858.toInt()),
            "brown" to (0xFF855C36.toInt() to 0xFFAE8968.toInt()),
            "red" to (0xFFE02135.toInt() to 0xFFFF5667.toInt()),
            "orange" to (0xFFE05B00.toInt() to 0xFFFF8838.toInt()),
            "yellow" to (0xFFE08600.toInt() to 0xFFFFAF38.toInt()),
            "green" to (0xFF009957.toInt() to 0xFF1CCF82.toInt()),
            "cyan" to (0xFF00A592.toInt() to 0xFF58D3C5.toInt()),
            "blue" to (0xFF0E74E0.toInt() to 0xFF459FFE.toInt()),
            "violet" to (0xFF804EE0.toInt() to 0xFFB792FE.toInt()),
            "magenta" to (0xFFE02A88.toInt() to 0xFFFF77BE.toInt()),
            "gray" to (0xFF696969.toInt() to 0xFFA6A6A6.toInt()),
        )

        val EYE_BG = 0xFFF3EFE6.toInt()

        /** 自定义色 → 渐变终点：亮方案往白提亮，暗方案往黑加深。 */
        fun inkToOf(fg: Int, scheme: String): Int =
            if (scheme == "dark") darken(fg, 0.25f) else lighten(fg, 0.35f)

        fun lighten(c: Int, k: Float) = Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * k).toInt(),
            (Color.green(c) + (255 - Color.green(c)) * k).toInt(),
            (Color.blue(c) + (255 - Color.blue(c)) * k).toInt()
        )

        fun darken(c: Int, k: Float) = Color.rgb(
            (Color.red(c) * (1 - k)).toInt(),
            (Color.green(c) * (1 - k)).toInt(),
            (Color.blue(c) * (1 - k)).toInt()
        )
    }
}
