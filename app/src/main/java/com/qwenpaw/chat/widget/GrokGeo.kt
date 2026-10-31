package com.qwenpaw.chat.widget

import android.content.Context
import android.graphics.Path
import org.json.JSONObject

/**
 * 单个身形。构造时一次性完成全部昂贵计算：
 * flatten 采样、polarRing 轮廓、Span 扫描线、原始 d 的 Path 预解析。
 * 帧内只做查表，零解析。
 */
internal class GrokShape(
    val label: String,
    val d: String,
    val flat: FloatArray,
    val ring: FloatArray,
    val span: PathGeom.Span,
    /** 预解析的静态身体路径（保真贝塞尔，帧内直接 draw）。 */
    val bodyPath: Path,
    val faceX: Float, val faceY: Float,
    val faceSx: Float, val faceSy: Float, val faceEye: Float,
    /** uniformEyes 模式下左眼质心的额外 X 偏移（仅 wedge = -6，其余为 0）。 */
    val faceLeftDX: Float,
    val tiltScale: Float,
    val top: Float, val bottom: Float, val radius: Float
)

/**
 * 几何数据（assets/grok_geometry.json，由 export-geometry.mjs 导出）。
 * 25 个眼睛形状 × [左眼, 右眼]，每眼 48 点扁平存储。
 */
internal class GrokGeo(json: JSONObject) {
    val Re = json.getDouble("Re").toFloat()
    val g9e = json.getDouble("G9e").toFloat()
    val vbX = json.getJSONObject("viewBox").getDouble("minX").toFloat()
    val vbY = json.getJSONObject("viewBox").getDouble("minY").toFloat()
    val vbW = json.getJSONObject("viewBox").getDouble("width").toFloat()

    val eyeShapes: Array<Array<FloatArray>>

    val shapes = HashMap<String, GrokShape>()
    val shapeOrder = ArrayList<String>()

    init {
        val je = json.getJSONArray("eyes")
        eyeShapes = Array(je.length()) { idx ->
            val pair = je.getJSONArray(idx)
            Array(2) { e ->
                val poly = pair.getJSONArray(e)
                val n = poly.length()
                FloatArray(n * 2).also { buf ->
                    for (p in 0 until n) {
                        val pt = poly.getJSONArray(p)
                        buf[p * 2] = pt.getDouble(0).toFloat()
                        buf[p * 2 + 1] = pt.getDouble(1).toFloat()
                    }
                }
            }
        }

        val js = json.getJSONObject("shapes")
        for (name in js.keys()) {
            val o = js.getJSONObject(name)
            val d = o.getString("path")
            val flat = PathGeom.flatten(d)
            val face = o.getJSONObject("face")
            shapeOrder.add(name)
            shapes[name] = GrokShape(
                label = o.optString("label", name),
                d = d,
                flat = flat,
                ring = PathGeom.polarRing(flat, Re),
                span = PathGeom.Span(flat, Re),
                bodyPath = PathGeom.parseD(d, Path()),
                faceX = face.optDouble("x", 0.0).toFloat(),
                faceY = face.optDouble("y", 0.0).toFloat(),
                faceSx = face.optDouble("sx", 1.0).toFloat(),
                faceSy = face.optDouble("sy", 1.0).toFloat(),
                faceEye = face.optDouble("eye", 1.0).toFloat(),
                faceLeftDX = face.optDouble("leftDX", 0.0).toFloat(),
                tiltScale = o.optDouble("tiltScale", 1.0).toFloat(),
                top = o.optDouble("top", (Re - 60.0)).toFloat(),
                bottom = o.optDouble("bottom", (Re + 60.0)).toFloat(),
                radius = o.optDouble("radius", 60.0).toFloat()
            )
        }
    }

    companion object {
        fun load(ctx: Context, assetName: String = "grok_geometry.json"): GrokGeo =
            GrokGeo(JSONObject(ctx.assets.open(assetName).bufferedReader().use { it.readText() }))
    }
}
