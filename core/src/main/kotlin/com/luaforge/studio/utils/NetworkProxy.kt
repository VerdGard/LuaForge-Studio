package com.luaforge.studio.utils

import androidx.annotation.Keep
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 网络代理配置(core 模块)。
 *
 * ## 为什么装全局 ProxySelector,而不是逐个客户端设 proxy
 *
 * 旧实现只给 [OkHttpUtil] 与 `MavenDownloader` 两个自己 new 的 OkHttpClient
 * 设了代理。编辑器里的网络请求远不止这两处:Glide / Coil 会自建 client,
 * `HttpURLConnection` 走 JVM 默认选择器。逐个改既会漏,又会在后续新增依赖时
 * 悄悄回退成直连。
 *
 * 现在改为装一个**进程级** `ProxySelector.setDefault(...)`:
 * - OkHttp 未显式 `proxy(...)` 时,默认用 `ProxySelector.getDefault()`;
 * - `HttpURLConnection` 同样走它;
 * - 于是所有走 JVM 网络栈的请求(含第三方库自建的 client)受同一套规则约束。
 *
 * **明确的限制**:WebView 与 Lua 的 `socket` 模块各自用原生网络栈,不经过 JVM
 * 的 ProxySelector,本配置对它们无效。设置页文案里写明了这一点 —— 不假装
 * "接管全部流量"。
 *
 * ## 分流规则(优先级从高到低)
 *
 * 1. 回环地址(`localhost` / `127.*` / `::1`)**永远直连** —— 否则 MCP、协同
 *    这类本机互连会绕一圈自己的代理,表现为连不上或超时;
 * 2. [directHosts] 命中的主机**直连**;
 * 3. [proxyHosts] 非空时,**只有**命中它的主机走代理(其余直连);
 *    为空表示"除直连名单外全部走代理";
 * 4. 其余走代理。
 *
 * 主机规则支持 `example.com`(含子域)、`*.example.com`(仅子域)、`*`(全部)。
 *
 * 只有 IDE(app 模块)会依据设置项调用 [apply];用户项目打包出的 APK 不含
 * 设置界面,也不会调用它,因此打包后永远直连。
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

    /** 代理总开关。关闭时即使填了地址也一律直连。 */
    @Volatile
    private var enabled: Boolean = false

    /** 强制直连的主机规则(优先于 [proxyRules])。 */
    @Volatile
    private var directRules: List<String> = emptyList()

    /** 只对这些主机走代理;为空表示"其余全走代理"。 */
    @Volatile
    private var proxyRules: List<String> = emptyList()

    /** 每次配置变化自增,供缓存了客户端的调用方判断是否需要重建。 */
    @Volatile
    var revision: Int = 0
        private set

    /** 当前生效的代理;null 表示直连(开关关闭 / 未配置 / 地址非法)。 */
    @JvmStatic
    fun current(): Config? = if (enabled) config else null

    /** 是否真的在走代理。 */
    @JvmStatic
    fun isEnabled(): Boolean = enabled && config != null

    /** 原始配置(忽略开关),仅供设置界面回显。 */
    @JvmStatic
    fun rawConfig(): Config? = config

    /** 强制直连规则。 */
    @JvmStatic
    fun directHosts(): List<String> = directRules

    /** 只走代理的规则。 */
    @JvmStatic
    fun proxyHosts(): List<String> = proxyRules

    /**
     * 应用完整配置。任一项变化都会推进 [revision] 并重装全局选择器。
     *
     * @param raw         代理地址;空白表示未配置(界面保留开关状态用于回显)
     * @param switchOn    总开关
     * @param directHosts 强制直连的主机规则
     * @param proxyHosts  只对这些主机走代理;为空表示其余全走代理
     */
    @JvmStatic
    fun apply(
        raw: String?,
        switchOn: Boolean,
        directHosts: List<String>,
        proxyHosts: List<String>
    ) {
        val parsed = parse(raw)
        val d = normalizeHosts(directHosts)
        val p = normalizeHosts(proxyHosts)
        val changed = parsed != config ||
            switchOn != enabled ||
            d != directRules ||
            p != proxyRules
        config = parsed
        enabled = switchOn
        directRules = d
        proxyRules = p
        if (changed) revision++
        // 无论是否变化都装一次:首次启动时 JVM 默认选择器还未被替换,
        // 而"有配置但没装选择器"会让第三方库自建的 client 意外直连。
        installGlobalSelector()
    }

    /**
     * 兼容旧调用:只给代理地址,默认开启开关。
     *
     * 传空白表示关闭。行为与旧版一致(旧版"有地址就是在用代理")。
     */
    @JvmStatic
    fun configure(raw: String?): Config? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            apply(null, false, directRules, proxyRules)
            return null
        }
        val parsed = parse(text) ?: return config
        apply(text, true, directRules, proxyRules)
        return parsed
    }

    /** 关闭代理,恢复直连。 */
    @JvmStatic
    fun clear() = apply(null, false, emptyList(), emptyList())

    /**
     * 判断某个主机的请求是否需要走代理。
     *
     * @return 需要走代理时返回对应 [Proxy],否则 null(直连)
     */
    @JvmStatic
    fun proxyForHost(host: String?): Proxy? {
        val cfg = config ?: return null
        if (!enabled) return null
        val h = host?.trim()?.lowercase().orEmpty()
        if (h.isEmpty()) return null
        if (isLoopback(h)) return null
        if (matchesAny(h, directRules)) return null
        if (proxyRules.isNotEmpty() && !matchesAny(h, proxyRules)) return null
        return cfg.toJavaProxy()
    }

    /** 某个 URL 是否需要走代理(供日志与调试)。 */
    @JvmStatic
    fun proxyForUrl(url: String?): Proxy? = proxyForHost(hostOf(url))

    /**
     * 本项目的 ProxySelector 实例。
     *
     * 各 OkHttp 客户端显式传入它,可保证即使别处改写过 JVM 默认选择器,
     * 本项目规则依然生效。
     */
    @JvmStatic
    fun selector(): ProxySelector = LfSelector

    /**
     * 安装进程级默认选择器。
     *
     * 重复设置同一实例是幂等的,配置变更时直接重装即可。
     */
    @JvmStatic
    fun installGlobalSelector() {
        try {
            if (ProxySelector.getDefault() !== LfSelector) {
                ProxySelector.setDefault(LfSelector)
            }
        } catch (t: Throwable) {
            // 某些环境禁止改写默认选择器:退化为各客户端显式传入 selector()
        }
    }

    private object LfSelector : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            listOf(proxyForHost(uri?.host) ?: Proxy.NO_PROXY)

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // 失败重试交给调用方,这里不做处理
        }
    }

    /** 回环地址判断。 */
    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "[::1]" || host.startsWith("127.")

    private fun matchesAny(host: String, patterns: List<String>): Boolean {
        for (p in patterns) {
            if (matches(host, p)) return true
        }
        return false
    }

    /**
     * 单条规则匹配。
     *
     * - `*` 匹配全部;
     * - `*.example.com` 只匹配子域,不匹配 `example.com` 本身;
     * - `example.com` 匹配自身与所有子域。
     */
    private fun matches(host: String, pattern: String): Boolean {
        val p = pattern.trim().lowercase()
        if (p.isEmpty()) return false
        if (p == "*") return true
        if (p.startsWith("*.")) {
            val suffix = p.substring(1) // ".example.com"
            return host.length > suffix.length && host.endsWith(suffix)
        }
        val bare = if (p.startsWith(".")) p.substring(1) else p
        return host == bare || host.endsWith(".$bare")
    }

    /** 规则去空白、去重、保序。 */
    private fun normalizeHosts(input: List<String>): List<String> {
        val out = ArrayList<String>(input.size)
        for (raw in input) {
            val t = raw.trim()
            if (t.isNotEmpty() && !out.contains(t)) out.add(t)
        }
        return out
    }

    /**
     * 解析代理地址。
     *
     * 允许省略协议(`host:port` 按 HTTP 代理处理),也允许带协议前缀。
     * 主机名允许字母、数字、点、连字符、下划线与 IPv6 方括号;端口 1..65535。
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

        // 去掉可能存在的路径与查询串
        rest = rest.substringBefore('/').substringBefore('?').trim()
        if (rest.isEmpty()) return null

        // 支持 user:pass@host:port,但不保存凭据(OkHttp 需要额外的 Authenticator),
        // 直接跳过,避免把密码当成主机名解析。
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

    /** 主机名合法字符集(允许 IPv6 方括号写法)。 */
    private val HOST_PATTERN = Regex("""^[A-Za-z0-9._\-\[\]]+$""")

    /** 把 `URI` 的 host 拆出来,便于日志定位;解析失败返回 null。 */
    @JvmStatic
    fun hostOf(url: String?): String? = try {
        if (url == null) null else URI(url).host
    } catch (_: Throwable) {
        null
    }
}
