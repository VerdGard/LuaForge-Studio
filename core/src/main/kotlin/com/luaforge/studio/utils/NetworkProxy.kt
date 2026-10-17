package com.luaforge.studio.utils

import androidx.annotation.Keep
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * 网络代理配置(core 模块)。
 *
 * 这是**默认关闭**的全局配置:未设置或格式非法时 [current] 返回 null,
 * 所有网络客户端(OkHttp / HttpURLConnection)按直连处理,行为与旧版一致。
 *
 * 只有 IDE(app 模块)会依据设置项调用 [configure];用户项目打包出的 APK
 * 不包含设置界面,也不会调用 [configure],因此打包后永远直连。
 *
 * 全仓仅在进程内保存一份,由各网络客户端在**构建 OkHttpClient 时**读取一次,
 * 因此修改代理后需要让对应客户端重建(见 [revision])。
 */
@Keep
object NetworkProxy {

    /** 代理协议。 */
    @Keep
    enum class Type { HTTP, SOCKS }

    /**
     * 一份已解析的代理配置。
     *
     * @param host 代理主机(IP 或域名)
     * @param port 端口,1..65535
     * @param type HTTP 或 SOCKS
     */
    @Keep
    data class Config(val host: String, val port: Int, val type: Type) {

        /** 转成 java.net.Proxy,供 OkHttp / HttpURLConnection 使用。 */
        fun toJavaProxy(): Proxy = Proxy(
            if (type == Type.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress(host, port)
        )

        /** 形如 `192.168.10.245:7890`,仅用于日志与界面回显。 */
        fun display(): String = "$host:$port"
    }

    @Volatile
    private var config: Config? = null

    /** 每次配置变化自增,供缓存了客户端的调用方判断是否需要重建。 */
    @Volatile
    var revision: Int = 0
        private set

    /** 当前生效的代理;null 表示直连。 */
    @JvmStatic
    fun current(): Config? = config

    /** 是否配置了代理。 */
    @JvmStatic
    fun isEnabled(): Boolean = config != null

    /**
     * 设置代理。
     *
     * @param raw 用户输入的代理地址,接受以下几种写法(大小写不敏感):
     *            `host:port`、`http://host:port`、`socks://host:port`、
     *            `socks5://host:port`;传入空白表示**关闭代理**。
     * @return 解析结果;返回 null 表示输入为空(已关闭)或格式非法(保持原状)。
     *
     * 格式非法时**不修改现有配置**,避免用户输入到一半(例如只敲了 "192.168")
     * 就把正在使用的代理清掉。
     */
    @JvmStatic
    fun configure(raw: String?): Config? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            clear()
            return null
        }
        val parsed = parse(text) ?: return current()
        if (parsed != config) {
            config = parsed
            revision++
        }
        return parsed
    }

    /** 关闭代理,恢复直连。 */
    @JvmStatic
    fun clear() {
        if (config != null) {
            config = null
            revision++
        }
    }

    /**
     * 解析代理地址。
     *
     * 允许省略协议(`host:port` 按 HTTP 代理处理),也允许带协议前缀。
     * 主机名允许字母、数字、点、连字符与下划线;端口必须落在 1..65535。
     */
    @JvmStatic
    fun parse(raw: String?): Config? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        var rest = text
        var type = Type.HTTP
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = rest.substring(0, schemeEnd).lowercase()
            type = when (scheme) {
                "http", "https" -> Type.HTTP
                "socks", "socks5", "socks4" -> Type.SOCKS
                else -> return null
            }
            rest = rest.substring(schemeEnd + 3)
        }

        // 去掉可能存在的路径与末尾斜杠
        rest = rest.substringBefore('/').substringBefore('?').trim()
        if (rest.isEmpty()) return null

        // 支持 user:pass@host:port 形式,但这里不保存凭据(OkHttp 需要额外的 Authenticator),
        // 直接跳过凭据段以免把密码当成主机名解析。
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)

        val colon = rest.lastIndexOf(':')
        if (colon <= 0 || colon == rest.length - 1) return null

        val host = rest.substring(0, colon).trim()
        val port = rest.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        if (!HOST_PATTERN.matches(host)) return null

        return Config(host, port, type)
    }

    /** 主机名合法字符集:字母、数字、点、连字符、下划线(允许 IPv6 方括号写法)。 */
    private val HOST_PATTERN = Regex("""^[A-Za-z0-9._\-]+$""")

    /** 把 `URI` 的 host 拆出来,便于日志定位;解析失败返回 null。 */
    @JvmStatic
    fun hostOf(url: String?): String? = try {
        if (url == null) null else URI(url).host
    } catch (_: Throwable) {
        null
    }
}
