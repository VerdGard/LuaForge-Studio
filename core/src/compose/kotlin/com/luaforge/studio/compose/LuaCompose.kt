@file:JvmName("LuaCompose")
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.luaforge.studio.compose

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.luajava.JavaFunction
import com.luajava.LuaObject
import com.luajava.LuaState

/**
 * Lua 侧 Jetpack Compose 运行时(随 core 一起编译,无其他分发方式)。
 *
 * ## 为什么不"反射调用 @Composable"
 *
 * `@Composable` 函数编译后签名被改写(注入 `Composer` / `$changed`),且必须在
 * composition 上下文里执行。反射直调需要自己维护 `currentComposer`、手工 start/end
 * group —— 那不是"打通底层",而是把编译器内部协议复制一份,任何 Compose 版本升级都会
 * 静默炸掉。所以这里走**语义等价**的路:
 *
 * 1. Lua 在**组合期**调用 `Column{...}` / `Text("hi")` 这类**普通函数**;
 * 2. 这些函数不渲染,只把调用登记成一条轻量记录(纯数据 + Lua 闭包引用);
 * 3. 由固定的 `@Composable` 展开器把记录逐条放进 Compose 自己的 SlotTable。
 *
 * 关键在于第 3 步:每条记录由**独立的 composable 调用**渲染,因此
 *
 * - Lua 里读 `s.value` 命中的是 `MutableState.value` 的真实 getter,快照系统把它记为
 *   **读观测**,状态变化只让读到它的那个 group 重组 —— 与手写 Kotlin Compose 一致;
 * - Material3 组件自身的动画/涟漪/焦点/`remember` 全部正常工作;
 * - 重组时重新执行 Lua 闭包,读到最新状态,不需要重建整棵树。
 *
 * ## 全局名与 `import` 的冲突(重要)
 *
 * 这些函数注册为**全局名**。`import "android.widget.*"` 的通配导入走 `_G` 的
 * `__index`,**只在键不存在时**才回填(`core/src/main/resources/lua/import.lua`),
 * 因此不会覆盖这里注册的 `Button` / `Switch` / `Card` 等。
 *
 * 但**显式** `import "android.widget.Button"` 会直接写 `_env[classname]`,
 * 于是原生类**覆盖**同名组件。遇到这种情况用命名空间表 `Compose.Button`,
 * 它始终指向 Compose 版本,不受 `import` 影响。
 */

// ---------------------------------------------------------------------------
// 节点模型
// ---------------------------------------------------------------------------

/** 一条待展开的组件调用。字段全是纯数据或 Lua 引用,不含任何 Compose 类型。 */
class LuaNode(
    val name: String,
    val opts: Map<String, Any?>,
    val content: LuaObject?
)

/**
 * 位置式 `remember` 槽位。
 *
 * 语义与 Compose 的位置记忆化一致:按**调用顺序**分配槽位,因此同一 group 内每次组合
 * 的调用顺序必须稳定(与手写 Compose 的约束相同)。子 group 用独立子表,父子各自
 * 复位游标,互不错位。
 */
class SlotStore {
    private val values = HashMap<Int, Any?>()
    private val children = HashMap<Int, SlotStore>()
    private var cursor = 0

    /**
     * 具名子槽位与位置子槽位**分开存**。
     *
     * 早期版本把名字哈希成负数塞进同一个表,但 `-hashCode - 1 == 0` 会在
     * `hashCode == -1` 时与索引 0 撞车,导致 Scaffold 的 topBar 和第一个内容节点
     * 共用同一个槽位表。两个 Map 从根上消除这个可能。
     */
    private val named = HashMap<String, SlotStore>()

    fun reset() {
        cursor = 0
    }

    fun childAt(index: Int): SlotStore = children.getOrPut(index) { SlotStore() }

    /** 具名子槽位:用于同一节点内多个内容槽(如 Scaffold 的 topBar / content)。 */
    fun namedChild(name: String): SlotStore = named.getOrPut(name) { SlotStore() }

    /*
     * 已知限制(不修,但记录在案):
     *
     * `children` 以节点下标为键,只增不减。普通容器的节点数由脚本静态决定,
     * 不会有问题;但 `ListItemContent` 用的是**懒加载条目的绝对下标**
     * (`store.childAt(index)`),因此长时间滚动大列表后,这个 Map 会随
     * 累计滚过的条目数增长(每个空 SlotStore 约 200 字节,百万条量级才到百 MB)。
     *
     * 为什么不在 reset 时裁剪:同一个 store 在一轮组合内可能被 reset 多次
     * (`Scaffold` 先 `namedChild("topBar")` 渲染顶栏,再 `SlotContent` 又 reset
     * 渲染内容),任何"轮首清空、轮末保留本轮用到的"方案都会在第二次 reset 时
     * 丢掉前一次收集的键,把 topBar 的槽位误删 —— 表现为顶栏状态随机丢失。
     * 正确做法是让懒加载条目用"可见窗口内的序号"做键,但那会改变 `remember`
     * 在滚动时的语义,属于行为变更而非修 bug,需单独评估。
     */
    fun nextSlot(factory: () -> Any?): Any? {
        val i = cursor++
        if (values.containsKey(i)) return values[i]
        val v = factory()
        values[i] = v
        return v
    }
}

/** 组合期上下文:节点收集器 + 槽位表。 */
private class ComposeCtx(val nodes: ArrayList<LuaNode>, val store: SlotStore)

private val tlCtx = ThreadLocal<ComposeCtx?>()

private const val TAG = "LuaCompose"

/** 桥版本号。控件集扩展后递增,便于用户脚本探测能力差异。 */
fun version(): String = "2.1"

// ---------------------------------------------------------------------------
// Lua 侧状态句柄
// ---------------------------------------------------------------------------

/**
 * Lua 侧可观察状态。
 *
 * - `s.value` 读写(走 javaGetter/javaSetter → getValue/setValue)
 * - `s:get()` / `s:set(v)`
 *
 * 读 `value` 发生在组合期时会被快照系统观测到,写入即触发对应 group 重组。
 */
class LuaComposeState(private val state: MutableState<Any?>) {
    fun getValue(): Any? = state.value

    fun setValue(v: Any?) {
        state.value = v
    }

    fun get(): Any? = state.value

    fun set(v: Any?) {
        state.value = v
    }

    override fun toString(): String = "state(${state.value})"
}

/** `RangeSlider` 用的区间状态:[getStart]/[getEnd] 读写都会触发重组。 */
class LuaRangeState(private val state: MutableState<ClosedFloatingPointRange<Float>>) {
    fun getStart(): Float = state.value.start

    fun setStart(v: Float) {
        state.value = v..state.value.endInclusive
    }

    fun getEnd(): Float = state.value.endInclusive

    fun setEnd(v: Float) {
        state.value = state.value.start..v
    }

    /** 整体读写:`r.value = 10..20` 需 Lua 侧用 `r:setRange(a, b)`。 */
    fun getRange(): List<Float> = listOf(state.value.start, state.value.endInclusive)

    fun setRange(a: Any?, b: Any?) {
        val s = floatOr(a, state.value.start)
        val e = floatOr(b, state.value.endInclusive)
        state.value = if (s <= e) s..e else e..s
    }

    fun toRange(): ClosedFloatingPointRange<Float> = state.value

    override fun toString(): String = "range(${state.value.start}..${state.value.endInclusive})"
}

