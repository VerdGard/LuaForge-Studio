package com.luaforge.studio.utils

import android.os.Looper
import androidx.annotation.Keep

/**
 * 网络请求拦截闸门(core 模块)。
 *
 * 这是一个**默认关闭**的钩子:只有的 app(IDE)模块在设置中打开开关并安装
 * [DecisionHandler] 后才会生效。用户项目打包出的 APK 只包含 core 模块,
 * 从不安装处理器,因此打包后的应用不会拦截任何请求。
 *
 * 所有 hook 点(OkHttp / HttpURLConnection)在发起真正的网络 IO 之前
 * 调用 [allow]:返回 false 表示用户拒绝了该请求,调用方必须中止。
 */
@Keep
object NetworkGate {

    /** 一次网络请求的快照,用于向用户展示与记录。 */
    @Keep
    data class Request(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val bodyPreview: String?,
        val contentType: String?,
        val source: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 决策回调。实现方(app 模块)负责弹出对话框并在用户做出选择后返回。
     *
     * 注意:回调会在**发起请求的那个后台线程**上被调用,实现方需要自行阻塞等待,
     * 并保证最终一定返回(超时也应返回),否则请求线程会一直挂起。
     */
    interface DecisionHandler {
        /** @return true 允许请求继续,false 拒绝。 */
        fun onRequest(request: Request): Boolean
    }

    @Volatile
    private var handler: DecisionHandler? = null

    @Volatile
    private var enabled: Boolean = false

    /** 是否已安装决策处理器(用户 APK 中始终为 false)。 */
    val installed: Boolean get() = handler != null

    /** 安装/卸载决策处理器。 */
    fun install(handler: DecisionHandler?) {
        this.handler = handler
    }

    @JvmStatic
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabled

    /** 只有开关打开且处理器已安装时才真正拦截。 */
    val isActive: Boolean get() = enabled && handler != null

    /** 供 Java 侧读取 [isActive]。 */
    @JvmStatic
    fun isActiveState(): Boolean = isActive

    /** 仅 http/https 需要审批,file/asset/content 等本地协议直接放行。 */
    @JvmStatic
    fun isRemote(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /**
     * 请求审批入口,供各 hook 点调用。
     *
     * @return true 允许继续;false 表示用户拒绝,调用方必须中止请求。
     */
    @JvmStatic
    fun allow(
        url: String?,
        method: String?,
        headers: Map<String, String>?,
        body: String?,
        contentType: String?,
        source: String?
    ): Boolean {
        if (!isActive) return true
        val target = url ?: return true
        if (!isRemote(target)) return true
        val current = handler ?: return true

        // 主线程不能阻塞等待对话框:那会直接 ANR/死锁。
        // 这类调用(例如 UI 线程直接发起的请求)只能记日志后放行,
        // 需要拦截的请求请在后台线程发起。
        if (Looper.myLooper() === Looper.getMainLooper()) {
            RuntimeLog.log("NetworkGate", "主线程发起的请求无法阻塞确认,已放行: $target")
            return true
        }

        return try {
            current.onRequest(
                Request(
                    url = target,
                    method = (method ?: "GET").uppercase(),
                    headers = headers ?: emptyMap(),
                    bodyPreview = body,
                    contentType = contentType,
                    source = source ?: "unknown"
                )
            )
        } catch (t: Throwable) {
            // 决策环节出问题不能把应用卡死:记日志后放行
            RuntimeLog.error("NetworkGate", "网络拦截决策异常,已放行: $target", t)
            true
        }
    }
}
