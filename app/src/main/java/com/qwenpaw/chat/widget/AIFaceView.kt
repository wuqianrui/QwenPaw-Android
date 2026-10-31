package com.qwenpaw.chat.widget

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.qwenpaw.chat.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AI 脸组件 —— **自包含的 FrameLayout**：舞台容器 + 角色全在内部。
 *
 * 布局里只写一个标签即可，不再需要自己套 FrameLayout：
 *
 * ```xml
 * <com.qwenpaw.chat.widget.AIFaceView
 *     android:layout_width="260dp"
 *     android:layout_height="260dp"
 *     android:background="@color/mascot_stage"
 *     app:aiFacePalette="violet"
 *     app:aiFaceBackdrop="aurora"
 *     app:aiFaceFollowPointer="true" />
 * ```
 *
 * 继承自 FrameLayout，所以原生属性直接生效：
 *  - `android:layout_width/height`  整体尺寸。**会被强制成正方形**：
 *                                   默认取宽高里较短的一边（见 [squareOnMeasure]），
 *                                   所以写了 `layout_weight` 拉高也只会得到正方形
 *  - `android:padding`              **无效**。组件强制 padding = 0（见 [setPadding]），
 *                                   角色尺寸完全由内部固定比例决定
 *  - `android:background`           舞台底色；纯色会被转成圆形舞台；
 *                                   默认圆形取自 @color/mascot_stage
 *  - `android:clickable`            点击事件
 *
 * 正方形化的两条规则：
 *  1. 边长 = 宽高里较短的一边（取 min 而不是 max，保证不超出父容器）
 *  2. 边长不足 `aiFaceMinSize`（默认 48dp）时抬到该值
 *
 * **容器 : 角色 = `OUTER_W` : `INNER_W`**（见文件末尾伴生对象，那里是唯一事实源）。
 * 设容器边长 S，内部 [bot] 的边长 = `S × INNER_W / OUTER_W`，居中摆放，
 * 四周各留 `S × (OUTER_W - INNER_W) / 2 / OUTER_W` 的舞台边。这个关系**不走 padding**，
 * 而是由 [onMeasure] 在测量阶段直接给角色定尺寸 —— 所以外部无论怎么设
 * padding 都影响不到比例。
 *
 * 背景层由 [AIFaceBackdrop] 单独封装（Drawable，不占子 View 名额），两种模式：
 *  - `solid`（默认）：纯色舞台，取 `aiFaceStageColor` / `android:background`；
 *  - `aurora`：极光流动渐变，动画内核是 [AuroraGradient]（同 [AuroraGradientView]）。
 *
 * **两种模式都按 `aiFaceStageShape` 画，默认 circle**，所以极光也是圆的 ——
 * 极光不是「矩形渐变 View 垫在底下再裁圆」，而是把渐变色直接填进圆形路径，
 * 边缘自带抗锯齿。切模式：
 * ```kotlin
 * face.setBackdropMode("aurora")   // 返回 false = 名字不合法，配置没变
 * face.backdropMode = "solid"      // 等价的属性写法
 * face.backdrop.aurora.colors = intArrayOf(...)  // 想换极光配色
 * ```
 * 想在「运行/思考」时自动变极光，用现成的状态监听驱动即可（不用碰动画内核）：
 * ```kotlin
 * face.setOnFaceStateChangeListener { _, to ->
 *     face.setBackdropMode(if (to == "thinking" || to == "searching") "aurora" else "solid")
 * }
 * ```
 *
 * 组件属性（`aiFace*` 前缀）：
 *  - `aiFacePalette`         调色板 black/brown/red/orange/yellow/green/cyan/
 *                            blue/violet/magenta/gray
 *  - `aiFaceScheme`          light / dark
 *  - `aiFaceFgColor`         本体主色（覆盖调色板）
 *  - `aiFaceInkColor`        墨色渐变终点
 *  - `aiFaceBgColor`         眼睛颜色
 *  - `aiFaceFollowPointer`   眼睛是否跟手（默认 false，触摸穿透给容器）
 *  - `aiFaceAutoOnboarding`  是否自动巡演（默认 true）
 *  - `aiFaceInitialState`    初始情绪状态名
 *  - `aiFaceStageColor`      舞台底色（优先于 android:background）
 *  - `aiFaceStageShape`      circle（默认）/ rounded / none
 *  - `aiFaceBackdrop`        背景层模式：solid（默认）/ aurora
 *  - `aiFaceSquare`          强制量成正方形（默认 true）
 *  - `aiFaceMinSize`         最小边长，默认 48dp
 *
 * 属性声明见 attrs.xml，三条容易踩的规则：
 *  1. `aiFacePalette` / `aiFaceScheme` / `aiFaceStageShape` / `aiFaceBackdrop`
 *     在本模块**有全局定义**。光在 `declare-styleable` 里写 `<attr name="x" />`
 *     不算定义 —— aapt2 视其为"引用已存在的 attr"，链接期会报
 *     `resource attr/x not found`，`R.styleable` 里也不会生成字段，
 *     Kotlin 侧直接编译不过。
 *  2. 名值型属性必须是 `format="string"`，**不能改成 enum**：enum 的值在编译期
 *     被折算成 int，"violet" 进来会变成 8，而 `getString()` 只能拿到 "8"
 *     这个序号串 —— 组件按名字查表，于是配置被静默忽略。
 *  3. `enum`/`color` 这类真实类型只给"真类型"属性用（如 `aiFaceStageColor`）。
 *
 * 常用 API：
 * ```kotlin
 * face.setState("thinking")         // 切情绪
 * face.setPalette("blue", "dark")   // 换调色板
 * face.setBackdropMode("aurora")    // 换背景层
 * face.setCustomTheme(0xFF7C4DFF.toInt(), 0xFFFFC24D.toInt())
 * face.onFaceReady = { ... }        // 几何就绪
 * face.onFaceClickListener = { ... }// 点击
 * ```
 */
class AIFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 内部角色，公开只读，需要底层能力时可继续操作它。 */
    val bot: GrokBotView = GrokBotView(context)

    /**
     * 背景层（舞台），公开只读 —— 想调极光配色 / 时长 / 圆角直接改它：
     * `face.backdrop.aurora.colors = ...`、`face.backdrop.loopMs = 8000`。
     */
    val backdrop: AIFaceBackdrop = AIFaceBackdrop()

    /** 舞台形状："circle" | "rounded" | "none"。 */
    var stageShape: String = "circle"
        set(value) {
            field = value
            applyStage()
        }

    /** 舞台底色（仅 `solid` 模式用到）。设为 Color.TRANSPARENT 即完全隐藏舞台。 */
    var stageColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            applyStage()
        }

    /**
     * 背景层模式："solid"（默认，纯色舞台）| "aurora"（极光流动渐变）。
     *
     * 等价于 [setBackdropMode]，但**非法值会被无声忽略**（属性访问器不返回结果，
     * 没法告诉调用方成功与否）；需要知道是否被接受就用 [setBackdropMode]。
     */
    var backdropMode: String
        get() = backdrop.mode
        set(value) {
            setBackdropMode(value)
        }

    /**
     * 切换背景层模式，在「默认舞台底色」和「极光流动渐变」之间切。
     *
     * 两种模式都沿用 [stageShape]（默认正圆），所以极光是**圆形**的。
     * 属性动画只在这两种情况下跑：模式为 `aurora` 且 View 可见
     * （离屏 / 退后台 / GONE 时自动停，不空转）。
     *
     * @param mode `"solid"` 或 `"aurora"`（不区分大小写），见 [AIFaceBackdrop.MODES]
     * @return 是否被接受。`false` 表示名字不合法，**配置保持不变**（不会退回默认）
     */
    fun setBackdropMode( mode: String): Boolean {
        val m = mode.lowercase()
        if (!AIFaceBackdrop.isMode(m)) return false
        backdrop.mode = m
        applyStage()
        syncBackdropRunning()
        return true
    }

    /**
     * 是否强制量成正方形（默认 true）。
     *
     * 取宽高里**较短**的一边作边长，再按 EXACTLY 量下去，
     * 保证不被父容器裁剪、且圆形舞台永远是正圆。
     * 用 `layout_weight` 拉高组件时也能自动收敛回正方形。
     */
    var squareOnMeasure: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /**
     * 最小边长（px）。宽度或高度不足时也不会小于这个值，
     * 避免父容器空间过小把角色压成看不见。
     */
    var minSize: Int = (48 * context.resources.displayMetrics.density).roundToInt()
        set(value) {
            val v = value.coerceAtLeast(0)
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    /** 几何数据就绪回调（转发内部角色）。 */
    var onFaceReady: (() -> Unit)?
        get() = bot.onBotReady
        set(value) { bot.onBotReady = value }

    /** 主题色变化回调 (fg, ink, bg)。 */
    var onFaceThemeChanged: ((fg: Int, ink: Int, bg: Int) -> Unit)?
        get() = bot.onThemeChanged
        set(value) { bot.onThemeChanged = value }

    /**
     * 情绪状态切换监听。
     *
     * 声明成 `fun interface`，所以 Kotlin 侧可以直接传 lambda：
     * ```kotlin
     * face.setOnFaceStateChangeListener { from, to -> ... }
     * ```
     * Java 侧则 `new AIFaceView.OnFaceStateChangeListener() { ... }`。
     */
    fun interface OnFaceStateChangeListener {
        /**
         * @param from 切换前的状态名
         * @param to   切换后的状态名
         */
        fun onFaceStateChanged(from: String, to: String)
    }

    private var stateChangeListener: OnFaceStateChangeListener? = null

    /** 情绪状态切换回调（等价于 [setOnFaceStateChangeListener]）。 */
    var onFaceStateChanged: OnFaceStateChangeListener?
        get() = stateChangeListener
        set(value) {
            setOnFaceStateChangeListener(value)
        }

    /**
     * 设置情绪状态切换监听。
     *
     * **两个来源都会回调**：手动 [setState]，以及 onboarding 自动巡演
     * （每约 1.2s 在 "idle ↔ 情绪" 之间轮换，见 [Tables.ONBOARDING_MS]）。
     * 想忽略自动轮换只关心手动锁定，可在回调里判断 [isOnboarding]。
     *
     * 顺带一提：拿它驱动背景层可以做出「运行态换肤」——
     * 回调里调 [setBackdropMode] 即可，不必动动画内核。
     *
     * @param notifyCurrent 非空监听时是否立刻用当前状态回调一次
     *                      （此时 from == to == [state]）。
     *                      适合「View 复用 / 界面重建后补一次初值」的场景。
     */
    fun setOnFaceStateChangeListener(
        listener: OnFaceStateChangeListener?,
        notifyCurrent: Boolean = false
    ) {
        stateChangeListener = listener
        if (listener == null) {
            bot.onStateChanged = null
        } else {
            bot.onStateChanged = { from: String, to: String ->
                listener.onFaceStateChanged(from, to)
            }
        }
        if (notifyCurrent && listener != null) {
            val cur = state
            listener.onFaceStateChanged(cur, cur)
        }
    }

    /** 可用情绪状态名（24 个）。 */
    val states: Set<String> get() = bot.states

    /** 是否正在自动巡演（onboarding）。 */
    val isOnboarding: Boolean get() = bot.autoOnboarding

    /** 点击回调。 */
    var onFaceClickListener: (() -> Unit)? = null

    /** 长按回调。 */
    var onFaceLongClickListener: (() -> Unit)? = null

    /** 当前情绪状态名（几何未就绪时返回最近一次请求的状态名）。 */
    val state: String get() = bot.currentState

    /** 眼睛是否跟随手指。 */
    var followPointer: Boolean
        get() = bot.followPointer
        set(value) { bot.followPointer = value }

    init {
        var paletteAttr: String? = null
        var schemeAttr: String? = null
        var fgAttr: Int? = null
        var inkAttr: Int? = null
        var bgAttr: Int? = null
        var followAttr = false
        var autoAttr = true
        var stateAttr: String? = null
        var stageColorAttr: Int? = null
        var stageShapeAttr = "circle"
        var backdropAttr = AIFaceBackdrop.MODE_SOLID

        attrs?.let { set ->
            val a = context.obtainStyledAttributes(set, R.styleable.AIFaceView)
            paletteAttr = a.getString(R.styleable.AIFaceView_aiFacePalette)
            schemeAttr = a.getString(R.styleable.AIFaceView_aiFaceScheme)
            if (a.hasValue(R.styleable.AIFaceView_aiFaceFgColor)) {
                fgAttr = a.getColor(R.styleable.AIFaceView_aiFaceFgColor, 0)
            }
            if (a.hasValue(R.styleable.AIFaceView_aiFaceInkColor)) {
                inkAttr = a.getColor(R.styleable.AIFaceView_aiFaceInkColor, 0)
            }
            if (a.hasValue(R.styleable.AIFaceView_aiFaceBgColor)) {
                bgAttr = a.getColor(R.styleable.AIFaceView_aiFaceBgColor, 0)
            }
            followAttr = a.getBoolean(R.styleable.AIFaceView_aiFaceFollowPointer, false)
            autoAttr = a.getBoolean(R.styleable.AIFaceView_aiFaceAutoOnboarding, true)
            stateAttr = a.getString(R.styleable.AIFaceView_aiFaceInitialState)
            if (a.hasValue(R.styleable.AIFaceView_aiFaceStageColor)) {
                stageColorAttr = a.getColor(R.styleable.AIFaceView_aiFaceStageColor, 0)
            }
            a.getString(R.styleable.AIFaceView_aiFaceStageShape)?.let { stageShapeAttr = it }
            a.getString(R.styleable.AIFaceView_aiFaceBackdrop)?.let { backdropAttr = it }
            squareOnMeasure = a.getBoolean(R.styleable.AIFaceView_aiFaceSquare, squareOnMeasure)
            if (a.hasValue(R.styleable.AIFaceView_aiFaceMinSize)) {
                minSize = a.getDimensionPixelSize(R.styleable.AIFaceView_aiFaceMinSize, minSize)
            }
            a.recycle()
        }

        // android:background 的纯色当作舞台底色，随后清掉（由 backdrop 自己画）
        val fromBg = extractSolidColor(background)
        background = null

        // 角色先按 MATCH_PARENT 挂上，真实尺寸在 onMeasure 里
        // 按「容器 : 角色 = OUTER_W : INNER_W」重新指定（居中）
        addView(bot, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER
        })

        // 兜底：XML 的 android:padding 可能已由 View 构造函数直接写进字段，
        // 这里再压一次，确保起点就是 0。
        setPadding(0, 0, 0, 0)

        // 角色属性（用公开 API 逐个设置）。
        // 顺序很重要：setBotState / setCustomTheme 会改 autoOnboarding，
        // 所以 autoOnboarding 与 initialState 必须最后设。
        bot.followPointer = followAttr
        paletteAttr?.let { bot.setPalette(it, schemeAttr) }
            ?: schemeAttr?.let { s -> bot.setPalette(bot.palette, s) }
        if (fgAttr != null || inkAttr != null) bot.setCustomTheme(fgAttr ?: bot.fgColor, inkAttr)
        bgAttr?.let { bot.bgColor = it }
        // 先给初始状态，再恢复巡演开关（否则 initialState 会把巡演关掉）
        stateAttr?.let { bot.setBotState(it) }
        bot.autoOnboarding = autoAttr

        // 背景层：模式要先定，形状/底色随后落（都在 applyStage 里生效）。
        // 非法模式名保持默认 solid —— 不静默改成别的模式。
        setBackdropMode(backdropAttr)
        // 舞台：优先 aiFaceStageColor，其次 android:background 纯色，最后默认沙色
        stageColor = stageColorAttr
            ?: fromBg
            ?: ContextCompat.getColor(context, R.color.mascot_stage)
        stageShape = stageShapeAttr

        isClickable = true
        isFocusable = true
    }

    /**
     * activity onDestroy 里面进行回收
     * */
    public fun destroyView(){
        this.onFaceReady = null
        this.onFaceThemeChanged = null
        this.onFaceClickListener = null
        this.onFaceLongClickListener = null
        this.setOnFaceStateChangeListener(null)
    }

    /** 从 drawable 里提取纯色；渐变色 / 位图返回 null。 */
    private fun extractSolidColor(d: Drawable?): Int? = when (d) {
        is ColorDrawable -> d.color
        else -> null
    }

    /**
     * 量成正方形，并把角色按 `OUTER_W` : `INNER_W` 收缩后居中。
     *
     * 正方形规则：取宽高里较短的一边作边长，用 EXACTLY 再量一次。
     *  - 父容器给的空间怎么变，组件都保持正方形（圆形舞台就是正圆）
     *  - 取 min 而不是 max，保证不会超出父容器可用空间
     *  - 边长不足 [minSize] 时抬到 [minSize]
     *
     * 注意：一边是 `UNSPECIFIED` 时它的 size 是 0，不能拿去比 min，
     * 否则会量出 0×0；那种情况改用另一边的值。
     *
     * 角色尺寸：容器边长 S → 角色边长 `S × INNER_W / OUTER_W`（见 [fitInner]），
     * 通过改 [bot] 的 LayoutParams 达成，**不经过 padding**。
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (!squareOnMeasure) {
            // 先恢复 MATCH_PARENT 再走原生测量。否则上一轮算出的角色尺寸
            // 会被当成组件的固有内容大小，AT_MOST 下每量一次就缩一圈。
            setBotSize(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            super.onMeasure(widthSpec, heightSpec)
        } else {
            val wMode = MeasureSpec.getMode(widthSpec)
            val hMode = MeasureSpec.getMode(heightSpec)
            val w = MeasureSpec.getSize(widthSpec)
            val h = MeasureSpec.getSize(heightSpec)

            var side = when {
                // 两边都没约束（如 ScrollView 内），只能用最小边长兜底
                wMode == MeasureSpec.UNSPECIFIED && hMode == MeasureSpec.UNSPECIFIED -> minSize
                wMode == MeasureSpec.UNSPECIFIED -> h
                hMode == MeasureSpec.UNSPECIFIED -> w
                else -> min(w, h)
            }

            if (side < minSize) side = minSize

            val squareSpec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
            super.onMeasure(squareSpec, squareSpec)
        }

        // 自身尺寸已定，按 OUTER_W : INNER_W 给角色定尺寸；变了就再量一次
        if (setBotSize(fitInner(measuredWidth), fitInner(measuredHeight))) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
            )
        }
    }

    /** 容器边长 → 角色边长：`outer × INNER_W / OUTER_W`，四舍五入。 */
    private fun fitInner(outer: Int): Int =
        ((outer.toLong() * INNER_W + OUTER_W / 2) / OUTER_W).toInt()

    /**
     * 手动把角色摆在几何中心，**不经过 FrameLayout 的 padding 逻辑**。
     *
     * 为什么不能靠 `super.onLayout`：FrameLayout 定位子 View 时是直接读
     * 私有字段 `mPaddingLeft` / `mPaddingTop`（`getPaddingLeftWithForeground()`），
     * 而 `View.resolvePadding()` 有机会把 XML 里的 `android:padding`
     * 重新写回这些字段 —— 光覆写 `setPadding` 堵不住这条路径。
     * 这里按几何中心直接摆，padding 是多少都无所谓。
     *
     * 注意：本组件约定只有 [bot] 一个子 View，别手动 addView 别的（不参与布局）。
     * 背景层是 Drawable（[backdrop]），不占子 View 名额，所以这条约定不受影响。
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        val cw = bot.measuredWidth
        val ch = bot.measuredHeight
        val x = (w - cw) / 2
        val y = (h - ch) / 2
        bot.layout(x, y, x + cw, y + ch)
    }

    /**
     * 直接改 [bot] 的 LayoutParams 字段，不走 `View.setLayoutParams()`，
     * 避免在测量过程中触发 requestLayout 造成递归。
     *
     * @return 尺寸是否真的变了（没变就不用重新测量）
     */
    private fun setBotSize(w: Int, h: Int): Boolean {
        val lp = bot.layoutParams as? LayoutParams ?: return false
        if (lp.width == w && lp.height == h) return false
        lp.width = w
        lp.height = h
        return true
    }

    /**
     * **强制 padding 恒为 0** —— 外部设置一律不生效。
     *
     * 无论是 XML 的 `android:padding` / `android:paddingHorizontal`，
     * 还是代码里调用 `setPadding(...)`、`setPaddingRelative(...)`，
     * 最终都落到这里被抹平。
     *
     * 角色与容器的 `OUTER_W` : `INNER_W` 关系由 [onMeasure] 独立控制，不走 padding 通道；
     * 摆放位置则由 [onLayout] 直接按几何中心算，同样不读 padding。
     * 所以调用方无法通过 padding 破坏比例或位置。
     */
    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(0, 0, 0, 0)
    }

    /** 同上：`android:paddingStart/End` 也一律无效。 */
    override fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {
        super.setPaddingRelative(0, 0, 0, 0)
    }

    // ---- 背景层动画的可见性生命周期 ----
    // 极光每帧都要 invalidate 整个组件，离屏还跑就是纯浪费 ——
    // 四条路径（attach / detach / 窗口可见性 / 自身可见性）都对齐一次。

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncBackdropRunning()
    }

    override fun onDetachedFromWindow() {
        // 注意：这里 isAttachedToWindow 还是 true，不能只靠 sync，
        // 必须显式停掉。
        backdrop.setRunning(false)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncBackdropRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // 构造期（尚未 attach）也会回调，此时字段可能还没初始化，直接跳过
        if (!isAttachedToWindow) return
        syncBackdropRunning()
    }

    /** 只有「已 attach 且整条链路可见」时才让极光跑起来；编辑预览一律不跑。 */
    private fun syncBackdropRunning() {
        if (isInEditMode) {
            backdrop.setRunning(false)
            return
        }
        backdrop.setRunning(isAttachedToWindow && isShown)
    }

    /**
     * 把 [stageShape] / [stageColor] / [backdropMode] 落到背景绘制上。
     *
     * 是否挂 background 交给 [AIFaceBackdrop.drawsSomething] 判断 ——
     * 「什么情况下不画」这件事属于背景层自己的语义，不散落在宿主里。
     */
    private fun applyStage() {
        backdrop.shape = stageShape
        backdrop.color = stageColor
        background = if (backdrop.drawsSomething) backdrop else null
    }

    // ---- 对外 API（全部转发到内部角色）----

    /** 切换情绪状态（自动退出巡演模式）。 */
    fun setState(name: String) = bot.setBotState(name)

    /** 恢复 onboarding 自动巡演。 */
    fun resetToOnboarding() = bot.resetToOnboarding()

    /** 切换调色板（11 套 × light/dark）。 */
    fun setPalette(id: String, scheme: String? = null) = bot.setPalette(id, scheme)

    /** 恢复默认主题（黑色）。 */
    fun resetTheme() = bot.resetTheme()

    /** 完全自定义主题色。[ink] 为空时按主色自动推导。 */
    fun setCustomTheme(fg: Int, ink: Int? = null, bg: Int? = null) =
        bot.setCustomTheme(fg, ink, bg)

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        super.setOnLongClickListener { v ->
            if (onFaceLongClickListener != null) {
                onFaceLongClickListener?.invoke()
                true
            } else {
                l?.onLongClick(v) ?: false
            }
        }
    }

    // 组件层负责把「手指抬起」转成点击回调（内部角色默认不消费触摸）
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && handled) {
            onFaceClickListener?.invoke()
        }
        return handled
    }

    private companion object {
        /**
         * **比例的唯一事实源**：容器边长 : 角色边长 = `OUTER_W` : `INNER_W`。
         *
         * 派生量（都不用另记常量）：
         *  - 角色边长占比 = `INNER_W / OUTER_W`
         *  - 每边舞台边占比 = `(OUTER_W - INNER_W) / 2 / OUTER_W`
         *
         * 所以**调整比例只需要改这两个数**：本文件的 KDoc、app/AIFaceView.md、
         * activity_main.xml 的注释、sim-ratio.mjs 一律只引用常量名，
         * 或者直接从本文件读值 —— 没有任何一处写死数字，改完不用回头同步文档。
         * 想保持舞台边视觉粗细不变，就让差值保持不变、只缩放基准数。
         *
         * lint-aiFace.mjs 有两条断言守着这条约定：
         *  1. 本文件里不得出现 "N : N" 形式的比例字面量（防注释再次漂移）；
         *  2. 文档里若写了比例数字，必须与这里的常量一致。
         *
         * 历史坑：这里一度被改成 18 / 16（+2），而文档、布局注释、sim 脚本、
         * 断言全都写死着旧比例 —— 于是 lint 长期红灯、sim 又验证不到真实常量
         * （它自己复刻了一份）。现在统一成"只认常量"。
         */
        const val OUTER_W = 16
        const val INNER_W = 12
    }
}