// ---------------------------------------------------------------------------
// 注册
// ---------------------------------------------------------------------------

/**
 * 全部组件名。
 *
 * 渲染端的 `when (node.name)` 必须逐个实现——漏一个就会落到"未支持的组件"
 * 分支并渲染红色提示。注册靠这张表,**不靠手写 60 行 nodeLua()**,
 * 这样注册表与错误提示里的名单永远一致。
 */
private val NODE_NAMES = listOf(
    // 布局容器
    "Column", "Row", "Box", "FlowRow", "FlowColumn", "ScrollColumn", "ScrollRow",
    "Card", "ElevatedCard", "OutlinedCard", "Surface", "Scaffold",
    // 懒加载列表/网格
    "LazyColumn", "LazyRow", "LazyVerticalGrid", "LazyHorizontalGrid",
    // 文本与图形
    "Text", "Icon", "Image", "Spacer", "Divider", "VerticalDivider",
    "CircularProgressIndicator", "LinearProgressIndicator",
    "Badge", "BadgedBox",
    // 按钮族
    "Button", "TextButton", "OutlinedButton", "ElevatedButton", "FilledTonalButton",
    "IconButton", "FilledIconButton", "OutlinedIconButton",
    "FloatingActionButton", "SmallFloatingActionButton", "LargeFloatingActionButton",
    "ExtendedFloatingActionButton",
    // 选择与输入
    "Switch", "Checkbox", "RadioButton", "Slider", "RangeSlider",
    "TextField", "OutlinedTextField",
    // Chip
    "AssistChip", "FilterChip", "InputChip", "SuggestionChip",
    // 导航
    "TopAppBar", "CenterAlignedTopAppBar", "MediumTopAppBar", "LargeTopAppBar",
    "BottomAppBar", "NavigationBar", "NavigationBarItem",
    "NavigationRail", "NavigationRailItem",
    "TabRow", "ScrollableTabRow", "Tab",
    // 弹窗
    "AlertDialog", "Dialog",
    // 原生 View 互操作(能塞任何现有控件)
    "AndroidView",
    // 副作用
    "LaunchedEffect"
)

/**
 * 向 [L] 注册全部 Compose 全局函数,返回注册成功的函数个数。
 *
 * 单个函数注册失败只记日志,不抛异常(避免一个没装好的组件把整页拉起不来)。
 */
fun register(L: LuaState, context: Context): Int {
    var n = 0

    // 入口/状态/组件三类,统一在这里定义"函数体",再分别注册到全局名与 `Compose` 表。
    // 用同一份 Lambda 而不是让 Lua 侧转发全局名:`Compose` 表的存在意义就是在
    // 全局名被 `import` 覆盖时仍可用,若它去读全局名,就退化成同一个被覆盖的值了。
    val bodies = LinkedHashMap<String, Pair<Int, (LuaState) -> Unit>>()

    fun def(name: String, nres: Int, body: (LuaState) -> Unit) {
        bodies[name] = nres to body
    }

    // ---- 入口 ----
    def("compose", 1) { L2 ->
        val body = argFunction(L2, 2)
        L2.pushObjectValue(buildComposeView(context, body))
    }
    def("composeView", 1) { L2 ->
        val body = argFunction(L2, 2)
        L2.pushObjectValue(buildComposeView(context, body))
    }
    def("composeContent", 0) { L2 ->
        val body = argFunction(L2, 2)
        val view = buildComposeView(context, body)
        val act = context as? Activity
        if (act != null) runOnMain { act.setContentView(view) }
    }

    // ---- 状态 ----
    def("state", 1) { L2 ->
        val init = try {
            if (L2.getTop() >= 2) L2.toJavaObject(2) else null
        } catch (_: Exception) {
            null
        }
        L2.pushObjectValue(LuaComposeState(mutableStateOf(init)))
    }
    def("rangeState", 1) { L2 ->
        val a = try {
            if (L2.getTop() >= 2) L2.toJavaObject(2) else null
        } catch (_: Exception) {
            null
        }
        val b = try {
            if (L2.getTop() >= 3) L2.toJavaObject(3) else null
        } catch (_: Exception) {
            null
        }
        val start = floatOr(a, 0f)
        val end = floatOr(b, 1f)
        val r = if (start <= end) start..end else end..start
        L2.pushObjectValue(LuaRangeState(mutableStateOf(r)))
    }
    def("remember", 1) { L2 ->
        val factory = argFunction(L2, 2)
        val ctx = tlCtx.get()
        if (ctx == null || factory == null) {
            L2.pushObjectValue(if (factory == null) null else safeCall(factory))
        } else {
            L2.pushObjectValue(ctx.store.nextSlot { safeCall(factory) })
        }
    }

    // ---- 组件(统一走 collect) ----
    NODE_NAMES.forEach { name ->
        def(name, 0) { L2 -> collect(L2, name) }
    }

    // ---- 注册到全局名 ----
    bodies.forEach { (name, pair) ->
        val (nres, body) = pair
        try {
            val f = object : JavaFunction(L) {
                override fun execute(): Int {
                    body(L)
                    return nres
                }
            }
            f.register(name)
            n++
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "注册 $name 失败: ${t.message}", t)
        }
    }

    // ---- 注册到命名空间表 `Compose` ----
    // 显式 `import "android.widget.Button"` 会覆盖同名全局,但 `Compose.Button`
    // 始终指向 Compose 版本。
    val before = L.getTop()
    try {
        L.newTable()
        bodies.forEach { (name, pair) ->
            val (nres, body) = pair
            L.pushString(name)
            L.pushJavaFunction(object : JavaFunction(L) {
                override fun execute(): Int {
                    body(L)
                    return nres
                }
            })
            L.rawSet(-3)
        }
        L.setGlobal("Compose")
    } catch (t: Throwable) {
        L.setTop(before)
        android.util.Log.w(TAG, "注册 Compose 命名空间表失败(不影响全局名): ${t.message}")
    }

    return n
}

/**
 * 读取 Lua 实参并登记节点。
 *
 * 支持的调用形态(等价):
 * - `Column{ contentFn }`            函数即内容
 * - `Column({ content = contentFn })` 表 + content 字段
 * - `Text("hi")` / `Text("hi", {color = ...})`
 * - `Button({ text = "确定", onClick = fn })`
 *
 * 表里的**函数字段**会被保留为 Lua 引用(命名槽位),例如 `Scaffold` 的
 * `topBar` / `bottomBar` / `fab`。
 */
private fun collect(L: LuaState, name: String) {
    val ctx = tlCtx.get()
    if (ctx == null) {
        android.util.Log.w(TAG, "$name 在组合期之外被调用,已忽略(应在 compose 的 content 闭包里调用)")
        return
    }
    var opts: Map<String, Any?> = emptyMap()
    var content: LuaObject? = null
    val top = L.getTop()

    for (i in 2..top) {
        if (L.isNil(i)) continue
        val obj = L.getLuaObject(i)
        when {
            obj.isFunction -> if (content == null) content = obj

            obj.isTable -> {
                val m = readTable(L, obj)
                opts = opts + m
                val c = obj.getField("content")
                if (c != null && c.isFunction) content = c
            }

            else -> opts = opts + ("text" to L.toJavaObject(i))
        }
    }
    ctx.nodes.add(LuaNode(name, opts, content))
}

// ---------------------------------------------------------------------------
// Lua 值转换
// ---------------------------------------------------------------------------

