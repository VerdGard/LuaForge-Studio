@file:JvmName("LuaCompose")

package com.luaforge.studio.compose

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luajava.JavaFunction
import com.luajava.LuaException
import com.luajava.LuaObject
import com.luajava.LuaState

private const val TAG = "LuaCompose"

/**
 * 向 [L] 注册 Compose 相关的 Lua 全局函数:
 *
 * - `compose(table)`        -> 返回可挂载的 ComposeView
 * - `composeContent(table)` -> 直接作为 Activity 内容视图(等价于 activity.setContentView(view))
 *
 * Lua 无法直接调用任何 `@Composable`(其签名被编译器改写、必须在 composition 上下文中执行),
 * 因此这里的形态是:Lua 用 table 描述 UI 树 -> Kotlin 侧解析为不可变节点树 -> 由固定的
 * `Render` composable 递归分发到 Material3 组件。
 */
fun register(L: LuaState, context: Context) {
    val composeFn = object : JavaFunction(L) {
        override fun execute(): Int {
            val tree = L.getLuaObject(2)
            L.pushObjectValue(buildView(context, tree))
            return 1
        }
    }
    composeFn.register("compose")

    val contentFn = object : JavaFunction(L) {
        override fun execute(): Int {
            val tree = L.getLuaObject(2)
            val view = buildView(context, tree)
            val activity = context as? Activity
                ?: throw LuaException("composeContent: context 不是 Activity,无法作为内容视图")
            if (Looper.myLooper() == Looper.getMainLooper()) {
                activity.setContentView(view)
            } else {
                activity.runOnUiThread { activity.setContentView(view) }
            }
            return 0
        }
    }
    contentFn.register("composeContent")
}

/** 把 Lua 描述的 UI 树解析并渲染成一个 [ComposeView]。 */
fun buildView(context: Context, tree: LuaObject): View {
    val node = parseNode(tree)
    val view = ComposeView(context)
    view.setContent {
        MaterialTheme {
            Render(node)
        }
    }
    return view
}

// ---------------------------------------------------------------------------
// 节点模型:从 Lua table 一次性物化为不可变结构,避免后续重组时再读 LuaState。
// ---------------------------------------------------------------------------

private sealed interface CNode {
    val mods: Mods
}

private data class Mods(
    val padding: Int = 0,
    val fillMaxWidth: Boolean = false,
    val background: Long? = null,
    val corner: Int = 0,
    val widthDp: Int = 0,
    val heightDp: Int = 0,
)

private class CText(
    val text: String,
    val sizeSp: Int,
    val bold: Boolean,
    val color: Long?,
    val align: TextAlign?,
    override val mods: Mods,
) : CNode

private class CButton(
    val text: String,
    val onClick: LuaObject?,
    override val mods: Mods,
) : CNode

private class CGroup(
    val vertical: Boolean,
    val spacing: Int,
    val children: List<CNode>,
    override val mods: Mods,
) : CNode

private class CBox(val children: List<CNode>, override val mods: Mods) : CNode

private class CCard(
    val children: List<CNode>,
    val corner: Int,
    override val mods: Mods,
) : CNode

private class CSpacer(
    val spacerHeightDp: Int,
    val spacerWidthDp: Int,
    override val mods: Mods,
) : CNode

private class CDivider(override val mods: Mods) : CNode

// ---------------------------------------------------------------------------
// 解析 Lua table -> 节点树
// ---------------------------------------------------------------------------

