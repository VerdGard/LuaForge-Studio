package com.luaforge.studio.utils

import androidx.annotation.Keep
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException

/**
 * OkHttp 网络拦截器(core 模块)。
 *
 * 在请求真正发出前交给 [NetworkGate] 审批;用户拒绝时抛出 [IOException] 中止请求。
 * 闸门未启用时几乎零开销地直接放行。
 */
@Keep
class NetworkInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!NetworkGate.isActive) return chain.proceed(request)

        val url = request.url.toString()
        if (!NetworkGate.isRemote(url)) return chain.proceed(request)

        val headers = LinkedHashMap<String, String>()
        try {
            val names = request.headers.names()
            for (name in names) {
                headers[name] = request.headers[name].orEmpty()
            }
        } catch (_: Throwable) {
        }

        val allowed = NetworkGate.allow(
            url,
            request.method,
            headers,
            bodyPreview(request),
            request.body?.contentType()?.toString(),
            "okhttp"
        )
        if (!allowed) throw IOException("网络请求已被用户拒绝: $url")
        return chain.proceed(request)
    }

    /**
     * 尽可能给出请求体预览。
     *
     * 只有在长度已知且较小时才读取:multipart / 流式请求体是一次性的,
     * 预先写进 Buffer 会把内容抽干导致请求体为空。
     */
    private fun bodyPreview(request: Request): String? {
        val body = request.body ?: return null
        val length = body.contentLength()
        if (length <= 0L || length > MAX_PREVIEW_BYTES) return null

        val type = body.contentType()?.toString()?.lowercase().orEmpty()
        val textual = type.isEmpty() ||
            type.startsWith("text/") ||
            type.contains("json") ||
            type.contains("xml") ||
            type.contains("x-www-form-urlencoded") ||
            type.contains("javascript")
        if (!textual) return null

        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val bytes = buffer.readByteArray(length)
            String(bytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /** 请求体预览上限 32KB,避免把大 body 读进内存。 */
        private const val MAX_PREVIEW_BYTES = 32L * 1024
    }
}