private fun argFunction(L: LuaState, idx: Int): LuaObject? {
    if (L.getTop() < idx) return null
    val o = L.getLuaObject(idx)
    return if (o.isFunction) o else null
}

/** 把 Lua 表读成 Java Map(浅层;嵌套表/函数保留为 LuaObject)。 */
private fun readTable(L: LuaState, table: LuaObject): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    if (!table.isTable) return map
    try {
        table.push()
        val t = L.getTop()
        L.pushNil()
        while (L.next(t) != 0) {
            val k = try {
                L.toJavaObject(-2)
            } catch (_: Exception) {
                null
            }
            val v = try {
                L.toJavaObject(-1)
            } catch (_: Exception) {
                null
            }
            L.pop(1)
            // Lua 数字键经 toJavaObject 会变成 Double(1.0),必须归一化成 "1",
            // 否则数组表判定(allInt)与按下标取值都会失败。
            val key = when (k) {
                null -> null
                is Number -> {
                    val d = k.toDouble()
                    if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
                }
                else -> k.toString()
            }
            if (key != null) map[key] = v
        }
        L.pop(1)
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "读取组件属性表失败: ${t.message}", t)
    }
    return map
}

/** Lua 数组表 -> List;非数组表 -> Map;其余原样返回。 */
private fun toPlain(v: Any?): Any? {
    if (v !is LuaObject || !v.isTable) return v
    val m = readTable(v.getLuaState(), v)
    val keys = m.keys
    val allInt = keys.isNotEmpty() && keys.all { it.toIntOrNull() != null }
    return if (allInt) keys.mapNotNull { it.toIntOrNull() }.sorted().map { m[it.toString()] } else m
}

// ---------------------------------------------------------------------------
// 渲染
// ---------------------------------------------------------------------------

/**
 * 当这个 ComposeView 被当作 Activity 的内容视图时,按系统栏高度补 padding。
 *
 * API 35+ 起系统强制 edge-to-edge(`LuaActivity` 会调
 * `WindowCompat.setDecorFitsSystemWindows(window, false)`),届时窗口不再自动为
 * 状态栏/导航栏留出空间。`LuaActivity` 原本把 inset 处理绑在自己的根布局上,
 * 但 `composeContent(fn)` 与 `activity.setContentView(compose(fn))` 会**整体替换**
 * 那个根布局 —— inset 处理随之失效,Compose 内容被状态栏压住、底部被导航栏切掉,
 * 表现就是"布局显示不完整"。
 *
 * 只在两种情况下生效:
 * - API >= 35(更低版本窗口仍是 fit 模式,自己再加 padding 会重复留白);
 * - 本视图的直接父容器是 `android.R.id.content`(即确实是内容视图)。
 *   若它被嵌进用户自己的布局,则由用户布局负责 inset,这里不能重复补。
 */
private fun applyContentInsetsIfRoot(view: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val isRootContent = (v.parent as? View)?.id == android.R.id.content
        if (isRootContent) {
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        }
        insets
    }
}

/** 构建一个可挂载的 ComposeView。 */
private fun buildComposeView(context: Context, body: LuaObject?): ComposeView {
    val view = ComposeView(context)
    applyContentInsetsIfRoot(view)
    val rootStore = SlotStore()
    view.setContent {
        MaterialTheme {
            if (body == null) {
                Text("compose: 缺少内容函数", color = Color(0xFFB3261E))
            } else {
                LuaGroup(rootStore, body)
            }
        }
    }
    return view
}

/**
 * 渲染一个 group:执行 Lua 闭包收集节点,再把每条节点交给独立 composable 展开。
 *
 * `reset()` 在**每次**组合时复位游标,`children` 按位置复用 —— 这正是 Compose 位置
 * 记忆化的必要条件。先收集再渲染,保证父子 group 的上下文不互相污染。
 */
@Composable
private fun LuaGroup(store: SlotStore, body: LuaObject) {
    store.reset()
    val nodes = buildNodes(store) { body.call() }
    nodes.forEachIndexed { index, node ->
        key(index) {
            LuaNodeView(store.childAt(index), node)
        }
    }
}

/**
 * Row 的子节点渲染器。
 *
 * 除了 `weight`(`Modifier.weight` 只能在 RowScope 里取到),这里还必须处理
 * **scope-only 组件**:`NavigationBarItem` 声明为 `fun RowScope.NavigationBarItem(...)`,
 * 在 `LuaNodeView` 的 `when` 里(没有 RowScope 接收者)调用会报 `Unresolved reference`。
 * 所以这类组件只能在持有接收者的地方调用,由这里分派。
 */
@Composable
private fun RowScope.LuaChildrenRow(store: SlotStore, body: LuaObject?) {
    store.reset()
    if (body == null) return
    val nodes = buildNodes(store) { body.call() }
    nodes.forEachIndexed { index, item ->
        key(index) {
            val child = store.childAt(index)
            when (item.name) {
                "NavigationBarItem" -> NavigationBarItemScope(child, item)
                else -> {
                    val w = floatOf(item.opts["weight"], 0f)
                    LuaNodeView(child, item, if (w > 0f) Modifier.weight(w) else Modifier)
                }
            }
        }
    }
}

/**
 * `NavigationBarItem` 的实际调用点(RowScope 扩展)。
 *
 * `label` 是 `(@Composable () -> Unit)?`:必须显式声明类型,写成
 * `?.let { { Text(it) } }` 会产生非可组合 lambda 而编译失败。
 */
@Composable
private fun RowScope.NavigationBarItemScope(store: SlotStore, node: LuaNode) {
    val o = node.opts
    val labelText = o["label"]?.toString()
    val labelSlot: (@Composable () -> Unit)? =
        if (labelText.isNullOrBlank()) null else ({ Text(labelText) })
    NavigationBarItem(
        selected = boolOf(o["selected"]),
        onClick = { callLua(o["onClick"]) },
        icon = { Text(o["icon"]?.toString() ?: "") },
        modifier = modifierOf(o),
        enabled = boolOf(o["enabled"], true),
        label = labelSlot
    )
}

/**
 * Column 的子节点渲染器,规则同 [LuaChildrenRow]:
 * `NavigationRailItem` 是 `fun ColumnScope.NavigationRailItem(...)`,必须在此分派。
 */
@Composable
private fun ColumnScope.LuaChildrenColumn(store: SlotStore, body: LuaObject?) {
    store.reset()
    if (body == null) return
    val nodes = buildNodes(store) { body.call() }
    nodes.forEachIndexed { index, item ->
        key(index) {
            val child = store.childAt(index)
            when (item.name) {
                "NavigationRailItem" -> NavigationRailItemScope(child, item)
                else -> {
                    val w = floatOf(item.opts["weight"], 0f)
                    LuaNodeView(child, item, if (w > 0f) Modifier.weight(w) else Modifier)
                }
            }
        }
    }
}

/** `NavigationRailItem` 的实际调用点(ColumnScope 扩展)。 */
@Composable
private fun ColumnScope.NavigationRailItemScope(store: SlotStore, node: LuaNode) {
    val o = node.opts
    val labelText = o["label"]?.toString()
    val labelSlot: (@Composable () -> Unit)? =
        if (labelText.isNullOrBlank()) null else ({ Text(labelText) })
    NavigationRailItem(
        selected = boolOf(o["selected"]),
        onClick = { callLua(o["onClick"]) },
        icon = { Text(o["icon"]?.toString() ?: "") },
        modifier = modifierOf(o),
        enabled = boolOf(o["enabled"], true),
        label = labelSlot
    )
}

