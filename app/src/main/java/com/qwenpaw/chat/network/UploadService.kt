package com.qwenpaw.chat.network

import android.util.Log
import com.google.gson.Gson
import com.qwenpaw.chat.model.UploadResponse
import com.slientwhale.library.core.widget.xcrash.XCrashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 文件上传接口(参考 docs/qwenpaw/08 upload.md)
 *
 * POST /api/console/upload:multipart 表单上传,返回服务端存储路径,
 * 之后在 /api/console/chat 请求中以 {"type":"image","image_url":"<url>"} 引用。
 * 服务端 media 目录不提供 HTTP 直接访问,该 url 只能在对话请求中引用。
 */
class UploadService(
    private val baseUrl: String,
    private val authToken: String? = null
) {
    private val gson = Gson()

    /**
     * 上传本地文件。
     * 表单字段:file(二进制) / file_name / size,url 字段实测可缺省。
     * 成功返回 {"url":"<服务端绝对路径>","file_name":"...","size":N}
     */
    suspend fun upload(file: File, fileName: String): Result<UploadResponse> = withContext(Dispatchers.IO) {
        val connection = try {
            URL("$baseUrl/api/console/upload").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        try {
            val boundary = "----QwenPawBoundary${System.currentTimeMillis()}"
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15 * 1000
            connection.readTimeout = 60 * 1000
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (!authToken.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $authToken")
            }

            val out = BufferedOutputStream(connection.outputStream)

            fun writeField(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write(value.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray())
            }

            writeField("file_name", fileName)
            writeField("size", file.length().toString())

            out.write("--$boundary\r\n".toByteArray())
            out.write(
                "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray()
            )
            out.write("Content-Type: ${contentTypeOf(fileName)}\r\n\r\n".toByteArray())
            file.inputStream().use { input -> input.copyTo(out) }
            out.write("\r\n".toByteArray())
            out.write("--$boundary--\r\n".toByteArray())
            out.flush()
            out.close()

            val code = connection.responseCode
            if (code in 200..299) {
                val body = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
                Result.success(gson.fromJson(body, UploadResponse::class.java))
            } else {
                val errorBody = connection.errorStream?.let {
                    BufferedReader(InputStreamReader(it, "UTF-8")).use { stream -> stream.readText() }
                }.orEmpty()
                Result.failure(Exception("HTTP $code: $errorBody"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(this@UploadService.javaClass.name, "upload", e)
            XCrashUtils.reportCaughtException(e)
            Result.failure(e)
        } finally {
            connection.disconnect()
        }
    }

    /** 按扩展名推断文件 MIME 类型 */
    private fun contentTypeOf(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
}
