package com.luaforge.studio.langs.lua.tools

import android.content.Context
import com.luaforge.studio.R
import com.luaforge.studio.utils.DexClassReader
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object PackageUtil {
    private var packages: JSONObject? = null
    private val classMap = mutableMapOf<String, MutableList<String>>()
    private val classNames = mutableListOf<String>()

    /**
     * 加载 Android SDK 类信息
     *
     * @param context Android 上下文
     * @return 类名映射表
     */
    @JvmStatic
    fun load(context: Context): Map<String, List<String>> {
        if (packages != null) {
            return classMap
        }

        try {
            // 从原始资源加载。use{} 保证异常路径也关闭:
            // android 类库清单有数 MB,解析中途抛异常若不关流会泄漏 fd,
            // 而补全数据加载失败后还会被反复重试。
            val content = StringBuilder()
            context.resources.openRawResource(R.raw.android).use { inputStream ->
                BufferedReader(
                    InputStreamReader(inputStream, StandardCharsets.UTF_8)
                ).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        content.append(line).append("\n")
                    }
                }
            }

            initializePackages(context, content.toString())

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return classMap
    }

    /**
     * 从指定路径加载类信息
     *
     * @param context Android 上下文
     * @param path 自定义路径
     */
    @JvmStatic
    fun load(context: Context, path: String) {
        if (packages != null) return

        try {
            val file = File(path)
            if (file.exists()) {
                val content = StringBuilder()
                BufferedReader(
                    InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)
                ).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        content.append(line).append("\n")
                    }
                }

                initializePackages(context, content.toString())
            }
        } catch (e: Exception) {
            // 如果自定义路径加载失败，回退到默认加载
            load(context)
        }
    }

    /**
     * 初始化包结构信息
     */
    private fun initializePackages(context: Context, jsonContent: String) {
        try {
            packages = JSONObject(jsonContent)

            // 扫描 Dex 文件获取所有类名。
            // 不用 DexFile(String)+entries():两者在 API 34+ 已弃用,
            // 且 entries() 会走 dexopt,这里只需要类名,直接解析 dex 字符串池更快。
            for (className in DexClassReader.readClasses(File(context.packageCodePath))) {
                // 在这里过滤掉 Compose 相关类
                if (shouldSkipClass(className)) {
                    continue
                }

                var current = packages!!
                val parts = className.split("\\.".toRegex())

                // 构建包结构树
                for (part in parts) {
                    if (current.has(part)) {
                        current = current.getJSONObject(part)
                    } else {
                        val newObj = JSONObject()
                        current.put(part, newObj)
                        current = newObj
                    }
                }
            }

            // 构建导入映射
            buildImports(packages!!, "")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 检查是否应该跳过某个类
     *
     * @param className 类名
     * @return 如果应该跳过则返回 true
     */
    private fun shouldSkipClass(className: String): Boolean {
        if (className.isEmpty()) {
            return true
        }

        // 过滤所有 Compose 相关类
        val lowerClassName = className.lowercase()
        if (lowerClassName.contains("compose")) {
            println("PackageUtil: Skipping Compose class: $className")
            return true
        }

        return false
    }

    /**
     * 递归构建导入映射表
     */
    private fun buildImports(json: JSONObject, pkg: String) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            try {
                val subJson = json.getJSONObject(key)
                if (key.isNotEmpty() && key[0].isUpperCase()) {
                    // 使用 getOrPut 替代 computeIfAbsent
                    classMap.getOrPut(key) { mutableListOf() }.add(pkg + key)
                }
                if (subJson.length() == 0) {
                    classNames.add(key)
                } else {
                    buildImports(subJson, pkg + key + ".")
                }
            } catch (e: Exception) {
                // 忽略解析错误
            }
        }
    }

    /**
     * 根据短类名查找完整类名列表
     *
     * @param name 短类名
     * @return 完整类名列表，未找到返回 null
     */
    @JvmStatic
    fun fix(name: String): List<String>? {
        return classMap[name]
    }

    /**
     * 获取所有类名列表
     */
    @JvmStatic
    fun getClassNames(): List<String> {
        return classNames
    }

    /**
     * 获取类名映射表
     */
    @JvmStatic
    fun getClassMap(): Map<String, List<String>> {
        return classMap
    }
}