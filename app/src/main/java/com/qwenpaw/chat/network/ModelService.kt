package com.qwenpaw.chat.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qwenpaw.chat.model.ModelProvider
import com.qwenpaw.chat.model.SwitchModelRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型相关接口(参考 docs/assets/api/model.md)
 * - GET /api/models 获取模型供应商列表
 * - PUT /api/models/active 切换当前激活模型
 */
class ModelService(
    private val baseUrl: String,
    private val authToken: String? = null
) {

    private val gson = Gson()

    suspend fun getModels(): Result<List<ModelProvider>> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/models")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Content-Type", "application/json")
                if (!authToken.isNullOrEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer $authToken")
                }
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
                    val body = reader.readText()
                    reader.close()
                    val type = object : TypeToken<List<ModelProvider>>() {}.type
                    val providers: List<ModelProvider> = gson.fromJson(body, type) ?: emptyList()
                    Result.success(providers)
                } else {
                    Result.failure(Exception(readError(connection, responseCode)))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun switchActiveModel(request: SwitchModelRequest): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/models/active")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.doInput = true
                connection.setRequestProperty("Content-Type", "application/json")
                if (!authToken.isNullOrEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer $authToken")
                }
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
                writer.write(gson.toJson(request))
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(readError(connection, responseCode)))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun readError(connection: HttpURLConnection, responseCode: Int): String {
        val errorStream = connection.errorStream ?: return "HTTP $responseCode"
        val reader = BufferedReader(InputStreamReader(errorStream, "UTF-8"))
        val body = reader.readText()
        reader.close()
        return "HTTP $responseCode: $body"
    }
}
