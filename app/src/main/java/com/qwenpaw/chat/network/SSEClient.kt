package com.qwenpaw.chat.network

import com.google.gson.Gson
import com.qwenpaw.chat.model.ChatRequest
import com.qwenpaw.chat.model.ChatResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

            connection.connectTimeout = 30000
            connection.readTimeout = 6 * 60 *1000

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
            val buffer = StringBuilder()
            var line: String?

            while (currentCoroutineContext().isActive) {
                line = reader.readLine()
                if (line == null) break

                if (line.startsWith("data: ")) {
                    val data = line.substring(6).trim()
                    if (data.isNotEmpty()) {
                        try {
                            val response = gson.fromJson(data, ChatResponse::class.java)
                            val event = when (response.status) {
                                "created" -> SSEEvent.Created(response)
                                "in_progress" -> {
                                    val text = extractText(response)
                                    SSEEvent.InProgress(response, text)
                                }
                                "completed" -> {
                                    val text = extractText(response)
                                    SSEEvent.Completed(response, text)
                                }
                                "failed" -> {
                                    val errorMsg = response.error?.message ?: "Unknown error"
                                    SSEEvent.Error(errorMsg)
                                }
                                else -> SSEEvent.Unknown(response)
                            }
                            emit(event)
                        } catch (e: Exception) {
                            emit(SSEEvent.Error("Parse error: ${e.message}"))
                        }
                    }
                }
            }

            reader.close()

        } catch (e: Exception) {
            emit(SSEEvent.Error("Connection error: ${e.message}"))
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun extractText(response: ChatResponse): String {
        val sb = StringBuilder()
        response.output?.forEach { message ->
            message.content?.forEach { content ->
                if (content.type == "text") {
                    sb.append(content.text)
                }
            }
        }
        return sb.toString()
    }
}

sealed class SSEEvent {
    data class Created(val response: ChatResponse) : SSEEvent()
    data class InProgress(val response: ChatResponse, val text: String) : SSEEvent()
    data class Completed(val response: ChatResponse, val text: String) : SSEEvent()
    data class Error(val message: String) : SSEEvent()
    data class Unknown(val response: ChatResponse) : SSEEvent()
}