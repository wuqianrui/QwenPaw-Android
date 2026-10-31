package com.qwenpaw.chat.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** 本地图片解码:先读尺寸再按最长边采样,避免大图直接解码 OOM */
object ImageDecode {

    /**
     * 解码本地图片文件,最长边压到不超过 maxDim(不足原尺寸则原样返回)。
     * @return 解码失败或文件不存在时返回 null
     */
    fun decodeSampled(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return try {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
