package com.qwenpaw.chat.widget

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 角色编排（对应 replica/src/character.js 的 _tick / _paint）。
 *
 * 每帧四站：
 *   1. Pose.apply 算目标值（不给结果，只给意图）
 *   2. 消化 ctx 侧信道命令（踢一脚 / 要求眨眼）
 *   3. 眼睛：morph / 眨眼队列 / 视线 / 眨单眼
 *   4. 全部弹簧按 1/120s 子步积分
 *
 * 绘制用 android.graphics.Canvas（非 Compose）：
 *   translate(tx, ty) + rotate(pivot=R) + scale(pivot=R)
 *   == Web 的 translate(R+tx, R+ty) rotate scale translate(-R, -R)
 */
internal class GrokCharacter(
    private val geo: GrokGeo,
    var shapeName: String = "blob"
) {
    // 并行弹簧通道
    val spin = Spring()
    val tx = Spring()
    val ty = Spring()
    val squash = Spring(1f)
    val blink = Spring(1f)
    val eyeScale = Spring(1f)
    val gazeX = Spring()
    val gazeY = Spring()
    val eyeMorph = Spring(1f)

    var state = "idle"
        private set

    /**
     * 情绪状态切换回调：(from, to)。
     *
     * 这是**唯一**的切换出口 —— 手动 [setState]（外部驱动）与 onboarding
     * 自动轮换（[tick] 里调 [setState]）都经过它。挂在 [GrokBotView.setBotState]
     * 上只能收到手动切换，会漏掉全部自动轮换。
     *
     * 主线程触发；onboarding 模式下约每 [Tables.ONBOARDING_MS] ms 一次。
     * 注意自动轮换是在 `onDraw → tick` 里切换的，所以回调也可能发生在绘制过程中。
     */
    var onStateChanged: ((from: String, to: String) -> Unit)? = null

    /** "onboarding" = 自动巡演情绪轮换；"hold" = 锁定在当前状态。 */
    var mode = "onboarding"

    private var stateAt = 0f
    private var t0 = 0f
    private var last = 0f
    private var moodN = 0

    private var eyeFrom = 0
    private var eyeTo = 0
    private var eyeIdx = 0
    private var eyeStiffness = 7f
    private var eyeUntil = 0f
    private var blinkUntil = 0f
    private var gazeUntil = 0f
    private var winkAt = -1e9f
    private var winkEye = 0
    private var winkUntil = 0f

    private val blinkQueue = BlinkQueue()
    private var ctx = Ctx()

    // 眼睛 morph 缓冲：fromPolys 是"上次被打断时的视觉快照"（Web _fromPolys），
    // morphBuf 是当帧两眼的插值结果。全部预分配，帧内零 GC。
    private val fromPolys: Array<FloatArray>
    private val morphBuf: Array<FloatArray>
    private var snapshotValid = false
    private val eyePath0 = Path()
    private val eyePath1 = Path()
    /** 单眼剪切矩阵（use3d 分支），复用避免帧内分配。 */
    private val eyeMatrix = Matrix()
    /** 3D 相对旋转：POSE(17,-14,29) 相对 POSE_HOME(33,-19,38)，常量只算一次。 */
    private val cr: FloatArray by lazy {
        Rot3.rel(
            Rot3.rot(Tables.POSE[0], Tables.POSE[1], Tables.POSE[2]),
            Rot3.rot(Tables.POSE_HOME[0], Tables.POSE_HOME[1], Tables.POSE_HOME[2])
        )
    }

    var sizePx = 190f
    var pointer: PointF? = null
    private val smoothPointer = FloatArray(2)

    // 颜色**不在这里存**：本体色与眼睛色由 GrokBotView 每帧通过
    // draw(canvas, w, h, fgPaint, bgPaint) 传进来。
    // 曾经这里有两个同名字段（fgColor/bgColor），但绘制从不读它们，
    // 只被外部赋值 —— 是个误导人的死状态，已删除。

    init {
        val n = geo.eyeShapes[0][0].size
        morphBuf = Array(2) { FloatArray(n) }
        fromPolys = Array(2) { FloatArray(n) }
        setState("idle", resetEyes = true)
    }

    private fun springOf(name: String) = Tables.SPRINGS.getValue(name)

    fun setState(name: String, resetEyes: Boolean = false, now: Float = last) {
        // 无效名直接忽略，不触发回调（否则会报出一个根本不存在的状态）
        val list = Tables.EYE_PLAYLIST[name] ?: return
        val prev = state
        state = name
        stateAt = now
        eyeIdx = 0
        if (resetEyes) {
            eyeFrom = list[0]; eyeTo = list[0]
            eyeMorph.x = 1f; eyeMorph.t = 1f; eyeMorph.v = 0f
            snapshotValid = false
        } else if (name != "sleeping" && name != "waking") {
            morphEyes(list[0], if (name == "excited") 10f else 8f)
        }
        eyeUntil = stateAt + rand(Tables.EYE_HOLD_MS.getValue(name))
        blinkUntil = Tables.BLINK_MS[name]?.let { stateAt + rand(1500f, 7000f) } ?: Float.MAX_VALUE
        gazeUntil = stateAt + rand(500f, 1400f)
        winkUntil = stateAt + rand(3000f, 8000f)
        // 每帧上下文：所有"下一次触发时刻"都从当前时间起算，
        // 否则刚进状态就会立刻踢一脚 / 立刻开始打盹。
        // 注意 nodUntil 是固定 1800（源码 _freshCtx），不是 rand。
        ctx = Ctx().also {
            it.nodUntil = stateAt + 1800f
            it.impulseAt = stateAt + rand(500f, 1200f)
            it.stAt = stateAt + if (name == "excited") rand(400f, 1100f)
            else if (name == "searching") rand(800f, 1600f)
            else if (name == "working") rand(1200f, 2400f)
            else rand(6000f, 10000f)
        }
        if (name !in setOf("waking", "sleeping", "drowsy")) blinkQueue.queue(stateAt)

        // 放在最后统一广播：监听方拿到回调时，眼睛 / 计时 / ctx 都已就位。
        // 只在状态**真的变了**时才通知（重复 setState 同一状态不算切换）。
        // init 里的 setState("idle") 因 prev == name 不会触发。
        if (prev != name) onStateChanged?.invoke(prev, name)
    }

    /**
     * 切换目标眼睛形状时，把"当前视觉状态"（可能正处于上一次 morph 的中间态）
     * 快照进 fromPolys，从它开始新的 morph。没有这一步，morph 未完成时
     * 被打断眼睛会瞬跳（Web _morphEyes + _currentPolys）。
     */
    fun morphEyes(index: Int, stiffness: Float = 7f) {
        if (index == eyeTo && eyeMorph.t == 1f) return
        val t = eyeMorph.x.clamp(0f, 1f)
        for (e in 0..1) {
            val from = if (snapshotValid) fromPolys[e] else geo.eyeShapes[eyeFrom][e]
            PathGeom.lerpPolyInto(fromPolys[e], from, geo.eyeShapes[eyeTo][e], t)
        }
        snapshotValid = true
        eyeFrom = eyeTo
        eyeTo = index
        eyeMorph.x = 0f; eyeMorph.v = 0f; eyeMorph.t = 1f
        eyeStiffness = stiffness
    }

    fun tick(nowMs: Float, pxSize: Float) {
        if (t0 == 0f) { t0 = nowMs; last = nowMs; stateAt = nowMs }
        val dt = min((nowMs - last) / 1000f, 0.1f)
        last = nowMs
        sizePx = pxSize

        // onboarding 轮换：idle / 情绪 交替，每 1200ms 切一次
        if (mode == "onboarding" && nowMs - stateAt >= Tables.ONBOARDING_MS) {
            moodN += 1
            setState(Tables.onboardMood(moodN), now = nowMs)
        }

        val mt = (nowMs - t0) / 1000f
        val dtState = (nowMs - stateAt) / 1000f

        // ---- 第一站：算目标值 ----
        val p = Pose.apply(state, mt, dtState, nowMs, ctx)
        spin.t = p.spin
        tx.t = p.tx
        ty.t = p.ty
        squash.t = p.squash
        eyeScale.t = p.eyeBoost

        // ---- 第二站：消化 ctx 命令 ----
        if (ctx.tyKick != 0f) { ty.v += ctx.tyKick; ctx.tyKick = 0f }
        if (ctx.spinKick != 0f) { spin.v += ctx.spinKick; ctx.spinKick = 0f }
        if (ctx.wantBlink) { blinkQueue.queue(nowMs); ctx.wantBlink = false }

        // ---- 第三站：眼睛 ----
        if (state != "waking" && state != "sleeping" && nowMs >= eyeUntil) {
            val list = Tables.EYE_PLAYLIST.getValue(state)
            eyeIdx = (eyeIdx + 1 + (Math.random() * (list.size - 1)).toInt()) % list.size
            val stiff = if (state == "searching" || state == "excited") 10f else 6f
            morphEyes(list[eyeIdx], stiff)
            eyeUntil = nowMs + rand(Tables.EYE_HOLD_MS.getValue(state))
        }

        val cadence = Tables.BLINK_MS[state]
        if (cadence != null && nowMs >= blinkUntil) {
            blinkQueue.queue(nowMs)
            blinkUntil = nowMs + rand(cadence)
        }
        blink.t = blinkQueue.consume(nowMs) ?: (if (blinkQueue.isEmpty()) p.lid else blink.t)

        if (nowMs >= gazeUntil) {
            val g = Pose.nextGaze(state)
            gazeX.t = g[0]; gazeY.t = g[1]
            gazeUntil = nowMs + rand(g[2], g[3])
        }
        if (state in Tables.WINK_STATES && nowMs >= winkUntil) {
            winkAt = nowMs
            winkEye = if (Math.random() < 0.5) 0 else 1
            winkUntil = nowMs + rand(4500f, 10000f)
        }

        // ---- 第四站：弹簧积分 ----
        val steps = springSteps(dt)
        stepSprings(
            steps, dt,
            spin to springOf("spin"), tx to springOf("x"), ty to springOf("y"),
            squash to springOf("squash"), blink to springOf("blink"), eyeScale to springOf("eyeScale"),
            gazeX to springOf("gazeX"), gazeY to springOf("gazeY")
        )
        val h = dt / steps
        for (i in 0 until steps) eyeMorph.step(eyeStiffness, 1f, h)

        // ---- 指针跟随：mapPointer 椭圆映射（math.js）+ 指数平滑 ----
        // 把指针按"归一化距离 + 角度"映到椭圆上，再 clamp ±0.6 × 22 / × 14。
        // 有指针时眼睛朝向权重降到 0.2（eyes.js gazeW）。
        val ptr = pointer
        if (ptr != null) {
            val c = sizePx / 2f
            val dx = ptr.x - c
            val dy = ptr.y - c
            val o = min(1f, hypot(dx, dy) / (sizePx * 2f))
            val l = atan2(dy, dx)
            val mx = 0.6f * (14f / 22f) * o * cos(l) * sizePx
            val my = 0.6f * o * sin(l) * sizePx
            val tx0 = (mx / sizePx).clamp(-0.6f, 0.6f) * 22f
            val ty0 = (my / sizePx).clamp(-0.6f, 0.6f) * 14f
            val z = Ease.frameLerp(0.16f, dt)
            smoothPointer[0] += (tx0 - smoothPointer[0]) * z
            smoothPointer[1] += (ty0 - smoothPointer[1]) * z
        } else {
            smoothPointer[0] *= 0.9f
            smoothPointer[1] *= 0.9f
        }
    }

    // ---- 每帧绘制（android.graphics.Canvas）----
    fun draw(canvas: Canvas, viewW: Float, viewH: Float, fgPaint: Paint, bgPaint: Paint) {
        val shape = geo.shapes[shapeName] ?: return
        val s = min(viewW, viewH) / geo.vbW
        val now = last
        val t = eyeMorph.x.clamp(0f, 1f)
        // morph 时的眼睛弹性膨胀（eyes.js pulse）
        val pulse = 1f + 0.07f * sin(t * PI_F)
        val rot = spin.x * shape.tiltScale
        val g9e = geo.g9e

        canvas.save()
        // 非正方形 View 时整体居中
        canvas.translate((viewW - geo.vbW * s) / 2f, (viewH - geo.vbW * s) / 2f)
        canvas.scale(s, s)
        canvas.translate(-geo.vbX, -geo.vbY)

        canvas.save()
        canvas.translate(tx.x, ty.x)
        canvas.rotate(rot, geo.Re, geo.Re)
        canvas.scale(1f, squash.x, geo.Re, geo.Re)

        // 静态身体：预解析 Path，保真贝塞尔（polarRing 是凸包近似，凹形会变形）
        canvas.drawPath(shape.bodyPath, fgPaint)

        // 眼睛永远画在身体内部（clip 硬规则）
        canvas.save()
        canvas.clipPath(shape.bodyPath)

        // ---- 共享尺寸链（eyes.js paintEyes 上半段，loginWrap=true 的路径）----
        // 先完成两眼 morph，再算统一尺寸：a1/o1 是两眼半宽，l1 是眼距，
        // _ee 把眼睛大小限制在"眼距装得下"的范围内。
        for (i in 0..1) PathGeom.lerpPolyInto(morphBuf[i], fromPolys[i], geo.eyeShapes[eyeTo][i], t)
        val c0x = PathGeom.centroidX(morphBuf[0]); val c0y = PathGeom.centroidY(morphBuf[0])
        val c1x = PathGeom.centroidX(morphBuf[1]); val c1y = PathGeom.centroidY(morphBuf[1])
        var a1 = 0f; var o1 = 0f
        for (p in 0 until morphBuf[0].size / 2) {
            val d0 = abs(morphBuf[0][p * 2] - c0x); if (d0 > a1) a1 = d0
            val d1 = abs(morphBuf[1][p * 2] - c1x); if (d1 > o1) o1 = d1
        }
        val fSx = shape.faceSx * Tables.FACE_GAP
        val fSy = shape.faceSy * Tables.FACE_HEIGHT
        val sX = shape.faceLeftDX // uniformEyes=true：左眼质心的额外偏移（仅 wedge 有 -6）
        val l1 = abs(c1x - (c0x + sX)) * fSx
        val ee = if (a1 + o1 > 0.5f) (l1 / (a1 + o1)).clamp(0.35f, 4f) else 4f
        // uniformEyes=true 时 Uee 不含 FACE_SIZE；eyeScaleProp=blob 恒为 1（shapeEyeScale）
        val oX = min(eyeScale.x.clamp(0.2f, 2f), ee / pulse)
        val hee = min(oX * Tables.FACE_EYE_W.clamp(0.2f, 3f), ee / pulse)
        val u1 = oX * Tables.FACE_EYE_H.clamp(0.2f, 3f)
        val ca = geo.Re + shape.faceX

        for (i in 0..1) {
            val poly = morphBuf[i]
            val gn = if (i == 0) c0x else c1x
            val gi = if (i == 0) c0y else c1y
            val ea = gn + (if (i == 0) sX else 0f)
            val lid = winkLid(blink.x, i)

            // ---- 3D 贴球（eyes.js use3d）：眼形数据是"家姿态(33,-19,38)下画在球面上的照片"，
            // 每帧用 cr 把它投回当前姿态：位置按球面坐标重算，远侧眼被 _c 透视压缩，
            // 再用 (km,Ree,Fee,zee) 剪切矩阵让眼睛贴回球面。漏掉这层，两眼就会以
            // 全尺寸 + 镜像倾斜平铺 —— 即"八字眼"。----
            val xr = (ea - geo.Re) / geo.Re
            val fr = (geo.Re - gi) / geo.Re
            val ia = sqrt(max(0f, 1f - xr * xr - fr * fr)).let { if (it == 0f) 0.02f else it }
            val li = cr[0] * xr + cr[1] * fr + cr[2] * ia
            val bl = cr[3] * xr + cr[4] * fr + cr[5] * ia
            val io = cr[6] * xr + cr[7] * fr + cr[8] * ia
            val wo = li * geo.Re * fSx
            val sre = (geo.Re + shape.faceY - bl * geo.Re * fSy)
                .clamp(shape.top + 2f, shape.bottom - 2f)
            var uo = -fr * xr; var tl = 1f - fr * fr; var zi = -fr * ia
            val yo = hypot(uo, hypot(tl, zi))
            if (yo < 1e-6f) { uo = 0f; tl = 0f; zi = 1f } else { uo /= yo; tl /= yo; zi /= yo }
            val md = fr * zi - ia * tl; val oc = ia * uo - xr * zi; val yu = xr * tl - fr * uo
            val vm = cr[0] * uo + cr[1] * tl + cr[2] * zi
            val hme = cr[3] * uo + cr[4] * tl + cr[5] * zi
            val nre = cr[0] * md + cr[1] * oc + cr[2] * yu
            val ere = cr[3] * md + cr[4] * oc + cr[5] * yu
            val cre = md; val ha = -oc; val ci = uo; val ys = -tl
            val ku = (cre * ys - ci * ha).let { if (it == 0f) 1e-6f else it }
            val ql = ys / ku; val gee = -ci / ku; val bm = -ha / ku; val ire = cre / ku
            val km = nre * ql + vm * bm
            val fee = nre * gee + vm * ire
            val ree = -ere * ql - hme * bm
            val zee = -ere * gee - hme * ire
            val c2 = max(hypot(km, ree), 0.02f)       // 水平透视压缩（远侧眼变窄）
            val vre = max(hypot(fee, zee), 0.02f)     // 垂直透视压缩
            val bre = io > 0.02f                       // 转到球面背面 = 隐藏
            val tre = Ease.smooth((io / 0.5f).clamp(0f, 1f))

            // 尺寸链：vee/v2 含透视压缩；frM/iaM 是矩阵的均匀基准
            val vee = (c2 * hee * pulse).clamp(0.02f, 2.4f)
            val v2 = (vre * lid * u1 * pulse).clamp(0.02f, 2.4f)
            val ume = g9e * v2 + 2f

            // 无意识微颤（eyes.js Kj/Ko）：删掉角色立刻"死"掉
            var kj = sin(now * 42e-5f + i) * 1.4f + sin(now * 0.001f + i * 2f) * 0.5f
            var ko = sin(now * 58e-5f + i) * 0.9f
            val hasPtr = pointer != null
            if (hasPtr) { kj += smoothPointer[0]; ko += smoothPointer[1] }
            val gazeW = if (hasPtr) 0.2f else 1f
            kj += gazeX.x * gazeW
            ko += gazeY.x * gazeW

            // use3d 路径的垂直位置：球面投影 Sre + 视线偏移
            val vl = (sre + ko * fSy).clamp(shape.top + ume, shape.bottom - ume)

            // 水平夹取：逐点把眼睛边缘压回身体扫描线边界内（span 硬规则）
            var o2 = -Float.MAX_VALUE
            var xl = Float.MAX_VALUE
            val n = poly.size / 2
            for (p in 0 until n) {
                val frp = (poly[p * 2] - gn) * vee
                val y = vl + (poly[p * 2 + 1] - gi) * v2
                val lft = shape.span.left(y)
                val rgt = shape.span.right(y)
                if (lft - frp > o2) o2 = lft - frp
                if (rgt - frp < xl) xl = rgt - frp
            }
            val xre = ca + wo + kj * fSx
            val lx0 = if (o2 <= xl) xre.clamp(o2, xl) else (o2 + xl) / 2f
            // 贴近球面边缘时允许越界滑动（Tre 渐近 0），正脸时严格夹取
            val dd = lx0 + (xre - lx0) * (1f - tre)
            if (!bre) continue // 背面：Web display:none

            // 眼睛剪切矩阵（turn==null 路径）：SVG matrix(a b c d 0 0)
            // x' = a*x + c*y; y' = b*x + d*y —— 对应 Android Matrix 的 MSCALE/MSKEW
            val frM = (hee * pulse).clamp(0.02f, 2.4f)
            val iaM = (lid * u1 * pulse).clamp(0.02f, 2.4f)
            eyeMatrix.setValues(
                floatArrayOf(
                    km * frM, fee * iaM, 0f,
                    ree * frM, zee * iaM, 0f,
                    0f, 0f, 1f
                )
            )
            val path = if (i == 0) eyePath0 else eyePath1
            PathGeom.polyToPath(poly, path)
            canvas.save()
            canvas.translate(dd, vl)
            canvas.concat(eyeMatrix)
            canvas.translate(-gn, -gi)
            canvas.drawPath(path, bgPaint)
            canvas.restore()
        }
        canvas.restore() // clip
        canvas.restore() // pivot 变换
        canvas.restore() // 视口变换
    }

    private fun winkLid(base: Float, i: Int): Float {
        var lid = max(base, 0.04f)
        if (i == winkEye && last < winkAt + 320f) {
            val x = (last - winkAt) / 320f
            val f = if (x < 0.42f) 1f - x / 0.42f else (x - 0.42f) / 0.58f
            lid = max(lid * f.clamp(0f, 1f), 0.04f)
        }
        return lid
    }
}
