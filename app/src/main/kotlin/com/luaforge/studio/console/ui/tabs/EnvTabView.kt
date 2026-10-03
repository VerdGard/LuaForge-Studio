package com.luaforge.studio.console.ui.tabs

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.luaforge.studio.console.core.FileStateTracker
import com.luaforge.studio.console.core.SessionManager
import com.luaforge.studio.console.env.DexLibraries
import com.luaforge.studio.console.env.LuaEnvironment
import com.luaforge.studio.console.env.ModuleTracker
import com.luaforge.studio.console.output.OutputManager
import com.luaforge.studio.console.ui.ConsoleTheme
import com.luaforge.studio.console.ui.ExpandableCard
import com.luaforge.studio.console.ui.dp

/**
 * 环境页:四类别折叠卡——环境信息 / Lua 模块 / 原生库 / Java 类库。
 * 手机窄屏优先:键值对 label/value 上下堆叠(不用固定宽度左键列),长文本统一省略号截断。
 */
class EnvTabView(context: Context) : ScrollView(context) {

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(12))
    }

    init {
        setBackgroundColor(ConsoleTheme.surface)
        addView(content)
    }

    /** 页签展示时刷新。 */
    fun refresh() {
        content.removeAllViews()
        val file = OutputManager.currentFile
        // 一次取全部再分组:原先调两次 luaLibs 会各自复制一遍列表
        val libs = ModuleTracker.luaLibs(file)
        val luaMods = libs.filter { !it.native }
        val natives = libs.filter { it.native }
        val dexLibs = DexLibraries.scan(SessionManager.current?.luaDir)

        // 1. 环境信息(键值对折叠卡,默认展开;贴顶不加卡间距)
        val envCard = ExpandableCard(context, "环境信息").apply { setExpanded(true) }
        envCard.addBody(kvRow("Lua 版本", LuaEnvironment.versionLabel()))
        envCard.addBody(kvRow("JIT", if (LuaEnvironment.jit) "启用" else "未启用"))
        envCard.addBody(kvRow("当前文件", FileStateTracker.relativePath.ifBlank { "(无)" }))
        envCard.addBody(kvRow("绑定布局", bindLayoutLabel()))
        addCard(envCard, 0)

        // 2. Lua 模块(自定义 lua 文件模块)
        content.addView(categoryTitle("Lua 模块 · ${luaMods.size}"))
        if (luaMods.isEmpty()) content.addView(emptyHint("(无)"))
        for (m in luaMods) addCard(moduleCard(m))

        // 3. 原生库(全部函数来自 [C] 的 require 模块)
        content.addView(categoryTitle("原生库 · ${natives.size}"))
        if (natives.isEmpty()) content.addView(emptyHint("(无)"))
        for (m in natives) addCard(moduleCard(m))

        // 4. Java 类库(仅项目 libs/ 下 .dex 文件,数量为 dex 文件数)
        content.addView(categoryTitle("Java 类库 · ${dexLibs.size}"))
        if (dexLibs.isEmpty()) content.addView(emptyHint("(无)"))
        for (d in dexLibs) addCard(dexCard(d))
    }

    /** 折叠卡入列:类别卡片统一 dp(4) 上间距;环境信息卡传 0 保持贴顶。 */
    private fun addCard(card: ExpandableCard, topMargin: Int = context.dp(4)) {
        content.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { this.topMargin = topMargin }
        )
    }

    /** Lua/原生模块卡:标题为模块名,卡内每行一个「函数名(参数个数)」。 */
    private fun moduleCard(m: ModuleTracker.LuaLib): ExpandableCard {
        val card = ExpandableCard(context, m.module)
        if (m.funcs.isEmpty()) {
            card.addBody(sigLine("(无函数)"))
        } else {
            for ((fn, np) in m.funcs) {
                card.addBody(sigLine("$fn(${if (np < 0) "?" else np})"))
            }
        }
        return card
    }

    /** Java 类库卡:标题「dex 文件名 · N 类」,卡内类名行 + 缩进的方法签名行。 */
    private fun dexCard(d: DexLibraries.DexLib): ExpandableCard {
        val card = ExpandableCard(context, "${d.dexFile} · ${d.classes.size} 类")
        for (cl in d.classes) {
            card.addBody(classNameLine(cl.className))
            for (sig in cl.methods) card.addBody(methodLine(sig))
        }
        return card
    }

    /** 绑定布局三态文案:ALY → ./相对路径(layout.aly);内联布局 / 无布局 → 原标签。 */
    private fun bindLayoutLabel(): String =
        if (FileStateTracker.layout == FileStateTracker.Layout.ALY) {
            "./" + FileStateTracker.alyRelativePath.trimStart('/')
        } else FileStateTracker.layout.label

    /** 键值行:窄屏下 label(小字) 与 value(正文) 上下堆叠,各自单行/多行省略号截断。 */
    private fun kvRow(key: String, value: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(4), 0, context.dp(4))
            addView(
                TextView(context).apply {
                    text = key
                    textSize = 11f
                    setTextColor(ConsoleTheme.onSurfaceVariant)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
            addView(
                TextView(context).apply {
                    text = value
                    textSize = 13f
                    setTextColor(ConsoleTheme.onSurface)
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.1f) // 只读属性:须用 setLineSpacing(extra, mult)
                    setPadding(0, context.dp(1), 0, 0)
                }
            )
        }

    /** 分段标题:「名称 · 数量」。 */
    private fun categoryTitle(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ConsoleTheme.onSurfaceVariant)
            setPadding(0, context.dp(14), 0, context.dp(4))
        }

    /** 分段为空提示。 */
    private fun emptyHint(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(ConsoleTheme.onSurfaceVariant)
            setPadding(context.dp(4), context.dp(2), 0, context.dp(2))
        }

    /** 类名行:主色加粗,长类名最多两行。 */
    private fun classNameLine(text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ConsoleTheme.primary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, context.dp(6), 0, context.dp(2))
        }

    /** 等宽签名行基准:正文色、长签名省略号截断。 */
    private fun monoLine(text: String, maxLines: Int, padStart: Int, padV: Int): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(ConsoleTheme.onSurface)
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(padStart), context.dp(padV), 0, context.dp(padV))
        }

    /** 模块函数签名行(最多三行)。 */
    private fun sigLine(text: String): TextView = monoLine(text, 3, 0, 2)

    /** Java 方法签名行(缩进 10dp,最多两行)。 */
    private fun methodLine(text: String): TextView = monoLine(text, 2, 10, 1)
}
