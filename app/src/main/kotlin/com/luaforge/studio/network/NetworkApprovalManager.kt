package com.luaforge.studio.network

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.luaforge.studio.R
import com.luaforge.studio.mcp.ActivityTracker
import com.luaforge.studio.ui.settings.SettingsManager
import com.luaforge.studio.utils.LogCatcher
import com.luaforge.studio.utils.NetworkGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 网络请求审批(app 模块)。
 *
 * 拦截能力本身在 core 模块的 [NetworkGate],[NetworkApprovalManager] 只在 IDE
 * 进程里安装决策器并弹出 MD3 对话框。用户项目打包出的 APK 只含 core 模块,
 * 从不安装本类,因此打包后的应用不会拦截任何请求。
 *
 * 决策发生在**发起请求的后台线程**上:这里用 CountDownLatch 阻塞该线程,
 * 把对话框投递到前台 Activity 的主线程,等用户点击后返回结果。
 * [NetworkGate] 已保证主线程调用直接放行,所以不会出现"主线程等自己"的死锁。
 */
object NetworkApprovalManager : NetworkGate.DecisionHandler {

    private const val TAG = "NetworkApproval"

    /** 等待用户确认的最长时间,超时按拒绝处理,避免请求线程永久挂起。 */
    private const val TIMEOUT_MS = 120_000L

    /** 同一时刻只弹一个对话框,其余请求排队等待。 */
    private val dialogLock = Any()

    @Volatile
    private var allowedHosts: Set<String> = emptySet()

    @Volatile
    private var blockedHosts: Set<String> = emptySet()

    @Volatile
    private var installed: Boolean = false

