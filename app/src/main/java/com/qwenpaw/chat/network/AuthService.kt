package com.qwenpaw.chat.network

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class AuthService {

    private val gson = Gson()

    data class LoginResponse(
        val token: String?,
        val username: String?,
        val detail: String?
    )

    suspend fun login(baseUrl: String, username: String, password: String): Result<LoginResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/auth/login")
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.doInput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val requestBody = "{\"username\":\"$username\",\"password\":\"$password\",\"expires_in\":0}"

                val outputStream = connection.outputStream
                val writer = OutputStreamWriter(outputStream, "UTF-8")
                writer.write(requestBody)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                val response = StringBuilder()

                if (responseCode >= 200 && responseCode < 300) {
                    val inputStream = connection.inputStream
                    val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val loginResponse = gson.fromJson(response.toString(), LoginResponse::class.java)
                    if (loginResponse.token != null) {
                        Result.success(loginResponse)
                    } else {
                        Result.failure(Exception(loginResponse.detail ?: "Login failed"))
                    }
                } else {
                    val inputStream = connection.errorStream
                    if (inputStream != null) {
                        val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            response.append(line)
                        }
                        reader.close()
                    }
                    Result.failure(Exception("HTTP $responseCode: ${response.toString()}"))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}