private inline fun buildNodes(store: SlotStore, crossinline exec: () -> Unit): List<LuaNode> {
    val out = ArrayList<LuaNode>(8)
    val prev = tlCtx.get()
    tlCtx.set(ComposeCtx(out, store))
    try {
        exec()
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Lua compose 闭包执行失败: ${t.message}", t)
        out.add(LuaNode("Text", mapOf("text" to ("compose 出错: " + (t.message ?: t.javaClass.simpleName))), null))
    } finally {
        // 必须 remove 而不是 set(prev):prev 通常就是 null,但 ThreadLocal 的
        // Entry 会把 key 强引用在 Thread 的 threadLocals 表里,value 也一并留着。
        // Lua 脚本常在后台线程回调(定时器、协程)里触发组合,这些线程可能是
        // 线程池的常驻线程 —— 只 set(null) 会让每条线程的 Entry 一直指向
        // 这个 ThreadLocal,而 ComposeCtx 里的 LuaNode(含 LuaObject、属性 Map)
        // 在异常路径下可能来不及被替换掉。remove() 明确释放 Entry。
        if (prev == null) tlCtx.remove() else tlCtx.set(prev)
    }
    return out
}

/** 懒加载/网格的单个 item:content 收到 `(item, index)`。 */
@Composable
private fun ListItemContent(store: SlotStore, body: LuaObject?, item: Any?, index: Int) {
    if (body == null) return
    val child = store.childAt(index)
    child.reset()
    val nodes = buildNodes(child) { body.call(item, index.toDouble()) }
    nodes.forEachIndexed { i, n ->
        key(i) { LuaNodeView(child.childAt(i), n) }
    }
}