private fun parseNode(t: LuaObject): CNode {
    if (!t.isTable) throw LuaException("compose: 节点必须是 table")

    val arr = t.asArray()
    if (arr.isEmpty() || arr[0] == null) {
        throw LuaException("compose: 节点缺少 [1] 标签,例如 { \"Text\", text = \"hi\" }")
    }
    val tag = arr[0].toString()

    val padding = intField(t, "padding", 0)
    val corner = intField(t, "corner", 0)
    val mods = Mods(
        padding = padding,
        fillMaxWidth = boolField(t, "fillMaxWidth", false),
        background = longField(t, "background"),
        corner = corner,
        widthDp = intField(t, "width", 0),
        heightDp = intField(t, "height", 0),
    )

    val children = ArrayList<CNode>()
    var positionalText: String? = null
    for (i in 1 until arr.size) {
        val e = arr[i]
        if (e is LuaObject) {
            children.add(parseNode(e))
        } else if (e is String && positionalText == null) {
            positionalText = e
        }
    }

    val onClick = t.getField("onClick").let { if (it.isFunction) it else null }

    return when (tag.lowercase()) {
        "column" -> CGroup(true, intField(t, "spacing", 0), children, mods)
        "row" -> CGroup(false, intField(t, "spacing", 0), children, mods)
        "text" -> CText(
            text = positionalText ?: strField(t, "text") ?: "",
            sizeSp = intField(t, "size", 14),
            bold = boolField(t, "bold", false),
            color = longField(t, "color"),
            align = alignOf(strField(t, "align")),
            mods = mods,
        )
        "button" -> CButton(
            text = positionalText ?: strField(t, "text") ?: "",
            onClick = onClick,
            mods = mods,
        )
        "box" -> CBox(children, mods)
        "card" -> CCard(children, if (corner > 0) corner else 12, mods)
        "spacer" -> CSpacer(intField(t, "h", 8), intField(t, "w", 0), mods)
        "divider" -> CDivider(mods)
        else -> throw LuaException("compose: 未知标签 '$tag'(支持 Column/Row/Box/Card/Text/Button/Spacer/Divider)")
    }
}

private fun intField(t: LuaObject, name: String, def: Int): Int {
    val f = t.getField(name)
    return if (f.isNumber) f.number.toInt() else def
}

private fun boolField(t: LuaObject, name: String, def: Boolean): Boolean {
    val f = t.getField(name)
    return if (f.isBoolean) f.boolean else def
}

private fun longField(t: LuaObject, name: String): Long? {
    val f = t.getField(name)
    return if (f.isNumber) f.number.toLong() else null
}

private fun strField(t: LuaObject, name: String): String? {
    val f = t.getField(name)
    return if (f.isString) f.string else null
}

private fun alignOf(s: String?): TextAlign? = when (s?.lowercase()) {
    "center" -> TextAlign.Center
    "end", "right" -> TextAlign.End
    "start", "left" -> TextAlign.Start
    else -> null
}

// ---------------------------------------------------------------------------
// 渲染:唯一的 @Composable 入口,按节点类型递归分发
// ---------------------------------------------------------------------------

@Composable
private fun Render(node: CNode) {
    when (node) {
        is CText -> Text(
            text = node.text,
            modifier = node.mods.toModifier(),
            color = node.color?.let { Color(it.toInt()) } ?: Color.Unspecified,
            fontSize = node.sizeSp.sp,
            fontWeight = if (node.bold) FontWeight.Bold else null,
            textAlign = node.align,
        )

        is CButton -> Button(
            onClick = { node.onClick?.let { invokeLua(it) } },
            modifier = node.mods.toModifier(),
        ) {
            Text(node.text)
        }

        is CGroup -> if (node.vertical) {
            Column(
                modifier = node.mods.toModifier(),
                verticalArrangement = Arrangement.spacedBy(node.spacing.dp),
            ) {
                node.children.forEach { Render(it) }
            }
        } else {
            Row(
                modifier = node.mods.toModifier(),
                horizontalArrangement = Arrangement.spacedBy(node.spacing.dp),
            ) {
                node.children.forEach { Render(it) }
            }
        }

        is CBox -> Box(modifier = node.mods.toModifier()) {
            node.children.forEach { Render(it) }
        }

        is CCard -> Card(
            modifier = node.mods.toModifier(),
            shape = RoundedCornerShape(node.corner.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                node.children.forEach { Render(it) }
            }
        }

        is CSpacer -> {
            var m: Modifier = Modifier
            if (node.spacerHeightDp > 0) m = m.height(node.spacerHeightDp.dp)
            if (node.spacerWidthDp > 0) m = m.width(node.spacerWidthDp.dp)
            Spacer(modifier = m)
        }

        is CDivider -> HorizontalDivider(modifier = node.mods.toModifier())
    }
}

private fun Mods.toModifier(): Modifier {
    var m: Modifier = Modifier
    if (padding > 0) m = m.padding(padding.dp)
    if (fillMaxWidth) m = m.fillMaxWidth()
    if (widthDp > 0) m = m.width(widthDp.dp)
    if (heightDp > 0) m = m.height(heightDp.dp)
    background?.let { argb ->
        m = m.background(Color(argb.toInt()), RoundedCornerShape(corner.dp))
    }
    return m
}

private fun invokeLua(fn: LuaObject) {
    try {
        fn.call()
    } catch (e: Throwable) {
        Log.e(TAG, "调用 Lua 回调失败", e)
    }
}
