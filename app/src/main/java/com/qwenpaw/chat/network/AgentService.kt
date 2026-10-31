package com.qwenpaw.chat.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qwenpaw.chat.model.AgentInfo
import com.qwenpaw.chat.model.AgentsResponse
import com.qwenpaw.chat.model.ChatHistoryResponse
import com.qwenpaw.chat.model.ChatSession
import com.qwenpaw.chat.model.HistoryMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent 相关接口(参考 docs/qwenpaw/05 agent.md、06 agent's chathistory.md、07 single chat history.md)
 * - GET /api/agents        获取当前环境所有 agents
 * - GET /api/chats         获取指定 agent 的会话列表(需 X-Agent-Id 请求头)
 * - GET /api/chats/{chatId} 获取单个会话的聊天历史(需 X-Agent-Id 请求头)
 * - PUT /api/chats/{chatId} 修改会话标题(需 X-Agent-Id 请求头)
 * - DELETE /api/chats/{chatId} 删除会话(需 X-Agent-Id 请求头)
 */
class AgentService(
    private val baseUrl: String,
    private val authToken: String? = null
) {

    private val gson = Gson()

    /** 获取当前环境所有 agents */
    suspend fun getAgents(): Result<List<AgentInfo>> = withContext(Dispatchers.IO) {
        try {
            val body = httpGet("$baseUrl/api/agents", agentId = null)
            val response = gson.fromJson(body, AgentsResponse::class.java)
            Result.success(response?.agents ?: emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /** 获取指定 agent 的会话列表 */
    suspend fun getChats(agentId: String): Result<List<ChatSession>> = withContext(Dispatchers.IO) {
        try {
            val body = httpGet("$baseUrl/api/chats", agentId = agentId)
            val type = object : TypeToken<List<ChatSession>>() {}.type
            val sessions: List<ChatSession> = gson.fromJson(body, type) ?: emptyList()
            Result.success(sessions)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /** 获取单个会话的聊天历史(docs/qwenpaw/07 single chat history.md) */
    suspend fun getChatHistory(agentId: String, chatId: String): Result<List<HistoryMessage>> = withContext(Dispatchers.IO) {
        try {
            val body = httpGet("$baseUrl/api/chats/$chatId", agentId = agentId)
            val response = gson.fromJson(body, ChatHistoryResponse::class.java)
            Result.success(response?.messages ?: emptyList())
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /** 修改会话标题(PUT /api/chats/{chatId}，请求体 {"name": "..."}) */
    suspend fun renameChat(agentId: String, chatId: String, newName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = "{\"name\":${gson.toJson(newName)}}"
            httpPut("$baseUrl/api/chats/$chatId", body = body, agentId = agentId)
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /** 删除会话(DELETE /api/chats/{chatId}，请求体 {"deleted": true}) */
    suspend fun deleteChat(agentId: String, chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            httpRequest(
                "$baseUrl/api/chats/$chatId",
                method = "DELETE",
                body = "{\"deleted\":true}",
                agentId = agentId
            )
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * 通用 GET 请求，非 2xx 时抛出异常(带响应体)。
     */
    private fun httpGet(urlString: String, agentId: String?): String {
        return httpRequest(urlString, method = "GET", body = null, agentId = agentId)
    }

    /**
     * 通用 PUT 请求(用于重命名会话等)。非 2xx 时抛出异常。
     */
    private fun httpPut(urlString: String, body: String, agentId: String?): String {
        return httpRequest(urlString, method = "PUT", body = body, agentId = agentId)
    }

    private fun httpRequest(urlString: String, method: String, body: String?, agentId: String?): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.setRequestProperty("Content-Type", "application/json")
            if (!authToken.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $authToken")
            }
            if (!agentId.isNullOrEmpty()) {
                connection.setRequestProperty("X-Agent-Id", agentId)
            }
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Length", body.toByteArray(Charsets.UTF_8).size.toString())
                val os: OutputStream = connection.outputStream
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
                os.close()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                val text = reader.readText()
                reader.close()
                text
            } else {
                val errorStream = connection.errorStream
                val errorBody = if (errorStream != null) {
                    val reader = BufferedReader(InputStreamReader(errorStream, "UTF-8"))
                    val text = reader.readText()
                    reader.close()
                    text
                } else ""
                throw Exception("HTTP $responseCode: $errorBody")
            }
        } finally {
            connection.disconnect()
        }
    }
}