    /**
     * 在应用启动时装上决策器。幂等:重复调用不会重置闸门状态。
     *
     * 这里**不**调用 [NetworkGate.setEnabled],闸门默认就是关闭的;
     * 真正的开关只由 [applyPolicy] 依据用户设置同步,避免启动顺序
     * (SplashWelcome 先应用策略、MainActivity 后调用 install)把已开启的拦截关掉。
     */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        NetworkGate.install(this)
    }

    /**
     * 随设置同步策略。关闭开关时不卸载决策器,只把闸门置为未启用,
     * 这样 [NetworkGate.isActive] 为 false,所有请求直接放行。
     */
    fun applyPolicy(enabled: Boolean, allowed: Set<String>, blocked: Set<String>) {
        // 开关状态由 NetworkGate 持有,这里只同步主机名单
        this.allowedHosts = allowed.map { it.lowercase() }.toSet()
        this.blockedHosts = blocked.map { it.lowercase() }.toSet()
        NetworkGate.setEnabled(enabled)
    }

    // ------------------------------------------------------------------
    // 决策
    // ------------------------------------------------------------------

    override fun onRequest(request: NetworkGate.Request): Boolean {
        val host = hostOf(request.url) ?: return true

        // 已记住的选择直接生效,不打扰用户
        if (allowedHosts.contains(host)) return true
        if (blockedHosts.contains(host)) {
            LogCatcher.i(TAG, "已拒绝的主机,直接拦截: ${request.url}")
            return false
        }

        val activity = ActivityTracker.current()
        if (activity == null || activity.isFinishing) {
            // 没有可用的界面就无法征得同意;拦截生效时按拒绝处理(失败关闭)
            LogCatcher.w(TAG, "无前台界面,已拒绝: ${request.url}")
            return false
        }

        // 串行化:一次只展示一个对话框
        synchronized(dialogLock) {
            // 排队期间可能已被别的对话框记住
            if (allowedHosts.contains(host)) return true
            if (blockedHosts.contains(host)) return false

            return askUser(activity, request, host)
        }
    }

    private fun askUser(activity: Activity, request: NetworkGate.Request, host: String): Boolean {
        val latch = CountDownLatch(1)
        val allowed = booleanArrayOf(false)
        val remember = booleanArrayOf(false)

        try {
            activity.runOnUiThread {
                try {
                    showDialog(activity, request, host, latch, allowed, remember)
                } catch (t: Throwable) {
                    LogCatcher.e(TAG, "展示网络确认对话框失败,已拒绝: ${request.url}", t as? Exception)
                    allowed[0] = false
                    latch.countDown()
                }
            }
        } catch (t: Throwable) {
            LogCatcher.e(TAG, "投递对话框失败,已拒绝: ${request.url}", t as? Exception)
            return false
        }

        val answered = try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!answered) {
            LogCatcher.w(TAG, "等待用户确认超时,已拒绝: ${request.url}")
            return false
        }

        if (remember[0]) rememberHost(activity, host, allowed[0])
        LogCatcher.i(
            TAG,
            if (allowed[0]) "用户允许请求: ${request.url}" else "用户拒绝请求: ${request.url}"
        )
        return allowed[0]
    }

    /** 记住用户对某个主机的选择,下次不再询问。 */
    private fun rememberHost(context: Context, host: String, allow: Boolean) {
        try {
            val current = SettingsManager.currentSettings
            val updated = if (allow) {
                current.copy(
                    networkAllowedHosts = current.networkAllowedHosts + host,
                    networkBlockedHosts = current.networkBlockedHosts - host
                )
            } else {
                current.copy(
                    networkBlockedHosts = current.networkBlockedHosts + host,
                    networkAllowedHosts = current.networkAllowedHosts - host
                )
            }
            SettingsManager.updateSettings(updated)
            applyPolicy(
                updated.networkInterceptEnabled,
                updated.networkAllowedHosts,
                updated.networkBlockedHosts
            )
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SettingsManager.saveSettingsAsync(appContext)
                } catch (e: Exception) {
                    LogCatcher.e(TAG, "保存主机记忆失败: $host", e)
                }
            }
        } catch (e: Exception) {
            LogCatcher.e(TAG, "记住主机选择失败: $host", e)
        }
    }

    private fun hostOf(url: String): String? = try {
        val parsed = java.net.URI(url)
        parsed.host?.lowercase()
            ?: parsed.authority?.substringAfterLast('@')?.substringBefore(':')?.lowercase()
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // MD3 对话框
    // ------------------------------------------------------------------

    private fun showDialog(
        activity: Activity,
        request: NetworkGate.Request,
        host: String,
        latch: CountDownLatch,
        allowed: BooleanArray,
        remember: BooleanArray
    ) {
        val rememberBox = MaterialCheckBox(activity).apply {
            setText(R.string.settings_network_remember)
            setTextColor(onSurfaceVariant(activity))
        }
        val content = buildContentView(activity, request, host, rememberBox)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.settings_network_dialog_title)
            .setView(content)
            .setCancelable(true)
            .setPositiveButton(R.string.settings_network_dialog_allow) { _, _ ->
                allowed[0] = true
                remember[0] = rememberBox.isChecked
                latch.countDown()
            }
            .setNegativeButton(R.string.settings_network_dialog_deny) { _, _ ->
                allowed[0] = false
                remember[0] = rememberBox.isChecked
                latch.countDown()
            }
            .setOnCancelListener {
                // 点外部/返回键一律视为拒绝,保证等待方一定能被唤醒
                allowed[0] = false
                remember[0] = false
                latch.countDown()
            }
            .create()
            .apply { setCanceledOnTouchOutside(false) }
            .show()
    }

    /** 构造对话框内容:方法徽标 + 主机 + 完整 URL + 请求头 + 请求体 + 记住选择。 */
    private fun buildContentView(
        activity: Activity,
        request: NetworkGate.Request,
        host: String,
        rememberBox: MaterialCheckBox
    ): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 4), dp(activity, 24), 0)
        }

        val onSurface = onSurface(activity)
        val onSurfaceVariant = onSurfaceVariant(activity)
        val boxBackground = themeColor(
            root,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            Color.LTGRAY
        )
        val outline = themeColor(
            root,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.GRAY
        )
        val methodBackground = themeColor(
            root,
            com.google.android.material.R.attr.colorSecondaryContainer,
            Color.LTGRAY
        )
        val methodForeground = themeColor(
            root,
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            Color.BLACK
        )

        // 第一行:方法徽标 + 主机
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        headerRow.addView(
            TextView(activity).apply {
                text = request.method
                setTextColor(methodForeground)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 12f
                setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4))
                background = rounded(methodBackground, dp(activity, 999).toFloat(), 0, null)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        headerRow.addView(
            TextView(activity).apply {
                text = host
                setTextColor(onSurface)
                typeface = Typeface.DEFAULT_BOLD
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(activity, 10)
            }
        )
        root.addView(headerRow, matchWidth())

        // 完整 URL(可选中复制)
        root.addView(
            sectionLabel(
                activity,
                activity.getString(R.string.settings_network_url),
                onSurfaceVariant
            ),
            matchWidth(topMargin = 14)
        )
        root.addView(
            scrollableBox(
                activity,
                request.url,
                boxBackground,
                outline,
                onSurface,
                maxHeightDp = 96
            ),
            matchWidth(topMargin = 6)
        )

        // 请求头
        if (request.headers.isNotEmpty()) {
            root.addView(
                sectionLabel(activity, activity.getString(R.string.settings_network_headers), onSurfaceVariant),
                matchWidth(topMargin = 16)
            )
            root.addView(
                scrollableBox(
                    activity,
                    request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                    boxBackground,
                    outline,
                    onSurface,
                    maxHeightDp = 120
                ),
                matchWidth(topMargin = 6)
            )
        }

        // 请求体
        val body = request.bodyPreview
        if (!body.isNullOrBlank()) {
            root.addView(
                sectionLabel(activity, activity.getString(R.string.settings_network_body), onSurfaceVariant),
                matchWidth(topMargin = 16)
            )
            root.addView(
                scrollableBox(
                    activity,
                    truncate(body, MAX_BODY_CHARS),
                    boxBackground,
                    outline,
                    onSurface,
                    maxHeightDp = 180
                ),
                matchWidth(topMargin = 6)
            )
        }

        // 来源(okhttp / http ...),便于判断请求由谁发起
        TextView(activity).let { tv ->
            tv.text = activity.getString(R.string.settings_network_source) + ": " + request.source
            tv.setTextColor(onSurfaceVariant)
            tv.textSize = 11f
            root.addView(tv, matchWidth(topMargin = 12))
        }

        root.addView(rememberBox, matchWidth(topMargin = 8))
        return root
    }

    private fun sectionLabel(activity: Activity, text: String, color: Int): TextView =
        TextView(activity).apply {
            this.text = text
            setTextColor(color)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }

    /** 带圆角背景的等宽文本框,内容超高时内部滚动。 */
    private fun scrollableBox(
        activity: Activity,
        content: String,
        boxBackground: Int,
        stroke: Int,
        textColor: Int,
        maxHeightDp: Int
    ): View {
        val text = TextView(activity).apply {
            this.text = content
            setTextColor(textColor)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(
                dp(activity, 12),
                dp(activity, 10),
                dp(activity, 12),
                dp(activity, 10)
            )
        }
        return ScrollView(activity).apply {
            isFillViewport = false
            addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            this.background =
                rounded(boxBackground, dp(activity, 12).toFloat(), dp(activity, 1), stroke)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, maxHeightDp)
            )
        }
    }

    private fun rounded(fill: Int, radius: Float, strokeWidth: Int, stroke: Int?): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
            if (stroke != null && strokeWidth > 0) setStroke(strokeWidth, stroke)
        }

    private fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "\n... (已截断,共 ${text.length} 字符)"

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun matchWidth(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }

    private fun themeColor(view: View, attr: Int, fallback: Int): Int =
        MaterialColors.getColor(view, attr, fallback)

    private fun onSurface(context: Context): Int =
        MaterialColors.getColor(
            android.view.View(context),
            com.google.android.material.R.attr.colorOnSurface,
            Color.DKGRAY
        )

    private fun onSurfaceVariant(context: Context): Int =
        MaterialColors.getColor(
            android.view.View(context),
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.GRAY
        )

    private const val MAX_BODY_CHARS = 4000
}
