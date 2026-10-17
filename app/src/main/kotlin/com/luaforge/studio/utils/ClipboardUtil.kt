package com.luaforge.studio.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板写入。
 *
 * Compose 的 `LocalClipboardManager` 已被弃用,替代者 `LocalClipboard`
 * 是 suspend 接口(`setClipEntry` 需要协程)。但本项目里绝大多数复制动作
 * 发生在 `onClick` 这类**同步**闭包中,仅为复制一段文本而引入协程作用域
 * 并不划算,也容易在组合期拿到已失效的 scope。
 *
 * 因此这里直接走 Android 原生 [ClipboardManager]:同步、无协程要求、
 * 在所有 API 级别行为一致,同时彻底避开弃用 API。
 */
object ClipboardUtil {

    /**
     * 把 [text] 写入系统剪贴板。
     *
     * @param label 剪贴板内容的描述(部分 ROM 的悬浮提示会显示)
     * @return 写入成功返回 true;取不到系统服务时返回 false
     */
    @JvmStatic
    @JvmOverloads
    fun copy(context: Context, text: CharSequence, label: String = "LuaForge-Studio"): Boolean {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return try {
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        } catch (t: Throwable) {
            // 个别 ROM 在后台/受限场景会拒绝写入,复制失败不应中断交互
            false
        }
    }
}
