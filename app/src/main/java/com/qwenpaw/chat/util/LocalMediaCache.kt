package com.qwenpaw.chat.util

import android.content.Context
import java.io.File

/**
 * 已上传图片的本地缓存
 *
 * 服务端 media 目录不提供 HTTP 直接访问(/media/<filename> 返回 SPA 页面),
 * 历史消息里的 image_url 无法回源加载。因此上传成功后把文件以服务端文件名
 * (<hash>_<原文件名>)留一份在 filesDir/media 下,加载历史时按同名回查渲染;
 * 未命中(如换设备/他人上传)则仅展示占位图。
 */
object LocalMediaCache {

    fun mediaDir(context: Context): File =
        File(context.filesDir, "media").apply { mkdirs() }

    /** 服务端 url → 文件名(末段 <hash>_<原文件名>) */
    fun basenameOf(serverUrl: String): String = serverUrl.substringAfterLast('/')

    /** 查询服务端路径对应的本地缓存文件,未缓存返回 null */
    fun resolve(context: Context, serverUrl: String): String? {
        val file = File(mediaDir(context), basenameOf(serverUrl))
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }
}
