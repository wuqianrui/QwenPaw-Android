package com.qwenpaw.chat.network

import android.util.Log
import com.google.gson.Gson
import com.qwenpaw.chat.model.ChatRequest
import com.qwenpaw.chat.model.ChatResponse
import com.qwenpaw.chat.model.TextContent
import com.qwenpaw.chat.model.TurnUsageResponse
import com.slientwhale.library.core.widget.xcrash.XCrashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import xcrash.XCrash
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SSEClient(
    private val baseUrl: String,
    private val agentId: String,
    private val authToken: String? = null
) {
    private val gson = Gson()

    suspend fun sendMessage(request: ChatRequest): Flow<SSEEvent> = flow {
        val url = URL("$baseUrl/api/console/chat")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Agent-Id", agentId)
            connection.setRequestProperty("Accept", "text/event-stream")

            if (!authToken.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $authToken")
            }

            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Connection", "keep-alive")
            // 等效于 curl 的 --no-buffer:禁止 gzip 压缩
            // (HttpURLConnection 的透明解压会攒数据缓冲整段流),保证 SSE 事件动态实时返回
            connection.setRequestProperty("Accept-Encoding", "identity")

            connection.connectTimeout = 30 * 1000
            // 只要服务端没关闭连接，客户端就永远阻塞在 read()/readLine()
            connection.readTimeout = 0

            val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
            writer.write(gson.toJson(request))
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorReader = BufferedReader(InputStreamReader(connection.errorStream, "UTF-8"))
                val errorBody = errorReader.readText()
                errorReader.close()
                emit(SSEEvent.Error("HTTP $responseCode: $errorBody"))
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            // 本次请求的流式累积文本:in_progress 增量逐段拼接,InProgress 事件携带到目前为止的完整文本供气泡打字机式展示
            val streamed = StringBuilder()
            var line: String?

            while (currentCoroutineContext().isActive) {
                line = reader.readLine()
                if (line == null) break

                // 回复数据处理，turn_usage(调用量)数据格式只有 2.0 版本支持
                if (line.startsWith("data: ")) {
                    val data = line.substring(6).trim()
                    if (data.isNotEmpty()) {
                        try {
                            val response = gson.fromJson(data, ChatResponse::class.java)
                            /* 2.0 版本的 turn_usage 调用量数据，解析后以事件抛出供 UI 展示，不写入数据库，
                               具体格式参考 docs/qwenpaw/02 response.md ### 调用量返回 */
                            if (response.type?.equals("turn_usage") == true) {
                                val turnUsage = gson.fromJson(data, TurnUsageResponse::class.java)
                                emit(SSEEvent.TurnUsage(turnUsage))
                            } else if(response != null && response.status?.isNotEmpty() == true){
                                val event = when (response.status) {
                                    "created" -> SSEEvent.Created(response)
                                    "in_progress" -> {
                                        val text = mergeStreamText(streamed, extractStreamChunk(response))
                                        SSEEvent.InProgress(response, text)
                                    }
                                    "completed" -> {
                                        // response 级完成事件携带完整 output 时直接覆盖;
                                        // message 级完成事件无 output(文本在顶层 content),保留已累积的流式文本
                                        val fullText = extractText(response)
                                        val text = fullText.ifEmpty { streamed.toString() }
                                        streamed.setLength(0)
                                        streamed.append(text)
                                        SSEEvent.Completed(response, text)
                                    }
                                    "failed" -> {
                                        val errorMsg = response.error?.message ?: "Unknown error"
                                        SSEEvent.Error(errorMsg)
                                    }
                                    else -> SSEEvent.Unknown(response)
                                }
                                emit(event)
                            } else {
                                Log.d("empty response",data )
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Log.e(this@SSEClient.javaClass.name,"SSEClient request",e)
                            XCrashUtils.reportCaughtException(e)
                            emit(SSEEvent.Error("Parse error: ${e.message}"))
                        }
                    }
                }
            }

            reader.close()

        } catch (e: Exception) {
            e.printStackTrace()
            emit(SSEEvent.Error("Connection error: ${e.message}"))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** 提取完成事件 output 里的完整文本(response 级完成事件使用) */
    private fun extractText(response: ChatResponse): String {
        val sb = StringBuilder()
        if(response.output?.isNotEmpty() == true ){
            response.output.forEach { message ->
                message.content?.forEach { content ->
                    if (content.type == "text") {
                        sb.append(content.text.orEmpty())
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * 提取流式事件携带的文本片段,兼容两种推送方式:
     * - message 级事件(object=message):增量在顶层 content[].delta,text 为空
     * - response 级事件(object=response):累积文本在 output[].content[].text
     * 优先取 delta,取不到再取 text
     */
    private fun extractStreamChunk(response: ChatResponse): String {
        val sb = StringBuilder()
        fun takeContents(contents: List<TextContent>?) {
            contents?.forEach { content ->
                if (content.type == "text") {
                    val chunk = (content.delta as? String)?.takeIf { it.isNotEmpty() }
                        ?: content.text.orEmpty()
                    sb.append(chunk)
                }
            }
        }
        takeContents(response.content)
        response.output?.forEach { takeContents(it.content) }
        return sb.toString()
    }

    /**
     * 合并流式片段并返回到目前为止的完整文本:
     * - 新片段以已累积文本为前缀 → 服务端推送的是「到目前为止的完整文本」,直接覆盖
     * - 否则视为增量片段,追加到尾部
     */
    private fun mergeStreamText(streamed: StringBuilder, chunk: String): String {
        if (chunk.isNotEmpty()) {
            if (chunk.startsWith(streamed.toString())) {
                streamed.setLength(0)
                streamed.append(chunk)
            } else {
                streamed.append(chunk)
            }
        }
        return streamed.toString()
    }
}

sealed class SSEEvent {
    data class Created(val response: ChatResponse) : SSEEvent()
    data class InProgress(val response: ChatResponse, val text: String) : SSEEvent()
    data class Completed(val response: ChatResponse, val text: String) : SSEEvent()
    data class TurnUsage(val usage: TurnUsageResponse) : SSEEvent()
    data class Error(val message: String) : SSEEvent()
    data class Unknown(val response: ChatResponse) : SSEEvent()
}