/** 单个节点的展开:全项目唯一出现 `@Composable` 组件调用的地方。 */
@Composable
private fun LuaNodeView(store: SlotStore, node: LuaNode, extra: Modifier = Modifier) {
    val o = node.opts
    val body = node.content
    val ctx = LocalContext.current
    // extra 里可能带 weight(父作用域给的),必须在组件自己的 modifier 之前接上。
    // 不写成局部 @Composable 函数:局部可组合函数的支持依赖编译器插件版本,内联更稳。

    when (node.name) {
        // ---- 布局容器 ----
        "Column" -> Column(
            modifier = extra.then(modifierOf(o)),
            verticalArrangement = vArrangementOf(o),
            horizontalAlignment = hAlignmentOf(o)
        ) { LuaChildrenColumn(store, body) }

        "Row" -> Row(
            modifier = extra.then(modifierOf(o)),
            horizontalArrangement = hArrangementOf(o),
            verticalAlignment = vAlignmentOf(o)
        ) { LuaChildrenRow(store, body) }

        "Box" -> Box(
            modifier = extra.then(modifierOf(o)),
            contentAlignment = alignmentOf(o)
        ) { SlotContent(store, body, o) }

        "FlowRow" -> FlowRow(
            modifier = extra.then(modifierOf(o)),
            horizontalArrangement = hArrangementOf(o),
            verticalArrangement = vArrangementOf(o)
        ) { SlotContent(store, body, o) }

        "FlowColumn" -> FlowColumn(
            modifier = extra.then(modifierOf(o)),
            verticalArrangement = vArrangementOf(o),
            horizontalArrangement = hArrangementOf(o)
        ) { SlotContent(store, body, o) }

        "ScrollColumn" -> Column(
            modifier = extra.then(modifierOf(o)).verticalScroll(rememberScrollState()),
            verticalArrangement = vArrangementOf(o),
            horizontalAlignment = hAlignmentOf(o)
        ) { LuaChildrenColumn(store, body) }

        "ScrollRow" -> Row(
            modifier = extra.then(modifierOf(o)).horizontalScroll(rememberScrollState()),
            horizontalArrangement = hArrangementOf(o),
            verticalAlignment = vAlignmentOf(o)
        ) { LuaChildrenRow(store, body) }

        "Card" -> Card(modifier = extra.then(modifierOf(o)), shape = shapeOf(o)) { SlotContent(store, body, o) }

        "ElevatedCard" -> ElevatedCard(modifier = extra.then(modifierOf(o)), shape = shapeOf(o)) { SlotContent(store, body, o) }

        "OutlinedCard" -> OutlinedCard(modifier = extra.then(modifierOf(o)), shape = shapeOf(o)) { SlotContent(store, body, o) }

        "Surface" -> Surface(
            modifier = extra.then(modifierOf(o)),
            color = colorOf(o["background"]) ?: MaterialTheme.colorScheme.surface,
            shape = shapeOf(o)
        ) { SlotContent(store, body, o) }

        "Scaffold" -> Scaffold(
            modifier = extra.then(modifierOf(o)),
            topBar = {
                val bar = o["topBar"] as? LuaObject
                if (bar != null) LuaGroup(store.namedChild("topBar"), bar)
            },
            bottomBar = {
                val bar = o["bottomBar"] as? LuaObject
                if (bar != null) LuaGroup(store.namedChild("bottomBar"), bar)
            },
            floatingActionButton = {
                val fab = o["fab"] as? LuaObject ?: o["floatingActionButton"] as? LuaObject
                if (fab != null) LuaGroup(store.namedChild("fab"), fab)
            }
        ) { inner ->
            Box(modifier = Modifier.padding(inner)) {
                SlotContent(store, body, o)
            }
        }

        // ---- 懒加载 ----
        "LazyColumn" -> {
            val data = (toPlain(o["items"]) as? List<*>) ?: emptyList<Any?>()
            val count = if (o.containsKey("items")) data.size else intOf(o["count"], 0)
            LazyColumn(
                modifier = extra.then(modifierOf(o)),
                verticalArrangement = vArrangementOf(o),
                contentPadding = paddingOf(o)
            ) {
                items(count) { index ->
                    ListItemContent(store, body, data.getOrNull(index), index)
                }
            }
        }

        "LazyRow" -> {
            val data = (toPlain(o["items"]) as? List<*>) ?: emptyList<Any?>()
            val count = if (o.containsKey("items")) data.size else intOf(o["count"], 0)
            LazyRow(
                modifier = extra.then(modifierOf(o)),
                horizontalArrangement = hArrangementOf(o),
                contentPadding = paddingOf(o)
            ) {
                items(count) { index ->
                    ListItemContent(store, body, data.getOrNull(index), index)
                }
            }
        }

        "LazyVerticalGrid" -> {
            val data = (toPlain(o["items"]) as? List<*>) ?: emptyList<Any?>()
            val count = if (o.containsKey("items")) data.size else intOf(o["count"], 0)
            val cells = gridCellsOf(o)
            LazyVerticalGrid(
                columns = cells,
                modifier = extra.then(modifierOf(o)),
                verticalArrangement = vArrangementOf(o),
                horizontalArrangement = hArrangementOf(o),
                contentPadding = paddingOf(o)
            ) {
                items(count) { index ->
                    ListItemContent(store, body, data.getOrNull(index), index)
                }
            }
        }

        "LazyHorizontalGrid" -> {
            val data = (toPlain(o["items"]) as? List<*>) ?: emptyList<Any?>()
            val count = if (o.containsKey("items")) data.size else intOf(o["count"], 0)
            LazyHorizontalGrid(
                rows = gridCellsOf(o),
                modifier = extra.then(modifierOf(o)),
                verticalArrangement = vArrangementOf(o),
                horizontalArrangement = hArrangementOf(o),
                contentPadding = paddingOf(o)
            ) {
                items(count) { index ->
                    ListItemContent(store, body, data.getOrNull(index), index)
                }
            }
        }

        // ---- 文本与图形 ----
        "Text" -> Text(
            text = textOf(o),
            modifier = extra.then(modifierOf(o, sizeAsDp = false)),
            color = colorOf(o["color"]) ?: Color.Unspecified,
            fontSize = spOf(o["size"]),
            fontWeight = if (boolOf(o["bold"])) FontWeight.Bold else null,
            textAlign = textAlignOf(o["align"]),
            maxLines = intOf(o["maxLines"], Int.MAX_VALUE),
            overflow = overflowOf(o)
        )

        "Icon" -> {
            val id = drawableId(ctx, o["icon"] ?: o["res"] ?: o["src"])
            if (id != 0) {
                Icon(
                    painter = painterResource(id),
                    contentDescription = o["desc"]?.toString(),
                    modifier = extra.then(modifierOf(o)),
                    tint = colorOf(o["color"]) ?: LocalContentColor.current
                )
            } else {
                // 没有可用图片资源时退化成文字,避免 painterResource 直接崩。
                Text(
                    text = textOf(o).ifEmpty { o["icon"]?.toString() ?: "" },
                    modifier = extra.then(modifierOf(o, sizeAsDp = false)),
                    fontSize = spOf(o["size"]),
                    color = colorOf(o["color"]) ?: Color.Unspecified
                )
            }
        }

        "Image" -> {
            val id = drawableId(ctx, o["src"] ?: o["res"] ?: o["icon"])
            if (id != 0) {
                Image(
                    painter = painterResource(id),
                    contentDescription = o["desc"]?.toString(),
                    modifier = extra.then(modifierOf(o)),
                    contentScale = contentScaleOf(o)
                )
            } else {
                Box(modifier = extra.then(modifierOf(o)), contentAlignment = Alignment.Center) { Text("[image]") }
            }
        }

        "Spacer" -> Spacer(modifier = extra.then(modifierOf(o)))

        "Divider" -> HorizontalDivider(modifier = extra.then(modifierOf(o)))

        "VerticalDivider" -> VerticalDivider(modifier = extra.then(modifierOf(o)))

        // 只传 modifier / color:strokeWidth 在 Material3 1.4 系列里经历过
        // "废弃 → 改名为 stroke(StrokeCap)" 的变动,传了可能直接编译失败。
        // 这两个参数在所有版本上都稳定。
        "CircularProgressIndicator" -> CircularProgressIndicator(
            modifier = extra.then(modifierOf(o)),
            color = colorOf(o["color"]) ?: MaterialTheme.colorScheme.primary
        )

        "LinearProgressIndicator" -> LinearProgressIndicator(
            modifier = extra.then(modifierOf(o)),
            color = colorOf(o["color"]) ?: MaterialTheme.colorScheme.primary
        )

        "Badge" -> Badge(modifier = extra.then(modifierOf(o))) { SlotContent(store, body, o) }

        "BadgedBox" -> {
            val badge = o["badge"] as? LuaObject
            BadgedBox(
                badge = { if (badge != null) LuaGroup(store.namedChild("badge"), badge) },
                modifier = extra.then(modifierOf(o))
            ) { SlotContent(store, body, o) }
        }

        // ---- 按钮族 ----
        "Button" -> Button(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        ) { SlotContent(store, body, o) }

        "TextButton" -> TextButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        ) { SlotContent(store, body, o) }

        "OutlinedButton" -> OutlinedButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        ) { SlotContent(store, body, o) }

        "ElevatedButton" -> ElevatedButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        ) { SlotContent(store, body, o) }

        "FilledTonalButton" -> FilledTonalButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        ) { SlotContent(store, body, o) }

        "IconButton" -> IconButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        ) { SlotContent(store, body, o) }

        "FilledIconButton" -> FilledIconButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        ) { SlotContent(store, body, o) }

        "OutlinedIconButton" -> OutlinedIconButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        ) { SlotContent(store, body, o) }

        "FloatingActionButton" -> FloatingActionButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            shape = shapeOf(o),
            containerColor = colorOf(o["background"]) ?: MaterialTheme.colorScheme.primaryContainer
        ) { SlotContent(store, body, o) }

        "SmallFloatingActionButton" -> SmallFloatingActionButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            shape = shapeOf(o),
            containerColor = colorOf(o["background"]) ?: MaterialTheme.colorScheme.primaryContainer
        ) { SlotContent(store, body, o) }

        "LargeFloatingActionButton" -> LargeFloatingActionButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            shape = shapeOf(o),
            containerColor = colorOf(o["background"]) ?: MaterialTheme.colorScheme.primaryContainer
        ) { SlotContent(store, body, o) }

        "ExtendedFloatingActionButton" -> ExtendedFloatingActionButton(
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            shape = shapeOf(o),
            containerColor = colorOf(o["background"]) ?: MaterialTheme.colorScheme.primaryContainer
        ) { SlotContent(store, body, o) }

        // ---- 选择与输入 ----
        "Switch" -> Switch(
            checked = boolOf(o["checked"]),
            onCheckedChange = { v -> callLuaWith(o["onChange"], v) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        )

        "Checkbox" -> Checkbox(
            checked = boolOf(o["checked"]),
            onCheckedChange = { v -> callLuaWith(o["onChange"], v) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        )

        "RadioButton" -> RadioButton(
            selected = boolOf(o["checked"]),
            onClick = { callLua(o["onClick"] ?: o["onChange"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        )

        "Slider" -> Slider(
            value = floatOf(o["value"], 0f),
            onValueChange = { v -> callLuaWith(o["onChange"], v) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true),
            valueRange = floatOf(o["min"], 0f)..floatOf(o["max"], 1f)
        )

        "RangeSlider" -> {
            val rs = o["state"] as? LuaRangeState
            val cur = rs?.toRange()
                ?: (floatOf(o["start"], 0f)..floatOf(o["end"], 1f))
            RangeSlider(
                value = cur,
                onValueChange = { r ->
                    rs?.setRange(r.start, r.endInclusive)
                    val cb = o["onChange"] as? LuaObject
                    if (cb != null) {
                        try {
                            cb.call(r.start, r.endInclusive)
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "RangeSlider 回调失败: ${t.message}", t)
                        }
                    }
                },
                modifier = extra.then(modifierOf(o)),
                enabled = boolOf(o["enabled"], true),
                valueRange = floatOf(o["min"], 0f)..floatOf(o["max"], 1f)
            )
        }

        "TextField" -> {
            val labelText = o["label"]?.toString()
            val labelSlot: (@Composable () -> Unit)? =
                if (labelText.isNullOrBlank()) null else ({ Text(labelText) })
            val hintText = o["hint"]?.toString() ?: o["placeholder"]?.toString()
            val hintSlot: (@Composable () -> Unit)? =
                if (hintText.isNullOrBlank()) null else ({ Text(hintText) })
            TextField(
                value = textOf(o),
                onValueChange = { v -> callLuaWith(o["onChange"], v) },
                modifier = extra.then(modifierOf(o)),
                enabled = boolOf(o["enabled"], true),
                singleLine = boolOf(o["singleLine"]),
                isError = boolOf(o["error"]),
                label = labelSlot,
                placeholder = hintSlot
            )
        }

        "OutlinedTextField" -> {
            val labelText = o["label"]?.toString()
            val labelSlot: (@Composable () -> Unit)? =
                if (labelText.isNullOrBlank()) null else ({ Text(labelText) })
            val hintText = o["hint"]?.toString() ?: o["placeholder"]?.toString()
            val hintSlot: (@Composable () -> Unit)? =
                if (hintText.isNullOrBlank()) null else ({ Text(hintText) })
            OutlinedTextField(
                value = textOf(o),
                onValueChange = { v -> callLuaWith(o["onChange"], v) },
                modifier = extra.then(modifierOf(o)),
                enabled = boolOf(o["enabled"], true),
                singleLine = boolOf(o["singleLine"]),
                isError = boolOf(o["error"]),
                label = labelSlot,
                placeholder = hintSlot
            )
        }

        // ---- Chip ----
        "AssistChip" -> AssistChip(
            onClick = { callLua(o["onClick"]) },
            label = { Text(textOf(o)) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        )

        "SuggestionChip" -> SuggestionChip(
            onClick = { callLua(o["onClick"]) },
            label = { Text(textOf(o)) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        )

        "FilterChip" -> FilterChip(
            selected = boolOf(o["selected"] ?: o["checked"]),
            onClick = { callLua(o["onClick"] ?: o["onChange"]) },
            label = { Text(textOf(o)) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        )

        "InputChip" -> InputChip(
            selected = boolOf(o["selected"] ?: o["checked"]),
            onClick = { callLua(o["onClick"] ?: o["onChange"]) },
            label = { Text(textOf(o)) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true)
        )

        // ---- 导航 ----
        "TopAppBar" -> TopAppBar(
            title = { Text(o["title"]?.toString() ?: textOf(o)) },
            modifier = extra.then(modifierOf(o))
        )

        "CenterAlignedTopAppBar" -> CenterAlignedTopAppBar(
            title = { Text(o["title"]?.toString() ?: textOf(o)) },
            modifier = extra.then(modifierOf(o))
        )

        "MediumTopAppBar" -> MediumTopAppBar(
            title = { Text(o["title"]?.toString() ?: textOf(o)) },
            modifier = extra.then(modifierOf(o))
        )

        "LargeTopAppBar" -> LargeTopAppBar(
            title = { Text(o["title"]?.toString() ?: textOf(o)) },
            modifier = extra.then(modifierOf(o))
        )

        "BottomAppBar" -> BottomAppBar(modifier = extra.then(modifierOf(o))) { SlotContent(store, body, o) }

        // 内容槽必须走 RowScope 渲染器:NavigationBarItem 是 RowScope 扩展函数,
        // 只有在这里子节点才拿得到 RowScope 接收者。
        "NavigationBar" -> NavigationBar(modifier = extra.then(modifierOf(o))) { LuaChildrenRow(store, body) }

        // 同上,NavigationRailItem 是 ColumnScope 扩展函数。
        "NavigationRail" -> NavigationRail(modifier = extra.then(modifierOf(o))) { LuaChildrenColumn(store, body) }

        "TabRow", "ScrollableTabRow" -> {
            val tabs = (toPlain(o["tabs"]) as? List<*>) ?: emptyList<Any?>()
            val selected = intOf(o["selected"], 0)
            if (node.name == "TabRow") {
                PrimaryTabRow(selectedTabIndex = selected, modifier = extra.then(modifierOf(o))) {
                    tabs.forEachIndexed { i, t ->
                        Tab(
                            selected = i == selected,
                            onClick = { callLuaWith(o["onSelect"], (i + 1).toDouble()) },
                            text = { Text(tabTitle(t)) }
                        )
                    }
                }
            } else {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selected,
                    modifier = extra.then(modifierOf(o))
                ) {
                    tabs.forEachIndexed { i, t ->
                        Tab(
                            selected = i == selected,
                            onClick = { callLuaWith(o["onSelect"], (i + 1).toDouble()) },
                            text = { Text(tabTitle(t)) }
                        )
                    }
                }
            }
        }

        "Tab" -> Tab(
            selected = boolOf(o["selected"]),
            onClick = { callLua(o["onClick"]) },
            modifier = extra.then(modifierOf(o)),
            enabled = boolOf(o["enabled"], true),
            text = { Text(textOf(o)) }
        )

        // ---- 弹窗 ----
        "AlertDialog" -> {
            if (boolOf(o["show"], true)) {
                val dismissBody = o["dismiss"] as? LuaObject
                val dismissSlot: (@Composable () -> Unit)? =
                    if (dismissBody == null) null else ({ LuaGroup(store.namedChild("dismiss"), dismissBody) })
                val titleText = o["title"]?.toString()
                val titleSlot: (@Composable () -> Unit)? =
                    if (titleText.isNullOrBlank()) null else ({ Text(titleText) })
                val plainText = o["text"]?.toString()
                AlertDialog(
                    onDismissRequest = { callLua(o["onDismiss"]) },
                    confirmButton = {
                        val cb = o["confirm"] as? LuaObject
                        if (cb != null) {
                            LuaGroup(store.namedChild("confirm"), cb)
                        } else {
                            TextButton(onClick = { callLua(o["onConfirm"]) }) { Text("确定") }
                        }
                    },
                    modifier = extra.then(modifierOf(o)),
                    dismissButton = dismissSlot,
                    title = titleSlot,
                    text = if (body != null) {
                        ({ SlotContent(store, body, o) })
                    } else if (!plainText.isNullOrBlank()) {
                        ({ Text(plainText) })
                    } else {
                        null
                    }
                )
            }
        }

        "Dialog" -> {
            if (boolOf(o["show"], true)) {
                Dialog(
                    onDismissRequest = { callLua(o["onDismiss"]) },
                    properties = dialogPropertiesOf(o)
                ) {
                    Surface(shape = shapeOf(o), color = colorOf(o["background"]) ?: MaterialTheme.colorScheme.surface) {
                        SlotContent(store, body, o)
                    }
                }
            }
        }

        // ---- 原生 View 互操作 ----
        "AndroidView" -> {
            val direct: View? = javaViewOf(o["view"])
            val factory = o["factory"] as? LuaObject
            val onUpdate = o["onUpdate"] as? LuaObject
            AndroidView(
                factory = { c ->
                    direct ?: (factory?.let { f ->
                        try {
                            f.call() as? View
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "AndroidView factory 失败: ${t.message}", t)
                            null
                        }
                    }) ?: View(c)
                },
                modifier = extra.then(modifierOf(o)),
                update = { v ->
                    if (onUpdate != null) {
                        try {
                            onUpdate.call(v)
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "AndroidView onUpdate 失败: ${t.message}", t)
                        }
                    }
                }
            )
        }

        // ---- 副作用 ----
        "LaunchedEffect" -> {
            val effectKey: Any = o["key"] ?: "default"
            LaunchedEffect(effectKey) { callLua(body) }
        }

        else -> Text(
            text = "未支持的组件: ${node.name}(可用:${NODE_NAMES.joinToString("/")})",
            color = Color(0xFFB3261E),
            modifier = Modifier.padding(4.dp)
        )
    }
}

/** 容器内容:优先渲染 Lua 闭包,其次回退到 `text` 属性。 */
@Composable
private fun SlotContent(store: SlotStore, body: LuaObject?, o: Map<String, Any?>) {
    if (body != null) {
        store.reset()
        LuaGroup(store, body)
    } else {
        val label = textOf(o)
        if (label.isNotEmpty()) Text(label)
    }
}

// ---------------------------------------------------------------------------
// 属性转换(全是普通函数,可在组合期任意位置调用)
// ---------------------------------------------------------------------------

private fun textOf(o: Map<String, Any?>): String = when (val v = o["text"]) {
    null -> ""
    is String -> v
    is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    is Number -> v.toString()
    is LuaObject -> if (v.isString) v.getString() else ""
    else -> v.toString()
}

private fun boolOf(v: Any?, default: Boolean = false): Boolean = when (v) {
    null -> default
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v.equals("true", true) || v == "1"
    else -> default
}

private fun intOf(v: Any?, default: Int): Int = when (v) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull() ?: default
    else -> default
}

private fun floatOf(v: Any?, default: Float): Float = when (v) {
    is Number -> v.toFloat()
    is String -> v.trim().toFloatOrNull() ?: default
    else -> default
}

/** 宽松取浮点:Lua 数字、Java 数字、数字字符串都接受。 */
private fun floatOr(v: Any?, default: Float): Float = when (v) {
    null -> default
    is Number -> v.toFloat()
    is String -> v.trim().toFloatOrNull() ?: default
    is LuaObject -> if (v.isNumber) v.getNumber().toFloat() else default
    else -> default
}

private fun spOf(v: Any?): TextUnit = when (v) {
    is Number -> v.toFloat().sp
    is String -> v.trim().toFloatOrNull()?.sp ?: TextUnit.Unspecified
    else -> TextUnit.Unspecified
}

/** 颜色:接受 Lua 数字 `0xAARRGGBB`、`"#RRGGBB"`、`"#AARRGGBB"`。 */
private fun colorOf(v: Any?): Color? = when (v) {
    null -> null
    is Color -> v
    is Number -> Color(v.toLong().toInt())
    is String -> parseColor(v)?.let { Color(it) }
    else -> null
}

private fun parseColor(s: String): Int? {
    val t = s.trim().removePrefix("#")
    return try {
        when (t.length) {
            6 -> (0xFF000000L or t.toLong(16)).toInt()
            8 -> t.toLong(16).toInt()
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

@Composable
private fun shapeOf(o: Map<String, Any?>): Shape {
    val r = floatOf(o["corner"], 0f)
    return if (r <= 0f) MaterialTheme.shapes.medium else RoundedCornerShape(r.dp)
}

private fun hArrangementOf(o: Map<String, Any?>): Arrangement.Horizontal {
    when (str(o["space"])) {
        "between" -> return Arrangement.SpaceBetween
        "around" -> return Arrangement.SpaceAround
        "evenly" -> return Arrangement.SpaceEvenly
        "center" -> return Arrangement.Center
        "end" -> return Arrangement.End
    }
    val g = floatOf(o["gap"], 0f)
    return if (g <= 0f) Arrangement.Start else Arrangement.spacedBy(g.dp)
}

private fun vArrangementOf(o: Map<String, Any?>): Arrangement.Vertical {
    when (str(o["space"])) {
        "between" -> return Arrangement.SpaceBetween
        "around" -> return Arrangement.SpaceAround
        "evenly" -> return Arrangement.SpaceEvenly
        "center" -> return Arrangement.Center
        "bottom" -> return Arrangement.Bottom
    }
    val g = floatOf(o["gap"], 0f)
    return if (g <= 0f) Arrangement.Top else Arrangement.spacedBy(g.dp, Alignment.Top)
}

private fun hAlignmentOf(o: Map<String, Any?>): Alignment.Horizontal = when (str(o["align"])) {
    "center" -> Alignment.CenterHorizontally
    "end", "right" -> Alignment.End
    else -> Alignment.Start
}

private fun vAlignmentOf(o: Map<String, Any?>): Alignment.Vertical = when (str(o["align"])) {
    "center" -> Alignment.CenterVertically
    "bottom" -> Alignment.Bottom
    else -> Alignment.Top
}

private fun alignmentOf(o: Map<String, Any?>): Alignment = when (str(o["align"])) {
    "topStart" -> Alignment.TopStart
    "topCenter" -> Alignment.TopCenter
    "topEnd" -> Alignment.TopEnd
    "centerStart" -> Alignment.CenterStart
    "centerEnd" -> Alignment.CenterEnd
    "bottomStart" -> Alignment.BottomStart
    "bottomCenter" -> Alignment.BottomCenter
    "bottomEnd" -> Alignment.BottomEnd
    else -> Alignment.Center
}

private fun textAlignOf(v: Any?): TextAlign? = when (str(v)) {
    "center" -> TextAlign.Center
    "end", "right" -> TextAlign.End
    "start", "left" -> TextAlign.Start
    else -> null
}

/**
 * `overflow`:只映射 `ellipsis` / `clip`。
 *
 * `TextOverflow.Visible` 在部分 compose-ui 版本上带实验性注解,传了就得额外 opt-in;
 * 而它在 Material3 组件里的作用与本桥的定位(描述型 UI)关系不大,故不暴露 ——
 * 未知取值一律回落到 `Clip`,不会因为写了个没见过的值就崩。
 */
private fun overflowOf(o: Map<String, Any?>): TextOverflow = when (str(o["overflow"])) {
    "ellipsis" -> TextOverflow.Ellipsis
    else -> TextOverflow.Clip
}

private fun contentScaleOf(o: Map<String, Any?>): ContentScale = when (str(o["scale"] ?: o["contentScale"])) {
    "fit" -> ContentScale.Fit
    "crop" -> ContentScale.Crop
    "fillbounds", "fill" -> ContentScale.FillBounds
    "fillwidth" -> ContentScale.FillWidth
    "fillheight" -> ContentScale.FillHeight
    "inside" -> ContentScale.Inside
    "none" -> ContentScale.None
    else -> ContentScale.Fit
}

/** LazyColumn/LazyRow 的 `contentPadding`。 */
private fun paddingOf(o: Map<String, Any?>): PaddingValues {
    val all = floatOf(o["contentPadding"], 0f)
    if (all > 0f) return PaddingValues(all.dp)
    val h = floatOf(o["contentPaddingH"], 0f)
    val v = floatOf(o["contentPaddingV"], 0f)
    return PaddingValues(horizontal = h.dp, vertical = v.dp)
}

/**
 * 网格列/行数。两种语义**不重叠**,避免"这个数字是列数还是宽度"的歧义:
 *
 * - `minSize = 120`(或 `cellSize`)→ `Adaptive(120.dp)`,按可用宽度自适应列数
 * - 否则 `columns = 3`(横向网格用 `rows`)→ 固定 3 列
 */
private fun gridCellsOf(o: Map<String, Any?>): GridCells {
    val minSize = floatOf(o["minSize"] ?: o["cellSize"], 0f)
    if (minSize > 0f) return GridCells.Adaptive(minSize.dp)
    val n = intOf(o["columns"] ?: o["rows"], 2)
    return GridCells.Fixed(if (n <= 0) 1 else n)
}

/**
 * 把 `"icon"` / `"res"` 之类的属性解析成 drawable / mipmap 资源 id;失败返回 0。
 *
 * 三种写法都接受:
 * - `R.drawable.xxx` 的数值(Lua 数字,LuaJava 会转成 Integer/Double)
 * - `"icon"` 资源名(依次在 drawable / mipmap / raw 里找)
 * - `"android:drawable/x"` 带包名
 */
private fun drawableId(ctx: Context, v: Any?): Int {
    // 数字直接就是资源 id,别退化成"名字解析"——那会返回 0 让 Icon 变成文字。
    if (v is Number) {
        val id = v.toInt()
        return if (id != 0) id else 0
    }
    val name = (v as? String)?.trim().orEmpty()
    if (name.isEmpty()) return 0
    // 纯数字字符串:同样直接当资源 id 用
    name.toIntOrNull()?.let { return it }
    val pkg = ctx.packageName
    for (type in listOf("drawable", "mipmap", "raw")) {
        val id = ctx.resources.getIdentifier(name, type, pkg)
        if (id != 0) return id
    }
    // 允许 `"android:drawable/x"` 这类带包名的写法
    if (name.contains(':')) {
        val p = name.substringBefore(':')
        val rest = name.substringAfter(':')
        val type = rest.substringBefore('/')
        val entry = rest.substringAfter('/', "")
        if (entry.isNotEmpty()) return ctx.resources.getIdentifier(entry, type, p)
    }
    return 0
}

/** 从 Lua 值里取出一个真实 Android View(用于 `AndroidView({ view = ... })`)。 */
private fun javaViewOf(v: Any?): View? = when (v) {
    null -> null
    is View -> v
    is LuaObject -> try {
        if (v.isJavaObject) v.getObject() as? View else null
    } catch (_: Throwable) {
        null
    }
    else -> null
}

/** TabRow 的 tab 可以写字符串,也可以写 `{ title = "..." }`。 */
private fun tabTitle(v: Any?): String = when (v) {
    null -> ""
    is Map<*, *> -> v["title"]?.toString() ?: v["text"]?.toString() ?: ""
    else -> v.toString()
}

private fun dialogPropertiesOf(o: Map<String, Any?>): androidx.compose.ui.window.DialogProperties =
    androidx.compose.ui.window.DialogProperties(
        dismissOnBackPress = boolOf(o["dismissOnBack"], true),
        dismissOnClickOutside = boolOf(o["dismissOnClickOutside"], true),
        usePlatformDefaultWidth = boolOf(o["usePlatformDefaultWidth"], true)
    )

private fun str(v: Any?): String = v?.toString()?.trim()?.lowercase() ?: ""

/**
 * 由属性表构造 Modifier。
 *
 * 顺序有讲究:`background` / `clip` / `border` 必须在 `padding` **之前**,
 * 否则背景只覆盖内容区、圆角裁不到内边距,视觉上会露白边。
 * `clickable` 放最后,保证点击区域包含内边距。
 */
@Composable
private fun modifierOf(o: Map<String, Any?>, sizeAsDp: Boolean = true): Modifier {
    var m: Modifier = Modifier

    // ---- 尺寸 ----
    val w = floatOf(o["width"], 0f)
    if (w > 0f) m = m.width(w.dp)
    val h = floatOf(o["height"], 0f)
    if (h > 0f) m = m.height(h.dp)
    // `size` 在属性表里有两种语义,必须由调用方声明是哪种:
    // - 尺寸(dp):Spacer / Box / Image 等,限制控件本身大小;
    // - 字号(sp):Text / Icon 等,由 spOf 单独消费。
    // 早期版本无条件按 dp 处理,导致 `Text("hi", size = 20)` 同时被加上
    // `.size(20.dp)`,文本被挤进 20dp 的方块里换行截断 —— 布局看起来"显示不全"。
    val s = if (sizeAsDp) floatOf(o["size"], 0f) else 0f
    if (s > 0f) m = m.size(s.dp)
    if (boolOf(o["fillMaxWidth"])) m = m.fillMaxWidth(floatOf(o["fillMaxWidthFraction"], 1f))
    if (boolOf(o["fillMaxHeight"])) m = m.fillMaxHeight(floatOf(o["fillMaxHeightFraction"], 1f))
    if (boolOf(o["fillMaxSize"])) m = m.fillMaxSize()
    val ratio = floatOf(o["aspectRatio"], 0f)
    if (ratio > 0f) m = m.aspectRatio(ratio)

    // ---- 位移 ----
    val dx = floatOf(o["offsetX"], 0f)
    val dy = floatOf(o["offsetY"], 0f)
    if (dx != 0f || dy != 0f) m = m.offset(dx.dp, dy.dp)

    // ---- 视觉(必须在 padding 之前) ----
    val corner = floatOf(o["corner"], 0f)
    if (corner > 0f) m = m.clip(RoundedCornerShape(corner.dp))
    colorOf(o["background"])?.let { bg -> m = m.background(bg) }
    val borderW = floatOf(o["borderWidth"], 0f)
    colorOf(o["borderColor"])?.let { bc ->
        val bw = if (borderW > 0f) borderW else 1f
        m = m.border(bw.dp, bc, RoundedCornerShape(if (corner > 0f) corner.dp else 0.dp))
    }
    val shadowE = floatOf(o["shadow"], 0f)
    if (shadowE > 0f) m = m.shadow(shadowE.dp)
    // alpha 用 containsKey 判断,而不是 `> 0`:显式写 alpha = 0(完全透明)
    // 是合法需求,按值判断会把它当成"没设置"。
    if (o.containsKey("alpha")) {
        val a = floatOf(o["alpha"], 1f).coerceIn(0f, 1f)
        m = m.alpha(a)
    }

    // ---- 间距 ----
    val pad = floatOf(o["padding"], 0f)
    if (pad > 0f) m = m.padding(pad.dp)
    val padH = floatOf(o["paddingH"], floatOf(o["paddingHorizontal"], 0f))
    val padV = floatOf(o["paddingV"], floatOf(o["paddingVertical"], 0f))
    if (padH > 0f || padV > 0f) m = m.padding(horizontal = padH.dp, vertical = padV.dp)

    // ---- 点击(最后,覆盖含内边距的整个区域) ----
    if (o["onClick"] != null && !boolOf(o["clickableOff"])) {
        m = m.clickable { callLua(o["onClick"]) }
    }
    return m
}

// ---------------------------------------------------------------------------
// Lua 回调
// ---------------------------------------------------------------------------

private fun safeCall(fn: LuaObject): Any? = try {
    fn.call()
} catch (t: Throwable) {
    android.util.Log.e(TAG, "Lua 回调执行失败: ${t.message}", t)
    null
}

/** 事件期调用(不在组合上下文):只允许写状态,不允许再登记节点。 */
private fun callLua(fn: Any?) {
    val f = fn as? LuaObject ?: return
    try {
        if (f.isFunction) f.call()
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Lua 回调执行失败: ${t.message}", t)
    }
}

private fun callLuaWith(fn: Any?, arg: Any?) {
    val f = fn as? LuaObject ?: return
    try {
        if (f.isFunction) f.call(arg)
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Lua 回调执行失败: ${t.message}", t)
    }
}

/**
 * 主线程 Handler,只建一次。
 *
 * 早期版本每次调用都 `Handler(Looper.getMainLooper())`:Handler 与它的 Looper
 * 绑定,而主线程 Looper 是进程级的,新建的 Handler 对象本身并不泄漏什么 ——
 * 但 `composeContent(fn)` 每次执行都会分配一个新对象,而这类入口常在
 * 循环/回调里被反复调用。复用一个实例是零成本的,顺手去掉这份分配。
 */
private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block()
    else mainHandler.post { block() }
}
