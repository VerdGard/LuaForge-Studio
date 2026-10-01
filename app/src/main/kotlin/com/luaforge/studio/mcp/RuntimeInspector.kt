package com.luaforge.studio.mcp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行时界面检查器。
 *
 * 用于在“调试运行”之后验证屏幕是否符合预期:把当前前台 Activity 的界面树
 * dump 成 JSON,并按期望文本/控件数量/错误弹窗做断言,让 MCP 客户端可以
 * 闭环判断“布局是否真的加载成功”,而不只是看进程是否起来。
 */
object RuntimeInspector {

    /** 单次 dump 的节点上限,避免超大界面拖垮响应。 */
    private const val MAX_NODES = 3000

    /** 命中这些文本通常意味着运行时错误弹窗。 */
    private val errorMarkers = arrayOf(
        "runtime error", "is not a field", "layout loading error",
        "error loading child view", "traceback", "语法错误", "运行时错误"
    )

    // ------------------------------------------------------------------
    // 界面树
    // ------------------------------------------------------------------

    fun dumpScreen(
        activity: Activity,
        includeInvisible: Boolean = false,
        maxDepth: Int = 40
    ): JSONObject {
        val decor = activity.window?.decorView
            ?: return JSONObject().put("error", "当前 Activity 没有 decorView")

        val counter = intArrayOf(0)
        val tree = buildNode(decor, 0, maxDepth, includeInvisible, counter)

        return JSONObject()
            .put("activity", activity.javaClass.name)
            .put("nodeCount", counter[0])
            .put("truncated", counter[0] >= MAX_NODES)
            .put("tree", tree ?: JSONObject.NULL)
    }

    private fun buildNode(
        view: View,
        depth: Int,
        maxDepth: Int,
        includeInvisible: Boolean,
        counter: IntArray
    ): JSONObject? {
        if (counter[0] >= MAX_NODES) return null

        val visible = view.visibility == View.VISIBLE
        if (!includeInvisible && !visible) return null

        counter[0]++
        val node = JSONObject()
        node.put("class", view.javaClass.simpleName)
        describeId(view)?.let { node.put("id", it) }

        (view as? TextView)?.text?.toString()?.takeIf { it.isNotEmpty() }
            ?.let { node.put("text", it) }
        view.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
            ?.let { node.put("contentDescription", it) }

        node.put("visible", visible)
        node.put("enabled", view.isEnabled)
        if (view.isClickable) node.put("clickable", true)

        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        node.put("bounds", JSONArray(listOf(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)))

        if (view is ViewGroup && depth < maxDepth) {
            val children = JSONArray()
            for (i in 0 until view.childCount) {
                buildNode(view.getChildAt(i), depth + 1, maxDepth, includeInvisible, counter)
                    ?.let { children.put(it) }
            }
            if (children.length() > 0) node.put("children", children)
        }
        return node
    }

    private fun describeId(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        // 资源名可能不存在(动态生成的 id),此时退回数值
        return try {
            view.resources.getResourceEntryName(id)
        } catch (_: Exception) {
            "@$id"
        }
    }

    // ------------------------------------------------------------------
    // 收集可见文本
    // ------------------------------------------------------------------

    /**
     * 收集当前界面的可见文本(供 wait_for_text 等轮询使用)。
     *
     * @return 界面未就绪时返回 null,便于调用方区分"无文本"与"没有 Activity"
     */
    fun collectVisibleTexts(activity: Activity, includeInvisible: Boolean = false): List<String>? {
        val decor = activity.window?.decorView ?: return null
        val texts = ArrayList<String>()
        collectTexts(decor, texts, includeInvisible)
        return texts
    }

    private fun collectTexts(
        view: View,
        out: MutableList<String>,
        includeInvisible: Boolean
    ) {
        val visible = view.visibility == View.VISIBLE
        if (includeInvisible || visible) {
            (view as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    collectTexts(view.getChildAt(i), out, includeInvisible)
                }
            }
        }
    }

    private fun countNodes(view: View, includeInvisible: Boolean): Int {
        val visible = view.visibility == View.VISIBLE
        if (!includeInvisible && !visible) return 0
        var n = 1
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                n += countNodes(view.getChildAt(i), includeInvisible)
            }
        }
        return n
    }

    // ------------------------------------------------------------------
    // 预期校验
    // ------------------------------------------------------------------

    /**
     * 按预期校验当前屏幕。
     *
     * @param expectTexts  全部必须出现的文本(子串匹配)
     * @param expectAnyOf  至少出现一个的文本
     * @param absentTexts  必须不出现的文本
     * @param minViews     可见控件数量下限
     */
    fun checkScreen(
        activity: Activity,
        expectTexts: List<String>,
        expectAnyOf: List<String>,
        absentTexts: List<String>,
        minViews: Int?,
        includeInvisible: Boolean
    ): JSONObject {
        val texts = ArrayList<String>()
        activity.window?.decorView?.let { collectTexts(it, texts, includeInvisible) }
        val joined = texts.joinToString("\n")
        val lowerJoined = joined.lowercase()

        val failures = JSONArray()
        val passedChecks = JSONArray()

        expectTexts.forEach { want ->
            if (want.isNotEmpty() && joined.contains(want)) {
                passedChecks.put("包含文本: $want")
            } else {
                failures.put("缺少文本: $want")
            }
        }

        if (expectAnyOf.isNotEmpty()) {
            if (expectAnyOf.any { it.isNotEmpty() && joined.contains(it) }) {
                passedChecks.put("命中任一文本: ${expectAnyOf.joinToString(" / ")}")
            } else {
                failures.put("未命中任一文本: ${expectAnyOf.joinToString(" / ")}")
            }
        }

        absentTexts.forEach { unwanted ->
            if (unwanted.isNotEmpty() && joined.contains(unwanted)) {
                failures.put("出现不应存在的文本: $unwanted")
            } else {
                passedChecks.put("未出现文本: $unwanted")
            }
        }

        if (minViews != null) {
            val visibleCount = activity.window?.decorView?.let { countNodes(it, false) } ?: 0
            if (visibleCount >= minViews) {
                passedChecks.put("可见控件数 $visibleCount >= $minViews")
            } else {
                failures.put("可见控件数 $visibleCount < $minViews")
            }
        }

        // 运行时错误弹窗自动识别
        val detected = errorMarkers.filter { lowerJoined.contains(it) }

        return JSONObject()
            .put("passed", failures.length() == 0)
            .put("failures", failures)
            .put("passedChecks", passedChecks)
            .put("detectedErrorMarkers", JSONArray(detected))
            .put("textCount", texts.size)
            .put("texts", JSONArray(texts))
    }
}
