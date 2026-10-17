package com.luaforge.studio.utils

import android.content.Context
import androidx.annotation.Keep
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

@Keep
object OkHttpUtil {

    data class HttpResponse(
        val code: Int,
        val content: String,
        val cookie: String,
        val headers: Map<String, List<String>>
    )

    @Volatile
    private var client: OkHttpClient? = null

    /** 构建 [client] 时 [NetworkProxy] 的版本号,用于判断代理变更后是否需要重建。 */
    @Volatile
    private var clientProxyRevision: Int = -1

    // 初始化OkHttpClient(双重检查锁,避免并发下重复创建)
    private fun getClient(): OkHttpClient {
        // 代理配置变更后必须重建:OkHttp 的 proxy 是建 client 时固定的,不是逐请求读取
        val revision = NetworkProxy.revision
        client?.let { if (clientProxyRevision == revision) return it }
        synchronized(this) {
            client?.let { if (clientProxyRevision == revision) return it }
            val builder = OkHttpClient.Builder()
                // 网络请求拦截:所有 okhttp.* 调用在发出前需用户确认
                .addInterceptor(NetworkInterceptor())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
            // 用户在「安全、网络与日志」里配置了代理时才走代理,否则直连
            NetworkProxy.current()?.let { builder.proxy(it.toJavaProxy()) }
            val created = builder.build()
            client = created
            clientProxyRevision = revision
            return created
        }
    }

    // ========== GET请求 ==========

    @JvmStatic
    fun get(
        context: Context,
        url: String,
        cookie: String? = null,
        charset: String? = null,
        headers: Map<String, String>? = null
    ): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .applyCommonHeaders(cookie, charset, headers)
            .build()
        return execute(request, charset)
    }

    // ========== POST请求(表单提交) ==========

    @JvmStatic
    fun post(
        context: Context,
        url: String,
        formData: Map<String, String>? = null,  // 允许空表
        cookie: String? = null,
        charset: String? = null,
        headers: Map<String, String>? = null
    ): HttpResponse {
        val formBody = FormBody.Builder().apply {
            formData?.forEach { (key, value) -> add(key, value) }
        }.build()

        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .applyCommonHeaders(cookie, charset, headers)
            .build()
        return execute(request, charset)
    }

    // ========== 文件上传 ==========

    @JvmStatic
    fun upload(
        context: Context,
        url: String,
        filePath: String,
        cookie: String? = null,
        headers: Map<String, String>? = null,
        fieldName: String = "file",
        extraParams: Map<String, String>? = null
    ): HttpResponse {
        val file = File(filePath)
        if (!file.exists()) {
            return HttpResponse(-1, "Error: File not found", "", emptyMap())
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fieldName,
                file.name,
                file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            )
            .apply {
                extraParams?.forEach { (key, value) -> addFormDataPart(key, value) }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .post(multipart)
            .applyCommonHeaders(cookie, null, headers)
            .build()
        return execute(request, null)
    }

    // ========== 文件下载 ==========

    @JvmStatic
    fun download(
        context: Context,
        url: String,
        savePath: String,
        cookie: String? = null,
        headers: Map<String, String>? = null
    ): HttpResponse {
        return try {
            val request = Request.Builder()
                .url(url)
                .applyCommonHeaders(cookie, null, headers)
                .build()

            getClient().newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val file = File(savePath)
                    file.parentFile?.mkdirs()
                    response.body.byteStream().use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    buildResponse(response, "Download success")
                } else {
                    buildResponse(response, "Download failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            HttpResponse(-1, "Error: ${e.message}", "", emptyMap())
        }
    }

    // ========== 辅助方法 ==========

    /** 为请求统一附加自定义请求头、Cookie 与字符集声明。 */
    private fun Request.Builder.applyCommonHeaders(
        cookie: String?,
        charset: String?,
        headers: Map<String, String>?
    ): Request.Builder {
        headers?.forEach { (key, value) -> addHeader(key, value) }
        cookie?.let { addHeader("Cookie", it) }
        charset?.let { addHeader("Accept-Charset", it) }
        return this
    }

    /** 执行请求并读取响应体(同步);任何异常都转成 code = -1 的失败结果。 */
    private fun execute(request: Request, charset: String?): HttpResponse {
        return try {
            // use{} 及时关闭响应体,避免连接泄漏
            getClient().newCall(request).execute().use { response ->
                val content = readBody(response, charset)
                buildResponse(response, content)
            }
        } catch (e: Exception) {
            HttpResponse(-1, "Error: ${e.message}", "", emptyMap())
        }
    }

    /** 按指定字符集读取响应体内容;charset 为空时交给 OkHttp 依据响应头解码。 */
    private fun readBody(response: Response, charset: String?): String {
        return if (charset != null) {
            response.body.bytes().toString(charset(charset))
        } else {
            response.body.string()
        }
    }

    /** 从响应中提取状态码、Cookie 与响应头,组装成统一结果。 */
    private fun buildResponse(response: Response, content: String): HttpResponse {
        return HttpResponse(
            response.code,
            content,
            response.headers("Set-Cookie").joinToString("; "),
            response.headers.toMultimap()
        )
    }

    private fun charset(charsetName: String): java.nio.charset.Charset {
        return try {
            java.nio.charset.Charset.forName(charsetName)
        } catch (e: Exception) {
            java.nio.charset.StandardCharsets.UTF_8
        }
    }
}
