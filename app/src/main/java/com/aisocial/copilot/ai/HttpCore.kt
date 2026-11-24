package com.aisocial.copilot.ai

import com.aisocial.copilot.core.NetResult
import com.aisocial.copilot.domain.model.ImagePayload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal data class AiRequest(
    val system: String,
    val user: String,
    val images: List<ImagePayload> = emptyList(),
    val temperature: Double = 0.9,
    val maxTokens: Int = 1600,
)

internal data class WireCall(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

/** Shared HTTP transport with human-readable Chinese error mapping. */
internal class HttpCore {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun post(call: WireCall, timeoutSeconds: Int): NetResult<String> {
        val request = try {
            Request.Builder()
                .url(call.url)
                .post(call.body.toRequestBody(JSON_MEDIA))
                .apply { call.headers.forEach { (name, value) -> header(name, value) } }
                .build()
        } catch (e: IllegalArgumentException) {
            return NetResult.Failure("接口地址不合法：${call.url}", e.message)
        } catch (e: Throwable) {
            return NetResult.Failure("构建请求失败：${e.message ?: "未知错误"}", e.message)
        }

        val httpCall = client.newCall(request)
        runCatching { httpCall.timeout().timeout(timeoutSeconds.toLong(), TimeUnit.SECONDS) }

        return try {
            httpCall.execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    !response.isSuccessful -> NetResult.Failure(
                        mapHttpError(response.code, text),
                        "HTTP ${response.code}: ${text.take(600)}"
                    )

                    text.isBlank() -> NetResult.Failure("模型返回了空内容，请重试。", "empty body")
                    else -> NetResult.Success(text)
                }
            }
        } catch (e: SocketTimeoutException) {
            NetResult.Failure("请求超时（${timeoutSeconds} 秒）。可以调大超时时间，或换一个更快的模型。", e.message)
        } catch (e: UnknownHostException) {
            NetResult.Failure("网络不可用或域名解析失败，请检查网络连接。", e.message)
        } catch (e: IOException) {
            NetResult.Failure("网络请求失败：${e.message ?: "未知错误"}", e.message)
        } catch (e: Throwable) {
            NetResult.Failure("请求发生异常：${e.message ?: "未知错误"}", e.message)
        }
    }

    fun get(url: String, headers: Map<String, String>, timeoutSeconds: Int): NetResult<String> {
        val request = try {
            Request.Builder()
                .url(url)
                .get()
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        } catch (e: IllegalArgumentException) {
            return NetResult.Failure("接口地址不合法：$url", e.message)
        }
        val call = client.newCall(request)
        runCatching { call.timeout().timeout(timeoutSeconds.toLong(), TimeUnit.SECONDS) }
        return try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    NetResult.Failure(
                        mapHttpError(response.code, text),
                        "HTTP ${response.code}: ${text.take(600)}"
                    )
                } else {
                    NetResult.Success(text)
                }
            }
        } catch (e: SocketTimeoutException) {
            NetResult.Failure("请求超时（${timeoutSeconds} 秒）。", e.message)
        } catch (e: IOException) {
            NetResult.Failure("网络请求失败：${e.message ?: "未知错误"}", e.message)
        } catch (e: Throwable) {
            NetResult.Failure("请求发生异常：${e.message ?: "未知错误"}", e.message)
        }
    }

    private fun mapHttpError(code: Int, body: String): String {
        val base = when (code) {
            400 -> "请求被拒绝（400）。可能是模型 ID 或参数不被支持。"
            401 -> "API Key 无效或已失效（401）。请在「设置 → 模型服务」里重新填写。"
            402 -> "账户余额不足（402）。请检查服务商账户。"
            403 -> "没有访问权限（403）。请检查 Key 权限或所在地区限制。"
            404 -> "接口或模型不存在（404）。请检查 Base URL 与模型 ID。"
            413 -> "请求内容过大（413）。请减少图片数量或聊天文本长度。"
            422 -> "参数不合法（422）。"
            429 -> "请求太频繁或额度不足（429）。请稍后再试。"
            in 500..599 -> "模型服务暂时不可用（$code）。请稍后再试。"
            else -> "请求失败（$code）。"
        }
        val serverMessage = extractServerMessage(body)
        return if (serverMessage.isNullOrBlank()) base else "$base\n服务端提示：$serverMessage"
    }

    private fun extractServerMessage(body: String): String? {
        if (body.isBlank()) return null
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (parsed != null) {
            val errorObject = runCatching { parsed["error"]?.jsonObject }.getOrNull()
            val nested = errorObject?.get("message")?.jsonPrimitive?.contentOrNull
            if (!nested.isNullOrBlank()) return nested.take(300)
            val plainError = runCatching { parsed["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (!plainError.isNullOrBlank()) return plainError.take(300)
            val message = runCatching { parsed["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (!message.isNullOrBlank()) return message.take(300)
        }
        return body.replace(Regex("\\s+"), " ").take(240).ifBlank { null }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
