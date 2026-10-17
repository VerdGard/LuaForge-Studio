package com.luaforge.studio.utils

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject

@Keep
object JsonUtil {

    @JvmStatic
    fun parseObject(jsonString: String): Map<String, Any?> {
        val json = JSONObject(jsonString)
        return jsonToMap(json)
    }

    @JvmStatic
    fun parseArray(jsonString: String): List<Any?> {
        val json = JSONArray(jsonString)
        return jsonToList(json)
    }

    /**
     * JSON 值转 [Map]。返回 null 表示该值不是对象。
     *
     * `as? Map<String, Any?>` 是 unchecked cast:泛型参数在运行时被擦除,
     * 编译器无法证明元素类型。这里做唯一的收口点并就地抑制,
     * 避免调用点各写一遍 `@Suppress`,
     * 也让"JSON 解析结果是 Map/List 的嵌套结构"这一前提只在一处声明。
     */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun asMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    /** 同 [asMap],但返回可变副本,便于就地修改后写回。 */
    @JvmStatic
    fun asMutableMap(value: Any?): MutableMap<String, Any?>? = asMap(value)?.toMutableMap()

    /** JSON 值转 `List<Map>`。返回 null 表示该值不是数组。 */
    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun asMapList(value: Any?): List<Map<String, Any?>>? = value as? List<Map<String, Any?>>

    @JvmStatic
    fun toFormattedString(map: Map<String, Any?>, indentSpaces: Int = 4): String {
        return JSONObject(map).toString(indentSpaces)
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            map[key] = convertJsonValue(value)
        }
        return map
    }

    private fun jsonToList(json: JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until json.length()) {
            val value = json.get(i)
            list.add(convertJsonValue(value))
        }
        return list
    }

    private fun convertJsonValue(value: Any): Any? {
        return when (value) {
            is JSONObject -> jsonToMap(value)
            is JSONArray -> jsonToList(value)
            JSONObject.NULL -> null
            else -> value
        }
    }
}