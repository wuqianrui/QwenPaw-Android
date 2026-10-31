package com.qwenpaw.chat.widget

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 路径几何（对应 replica/src/geometry 相关函数）。
 * 输出统一为扁平 FloatArray [x0,y0,x1,y1,...]，避免装箱与 GC 抖动。
 */
internal object PathGeom {

    private val TOKEN = Regex("[MLCQZmlcqz]|-?\\d*\\.?\\d+(?:e[-+]?\\d+)?")

    /**
     * 把 SVG path 的 d 字符串采样成折线（flattenPath）。
     * 贝塞尔按弦长自适应分段（默认每 4 单位一段）。
     */
    fun flatten(d: String, step: Float = 4f): FloatArray {
        val tk = TOKEN.findAll(d).map { it.value }.toList()
        val out = ArrayList<Float>(4096)
        var i = 0
        var cmd = ' '
        var ox = 0f; var oy = 0f
        var sx = 0f; var sy = 0f

        fun num() = tk[i++].toFloat()
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            val n = max(2, ceil(hypot(x1 - x0, y1 - y0) / step).toInt())
            for (k in 1..n) {
                val f = k.toFloat() / n
                out.add(x0 + (x1 - x0) * f); out.add(y0 + (y1 - y0) * f)
            }
        }
        fun quad(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) {
            val n = max(2, ceil((hypot(cx - x0, cy - y0) + hypot(x1 - cx, y1 - cy)) / step).toInt())
            for (k in 1..n) {
                val f = k.toFloat() / n; val g = 1f - f
                out.add(g * g * x0 + 2f * g * f * cx + f * f * x1)
                out.add(g * g * y0 + 2f * g * f * cy + f * f * y1)
            }
        }
        fun cubic(x0: Float, y0: Float, a: Float, b: Float, c: Float, e: Float, x1: Float, y1: Float) {
            val len = hypot(a - x0, b - y0) + hypot(c - a, e - b) + hypot(x1 - c, y1 - e)
            val n = max(2, ceil(len / step).toInt())
            for (k in 1..n) {
                val f = k.toFloat() / n; val g = 1f - f
                out.add(g * g * g * x0 + 3f * g * g * f * a + 3f * g * f * f * c + f * f * f * x1)
                out.add(g * g * g * y0 + 3f * g * g * f * b + 3f * g * f * f * e + f * f * f * y1)
            }
        }

        while (i < tk.size) {
            val tok = tk[i]
            if (tok.isNotEmpty() && tok[0].isLetter()) {
                cmd = tok[0].uppercaseChar()
                i++
                if (cmd == 'Z') {
                    // 与源码一致：已在起点时不重复补线，避免 polarRing/Span 多算一个点
                    if (hypot(sx - ox, sy - oy) > 0.01f) line(ox, oy, sx, sy)
                    ox = sx; oy = sy
                }
                continue
            }
            when (cmd) {
                'M' -> { ox = num(); oy = num(); sx = ox; sy = oy; out.add(ox); out.add(oy); cmd = 'L' }
                'L' -> { val x = num(); val y = num(); line(ox, oy, x, y); ox = x; oy = y }
                'Q' -> {
                    val cx = num(); val cy = num(); val x = num(); val y = num()
                    quad(ox, oy, cx, cy, x, y); ox = x; oy = y
                }
                'C' -> {
                    val a = num(); val b = num(); val c = num(); val e = num(); val x = num(); val y = num()
                    cubic(ox, oy, a, b, c, e, x, y); ox = x; oy = y
                }
                else -> i++
            }
        }
        return out.toFloatArray()
    }

    /**
     * 极坐标轮廓（polarRing）：从圆心打 n 条射线取最远交点。
     * 是"眼睛被身体包住"的几何基础，也是 overlay 环形插值的目标形状。
     * 注意它是凸包近似 —— 只用于轮廓计算，不用于静态身体绘制。
     */
    fun polarRing(pts: FloatArray, r: Float, n: Int = 96): FloatArray {
        val cnt = pts.size / 2
        val out = FloatArray(n * 2)
        for (t in 0 until n) {
            val a = (t.toFloat() / n) * TWO_PI
            val cx0 = cos(a); val sy0 = sin(a)
            var best = 0f
            for (l in 0 until cnt) {
                val lx = pts[l * 2] - r; val ly = pts[l * 2 + 1] - r
                val j = (l + 1) % cnt
                val nx = pts[j * 2] - r; val ny = pts[j * 2 + 1] - r
                val det = (nx - lx) * sy0 - (ny - ly) * cx0
                if (abs(det) < 1e-9f) continue
                val k = (lx * sy0 - ly * cx0) / -det
                if (k < 0f || k > 1f) continue
                val v = (lx + (nx - lx) * k) * cx0 + (ly + (ny - ly) * k) * sy0
                if (v > best) best = v
            }
            out[t * 2] = r + cx0 * best
            out[t * 2 + 1] = r + sy0 * best
        }
        return out
    }

    /**
     * 水平扫描线（buildSpan / spanPoly）：给定 y 求多边形左右边界。
     * 眼睛每帧用它把自己夹回身体内部 —— 三条硬规则之一。
     */
    class Span(pts: FloatArray, private val r: Float, private val buckets: Int = 160) {
        private val top: Float
        private val spanH: Float
        private val left = FloatArray(buckets)
        private val right = FloatArray(buckets)

        init {
            var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (i in 0 until pts.size / 2) {
                val y = pts[i * 2 + 1]
                if (y < t) t = y
                if (y > b) b = y
            }
            top = t; spanH = max(b - t, 1e-3f)
            val cnt = pts.size / 2
            for (m in 0 until buckets) {
                val f = top + spanH * (m + 0.5f) / buckets
                var hi = -Float.MAX_VALUE; var lo = Float.MAX_VALUE
                for (k in 0 until cnt) {
                    val j = (k + 1) % cnt
                    val ax = pts[k * 2]; val ay = pts[k * 2 + 1]
                    val bx = pts[j * 2]; val by = pts[j * 2 + 1]
                    if ((ay <= f) == (by <= f)) continue
                    val x = ax + (bx - ax) * (f - ay) / (by - ay)
                    if (x <= r) { if (x > hi) hi = x } else if (x < lo) lo = x
                }
                left[m] = if (hi.isFinite()) hi else r
                right[m] = if (lo.isFinite()) lo else r
            }
        }

        private fun idx(y: Float): Pair<Int, Float> {
            val f = ((y - top) / spanH * buckets - 0.5f).clamp(0f, buckets - 1f)
            val k = f.toInt()
            return k to (f - k)
        }

        fun left(y: Float): Float {
            val (k, w) = idx(y); val b = min(k + 1, buckets - 1)
            return left[k] + (left[b] - left[k]) * w
        }

        fun right(y: Float): Float {
            val (k, w) = idx(y); val b = min(k + 1, buckets - 1)
            return right[k] + (right[b] - right[k]) * w
        }
    }

    /** Catmull-Rom 转三次贝塞尔，闭合（closedSpline）。用于动态重建轮廓。 */
    fun closedSpline(pts: FloatArray, out: Path): Path {
        val n = pts.size / 2
        out.reset()
        if (n < 3) return out
        out.moveTo(pts[0], pts[1])
        for (s in 0 until n) {
            val r = (s - 1 + n) % n; val o = (s + 1) % n; val l = (s + 2) % n
            out.cubicTo(
                pts[s * 2] + (pts[o * 2] - pts[r * 2]) / 6f,
                pts[s * 2 + 1] + (pts[o * 2 + 1] - pts[r * 2 + 1]) / 6f,
                pts[o * 2] - (pts[l * 2] - pts[s * 2]) / 6f,
                pts[o * 2 + 1] - (pts[l * 2 + 1] - pts[s * 2 + 1]) / 6f,
                pts[o * 2], pts[o * 2 + 1]
            )
        }
        out.close()
        return out
    }

    /**
     * 把 SVG d 字符串直接解析成 android.graphics.Path（保留贝塞尔本身）。
     * 静态身体必须用它而不是 polarRing+closedSpline 重建 ——
     * polarRing 是凸包近似，wedge/leaf 这类凹形会被撑胖。
     */
    fun parseD(d: String, out: Path): Path {
        val tk = TOKEN.findAll(d).map { it.value }.toList()
        out.reset()
        var i = 0
        var cmd = ' '
        var ox = 0f; var oy = 0f
        var sx = 0f; var sy = 0f
        fun num() = tk[i++].toFloat()
        while (i < tk.size) {
            val tok = tk[i]
            if (tok.isNotEmpty() && tok[0].isLetter()) {
                cmd = tok[0].uppercaseChar()
                i++
                if (cmd == 'Z') {
                    if (hypot(sx - ox, sy - oy) > 0.01f) out.lineTo(sx, sy)
                    ox = sx; oy = sy
                    out.close()
                }
                continue
            }
            when (cmd) {
                'M' -> { ox = num(); oy = num(); sx = ox; sy = oy; out.moveTo(ox, oy); cmd = 'L' }
                'L' -> { val x = num(); val y = num(); out.lineTo(x, y); ox = x; oy = y }
                'Q' -> {
                    val cx = num(); val cy = num(); val x = num(); val y = num()
                    out.quadTo(cx, cy, x, y); ox = x; oy = y
                }
                'C' -> {
                    val a = num(); val b = num(); val c = num(); val e = num(); val x = num(); val y = num()
                    out.cubicTo(a, b, c, e, x, y); ox = x; oy = y
                }
                else -> i++
            }
        }
        return out
    }

    /** 折线 → Path，重用已有对象避免分配。 */
    fun polyToPath(pts: FloatArray, out: Path): Path {
        out.reset()
        val n = pts.size / 2
        if (n < 2) return out
        out.moveTo(pts[0], pts[1])
        for (i in 1 until n) out.lineTo(pts[i * 2], pts[i * 2 + 1])
        out.close()
        return out
    }

    /** 顶点级插值，写入复用缓冲区。 */
    fun lerpPolyInto(dst: FloatArray, a: FloatArray, b: FloatArray, t: Float) {
        val n = min(dst.size, min(a.size, b.size))
        for (i in 0 until n) dst[i] = a[i] + (b[i] - a[i]) * t
    }

    fun centroidX(pts: FloatArray): Float {
        var s = 0f
        for (i in 0 until pts.size / 2) s += pts[i * 2]
        return s / (pts.size / 2)
    }

    fun centroidY(pts: FloatArray): Float {
        var s = 0f
        for (i in 0 until pts.size / 2) s += pts[i * 2 + 1]
        return s / (pts.size / 2)
    }
}
